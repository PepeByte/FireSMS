package com.firesms.app.ui.viewmodels

import com.firesms.app.data.remote.AccountResource
import com.firesms.app.domain.rules.RuleAccounts

/** Plain-language wording for a rule's two accounts, which depends on the transaction type. */
data class AccountRoleText(
    val summary: String,
    val ownLabel: String,
    val ownPlaceholder: String,
    val otherLabel: String,
    val otherPlaceholder: String
)

fun accountRoleText(transactionType: String): AccountRoleText = when (transactionType) {
    "deposit" -> AccountRoleText(
        summary = "Money comes into your account from someone else.",
        ownLabel = "Received into (your account)",
        ownPlaceholder = "Search your Firefly accounts",
        otherLabel = "Received from (optional)",
        otherPlaceholder = "Payer, e.g. Employer"
    )
    "transfer" -> AccountRoleText(
        summary = "Money moves between two of your own accounts.",
        ownLabel = "Moved from (your account)",
        ownPlaceholder = "Search your Firefly accounts",
        otherLabel = "Moved to (your account)",
        otherPlaceholder = "Search your Firefly accounts"
    )
    else -> AccountRoleText(
        summary = "Money leaves your account to a merchant or payee.",
        ownLabel = "Paid from (your account)",
        ownPlaceholder = "Search your Firefly accounts",
        otherLabel = "Paid to (optional)",
        otherPlaceholder = "Merchant or payee, e.g. Example Store"
    )
}

/** Result of checking a typed account name against the accounts loaded from Firefly. */
enum class AccountCheck {
    /** Nothing entered. */
    EMPTY,
    /** Firefly accounts could not be loaded, so the name cannot be checked. */
    UNVERIFIED,
    /** Matches an existing Firefly account of a suitable type. */
    FOUND,
    /** Not in Firefly; Firefly will create it on the first transaction. */
    WILL_BE_CREATED,
    /** Not in Firefly, and Firefly will reject it. */
    NOT_FOUND
}

private val BY_NAME: Comparator<AccountResource> = compareBy(String.CASE_INSENSITIVE_ORDER) { it.attributes.name }

/** Firefly accounts that fit the given account types, sorted by name. */
fun List<AccountResource>.ofTypes(types: Set<String>): List<AccountResource> =
    filter { it.attributes.type in types }.sortedWith(BY_NAME)

/** ID of the account whose name matches [name] exactly, ignoring case and surrounding spaces. */
fun List<AccountResource>.idForName(name: String): String? =
    firstOrNull { it.attributes.name.equals(name.trim(), ignoreCase = true) }?.id

/**
 * Re-links a name/ID pair to the loaded Firefly accounts: a still-valid ID wins (picking up a
 * renamed account's current name), then an exact case-insensitive name match. Without loaded
 * accounts nothing can be checked, so the pair is kept as is.
 */
fun resolveAccount(
    name: String,
    id: String?,
    candidates: List<AccountResource>,
    accountsLoaded: Boolean
): Pair<String, String?> {
    if (!accountsLoaded) return name to id
    candidates.firstOrNull { id != null && it.id == id }
        ?.let { return it.attributes.name to it.id }
    candidates.firstOrNull { it.attributes.name.equals(name.trim(), ignoreCase = true) }
        ?.let { return it.attributes.name to it.id }
    return name to null
}

fun checkOwnAccount(name: String, id: String?, accountsLoaded: Boolean): AccountCheck = when {
    name.isBlank() -> AccountCheck.EMPTY
    !accountsLoaded -> AccountCheck.UNVERIFIED
    id != null -> AccountCheck.FOUND
    else -> AccountCheck.NOT_FOUND
}

fun checkOtherAccount(
    name: String,
    id: String?,
    transactionType: String,
    accountsLoaded: Boolean
): AccountCheck = when {
    name.isBlank() -> AccountCheck.EMPTY
    !accountsLoaded -> AccountCheck.UNVERIFIED
    id != null -> AccountCheck.FOUND
    RuleAccounts.otherAccountRequired(transactionType) -> AccountCheck.NOT_FOUND
    else -> AccountCheck.WILL_BE_CREATED
}

/** Which of the rule's two account fields a problem belongs to. */
enum class AccountSide { OWN, OTHER }

/** Returns a problem with the rule's accounts that would make Firefly reject it, or null. */
fun validateRuleAccounts(
    transactionType: String,
    ownName: String,
    ownId: String?,
    accountKeyword: String,
    otherName: String,
    otherId: String?,
    accountsLoaded: Boolean
): String? = ruleAccountProblem(
    transactionType, ownName, ownId, accountKeyword, otherName, otherId, accountsLoaded
)?.second

/** Like [validateRuleAccounts], but also says which account field the problem is in. */
fun ruleAccountProblem(
    transactionType: String,
    ownName: String,
    ownId: String?,
    accountKeyword: String,
    otherName: String,
    otherId: String?,
    accountsLoaded: Boolean
): Pair<AccountSide, String>? {
    val text = accountRoleText(transactionType)
    if (ownName.isBlank() && accountKeyword.isBlank()) {
        return AccountSide.OWN to "Choose your account for \"${text.ownLabel.substringBefore(" (")}\"."
    }
    if (checkOwnAccount(ownName, ownId, accountsLoaded) == AccountCheck.NOT_FOUND) {
        return AccountSide.OWN to "\"${ownName.trim()}\" is not one of your Firefly accounts. Pick it from the list."
    }
    if (RuleAccounts.otherAccountRequired(transactionType) && otherName.isBlank()) {
        return AccountSide.OTHER to "Choose the account for \"${text.otherLabel.substringBefore(" (")}\"."
    }
    if (checkOtherAccount(otherName, otherId, transactionType, accountsLoaded) == AccountCheck.NOT_FOUND) {
        return AccountSide.OTHER to "\"${otherName.trim()}\" is not one of your Firefly accounts. Pick it from the list."
    }
    return null
}
