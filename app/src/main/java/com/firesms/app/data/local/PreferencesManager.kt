package com.firesms.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class AppPreferencesSnapshot(
    val fireflyUrl: String,
    val fireflyToken: String,
    val onboardingCompleted: Boolean,
    val syncNetworkPolicy: String,
    val historyRetention: String
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "firesms_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        private val FIREFLY_URL = stringPreferencesKey("firefly_url")
        private val FIREFLY_TOKEN = stringPreferencesKey("firefly_token")
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val SYNC_NETWORK_POLICY = stringPreferencesKey("sync_network_policy")
        private val HISTORY_RETENTION = stringPreferencesKey("history_retention")
    }

    val fireflyUrl: Flow<String> = context.dataStore.data.map { it[FIREFLY_URL] ?: "" }
    val fireflyToken: Flow<String> = context.dataStore.data.map { it[FIREFLY_TOKEN] ?: "" }
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_COMPLETED] ?: false }
    val syncNetworkPolicy: Flow<String> = context.dataStore.data.map { it[SYNC_NETWORK_POLICY] ?: "any_network" }
    val historyRetention: Flow<String> = context.dataStore.data.map {
        it[HISTORY_RETENTION] ?: HistoryRetention.NEVER.value
    }

    suspend fun setFireflyUrl(url: String) {
        context.dataStore.edit { it[FIREFLY_URL] = url }
    }

    suspend fun setFireflyToken(token: String) {
        context.dataStore.edit { it[FIREFLY_TOKEN] = token }
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
            historyRetention = values[HISTORY_RETENTION] ?: HistoryRetention.NEVER.value
        )
    }

    suspend fun restore(snapshot: AppPreferencesSnapshot) {
        context.dataStore.edit { values ->
            values[FIREFLY_URL] = snapshot.fireflyUrl
            values[FIREFLY_TOKEN] = snapshot.fireflyToken
            values[ONBOARDING_COMPLETED] = snapshot.onboardingCompleted
            values[SYNC_NETWORK_POLICY] = snapshot.syncNetworkPolicy
            values[HISTORY_RETENTION] = HistoryRetention.fromValue(snapshot.historyRetention).value
        }
    }
}
