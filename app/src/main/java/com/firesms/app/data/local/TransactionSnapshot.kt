package com.firesms.app.data.local

import com.firesms.app.data.remote.FireflyTransactionAttributes
import com.firesms.app.data.remote.TransactionRequest
import com.firesms.app.domain.model.ParsedTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class StoredTransactionSnapshot(
    val schemaVersion: Int = 1,
    val groupTitle: String? = null,
    val transactions: List<StoredTransactionSplit> = emptyList()
)

@Serializable
data class StoredTransactionSplit(
    val transactionJournalId: String? = null,
    val type: String = "",
    val date: String = "",
    val amount: String = "",
    val currencyCode: String? = null,
    val currencySymbol: String? = null,
    val description: String = "",
    val sourceId: String? = null,
    val sourceName: String? = null,
    val destinationId: String? = null,
    val destinationName: String? = null,
    val categoryName: String? = null,
    val budgetName: String? = null,
    val notes: String? = null,
    val order: Int = 0
)

private val snapshotJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun StoredTransactionSnapshot.encode(): String = snapshotJson.encodeToString(this)

fun decodeTransactionSnapshot(value: String?): StoredTransactionSnapshot? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        snapshotJson.decodeFromString<StoredTransactionSnapshot>(value).takeIf { it.schemaVersion == 1 }
    }.getOrNull()
}

/** Compare user-visible transaction fields while ignoring Firefly-generated IDs and formatting. */
fun StoredTransactionSnapshot.semanticallyEquals(other: StoredTransactionSnapshot): Boolean {
    fun String?.clean(): String = this?.trim().orEmpty()
    fun String.cleanDate(): String = trim().replace('T', ' ').take(19)
    fun String.cleanAmount(): String = runCatching {
        BigDecimal(trim()).stripTrailingZeros().toPlainString()
    }.getOrElse { trim() }
    fun StoredTransactionSplit.normalized(): List<String> = listOf(
        type.trim().lowercase(),
        date.cleanDate(),
        amount.cleanAmount(),
        description.trim(),
        categoryName.clean(),
        budgetName.clean(),
        notes.clean()
    )
    fun accountsEqual(
        firstId: String?, firstName: String?, secondId: String?, secondName: String?
    ): Boolean {
        val idA = firstId.clean()
        val idB = secondId.clean()
        if (idA.isNotEmpty() && idB.isNotEmpty()) return idA == idB
        val nameA = firstName.clean()
        val nameB = secondName.clean()
        if (nameA.isNotEmpty() && nameB.isNotEmpty()) return nameA == nameB
        // A create request may identify an account only by ID while Firefly returns its name.
        return true
    }

    val thisTitle = groupTitle.clean()
    val otherTitle = other.groupTitle.clean()
    return thisTitle == otherTitle &&
        transactions.size == other.transactions.size &&
        transactions.zip(other.transactions).all { (first, second) ->
            first.normalized() == second.normalized() &&
                accountsEqual(first.sourceId, first.sourceName, second.sourceId, second.sourceName) &&
                accountsEqual(
                    first.destinationId,
                    first.destinationName,
                    second.destinationId,
                    second.destinationName
                )
        }
}

fun ParsedTransaction.toStoredSnapshot(): StoredTransactionSnapshot {
    val formattedDate = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(date),
        ZoneId.systemDefault()
    ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    return StoredTransactionSnapshot(
        transactions = listOf(
            StoredTransactionSplit(
                type = transactionType,
                date = formattedDate,
                amount = amount,
                description = description,
                sourceId = sourceAccountId,
                sourceName = sourceAccountName,
                destinationName = destinationAccountName,
                categoryName = categoryName,
                budgetName = budgetName,
                notes = notes
            )
        )
    )
}

fun TransactionRequest.toStoredSnapshot(): StoredTransactionSnapshot = StoredTransactionSnapshot(
    transactions = transactions.mapIndexed { index, transaction ->
        StoredTransactionSplit(
            type = transaction.type,
            date = transaction.date,
            amount = transaction.amount,
            description = transaction.description,
            sourceId = transaction.sourceId,
            sourceName = transaction.sourceName,
            destinationName = transaction.destinationName,
            categoryName = transaction.categoryName,
            budgetName = transaction.budgetName,
            notes = transaction.notes,
            order = index
        )
    }
)

fun FireflyTransactionAttributes.toStoredSnapshot(): StoredTransactionSnapshot = StoredTransactionSnapshot(
    groupTitle = groupTitle,
    transactions = transactions.map { transaction ->
        StoredTransactionSplit(
            transactionJournalId = transaction.transactionJournalId,
            type = transaction.type,
            date = transaction.date,
            amount = transaction.amount,
            currencyCode = transaction.currencyCode,
            currencySymbol = transaction.currencySymbol,
            description = transaction.description,
            sourceId = transaction.sourceId,
            sourceName = transaction.sourceName,
            destinationId = transaction.destinationId,
            destinationName = transaction.destinationName,
            categoryName = transaction.categoryName,
            budgetName = transaction.budgetName,
            notes = transaction.notes,
            order = transaction.order
        )
    }
)
