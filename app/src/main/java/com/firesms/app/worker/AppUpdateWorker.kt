package com.firesms.app.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.firesms.app.FireSmsApp
import com.firesms.app.service.showUpdateAvailableNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as FireSmsApp
        app.updateRepository.checkForUpdates { update ->
            val revision = app.updateRepository.state.first().ignoreRevision
            showUpdateAvailableNotification(applicationContext, update, revision)
        }
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Bound retries for outages/rate limits; after that, wait for the next daily check.
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}

internal fun appUpdateWorkRequest() = PeriodicWorkRequestBuilder<AppUpdateWorker>(1, TimeUnit.DAYS)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
    .build()

fun scheduleAppUpdateChecks(context: Context) {
    // The first check is eligible immediately. KEEP preserves cadence across app restarts.
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "app_update_check",
        ExistingPeriodicWorkPolicy.KEEP,
        appUpdateWorkRequest()
    )
}
