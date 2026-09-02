package com.firesms.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.data.local.HistoryCleaner
import com.firesms.app.data.local.HistoryRetention
import com.firesms.app.data.local.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class HistoryCleanupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        DataMaintenanceLock.withLock {
            val retention = HistoryRetention.fromValue(
                PreferencesManager(applicationContext).historyRetention.first()
            )
            HistoryCleaner(AppDatabase.getInstance(applicationContext)).applyRetention(retention)
            Result.success()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Result.retry()
    }
}
