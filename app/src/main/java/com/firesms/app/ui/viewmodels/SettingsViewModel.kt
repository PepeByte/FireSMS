package com.firesms.app.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.work.WorkManager
import androidx.work.await
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import com.firesms.app.data.backup.BackupRepository
import com.firesms.app.data.local.HistoryRetention
import com.firesms.app.data.remote.FireflyApi
import com.firesms.app.data.remote.networkErrorMessage
import com.firesms.app.data.update.UpdateSnapshot
import com.firesms.app.worker.configureHistoryCleanup
import com.firesms.app.worker.FailedTransactionRetryWorker
import com.firesms.app.worker.updateFailedRetryNetworkPolicy
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

    val updateSnapshot = app.updateRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UpdateSnapshot())

    sealed interface UpdateCheckState {
        data object Checking : UpdateCheckState
        data object UpToDate : UpdateCheckState
        data class Failure(val message: String) : UpdateCheckState
    }

    private val _updateCheckState = MutableStateFlow<UpdateCheckState?>(null)
    val updateCheckState = _updateCheckState.asStateFlow()
    private val _updatePreferenceError = MutableStateFlow<String?>(null)
    val updatePreferenceError = _updatePreferenceError.asStateFlow()

    fun checkForUpdates() {
        if (_updateCheckState.value == UpdateCheckState.Checking) return
        _updatePreferenceError.value = null
        _updateCheckState.value = UpdateCheckState.Checking
        viewModelScope.launch {
            try {
                val release = app.updateRepository.checkForUpdates()
                _updateCheckState.value = if (release == null) UpdateCheckState.UpToDate else null
            } catch (error: CancellationException) {
                _updateCheckState.value = null
                throw error
            } catch (error: Exception) {
                _updateCheckState.value = UpdateCheckState.Failure(
                    error.message ?: "Couldn't check for updates. Try again later."
                )
            }
        }
    }

    fun ignoreUpdate(version: String) {
        changeIgnoredUpdate { app.updateRepository.ignoreUpdate(version) }
    }

    fun clearIgnoredUpdate() {
        changeIgnoredUpdate { app.updateRepository.clearIgnoredUpdate() }
    }

    private fun changeIgnoredUpdate(action: suspend () -> Unit) {
        viewModelScope.launch {
            _updatePreferenceError.value = null
            try {
                action()
                // Leave an in-flight check's progress alone; otherwise clear stale status/error text.
                if (_updateCheckState.value != UpdateCheckState.Checking) {
                    _updateCheckState.value = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _updatePreferenceError.value = "Couldn't save the ignored update preference. Try again."
            }
        }
    }

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
            updateFailedRetryNetworkPolicy(getApplication())
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
        if (_dataTransferState.value is DataTransferState.Working) return
        viewModelScope.launch {
            _dataTransferState.value = DataTransferState.Working("Exporting backup…")
            try {
                val summary = backupRepository.exportTo(uri)
                _dataTransferState.value = DataTransferState.Success(
                    "Backup exported (${summary.totalRecords} records)"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _dataTransferState.value = DataTransferState.Failure(
                    "Export failed: ${error.message ?: "Unknown error"}"
                )
            }
        }
    }

    fun importData(uri: Uri) {
        if (_dataTransferState.value is DataTransferState.Working) return
        viewModelScope.launch {
            _dataTransferState.value = DataTransferState.Working("Restoring backup…")
            try {
                val summary = backupRepository.importFrom(uri) {
                    WorkManager.getInstance(getApplication()).apply {
                        cancelUniqueWork("firefly_retry").await()
                        cancelUniqueWork(FailedTransactionRetryWorker.WORK_NAME).await()
                        cancelUniqueWork("history_cleanup").await()
                        cancelUniqueWork("history_cleanup_now").await()
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
                updateFailedRetryNetworkPolicy(getApplication())
            }
        }
    }

    fun testConnection(url: String, token: String) {
        viewModelScope.launch {
            _isTesting.value = true
            _testResult.value = null
            val api = FireflyApi(token)
            try {
                val success = api.testConnection(url.trimEnd('/'))
                _testResult.value = if (success) {
                    TestResult.Success
                } else {
                    TestResult.Failure("Server rejected the request. Check the URL and access token.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _testResult.value = TestResult.Failure(networkErrorMessage(e))
            } finally {
                _isTesting.value = false
            }
        }
    }
}
