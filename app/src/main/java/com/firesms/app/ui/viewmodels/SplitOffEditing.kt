package com.firesms.app.ui.viewmodels

import com.firesms.app.data.local.SplitOffRecord
import com.firesms.app.data.remote.FireflyTransactionSplit
import com.firesms.app.data.remote.TransactionBody
import com.firesms.app.data.remote.TransactionRequest
import com.firesms.app.data.remote.TransactionUpdateRequest
import com.firesms.app.data.remote.TransactionUpdateSplit
import java.math.BigDecimal
import java.math.RoundingMode

/** One split of a split-off: its own amount, description, category, budget and notes. */
data class SplitOffPart(
    /** Local identity so the UI keeps per-split state attached to the right split. */
    val key: Long = 0,
    /** Null until Firefly has stored this split. */
    val transactionJournalId: String? = null,
    val amount: String = "",
    val description: String = "",
    val categoryName: String = "",
    val budgetName: String = "",
    val notes: String = "",
    val amountError: String? = null
)

/**
 * A separate Firefly transaction split off the SMS transaction (e.g. a friend's share moved to a
 * liability account). It shares the payment's type, date and own-side account; the other party's
 * account is its own, and so are its [parts]: one split, or several in a Firefly split transaction.
 */
data class SplitOffState(
    /** Local identity so the UI keeps per-card state attached to the right split-off. */
    val key: Long = 0,
    /** Null until Firefly has stored it. */
    val fireflyId: String? = null,
    val externalId: String,
    val accountName: String = "",
    val accountId: String? = null,
    /** Names the Firefly split transaction; required once there are several [parts]. */
    val groupTitle: String = "",
    val parts: List<SplitOffPart> = listOf(SplitOffPart()),
    /** Whether Firefly holds the "Related" link to the original transaction. */
    val linked: Boolean = false,
    val accountError: String? = null,
    val groupTitleError: String? = null
) {
    val isSaved: Boolean get() = fireflyId != null

    val isSplit: Boolean get() = parts.size > 1

    /** The split-off's whole amount: its splits added up, unfinished ones counting as zero. */
    val amount: String
        get() {
            val parsed = parts.mapNotNull { amountOf(it.amount) }
            return if (parsed.isEmpty()) "" else parsed.fold(BigDecimal.ZERO, BigDecimal::add).toPlainString()
        }

    /** The first split's journal, which the "Related" link points at. */
    val transactionJournalId: String? get() = parts.firstOrNull()?.transactionJournalId

    /** What the Activity screen and the stored record call it. */
    val title: String
        get() = groupTitle.trim().takeIf { isSplit && it.isNotEmpty() } ?: parts.firstOrNull()?.description?.trim().orEmpty()

    fun withoutErrors() = copy(
        accountError = null,
        groupTitleError = null,
        parts = parts.map { it.copy(amountError = null) }
    )
}

/** The parts of the payment every split-off copies from the original transaction. */
internal data class SharedPaymentFields(
    val type: String,
    val date: String,
    val sourceId: String?,
    val sourceName: String?,
    val destinationId: String?,
    val destinationName: String?
)

/** Split-offs exist only for withdrawals (other party = destination) and deposits (other party = source). */
internal fun supportsSplitOff(type: String): Boolean =
    type.trim().lowercase() in setOf("withdrawal", "deposit")

internal const val SPLIT_OFF_NOTES = "Split off with FireSMS"

private data class SplitOffAccounts(
    val sourceId: String?,
    val sourceName: String?,
    val destinationId: String?,
    val destinationName: String?
)

/** Puts the split-off's own account on the other party's side, keeping the payment's own account. */
private fun splitOffAccounts(shared: SharedPaymentFields, piece: SplitOffState): SplitOffAccounts {
    val accountId = piece.accountId?.takeIf { it.isNotBlank() }
    val accountName = piece.accountName.trim().takeIf { it.isNotEmpty() }
    // An id wins over a name in Firefly; drop it when the name was retyped.
    return if (shared.type.trim().equals("deposit", ignoreCase = true)) {
        SplitOffAccounts(accountId, accountName, shared.destinationId, shared.destinationName)
    } else {
        SplitOffAccounts(shared.sourceId, shared.sourceName, accountId, accountName)
    }
}

