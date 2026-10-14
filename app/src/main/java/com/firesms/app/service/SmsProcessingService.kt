package com.firesms.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.firesms.app.MainActivity
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.data.local.entity.SmsLog
import com.firesms.app.domain.parser.SmsParser
import kotlinx.coroutines.*
import java.security.MessageDigest

class SmsProcessingService : Service() {

    companion object {
        private const val TAG = "SmsProcessingService"
        private const val NOTIFICATION_ID = 1

        fun smsHash(sender: String, body: String, receivedAt: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest("$sender|$body|$receivedAt".toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase
    private lateinit var parser: SmsParser
    private lateinit var processor: SmsTransactionProcessor

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        parser = SmsParser(db.parserRuleDao())
        processor = SmsTransactionProcessor(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

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
        retryLogId: Long?
    ) {
        val smsLogDao = db.smsLogDao()

        if (retryLogId != null) {
            val retryLog = smsLogDao.getById(retryLogId)
            if (retryLog == null) {
                Log.w(TAG, "Retry requested for missing SMS log: $retryLogId")
                return
            }
            if (!parser.senderMatchesAnyEnabledRule(retryLog.sender)) {
                smsLogDao.updateStatus(
                    retryLog.id,
                    "unparsed",
                    processedAt = System.currentTimeMillis(),
                    errorMessage = "No enabled parser rule matches sender"
                )
                return
            }
            // Manual retry re-uses the existing log row; the new-SMS path deduplicates by hash.
            db.pendingTransactionDao().deleteBySmsLogId(retryLog.id)
            smsLogDao.updateStatus(retryLog.id, "pending")
            processor.process(retryLog)
            return
        }

        // Defensive sender match check (catches manual service starts)
        if (!parser.senderMatchesAnyEnabledRule(sender)) {
            Log.d(TAG, "Ignoring SMS from sender with no enabled parser rule match: $sender")
            return
        }

        val hash = smsHash(sender, body, receivedAt)
        if (smsLogDao.findByHash(hash) != null) {
            Log.d(TAG, "Duplicate SMS detected, skipping")
            return
        }

        val log = SmsLog(
            smsHash = hash,
            sender = sender,
            body = body,
            receivedAt = receivedAt,
            status = "pending"
        )
        val insertedId = smsLogDao.insert(log)
        if (insertedId <= 0L) {
            Log.w(TAG, "Insert returned $insertedId, aborting processing")
            return
        }

        val outcome = processor.process(log.copy(id = insertedId))
        Log.d(TAG, "Processed SMS from $sender: $outcome")
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, PROCESSING_CHANNEL_ID)
            .setContentTitle("FireSMS")
            .setContentText("Monitoring SMS messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
