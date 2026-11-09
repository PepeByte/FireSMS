package com.firesms.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.entity.SmsLog
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
    val rows: List<EditRowState>
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
        rows = rows.map { it.copy(amountError = null) }
    )

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
                    rows = originalSplits.map { it.toRow() },
                    originalSplits = originalSplits
                )
                // Show the form now; suggestions fill in as they arrive.
                _state.value = loadedState.copy(loaded = loadedState.editableFields())
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
            val allocation = allocateNewSplit(current.originalTotal, current.rows.map { it.amount })
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

    fun save() {
        val current = _state.value
        if (current.saving || current.fireflyTxId == null) return

        val validation = validateEdit(current.rows.map { it.amount }, current.groupTitle, current.originalTotal)
        if (!validation.isValid) {
            _state.value = current.copy(
                rows = current.rows.mapIndexed { i, row -> row.copy(amountError = validation.amountErrors[i]) },
                groupTitleError = validation.groupTitleError,
                saveError = validation.summary,
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
                val result = api.updateTransaction(baseUrl, current.fireflyTxId, updateRequest)
                // Refresh state from the response
                val updatedSplits = result.data.attributes.transactions
                val firstUpdated = updatedSplits.firstOrNull()

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

                _state.update { latest ->
                    val refreshed = latest.copy(
                        saving = false,
                        rows = updatedSplits.map { it.toRow() },
                        originalSplits = updatedSplits,
                        originalTotal = splitTotal(updatedSplits),
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

internal fun validateEdit(amounts: List<String>, groupTitle: String, total: String): EditValidation {
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
    if (isSplit && amountErrors.isEmpty()) {
        val allocation = allocationOf(total, amounts)
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
