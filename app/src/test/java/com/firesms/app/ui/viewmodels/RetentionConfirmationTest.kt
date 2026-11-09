package com.firesms.app.ui.viewmodels

import com.firesms.app.data.local.HistoryRetention
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionConfirmationTest {

    @Test
    fun `shortening retention asks first`() {
        assertTrue(retentionDeletesMoreHistory(HistoryRetention.NEVER, HistoryRetention.ONE_YEAR))
        assertTrue(retentionDeletesMoreHistory(HistoryRetention.ONE_YEAR, HistoryRetention.SEVEN_DAYS))
        assertTrue(retentionDeletesMoreHistory(HistoryRetention.THREE_MONTHS, HistoryRetention.ONE_MONTH))
    }

    @Test
    fun `keeping or lengthening retention saves straight away`() {
        assertFalse(retentionDeletesMoreHistory(HistoryRetention.SEVEN_DAYS, HistoryRetention.NEVER))
        assertFalse(retentionDeletesMoreHistory(HistoryRetention.ONE_MONTH, HistoryRetention.SIX_MONTHS))
        assertFalse(retentionDeletesMoreHistory(HistoryRetention.ONE_YEAR, HistoryRetention.ONE_YEAR))
        assertFalse(retentionDeletesMoreHistory(HistoryRetention.NEVER, HistoryRetention.NEVER))
    }
}
