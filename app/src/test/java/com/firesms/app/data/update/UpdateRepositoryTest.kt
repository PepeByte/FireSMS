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
        override suspend fun setIgnoredVersion(version: String?) {
            state.value = state.value.copy(ignoredVersion = version, ignoreRevision = state.value.ignoreRevision + 1)
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
    fun `ignoring a release hides the offer and dismisses its notification`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release, 123L))
        var dismissed = 0
        val repository = UpdateRepository(store, "1.0.2", dismissNotification = { dismissed++ })
        repository.ignoreUpdate(release.version)
        assertNull(repository.state.first().availableRelease)
        assertEquals(release.version, store.state.value.ignoredVersion)
        assertEquals(release, store.state.value.availableRelease)
        assertEquals(123L, store.state.value.lastCheckedAt)
        assertEquals(1, dismissed)
    }

    @Test
    fun `ignored release stays hidden across checks and repository instances without notifying`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release))
        UpdateRepository(store, "1.0.2").ignoreUpdate(release.version)
        var notifications = 0
        repeat(2) {
            val repository = UpdateRepository(store, "1.0.2", { release }, { 456L })
            assertNull(repository.state.first().availableRelease)
            assertNull(repository.checkForUpdates { notifications++; true })
            assertNull(repository.state.first().availableRelease)
        }
        assertEquals(0, notifications)
        assertEquals(456L, store.state.value.lastCheckedAt)
        assertNull(store.state.value.lastNotifiedVersion)
    }

    @Test
    fun `ignoring a release does not hide or silence a newer release`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release))
        val newer = AppRelease("1.2.0", "https://github.com/PepeByte/FireSMS/releases/tag/v1.2.0")
        val repository = UpdateRepository(store, "1.0.2", { newer })
        repository.ignoreUpdate(release.version)
        var notifications = 0
        assertEquals(newer, repository.checkForUpdates { notifications++; true })
        assertEquals(newer, repository.state.first().availableRelease)
        assertEquals(1, notifications)
        assertEquals(newer.version, store.state.value.lastNotifiedVersion)
    }

    @Test
    fun `undo restores cached offer without network access or resetting notification history`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release, 123L, release.version, release.version))
        val repository = UpdateRepository(store, "1.0.2", { error("Should not fetch") })
        repository.clearIgnoredUpdate()
        assertEquals(release, repository.state.first().availableRelease)
        assertNull(store.state.value.ignoredVersion)
        assertEquals(release.version, store.state.value.lastNotifiedVersion)
    }

    @Test
    fun `failed check preserves ignored preference and hidden cached offer`() = runBlocking {
        val initial = UpdateSnapshot(release, 123L, ignoredVersion = release.version)
        val store = MemoryStore(initial)
        val repository = UpdateRepository(store, "1.0.2", { throw UpdateCheckException("Offline") })
        assertFailsWith<UpdateCheckException> { repository.checkForUpdates() }
        assertEquals(initial, store.state.value)
        assertNull(repository.state.first().availableRelease)
    }

    @Test
    fun `stale ignore waits for check and cannot cancel a newer release notification`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release))
        val newer = AppRelease("1.2.0", "https://github.com/PepeByte/FireSMS/releases/tag/v1.2.0")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var dismissed = 0
        val repository = UpdateRepository(store, "1.0.2", {
            started.complete(Unit)
            finish.await()
            newer
        }, dismissNotification = { dismissed++ })
        val checking = async { repository.checkForUpdates { true } }
        started.await()
        val ignoring = async { repository.ignoreUpdate(release.version) }
        finish.complete(Unit)
        checking.await()
        ignoring.await()
        assertEquals(newer, repository.state.first().availableRelease)
        assertNull(store.state.value.ignoredVersion)
        assertEquals(0, dismissed)
        assertEquals(newer.version, store.state.value.lastNotifiedVersion)
    }

    @Test
    fun `notification action skips an already announced release and repeated taps are harmless`() = runBlocking {
        val store = MemoryStore()
        var notifications = 0
        var dismissed = 0
        val repository = UpdateRepository(store, "1.0.2", { release },
            dismissNotification = { dismissed++ })
        repository.checkForUpdates { notifications++; true }
        assertEquals(release.version, store.state.value.lastNotifiedVersion)

        // The durable notification action can execute more than once, including after restart.
        repeat(2) { repository.ignoreUpdate(release.version, expectedRevision = 0) }
        assertNull(repository.state.first().availableRelease)
        assertEquals(1, dismissed)
        assertEquals(release.version, store.state.value.ignoredVersion)
        val restarted = UpdateRepository(store, "1.0.2", { release })
        assertNull(restarted.checkForUpdates { notifications++; true })
        assertEquals(1, notifications)

        restarted.clearIgnoredUpdate()
        assertEquals(release, restarted.state.first().availableRelease)
    }

    @Test
    fun `deferred notification action cannot override a later settings undo`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release))
        var dismissed = 0
        val repository = UpdateRepository(store, "1.0.2", dismissNotification = { dismissed++ })
        val notificationRevision = repository.state.first().ignoreRevision
        repository.ignoreUpdate(release.version)
        repository.clearIgnoredUpdate()
        assertEquals(2L, store.state.value.ignoreRevision)

        // A queued action from before the Settings changes executes after an app restart.
        UpdateRepository(store, "1.0.2", dismissNotification = { dismissed++ })
            .ignoreUpdate(release.version, expectedRevision = notificationRevision)
        assertEquals(release, repository.state.first().availableRelease)
        assertNull(store.state.value.ignoredVersion)
        assertEquals(1, dismissed)
    }

    @Test
    fun `notification action with current preference revision can skip a release`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release, ignoreRevision = 7))
        val repository = UpdateRepository(store, "1.0.2")
        repository.ignoreUpdate(release.version, expectedRevision = 7)
        assertNull(repository.state.first().availableRelease)
        assertEquals(release.version, store.state.value.ignoredVersion)
        assertEquals(8L, store.state.value.ignoreRevision)
    }

    @Test
    fun `startup dismisses notification for ignored release`() = runBlocking {
        val store = MemoryStore(UpdateSnapshot(release, ignoredVersion = release.version))
        var dismissed = 0
        UpdateRepository(store, "1.0.2", dismissNotification = { dismissed++ })
            .dismissObsoleteNotification()
        assertEquals(1, dismissed)
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
