package com.firesms.app

import android.app.Application
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.PreferencesManager
import com.firesms.app.data.update.AppUpdateStore
import com.firesms.app.data.update.UpdateRepository
import com.firesms.app.service.createNotificationChannels
import com.firesms.app.service.createUpdateNotificationChannel
import com.firesms.app.service.dismissUpdateNotification
import com.firesms.app.worker.scheduleAppUpdateChecks
import com.firesms.app.worker.configureHistoryCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FireSmsApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var database: AppDatabase
        private set
    lateinit var preferencesManager: PreferencesManager
        private set
    lateinit var updateRepository: UpdateRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        preferencesManager = PreferencesManager(this)
        updateRepository = UpdateRepository(
            AppUpdateStore(this), BuildConfig.VERSION_NAME,
            dismissNotification = { dismissUpdateNotification(this) }
        )
        createNotificationChannels(this)
        createUpdateNotificationChannel(this)
        scheduleAppUpdateChecks(this)
        applicationScope.launch { configureHistoryCleanup(this@FireSmsApp) }
        applicationScope.launch { updateRepository.dismissObsoleteNotification() }
    }
}
