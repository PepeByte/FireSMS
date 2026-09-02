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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

data class EditRowState(
    val transactionJournalId: String? = null,
    val amount: String = "",
    val description: String = "",
    val categoryName: String = "",
    val budgetName: String = "",
    val notes: String = ""
)

data class EditTransactionState(
    val loading: Boolean = true,
    val error: String? = null,
    val smsLog: SmsLog? = null,
    val fireflyTxId: String? = null,
    val groupTitle: String = "",
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
    val categorySuggestions: List<String> = emptyList(),
    val budgetSuggestions: List<String> = emptyList(),
    val descriptionSuggestions: List<String> = emptyList(),
    val accountSuggestions: List<String> = emptyList(),
    val saving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false
) {
    fun originalSplitsOrThrow(): List<FireflyTransactionSplit> = originalSplits
}

class EditTransactionViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val smsLogDao = db.smsLogDao()
    private val prefs = (application as FireSmsApp).preferencesManager
    private val mappingResolver = com.firesms.app.domain.parser.TitleMappingResolver(db.titleMappingRuleDao())

    private val _state = MutableStateFlow(EditTransactionState())
    val state: StateFlow<EditTransactionState> = _state.asStateFlow()

    fun updateTransactionField(update: (EditTransactionState) -> EditTransactionState) {
        _state.value = _state.value.let(update)
    }

    fun load(smsLogId: Long?) {
        viewModelScope.launch {
            if (smsLogId == null) {
                _state.value = _state.value.copy(loading = false, error = "Invalid SMS log ID")
                return@launch
            }

            val log = smsLogDao.getById(smsLogId)
            if (log == null) {
                _state.value = _state.value.copy(loading = false, error = "SMS log not found")
                return@launch
            }

            val fireflyId = log.fireflyId
            if (fireflyId == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "No Firefly transaction associated with this log"
                )
                return@launch
            }

            val baseUrl = prefs.fireflyUrl.first()
            val token = prefs.fireflyToken.first()
            if (baseUrl.isEmpty() || token.isEmpty()) {
                _state.value = _state.value.copy(loading = false, error = "Firefly not configured")
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

                val categories = api.getCategories(baseUrl)
                val budgets = api.getBudgets(baseUrl)
                val descItems = api.getAutocompleteTransactions(baseUrl)
                val accounts = api.getAccounts(baseUrl, "all")

                val originalSplits = txResponse.data.attributes.transactions
                val groupTitle = txResponse.data.attributes.groupTitle ?: ""
                val firstSplit = originalSplits.firstOrNull()
                val editableRows = originalSplits.map { split ->
                    EditRowState(
                        transactionJournalId = split.transactionJournalId,
                        amount = java.math.BigDecimal(split.amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                        description = split.description,
                        categoryName = split.categoryName ?: "",
                        budgetName = split.budgetName ?: "",
                        notes = split.notes ?: ""
                    )
                }

                val total = originalSplits.sumOf { split ->
                    java.math.BigDecimal(split.amount).setScale(2, java.math.RoundingMode.HALF_UP)
                }.toPlainString()

                _state.value = EditTransactionState(
                    loading = false,
                    smsLog = log,
                    fireflyTxId = fireflyId,
                    groupTitle = groupTitle,
                    transactionType = firstSplit?.type?.ifBlank { "withdrawal" } ?: "withdrawal",
                    transactionDate = firstSplit?.date ?: "",
                    sourceName = firstSplit?.sourceName ?: "",
                    sourceId = firstSplit?.sourceId,
                    destinationName = firstSplit?.destinationName ?: "",
                    destinationId = firstSplit?.destinationId,
                    originalTotal = total,
                    currencyCode = firstSplit?.currencyCode ?: "",
                    currencySymbol = firstSplit?.currencySymbol ?: "",
                    rows = editableRows,
                    originalSplits = originalSplits,
                    categorySuggestions = categories.map { it.attributes.name },
                    budgetSuggestions = budgets.map { it.attributes.name },
                    descriptionSuggestions = descItems.map { it.name }.distinct(),
                    accountSuggestions = accounts.map { it.attributes.name }
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Failed to load transaction: ${e.message}"
                )
            } finally {
                api.close()
            }
        }
    }

    fun updateRow(index: Int, row: EditRowState) {
        val rows = _state.value.rows.toMutableList()
        if (index in rows.indices) {
            rows[index] = row
            _state.value = _state.value.copy(rows = rows, saveError = null)
        }
    }

    fun addRow() {
        val current = _state.value
        val rows = current.rows.toMutableList()

        // Auto-calculate remaining amount for the new split
        val total = try {
            java.math.BigDecimal(current.originalTotal).setScale(2, java.math.RoundingMode.HALF_UP)
        } catch (_: Exception) {
            java.math.BigDecimal.ZERO
        }

        val allocated = rows.sumOf { row ->
            try {
                java.math.BigDecimal(row.amount).setScale(2, java.math.RoundingMode.HALF_UP)
            } catch (_: Exception) {
                java.math.BigDecimal.ZERO
            }
        }

        val remaining = total.subtract(allocated).setScale(2, java.math.RoundingMode.HALF_UP)
        val newAmount = if (remaining.compareTo(java.math.BigDecimal.ZERO) > 0) {
            remaining.toPlainString()
        } else {
            ""
        }

        rows.add(EditRowState(amount = newAmount, description = ""))
        _state.value = current.copy(rows = rows, saveError = null)
    }

    fun removeRow(index: Int) {
        val rows = _state.value.rows.toMutableList()
        if (index in rows.indices && rows.size > 1) {
            rows.removeAt(index)
            _state.value = _state.value.copy(rows = rows, saveError = null)
        }
    }

    fun save() {
        val current = _state.value
        if (current.saving || current.fireflyTxId == null) return

        // Validation
        if (current.rows.isEmpty()) {
            _state.value = current.copy(saveError = "At least one split row is required")
            return
        }

        for ((i, row) in current.rows.withIndex()) {
            if (row.amount.isBlank()) {
                _state.value = current.copy(saveError = "Row ${i + 1}: amount is required")
                return
            }
        }

        // For multiple splits, validate sum
        if (current.rows.size > 1) {
            val splitSum = current.rows.sumOf { row ->
                try { BigDecimal(row.amount) } catch (_: Exception) { BigDecimal.ZERO }
            }
            val original = try {
                BigDecimal(current.originalTotal)
            } catch (_: Exception) {
                BigDecimal.ZERO
            }
            val roundedSplit = splitSum.setScale(2, RoundingMode.HALF_UP)
            val roundedOriginal = original.setScale(2, RoundingMode.HALF_UP)
            if (roundedSplit != roundedOriginal) {
                _state.value = current.copy(
                    saveError = "Split amounts ($roundedSplit) must equal original total ($roundedOriginal)"
                )
                return
            }
        }

        _state.value = current.copy(saving = true, saveError = null)

        viewModelScope.launch {
            val baseUrl = prefs.fireflyUrl.first()
            val token = prefs.fireflyToken.first()
            val api = FireflyApi(token)
            try {
                val originalSplits = current.originalSplitsOrThrow()

                val mergedSplits = current.rows.mapIndexed { i, row ->
                    val original = originalSplits.getOrNull(i)
                    val roundedAmount = java.math.BigDecimal(row.amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

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
                    groupTitle = if (mergedSplits.size > 1) {
                        current.groupTitle.ifBlank { "Split transaction" }
                    } else {
                        current.groupTitle.ifBlank { null }
                    },
                    transactions = mergedSplits
                )
                val result = api.updateTransaction(baseUrl, current.fireflyTxId, updateRequest)
                // Refresh state from the response
                val updatedSplits = result.data.attributes.transactions
                val firstUpdated = updatedSplits.firstOrNull()
                val updatedRows = updatedSplits.map { split ->
                    EditRowState(
                        transactionJournalId = split.transactionJournalId,
                        amount = java.math.BigDecimal(split.amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                        description = split.description,
                        categoryName = split.categoryName ?: "",
                        budgetName = split.budgetName ?: "",
                        notes = split.notes ?: ""
                    )
                }
                val refreshedTotal = updatedSplits.sumOf { split ->
                    java.math.BigDecimal(split.amount).setScale(2, java.math.RoundingMode.HALF_UP)
                }.toPlainString()

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

                _state.value = _state.value.copy(
                    saving = false,
                    rows = updatedRows,
                    originalSplits = updatedSplits,
                    originalTotal = refreshedTotal,
                    currencyCode = firstUpdated?.currencyCode ?: current.currencyCode,
                    currencySymbol = firstUpdated?.currencySymbol ?: current.currencySymbol,
                    groupTitle = result.data.attributes.groupTitle ?: "",
                    transactionType = firstUpdated?.type?.ifBlank { "withdrawal" } ?: "withdrawal",
                    transactionDate = firstUpdated?.date ?: "",
                    sourceName = firstUpdated?.sourceName ?: "",
                    sourceId = firstUpdated?.sourceId,
                    destinationName = firstUpdated?.destinationName ?: "",
                    destinationId = firstUpdated?.destinationId,
                    saveSuccess = true
                )
            } catch (e: FireflyError) {
                _state.value = _state.value.copy(
                    saving = false,
                    saveError = "Firefly error: ${e.message}"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    saving = false,
                    saveError = "Save failed: ${e.message}"
                )
            } finally {
                api.close()
            }
        }
    }

    fun clearSaveSuccess() {
        _state.value = _state.value.copy(saveSuccess = false)
    }
}
