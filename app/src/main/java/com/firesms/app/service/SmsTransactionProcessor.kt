package com.firesms.app.service

import android.content.Context
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.PreferencesManager
import com.firesms.app.data.local.canRetryFailedTransaction
import com.firesms.app.data.local.encode
import com.firesms.app.data.local.entity.PendingTransaction
import com.firesms.app.data.local.entity.SmsLog
import com.firesms.app.data.local.toStoredSnapshot
import com.firesms.app.data.remote.FireflyApi
import com.firesms.app.data.remote.FireflyError
import com.firesms.app.data.remote.FireflyUnavailable
import com.firesms.app.data.remote.TransactionRequest
import com.firesms.app.data.remote.buildCreateRequest
import com.firesms.app.domain.model.ParsedTransaction
import com.firesms.app.domain.parser.SmsParser
import com.firesms.app.domain.parser.TitleMappingResolver
import com.firesms.app.worker.scheduleRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Parses a logged SMS and submits it to Firefly III, keeping the SMS log status and the retry
 * queue in sync. Callers should hold [com.firesms.app.data.local.DataMaintenanceLock].
 */
class SmsTransactionProcessor(private val context: Context) {

    sealed interface Outcome {
        data object Unparsed : Outcome
        data object NotConfigured : Outcome
        data class Created(val fireflyId: String) : Outcome
        data class Queued(val message: String?) : Outcome
        data class Failed(val message: String?) : Outcome
    }

    private val db = AppDatabase.getInstance(context)
    private val smsLogDao = db.smsLogDao()
    private val pendingDao = db.pendingTransactionDao()
    private val prefs = PreferencesManager(context)
    private val parser = SmsParser(db.parserRuleDao())
    private val titleMappingResolver = TitleMappingResolver(db.titleMappingRuleDao())

    /** Re-read under DataMaintenanceLock so stale UI/queued retries cannot resend known successes. */
    suspend fun retryFailed(logId: Long, expectedSmsHash: String? = null): Outcome? {
        val log = smsLogDao.getById(logId) ?: return null
        if (!log.canRetryFailedTransaction) return null
        if (expectedSmsHash != null && log.smsHash != expectedSmsHash) return null

        pendingDao.deleteBySmsLogId(log.id)
        // Keep the failed status until an outcome is recorded. If background work is stopped,
        // the item remains retryable instead of getting stranded in "pending".
        return process(log)
    }

    suspend fun process(log: SmsLog): Outcome = try {
        submit(log)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never leave a log stuck in "pending" because of an unexpected error.
        val message = e.message ?: e.toString()
        markFailed(log.id, message)
        Outcome.Failed(message)
    }

    private suspend fun submit(log: SmsLog): Outcome {
        val parsed = parser.parse(log.sender, log.body, log.receivedAt)
            ?.let { applyTitleMapping(it) }
        if (parsed == null) {
            smsLogDao.updateStatus(log.id, "unparsed", processedAt = System.currentTimeMillis())
            return Outcome.Unparsed
        }

        // Persist the submitted transaction as an immutable comparison baseline and display cache.
        val createRequest = buildCreateRequest(parsed, log.smsHash)
        smsLogDao.initializeTransactionSnapshot(log.id, createRequest.toStoredSnapshot().encode())

        val baseUrl = prefs.fireflyUrl.first()
        val token = prefs.fireflyToken.first()
        if (baseUrl.isEmpty() || token.isEmpty()) {
            markFailed(log.id, "Firefly not configured")
            return Outcome.NotConfigured
        }

        val api = FireflyApi(token)
        return try {
            val fireflyId = api.createTransactionRaw(baseUrl, createRequest)
            smsLogDao.updateStatus(
                log.id, "success",
                processedAt = System.currentTimeMillis(),
                fireflyId = fireflyId
            )
            showTransactionCreatedNotification(context, log.id, parsed.description)
            Outcome.Created(fireflyId)
        } catch (e: FireflyError) {
            // 4xx: the request itself is wrong, so retrying will not help.
            markFailed(log.id, e.message)
            Outcome.Failed(e.message)
        } catch (e: FireflyUnavailable) {
            // 5xx or network: queue for retry.
            markFailed(log.id, e.message)
            pendingDao.deleteBySmsLogId(log.id)
            pendingDao.insert(
                PendingTransaction(
                    smsLogId = log.id,
                    fireflyPayload = Json.encodeToString(TransactionRequest.serializer(), createRequest),
                    nextRetryAt = System.currentTimeMillis() + 120_000
                )
            )
            scheduleRetry(context)
            Outcome.Queued(e.message)
        } finally {
            api.close()
        }
    }

    private suspend fun applyTitleMapping(transaction: ParsedTransaction): ParsedTransaction {
        val mapping = titleMappingResolver.resolve(transaction.description) ?: return transaction
        return transaction.copy(
            categoryName = mapping.categoryName.ifBlank { null },
            budgetName = mapping.budgetName.ifBlank { null }
        )
    }

    private suspend fun markFailed(logId: Long, message: String?) {
        smsLogDao.updateStatus(
            logId, "failed",
            processedAt = System.currentTimeMillis(),
            errorMessage = message
        )
    }
}
