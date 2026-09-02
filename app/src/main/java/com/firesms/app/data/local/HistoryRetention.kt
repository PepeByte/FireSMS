package com.firesms.app.data.local

import java.time.Instant
import java.time.ZoneId

/** Stable preference values for automatic local history cleanup. */
enum class HistoryRetention(
    val value: String,
    val label: String,
    val description: String
) {
    NEVER("never", "Never", "Keep history until you delete it manually"),
    SEVEN_DAYS("7_days", "7 days", "Delete local history older than 7 days"),
    ONE_MONTH("1_month", "1 month", "Delete local history older than 1 month"),
    THREE_MONTHS("3_months", "3 months", "Delete local history older than 3 months"),
    SIX_MONTHS("6_months", "6 months", "Delete local history older than 6 months"),
    ONE_YEAR("1_year", "1 year", "Delete local history older than 1 year");

    fun cutoffMillis(nowMillis: Long = System.currentTimeMillis()): Long? {
        if (this == NEVER) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        val cutoff = when (this) {
            SEVEN_DAYS -> now.minusDays(7)
            ONE_MONTH -> now.minusMonths(1)
            THREE_MONTHS -> now.minusMonths(3)
            SIX_MONTHS -> now.minusMonths(6)
            ONE_YEAR -> now.minusYears(1)
            NEVER -> return null
        }
        return cutoff.toInstant().toEpochMilli()
    }

    companion object {
        fun fromValue(value: String?): HistoryRetention =
            entries.firstOrNull { it.value == value } ?: NEVER
    }
}
