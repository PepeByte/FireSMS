package com.firesms.app.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.data.local.PreferencesManager
import com.firesms.app.data.local.encode
import com.firesms.app.data.local.toStoredSnapshot
import com.firesms.app.data.remote.FireflyApi
import com.firesms.app.data.remote.FireflyUnavailable
import com.firesms.app.data.remote.TransactionRequest
import com.firesms.app.data.local.SmsLogDao
import com.firesms.app.data.local.decodeTransactionSnapshot
import com.firesms.app.service.SmsStatus
import com.firesms.app.service.displayDescription
import com.firesms.app.service.showSyncFailedNotification
import com.firesms.app.service.showTransactionCreatedNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

class RetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RetryWorker"
        private val payloadJson = Json { ignoreUnknownKeys = true }
    }

    override suspend fun doWork(): Result = DataMaintenanceLock.withLock {
        performWork()
    }

    private suspend fun performWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val pendingDao = db.pendingTransactionDao()
        val smsLogDao = db.smsLogDao()
        val prefs = PreferencesManager(applicationContext)

        val baseUrl = prefs.fireflyUrl.first()
        val token = prefs.fireflyToken.first()

        if (baseUrl.isEmpty() || token.isEmpty()) {
            Log.w(TAG, "Firefly not configured, skipping retry")
            return Result.failure()
        }

        val api = FireflyApi(token)

        try {
            val due = pendingDao.getDueRetries(System.currentTimeMillis())
            for (tx in due) {
                try {
                    val payload = payloadJson.decodeFromString(TransactionRequest.serializer(), tx.fireflyPayload)

                    val log = smsLogDao.getById(tx.smsLogId)
                    if (log?.transactionSnapshotJson.isNullOrBlank()) {
                        smsLogDao.initializeTransactionSnapshot(tx.smsLogId, payload.toStoredSnapshot().encode())
                    }

                    val result = api.createTransactionIfMissing(baseUrl, payload)
                    pendingDao.delete(tx)
                    smsLogDao.updateStatus(
                        tx.smsLogId, SmsStatus.SUCCESS,
                        processedAt = System.currentTimeMillis(),
                        fireflyId = result
                    )
                    showTransactionCreatedNotification(
                        applicationContext,
                        tx.smsLogId,
                        payload.toStoredSnapshot()
                    )
                    Log.d(TAG, "Retry successful: $result")
                } catch (e: FireflyUnavailable) {
                    val retries = tx.retries + 1
                    if (retriesExhausted(retries, tx.maxRetries)) {
                        // Stop automatic retries; the user can still retry by hand.
                        val message = exhaustedRetriesMessage(retries, e.message)
                        pendingDao.markDead(tx.id, message)
                        markFailed(smsLogDao, tx.smsLogId, message)
                    } else {
                        pendingDao.updateRetry(
                            tx.id, retries, System.currentTimeMillis() + retryDelayMillis(retries), e.message
                        )
                        smsLogDao.updateStatus(
                            tx.smsLogId, SmsStatus.QUEUED,
                            processedAt = System.currentTimeMillis(),
                            errorMessage = e.message
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 4xx or an unreadable payload: retrying cannot succeed, keep it as a dead letter.
                    val message = e.message ?: e.toString()
                    pendingDao.markDead(tx.id, message)
                    markFailed(smsLogDao, tx.smsLogId, message)
                    Log.e(TAG, "Non-retriable error in retry", e)
                }
            }
        } finally {
            api.close()
        }

        // Wake up when the next retry is actually due instead of polling every two minutes.
        pendingDao.nextActiveRetryAt()?.let { nextRetryAt ->
            scheduleRetry(applicationContext, nextRetryAt - System.currentTimeMillis())
        }
        return Result.success()
    }

    /** Moves an item out of the automatic queue and tells the user it needs a manual look. */
    private suspend fun markFailed(smsLogDao: SmsLogDao, smsLogId: Long, message: String) {
        smsLogDao.updateStatus(
            smsLogId, SmsStatus.FAILED,
            processedAt = System.currentTimeMillis(),
            errorMessage = message
        )
        val log = smsLogDao.getById(smsLogId)
        val description = log?.let {
            decodeTransactionSnapshot(it.transactionSnapshotJson)?.displayDescription() ?: it.sender
        }
        showSyncFailedNotification(applicationContext, smsLogId, description, message)
    }
}

/** True once [retriesDone] automatic retries have used up the row's budget. */
internal fun retriesExhausted(retriesDone: Int, maxRetries: Int): Boolean = retriesDone >= maxRetries

internal fun exhaustedRetriesMessage(attempts: Int, lastError: String?): String {
    val base = "Automatic retries stopped after $attempts ${if (attempts == 1) "attempt" else "attempts"}"
    return if (lastError.isNullOrBlank()) base else "$base: $lastError"
}

/** Exponential backoff: 2, 4, 8, ... minutes after the given number of completed retries. */
internal fun retryDelayMillis(retriesDone: Int): Long = 60_000L * (1L shl retriesDone.coerceIn(1, 20))
