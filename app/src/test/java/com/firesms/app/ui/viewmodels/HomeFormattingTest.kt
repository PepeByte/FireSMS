package com.firesms.app.ui.viewmodels

import com.firesms.app.data.local.ActivityCounts
import com.firesms.app.data.local.ActivitySummary
import com.firesms.app.data.local.StoredTransactionSnapshot
import com.firesms.app.data.local.StoredTransactionSplit
import com.firesms.app.data.local.encode
import com.firesms.app.service.SmsTransactionProcessor.IngestResult
import com.firesms.app.service.SmsTransactionProcessor.Outcome
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeFormattingTest {
    private val zone = ZoneOffset.UTC
    private val sameYear = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
    private val otherYear = DateTimeFormatter.ofPattern("EEEE, MMMM d, y", Locale.US)
    private val time24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val time12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun `date headers name today and yesterday and add the year only for other years`() {
        assertEquals("Today", dateHeaderLabel(today, today, sameYear, otherYear))
        assertEquals("Yesterday", dateHeaderLabel(today.minusDays(1), today, sameYear, otherYear))
        assertEquals("Monday, November 2", dateHeaderLabel(LocalDate.of(2026, 11, 2), today, sameYear, otherYear))
        assertEquals(
            "Wednesday, December 31, 2025",
            dateHeaderLabel(LocalDate.of(2025, 12, 31), today, sameYear, otherYear)
        )
    }

    @Test
    fun `amounts are signed by transaction type`() {
        assertEquals(AmountDirection.OUT, amountDirection("withdrawal"))
        assertEquals(AmountDirection.IN, amountDirection("Deposit"))
        assertEquals(AmountDirection.NEUTRAL, amountDirection("transfer"))
        assertEquals("−₹12.00", signedAmount("₹12.00", AmountDirection.OUT))
        assertEquals("+₹12.00", signedAmount("₹12.00", AmountDirection.IN))
        assertEquals("₹12.00", signedAmount("₹12.00", AmountDirection.NEUTRAL))
    }

    private fun summary(snapshot: StoredTransactionSnapshot?, status: String = "success") = ActivitySummary(
        id = 9,
        sender = "TEST-BANK",
        bodyPreview = "Paid Rs 12.5\nat Cafe",
        receivedAt = LocalDateTime.of(2026, 11, 10, 14, 5).toInstant(zone).toEpochMilli(),
        status = status,
        errorMessage = null,
        transactionSnapshotJson = snapshot?.encode(),
        locallyModifiedAt = null
    )

    @Test
    fun `rows are fully formatted for the card`() {
        val snapshot = StoredTransactionSnapshot(
            transactions = listOf(
                StoredTransactionSplit(
                    type = "withdrawal", amount = "12.5", currencySymbol = "₹", description = "Cafe",
                    sourceName = "Checking", destinationName = "Cafe", categoryName = "Food"
                )
            )
        )
        val row = buildActivityRow(summary(snapshot), time24, zone)
        assertEquals("Cafe", row.headline)
        assertEquals("−₹12.50", row.amount)
        assertEquals("Checking → Cafe", row.accountPath)
        assertEquals("Withdrawal · 14:05 · Food", row.metadata)
        assertNull(row.preview)

        assertEquals("Withdrawal · 2:05 PM · Food", buildActivityRow(summary(snapshot), time12, zone).metadata)
    }

    @Test
    fun `unparsed rows fall back to the sender and an SMS preview`() {
        val row = buildActivityRow(summary(null, status = "unparsed"), time24, zone)
        assertEquals("TEST-BANK", row.headline)
        assertNull(row.amount)
        assertEquals("Paid Rs 12.5 at Cafe", row.preview)
        assertEquals("14:05", row.metadata)
    }

    @Test
    fun `split dates are localized in either stored format`() {
        val formatter = DateTimeFormatter.ofPattern("MMM d, y HH:mm", Locale.US)
        assertEquals("Mar 20, 2026 10:30", formatSplitDate("2026-03-20 10:30:00", formatter, zone))
        assertEquals("Mar 20, 2026 09:30", formatSplitDate("2026-03-20T10:30:00+01:00", formatter, zone))
        assertEquals("sometime", formatSplitDate("sometime", formatter, zone))
        assertNull(formatSplitDate(" ", formatter, zone))
    }

    @Test
    fun `health panel lists unparsed failed and queued separately`() {
        assertEquals(listOf(HealthLine.AllGood), healthLines(true, ActivityCounts()))
        assertEquals(
            listOf(HealthLine.PermissionOff, HealthLine.Unparsed(2), HealthLine.Failed(1), HealthLine.Queued(3)),
            healthLines(false, ActivityCounts(unparsed = 2, failed = 1, queued = 3))
        )
        assertEquals(
            "3 waiting to sync — will retry automatically",
            healthLineTitle(HealthLine.Queued(3))
        )
        assertEquals("1 transaction couldn't sync", healthLineTitle(HealthLine.Failed(1)))
    }

    @Test
    fun `sample SMS outcomes are reported`() {
        assertEquals("No rule matches this sender", sampleSmsMessage(IngestResult.NoRuleForSender).text)
        assertTrue(sampleSmsMessage(IngestResult.Duplicate).text.startsWith("Duplicate"))
        assertEquals(
            "Synced “Cafe” to Firefly",
            sampleSmsMessage(IngestResult.Processed(1, Outcome.Created("42", "Cafe"))).text
        )
        assertTrue("Waiting to sync" in sampleSmsMessage(IngestResult.Processed(1, Outcome.Queued("503", "Cafe"))).text)
        assertEquals("Failed: HTTP 422", sampleSmsMessage(IngestResult.Processed(1, Outcome.Failed("HTTP 422"))).text)
        assertEquals(
            UiMessageAction.OPEN_SETTINGS,
            sampleSmsMessage(IngestResult.Processed(1, Outcome.NotConfigured)).action
        )
    }

    @Test
    fun `retry messages never show a bare null`() {
        assertEquals("Retry failed: Firefly rejected the transaction", retryMessage(Outcome.Failed(null)).text)
        assertTrue(retryMessage(null).text.isNotBlank())
    }
}
