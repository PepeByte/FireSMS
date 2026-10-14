package com.firesms.app.domain.parser

import com.firesms.app.data.local.entity.ParserRule
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SmsParserApplyRuleTest {

    private val receivedAt = LocalDateTime.of(2026, 4, 19, 16, 30, 47)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val rule = ParserRule(
        id = "rule",
        name = "Bank debit",
        senderPattern = "BANK",
        bodyPattern = "debited by USD (?<amount>[0-9,]+(?:[.][0-9]{1,2})?) at (?<merchant>[^.]+)",
        amountTemplate = "{{amount}}",
        descriptionSource = "template",
        descriptionValue = "{{merchant}}",
        type = "withdrawal"
    )

    private fun apply(rule: ParserRule, body: String, sender: String = "VM-BANK") =
        SmsParser.applyRule(rule, sender, body, receivedAt)

    @Test
    fun `parses amount and description`() {
        val result = assertNotNull(apply(rule, "A/C debited by USD 1,234.50 at EXAMPLE STORE."))
        assertEquals("1234.50", result.amount)
        assertEquals("EXAMPLE STORE", result.description)
        assertEquals(receivedAt, result.date)
    }

    @Test
    fun `template referencing a missing group does not throw`() {
        val broken = rule.copy(descriptionValue = "{{shop}}", remarksTemplate = "Ref {{ref}}")
        val result = assertNotNull(apply(broken, "debited by USD 10 at SHOP."))
        // Unresolved description falls back to the rule name; unresolved remarks become blank.
        assertEquals("Bank debit", result.description)
        assertEquals("Ref", result.notes)

        val brokenAmount = rule.copy(amountTemplate = "{{total}}")
        assertNull(apply(brokenAmount, "debited by USD 10 at SHOP."))

        val brokenGroupSource = rule.copy(descriptionSource = "regex_group", descriptionValue = "")
        assertEquals("Bank debit", apply(brokenGroupSource, "debited by USD 10 at SHOP.")?.description)
    }

    @Test
    fun `amount that is not a positive number does not match`() {
        val loose = rule.copy(bodyPattern = "debited by (?<amount>\\S+) at (?<merchant>[^.]+)")
        assertNull(apply(loose, "debited by 1.234.567 at SHOP."))
        assertNull(apply(loose, "debited by 0.00 at SHOP."))
        assertEquals("1234.50", apply(loose, "debited by Rs.1,234.50 at SHOP.")?.amount)
    }

    @Test
    fun `invalid regex or date format never throws`() {
        assertNull(apply(rule.copy(bodyPattern = "[unclosed"), "debited by USD 1 at X."))
        val badDate = rule.copy(datePattern = "{{merchant}}", dateFormat = "yyyy-MM-dd T")
        assertEquals(receivedAt, apply(badDate, "debited by USD 1 at X.")?.date)
    }

    @Test
    fun `date parsing is case insensitive and supports date-only formats`() {
        val dated = rule.copy(
            bodyPattern = "debited by USD (?<amount>[0-9.]+) on (?<date>[0-9]{2}-[A-Za-z]{3}-[0-9]{4} [0-9]{2}:[0-9]{2})",
            descriptionValue = "Card",
            datePattern = "{{date}}",
            dateFormat = "dd-MMM-yyyy HH:mm"
        )
        val expected = LocalDateTime.of(2026, 3, 5, 9, 15)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, apply(dated, "debited by USD 5 on 05-MAR-2026 09:15")?.date)

        val dateOnly = rule.copy(
            bodyPattern = "debited by USD (?<amount>[0-9.]+) on (?<date>[0-9/]+)",
            descriptionValue = "Card",
            datePattern = "{{date}}",
            dateFormat = "dd/MM/yy"
        )
        val expectedDateOnly = LocalDateTime.of(2026, 3, 5, 16, 30, 47)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expectedDateOnly, apply(dateOnly, "debited by USD 5 on 05/03/26")?.date)
    }

    @Test
    fun `deposit puts the user's account on the destination side`() {
        val deposit = rule.copy(
            type = "deposit",
            sourceAccountName = "Checking",
            sourceAccountId = "42",
            destinationAccountName = "Employer"
        )
        val result = assertNotNull(apply(deposit, "debited by USD 10 at SHOP."))
        assertEquals("Employer", result.sourceAccountName)
        assertNull(result.sourceAccountId)
        assertEquals("Checking", result.destinationAccountName)
        assertEquals("42", result.destinationAccountId)

        // Without an own account the legacy mapping is untouched.
        val legacy = rule.copy(type = "deposit", destinationAccountName = "Checking")
        assertEquals("Checking", apply(legacy, "debited by USD 10 at SHOP.")?.destinationAccountName)

        val withdrawal = rule.copy(
            sourceAccountName = "Checking",
            sourceAccountId = "42",
            destinationAccountName = "Example Store",
            destinationAccountId = "7"
        )
        val paid = assertNotNull(apply(withdrawal, "debited by USD 10 at SHOP."))
        assertEquals("42", paid.sourceAccountId)
        assertEquals("Example Store", paid.destinationAccountName)
        assertEquals("7", paid.destinationAccountId)

        val pickedPayer = deposit.copy(destinationAccountId = "9")
        assertEquals("9", apply(pickedPayer, "debited by USD 10 at SHOP.")?.sourceAccountId)
    }

    @Test
    fun `account keyword token is stripped of punctuation`() {
        val keyword = rule.copy(sourceAccountKeyword = "XX12")
        assertEquals("XX1234", apply(keyword, "A/C XX1234, debited by USD 10 at SHOP.")?.sourceAccountName)
    }

    @Test
    fun `remarks template takes precedence over pattern`() {
        val remarks = rule.copy(
            bodyPattern = rule.bodyPattern + "[.] Ref (?<ref>[A-Z0-9]+)",
            remarksPattern = "{{merchant}}",
            remarksTemplate = "Reference {{ref}}"
        )
        assertEquals("Reference ABC123", apply(remarks, "debited by USD 1 at SHOP. Ref ABC123")?.notes)
        assertEquals(
            "SHOP",
            apply(remarks.copy(remarksTemplate = null), "debited by USD 1 at SHOP. Ref ABC123")?.notes
        )
    }

    @Test
    fun `sender mismatch does not match`() {
        assertNull(apply(rule, "debited by USD 1 at SHOP.", sender = "OTHER"))
    }
}
