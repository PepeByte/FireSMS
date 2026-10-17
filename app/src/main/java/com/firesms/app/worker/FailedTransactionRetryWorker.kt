package com.firesms.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.data.local.FailedTransactionRetryCandidate
import com.firesms.app.data.local.PreferencesManager
import com.firesms.app.service.SmsTransactionProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.util.UUID

/** One durable work item per SMS: completed attempts are not replayed when a batch is stopped. */
class FailedTransactionRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "manual_failed_retry"
        internal const val BATCH_TAG_PREFIX = "failed-retry-batch:"
        private const val CANDIDATE_TAG_PREFIX = "failed-retry-log:"
        private const val LOG_ID = "log_id"
        private const val SMS_HASH = "sms_hash"

        internal fun request(
            candidate: FailedTransactionRetryCandidate,
            constraints: Constraints,
            batchTag: String,
            id: UUID = UUID.randomUUID()
        ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<FailedTransactionRetryWorker>()
            .setId(id)
            .setConstraints(constraints)
            .setInputData(workDataOf(LOG_ID to candidate.id, SMS_HASH to candidate.smsHash))
            .addTag(batchTag)
            .addTag("$CANDIDATE_TAG_PREFIX${candidate.id}:${candidate.smsHash}")
            .build()

        internal fun candidate(work: WorkInfo): FailedTransactionRetryCandidate? {
            val value = work.tags.firstOrNull { it.startsWith(CANDIDATE_TAG_PREFIX) }
                ?.removePrefix(CANDIDATE_TAG_PREFIX) ?: return null
            val parts = value.split(':', limit = 2)
            val id = parts.firstOrNull()?.toLongOrNull() ?: return null
            return parts.getOrNull(1)?.let { FailedTransactionRetryCandidate(id, it) }
        }
    }

    override suspend fun doWork(): Result {
        val logId = inputData.getLong(LOG_ID, -1)
        val smsHash = inputData.getString(SMS_HASH) ?: return Result.failure()
        if (logId <= 0) return Result.failure()
        DataMaintenanceLock.withLock {
            currentCoroutineContext().ensureActive()
            SmsTransactionProcessor(applicationContext).retryFailed(logId, smsHash)
        }
        // A transaction-level failure is a completed manual attempt, not a failed work chain.
        // Transient errors are handled by the separate automatic retry queue.
        return Result.success()
    }
}

data class FailedRetryProgress(val completed: Int, val total: Int, val isRunning: Boolean)

internal fun failedRetryProgress(work: List<WorkInfo>): FailedRetryProgress? {
    val active = work.firstOrNull { !it.state.isFinished } ?: return null
    val batchTag = active.tags.firstOrNull { it.startsWith(FailedTransactionRetryWorker.BATCH_TAG_PREFIX) }
        ?: return null
    val batch = work.filter { batchTag in it.tags }
    return FailedRetryProgress(
        completed = batch.count { it.state.isFinished },
        total = batch.size,
        isRunning = batch.any { it.state == WorkInfo.State.RUNNING }
    )
}

private suspend fun manualRetryConstraints(context: Context): Constraints {
    val policy = PreferencesManager(context).syncNetworkPolicy.first()
    return Constraints.Builder()
        .setRequiredNetworkType(if (policy == "wifi_only") NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()
}

suspend fun scheduleFailedTransactionRetry(context: Context) {
    val candidates = DataMaintenanceLock.withLock {
        AppDatabase.getInstance(context).smsLogDao().getRetryableFailedCandidates()
    }
    if (candidates.isEmpty()) return
    val constraints = manualRetryConstraints(context)
    val batchTag = "${FailedTransactionRetryWorker.BATCH_TAG_PREFIX}${UUID.randomUUID()}"
    val requests = candidates.map { FailedTransactionRetryWorker.request(it, constraints, batchTag) }
    val manager = WorkManager.getInstance(context)
    // KEEP makes repeated taps harmless; serial chaining avoids simultaneous financial submissions.
    var chain = manager.beginUniqueWork(FailedTransactionRetryWorker.WORK_NAME, ExistingWorkPolicy.KEEP, requests.first())
    requests.drop(1).forEach { request -> chain = chain.then(request) }
    chain.enqueue().await()
}

/** Update unfinished requests without replaying completed attempts or replacing the batch. */
suspend fun updateFailedRetryNetworkPolicy(context: Context) {
    val manager = WorkManager.getInstance(context)
    val work = manager.getWorkInfosForUniqueWorkFlow(FailedTransactionRetryWorker.WORK_NAME).first()
    val constraints = manualRetryConstraints(context)
    work.filter { !it.state.isFinished }.forEach { info ->
        val candidate = FailedTransactionRetryWorker.candidate(info) ?: return@forEach
        val batchTag = info.tags.firstOrNull { it.startsWith(FailedTransactionRetryWorker.BATCH_TAG_PREFIX) }
            ?: return@forEach
        runInterruptible(Dispatchers.IO) {
            manager.updateWork(FailedTransactionRetryWorker.request(candidate, constraints, batchTag, info.id)).get()
        }
    }
}
