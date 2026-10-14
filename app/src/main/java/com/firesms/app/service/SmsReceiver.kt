package com.firesms.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.domain.parser.SmsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Long SMS arrive as several PDUs; join the parts so the body pattern sees the full text.
        // getMessagesFromIntent also decodes using the PDU format the radio reported.
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?.filterNotNull()
            .orEmpty()
            .filter { it.originatingAddress != null && it.messageBody != null }
            .groupBy { it.originatingAddress!! }
        if (messages.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val parser = SmsParser(AppDatabase.getInstance(context).parserRuleDao())

                for ((sender, parts) in messages) {
                    val body = parts.joinToString("") { it.messageBody }
                    val receivedAt = parts.first().timestampMillis

                    if (!parser.senderMatchesAnyEnabledRule(sender)) {
                        Log.d(TAG, "Ignoring SMS from sender with no enabled parser rule match: $sender")
                        continue
                    }

                    val serviceIntent = Intent(context, SmsProcessingService::class.java).apply {
                        putExtra("sender", sender)
                        putExtra("body", body)
                        putExtra("receivedAt", receivedAt)
                    }
                    context.startForegroundService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
