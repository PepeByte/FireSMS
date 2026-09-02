package com.firesms.app.worker

import android.content.Context
import androidx.work.*
import com.firesms.app.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Schedule a retry for pending Firefly transaction creates.
 * Reads the user's sync network policy from preferences and applies
 * the appropriate [NetworkType] constraint so retries only fire
 * when the device is on an allowed network.
 */
suspend fun scheduleRetry(context: Context) {
    val prefs = PreferencesManager(context)
    val policy = prefs.syncNetworkPolicy.first()

    val networkConstraint = when (policy) {
        "wifi_only" -> NetworkType.UNMETERED
        else -> NetworkType.CONNECTED
    }

    val constraints = Constraints.Builder()
        .setRequiredNetworkType(networkConstraint)
        .build()

    val workRequest = OneTimeWorkRequestBuilder<RetryWorker>()
        .setInitialDelay(2, TimeUnit.MINUTES)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        "firefly_retry",
        ExistingWorkPolicy.REPLACE,
        workRequest
    )
}
