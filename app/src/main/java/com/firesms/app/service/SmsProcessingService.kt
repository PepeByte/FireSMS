package com.firesms.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.firesms.app.MainActivity
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.data.local.PreferencesManager
import com.firesms.app.data.local.encode
import com.firesms.app.data.local.toStoredSnapshot
import com.firesms.app.data.remote.FireflyApi
import com.firesms.app.data.remote.FireflyError
import com.firesms.app.data.remote.FireflyUnavailable
import com.firesms.app.data.remote.buildCreateRequest
import com.firesms.app.domain.parser.SmsParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

class SmsProcessingService : Service() {

    companion object {
        private const val TAG = "SmsProcessingService"
        private const val CHANNEL_ID = "sms_processing"
        private const val SUCCESS_CHANNEL_ID = "transaction_success"
        private const val NOTIFICATION_ID = 1
        private const val SUCCESS_NOTIFICATION_ID = 2
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var parser: SmsParser

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        prefs = PreferencesManager(this)
        parser = SmsParser(db.parserRuleDao())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(0))

        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val sender = intent.getStringExtra("sender") ?: ""
        val body = intent.getStringExtra("body") ?: ""
        val receivedAt = intent.getLongExtra("receivedAt", System.currentTimeMillis())
        val retryLogId = intent.getLongExtra("retryLogId", -1L).takeIf { it > 0L }

        if (retryLogId == null && (sender.isEmpty() || body.isEmpty())) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                DataMaintenanceLock.withLock {
                    processSms(sender, body, receivedAt, retryLogId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS", e)
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun processSms(
        sender: String,
        body: String,
        receivedAt: Long,
        retryLogId: Long? = null
    ) {
        val smsLogDao = db.smsLogDao()
        val pendingDao = db.pendingTransactionDao()
        val retryLog = retryLogId?.let { id ->
            smsLogDao.getById(id).also {
                if (it == null) Log.w(TAG, "Retry requested for missing SMS log: $id")
            }
        } ?: if (retryLogId != null) return else null

        val smsSender = retryLog?.sender ?: sender
        val smsBody = retryLog?.body ?: body
        val smsReceivedAt = retryLog?.receivedAt ?: receivedAt
        val hash = retryLog?.smsHash ?: sha256("$smsSender|$smsBody|$smsReceivedAt")

        // Defensive sender match check (catches manual service starts)
        if (!parser.senderMatchesAnyEnabledRule(smsSender)) {
            Log.d(TAG, "Ignoring SMS from sender with no enabled parser rule match: $smsSender")
            if (retryLog != null) {
                smsLogDao.updateStatus(
                    retryLog.id,
                    "unparsed",
                    processedAt = System.currentTimeMillis(),
                    errorMessage = "No enabled parser rule matches sender"
                )
            }
            return
        }

        val logId = if (retryLog != null) {
            // Manual retry should re-use the existing log row. The normal SMS path
            // deduplicates by hash, which is exactly why the Retry button used to
            // appear to do nothing for already-logged failures.
            pendingDao.deleteBySmsLogId(retryLog.id)
            smsLogDao.updateStatus(retryLog.id, "pending")
            retryLog.id
        } else {
            // Dedup check for newly received SMS messages.
            val existing = smsLogDao.findByHash(hash)
            if (existing != null) {
                Log.d(TAG, "Duplicate SMS detected, skipping")
                return
            }

            val insertedId = smsLogDao.insert(
                com.firesms.app.data.local.entity.SmsLog(
                    smsHash = hash,
                    sender = smsSender,
                    body = smsBody,
                    receivedAt = smsReceivedAt,
                    status = "pending"
                )
            )

            if (insertedId <= 0L) {
                Log.w(TAG, "Insert returned $insertedId, aborting processing")
                return
            }
            insertedId
        }

        try {

        // 4. Parse
        val parsed = parser.parse(smsSender, smsBody, smsReceivedAt)
        if (parsed == null) {
            Log.d(TAG, "No matching rule for SMS from $smsSender")
            smsLogDao.updateStatus(logId, "unparsed", processedAt = System.currentTimeMillis())
            return
        }

        // Persist the submitted transaction as an immutable comparison baseline and display cache.
        val createRequest = buildCreateRequest(parsed, hash)
        smsLogDao.initializeTransactionSnapshot(logId, createRequest.toStoredSnapshot().encode())

        // 5. Send to Firefly
        val baseUrl = prefs.fireflyUrl.first()
        val token = prefs.fireflyToken.first()

        if (baseUrl.isEmpty() || token.isEmpty()) {
            Log.w(TAG, "Firefly not configured")
            smsLogDao.updateStatus(
                logId,
                "failed",
                processedAt = System.currentTimeMillis(),
                errorMessage = "Firefly not configured"
            )
            return
        }

        val api = FireflyApi(token)
        try {
            val fireflyId = api.createTransaction(baseUrl, parsed, hash)
            smsLogDao.updateStatus(logId, "success", processedAt = System.currentTimeMillis(), fireflyId = fireflyId)
            Log.d(TAG, "Transaction created: $fireflyId")
            showSuccessNotification(logId, parsed.description)
        } catch (e: FireflyError) {
            // 4xx — bad request, don't retry
            Log.w(TAG, "Firefly rejected transaction: ${e.message}")
            smsLogDao.updateStatus(logId, "failed", processedAt = System.currentTimeMillis(), errorMessage = e.message)
        } catch (e: FireflyUnavailable) {
            // 5xx/connection — queue for retry
            Log.w(TAG, "Firefly unavailable, queuing for retry: ${e.message}")
            smsLogDao.updateStatus(logId, "failed", processedAt = System.currentTimeMillis(), errorMessage = e.message)
            val payload = kotlinx.serialization.json.Json.encodeToString(
                com.firesms.app.data.remote.TransactionRequest.serializer(),
                createRequest
            )
            pendingDao.insert(
                com.firesms.app.data.local.entity.PendingTransaction(
                    smsLogId = logId,
                    fireflyPayload = payload,
                    nextRetryAt = System.currentTimeMillis() + 120_000 // 2 min
                )
            )
            scheduleRetry()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending to Firefly", e)
            smsLogDao.updateStatus(logId, "failed", processedAt = System.currentTimeMillis(), errorMessage = e.message)
        } finally {
            api.close()
        }

        } catch (e: Exception) {
            Log.e(TAG, "Fatal processing error", e)
            smsLogDao.updateStatus(logId, "failed", processedAt = System.currentTimeMillis(), errorMessage = e.message ?: e.toString())
        }
        updateNotification()
    }

    private suspend fun scheduleRetry() {
        com.firesms.app.worker.scheduleRetry(this)
    }

    private fun updateNotification() {
        val notification = buildNotification(0)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(pendingCount: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FireSMS")
            .setContentText("Monitoring SMS messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    private fun showSuccessNotification(smsLogId: Long, description: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("destination", "edit_transaction")
            putExtra("sms_log_id", smsLogId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, smsLogId.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val editPendingIntent = PendingIntent.getActivity(
            this, smsLogId.toInt() + 1000, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, SUCCESS_CHANNEL_ID)
            .setContentTitle("Transaction added to Firefly")
            .setContentText(description)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_edit, "Edit", editPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(SUCCESS_NOTIFICATION_ID, notification)
    }
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS Processing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "FireSMS foreground service notification"
        }
        val successChannel = NotificationChannel(
            SUCCESS_CHANNEL_ID,
            "Transaction Added",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications when Firefly transactions are created"
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(successChannel)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
