package com.firesms.app.ui.screens

import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class PickedDateTimeTest {

    private val picked = LocalDate.of(2026, 3, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val original = OffsetDateTime.parse("2026-03-01T21:45:00+05:30")

    @Test
    fun `picked time replaces the original time and keeps its offset`() {
        assertEquals("2026-03-14T09:05:00+05:30", pickedDateTime(picked, LocalTime.of(9, 5), original))
    }

    @Test
    fun `skipping the time step keeps the original time`() {
        assertEquals("2026-03-14T21:45:00+05:30", pickedDateTime(picked, null, original))
    }

    @Test
    fun `without an original date the fallback offset and noon are used`() {
        assertEquals(
            "2026-03-14T12:00:00Z",
            pickedDateTime(picked, null, original = null, fallbackOffset = ZoneOffset.UTC)
        )
    }
}