/** Firefly needs a group title for a split transaction; a single split goes without one. */
private fun splitOffGroupTitle(piece: SplitOffState): String? =
    if (piece.isSplit) piece.groupTitle.trim().ifBlank { null } else null

internal fun buildSplitOffCreateRequest(shared: SharedPaymentFields, piece: SplitOffState): TransactionRequest {
    val accounts = splitOffAccounts(shared, piece)
    return TransactionRequest(
        transactions = piece.parts.map { part ->
            TransactionBody(
                type = shared.type.trim().lowercase(),
                date = shared.date,
                amount = moneyText(part.amount),
                description = part.description.trim(),
                sourceId = accounts.sourceId,
                sourceName = accounts.sourceName,
                destinationId = accounts.destinationId,
                destinationName = accounts.destinationName,
                // Shared by its splits, so a retried create finds the whole group.
                externalId = piece.externalId,
                categoryName = part.categoryName.trim().ifBlank { null },
                budgetName = part.budgetName.trim().ifBlank { null },
                notes = part.notes.trim().ifBlank { SPLIT_OFF_NOTES }
            )
        },
        groupTitle = splitOffGroupTitle(piece)
    )
}

/** Splits left out of the request are deleted by Firefly; new ones (no journal id) are added. */
internal fun buildSplitOffUpdateRequest(shared: SharedPaymentFields, piece: SplitOffState): TransactionUpdateRequest {
    val accounts = splitOffAccounts(shared, piece)
    return TransactionUpdateRequest(
        groupTitle = splitOffGroupTitle(piece),
        transactions = piece.parts.mapIndexed { i, part ->
            TransactionUpdateSplit(
                transactionJournalId = part.transactionJournalId,
                type = shared.type.trim().lowercase(),
                date = shared.date,
                amount = moneyText(part.amount),
                description = part.description.trim(),
                sourceId = accounts.sourceId,
                sourceName = accounts.sourceName,
                destinationId = accounts.destinationId,
                destinationName = accounts.destinationName,
                categoryName = part.categoryName.trim().ifBlank { null },
                budgetName = part.budgetName.trim().ifBlank { null },
                notes = part.notes.trim().ifBlank { null },
                order = i
            )
        }
    )
}

/**
 * The editable state of a stored split-off, read from its Firefly transaction. [nextKey] hands out
 * a local key for each split.
 */
internal fun splitOffFromFirefly(
    key: Long,
    record: SplitOffRecord,
    splits: List<FireflyTransactionSplit>,
    groupTitle: String?,
    nextKey: () -> Long
): SplitOffState {
    val first = splits.first()
    val isDeposit = first.type.trim().equals("deposit", ignoreCase = true)
    return SplitOffState(
        key = key,
        fireflyId = record.fireflyId,
        externalId = record.externalId,
        accountName = (if (isDeposit) first.sourceName else first.destinationName).orEmpty(),
        accountId = if (isDeposit) first.sourceId else first.destinationId,
        groupTitle = groupTitle.orEmpty(),
        parts = splits.mapIndexed { i, split ->
            SplitOffPart(
                key = nextKey(),
                transactionJournalId = split.transactionJournalId ?: record.transactionJournalId.takeIf { i == 0 },
                amount = parseEditAmount(split.amount)?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: split.amount,
                description = split.description,
                categoryName = split.categoryName.orEmpty(),
                budgetName = split.budgetName.orEmpty(),
                notes = split.notes.orEmpty()
            )
        },
        linked = record.linked
    )
}

/** A stored split-off known only from its record, e.g. one whose deletion is still to be finished. */
internal fun splitOffFromRecord(key: Long, partKey: Long, record: SplitOffRecord): SplitOffState = SplitOffState(
    key = key,
    fireflyId = record.fireflyId,
    externalId = record.externalId,
    accountName = record.accountName,
    parts = listOf(
        SplitOffPart(
            key = partKey,
            transactionJournalId = record.transactionJournalId,
            amount = record.amount,
            description = record.description
        )
    ),
    linked = record.linked
)

internal fun moneyText(text: String): String =
    parseEditAmount(text)?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: text.trim()

private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

private fun amountOf(text: String): BigDecimal? = parseEditAmount(text)?.money()

