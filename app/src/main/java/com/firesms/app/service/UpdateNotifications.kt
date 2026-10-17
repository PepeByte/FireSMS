package com.firesms.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.firesms.app.data.update.AppRelease

private const val UPDATE_CHANNEL_ID = "app_updates"
private const val UPDATE_NOTIFICATION_ID = 3

fun createUpdateNotificationChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(UPDATE_CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "New stable FireSMS releases on GitHub" }
    )
}

/** Returns false if notifications are blocked, so a future check may try again. */
fun showUpdateAvailableNotification(context: Context, release: AppRelease): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) return false
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled() ||
        manager.getNotificationChannel(UPDATE_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
    ) return false

    val intent = Intent(Intent.ACTION_VIEW, release.pageUrl.toUri())
    val pendingIntent = PendingIntent.getActivity(
        context, UPDATE_NOTIFICATION_ID, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("FireSMS ${release.version} is available")
        .setContentText("Tap to view the release and download the update on GitHub.")
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .build()
    return try {
        manager.notify(UPDATE_NOTIFICATION_ID, notification)
        true
    } catch (_: SecurityException) {
        // Permission can be revoked between checking it and posting.
        false
    }
}

fun dismissUpdateNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(UPDATE_NOTIFICATION_ID)
}
