package com.firesms.app.domain.parser

import com.firesms.app.data.local.entity.ParserRule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsParserSenderFilterTest {

    private val bankRule = ParserRule(
        id = "rule1",
        name = "Bank SMS",
        enabled = true,
        senderPattern = "BANKSMS",
        bodyPattern = ".*",
        amountTemplate = "{{amount}}",
        descriptionSource = "template",
        descriptionValue = "Bank transaction",
        type = "withdrawal",
        priority = 10
    )

    private val shopRule = ParserRule(
        id = "rule2",
        name = "Shop SMS",
        enabled = true,
        senderPattern = "SHOPALERT",
        bodyPattern = ".*",
        amountTemplate = "{{amount}}",
        descriptionSource = "template",
        descriptionValue = "Shop transaction",
        type = "withdrawal",
        priority = 20
    )

    private val disabledRule = bankRule.copy(enabled = false, id = "rule3")

    private val invalidRegexRule = ParserRule(
        id = "rule4",
        name = "Bad regex",
        enabled = true,
        senderPattern = "[invalid",
        bodyPattern = ".*",
        amountTemplate = "{{amount}}",
        descriptionSource = "template",
        descriptionValue = "Bad",
        type = "withdrawal",
        priority = 5
    )

    @Test
    fun `empty enabled rule list returns false`() {
        assertFalse(SmsParser.senderMatchesAnyEnabledRule("BANKSMS", emptyList()))
    }

    @Test
    fun `enabled sender regex matching sender returns true`() {
        assertTrue(SmsParser.senderMatchesAnyEnabledRule("BANKSMS", listOf(bankRule)))
    }

    @Test
    fun `enabled sender regex not matching sender returns false`() {
        assertFalse(SmsParser.senderMatchesAnyEnabledRule("RANDOMSHOP", listOf(bankRule)))
    }

    @Test
    fun `disabled matching rule returns false`() {
        assertFalse(SmsParser.senderMatchesAnyEnabledRule("BANKSMS", listOf(disabledRule)))
    }

    @Test
    fun `invalid sender regex is ignored and later valid rule can still match`() {
        val rules = listOf(invalidRegexRule, bankRule)
        assertTrue(SmsParser.senderMatchesAnyEnabledRule("BANKSMS", rules))
    }
}
