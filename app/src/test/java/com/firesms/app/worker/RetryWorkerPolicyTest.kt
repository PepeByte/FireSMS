package com.firesms.app.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryWorkerPolicyTest {
    @Test
    fun `retries stop once the budget is used`() {
        assertFalse(retriesExhausted(retriesDone = 4, maxRetries = 5))
        assertTrue(retriesExhausted(retriesDone = 5, maxRetries = 5))
        assertTrue(retriesExhausted(retriesDone = 6, maxRetries = 5))
    }

    @Test
    fun `exhausted message names the attempt count and the last error`() {
        assertEquals(
            "Automatic retries stopped after 5 attempts: HTTP 503",
            exhaustedRetriesMessage(5, "HTTP 503")
        )
        assertEquals("Automatic retries stopped after 1 attempt", exhaustedRetriesMessage(1, null))
        assertEquals("Automatic retries stopped after 2 attempts", exhaustedRetriesMessage(2, " "))
    }

    @Test
    fun `backoff doubles from two minutes`() {
        assertEquals(120_000L, retryDelayMillis(1))
        assertEquals(240_000L, retryDelayMillis(2))
        assertEquals(480_000L, retryDelayMillis(3))
    }
}
