package com.firesms.app

import android.app.Application
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.PreferencesManager
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

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        preferencesManager = PreferencesManager(this)
        applicationScope.launch { configureHistoryCleanup(this@FireSmsApp) }
    }
}
