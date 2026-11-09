package com.firesms.app.ui.viewmodels

import android.app.Application
import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.service.SmsStatus
import com.firesms.app.service.SmsTransactionProcessor
import com.firesms.app.service.SmsTransactionProcessor.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class UnparsedItem(
    val id: Long,
    val sender: String,
    val body: String,
    val received: String
)

class UnparsedViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val smsLogDao = db.smsLogDao()
    private val pendingDao = db.pendingTransactionDao()
    private val processor by lazy { SmsTransactionProcessor(application) }

    private val is24Hour = MutableStateFlow(DateFormat.is24HourFormat(application))

    /** Null while loading, so the empty state never flashes on open. */
    val unparsedLogs: StateFlow<List<UnparsedItem>?> = combine(
        smsLogDao.getByStatus(SmsStatus.UNPARSED),
        is24Hour
    ) { logs, is24 ->
        val locale = Locale.getDefault()
        val zone = ZoneId.systemDefault()
        val formatter = runCatching {
            DateTimeFormatter.ofPattern(
                DateFormat.getBestDateTimePattern(locale, if (is24) "MMMdHm" else "MMMdhma"),
                locale
            )
        }.getOrElse { DateTimeFormatter.ofPattern(if (is24) "MMM d, HH:mm" else "MMM d, h:mm a", locale) }
        logs.map { log ->
            UnparsedItem(
                id = log.id,
                sender = log.sender,
                body = log.body,
                received = Instant.ofEpochMilli(log.receivedAt).atZone(zone).format(formatter)
            )
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _runningIds = MutableStateFlow<Set<Long>>(emptySet())
    val runningIds: StateFlow<Set<Long>> = _runningIds.asStateFlow()

    private val _isRetryingAll = MutableStateFlow(false)
    val isRetryingAll: StateFlow<Boolean> = _isRetryingAll.asStateFlow()

    private val _events = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = _events.receiveAsFlow()

    fun refreshFormatting() {
        is24Hour.value = DateFormat.is24HourFormat(getApplication())
    }

    fun reprocess(id: Long) {
        if (_isRetryingAll.value || id in _runningIds.value) return
        _runningIds.update { it + id }
        viewModelScope.launch {
            val message = try {
                unparsedOutcomeMessage(processOne(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiMessage(e.message?.takeIf { it.isNotBlank() } ?: GENERIC_ERROR)
            } finally {
                _runningIds.update { it - id }
            }
            _events.send(message)
        }
    }

    /** Runs every unparsed message through the current rules again, one at a time. */
    fun reprocessAll() {
        if (_isRetryingAll.value || _runningIds.value.isNotEmpty()) return
        val ids = unparsedLogs.value.orEmpty().map { it.id }
        if (ids.isEmpty()) return
        _isRetryingAll.value = true
        viewModelScope.launch {
            val tally = TryAllTally()
            val message = try {
                for (id in ids) {
                    _runningIds.update { it + id }
                    val outcome = try {
                        processOne(id)
                    } finally {
                        _runningIds.update { it - id }
                    }
                    if (outcome == Outcome.NotConfigured) {
                        tally.notConfigured = true
                        break
                    }
                    tally.add(outcome)
                }
                tryAllMessage(tally)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiMessage(e.message?.takeIf { it.isNotBlank() } ?: GENERIC_ERROR)
            } finally {
                _isRetryingAll.value = false
            }
            _events.send(message)
        }
    }

    /** Null when the message was already handled elsewhere. */
    private suspend fun processOne(id: Long): Outcome? =
        // Finish even if the screen closes, so a message is never left "Processing".
        withContext(NonCancellable + Dispatchers.IO) {
            DataMaintenanceLock.withLock {
                val log = smsLogDao.getById(id)?.takeIf { it.status == SmsStatus.UNPARSED }
                log?.let { processor.process(it) }
            }
        }

    fun delete(id: Long) {
        viewModelScope.launch {
            val message = try {
                db.withTransaction {
                    pendingDao.deleteBySmsLogId(id)
                    smsLogDao.deleteById(id)
                }
                UiMessage("Message deleted")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiMessage("Couldn't delete: ${e.message?.takeIf { it.isNotBlank() } ?: "unknown error"}")
            }
            _events.send(message)
        }
    }

    private companion object {
        const val GENERIC_ERROR = "Something went wrong. Please try again."
    }
}

internal class TryAllTally(
    var created: Int = 0,
    var unparsed: Int = 0,
    var queued: Int = 0,
    var failed: Int = 0,
    var notConfigured: Boolean = false
) {
    fun add(outcome: Outcome?) {
        when (outcome) {
            is Outcome.Created -> created++
            Outcome.Unparsed -> unparsed++
            is Outcome.Queued -> queued++
            is Outcome.Failed -> failed++
            Outcome.NotConfigured -> notConfigured = true
            null -> Unit
        }
    }
}

private fun quote(description: String) = description.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: "the transaction"

internal fun unparsedOutcomeMessage(outcome: Outcome?): UiMessage = when (outcome) {
    null -> UiMessage("This message was already handled")
    Outcome.Unparsed -> UiMessage("Still no rule matches this message")
    Outcome.NotConfigured -> UiMessage("Firefly isn't set up", UiMessageAction.OPEN_SETTINGS)
    is Outcome.Created -> UiMessage("Added ${quote(outcome.description)} to Firefly")
    is Outcome.Queued ->
        UiMessage("Firefly is unreachable. ${quote(outcome.description).replaceFirstChar { it.uppercase() }} is waiting to sync — will retry automatically")
    is Outcome.Failed -> UiMessage(
        outcome.message?.takeIf { it.isNotBlank() }?.let { "Couldn't add to Firefly: $it" }
            ?: "Couldn't add to Firefly. Check the item on the Activity screen."
    )
}

internal fun tryAllMessage(tally: TryAllTally): UiMessage {
    if (tally.notConfigured && tally.created + tally.queued + tally.failed == 0) {
        return UiMessage("Firefly isn't set up", UiMessageAction.OPEN_SETTINGS)
    }
    val parts = listOfNotNull(
        tally.created.takeIf { it > 0 }?.let { "$it added to Firefly" },
        tally.queued.takeIf { it > 0 }?.let { "$it waiting to sync" },
        tally.failed.takeIf { it > 0 }?.let { "$it couldn't be added" },
        tally.unparsed.takeIf { it > 0 }?.let { "$it still ${if (it == 1) "needs" else "need"} a rule" }
    )
    val summary = parts.joinToString(" · ").ifEmpty { "Nothing to try again" }
    return if (tally.notConfigured) {
        UiMessage("$summary · stopped: Firefly isn't set up", UiMessageAction.OPEN_SETTINGS)
    } else {
        UiMessage(summary)
    }
}
