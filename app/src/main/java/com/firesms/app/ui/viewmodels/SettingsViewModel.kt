package com.firesms.app.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.work.WorkManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import com.firesms.app.data.backup.BackupRepository
import com.firesms.app.data.local.HistoryRetention
import com.firesms.app.data.remote.FireflyApi
import com.firesms.app.worker.configureHistoryCleanup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FireSmsApp
    private val prefs = app.preferencesManager
    private val backupRepository = BackupRepository(app.database, prefs, application.contentResolver)

    val fireflyUrl: StateFlow<String> = prefs.fireflyUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val fireflyToken: StateFlow<String> = prefs.fireflyToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val syncNetworkPolicy: StateFlow<String> = prefs.syncNetworkPolicy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "any_network")

    val historyRetention: StateFlow<String> = prefs.historyRetention
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryRetention.NEVER.value)

    sealed class TestResult {
        data object Success : TestResult()
        data class Failure(val message: String) : TestResult()
    }

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    sealed class DataTransferState {
        data class Working(val action: String) : DataTransferState()
        data class Success(val message: String) : DataTransferState()
        data class Failure(val message: String) : DataTransferState()
    }

    private val _dataTransferState = MutableStateFlow<DataTransferState?>(null)
    val dataTransferState: StateFlow<DataTransferState?> = _dataTransferState.asStateFlow()

    fun saveSyncNetworkPolicy(value: String) {
        viewModelScope.launch {
            prefs.setSyncNetworkPolicy(value)
            // Re-schedule retries with new network constraint
            com.firesms.app.worker.scheduleRetry(getApplication())
        }
    }

    fun saveHistoryRetention(value: String) {
        viewModelScope.launch {
            val retention = HistoryRetention.fromValue(value)
            prefs.setHistoryRetention(retention.value)
            configureHistoryCleanup(getApplication(), retention.value)
        }
    }

    fun exportData(uri: Uri) {
        viewModelScope.launch {
            _dataTransferState.value = DataTransferState.Working("Exporting backup…")
            try {
                val summary = backupRepository.exportTo(uri)
                _dataTransferState.value = DataTransferState.Success(
                    "Backup exported (${summary.totalRecords} records)"
                )
            } catch (error: Exception) {
                _dataTransferState.value = DataTransferState.Failure(
                    "Export failed: ${error.message ?: "Unknown error"}"
                )
            }
        }
    }

    fun importData(uri: Uri) {
        viewModelScope.launch {
            _dataTransferState.value = DataTransferState.Working("Restoring backup…")
            try {
                val summary = backupRepository.importFrom(uri) {
                    WorkManager.getInstance(getApplication()).apply {
                        cancelUniqueWork("firefly_retry")
                        cancelUniqueWork("history_cleanup")
                        cancelUniqueWork("history_cleanup_now")
                    }
                }
                _dataTransferState.value = DataTransferState.Success(
                    "Backup restored (${summary.totalRecords} records)"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _dataTransferState.value = DataTransferState.Failure(
                    "Import failed: ${error.message ?: "Unknown error"}"
                )
            } finally {
                withContext(NonCancellable) {
                    configureHistoryCleanup(getApplication())
                    if (app.database.pendingTransactionDao().countActive() > 0) {
                        com.firesms.app.worker.scheduleRetry(getApplication())
                    }
                }
            }
        }
    }

    fun clearDataTransferState() {
        _dataTransferState.value = null
    }

    fun clearTestResult() {
        _testResult.value = null
    }
    fun saveSettings(url: String, token: String, syncNetworkPolicy: String? = null) {
        viewModelScope.launch {
            prefs.setFireflyUrl(url.trimEnd('/'))
            prefs.setFireflyToken(token)
            if (syncNetworkPolicy != null) {
                prefs.setSyncNetworkPolicy(syncNetworkPolicy)
            }
        }
    }

    fun testConnection(url: String, token: String) {
        viewModelScope.launch {
            _isTesting.value = true
            _testResult.value = null
            try {
                val api = FireflyApi(token)
                val success = api.testConnection(url.trimEnd('/'))
                api.close()
                _testResult.value = if (success) {
                    TestResult.Success
                } else {
                    TestResult.Failure("Invalid response from server")
                }
            } catch (e: Exception) {
                _testResult.value = TestResult.Failure(e.message ?: "Unknown error")
            } finally {
                _isTesting.value = false
            }
        }
    }
}
