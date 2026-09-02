package com.firesms.app.data.local

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionSnapshotTest {
    private val original = StoredTransactionSnapshot(
        transactions = listOf(
            StoredTransactionSplit(
                type = "withdrawal",
                date = "2026-03-20 10:30:00",
                amount = "12.50",
                description = "Coffee",
                sourceName = "Checking",
                destinationName = "Coffee shop",
                notes = "Imported from FireSMS"
            )
        )
    )

    @Test
    fun semanticComparisonIgnoresServerFormattingAndGeneratedIds() {
        val serverCopy = original.copy(
            transactions = listOf(
                original.transactions.single().copy(
                    transactionJournalId = "42",
                    date = "2026-03-20T10:30:00+01:00",
                    amount = "12.500",
                    sourceId = "1",
                    destinationId = "2"
                )
            )
        )

        assertTrue(original.semanticallyEquals(serverCopy))
    }

    @Test
    fun semanticComparisonDetectsVisibleEdit() {
        val changed = original.copy(
            transactions = listOf(original.transactions.single().copy(description = "Lunch"))
        )

        assertFalse(original.semanticallyEquals(changed))
    }
}
