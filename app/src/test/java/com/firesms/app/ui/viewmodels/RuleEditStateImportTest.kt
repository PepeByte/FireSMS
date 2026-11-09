package com.firesms.app.ui.viewmodels

import com.firesms.app.domain.rules.ImportedRuleFields
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleEditStateImportTest {

    @Test
    fun `import replaces generated fields and preserves manually configured fields`() {
        val original = RuleEditState(
            id = "existing-id",
            name = "Existing name",
            senderPattern = "BANK",
            bodyPattern = "old body",
            amountTemplate = "old amount",
            descriptionSource = "template",
            descriptionValue = "Old description",
            accountKeyword = "1234",
            ownAccountName = "Checking",
            ownAccountId = "account-1",
            otherAccountName = "Merchant",
            otherAccountId = "account-2",
            type = "deposit",
            priorityText = "3",
            datePattern = "old date",
            dateFormat = "old format",
            remarksPattern = "old remarks",
            remarksTemplate = "old template",
            saving = true
        )
        val imported = ImportedRuleFields(
            bodyPattern = "(?<amount>\\d+)",
            amountTemplate = "{{amount}}",
            descriptionSource = "template",
            descriptionValue = "Imported description",
            type = "withdrawal",
            datePattern = "",
            dateFormat = "",
            remarksPattern = "",
            remarksTemplate = "Imported notes"
        )

        val result = original.withImportedRuleFields(imported)

        assertEquals("(?<amount>\\d+)", result.bodyPattern)
        assertEquals("{{amount}}", result.amountTemplate)
        assertEquals("Imported description", result.descriptionValue)
        assertEquals("withdrawal", result.type)
        assertEquals("Imported notes", result.remarksTemplate)

        assertEquals(original.id, result.id)
        assertEquals(original.name, result.name)
        assertEquals(original.senderPattern, result.senderPattern)
        assertEquals(original.accountKeyword, result.accountKeyword)
        assertEquals(original.ownAccountName, result.ownAccountName)
        assertEquals(original.ownAccountId, result.ownAccountId)
        assertEquals(original.otherAccountName, result.otherAccountName)
        assertEquals(original.otherAccountId, result.otherAccountId)
        assertEquals(original.priorityText, result.priorityText)
        assertEquals(original.saving, result.saving)
    }

    @Test
    fun `undo restores the fields an import replaced`() {
        val before = RuleEditState(
            bodyPattern = "old (?<amount>\\d+)",
            descriptionValue = "Old",
            type = "deposit",
            otherAccountName = "Employer",
            otherAccountId = "7",
            remarksTemplate = "old"
        )
        val imported = before.withTransactionType("withdrawal").withImportedRuleFields(
            ImportedRuleFields(
                bodyPattern = "(?<amount>\\d+)",
                amountTemplate = "{{amount}}",
                descriptionSource = "template",
                descriptionValue = "New",
                type = "withdrawal",
                datePattern = "",
                dateFormat = "",
                remarksPattern = "",
                remarksTemplate = ""
            )
        ).copy(name = "Typed after import")

        val undone = imported.withImportUndone(before)
        assertEquals(before.bodyPattern, undone.bodyPattern)
        assertEquals("Old", undone.descriptionValue)
        assertEquals("deposit", undone.type)
        assertEquals("7", undone.otherAccountId)
        assertEquals("old", undone.remarksTemplate)
        assertEquals("Typed after import", undone.name)
    }

    @Test
    fun `candidates are computed when accounts or type change`() {
        fun account(id: String, name: String, type: String) = com.firesms.app.data.remote.AccountResource(
            id = id,
            attributes = com.firesms.app.data.remote.AccountAttributes(name = name, type = type)
        )
        val state = RuleEditState().withAccounts(
            listOf(account("1", "savings", "asset"), account("2", "Checking", "asset"), account("3", "Shop", "expense"))
        )
        assertEquals(listOf("Checking", "savings"), state.ownAccountCandidates.map { it.attributes.name })
        assertEquals(listOf("Shop"), state.otherAccountCandidates.map { it.attributes.name })
        assertEquals(
            listOf("Checking", "savings"),
            state.withTransactionType("transfer").otherAccountCandidates.map { it.attributes.name }
        )
    }

    @Test
    fun `changing type without loaded accounts drops the unverifiable other account id`() {
        val state = RuleEditState(type = "withdrawal", otherAccountName = "Example Store", otherAccountId = "5")

        val transfer = state.withTransactionType("transfer")
        assertEquals("Example Store", transfer.otherAccountName)
        assertEquals(null, transfer.otherAccountId)

        assertEquals("5", state.withTransactionType("withdrawal").otherAccountId)
    }
}
