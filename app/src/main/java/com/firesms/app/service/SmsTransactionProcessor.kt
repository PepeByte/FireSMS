package com.firesms.app.service

import android.content.Context
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.PreferencesManager
import com.firesms.app.data.local.StoredTransactionSnapshot
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
import java.security.MessageDigest

/** SMS log statuses. "queued" items have an active row in the automatic retry queue. */
object SmsStatus {
    const val PENDING = "pending"
    const val SUCCESS = "success"
    const val QUEUED = "queued"
    const val FAILED = "failed"
    const val UNPARSED = "unparsed"
}

/**
 * Parses a logged SMS and submits it to Firefly III, keeping the SMS log status and the retry
 * queue in sync. Callers should hold [com.firesms.app.data.local.DataMaintenanceLock].
 */
class SmsTransactionProcessor(private val context: Context) {

    sealed interface Outcome {
        data object Unparsed : Outcome
        data object NotConfigured : Outcome
        data class Created(val fireflyId: String, val description: String) : Outcome
        /** Firefly was unreachable; the transaction waits in the automatic retry queue. */
        data class Queued(val message: String?, val description: String) : Outcome
        data class Failed(val message: String?) : Outcome
    }

    /** Result of logging and processing a newly received (or sample) SMS. */
    sealed interface IngestResult {
        data object NoRuleForSender : IngestResult
        data object Duplicate : IngestResult
        data class Processed(val smsLogId: Long, val outcome: Outcome) : IngestResult
    }

    private val db = AppDatabase.getInstance(context)
    private val smsLogDao = db.smsLogDao()
    private val pendingDao = db.pendingTransactionDao()
    private val prefs = PreferencesManager(context)
    private val parser = SmsParser(db.parserRuleDao())
    private val titleMappingResolver = TitleMappingResolver(db.titleMappingRuleDao())

    /** Logs an incoming SMS once and processes it. Callers must hold DataMaintenanceLock. */
    suspend fun ingest(sender: String, body: String, receivedAt: Long): IngestResult {
        // Defensive sender match check (catches manual service starts and sample messages).
        if (!parser.senderMatchesAnyEnabledRule(sender)) return IngestResult.NoRuleForSender

        val hash = smsHash(sender, body, receivedAt)
        if (smsLogDao.findByHash(hash) != null) return IngestResult.Duplicate

        val log = SmsLog(
            smsHash = hash,
            sender = sender,
            body = body,
            receivedAt = receivedAt,
            status = SmsStatus.PENDING
        )
        val insertedId = smsLogDao.insert(log)
        if (insertedId <= 0L) return IngestResult.Duplicate
        return IngestResult.Processed(insertedId, process(log.copy(id = insertedId)))
    }

    /** Re-read under DataMaintenanceLock so stale UI/queued retries cannot resend known successes. */
    suspend fun retryFailed(logId: Long, expectedSmsHash: String? = null): Outcome? {
        val log = smsLogDao.getById(logId) ?: return null
        if (!log.canRetryFailedTransaction) return null
        if (expectedSmsHash != null && log.smsHash != expectedSmsHash) return null

        // Keep the current status and retry row until an outcome is recorded. If background work
        // is stopped, the item stays retryable (and queued items stay queued) instead of stranding.
        val outcome = process(log)
        // A queued outcome has already replaced the retry row; anything else ends automatic retries.
        if (outcome !is Outcome.Queued) pendingDao.deleteBySmsLogId(log.id)
        return outcome
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
            smsLogDao.updateStatus(log.id, SmsStatus.UNPARSED, processedAt = System.currentTimeMillis())
            return Outcome.Unparsed
        }

        // Persist the submitted transaction as an immutable comparison baseline and display cache.
        val createRequest = buildCreateRequest(parsed, log.smsHash)
        val snapshot = createRequest.toStoredSnapshot()
        smsLogDao.initializeTransactionSnapshot(log.id, snapshot.encode())

        val baseUrl = prefs.fireflyUrl.first()
        val token = prefs.fireflyToken.first()
        if (baseUrl.isEmpty() || token.isEmpty()) {
            markFailed(log.id, FIREFLY_NOT_CONFIGURED)
            return Outcome.NotConfigured
        }

        val api = FireflyApi(token)
        return try {
            val fireflyId = api.createTransactionIfMissing(baseUrl, createRequest)
            smsLogDao.updateStatus(
                log.id, SmsStatus.SUCCESS,
                processedAt = System.currentTimeMillis(),
                fireflyId = fireflyId
            )
            showTransactionCreatedNotification(context, log.id, snapshot)
            Outcome.Created(fireflyId, parsed.description)
        } catch (e: FireflyError) {
            // 4xx: the request itself is wrong, so retrying will not help.
            markFailed(log.id, e.message)
            Outcome.Failed(e.message)
        } catch (e: FireflyUnavailable) {
            // 5xx or network: queue for automatic retry.
            smsLogDao.updateStatus(
                log.id, SmsStatus.QUEUED,
                processedAt = System.currentTimeMillis(),
                errorMessage = e.message
            )
            pendingDao.deleteBySmsLogId(log.id)
            pendingDao.insert(
                PendingTransaction(
                    smsLogId = log.id,
                    fireflyPayload = Json.encodeToString(TransactionRequest.serializer(), createRequest),
                    nextRetryAt = System.currentTimeMillis() + 120_000
                )
            )
            scheduleRetry(context)
            Outcome.Queued(e.message, parsed.description)
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
            logId, SmsStatus.FAILED,
            processedAt = System.currentTimeMillis(),
            errorMessage = message
        )
    }

    companion object {
        const val FIREFLY_NOT_CONFIGURED = "Firefly not configured"

        fun smsHash(sender: String, body: String, receivedAt: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest("$sender|$body|$receivedAt".toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

/** The display description of a stored snapshot, used by notifications and snackbars. */
internal fun StoredTransactionSnapshot.displayDescription(): String? =
    groupTitle?.takeIf { it.isNotBlank() }
        ?: transactions.firstOrNull()?.description?.takeIf { it.isNotBlank() }
