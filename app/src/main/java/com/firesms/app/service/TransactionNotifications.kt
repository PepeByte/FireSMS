package com.firesms.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.firesms.app.MainActivity

internal const val PROCESSING_CHANNEL_ID = "sms_processing"
private const val SUCCESS_CHANNEL_ID = "transaction_success"
private const val SUCCESS_NOTIFICATION_ID = 2

/** Creates notification channels. Called from Application so workers can post before the service runs. */
fun createNotificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            PROCESSING_CHANNEL_ID,
            "SMS Processing",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "FireSMS foreground service notification" }
    )
    manager.createNotificationChannel(
        NotificationChannel(
            SUCCESS_CHANNEL_ID,
            "Transaction Added",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Notifications when Firefly transactions are created" }
    )
}

fun showTransactionCreatedNotification(context: Context, smsLogId: Long, description: String) {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("destination", "edit_transaction")
        putExtra("sms_log_id", smsLogId)
    }
    val pendingIntent = PendingIntent.getActivity(
        context, smsLogId.toInt(), intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val notification = NotificationCompat.Builder(context, SUCCESS_CHANNEL_ID)
        .setContentTitle("Transaction added to Firefly")
        .setContentText(description)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentIntent(pendingIntent)
        .addAction(android.R.drawable.ic_menu_edit, "Edit", pendingIntent)
        .setAutoCancel(true)
        .build()

    context.getSystemService(NotificationManager::class.java)
        .notify(SUCCESS_NOTIFICATION_ID, notification)
}
