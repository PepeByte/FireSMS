package com.firesms.app.ui.viewmodels

import com.firesms.app.service.SmsTransactionProcessor.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnparsedMessagesTest {
    @Test
    fun `successful reprocess names the transaction`() {
        assertEquals("Added “Coffee” to Firefly", unparsedOutcomeMessage(Outcome.Created("42", "Coffee")).text)
    }

    @Test
    fun `missing Firefly setup offers settings`() {
        val message = unparsedOutcomeMessage(Outcome.NotConfigured)
        assertEquals("Firefly isn't set up", message.text)
        assertEquals(UiMessageAction.OPEN_SETTINGS, message.action)
    }

    @Test
    fun `failures without a reason never read Error null`() {
        val text = unparsedOutcomeMessage(Outcome.Failed(null)).text
        assertTrue("null" !in text)
        assertEquals("Couldn't add to Firefly: HTTP 422", unparsedOutcomeMessage(Outcome.Failed("HTTP 422")).text)
    }

    @Test
    fun `try all summarizes every outcome`() {
        val tally = TryAllTally().apply {
            add(Outcome.Created("1", "A"))
            add(Outcome.Created("2", "B"))
            add(Outcome.Unparsed)
            add(Outcome.Queued(null, "C"))
        }
        val message = tryAllMessage(tally)
        assertEquals("2 added to Firefly · 1 waiting to sync · 1 still needs a rule", message.text)
        assertNull(message.action)
    }

    @Test
    fun `try all stops with a settings action when Firefly is not set up`() {
        val message = tryAllMessage(TryAllTally(notConfigured = true))
        assertEquals("Firefly isn't set up", message.text)
        assertEquals(UiMessageAction.OPEN_SETTINGS, message.action)
    }
}
