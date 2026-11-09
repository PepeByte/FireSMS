package com.firesms.app.domain.parser

import com.firesms.app.data.local.entity.ParserRule
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmsParserDiagnoseRuleTest {

    private val receivedAt = LocalDateTime.of(2026, 4, 19, 16, 30, 47)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val rule = ParserRule(
        id = "rule",
        name = "Bank debit",
        senderPattern = "BANK",
        bodyPattern = "debited by (?<amount>\\S+) at (?<merchant>[A-Z]+)(?: on (?<date>\\S+))?",
        amountTemplate = "{{amount}}",
        descriptionSource = "template",
        descriptionValue = "{{merchant}}",
        type = "withdrawal"
    )

    private fun diagnose(rule: ParserRule, body: String, sender: String = "VM-BANK") =
        SmsParser.diagnoseRule(rule, sender, body, receivedAt)

    @Test
    fun `reports which step failed`() {
        assertEquals(RuleDiagnosis.SenderMismatch("VM-OTHER"), diagnose(rule, "debited by 10 at SHOP.", "VM-OTHER"))
        assertEquals(RuleDiagnosis.BodyMismatch, diagnose(rule, "credited by 10"))

        val amount = assertIs<RuleDiagnosis.AmountInvalid>(diagnose(rule, "debited by 0.00 at SHOP."))
        assertEquals("0.00", amount.resolved)
        assertTrue(amount.missingGroups.isEmpty())

        val missing = assertIs<RuleDiagnosis.AmountInvalid>(
            diagnose(rule.copy(amountTemplate = "{{total}}"), "debited by 10 at SHOP.")
        )
        assertEquals(listOf("total"), missing.missingGroups)

        assertIs<RuleDiagnosis.InvalidPattern>(diagnose(rule.copy(bodyPattern = "[oops"), "x"))
    }

    @Test
    fun `match lists named groups and the date source`() {
        val matched = assertIs<RuleDiagnosis.Matched>(diagnose(rule, "debited by 10 at SHOP."))
        assertEquals(listOf("amount" to "10", "merchant" to "SHOP", "date" to null), matched.groups)
        assertIs<DateResolution.NotConfigured>(matched.date)
        assertEquals(receivedAt, matched.transaction.date)

        val dated = rule.copy(datePattern = "{{date}}", dateFormat = "dd-MM-yyyy")
        val parsed = assertIs<RuleDiagnosis.Matched>(diagnose(dated, "debited by 10 at SHOP on 01-03-2026"))
        assertIs<DateResolution.Parsed>(parsed.date)
        assertEquals(parsed.date.millis, parsed.transaction.date)

        val bad = assertIs<RuleDiagnosis.Matched>(diagnose(dated, "debited by 10 at SHOP on 2026/03/01"))
        val fallback = assertIs<DateResolution.Fallback>(bad.date)
        assertEquals(receivedAt, fallback.millis)
        assertTrue(fallback.reason.contains("2026/03/01"))
    }

    @Test
    fun `diagnosis agrees with applyRule`() {
        listOf("debited by 10 at SHOP.", "debited by 0 at SHOP.", "nothing").forEach { body ->
            val applied = SmsParser.applyRule(rule, "VM-BANK", body, receivedAt)
            val diagnosis = diagnose(rule, body)
            assertEquals(applied, (diagnosis as? RuleDiagnosis.Matched)?.transaction, body)
        }
    }
}
