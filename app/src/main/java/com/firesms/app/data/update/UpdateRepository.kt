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
    val lastNotifiedVersion: String? = null
)

internal interface UpdateStore {
    val state: Flow<UpdateSnapshot>
    suspend fun saveCheck(release: AppRelease?, checkedAt: Long)
    suspend fun markNotified(version: String)
}

class UpdateRepository internal constructor(
    private val store: UpdateStore,
    private val installedVersion: String,
    private val findUpdate: suspend (String) -> AppRelease? = GitHubReleaseClient()::findUpdate,
    private val now: () -> Long = System::currentTimeMillis,
    private val dismissNotification: () -> Unit = {}
) {
    private val checkMutex = Mutex()

    // Hide a cached offer immediately after installing that version (or a newer one).
    val state: Flow<UpdateSnapshot> = store.state.map { snapshot ->
        val installed = StableVersion.parse(installedVersion)
        val available = snapshot.availableRelease?.let { StableVersion.parse(it.version) }
        snapshot.copy(availableRelease = snapshot.availableRelease.takeIf {
            installed != null && available != null && available > installed
        })
    }

    /** A failed check preserves the last successful result and propagates the error to the caller. */
    suspend fun checkForUpdates(
        notify: (AppRelease) -> Boolean = { false }
    ): AppRelease? = checkMutex.withLock {
        val release = findUpdate(installedVersion)
        store.saveCheck(release, now())
        if (release == null) dismissNotification()
        if (release != null && store.state.first().lastNotifiedVersion != release.version) {
            // Do not mark denied/disabled notifications as delivered: allow a later daily attempt.
            if (notify(release)) store.markNotified(release.version)
        }
        release
    }

    /** Shares the check lock so startup cannot cancel a newly posted update notification. */
    suspend fun dismissObsoleteNotification() = checkMutex.withLock {
        if (state.first().availableRelease == null) dismissNotification()
    }
}