/**
 * The amount for a new split-off, taken from the payer's own share ([rowAmounts]):
 * - when part of the payment is unallocated, the split-off takes the rest;
 * - otherwise the last own split that can be halved is halved, and the split-off takes the other half
 *   (an odd paisa stays with the payer);
 * - with nothing to take from, the amount is left empty for the user to fill in.
 */
internal fun allocateSplitOff(
    total: String,
    rowAmounts: List<String>,
    splitOffAmounts: List<String>
): NewSplitAmounts {
    val allocation = allocationOf(total, rowAmounts + splitOffAmounts)
    val remaining = allocation?.remaining
    if (remaining != null && remaining.signum() > 0) return NewSplitAmounts(rowAmounts, remaining.toPlainString())
    if (remaining != null && remaining.signum() < 0) return NewSplitAmounts(rowAmounts, "")

    val minimum = BigDecimal("0.02")
    val donor = rowAmounts.indexOfLast { (amountOf(it) ?: BigDecimal.ZERO) >= minimum }
    if (donor < 0) return NewSplitAmounts(rowAmounts, "")
    val donorAmount = amountOf(rowAmounts[donor])!!
    val half = donorAmount.divide(BigDecimal(2), 2, RoundingMode.DOWN)
    val kept = donorAmount.subtract(half)
    return NewSplitAmounts(rowAmounts.toMutableList().also { it[donor] = kept.toPlainString() }, half.toPlainString())
}

/**
 * Keeps the payment balanced after a split-off amount changes: the payer's last split becomes
 * whatever [total] the other splits and the split-offs leave. Unfinished input counts as zero, so
 * clearing and retyping an amount still balances. Returns null, leaving the amounts alone, when the
 * total is unknown or the payer's split would drop below 0.01.
 */
internal fun rebalanceForSplitOff(total: String, rowAmounts: List<String>, splitOffAmounts: List<String>): List<String>? {
    val parsedTotal = amountOf(total) ?: return null
    if (rowAmounts.isEmpty()) return null
    val last = rowAmounts.lastIndex
    val others = (rowAmounts.take(last) + splitOffAmounts).sumOf { amountOf(it) ?: BigDecimal.ZERO }
    val adjusted = parsedTotal.subtract(others)
    if (adjusted < BigDecimal("0.01")) return null
    return rowAmounts.toMutableList().also { it[last] = adjusted.toPlainString() }
}

/** Gives a removed split-off's [amount] back to the payer's last split. */
internal fun returnSplitOffAmount(rowAmounts: List<String>, amount: String): List<String> {
    val returned = amountOf(amount) ?: return rowAmounts
    if (rowAmounts.isEmpty()) return rowAmounts
    val last = rowAmounts.lastIndex
    val current = amountOf(rowAmounts[last]) ?: return rowAmounts
    return rowAmounts.toMutableList().also { it[last] = current.add(returned).toPlainString() }
}

/**
 * Errors for split-off fields, keyed by split-off index ([amountErrors] by split-off, then split
 * index). Empty when all are valid.
 */
internal data class SplitOffValidation(
    val amountErrors: Map<Int, Map<Int, String>> = emptyMap(),
    val accountErrors: Map<Int, String> = emptyMap(),
    val groupTitleErrors: Map<Int, String> = emptyMap(),
    val typeError: String? = null
) {
    val isValid: Boolean
        get() = amountErrors.isEmpty() && accountErrors.isEmpty() && groupTitleErrors.isEmpty() && typeError == null
}

internal fun validateSplitOffs(type: String, pieces: List<SplitOffState>): SplitOffValidation {
    if (pieces.isEmpty()) return SplitOffValidation()
    val amountErrors = buildMap {
        pieces.forEachIndexed { i, piece ->
            val errors = buildMap {
                piece.parts.forEachIndexed { j, part ->
                    val amount = parseEditAmount(part.amount)
                    when {
                        part.amount.isBlank() -> put(j, "Required")
                        amount == null || amount.signum() <= 0 -> put(j, "Enter an amount above zero")
                    }
                }
            }
            if (errors.isNotEmpty()) put(i, errors)
        }
    }
    val accountErrors = buildMap {
        pieces.forEachIndexed { i, piece -> if (piece.accountName.isBlank()) put(i, "Choose an account") }
    }
    val groupTitleErrors = buildMap {
        pieces.forEachIndexed { i, piece ->
            if (piece.isSplit && piece.groupTitle.isBlank()) put(i, "Required for split transactions")
        }
    }
    val typeError = if (supportsSplitOff(type)) null
    else "Split-offs work only with withdrawals and deposits"
    return SplitOffValidation(amountErrors, accountErrors, groupTitleErrors, typeError)
}

