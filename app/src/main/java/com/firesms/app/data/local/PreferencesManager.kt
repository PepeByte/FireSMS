package com.firesms.app.data.local

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.firesms.app.data.remote.hasUrlScheme
import com.firesms.app.data.remote.normalizeFireflyUrl
import kotlinx.serialization.Serializable

@Serializable
data class AppPreferencesSnapshot(
    val fireflyUrl: String,
    val fireflyToken: String,
    val onboardingCompleted: Boolean,
    val syncNetworkPolicy: String,
    val historyRetention: String,
    // Added after the first backup version; older backups decode with the default.
    val dynamicColor: Boolean = false
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "firesms_prefs",
    produceMigrations = { listOf(PreferencesManager.Companion.LegacyUrlSchemeMigration) }
)

/**
 * Older versions stored the server address as typed and assumed `http://` when it had no scheme.
 * Addresses now default to `https://`, so a stored scheme-less address is pinned to `http://`
 * once, keeping existing installs talking to their server exactly as before.
 */
internal fun migrateLegacyFireflyUrl(stored: String): String {
    val text = stored.trim()
    if (text.isEmpty() || hasUrlScheme(text)) return stored
    return "http://$text"
}

class PreferencesManager(private val context: Context) {

    companion object {
        private val FIREFLY_URL = stringPreferencesKey("firefly_url")
        private val FIREFLY_TOKEN = stringPreferencesKey("firefly_token")
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val SYNC_NETWORK_POLICY = stringPreferencesKey("sync_network_policy")
        private val HISTORY_RETENTION = stringPreferencesKey("history_retention")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val URL_SCHEME_MIGRATED = booleanPreferencesKey("url_scheme_migrated")

        /** Runs once before the first read; see [migrateLegacyFireflyUrl]. */
        internal object LegacyUrlSchemeMigration : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                currentData[URL_SCHEME_MIGRATED] != true

            override suspend fun migrate(currentData: Preferences): Preferences {
                val values = currentData.toMutablePreferences()
                values[FIREFLY_URL]?.let { values[FIREFLY_URL] = migrateLegacyFireflyUrl(it) }
                values[URL_SCHEME_MIGRATED] = true
                return values.toPreferences()
            }

            override suspend fun cleanUp() = Unit
        }
    }

    /** Use the wallpaper-based Material You palette on Android 12+. Off keeps the FireSMS blue. */
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[DYNAMIC_COLOR] ?: false }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    val fireflyUrl: Flow<String> = context.dataStore.data.map { it[FIREFLY_URL] ?: "" }
    val fireflyToken: Flow<String> = context.dataStore.data.map { it[FIREFLY_TOKEN] ?: "" }
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_COMPLETED] ?: false }
    val syncNetworkPolicy: Flow<String> = context.dataStore.data.map { it[SYNC_NETWORK_POLICY] ?: "any_network" }
    val historyRetention: Flow<String> = context.dataStore.data.map {
        it[HISTORY_RETENTION] ?: HistoryRetention.NEVER.value
    }

    /** Stores the address with an explicit scheme (`https://` when none was typed). */
    suspend fun setFireflyUrl(url: String) {
        context.dataStore.edit { it[FIREFLY_URL] = normalizeFireflyUrl(url) }
        // Cached Firefly lists belong to the old connection.
        com.firesms.app.data.remote.FireflyLookupCache.invalidate()
    }

    suspend fun setFireflyToken(token: String) {
        context.dataStore.edit { it[FIREFLY_TOKEN] = token }
        // Cached Firefly lists belong to the old connection.
        com.firesms.app.data.remote.FireflyLookupCache.invalidate()
    }

    /** Saves the address and token in one edit, so readers never see a mismatched pair. */
    suspend fun setFireflyConnection(url: String, token: String) {
        context.dataStore.edit {
            it[FIREFLY_URL] = normalizeFireflyUrl(url)
            it[FIREFLY_TOKEN] = token
        }
        // Cached Firefly lists belong to the old connection.
        com.firesms.app.data.remote.FireflyLookupCache.invalidate()
    }

    suspend fun setOnboardingCompleted() {
        context.dataStore.edit { it[ONBOARDING_COMPLETED] = true }
    }

    suspend fun isOnboardingCompleted(): Boolean {
        return context.dataStore.data.first()[ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setSyncNetworkPolicy(value: String) {
        context.dataStore.edit { it[SYNC_NETWORK_POLICY] = value }
    }

    suspend fun setHistoryRetention(value: String) {
        context.dataStore.edit { it[HISTORY_RETENTION] = HistoryRetention.fromValue(value).value }
    }

    suspend fun snapshot(): AppPreferencesSnapshot {
        val values = context.dataStore.data.first()
        return AppPreferencesSnapshot(
            fireflyUrl = values[FIREFLY_URL] ?: "",
            fireflyToken = values[FIREFLY_TOKEN] ?: "",
            onboardingCompleted = values[ONBOARDING_COMPLETED] ?: false,
            syncNetworkPolicy = values[SYNC_NETWORK_POLICY] ?: "any_network",
            historyRetention = values[HISTORY_RETENTION] ?: HistoryRetention.NEVER.value,
            dynamicColor = values[DYNAMIC_COLOR] ?: false
        )
    }

    /**
     * Writes a restored snapshot. The onboarding flag is device state, not a setting, so it is
     * left as it is: restoring an old backup must not send a set-up user back to onboarding.
     */
    suspend fun restore(snapshot: AppPreferencesSnapshot) {
        context.dataStore.edit { values ->
            values[FIREFLY_URL] = snapshot.fireflyUrl
            values[FIREFLY_TOKEN] = snapshot.fireflyToken
            values[SYNC_NETWORK_POLICY] = snapshot.syncNetworkPolicy
            values[HISTORY_RETENTION] = HistoryRetention.fromValue(snapshot.historyRetention).value
            values[DYNAMIC_COLOR] = snapshot.dynamicColor
        }
        // Cached Firefly lists belong to the old connection.
        com.firesms.app.data.remote.FireflyLookupCache.invalidate()
    }
}
