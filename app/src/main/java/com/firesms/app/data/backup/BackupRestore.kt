package com.firesms.app.data.backup

import com.firesms.app.data.local.AppPreferencesSnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Coordinates Room and DataStore while the caller holds DataMaintenanceLock.
 * Keep Room uncommitted until preferences are written, and compensate the preference
 * write if the transaction fails. Once replacement starts, navigation cancellation
 * must not leave imported records paired with the previous Firefly credentials.
 * This handles runtime failures; it is not a cross-store crash-recovery journal.
 */
internal suspend fun restoreBackupState(
    preferences: AppPreferencesSnapshot,
    readPreferences: suspend () -> AppPreferencesSnapshot,
    writePreferences: suspend (AppPreferencesSnapshot) -> Unit,
    transaction: suspend (suspend () -> Unit) -> Unit,
    replaceRecords: suspend () -> Unit
) {
    currentCoroutineContext().ensureActive()
    withContext(NonCancellable) {
        val previousPreferences = readPreferences()
        var preferencesWritten = false
        try {
            transaction {
                replaceRecords()
                writePreferences(preferences)
                preferencesWritten = true
            }
        } catch (error: Exception) {
            if (preferencesWritten) {
                try {
                    writePreferences(previousPreferences)
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
            }
            throw error
        }
    }
}
