package com.firesms.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.entity.SmsLog
import com.firesms.app.data.local.encode
import com.firesms.app.data.local.toStoredSnapshot
import com.firesms.app.data.remote.FireflyApi
import com.firesms.app.data.remote.FireflyError
import com.firesms.app.data.remote.FireflyUnavailable
import com.firesms.app.data.remote.buildCreateRequest
import com.firesms.app.data.remote.TransactionRequest
import com.firesms.app.domain.parser.SmsParser
import com.firesms.app.worker.RetryWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UnparsedViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val smsLogDao = db.smsLogDao()
    private val parserRuleDao = db.parserRuleDao()
    private val pendingDao = db.pendingTransactionDao()
    private val prefs = (application as FireSmsApp).preferencesManager
    private val parser = SmsParser(parserRuleDao)



    val unparsedLogs: StateFlow<List<SmsLog>> = smsLogDao.getByStatus("unparsed")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _reprocessResult = MutableStateFlow<String?>(null)
    val reprocessResult: StateFlow<String?> = _reprocessResult.asStateFlow()

    fun reprocess(log: SmsLog) {
        viewModelScope.launch {
            try {
                val parsed = parser.parse(log.sender, log.body, log.receivedAt)
                if (parsed == null) {
                    _reprocessResult.value = "Still no matching rule"
                    return@launch
                }

                val hash = sha256("${log.sender}|${log.body}|${log.receivedAt}")
                val createRequest = buildCreateRequest(parsed, hash)
                smsLogDao.initializeTransactionSnapshot(
                    log.id,
                    createRequest.toStoredSnapshot().encode()
                )

                val baseUrl = prefs.fireflyUrl.first()
                val token = prefs.fireflyToken.first()

                if (baseUrl.isEmpty() || token.isEmpty()) {
                    smsLogDao.updateStatus(
                        log.id, "failed",
                        processedAt = System.currentTimeMillis(),
                        errorMessage = "Firefly not configured"
                    )
                    _reprocessResult.value = "Firefly not configured"
                    return@launch
                }

                val api = FireflyApi(token)
                try {
                    val fireflyId = api.createTransaction(baseUrl, parsed, hash)
                    smsLogDao.updateStatus(
                        log.id, "success",
                        processedAt = System.currentTimeMillis(),
                        fireflyId = fireflyId
                    )
                    _reprocessResult.value = "Success! Transaction: $fireflyId"
                } catch (e: FireflyError) {
                    smsLogDao.updateStatus(
                        log.id, "failed",
                        processedAt = System.currentTimeMillis(),
                        errorMessage = e.message ?: e.toString()
                    )
                    _reprocessResult.value = "Error: ${e.message}"
                } catch (e: FireflyUnavailable) {
                    smsLogDao.updateStatus(
                        log.id, "failed",
                        processedAt = System.currentTimeMillis(),
                        errorMessage = e.message ?: e.toString()
                    )
                    val payload = Json.encodeToString(
                        TransactionRequest.serializer(),
                        createRequest
                    )
                    pendingDao.insert(
                        com.firesms.app.data.local.entity.PendingTransaction(
                            smsLogId = log.id,
                            fireflyPayload = payload,
                            nextRetryAt = System.currentTimeMillis() + 120_000
                        )
                    )
                    scheduleRetry()
                    _reprocessResult.value = "Firefly unavailable, queued for retry"
                } catch (e: Exception) {
                    smsLogDao.updateStatus(
                        log.id, "failed",
                        processedAt = System.currentTimeMillis(),
                        errorMessage = e.message ?: e.toString()
                    )
                    _reprocessResult.value = "Error: ${e.message}"
                } finally {
                    api.close()
                }
            } catch (e: Exception) {
                _reprocessResult.value = "Error: ${e.message}"
            }
        }
    }

    fun delete(log: SmsLog) {
        viewModelScope.launch {
            try {
                pendingDao.deleteBySmsLogId(log.id)
                smsLogDao.delete(log)
                _reprocessResult.value = "Deleted unparsed message"
            } catch (e: Exception) {
                _reprocessResult.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun clearResult() {
        _reprocessResult.value = null
    }

    private fun scheduleRetry() {
        viewModelScope.launch {
            com.firesms.app.worker.scheduleRetry(getApplication())
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
