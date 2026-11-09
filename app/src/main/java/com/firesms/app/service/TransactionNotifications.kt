package com.firesms.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.firesms.app.MainActivity
import com.firesms.app.data.local.StoredTransactionSnapshot
import com.firesms.app.ui.components.formatDisplayAmount
import java.math.BigDecimal

internal const val PROCESSING_CHANNEL_ID = "sms_processing"
private const val SUCCESS_CHANNEL_ID = "transaction_success"
private const val ATTENTION_CHANNEL_ID = "needs_attention"

// Per-SMS notifications use a tag plus the SMS log ID, so they never collide with the fixed
// foreground (1) and update (3) notification IDs, and a later outcome replaces an earlier one.
private const val SUCCESS_TAG = "transaction_added"
private const val ATTENTION_TAG = "needs_attention"
private const val SUMMARY_TAG = "summary"
private const val SUCCESS_SUMMARY_ID = 1
private const val ATTENTION_SUMMARY_ID = 2
private const val SUCCESS_GROUP = "com.firesms.app.TRANSACTIONS_ADDED"
private const val ATTENTION_GROUP = "com.firesms.app.NEEDS_ATTENTION"

/** Intent extras read by MainActivity. */
const val EXTRA_DESTINATION = "destination"
const val EXTRA_SMS_LOG_ID = "sms_log_id"
const val DESTINATION_EDIT_TRANSACTION = "edit_transaction"
const val DESTINATION_UNPARSED = "unparsed"
const val DESTINATION_SMS_LOG = "sms_log"

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
    manager.createNotificationChannel(
        NotificationChannel(
            ATTENTION_CHANNEL_ID,
            "Needs Attention",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Messages FireSMS could not read and transactions that could not sync" }
    )
}

fun showTransactionCreatedNotification(context: Context, smsLogId: Long, snapshot: StoredTransactionSnapshot?) {
    val description = snapshot?.displayDescription() ?: "Transaction"
    val amount = snapshot?.let(::notificationAmount)
    val intent = mainIntent(context, DESTINATION_EDIT_TRANSACTION, smsLogId)
    val pendingIntent = PendingIntent.getActivity(
        context, smsLogId.toInt(), intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val text = listOfNotNull(amount, description).joinToString(" · ")
    val notification = NotificationCompat.Builder(context, SUCCESS_CHANNEL_ID)
        .setContentTitle("Transaction added to Firefly")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentIntent(pendingIntent)
        .addAction(android.R.drawable.ic_menu_edit, "Edit", pendingIntent)
        .setGroup(SUCCESS_GROUP)
        .setAutoCancel(true)
        .build()

    val manager = context.getSystemService(NotificationManager::class.java)
    // A synced item no longer needs attention.
    manager.cancel(ATTENTION_TAG, smsLogId.toInt())
    manager.notify(SUCCESS_TAG, smsLogId.toInt(), notification)
    postSummary(context, manager, SUCCESS_GROUP, SUCCESS_CHANNEL_ID, SUCCESS_SUMMARY_ID, smsLogId) { count ->
        "$count ${if (count == 1) "transaction" else "transactions"} added to Firefly"
    }
}

/** An SMS from a known sender that no rule could read; opens the Needs attention screen. */
fun showUnparsedSmsNotification(context: Context, smsLogId: Long, sender: String) {
    showAttentionNotification(
        context = context,
        smsLogId = smsLogId,
        title = "Message from $sender needs a rule",
        text = "FireSMS couldn't read this message. Teach it once and similar messages sync automatically.",
        intent = mainIntent(context, DESTINATION_UNPARSED, smsLogId)
    )
}

/** A transaction that will not be retried automatically; opens the item on the Activity screen. */
fun showSyncFailedNotification(context: Context, smsLogId: Long, description: String?, reason: String?) {
    showAttentionNotification(
        context = context,
        smsLogId = smsLogId,
        title = if (description.isNullOrBlank()) "Transaction couldn't sync" else "Couldn't sync “$description”",
        text = reason?.takeIf { it.isNotBlank() } ?: "Open FireSMS to review and retry.",
        intent = mainIntent(context, DESTINATION_SMS_LOG, smsLogId)
    )
}

private fun showAttentionNotification(
    context: Context,
    smsLogId: Long,
    title: String,
    text: String,
    intent: Intent
) {
    // Offset the request code so it never shares a PendingIntent with the success notification.
    val pendingIntent = PendingIntent.getActivity(
        context, (smsLogId.toInt() xor ATTENTION_REQUEST_MASK), intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val notification = NotificationCompat.Builder(context, ATTENTION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentIntent(pendingIntent)
        .setGroup(ATTENTION_GROUP)
        .setAutoCancel(true)
        .build()
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.notify(ATTENTION_TAG, smsLogId.toInt(), notification)
    postSummary(context, manager, ATTENTION_GROUP, ATTENTION_CHANNEL_ID, ATTENTION_SUMMARY_ID, smsLogId) { count ->
        "$count ${if (count == 1) "item needs" else "items need"} your attention"
    }
}

private const val ATTENTION_REQUEST_MASK = 0x40000000

private fun mainIntent(context: Context, destination: String, smsLogId: Long) =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_DESTINATION, destination)
        putExtra(EXTRA_SMS_LOG_ID, smsLogId)
    }

/** A group summary is required for Android to bundle per-SMS notifications. */
private fun postSummary(
    context: Context,
    manager: NotificationManager,
    group: String,
    channelId: String,
    summaryId: Int,
    justPostedId: Long,
    title: (Int) -> String
) {
    val others = runCatching {
        manager.activeNotifications.count { active ->
            active.notification.group == group && active.tag != SUMMARY_TAG && active.id != justPostedId.toInt()
        }
    }.getOrDefault(0)
    val count = others + 1
    val summary = NotificationCompat.Builder(context, channelId)
        .setContentTitle(title(count))
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setStyle(NotificationCompat.InboxStyle().setSummaryText(title(count)))
        .setGroup(group)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setAutoCancel(true)
        .build()
    manager.notify(SUMMARY_TAG, summaryId, summary)
}

/** The transaction total with its currency, e.g. "₹1250.00" or "EUR 12.50". */
internal fun notificationAmount(snapshot: StoredTransactionSnapshot): String? {
    val first = snapshot.transactions.firstOrNull() ?: return null
    val raw = if (snapshot.transactions.size == 1) {
        first.amount
    } else {
        snapshot.transactions.fold(BigDecimal.ZERO) { sum, split ->
            sum + (split.amount.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO)
        }.toPlainString()
    }
    if (raw.isBlank()) return null
    val amount = formatDisplayAmount(raw)
    return when {
        !first.currencySymbol.isNullOrBlank() -> "${first.currencySymbol}$amount"
        !first.currencyCode.isNullOrBlank() -> "${first.currencyCode} $amount"
        else -> amount
    }
}
