package com.firesms.app.worker

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class IgnoreAppUpdateWorkerTest {
    @Test
    fun `notification action persists the exact release version for deferred execution`() {
        val version = "1.1.0+build.42"
        val request = IgnoreAppUpdateWorker.request(version, 7)
        assertEquals(version, request.workSpec.input.getString(IgnoreAppUpdateWorker.VERSION))
        assertEquals(7L, request.workSpec.input.getLong(IgnoreAppUpdateWorker.REVISION, -1))
        assertEquals(IgnoreAppUpdateWorker::class.java.name, request.workSpec.workerClassName)
    }

    @Test
    fun `ignore runs without network or charging and has no initial delay`() {
        val spec = IgnoreAppUpdateWorker.request("1.1.0", 0).workSpec
        assertEquals(NetworkType.NOT_REQUIRED, spec.constraints.requiredNetworkType)
        assertFalse(spec.constraints.requiresCharging())
        assertFalse(spec.constraints.requiresDeviceIdle())
        assertEquals(0L, spec.initialDelay)
        assertEquals(0L, spec.intervalDuration)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(10), spec.backoffDelayDuration)
    }

    @Test
    fun `different notification versions retain their own target`() {
        val first = IgnoreAppUpdateWorker.request("1.1.0", 0)
        val second = IgnoreAppUpdateWorker.request("1.2.0", 0)
        assertNotEquals(first.id, second.id)
        assertEquals("1.1.0", first.workSpec.input.getString(IgnoreAppUpdateWorker.VERSION))
        assertEquals("1.2.0", second.workSpec.input.getString(IgnoreAppUpdateWorker.VERSION))
    }
}