/** Every split amount of every split-off, as typed, for checking the payment adds up. */
internal fun splitOffPartAmounts(pieces: List<SplitOffState>): List<String> =
    pieces.flatMap { piece -> piece.parts.map { it.amount } }

/**
 * Adds a split to [piece], keeping its total: the new split takes half of the last split that can
 * be halved (an odd paisa stays put). A first extra split also gets a group title, from the
 * first split's description, since Firefly requires one.
 */
internal fun addSplitOffPart(piece: SplitOffState, newKey: Long): SplitOffState {
    val allocation = allocateNewSplit(piece.amount, piece.parts.map { it.amount })
    val parts = piece.parts.mapIndexed { i, part ->
        val amount = allocation.amounts[i]
        if (amount == part.amount) part else part.copy(amount = amount, amountError = null)
    } + SplitOffPart(key = newKey, amount = allocation.newAmount, notes = SPLIT_OFF_NOTES)
    val groupTitle = piece.groupTitle.ifBlank { piece.parts.firstOrNull()?.description?.trim().orEmpty() }
    return piece.copy(parts = parts, groupTitle = groupTitle, groupTitleError = null)
}

/**
 * Removes a split from [piece] and adds its amount to the split before it (or the new first one),
 * so the split-off's total, and the payer's share, stay the same. The last split cannot be removed.
 */
internal fun removeSplitOffPart(piece: SplitOffState, partKey: Long): SplitOffState {
    val index = piece.parts.indexOfFirst { it.key == partKey }
    if (index < 0 || piece.parts.size <= 1) return piece
    val removed = piece.parts[index]
    val remaining = piece.parts.toMutableList().also { it.removeAt(index) }
    val target = (index - 1).coerceAtLeast(0)
    val merged = returnSplitOffAmount(listOf(remaining[target].amount), removed.amount).single()
    remaining[target] = remaining[target].copy(amount = merged, amountError = null)
    return piece.copy(
        parts = remaining,
        groupTitleError = if (remaining.size > 1) piece.groupTitleError else null
    )
}

/** The editor's starting point once split-offs a previous save left half-done are accounted for. */
internal data class RecoveredPayment(
    val paymentTotal: String,
    val rowAmounts: List<String>,
    val splitOffs: List<SplitOffState>,
    val deletedSplitOffs: List<SplitOffState>,
    /** True when a previous save stopped part-way and Save must be tapped again to finish it. */
    val interrupted: Boolean
)

/**
 * Restores what the user intended when a save stopped part-way, from the split-offs grouped by
 * their stored status:
 * - [active]: created, and the original reduced. Part of the payment.
 * - [creating]: created, but the original still holds their amount. Not yet part of the payment, so
 *   the payer's share is reduced again to make room for them.
 * - [removing]: to be deleted, but the original has not got their amount back. Part of the payment,
 *   and their amount is returned to the payer's share.
 * - [removed]: to be deleted, and the original already has their amount back. Not part of it.
 */
internal fun recoverPayment(
    groupTotal: String,
    rowAmounts: List<String>,
    active: List<SplitOffState>,
    creating: List<SplitOffState>,
    removing: List<SplitOffState>,
    removed: List<SplitOffState>
): RecoveredPayment {
    val counted = (active + removing).sumOf { amountOf(it.amount) ?: BigDecimal.ZERO }
    val paymentTotal = ((amountOf(groupTotal) ?: BigDecimal.ZERO) + counted).money().toPlainString()
    var rows = rowAmounts
    removing.forEach { rows = returnSplitOffAmount(rows, it.amount) }
    val splitOffs = active + creating
    if (creating.isNotEmpty()) {
        rows = rebalanceForSplitOff(paymentTotal, rows, splitOffs.map { it.amount }) ?: rows
    }
    return RecoveredPayment(
        paymentTotal = paymentTotal,
        rowAmounts = rows,
        splitOffs = splitOffs,
        deletedSplitOffs = removing + removed,
        interrupted = creating.isNotEmpty() || removing.isNotEmpty() || removed.isNotEmpty()
    )
}
