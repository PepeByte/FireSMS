package com.firesms.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.SplitOffRecord
import com.firesms.app.data.local.SplitOffStatus
import com.firesms.app.data.local.decodeSplitOffs
import com.firesms.app.data.local.encodeSplitOffs
import com.firesms.app.data.local.entity.SmsLog
import com.firesms.app.data.local.newSplitOffExternalId
import com.firesms.app.data.local.decodeTransactionSnapshot
import com.firesms.app.data.local.encode
import com.firesms.app.data.local.semanticallyEquals
import com.firesms.app.data.local.toStoredSnapshot
import com.firesms.app.data.remote.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

data class EditRowState(
    /** Local identity so the UI keeps per-row state (focus, menus) attached to the right split. */
    val key: Long = 0,
    val transactionJournalId: String? = null,
    val amount: String = "",
    val description: String = "",
    val categoryName: String = "",
    val budgetName: String = "",
    val notes: String = "",
    val amountError: String? = null
)

/** The user-editable part of the form, used to tell whether anything changed since loading. */
data class EditableFields(
    val groupTitle: String,
    val transactionType: String,
    val transactionDate: String,
    val sourceName: String,
    val destinationName: String,
    val rows: List<EditRowState>,
    val splitOffs: List<SplitOffState> = emptyList(),
    val deletedSplitOffIds: List<String> = emptyList()
)

/** A split the user just removed, kept so a snackbar can put it back. [id] makes each removal distinct. */
data class RemovedSplit(val id: Long, val index: Int, val row: EditRowState)

