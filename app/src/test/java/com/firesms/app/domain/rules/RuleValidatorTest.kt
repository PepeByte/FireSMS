package com.firesms.app.domain.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleValidatorTest {

    private fun validate(
        bodyPattern: String = "(?<amount>[0-9]+) at (?<merchant>.+)",
        amountTemplate: String = "{{amount}}",
        descriptionSource: String = "template",
        descriptionValue: String = "{{merchant}}",
        type: String = "withdrawal",
        datePattern: String = "",
        dateFormat: String = "",
        remarksPattern: String = "",
        remarksTemplate: String = "",
        label: (String) -> String = { it }
    ) = RuleValidator.validate(
        bodyPattern, amountTemplate, descriptionSource, descriptionValue, type,
        datePattern, dateFormat, remarksPattern, remarksTemplate, label
    )

    @Test
    fun `valid rule passes`() {
        assertNull(validate())
        assertNull(validate(descriptionSource = "regex_group", descriptionValue = "merchant"))
    }

    @Test
    fun `named groups ignore lookbehind constructs`() {
        assertEquals(
            setOf("amount", "merchant"),
            RuleValidator.namedGroups("(?<=Rs)(?<amount>[0-9]+)(?<!x) at (?<merchant>.+)")
        )
    }

    @Test
    fun `invalid date format is rejected`() {
        val error = assertNotNull(validate(datePattern = "{{merchant}}", dateFormat = "yyyy-MM-dd T"))
        assertTrue(error.contains("date format"))
    }

    @Test
    fun `labels are applied to messages`() {
        val error = assertNotNull(
            validate(amountTemplate = "{{total}}", label = { if (it == "bodyPattern") "Body pattern" else it })
        )
        assertEquals("amountTemplate references {{total}}, which is missing from Body pattern.", error)
    }
}
