package com.firesms.app.domain.rules

import com.firesms.app.data.local.entity.ParserRule

/**
 * A parser rule names two accounts: the user's own account (the one the SMS is about) and the
 * other party. Firefly III needs them on different sides depending on the transaction type.
 */
object RuleAccounts {
    private val LIABILITY_TYPES = setOf("liability", "liabilities")

    /** Firefly account types that can be the user's own account. */
    val OWN_ACCOUNT_TYPES: Set<String> = setOf("asset") + LIABILITY_TYPES

    /** Firefly account types that make sense as the other party for [transactionType]. */
    fun otherAccountTypes(transactionType: String): Set<String> = when (transactionType) {
        "deposit" -> setOf("revenue") + LIABILITY_TYPES
        "transfer" -> OWN_ACCOUNT_TYPES
        else -> setOf("expense") + LIABILITY_TYPES
    }

    /**
     * Firefly creates missing expense and revenue accounts by name, but a transfer must go to an
     * existing account of the user's.
     */
    fun otherAccountRequired(transactionType: String): Boolean = transactionType == "transfer"

    /** The user's own account is the destination of a deposit and the source otherwise. */
    fun ownAccountIsDestination(transactionType: String): Boolean = transactionType == "deposit"
}

/**
 * Before the editor had separate own/other account fields, deposit rules could only work by
 * storing the user's own account in the destination name. Treat that shape as the own account.
 */
fun ParserRule.withLegacyDepositAccountResolved(): ParserRule {
    val legacyDeposit = type == "deposit" &&
        sourceAccountName == null && sourceAccountId == null && sourceAccountKeyword == null &&
        destinationAccountName != null && destinationAccountId == null
    return if (legacyDeposit) copy(sourceAccountName = destinationAccountName, destinationAccountName = null) else this
}
