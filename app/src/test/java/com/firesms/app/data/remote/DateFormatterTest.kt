package com.firesms.app.data.remote

import com.firesms.app.domain.model.ParsedTransaction
import kotlin.test.Test
import kotlin.test.assertTrue
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

class DateFormatterTest {

    @Test
    fun `buildCreateRequest formats date with device local offset`() {
        val originalTz = TimeZone.getDefault()
        val testZone = ZoneOffset.ofHours(5) // UTC+5
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(testZone))

            // Fixed epoch millis: 2024-01-15T10:30:00 UTC = 1705319400000L
            val epochMillis = 1705319400000L

            val transaction = ParsedTransaction(
                date = epochMillis,
                amount = "100.00",
                description = "Test transaction",
                transactionType = "withdrawal",
                notes = null,
                sourceAccountId = null,
                sourceAccountName = null,
                destinationAccountName = null
            )

            val request = buildCreateRequest(transaction, "ext-123")

            val dateStr = request.transactions.first().date
            assertTrue(
                dateStr == "2024-01-15 16:50:00",
                "Expected date 2024-01-15 16:50:00 (UTC+5), but got: $dateStr"
            )
            assertTrue(
                !dateStr.endsWith("Z"),
                "Date should not end with Z (UTC), but got: $dateStr"
            )
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `buildCreateRequest formats date with negative offset`() {
        val originalTz = TimeZone.getDefault()
        val testZone = ZoneOffset.ofHours(-3) // UTC-3
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(testZone))

            val epochMillis = 1705319400000L

            val transaction = ParsedTransaction(
                date = epochMillis,
                amount = "50.00",
                description = "Test negative offset",
                transactionType = "deposit",
                notes = null,
                sourceAccountId = null,
                sourceAccountName = null,
                destinationAccountName = null
            )
            val request = buildCreateRequest(transaction, "ext-456")
            val dateStr = request.transactions.first().date

            assertTrue(
                dateStr == "2024-01-15 08:50:00",
                "Expected date 2024-01-15 08:50:00 (UTC-3), but got: $dateStr"
            )
            assertTrue(
                !dateStr.endsWith("Z"),
                "Date should not end with Z (UTC), but got: $dateStr"
            )
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }
}
