package com.firesms.app.domain.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenderPatternsTest {

    private fun matches(pattern: String, sender: String) = Regex(pattern).containsMatchIn(sender)

    @Test
    fun `bank header ignores the operator prefix and category suffix`() {
        val pattern = SenderPatterns.suggestFor("AD-HDFCBK")
        assertEquals("(?i)^([A-Z]{2}-)?HDFCBK(-[A-Z])?$", pattern)
        listOf("AD-HDFCBK", "VM-HDFCBK", "JD-HDFCBK-S", "HDFCBK", "vm-hdfcbk").forEach {
            assertTrue(matches(pattern, it), it)
        }
        listOf("AD-HDFCBKX", "AD-ICICIB", "AD-XHDFCBK").forEach {
            assertFalse(matches(pattern, it), it)
        }
        assertEquals(pattern, SenderPatterns.suggestFor("VM-HDFCBK-T"))
    }

    @Test
    fun `other senders are matched exactly with readable escaping`() {
        val pattern = SenderPatterns.suggestFor("+919812345678")
        assertEquals("^\\+919812345678$", pattern)
        assertTrue(matches(pattern, "+919812345678"))
        assertFalse(matches(pattern, "+9198123456789"))

        val dotted = SenderPatterns.suggestFor("My.Bank (Alerts)")
        assertEquals("^My\\.Bank \\(Alerts\\)$", dotted)
        assertTrue(matches(dotted, "My.Bank (Alerts)"))
        assertFalse(matches(dotted, "MyXBank (Alerts)"))
    }

    @Test
    fun `describe shows generated and literal patterns in plain words`() {
        assertEquals("HDFCBK (any prefix)", SenderPatterns.describe(SenderPatterns.suggestFor("AD-HDFCBK")))
        assertEquals("+919812345678", SenderPatterns.describe("^\\+919812345678$"))
        assertEquals("AD-HDFCBK", SenderPatterns.describe("\\QAD-HDFCBK\\E"))
        assertEquals("BANK|CARD", SenderPatterns.describe("BANK|CARD"))
        assertNull(SenderPatterns.explain("BANK|CARD"))
        assertTrue(SenderPatterns.explain(SenderPatterns.suggestFor("AD-HDFCBK"))!!.contains("HDFCBK"))
    }
}
