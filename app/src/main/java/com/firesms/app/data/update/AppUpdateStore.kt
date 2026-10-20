package com.firesms.app.data.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.updateDataStore by preferencesDataStore(name = "app_updates")

internal class AppUpdateStore(context: Context) : UpdateStore {
    private val dataStore = context.applicationContext.updateDataStore

    override val state = dataStore.data.map { values ->
        val version = values[RELEASE_VERSION]
        val url = values[RELEASE_URL]
        UpdateSnapshot(
            availableRelease = if (version != null && url != null) AppRelease(version, url) else null,
            lastCheckedAt = values[LAST_CHECKED_AT] ?: 0,
            lastNotifiedVersion = values[LAST_NOTIFIED_VERSION],
            ignoredVersion = values[IGNORED_VERSION],
            ignoreRevision = values[IGNORE_REVISION] ?: 0
        )
    }

    override suspend fun saveCheck(release: AppRelease?, checkedAt: Long) {
        dataStore.edit { values ->
            if (release == null) {
                values.remove(RELEASE_VERSION)
                values.remove(RELEASE_URL)
            } else {
                values[RELEASE_VERSION] = release.version
                values[RELEASE_URL] = release.pageUrl
            }
            values[LAST_CHECKED_AT] = checkedAt
        }
    }

    override suspend fun markNotified(version: String) {
        dataStore.edit { it[LAST_NOTIFIED_VERSION] = version }
    }

    override suspend fun setIgnoredVersion(version: String?) {
        dataStore.edit { values ->
            if (version == null) values.remove(IGNORED_VERSION) else values[IGNORED_VERSION] = version
            values[IGNORE_REVISION] = (values[IGNORE_REVISION] ?: 0) + 1
        }
    }

    private companion object {
        val RELEASE_VERSION = stringPreferencesKey("release_version")
        val RELEASE_URL = stringPreferencesKey("release_url")
        val LAST_CHECKED_AT = longPreferencesKey("last_checked_at")
        val LAST_NOTIFIED_VERSION = stringPreferencesKey("last_notified_version")
        val IGNORED_VERSION = stringPreferencesKey("ignored_version")
        val IGNORE_REVISION = longPreferencesKey("ignore_revision")
    }
}
