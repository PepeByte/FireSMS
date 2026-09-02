package com.firesms.app.data.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.AppPreferencesSnapshot
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.data.local.PreferencesManager
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.data.local.entity.PendingTransaction
import com.firesms.app.data.local.entity.SmsLog
import com.firesms.app.data.local.entity.TitleMappingRule
import com.firesms.app.data.remote.TransactionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

private const val BACKUP_FORMAT = "firesms-backup"
private const val BACKUP_VERSION = 1
private const val MAX_IMPORT_BYTES = 25 * 1024 * 1024

@Serializable
data class FireSmsBackup(
    val format: String,
    val version: Int,
    val exportedAt: Long,
    val preferences: AppPreferencesSnapshot,
    val smsLogs: List<SmsLog>,
    val pendingTransactions: List<PendingTransaction>,
    val parserRules: List<ParserRule>,
    val titleMappingRules: List<TitleMappingRule>
)

data class BackupSummary(
    val smsLogs: Int,
    val pendingTransactions: Int,
    val parserRules: Int,
    val titleMappingRules: Int
) {
    val totalRecords: Int
        get() = smsLogs + pendingTransactions + parserRules + titleMappingRules
}

class BackupRepository(
    private val database: AppDatabase,
    private val preferencesManager: PreferencesManager,
    private val contentResolver: ContentResolver
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun exportTo(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val preferences = preferencesManager.snapshot()
        val backup = database.withTransaction {
            FireSmsBackup(
                format = BACKUP_FORMAT,
                version = BACKUP_VERSION,
                exportedAt = System.currentTimeMillis(),
                preferences = preferences,
                smsLogs = database.smsLogDao().getAllForBackup(),
                pendingTransactions = database.pendingTransactionDao().getAllForBackup(),
                parserRules = database.parserRuleDao().getAllForBackup(),
                titleMappingRules = database.titleMappingRuleDao().getAllForBackup()
            )
        }
        val encoded = json.encodeToString(backup)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) {
            "Backup is larger than the supported 25 MB limit"
        }
        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
            writer.write(encoded)
        } ?: error("The selected file could not be opened")
        backup.summary()
    }

    suspend fun importFrom(
        uri: Uri,
        beforeRestore: suspend () -> Unit = {}
    ): BackupSummary = withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_IMPORT_BYTES) { "Backup is larger than 25 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("The selected file could not be opened")

        val backup = try {
            json.decodeFromString<FireSmsBackup>(bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalArgumentException("This is not a valid FireSMS backup", error)
        }
        validate(backup)
        beforeRestore()

        DataMaintenanceLock.withLock {
            database.withTransaction {
                database.pendingTransactionDao().clearAll()
                database.smsLogDao().clearAll()
                database.titleMappingRuleDao().clearAll()
                database.parserRuleDao().clearAll()

                if (backup.smsLogs.isNotEmpty()) database.smsLogDao().insertAll(backup.smsLogs)
                if (backup.pendingTransactions.isNotEmpty()) {
                    database.pendingTransactionDao().insertAll(backup.pendingTransactions)
                }
                if (backup.parserRules.isNotEmpty()) database.parserRuleDao().insertAll(backup.parserRules)
                if (backup.titleMappingRules.isNotEmpty()) {
                    database.titleMappingRuleDao().insertAll(backup.titleMappingRules)
                }
            }
            preferencesManager.restore(backup.preferences)
        }
        backup.summary()
    }

    private fun validate(backup: FireSmsBackup) {
        require(backup.format == BACKUP_FORMAT) { "Unsupported backup format" }
        require(backup.version == BACKUP_VERSION) {
            "Backup version ${backup.version} is not supported by this app version"
        }
        require(backup.smsLogs.all { it.id > 0 }) { "Backup contains an invalid SMS log ID" }
        require(backup.smsLogs.all { it.status in setOf("pending", "success", "failed", "unparsed") }) {
            "Backup contains an invalid SMS status"
        }
        require(backup.pendingTransactions.all { it.id > 0 }) {
            "Backup contains an invalid pending transaction ID"
        }
        require(backup.titleMappingRules.all { it.id > 0 }) {
            "Backup contains an invalid title mapping ID"
        }
        require(backup.preferences.syncNetworkPolicy in setOf("any_network", "wifi_only")) {
            "Backup contains an invalid sync preference"
        }
        require(backup.preferences.historyRetention ==
            com.firesms.app.data.local.HistoryRetention.fromValue(backup.preferences.historyRetention).value
        ) { "Backup contains an invalid history-retention preference" }
        require(backup.smsLogs.map { it.id }.toSet().size == backup.smsLogs.size) {
            "Backup contains duplicate SMS log IDs"
        }
        require(backup.pendingTransactions.map { it.id }.toSet().size == backup.pendingTransactions.size) {
            "Backup contains duplicate pending transaction IDs"
        }
        require(backup.parserRules.map { it.id }.toSet().size == backup.parserRules.size) {
            "Backup contains duplicate parser rule IDs"
        }
        require(backup.titleMappingRules.map { it.id }.toSet().size == backup.titleMappingRules.size) {
            "Backup contains duplicate title mapping IDs"
        }
        val logIds = backup.smsLogs.mapTo(mutableSetOf()) { it.id }
        require(backup.pendingTransactions.all { it.smsLogId in logIds }) {
            "Backup contains a pending transaction without its SMS history record"
        }
        require(backup.pendingTransactions.all { pending ->
            pending.retries >= 0 && pending.maxRetries > 0 &&
                runCatching {
                    json.decodeFromString<TransactionRequest>(pending.fireflyPayload)
                }.isSuccess
        }) { "Backup contains an invalid queued transaction" }
    }

    private fun FireSmsBackup.summary() = BackupSummary(
        smsLogs = smsLogs.size,
        pendingTransactions = pendingTransactions.size,
        parserRules = parserRules.size,
        titleMappingRules = titleMappingRules.size
    )
}
