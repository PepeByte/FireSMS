package com.firesms.app.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DuplicateTransactionTest {

    private val body = TransactionBody(
        type = "withdrawal",
        date = "2026-11-06 10:15:30",
        amount = "1250.00",
        description = "Coffee",
        externalId = "hash-1"
    )

    private fun group(id: String, split: FireflyTransactionSplit) =
        FireflyTransactionData(id, FireflyTransactionAttributes(transactions = listOf(split)))

    @Test
    fun `matches on external id regardless of other fields`() {
        val candidates = listOf(
            group("1", FireflyTransactionSplit(type = "deposit", amount = "1", date = "2026-11-06T00:00:00+00:00")),
            group("2", FireflyTransactionSplit(type = "deposit", amount = "9", date = "2026-11-06T01:00:00+00:00", externalId = "hash-1"))
        )
        assertEquals("2", findDuplicateTransaction(body, candidates)?.id)
    }

    @Test
    fun `matches on type amount and wall clock time`() {
        val candidates = listOf(
            group("1", FireflyTransactionSplit(type = "withdrawal", amount = "1250.000000000000", date = "2026-11-06T10:15:30+05:45"))
        )
        assertEquals("1", findDuplicateTransaction(body, candidates)?.id)
    }

    @Test
    fun `different time amount or type is not a duplicate`() {
        val candidates = listOf(
            group("1", FireflyTransactionSplit(type = "withdrawal", amount = "1250", date = "2026-11-06T10:15:31+05:45")),
            group("2", FireflyTransactionSplit(type = "withdrawal", amount = "1251", date = "2026-11-06T10:15:30+05:45")),
            group("3", FireflyTransactionSplit(type = "deposit", amount = "1250", date = "2026-11-06T10:15:30+05:45"))
        )
        assertNull(findDuplicateTransaction(body, candidates))
    }
}
