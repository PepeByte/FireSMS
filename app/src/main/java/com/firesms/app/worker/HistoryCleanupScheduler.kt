package com.firesms.app.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.firesms.app.data.local.HistoryRetention
import com.firesms.app.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

private const val PERIODIC_WORK_NAME = "history_cleanup"
private const val IMMEDIATE_WORK_NAME = "history_cleanup_now"

suspend fun configureHistoryCleanup(context: Context, retentionValue: String? = null) {
    val retention = HistoryRetention.fromValue(
        retentionValue ?: PreferencesManager(context).historyRetention.first()
    )
    val workManager = WorkManager.getInstance(context)

    if (retention == HistoryRetention.NEVER) {
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        return
    }

    val periodic = PeriodicWorkRequestBuilder<HistoryCleanupWorker>(1, TimeUnit.DAYS).build()
    workManager.enqueueUniquePeriodicWork(
        PERIODIC_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        periodic
    )

    workManager.enqueueUniqueWork(
        IMMEDIATE_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<HistoryCleanupWorker>().build()
    )
}
