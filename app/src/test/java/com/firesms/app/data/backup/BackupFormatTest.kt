package com.firesms.app.data.backup

import com.firesms.app.data.local.AppPreferencesSnapshot
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.data.local.entity.PendingTransaction
import com.firesms.app.data.local.entity.SmsLog
import com.firesms.app.data.local.entity.TitleMappingRule
import com.firesms.app.data.remote.TransactionBody
import com.firesms.app.data.remote.TransactionRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupFormatTest {
    private fun populatedBackup(): FireSmsBackup {
        val payload = Json.encodeToString(
            TransactionRequest(
                listOf(
                    TransactionBody(
                        type = "withdrawal",
                        date = "2025-01-02T03:04:05+05:30",
                        amount = "1234.50",
                        description = "Café 日本",
                        sourceId = "12",
                        sourceName = "My account",
                        destinationId = "34",
                        destinationName = "Merchant",
                        externalId = "test-hash",
                        categoryName = "Food",
                        budgetName = "Monthly",
                        notes = "Line one\nLine two: ₹"
                    )
                )
            )
        )
        return FireSmsBackup(
            format = BACKUP_FORMAT,
            version = BACKUP_VERSION,
            exportedAt = 123L,
            preferences = AppPreferencesSnapshot(
                fireflyUrl = "https://example.test",
                fireflyToken = "test-token",
                onboardingCompleted = true,
                syncNetworkPolicy = "wifi_only",
                historyRetention = "3_months"
            ),
            smsLogs = listOf(
                SmsLog(
                    id = 1,
                    smsHash = "test-hash",
                    sender = "TEST-BANK",
                    body = "Paid ₹1,234.50 at Café 日本.\nReference: \\\"ABC\\\"",
                    receivedAt = 100L,
                    processedAt = 110L,
                    status = "failed",
                    fireflyId = null,
                    errorMessage = "Network unavailable",
                    originalTransactionSnapshotJson = "{\"description\":\"Café\"}",
                    transactionSnapshotJson = "{\"description\":\"Café 日本\"}",
                    locallyModifiedAt = 115L
                )
            ),
            pendingTransactions = listOf(
                PendingTransaction(
                    id = 2,
                    smsLogId = 1,
                    fireflyPayload = payload,
                    retries = 1,
                    maxRetries = 5,
                    nextRetryAt = 200L,
                    lastError = "Network unavailable",
                    createdAt = 120L
                )
            ),
            parserRules = listOf(
                ParserRule(
                    id = "test-rule",
                    name = "Café 日本",
                    enabled = false,
                    senderPattern = "^TEST-\\w+$",
                    bodyPattern = "Paid ₹([\\d,.]+) at (.+)\\.",
                    amountTemplate = "{g1}",
                    descriptionSource = "template",
                    descriptionValue = "Purchase: {g2}",
                    sourceAccountKeyword = "1234",
                    sourceAccountName = "My account",
                    sourceAccountId = "12",
                    destinationAccountName = "Merchant",
                    destinationAccountId = "34",
                    type = "withdrawal",
                    priority = 3,
                    datePattern = "on (\\d{2}/\\d{2}/\\d{4})",
                    dateFormat = "dd/MM/yyyy",
                    remarksPattern = "Ref: (\\w+)",
                    remarksTemplate = "Reference {g1}\n₹"
                )
            ),
            titleMappingRules = listOf(
                TitleMappingRule(
                    id = 3,
                    titlePattern = "(?i)Café\\s+日本",
                    categoryName = "Food",
                    budgetName = "Monthly",
                    enabled = false,
                    priority = 7,
                    createdAt = 90L
                )
            )
        )
    }

    private fun assertInvalid(backup: FireSmsBackup, expectedMessage: String) {
        val error = assertFailsWith<IllegalArgumentException> {
            BackupFormat.decode(BackupFormat.encode(backup))
        }
        assertEquals(expectedMessage, error.message)
    }

    @Test
    fun emptyObjectIsNotAcceptedAsBackup() {
        val error = assertFailsWith<IllegalArgumentException> { BackupFormat.decode("{}") }
        assertEquals("This is not a valid FireSMS backup", error.message)
    }

    @Test
    fun malformedJsonIsNotAcceptedAsBackup() {
        assertFailsWith<IllegalArgumentException> { BackupFormat.decode("{broken") }
    }

    @Test
    fun emptyBackupRoundTrips() {
        val backup = populatedBackup().copy(
            smsLogs = emptyList(),
            pendingTransactions = emptyList(),
            parserRules = emptyList(),
            titleMappingRules = emptyList()
        )
        assertEquals(backup, BackupFormat.decode(BackupFormat.encode(backup)))
        assertEquals(0, backup.summary().totalRecords)
    }

    @Test
    fun completeBackupRoundTripsWithUnicodeRegexAndAllEntityFields() {
        val backup = populatedBackup()
        assertEquals(backup, BackupFormat.decode(BackupFormat.encode(backup)))
        assertEquals(BackupSummary(1, 1, 1, 1), backup.summary())
        assertEquals(4, backup.summary().totalRecords)
    }

    @Test
    fun exportIncludesDefaultValuesAndUsesOriginalPropertyNames() {
        val backup = populatedBackup()
        val encoded = BackupFormat.encode(backup)
        assertTrue(encoded.contains("\"fireflyId\": null"))
        assertTrue(encoded.contains("\"smsHash\""))
        assertTrue(encoded.contains("\"sourceAccountId\""))
        assertTrue(encoded.contains("\n"), "Backups remain pretty-printed")
    }

    @Test
    fun unknownFieldsRemainForwardCompatible() {
        val backup = populatedBackup()
        val fields = Json.parseToJsonElement(BackupFormat.encode(backup)).jsonObject.toMutableMap()
        fields["futureField"] = JsonPrimitive("ignored")
        assertEquals(backup, BackupFormat.decode(JsonObject(fields).toString()))
    }

    @Test
    fun unreadableDeadLetterPayloadRoundTrips() {
        val backup = populatedBackup()
        val deadLetter = backup.pendingTransactions.single().copy(
            retries = 5,
            maxRetries = 5,
            fireflyPayload = "unreadable historical payload",
            lastError = "Cannot decode transaction"
        )
        val withDeadLetter = backup.copy(pendingTransactions = listOf(deadLetter))
        assertEquals(withDeadLetter, BackupFormat.decode(BackupFormat.encode(withDeadLetter)))
    }

    @Test
    fun unreadableActivePayloadIsRejected() {
        val backup = populatedBackup()
        assertInvalid(
            backup.copy(pendingTransactions = listOf(
                backup.pendingTransactions.single().copy(retries = 4, fireflyPayload = "not JSON")
            )),
            "Backup contains an invalid queued transaction"
        )
    }

    @Test
    fun invalidCountersAreRejectedEvenForDeadLetters() {
        val backup = populatedBackup()
        listOf(-1 to 5, 0 to 0, 0 to -1).forEach { (retries, maxRetries) ->
            assertInvalid(
                backup.copy(pendingTransactions = listOf(
                    backup.pendingTransactions.single().copy(
                        retries = retries,
                        maxRetries = maxRetries,
                        fireflyPayload = "not JSON"
                    )
                )),
                "Backup contains an invalid queued transaction"
            )
        }
    }

    @Test
    fun duplicateSmsHashesWithDifferentIdsPreserveExistingHistory() {
        val backup = populatedBackup().let {
            it.copy(smsLogs = it.smsLogs + it.smsLogs.single().copy(id = 4))
        }
        assertEquals(backup, BackupFormat.decode(BackupFormat.encode(backup)))
    }

    @Test
    fun duplicateIdsAreRejectedForEveryEntity() {
        val backup = populatedBackup()
        assertInvalid(backup.copy(smsLogs = backup.smsLogs + backup.smsLogs),
            "Backup contains duplicate SMS log IDs")
        assertInvalid(backup.copy(pendingTransactions = backup.pendingTransactions + backup.pendingTransactions),
            "Backup contains duplicate pending transaction IDs")
        assertInvalid(backup.copy(parserRules = backup.parserRules + backup.parserRules),
            "Backup contains duplicate parser rule IDs")
        assertInvalid(backup.copy(titleMappingRules = backup.titleMappingRules + backup.titleMappingRules),
            "Backup contains duplicate title mapping IDs")
    }

    @Test
    fun nonPositiveGeneratedIdsAreRejected() {
        val backup = populatedBackup()
        listOf(0L, -1L).forEach { id ->
            assertInvalid(backup.copy(smsLogs = listOf(backup.smsLogs.single().copy(id = id))),
                "Backup contains an invalid SMS log ID")
            assertInvalid(backup.copy(pendingTransactions = listOf(backup.pendingTransactions.single().copy(id = id))),
                "Backup contains an invalid pending transaction ID")
            assertInvalid(backup.copy(titleMappingRules = listOf(backup.titleMappingRules.single().copy(id = id))),
                "Backup contains an invalid title mapping ID")
        }
    }

    @Test
    fun pendingTransactionWithoutHistoryIsRejected() {
        val backup = populatedBackup()
        assertInvalid(backup.copy(smsLogs = emptyList()),
            "Backup contains a pending transaction without its SMS history record")
    }

    @Test
    fun unsupportedFormatAndVersionAreRejected() {
        val backup = populatedBackup()
        assertInvalid(backup.copy(format = "other-backup"), "Unsupported backup format")
        assertInvalid(backup.copy(version = 2), "Backup version 2 is not supported by this app version")
    }

    @Test
    fun invalidStatusAndPreferencesAreRejected() {
        val backup = populatedBackup()
        assertInvalid(backup.copy(smsLogs = listOf(backup.smsLogs.single().copy(status = "unknown"))),
            "Backup contains an invalid SMS status")
        assertInvalid(backup.copy(preferences = backup.preferences.copy(syncNetworkPolicy = "unknown")),
            "Backup contains an invalid sync preference")
        assertInvalid(backup.copy(preferences = backup.preferences.copy(historyRetention = "unknown")),
            "Backup contains an invalid history-retention preference")
    }
}
