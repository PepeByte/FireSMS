package com.firesms.app.data.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.data.local.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class BackupRepository(
    private val database: AppDatabase,
    private val preferencesManager: PreferencesManager,
    private val contentResolver: ContentResolver
) {
    suspend fun exportTo(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        // A Room transaction alone can capture an SMS between processing writes. Use the
        // same lock as SMS processing, retries and restore to capture a completed operation.
        val backup = DataMaintenanceLock.withLock {
            val preferences = preferencesManager.snapshot()
            database.withTransaction {
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
        }
        // Never write a backup that this version of the app cannot restore.
        BackupFormat.validate(backup)
        val encoded = BackupFormat.encode(backup)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) {
            "Backup is larger than the supported 25 MB limit"
        }
        // Document-provider I/O need not block SMS processing once the snapshot is captured.
        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
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

        val backup = BackupFormat.decode(bytes.toString(Charsets.UTF_8))
        beforeRestore()

        DataMaintenanceLock.withLock {
            restoreBackupState(
                preferences = backup.preferences,
                readPreferences = preferencesManager::snapshot,
                writePreferences = preferencesManager::restore,
                transaction = { block -> database.withTransaction { block() } },
                replaceRecords = {
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
            )
        }
        backup.summary()
    }
}
