package com.firesms.app.ui.viewmodels

import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.data.remote.AccountAttributes
import com.firesms.app.data.remote.AccountResource
import com.firesms.app.domain.rules.RuleAccounts
import com.firesms.app.domain.rules.withLegacyDepositAccountResolved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuleAccountFieldsTest {

    private val accounts = listOf(
        AccountResource("1", AccountAttributes("Checking", "asset")),
        AccountResource("2", AccountAttributes("Savings", "asset")),
        AccountResource("3", AccountAttributes("Example Store", "expense")),
        AccountResource("4", AccountAttributes("Employer", "revenue")),
        AccountResource("5", AccountAttributes("Credit Card", "liabilities"))
    )

    @Test
    fun `candidates follow the transaction type`() {
        fun names(types: Set<String>) = accounts.ofTypes(types).map { it.attributes.name }
        assertEquals(listOf("Checking", "Credit Card", "Savings"), names(RuleAccounts.OWN_ACCOUNT_TYPES))
        assertEquals(listOf("Credit Card", "Example Store"), names(RuleAccounts.otherAccountTypes("withdrawal")))
        assertEquals(listOf("Credit Card", "Employer"), names(RuleAccounts.otherAccountTypes("deposit")))
        assertEquals(listOf("Checking", "Credit Card", "Savings"), names(RuleAccounts.otherAccountTypes("transfer")))
    }

    @Test
    fun `resolve links by id first then by case-insensitive name`() {
        val own = accounts.ofTypes(RuleAccounts.OWN_ACCOUNT_TYPES)
        // Renamed in Firefly: the stored ID still wins and picks up the new name.
        assertEquals("Checking" to "1", resolveAccount("Old name", "1", own, accountsLoaded = true))
        assertEquals("Savings" to "2", resolveAccount(" savings ", null, own, accountsLoaded = true))
        assertEquals("Unknown" to null, resolveAccount("Unknown", "99", own, accountsLoaded = true))
        // Nothing loaded: keep what was stored.
        assertEquals("Unknown" to "99", resolveAccount("Unknown", "99", emptyList(), accountsLoaded = false))
    }

    @Test
    fun `checks distinguish creatable and missing accounts`() {
        assertEquals(AccountCheck.EMPTY, checkOwnAccount("", null, true))
        assertEquals(AccountCheck.UNVERIFIED, checkOwnAccount("Checking", null, false))
        assertEquals(AccountCheck.FOUND, checkOwnAccount("Checking", "1", true))
        assertEquals(AccountCheck.NOT_FOUND, checkOwnAccount("Chequing", null, true))
        assertEquals(AccountCheck.WILL_BE_CREATED, checkOtherAccount("New Shop", null, "withdrawal", true))
        assertEquals(AccountCheck.WILL_BE_CREATED, checkOtherAccount("New Payer", null, "deposit", true))
        assertEquals(AccountCheck.NOT_FOUND, checkOtherAccount("Nowhere", null, "transfer", true))
    }

    @Test
    fun `validation requires an own account and a transfer target`() {
        assertNotNull(validateRuleAccounts("withdrawal", "", null, "", "", null, true))
        assertNull(validateRuleAccounts("withdrawal", "", null, "XX12", "", null, true))
        assertNull(validateRuleAccounts("withdrawal", "Checking", "1", "", "New Shop", null, true))
        assertNotNull(validateRuleAccounts("withdrawal", "Chequing", null, "", "", null, true))
        // Accounts could not be loaded: names are not checked.
        assertNull(validateRuleAccounts("withdrawal", "Chequing", null, "", "", null, false))
        assertNotNull(validateRuleAccounts("transfer", "Checking", "1", "", "", null, true))
        assertNull(validateRuleAccounts("transfer", "Checking", "1", "", "Savings", "2", true))
    }

    @Test
    fun `legacy deposit rules move the stored account to the own side`() {
        val legacy = ParserRule(
            id = "r", name = "Salary", senderPattern = "BANK", bodyPattern = "(?<amount>[0-9]+)",
            amountTemplate = "{{amount}}", descriptionSource = "template", descriptionValue = "Salary",
            type = "deposit", destinationAccountName = "Checking"
        )
        val resolved = legacy.withLegacyDepositAccountResolved()
        assertEquals("Checking", resolved.sourceAccountName)
        assertNull(resolved.destinationAccountName)

        val modern = legacy.copy(sourceAccountName = "Checking", destinationAccountName = "Employer")
        assertEquals(modern, modern.withLegacyDepositAccountResolved())
        val withdrawal = legacy.copy(type = "withdrawal")
        assertEquals(withdrawal, withdrawal.withLegacyDepositAccountResolved())
    }

    @Test
    fun `labels follow the money`() {
        assertEquals("Paid from (your account)", accountRoleText("withdrawal").ownLabel)
        assertEquals("Received from (optional)", accountRoleText("deposit").otherLabel)
        assertEquals("Moved to (your account)", accountRoleText("transfer").otherLabel)
    }
}
