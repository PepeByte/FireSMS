package com.firesms.app.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsMessage
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
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val bundle = intent.extras ?: return
            val pdus = bundle["pdus"] as? Array<*> ?: return
            val messages = pdus.mapNotNull { pdu ->
                try {
                    SmsMessage.createFromPdu(pdu as ByteArray, SmsMessage.FORMAT_3GPP)
                } catch (_: Exception) {
                    try {
                        SmsMessage.createFromPdu(pdu as ByteArray, SmsMessage.FORMAT_3GPP2)
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val parser = SmsParser(AppDatabase.getInstance(context).parserRuleDao())

                    for (msg in messages) {
                        val sender = msg.originatingAddress ?: continue
                        val body = msg.messageBody ?: continue
                        val receivedAt = msg.timestampMillis

                        Log.d(TAG, "SMS received from $sender: ${body.take(80)}")

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
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