data class EditTransactionState(
    val loading: Boolean = true,
    val error: String? = null,
    val smsLog: SmsLog? = null,
    val fireflyTxId: String? = null,
    val groupTitle: String = "",
    val groupTitleError: String? = null,
    val transactionType: String = "withdrawal",
    val transactionDate: String = "",
    val sourceName: String = "",
    val sourceId: String? = null,
    val destinationName: String = "",
    val destinationId: String? = null,
    val originalTotal: String = "",
    val currencyCode: String = "",
    val currencySymbol: String = "",
    val rows: List<EditRowState> = emptyList(),
    /**
     * The whole payment: the original transaction plus its split-offs, as loaded. While split-offs
     * exist, the original's splits and the split-offs must add up to this.
     */
    val paymentTotal: String = "",
    val splitOffs: List<SplitOffState> = emptyList(),
    /** Stored split-offs the user removed; they are deleted from Firefly on save. */
    val deletedSplitOffs: List<SplitOffState> = emptyList(),
    /** The Firefly address, for "Open in Firefly" links. */
    val fireflyBaseUrl: String = "",
    /** A one-off message for the snackbar, e.g. after removing a split-off. */
    val notice: String? = null,
    val originalSplits: List<FireflyTransactionSplit> = emptyList(),
    val loaded: EditableFields? = null,
    val categorySuggestions: List<String> = emptyList(),
    val budgetSuggestions: List<String> = emptyList(),
    val descriptionSuggestions: List<String> = emptyList(),
    val accountSuggestions: List<String> = emptyList(),
    /** False until the account list arrives, so the "unknown account" hint never shows on a guess. */
    val accountsLoaded: Boolean = false,
    val removedSplit: RemovedSplit? = null,
    val saving: Boolean = false,
    /** A readable, one-line message for the snackbar. */
    val saveError: String? = null,
    /** The raw error behind [saveError], shown on request. */
    val saveErrorDetail: String? = null,
    val saveSuccess: Boolean = false
) {
    val currencyLabel: String get() = currencySymbol.ifBlank { currencyCode }

    fun editableFields() = EditableFields(
        groupTitle = groupTitle,
        transactionType = transactionType,
        transactionDate = transactionDate,
        sourceName = sourceName,
        destinationName = destinationName,
        rows = rows.map { it.copy(amountError = null) },
        splitOffs = splitOffs.map { it.withoutErrors() },
        deletedSplitOffIds = deletedSplitOffs.mapNotNull { it.fireflyId }
    )

    /** What the payer's own splits should add up to: the payment minus its split-offs. */
    fun payerShareTotal(): String {
        val total = parseEditAmount(paymentTotal) ?: return originalTotal
        if (splitOffs.isEmpty()) return total.setScale(2, RoundingMode.HALF_UP).toPlainString()
        val splitOff = splitOffs.sumOf { parseEditAmount(it.amount) ?: BigDecimal.ZERO }
        return total.subtract(splitOff).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    /** Split-offs need a withdrawal or deposit and the SMS hash for their external ids. */
    val canSplitOff: Boolean get() = supportsSplitOff(transactionType) && smsLog != null

    val hasSplitOffs: Boolean get() = splitOffs.isNotEmpty()

    val isDirty: Boolean get() = loaded != null && editableFields() != loaded
}

class EditTransactionViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val smsLogDao = db.smsLogDao()
    private val prefs = (application as FireSmsApp).preferencesManager
    private val mappingResolver = com.firesms.app.domain.parser.TitleMappingResolver(db.titleMappingRuleDao())

    private val _state = MutableStateFlow(EditTransactionState())
    val state: StateFlow<EditTransactionState> = _state.asStateFlow()

    private var nextKey = 1L
    private var nextRemovalId = 1L

    fun updateTransactionField(update: (EditTransactionState) -> EditTransactionState) {
        _state.update(update)
    }

    fun setGroupTitle(value: String) = _state.update { it.copy(groupTitle = value, groupTitleError = null) }

    fun setTransactionType(value: String) = _state.update { it.copy(transactionType = value) }

    fun setTransactionDate(value: String) = _state.update { it.copy(transactionDate = value) }

    fun setSourceName(value: String) = _state.update { it.copy(sourceName = value, sourceId = null) }

    fun setDestinationName(value: String) =
        _state.update { it.copy(destinationName = value, destinationId = null) }

    private var loadedSmsLogId: Long? = null

    fun load(smsLogId: Long?) {
        // The screen calls this again after a configuration change; keep unsaved edits.
        if (smsLogId != null && smsLogId == loadedSmsLogId) return
        loadedSmsLogId = smsLogId
        viewModelScope.launch {
            if (smsLogId == null) {
                _state.update { it.copy(loading = false, error = "Invalid SMS log ID") }
                return@launch
            }

            val log = smsLogDao.getById(smsLogId)
            if (log == null) {
                _state.update { it.copy(loading = false, error = "SMS log not found") }
                return@launch
            }

            val fireflyId = log.fireflyId
            if (fireflyId == null) {
                _state.update {
                    it.copy(loading = false, error = "No Firefly transaction associated with this log")
                }
                return@launch
            }

            val baseUrl = prefs.fireflyUrl.first()
            val token = prefs.fireflyToken.first()
            if (baseUrl.isEmpty() || token.isEmpty()) {
                _state.update { it.copy(loading = false, error = "Firefly not configured") }
                return@launch
            }

            val api = FireflyApi(token)
            try {
                val txResponse = api.getTransaction(baseUrl, fireflyId)
                val remoteSnapshot = txResponse.data.attributes.toStoredSnapshot()
                val originalSnapshot = decodeTransactionSnapshot(log.originalTransactionSnapshotJson)
                val isModified = originalSnapshot?.let { !it.semanticallyEquals(remoteSnapshot) } ?: false
                smsLogDao.updateTransactionSnapshotState(
                    log.id,
                    remoteSnapshot.encode(),
                    if (isModified) log.locallyModifiedAt ?: System.currentTimeMillis() else null
                )

                val originalSplits = txResponse.data.attributes.transactions
                val firstSplit = originalSplits.firstOrNull()
                val stored = loadSplitOffs(api, baseUrl, log, firstSplit?.date.orEmpty())
                val rows = originalSplits.map { it.toRow() }
                val recovered = recoverPayment(
                    groupTotal = splitTotal(originalSplits),
                    rowAmounts = rows.map { it.amount },
                    active = stored.active,
                    creating = stored.creating,
                    removing = stored.removing,
                    removed = stored.removed
                )
                val loadedState = EditTransactionState(
                    loading = false,
                    smsLog = log,
                    fireflyTxId = fireflyId,
                    groupTitle = txResponse.data.attributes.groupTitle ?: "",
                    transactionType = firstSplit?.type?.ifBlank { "withdrawal" } ?: "withdrawal",
                    transactionDate = firstSplit?.date ?: "",
                    sourceName = firstSplit?.sourceName ?: "",
                    sourceId = firstSplit?.sourceId,
                    destinationName = firstSplit?.destinationName ?: "",
                    destinationId = firstSplit?.destinationId,
                    originalTotal = splitTotal(originalSplits),
                    currencyCode = firstSplit?.currencyCode ?: "",
                    currencySymbol = firstSplit?.currencySymbol ?: "",
                    rows = rows,
                    paymentTotal = recovered.paymentTotal,
                    fireflyBaseUrl = baseUrl,
                    splitOffs = recovered.splitOffs,
                    originalSplits = originalSplits
                )
                // "Loaded" is Firefly as it stands, so finishing an interrupted save counts as a change.
                val asStored = loadedState.editableFields()
                val shown = loadedState.copy(
                    rows = rows.mapIndexed { i, row -> row.copy(amount = recovered.rowAmounts[i]) },
                    deletedSplitOffs = recovered.deletedSplitOffs,
                    notice = if (recovered.interrupted) {
                        "The last save didn't finish. Check the amounts and tap Save to complete it."
                    } else null
                )
                // Show the form now; suggestions fill in as they arrive.
                _state.value = shown.copy(loaded = asStored)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadedSmsLogId = null
                _state.update {
                    it.copy(loading = false, error = "Failed to load transaction: ${e.message}")
                }
                return@launch
            }

            loadLookups(api, baseUrl)
        }
    }

    /**
     * Loads every suggestion list concurrently. Each list is applied as soon as it arrives, and a
     * failure only leaves that list empty: suggestions are a convenience, never a reason to block editing.
     */
    private suspend fun loadLookups(api: FireflyApi, baseUrl: String) = coroutineScope {
        suspend fun <T> lookup(fetch: suspend () -> T, apply: (EditTransactionState, T) -> EditTransactionState) {
            try {
                val value = fetch()
                _state.update { apply(it, value) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Leave the list empty; the field still accepts free text.
            }
        }
        listOf(
            async {
                lookup({ FireflyLookupCache.categories(api, baseUrl) }) { s, list ->
                    s.copy(categorySuggestions = list.map { it.attributes.name })
                }
            },
            async {
                lookup({ FireflyLookupCache.budgets(api, baseUrl) }) { s, list ->
                    s.copy(budgetSuggestions = list.map { it.attributes.name })
                }
            },
            async {
                lookup({ FireflyLookupCache.descriptions(api, baseUrl) }) { s, list ->
                    s.copy(descriptionSuggestions = list.map { it.name }.distinct())
                }
            },
            async {
                lookup({ FireflyLookupCache.accounts(api, baseUrl, "all") }) { s, list ->
                    s.copy(accountSuggestions = list.map { it.attributes.name }.distinct(), accountsLoaded = true)
                }
            }
        ).awaitAll()
    }

    private class StoredSplitOffs(
        val active: List<SplitOffState>,
        val creating: List<SplitOffState>,
        val removing: List<SplitOffState>,
        val removed: List<SplitOffState>
    )

    /**
     * Reads every stored split-off from Firefly, grouped by how far its last save got. One deleted
     * in Firefly is forgotten, as is a create that never reached it; any other failure fails the
     * load, since the payment total depends on them.
     */
    private suspend fun loadSplitOffs(
        api: FireflyApi,
        baseUrl: String,
        log: SmsLog,
        originalDate: String
    ): StoredSplitOffs {
        val records = decodeSplitOffs(log.splitOffsJson)
        val empty = StoredSplitOffs(emptyList(), emptyList(), emptyList(), emptyList())
        if (records.isEmpty()) return empty

        val resolved: List<Pair<SplitOffRecord, FireflyTransactionAttributes?>?> = coroutineScope {
            records.map { record ->
                async {
                    when {
                        record.status == SplitOffStatus.REMOVED -> record to null
                        record.fireflyId == null -> {
                            // The create may have reached Firefly before the app stopped.
                            val day = record.date.ifBlank { originalDate }.take(10)
                            val group = api.findTransactionByExternalId(baseUrl, day, record.externalId)
                            group?.takeIf { it.attributes.transactions.isNotEmpty() }?.let {
                                val journalId = it.attributes.transactions.first().transactionJournalId
                                record.copy(fireflyId = it.id, transactionJournalId = journalId) to it.attributes
                            }
                        }
                        else -> try {
                            val attributes = api.getTransaction(baseUrl, record.fireflyId).data.attributes
                            attributes.takeIf { it.transactions.isNotEmpty() }?.let { record to it }
                        } catch (e: FireflyUnavailable) {
                            if (e.statusCode == 404) null else throw e
                        }
                    }
                }
            }.awaitAll()
        }
        val kept = resolved.filterNotNull()
        val keptRecords = kept.map { it.first }
        if (keptRecords != records) smsLogDao.updateSplitOffs(log.id, encodeSplitOffs(keptRecords))

        fun stateOf(entry: Pair<SplitOffRecord, FireflyTransactionAttributes?>): SplitOffState {
            val (record, attributes) = entry
            return if (attributes != null) {
                splitOffFromFirefly(nextKey++, record, attributes.transactions, attributes.groupTitle) { nextKey++ }
            } else splitOffFromRecord(nextKey++, nextKey++, record)
        }
        fun withStatus(status: String) = kept.filter { it.first.status == status }.map(::stateOf)
        return StoredSplitOffs(
            // Records from before statuses existed decode as active.
            active = kept.filter {
                it.first.status !in setOf(SplitOffStatus.CREATING, SplitOffStatus.REMOVING, SplitOffStatus.REMOVED)
            }.map(::stateOf),
            creating = withStatus(SplitOffStatus.CREATING),
            removing = withStatus(SplitOffStatus.REMOVING),
            removed = withStatus(SplitOffStatus.REMOVED)
        )
    }

    private fun paymentTotal(splits: List<FireflyTransactionSplit>, splitOffs: List<SplitOffState>): String =
        (splits.sumOf { BigDecimal(it.amount).setScale(2, RoundingMode.HALF_UP) } +
            splitOffs.sumOf { parseEditAmount(it.amount)?.setScale(2, RoundingMode.HALF_UP) ?: BigDecimal.ZERO })
            .toPlainString()

    private fun FireflyTransactionSplit.toRow() = EditRowState(
        key = nextKey++,
        transactionJournalId = transactionJournalId,
        amount = BigDecimal(amount).setScale(2, RoundingMode.HALF_UP).toPlainString(),
        description = description,
        categoryName = categoryName ?: "",
        budgetName = budgetName ?: "",
        notes = notes ?: ""
    )

    private fun splitTotal(splits: List<FireflyTransactionSplit>): String =
        splits.sumOf { BigDecimal(it.amount).setScale(2, RoundingMode.HALF_UP) }.toPlainString()

    fun updateRow(index: Int, row: EditRowState) {
        _state.update { current ->
            if (index !in current.rows.indices) return@update current
            val previous = current.rows[index]
            // Editing the amount clears its error; other edits leave it in place.
            val error = if (row.amount != previous.amount) null else row.amountError
            current.copy(rows = current.rows.toMutableList().also { it[index] = row.copy(amountError = error) })
        }
    }

    /** Adds a split and returns its index, so the screen can focus its amount. */
    fun addRow(): Int {
        var newIndex = -1
        _state.update { current ->
            val allocation = allocateNewSplit(current.payerShareTotal(), current.rows.map { it.amount })
            val rows = current.rows.mapIndexed { i, row ->
                val amount = allocation.amounts[i]
                if (amount == row.amount) row else row.copy(amount = amount, amountError = null)
            } + EditRowState(key = nextKey++, amount = allocation.newAmount)
            newIndex = rows.lastIndex
            current.copy(rows = rows)
        }
        return newIndex
    }

    fun removeRow(index: Int) {
        _state.update { current ->
            if (index !in current.rows.indices || current.rows.size <= 1) return@update current
            val rows = current.rows.toMutableList()
            val removed = rows.removeAt(index)
            current.copy(
                rows = rows,
                // A single transaction has no group title to require.
                groupTitleError = if (rows.size > 1) current.groupTitleError else null,
                removedSplit = RemovedSplit(nextRemovalId++, index, removed)
            )
        }
    }

    /** Puts the split from [removal] back at its old position. */
    fun undoRemove(removal: RemovedSplit) {
        _state.update { current ->
            if (current.rows.any { it.key == removal.row.key }) return@update current.copy(removedSplit = null)
            val rows = current.rows.toMutableList()
            rows.add(removal.index.coerceIn(0, rows.size), removal.row)
            current.copy(rows = rows, removedSplit = null)
        }
    }

    fun clearRemovedSplit(removal: RemovedSplit) {
        _state.update { if (it.removedSplit?.id == removal.id) it.copy(removedSplit = null) else it }
    }

    fun clearSaveError() = _state.update { it.copy(saveError = null, saveErrorDetail = null) }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ─── Split-offs ────────────────────────────────────────────

    /** Adds a split-off that takes half of the payer's share, and returns its key for focusing. */
    fun addSplitOff(): Long? {
        var newKey: Long? = null
        _state.update { current ->
            val log = current.smsLog
            if (!current.canSplitOff || log == null) return@update current
            val allocation = allocateSplitOff(
                current.paymentTotal,
                current.rows.map { it.amount },
                current.splitOffs.map { it.amount }
            )
            val rows = current.rows.mapIndexed { i, row ->
                val amount = allocation.amounts[i]
                if (amount == row.amount) row else row.copy(amount = amount, amountError = null)
            }
            val piece = SplitOffState(
                key = nextKey++,
                externalId = newSplitOffExternalId(log.smsHash),
                parts = listOf(
                    SplitOffPart(
                        key = nextKey++,
                        amount = allocation.newAmount,
                        description = current.rows.firstOrNull()?.description?.ifBlank { null }
                            ?: current.groupTitle,
                        notes = SPLIT_OFF_NOTES
                    )
                )
            )
            newKey = piece.key
            current.copy(rows = rows, splitOffs = current.splitOffs + piece)
        }
        return newKey
    }

    /**
     * Replaces the split-off with [piece]'s key. A changed amount is taken from (or given back to)
     * the payer's last split so the payment stays balanced; a retyped account drops its Firefly id.
     */
    fun updateSplitOff(piece: SplitOffState) {
        _state.update { current ->
            val index = current.splitOffs.indexOfFirst { it.key == piece.key }
            if (index < 0) return@update current
            val previous = current.splitOffs[index]
            // Editing a field clears its error; other edits leave errors in place.
            var updated = piece.copy(parts = piece.parts.map { part ->
                val before = previous.parts.firstOrNull { it.key == part.key }
                if (before != null && before.amount != part.amount) part.copy(amountError = null) else part
            })
            if (piece.accountName != previous.accountName) updated = updated.copy(accountId = null, accountError = null)
            if (piece.groupTitle != previous.groupTitle) updated = updated.copy(groupTitleError = null)

            val splitOffs = current.splitOffs.toMutableList().also { it[index] = updated }
            val rebalanced = if (piece.amount != previous.amount) {
                rebalanceForSplitOff(current.paymentTotal, current.rows.map { it.amount }, splitOffs.map { it.amount })
            } else null
            val rows = rebalanced?.let { amounts ->
                current.rows.mapIndexed { i, row ->
                    if (amounts[i] == row.amount) row else row.copy(amount = amounts[i], amountError = null)
                }
            } ?: current.rows
            current.copy(rows = rows, splitOffs = splitOffs)
        }
    }

    /** Removes a split-off and gives its amount back to the payer. A stored one is deleted on save. */
    fun removeSplitOff(key: Long) {
        _state.update { current ->
            val piece = current.splitOffs.firstOrNull { it.key == key } ?: return@update current
            val amounts = returnSplitOffAmount(current.rows.map { it.amount }, piece.amount)
            val rows = current.rows.mapIndexed { i, row ->
                if (amounts[i] == row.amount) row else row.copy(amount = amounts[i], amountError = null)
            }
            current.copy(
                rows = rows,
                splitOffs = current.splitOffs.filterNot { it.key == key },
                deletedSplitOffs = if (piece.isSaved) current.deletedSplitOffs + piece else current.deletedSplitOffs,
                notice = if (piece.isSaved) "Split-off removed. It's deleted from Firefly when you save."
                else "Split-off removed"
            )
        }
    }

    /**
     * Adds a split inside the split-off with [pieceKey], taking half of its last split so neither
     * the split-off's total nor the payer's share changes. Returns the new split's key for focusing.
     */
    fun addSplitOffPart(pieceKey: Long): Long? {
        var newKey: Long? = null
        _state.update { current ->
            val index = current.splitOffs.indexOfFirst { it.key == pieceKey }
            if (index < 0) return@update current
            val key = nextKey++
            newKey = key
            val piece = addSplitOffPart(current.splitOffs[index], key)
            current.copy(splitOffs = current.splitOffs.toMutableList().also { it[index] = piece })
        }
        return newKey
    }

    /** Removes a split inside a split-off; its amount joins the split before it. */
    fun removeSplitOffPart(pieceKey: Long, partKey: Long) {
        _state.update { current ->
            val index = current.splitOffs.indexOfFirst { it.key == pieceKey }
            if (index < 0) return@update current
            val piece = removeSplitOffPart(current.splitOffs[index], partKey)
            if (piece == current.splitOffs[index]) return@update current
            current.copy(
                splitOffs = current.splitOffs.toMutableList().also { it[index] = piece },
                notice = "Split removed. Its amount moved to the split above."
            )
        }
    }

    fun save() {
        val current = _state.value
        if (current.saving || current.fireflyTxId == null) return

        val validation = validateEdit(
            current.rows.map { it.amount },
            current.groupTitle,
            current.paymentTotal.ifBlank { current.originalTotal },
            splitOffPartAmounts(current.splitOffs)
        )
        val splitOffValidation = validateSplitOffs(current.transactionType, current.splitOffs)
        if (!validation.isValid || !splitOffValidation.isValid) {
            _state.value = current.copy(
                rows = current.rows.mapIndexed { i, row -> row.copy(amountError = validation.amountErrors[i]) },
                splitOffs = current.splitOffs.mapIndexed { i, piece ->
                    piece.copy(
                        accountError = splitOffValidation.accountErrors[i],
                        groupTitleError = splitOffValidation.groupTitleErrors[i],
                        parts = piece.parts.mapIndexed { j, part ->
                            part.copy(amountError = splitOffValidation.amountErrors[i]?.get(j))
                        }
                    )
                },
                groupTitleError = validation.groupTitleError,
                saveError = splitOffValidation.typeError
                    ?: validation.summary
                    ?: "Fix the highlighted fields",
                saveErrorDetail = null
            )
            return
        }

        _state.value = current.copy(saving = true, saveError = null, saveErrorDetail = null)

        viewModelScope.launch {
            val baseUrl = prefs.fireflyUrl.first()
            val token = prefs.fireflyToken.first()
            val api = FireflyApi(token)
            try {
                val originalSplits = current.originalSplits
                val firstOriginal = originalSplits.firstOrNull()
                val shared = SharedPaymentFields(
                    type = current.transactionType,
                    date = current.transactionDate.ifBlank { firstOriginal?.date ?: "" },
                    sourceId = current.sourceId ?: firstOriginal?.sourceId,
                    sourceName = current.sourceName.ifBlank { firstOriginal?.sourceName },
                    destinationId = current.destinationId ?: firstOriginal?.destinationId,
                    destinationName = current.destinationName.ifBlank { firstOriginal?.destinationName }
                )

                // Order matters, so that a failure part-way never loses money from Firefly:
                // new split-offs are stored before the original shrinks, and removed split-offs
                // are deleted only after the original has grown back. Saving again resumes safely.
                // Each record's status says which step was reached, so reopening the editor after an
                // interruption restores the intended amounts (see recoverPayment).
                val created = createNewSplitOffs(api, baseUrl, current, shared)
                updateChangedSplitOffs(api, baseUrl, current, shared, skipKeys = created)
                markRemovalsStarted(current)
                val result = updateOriginal(api, baseUrl, current)
                markOriginalUpdated(current)
                deleteRemovedSplitOffs(api, baseUrl, current)
                linkSplitOffs(api, baseUrl, current.smsLog?.id, result.data.attributes.transactions)

                val updatedSplits = result.data.attributes.transactions
                val firstUpdated = updatedSplits.firstOrNull()
                _state.update { latest ->
                    val refreshed = latest.copy(
                        saving = false,
                        rows = updatedSplits.map { it.toRow() },
                        originalSplits = updatedSplits,
                        originalTotal = splitTotal(updatedSplits),
                        paymentTotal = paymentTotal(updatedSplits, latest.splitOffs),
                        deletedSplitOffs = emptyList(),
                        currencyCode = firstUpdated?.currencyCode ?: current.currencyCode,
                        currencySymbol = firstUpdated?.currencySymbol ?: current.currencySymbol,
                        groupTitle = result.data.attributes.groupTitle ?: "",
                        groupTitleError = null,
                        transactionType = firstUpdated?.type?.ifBlank { "withdrawal" } ?: "withdrawal",
                        transactionDate = firstUpdated?.date ?: "",
                        sourceName = firstUpdated?.sourceName ?: "",
                        sourceId = firstUpdated?.sourceId,
                        destinationName = firstUpdated?.destinationName ?: "",
                        destinationId = firstUpdated?.destinationId,
                        removedSplit = null,
                        saveSuccess = true
                    )
                    refreshed.copy(loaded = refreshed.editableFields())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val readable = describeSaveError(e)
                _state.update {
                    it.copy(saving = false, saveError = readable.message, saveErrorDetail = readable.detail)
                }
            } finally {
                api.close()
            }
        }
    }

    /**
     * Stores every split-off Firefly does not hold yet and returns their keys. Each one is recorded
     * on the SMS log as soon as it exists, so a later failure cannot orphan it, and a create that
     * timed out is found again by its external id instead of being repeated.
     */
    private suspend fun createNewSplitOffs(
        api: FireflyApi,
        baseUrl: String,
        current: EditTransactionState,
        shared: SharedPaymentFields
    ): Set<Long> {
        val logId = current.smsLog?.id ?: return emptySet()
        val created = mutableSetOf<Long>()
        for (piece in current.splitOffs.filterNot { it.isSaved }) {
            // Recorded before the request: if the app stops before the response arrives, reopening
            // the editor looks for the transaction by its external id.
            val pending = SplitOffRecord(
                externalId = piece.externalId,
                amount = moneyText(piece.amount),
                accountName = piece.accountName.trim(),
                description = piece.title,
                date = shared.date,
                status = SplitOffStatus.CREATING,
                createdAt = System.currentTimeMillis()
            )
            editSplitOffRecords(logId) { records ->
                if (records.any { it.externalId == piece.externalId }) records else records + pending
            }
            val existing = try {
                api.findTransactionByExternalId(baseUrl, shared.date.take(10), piece.externalId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FireflyUnavailable) {
                throw e
            } catch (_: Exception) {
                null
            }
            val group = existing ?: api.createTransactionGroup(baseUrl, buildSplitOffCreateRequest(shared, piece))
            val journalId = group.attributes.transactions.firstOrNull()?.transactionJournalId
            editSplitOffRecords(logId) { records ->
                records.map {
                    if (it.externalId == piece.externalId) it.copy(fireflyId = group.id, transactionJournalId = journalId)
                    else it
                }
            }
            _state.update { latest ->
                latest.copy(splitOffs = latest.splitOffs.map {
                    if (it.key == piece.key) withJournalIds(it.copy(fireflyId = group.id), group.attributes.transactions)
                    else it
                })
            }
            created += piece.key
        }
        return created
    }

    /** Saves stored split-offs whose own fields, or the payment fields they share, changed. */
    private suspend fun updateChangedSplitOffs(
        api: FireflyApi,
        baseUrl: String,
        current: EditTransactionState,
        shared: SharedPaymentFields,
        skipKeys: Set<Long>
    ) {
        val loaded = current.loaded
        val sharedChanged = loaded == null ||
            loaded.transactionType != current.transactionType ||
            loaded.transactionDate != current.transactionDate ||
            loaded.sourceName != current.sourceName ||
            loaded.destinationName != current.destinationName
        val logId = current.smsLog?.id
        for (piece in current.splitOffs) {
            val fireflyId = piece.fireflyId ?: continue
            if (piece.key in skipKeys) continue
            val before = loaded?.splitOffs?.firstOrNull { it.key == piece.key }
            if (!sharedChanged && before == piece.withoutErrors()) continue
            val result = api.updateTransaction(baseUrl, fireflyId, buildSplitOffUpdateRequest(shared, piece))
            val splits = result.data.attributes.transactions
            if (logId != null) {
                val firstJournalId = splits.firstOrNull()?.transactionJournalId
                editSplitOffRecords(logId) { records ->
                    records.map {
                        if (it.fireflyId == fireflyId) it.copy(
                            amount = moneyText(piece.amount),
                            accountName = piece.accountName.trim(),
                            description = piece.title,
                            transactionJournalId = firstJournalId ?: it.transactionJournalId,
                            // Removing the first split deletes the journal the link was on; link the new one.
                            linked = it.linked && (firstJournalId == null || firstJournalId == it.transactionJournalId)
                        ) else it
                    }
                }
            }
            // Splits added here now have journals; keep them so a second save updates, not duplicates.
            _state.update { latest ->
                latest.copy(splitOffs = latest.splitOffs.map { if (it.key == piece.key) withJournalIds(it, splits) else it })
            }
        }
    }

    private suspend fun updateOriginal(
        api: FireflyApi,
        baseUrl: String,
        current: EditTransactionState
    ): FireflyTransactionResponse {
        val fireflyTxId = current.fireflyTxId!!
        val originalSplits = current.originalSplits
        val mergedSplits = current.rows.mapIndexed { i, row ->
            val original = originalSplits.getOrNull(i)
            val roundedAmount = parseEditAmount(row.amount)!!.setScale(2, RoundingMode.HALF_UP).toPlainString()

            // Resolve title mapping for blank category/budget on save
            val catName = row.categoryName.ifBlank { null }
            val budName = row.budgetName.ifBlank { null }
            val resolvedCat = if (catName == null && budName == null) {
                val mappingRule = mappingResolver.resolve(row.description.ifBlank { original?.description ?: "" })
                if (mappingRule != null) {
                    Pair(
                        mappingRule.categoryName.takeIf { it.isNotBlank() },
                        mappingRule.budgetName.takeIf { it.isNotBlank() }
                    )
                } else null to null
            } else catName to budName

            TransactionUpdateSplit(
                transactionJournalId = row.transactionJournalId,
                type = current.transactionType,
                date = current.transactionDate.ifBlank { original?.date ?: "" },
                amount = roundedAmount,
                description = row.description.ifBlank { original?.description ?: "" },
                sourceId = current.sourceId ?: original?.sourceId,
                sourceName = current.sourceName.ifBlank { original?.sourceName },
                destinationId = current.destinationId ?: original?.destinationId,
                destinationName = current.destinationName.ifBlank { original?.destinationName },
                categoryName = resolvedCat.first ?: row.categoryName.ifBlank { null },
                budgetName = resolvedCat.second ?: row.budgetName.ifBlank { null },
                notes = row.notes.ifBlank { original?.notes },
                order = i
            )
        }
        val updateRequest = TransactionUpdateRequest(
            groupTitle = current.groupTitle.trim().ifBlank { null },
            transactions = mergedSplits
        )
        val result = api.updateTransaction(baseUrl, fireflyTxId, updateRequest)

        current.smsLog?.let { log ->
            val storedLog = smsLogDao.getById(log.id)
            val updatedSnapshot = result.data.attributes.toStoredSnapshot()
            val baseline = decodeTransactionSnapshot(storedLog?.originalTransactionSnapshotJson)
            val isModified = baseline?.let { !it.semanticallyEquals(updatedSnapshot) } ?: false
            smsLogDao.updateTransactionSnapshotState(
                log.id,
                updatedSnapshot.encode(),
                if (isModified) storedLog?.locallyModifiedAt ?: System.currentTimeMillis() else null
            )
        }
        return result
    }

    /**
     * Before the original gets removed split-offs' amounts back: an active split-off becomes
     * "removing". One whose amount the original never gave up (still "creating") is already "removed".
     */
    private suspend fun markRemovalsStarted(current: EditTransactionState) {
        val logId = current.smsLog?.id ?: return
        val ids = current.deletedSplitOffs.mapNotNull { it.fireflyId }.toSet()
        if (ids.isEmpty()) return
        editSplitOffRecords(logId) { records ->
            records.map { record ->
                if (record.fireflyId !in ids) record
                else when (record.status) {
                    SplitOffStatus.CREATING -> record.copy(status = SplitOffStatus.REMOVED)
                    SplitOffStatus.REMOVING, SplitOffStatus.REMOVED -> record
                    else -> record.copy(status = SplitOffStatus.REMOVING)
                }
            }
        }
    }

    /** The original now matches: new split-offs are active and removed ones only need deleting. */
    private suspend fun markOriginalUpdated(current: EditTransactionState) {
        val logId = current.smsLog?.id ?: return
        editSplitOffRecords(logId) { records ->
            records.map { record ->
                when (record.status) {
                    SplitOffStatus.CREATING ->
                        if (record.fireflyId != null) record.copy(status = SplitOffStatus.ACTIVE) else record
                    SplitOffStatus.REMOVING -> record.copy(status = SplitOffStatus.REMOVED)
                    else -> record
                }
            }
        }
    }

    /** Deletes the split-offs the user removed, forgetting each one as soon as it is gone. */
    private suspend fun deleteRemovedSplitOffs(api: FireflyApi, baseUrl: String, current: EditTransactionState) {
        val logId = current.smsLog?.id
        for (piece in current.deletedSplitOffs) {
            val fireflyId = piece.fireflyId ?: continue
            api.deleteTransaction(baseUrl, fireflyId)
            if (logId != null) editSplitOffRecords(logId) { records -> records.filterNot { it.fireflyId == fireflyId } }
            _state.update { latest -> latest.copy(deletedSplitOffs = latest.deletedSplitOffs.filterNot { it.key == piece.key }) }
        }
    }

    /**
     * Links each split-off to the original with Firefly's "Related" link type. Best effort: the
     * split-off is saved either way, and links that failed are tried again on the next save.
     */
    private suspend fun linkSplitOffs(
        api: FireflyApi,
        baseUrl: String,
        logId: Long?,
        originalSplits: List<FireflyTransactionSplit>
    ) {
        if (logId == null) return
        val originalJournalId = originalSplits.firstOrNull()?.transactionJournalId ?: return
        val unlinked = decodeSplitOffs(smsLogDao.getById(logId)?.splitOffsJson)
            .filter { !it.linked && it.transactionJournalId != null && it.status == SplitOffStatus.ACTIVE }
        if (unlinked.isEmpty()) return
        try {
            val linkTypeId = relatedLinkTypeId(api.getLinkTypes(baseUrl)) ?: return
            for (record in unlinked) {
                api.createTransactionLink(
                    baseUrl,
                    TransactionLinkRequest(
                        linkTypeId = linkTypeId,
                        inwardId = originalJournalId,
                        outwardId = record.transactionJournalId!!,
                        notes = "Split off with FireSMS"
                    )
                )
                editSplitOffRecords(logId) { records ->
                    records.map { if (it.fireflyId == record.fireflyId) it.copy(linked = true) else it }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Linking is a convenience; the next save retries what is still unlinked.
        }
    }

    /** Takes Firefly's journal ids for [piece]'s splits, which it returns in the order sent. */
    private fun withJournalIds(piece: SplitOffState, splits: List<FireflyTransactionSplit>): SplitOffState {
        if (splits.size != piece.parts.size) return piece
        return piece.copy(parts = piece.parts.mapIndexed { i, part ->
            part.copy(transactionJournalId = splits[i].transactionJournalId ?: part.transactionJournalId)
        })
    }

    private suspend fun editSplitOffRecords(logId: Long, transform: (List<SplitOffRecord>) -> List<SplitOffRecord>) {
        val records = decodeSplitOffs(smsLogDao.getById(logId)?.splitOffsJson)
        smsLogDao.updateSplitOffs(logId, encodeSplitOffs(transform(records)))
    }

    fun clearSaveSuccess() {
        _state.update { it.copy(saveSuccess = false) }
    }
}

// ─── Pure editing logic (unit-tested) ──────────────────────────

/**
 * Reads a typed amount. A lone comma is taken as the decimal separator ("12,50"), since some
 * locales' decimal keypads offer only that; otherwise commas are thousands separators.
 */
internal fun parseEditAmount(text: String): BigDecimal? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val normalized = if (trimmed.count { it == ',' } == 1 && '.' !in trimmed) {
        trimmed.replace(',', '.')
    } else {
        trimmed.replace(",", "")
    }
    return normalized.toBigDecimalOrNull()
}

private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

/** How much of [total] the splits currently cover. Unparseable amounts count as zero. */
internal data class SplitAllocation(val allocated: BigDecimal, val total: BigDecimal) {
    val remaining: BigDecimal get() = total.subtract(allocated)
    val isBalanced: Boolean get() = remaining.signum() == 0
}

internal fun allocationOf(total: String, amounts: List<String>): SplitAllocation? {
    val parsedTotal = parseEditAmount(total) ?: return null
    val allocated = amounts.sumOf { parseEditAmount(it)?.money() ?: BigDecimal.ZERO }
    return SplitAllocation(allocated.money(), parsedTotal.money())
}

/** "Allocated ₹60.00 of ₹100.00 · ₹40.00 left", "… · ₹5.00 over", or "… · balanced". */
internal fun allocationText(allocation: SplitAllocation, currencyLabel: String): String {
    val base = "Allocated ${formatMoney(allocation.allocated, currencyLabel)} of " +
        formatMoney(allocation.total, currencyLabel)
    val remaining = allocation.remaining
    return when {
        remaining.signum() == 0 -> "$base · balanced"
        remaining.signum() > 0 -> "$base · ${formatMoney(remaining, currencyLabel)} left"
        else -> "$base · ${formatMoney(remaining.negate(), currencyLabel)} over"
    }
}

/** A symbol sits against the number ("₹12.00"); a currency code is spaced ("INR 12.00"). */
internal fun formatMoney(value: BigDecimal, currencyLabel: String): String {
    val number = value.money().toPlainString()
    return when {
        currencyLabel.isBlank() -> number
        currencyLabel.length > 1 && currencyLabel.all { it.isLetter() } -> "$currencyLabel $number"
        else -> "$currencyLabel$number"
    }
}

/** The amounts after adding a split: [amounts] are the existing splits' (possibly adjusted) values. */
internal data class NewSplitAmounts(val amounts: List<String>, val newAmount: String)

/**
 * Chooses the amount for a newly added split so the total stays balanced where possible:
 * - when part of the total is unallocated, the new split takes the rest;
 * - when the splits already cover the total exactly (typically split 1 still holds all of it),
 *   the last split with an amount is halved and the new split takes the other half;
 * - otherwise (over-allocated, unknown total, nothing to halve) the new amount is left empty.
 */
internal fun allocateNewSplit(total: String, amounts: List<String>): NewSplitAmounts {
    val allocation = allocationOf(total, amounts) ?: return NewSplitAmounts(amounts, "")
    val remaining = allocation.remaining
    if (remaining.signum() > 0) return NewSplitAmounts(amounts, remaining.toPlainString())
    if (remaining.signum() < 0) return NewSplitAmounts(amounts, "")

    val minimum = BigDecimal("0.02")
    val donor = amounts.indexOfLast { (parseEditAmount(it)?.money() ?: BigDecimal.ZERO) >= minimum }
    if (donor < 0) return NewSplitAmounts(amounts, "")
    val donorAmount = parseEditAmount(amounts[donor])!!.money()
    val half = donorAmount.divide(BigDecimal(2), 2, RoundingMode.DOWN)
    val kept = donorAmount.subtract(half)
    val adjusted = amounts.toMutableList().also { it[donor] = kept.toPlainString() }
    return NewSplitAmounts(adjusted, half.toPlainString())
}

internal data class EditValidation(
    val amountErrors: Map<Int, String> = emptyMap(),
    val groupTitleError: String? = null,
    val totalError: String? = null
) {
    val isValid: Boolean get() = amountErrors.isEmpty() && groupTitleError == null && totalError == null

    /** One line for the snackbar; the details sit on the fields themselves. */
    val summary: String?
        get() = when {
            isValid -> null
            totalError != null && amountErrors.isEmpty() && groupTitleError == null -> totalError
            else -> "Fix the highlighted fields"
        }
}

/**
 * Checks the original's split [amounts]. While there are several splits or any split-offs, the
 * splits plus [splitOffAmounts] must add up to [total] (the whole payment).
 */
internal fun validateEdit(
    amounts: List<String>,
    groupTitle: String,
    total: String,
    splitOffAmounts: List<String> = emptyList()
): EditValidation {
    if (amounts.isEmpty()) return EditValidation(totalError = "At least one split is required")

    val amountErrors = buildMap {
        amounts.forEachIndexed { i, text ->
            val amount = parseEditAmount(text)
            when {
                text.isBlank() -> put(i, "Required")
                amount == null || amount.signum() <= 0 -> put(i, "Enter an amount above zero")
            }
        }
    }
    val isSplit = amounts.size > 1
    val groupTitleError = if (isSplit && groupTitle.isBlank()) "Required for split transactions" else null

    var totalError: String? = null
    val splitOffsValid = splitOffAmounts.all { (parseEditAmount(it)?.signum() ?: 0) > 0 }
    if ((isSplit || splitOffAmounts.isNotEmpty()) && amountErrors.isEmpty() && splitOffsValid) {
        val allocation = allocationOf(total, amounts + splitOffAmounts)
        if (allocation != null && !allocation.isBalanced) {
            totalError = "Split amounts (${allocation.allocated.toPlainString()}) must add up to the " +
                "total (${allocation.total.toPlainString()})"
        }
    }
    return EditValidation(amountErrors, groupTitleError, totalError)
}

/** A message the user can act on, plus the raw text it was derived from. */
internal data class ReadableError(val message: String, val detail: String?)

internal fun describeSaveError(error: Throwable): ReadableError = when (error) {
    is FireflyError -> describeFireflyError(error.statusCode, error.message.orEmpty())
    is FireflyUnavailable -> ReadableError(
        if (error.statusCode >= 500) "Firefly had a server problem. Try again in a moment."
        else "Firefly couldn't be reached (HTTP ${error.statusCode}). Try again in a moment.",
        error.message
    )
    else -> {
        val readable = networkErrorMessage(error)
        ReadableError("Couldn't save: $readable", error.message?.takeIf { it != readable })
    }
}

private val JSON_MESSAGE = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")
private val JSON_FIRST_ERROR = Regex(""""errors"\s*:\s*\{\s*"[^"]*"\s*:\s*\[\s*"((?:[^"\\]|\\.)*)"""")
private val SPLIT_FIELD = Regex("""transactions\.(\d+)\.([a-z_]+)""")
private val FIELD_NAMES = mapOf(
    "amount" to "amount",
    "date" to "date",
    "description" to "description",
    "source_id" to "source account",
    "source_name" to "source account",
    "destination_id" to "destination account",
    "destination_name" to "destination account",
    "category_name" to "category",
    "budget_name" to "budget",
    "budget_id" to "budget",
    "notes" to "notes",
    "type" to "type"
)

/**
 * Turns Firefly's "HTTP 422: {json}" error into a sentence. Field paths such as
 * `transactions.1.source_name` become "split 2 source account". The body may be cut short,
 * so it is read with patterns rather than a JSON parser.
 */
internal fun describeFireflyError(statusCode: Int, raw: String): ReadableError {
    fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
    val firstError = JSON_FIRST_ERROR.find(raw)?.groupValues?.get(1)?.let(::unescape)
    val message = JSON_MESSAGE.find(raw)?.groupValues?.get(1)?.let(::unescape)
    val reason = (firstError ?: message)
        ?.replace(SPLIT_FIELD) { m ->
            val field = m.groupValues[2]
            "split ${m.groupValues[1].toInt() + 1} ${FIELD_NAMES[field] ?: field.replace('_', ' ')}"
        }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    val readable = when (statusCode) {
        401 -> "Firefly rejected the access token. Check it in Settings."
        403 -> "This access token isn't allowed to change transactions."
        404 -> "This transaction no longer exists in Firefly."
        422 -> if (reason != null) "Firefly didn't accept the changes: $reason" else "Firefly didn't accept the changes."
        else -> if (reason != null) "Firefly couldn't save: $reason" else "Firefly couldn't save the changes (HTTP $statusCode)."
    }
    return ReadableError(readable, raw.takeIf { it.isNotBlank() })
}
