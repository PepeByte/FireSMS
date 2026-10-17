package com.firesms.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.firesms.app.worker.FailedRetryProgress
import com.firesms.app.worker.failedRetryProgress
import com.firesms.app.worker.FailedTransactionRetryWorker
import com.firesms.app.worker.scheduleFailedTransactionRetry
import com.firesms.app.data.local.StoredTransactionSnapshot
import com.firesms.app.data.local.decodeTransactionSnapshot
import com.firesms.app.data.local.entity.SmsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeTransactionItem(
    val log: SmsLog,
    val transaction: StoredTransactionSnapshot?
) {
    val isModified: Boolean get() = log.locallyModifiedAt != null
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val smsLogDao = db.smsLogDao()
    private val pendingDao = db.pendingTransactionDao()

    val logs: StateFlow<List<HomeTransactionItem>> = smsLogDao.getAllByDateDesc()
        .map { logs ->
            logs.map { log ->
                HomeTransactionItem(log, decodeTransactionSnapshot(log.transactionSnapshotJson))
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unparsedCount: StateFlow<Int> = smsLogDao.countByStatus("unparsed")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pendingCount: StateFlow<Int> = pendingDao.countPending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedRetryWork: StateFlow<FailedRetryProgress?> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow(FailedTransactionRetryWorker.WORK_NAME)
        .map(::failedRetryProgress)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isSchedulingRetry = MutableStateFlow(false)
    val isSchedulingRetry = _isSchedulingRetry.asStateFlow()

    private val _retryError = MutableStateFlow<String?>(null)
    val retryError = _retryError.asStateFlow()

    fun retryAllFailedTransactions() {
        if (_isSchedulingRetry.value || failedRetryWork.value != null) return
        _isSchedulingRetry.value = true
        viewModelScope.launch {
            try {
                scheduleFailedTransactionRetry(getApplication())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _retryError.value = "Could not schedule retries: ${error.message ?: "Unknown error"}"
            } finally {
                _isSchedulingRetry.value = false
            }
        }
    }

    fun clearRetryError() {
        _retryError.value = null
    }

    fun submitTestSms(sender: String, body: String) {
        val intent = android.content.Intent(
            getApplication(),
            com.firesms.app.service.SmsProcessingService::class.java
        ).apply {
            putExtra("sender", sender)
            putExtra("body", body)
            putExtra("receivedAt", System.currentTimeMillis())
        }
        getApplication<android.app.Application>().startForegroundService(intent)
    }

    fun retryFailedTransaction(log: SmsLog) {
        val intent = android.content.Intent(
            getApplication(),
            com.firesms.app.service.SmsProcessingService::class.java
        ).apply {
            putExtra("retryLogId", log.id)
        }
        getApplication<android.app.Application>().startForegroundService(intent)
    }

    fun deleteSmsLog(log: SmsLog) {
        viewModelScope.launch {
            try {
                db.withTransaction {
                    pendingDao.deleteBySmsLogId(log.id)
                    smsLogDao.delete(log)
                }
            } catch (_: Exception) { }
        }
    }
}
