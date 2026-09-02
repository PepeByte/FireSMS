package com.firesms.app.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.firesms.app.MainActivity
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.data.local.PreferencesManager
import com.firesms.app.data.local.encode
import com.firesms.app.data.local.toStoredSnapshot
import com.firesms.app.data.remote.FireflyApi
import com.firesms.app.data.remote.FireflyError
import com.firesms.app.data.remote.FireflyUnavailable
import com.firesms.app.data.remote.TransactionRequest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

class RetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RetryWorker"
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
                    val payload = Json { ignoreUnknownKeys = true }
                        .decodeFromString(
                            TransactionRequest.serializer(),
                            tx.fireflyPayload
                        )

                    val log = smsLogDao.getById(tx.smsLogId)
                    if (log?.transactionSnapshotJson.isNullOrBlank()) {
                        smsLogDao.initializeTransactionSnapshot(tx.smsLogId, payload.toStoredSnapshot().encode())
                    }

                    val result = api.createTransactionRaw(baseUrl, payload)
                    // Success: delete pending row, update sms_log
                    pendingDao.delete(tx)
                    smsLogDao.updateStatus(
                        tx.smsLogId, "success",
                        processedAt = System.currentTimeMillis(),
                        fireflyId = result
                    )
                    showSuccessNotification(tx.smsLogId, payload.transactions.firstOrNull()?.description ?: "Transaction")
                    Log.d(TAG, "Retry successful: $result")
                } catch (e: FireflyUnavailable) {
                    // Increment retries with exponential backoff
                    val nextRetry = System.currentTimeMillis() + (60_000L * (1L shl (tx.retries + 1)))
                    pendingDao.updateRetry(tx.id, tx.retries + 1, nextRetry, e.message)
                    smsLogDao.updateStatus(
                        tx.smsLogId, "failed",
                        processedAt = System.currentTimeMillis(),
                        errorMessage = e.message
                    )
                } catch (e: FireflyError) {
                    // Non-retriable 4xx: keep pending as dead-letter, update sms_log
                    pendingDao.updateRetry(tx.id, tx.retries + 1, tx.nextRetryAt, e.message)
                    smsLogDao.updateStatus(
                        tx.smsLogId, "failed",
                        processedAt = System.currentTimeMillis(),
                        errorMessage = e.message ?: e.toString()
                    )
                    Log.e(TAG, "Non-retriable Firefly error in retry", e)
                } catch (e: Exception) {
                    // Non-retriable: log error but keep as dead-letter
                    pendingDao.updateRetry(tx.id, tx.retries, tx.nextRetryAt, e.message)
                    smsLogDao.updateStatus(
                        tx.smsLogId, "failed",
                        processedAt = System.currentTimeMillis(),
                        errorMessage = e.message ?: e.toString()
                    )
                    Log.e(TAG, "Non-retriable error in retry", e)
                }
            }
        } finally {
            api.close()
        }

        // Re-schedule only while actionable retries remain.
        if (pendingDao.countActive() > 0) {
            com.firesms.app.worker.scheduleRetry(applicationContext)
            return Result.success()
        }
        return Result.success()
    }

    private fun showSuccessNotification(smsLogId: Long, description: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("destination", "edit_transaction")
            putExtra("sms_log_id", smsLogId)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, smsLogId.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val editPendingIntent = PendingIntent.getActivity(
            applicationContext, smsLogId.toInt() + 1000, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, "transaction_success")
            .setContentTitle("Transaction added to Firefly")
            .setContentText(description)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_edit, "Edit", editPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2, notification)
    }
}
