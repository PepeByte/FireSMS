package com.firesms.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.firesms.app.data.update.StableVersion
import com.firesms.app.worker.scheduleIgnoreAppUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Private receiver for the immutable, version-specific update notification action. */
class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_IGNORE_UPDATE) return
        val version = intent.getStringExtra(EXTRA_RELEASE_VERSION)
            ?.takeIf { StableVersion.parse(it) != null } ?: return
        val revision = intent.getLongExtra(EXTRA_IGNORE_REVISION, -1)
        if (revision < 0) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Only wait for durable enqueue here. A running release check may hold the
                // repository lock longer than a broadcast receiver is allowed to stay alive.
                scheduleIgnoreAppUpdate(appContext, version, revision)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Leave the notification in place if scheduling failed so the user can retry.
                Log.e("UpdateActionReceiver", "Couldn't schedule update ignore action", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal companion object {
        const val ACTION_IGNORE_UPDATE = "com.firesms.app.action.IGNORE_UPDATE"
        const val EXTRA_RELEASE_VERSION = "release_version"
        const val EXTRA_IGNORE_REVISION = "ignore_revision"
    }
}
