package com.firesms.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.data.local.entity.SmsLog
import com.firesms.app.service.SmsTransactionProcessor
import com.firesms.app.service.SmsTransactionProcessor.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class UnparsedViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val smsLogDao = db.smsLogDao()
    private val pendingDao = db.pendingTransactionDao()
    private val processor = SmsTransactionProcessor(application)

    val unparsedLogs: StateFlow<List<SmsLog>> = smsLogDao.getByStatus("unparsed")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _reprocessResult = MutableStateFlow<String?>(null)
    val reprocessResult: StateFlow<String?> = _reprocessResult.asStateFlow()

    fun reprocess(log: SmsLog) {
        viewModelScope.launch {
            _reprocessResult.value = try {
                when (val outcome = DataMaintenanceLock.withLock { processor.process(log) }) {
                    Outcome.Unparsed -> "Still no matching rule"
                    Outcome.NotConfigured -> "Firefly not configured"
                    is Outcome.Created -> "Success! Transaction: ${outcome.fireflyId}"
                    is Outcome.Queued -> "Firefly unavailable, queued for retry"
                    is Outcome.Failed -> "Error: ${outcome.message}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }

    fun delete(log: SmsLog) {
        viewModelScope.launch {
            try {
                db.withTransaction {
                    pendingDao.deleteBySmsLogId(log.id)
                    smsLogDao.delete(log)
                }
                _reprocessResult.value = "Deleted unparsed message"
            } catch (e: Exception) {
                _reprocessResult.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun clearResult() {
        _reprocessResult.value = null
    }
}
