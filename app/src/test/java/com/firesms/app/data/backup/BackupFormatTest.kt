package com.firesms.app.data.backup

import com.firesms.app.data.local.AppPreferencesSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupFormatTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun emptyObjectIsNotAcceptedAsBackup() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<FireSmsBackup>("{}")
        }
    }

    @Test
    fun completeBackupRoundTrips() {
        val backup = FireSmsBackup(
            format = "firesms-backup",
            version = 1,
            exportedAt = 123L,
            preferences = AppPreferencesSnapshot(
                fireflyUrl = "https://example.test",
                fireflyToken = "token",
                onboardingCompleted = true,
                syncNetworkPolicy = "wifi_only",
                historyRetention = "3_months"
            ),
            smsLogs = emptyList(),
            pendingTransactions = emptyList(),
            parserRules = emptyList(),
            titleMappingRules = emptyList()
        )

        assertEquals(backup, json.decodeFromString<FireSmsBackup>(json.encodeToString(backup)))
    }
}
