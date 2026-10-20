package com.firesms.app.worker

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class AppUpdateWorkerTest {
    @Test
    fun `daily checks require connectivity and use hourly exponential backoff`() {
        val spec = appUpdateWorkRequest().workSpec
        assertEquals(TimeUnit.DAYS.toMillis(1), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.HOURS.toMillis(1), spec.backoffDelayDuration)
        assertEquals(0L, spec.initialDelay)
    }
}
