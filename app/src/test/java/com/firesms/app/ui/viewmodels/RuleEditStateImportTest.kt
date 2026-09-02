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
            sourceAccountKeyword = "1234",
            sourceAccountName = "Checking",
            sourceAccountId = "account-1",
            destinationAccountName = "Merchant",
            type = "deposit",
            priority = 3,
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
        assertEquals(original.sourceAccountKeyword, result.sourceAccountKeyword)
        assertEquals(original.sourceAccountName, result.sourceAccountName)
        assertEquals(original.sourceAccountId, result.sourceAccountId)
        assertEquals(original.destinationAccountName, result.destinationAccountName)
        assertEquals(original.priority, result.priority)
        assertEquals(original.saving, result.saving)
    }
}
