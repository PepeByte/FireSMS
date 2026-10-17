package com.firesms.app.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UpdateRepositoryTest {
    private val release = AppRelease("1.1.0", "https://github.com/PepeByte/FireSMS/releases/tag/v1.1.0")

    private class MemoryStore(initial: UpdateSnapshot = UpdateSnapshot()) : UpdateStore {
        override val state = MutableStateFlow(initial)
        override suspend fun saveCheck(release: AppRelease?, checkedAt: Long) {
            state.value = state.value.copy(availableRelease = release, lastCheckedAt = checkedAt)
        }
        override suspend fun markNotified(version: String) {
            state.value = state.value.copy(lastNotifiedVersion = version)
        }
    }

    @Test
    fun `successful check persists release and check time`() = runBlocking {
        val store = MemoryStore()
        val repository = UpdateRepository(store, "1.0.2", { release }, { 123L })
        assertEquals(release, repository.checkForUpdates())
        assertEquals(release, repository.state.first().availableRelease)
        assertEquals(123L, store.state.value.lastCheckedAt)
        assertNull(store.state.value.lastNotifiedVersion)
    }

    @Test
    fun `same release only notifies once across repository instances`() = runBlocking {
        val store = MemoryStore()
        var notifications = 0
        repeat(2) {
            UpdateRepository(store, "1.0.2", { release }).checkForUpdates {
                notifications++
                true
            }
        }
        assertEquals(1, notifications)
        assertEquals(release.version, store.state.value.lastNotifiedVersion)
    }

    @Test
    fun `blocked notification can be retried and a newer release alerts again`() = runBlocking {
        val store = MemoryStore()
        var latest = release
        var notifications = 0
        val repository = UpdateRepository(store, "1.0.2", { latest })
        repository.checkForUpdates { false }
        assertNull(store.state.value.lastNotifiedVersion)
        repository.checkForUpdates { notifications++; true }
        latest = AppRelease("1.2.0", "https://github.com/PepeByte/FireSMS/releases/tag/v1.2.0")
        repository.checkForUpdates { notifications++; true }
        assertEquals(2, notifications)
        assertEquals("1.2.0", store.state.value.lastNotifiedVersion)
    }

    @Test
    fun `failed check preserves last successful result`() = runBlocking {
        val initial = UpdateSnapshot(release, 123L, release.version)
        val store = MemoryStore(initial)
        val repository = UpdateRepository(store, "1.0.2", { throw UpdateCheckException("Offline") })
        assertFailsWith<UpdateCheckException> { repository.checkForUpdates() }
        assertEquals(initial, store.state.value)
    }

    @Test
    fun `cancellation is propagated without changing cached state`() = runBlocking {
        val store = MemoryStore()
        val repository = UpdateRepository(store, "1.0.2", { throw CancellationException() })
        assertFailsWith<CancellationException> { repository.checkForUpdates() }
        assertEquals(UpdateSnapshot(), store.state.value)
    }

    @Test
    fun `cached offers are hidden after installing same or newer version`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release, 123L))
        for (installed in listOf("1.1.0", "1.2.0")) {
            assertNull(UpdateRepository(store, installed).state.first().availableRelease)
        }
        assertEquals(release, UpdateRepository(store, "1.0.2").state.first().availableRelease)
    }

    @Test
    fun `successful check without update clears stale offer and dismisses notification`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release))
        var dismissed = 0
        val repository = UpdateRepository(store, "1.0.2", { null }, { 456L }, { dismissed++ })
        assertNull(repository.checkForUpdates())
        assertNull(repository.state.first().availableRelease)
        assertEquals(456L, store.state.value.lastCheckedAt)
        assertEquals(1, dismissed)
    }

    @Test
    fun `startup cleanup waits for check and cannot dismiss newly posted update`() = runBlocking {
        val store = MemoryStore()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var dismissed = 0
        val repository = UpdateRepository(store, "1.0.2", {
            started.complete(Unit)
            finish.await()
            release
        }, dismissNotification = { dismissed++ })
        val checking = async { repository.checkForUpdates { true } }
        started.await()
        val cleanup = async { repository.dismissObsoleteNotification() }
        finish.complete(Unit)
        checking.await()
        cleanup.await()
        assertEquals(0, dismissed)
        assertEquals(release.version, store.state.value.lastNotifiedVersion)
    }

    @Test
    fun `startup dismisses notification for already installed release`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release))
        var dismissed = 0
        UpdateRepository(store, "1.1.0", dismissNotification = { dismissed++ })
            .dismissObsoleteNotification()
        assertEquals(1, dismissed)
    }
}
