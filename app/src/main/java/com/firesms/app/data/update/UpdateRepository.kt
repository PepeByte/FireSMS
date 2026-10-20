package com.firesms.app.data.update

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device-local update metadata; deliberately excluded from financial backups. */
data class UpdateSnapshot(
    val availableRelease: AppRelease? = null,
    val lastCheckedAt: Long = 0,
    val lastNotifiedVersion: String? = null,
    val ignoredVersion: String? = null,
    val ignoreRevision: Long = 0
)

internal interface UpdateStore {
    val state: Flow<UpdateSnapshot>
    suspend fun saveCheck(release: AppRelease?, checkedAt: Long)
    suspend fun markNotified(version: String)
    /** Atomically changes the preference and advances ignoreRevision, including on undo. */
    suspend fun setIgnoredVersion(version: String?)
}

class UpdateRepository internal constructor(
    private val store: UpdateStore,
    private val installedVersion: String,
    private val findUpdate: suspend (String) -> AppRelease? = GitHubReleaseClient()::findUpdate,
    private val now: () -> Long = System::currentTimeMillis,
    private val dismissNotification: () -> Unit = {}
) {
    private val checkMutex = Mutex()

    // Hide ignored offers and cached offers for an already installed version (or an older one).
    val state: Flow<UpdateSnapshot> = store.state.map { snapshot ->
        val installed = StableVersion.parse(installedVersion)
        val available = snapshot.availableRelease?.let { StableVersion.parse(it.version) }
        snapshot.copy(availableRelease = snapshot.availableRelease.takeIf {
            installed != null && available != null && available > installed &&
                it?.version != snapshot.ignoredVersion
        })
    }

    /** A failed check preserves the last successful result and propagates the error to the caller. */
    suspend fun checkForUpdates(
        notify: suspend (AppRelease) -> Boolean = { false }
    ): AppRelease? = checkMutex.withLock {
        val release = findUpdate(installedVersion)
        store.saveCheck(release, now())
        val snapshot = state.first()
        val offeredRelease = snapshot.availableRelease
        if (offeredRelease == null) dismissNotification()
        if (offeredRelease != null && snapshot.lastNotifiedVersion != offeredRelease.version) {
            // Do not mark denied/disabled notifications as delivered: allow a later daily attempt.
            if (notify(offeredRelease)) store.markNotified(offeredRelease.version)
        }
        offeredRelease
    }

    /** Skip only this release, retaining the cached result so the user can undo without a check. */
    suspend fun ignoreUpdate(version: String, expectedRevision: Long? = null) = checkMutex.withLock {
        val snapshot = state.first()
        // Deferred notification actions must not override a later Settings ignore/undo.
        if (expectedRevision != null && snapshot.ignoreRevision != expectedRevision) return@withLock
        // A newer check may have completed since the user saw the offer.
        if (snapshot.availableRelease?.version == version) {
            store.setIgnoredVersion(version)
            dismissNotification()
        }
    }

    suspend fun clearIgnoredUpdate() = checkMutex.withLock {
        store.setIgnoredVersion(null)
    }

    /** Shares the check lock so startup cannot cancel a newly posted update notification. */
    suspend fun dismissObsoleteNotification() = checkMutex.withLock {
        if (state.first().availableRelease == null) dismissNotification()
    }
}
