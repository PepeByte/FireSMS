package com.firesms.app.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.firesms.app.FireSmsApp
import com.firesms.app.data.update.StableVersion
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Durable local-only notification action; independent of connectivity and update-check timing. */
class IgnoreAppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val version = inputData.getString(VERSION)
            ?.takeIf { StableVersion.parse(it) != null } ?: return Result.failure()
        val revision = inputData.getLong(REVISION, -1)
        if (revision < 0) return Result.failure()
        return try {
            (applicationContext as FireSmsApp).updateRepository.ignoreUpdate(version, revision)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    internal companion object {
        const val VERSION = "release_version"
        const val REVISION = "ignore_revision"

        fun request(version: String, revision: Long) = OneTimeWorkRequestBuilder<IgnoreAppUpdateWorker>()
            .setInputData(workDataOf(VERSION to version, REVISION to revision))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
    }
}

internal suspend fun scheduleIgnoreAppUpdate(context: Context, version: String, revision: Long) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "ignore_app_update:$version:$revision",
        ExistingWorkPolicy.KEEP,
        IgnoreAppUpdateWorker.request(version, revision)
    ).await()
}
