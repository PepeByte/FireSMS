package com.firesms.app.data.local

import com.firesms.app.data.local.entity.SmsLog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailedTransactionRetryTest {
    private fun log(status: String, fireflyId: String? = null) = SmsLog(
        id = 7,
        smsHash = "test-hash",
        sender = "TEST-BANK",
        body = "Test transaction",
        receivedAt = 0,
        status = status,
        fireflyId = fireflyId
    )

    @Test
    fun `failed transactions without a Firefly ID are retryable`() {
        assertTrue(log("failed").canRetryFailedTransaction)
    }

    @Test
    fun `synced transactions are never retryable even without a cached ID`() {
        assertFalse(log("success", "42").canRetryFailedTransaction)
        assertFalse(log("success").canRetryFailedTransaction)
    }

    @Test
    fun `unparsed and pending messages are not bulk retry candidates`() {
        assertFalse(log("unparsed").canRetryFailedTransaction)
        assertFalse(log("pending").canRetryFailedTransaction)
        assertFalse(log("unknown").canRetryFailedTransaction)
    }

    @Test
    fun `failed logs already linked to Firefly are not submitted again`() {
        assertFalse(log("failed", "42").canRetryFailedTransaction)
        assertFalse(log("failed", "").canRetryFailedTransaction)
    }

    @Test
    fun `eligibility changes after a retry succeeds`() {
        val failed = log("failed")
        assertTrue(failed.canRetryFailedTransaction)
        assertFalse(failed.copy(status = "success", fireflyId = "42").canRetryFailedTransaction)
    }
}
