package com.firesms.app.ui.viewmodels

import android.app.Application
import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.ActivityCounts
import com.firesms.app.data.local.ActivitySummary
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.data.local.StoredTransactionSnapshot
import com.firesms.app.data.local.StoredTransactionSplit
import com.firesms.app.data.local.activitySearchPattern
import com.firesms.app.data.local.canRetryFailedTransaction
import com.firesms.app.data.local.decodeTransactionSnapshot
import com.firesms.app.data.local.entity.SmsLog
import com.firesms.app.service.SmsStatus
import com.firesms.app.service.SmsTransactionProcessor
import com.firesms.app.service.SmsTransactionProcessor.IngestResult
import com.firesms.app.service.SmsTransactionProcessor.Outcome
import com.firesms.app.ui.components.formatDisplayAmount
import com.firesms.app.worker.FailedRetryProgress
import com.firesms.app.worker.FailedTransactionRetryWorker
import com.firesms.app.worker.failedRetryProgress
import com.firesms.app.worker.scheduleFailedTransactionRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// ─── UI models ─────────────────────────────────────────────────

enum class ActivityFilter(val label: String) {
    ALL("All"),
    NEEDS_ATTENTION("Attention"),
    WAITING("Waiting to sync"),
    UNMODIFIED("Unmodified"),
    MODIFIED("Modified")
}

enum class AmountDirection { OUT, IN, NEUTRAL }

/** A list row with every string already formatted, so the card only lays out text. */
@Immutable
data class ActivityRow(
    val id: Long,
    val headline: String,
    val amount: String?,
    val direction: AmountDirection,
    val accountPath: String?,
    val metadata: String,
    val preview: String?,
    val errorMessage: String?,
    val status: String,
    val isModified: Boolean
)

sealed interface ActivityListItem {
    val key: String
    val contentType: String

    @Immutable
    data class Header(val label: String, val date: LocalDate) : ActivityListItem {
        override val key: String get() = "header-$date"
        override val contentType: String get() = "header"
    }

    @Immutable
    data class Entry(val row: ActivityRow, val date: LocalDate) : ActivityListItem {
        override val key: String get() = "log-${row.id}"
        override val contentType: String get() = "entry"
    }
}

@Immutable
data class ActivitySplitDetail(
    val split: StoredTransactionSplit,
    val amount: String?,
    val direction: AmountDirection,
    val date: String?
)

/** The full record for the detail sheet, loaded only when a row is opened. */
@Immutable
data class ActivityDetail(
    val log: SmsLog,
    val transaction: StoredTransactionSnapshot?,
    val headline: String,
    val amount: String?,
    val direction: AmountDirection,
    val splits: List<ActivitySplitDetail>,
    val received: String,
    val processed: String?,
    val isModified: Boolean
) {
    val canRetry: Boolean get() = log.canRetryFailedTransaction
}

enum class UiMessageAction(val label: String) {
    OPEN_SETTINGS("Settings"),
    OPEN_UNPARSED("Review"),
    OPEN_RULES("Rules"),
    SHOW_ATTENTION("Show")
}

/** A one-shot snackbar message. */
data class UiMessage(val text: String, val action: UiMessageAction? = null)

/** One line of the Activity health panel, most urgent first. */
sealed interface HealthLine {
    data object PermissionOff : HealthLine
    data class Unparsed(val count: Int) : HealthLine
    data class Failed(val count: Int) : HealthLine
    data class Queued(val count: Int) : HealthLine
    data object AllGood : HealthLine
}

// ─── View model ────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val smsLogDao = db.smsLogDao()
    private val pendingDao = db.pendingTransactionDao()
    private val processor by lazy { SmsTransactionProcessor(application) }

    private val _events = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = _events.receiveAsFlow()

    val filter: StateFlow<ActivityFilter> = savedState.getStateFlow(KEY_FILTER, ActivityFilter.ALL)
    val query: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")
    val selectedLogId: StateFlow<Long?> = savedState.getStateFlow<Long?>(KEY_DETAIL, null)

    private val formatKey = MutableStateFlow(currentFormatKey())
    @Volatile private var cachedFormatters: ActivityFormatters? = null

    /** Null until the first count query answers, so the panel never flashes "all good". */
    val counts: StateFlow<ActivityCounts?> = smsLogDao.observeCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Paging transforms (decoding and formatting) run once, on Default, before caching.
    private val pagingScope = CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default)

    val activity: Flow<PagingData<ActivityListItem>> = combine(
        filter,
        query.debounce { if (it.isBlank()) 0L else 250L }.map { it.trim() }.distinctUntilChanged(),
        formatKey
    ) { filter, query, key -> Triple(filter, query, key) }
        .flatMapLatest { (filter, query, key) ->
            val formatters = formattersFor(key)
            val pattern = activitySearchPattern(query)
            Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
                when (filter) {
                    ActivityFilter.ALL -> smsLogDao.pageAll(pattern)
                    ActivityFilter.NEEDS_ATTENTION ->
                        smsLogDao.pageByStatus(listOf(SmsStatus.FAILED, SmsStatus.UNPARSED), pattern)
                    ActivityFilter.WAITING ->
                        smsLogDao.pageByStatus(listOf(SmsStatus.QUEUED, SmsStatus.PENDING), pattern)
                    ActivityFilter.UNMODIFIED -> smsLogDao.pageUnmodified(pattern)
                    ActivityFilter.MODIFIED -> smsLogDao.pageModified(pattern)
                }
            }.flow.map { paging ->
                paging
                    .map { summary ->
                        ActivityListItem.Entry(
                            row = buildActivityRow(summary, formatters.time, key.zone),
                            date = summary.localDate(key.zone)
                        )
                    }
                    .insertSeparators { before: ActivityListItem.Entry?, after: ActivityListItem.Entry? ->
                        when {
                            after == null -> null
                            before == null || before.date != after.date -> ActivityListItem.Header(
                                label = dateHeaderLabel(
                                    after.date, key.today, formatters.headerSameYear, formatters.headerOtherYear
                                ),
                                date = after.date
                            )
                            else -> null
                        }
                    }
            }
        }
        .cachedIn(pagingScope)

    val detail: StateFlow<ActivityDetail?> = combine(selectedLogId, formatKey) { id, key -> id to key }
        .flatMapLatest { (id, key) ->
            if (id == null) {
                flowOf(null)
            } else {
                smsLogDao.observeById(id).map { log -> log?.let { buildActivityDetail(it, formattersFor(key), key.zone) } }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val failedRetryWork: StateFlow<FailedRetryProgress?> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow(FailedTransactionRetryWorker.WORK_NAME)
        .map(::failedRetryProgress)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isSchedulingRetry = MutableStateFlow(false)
    val isSchedulingRetry = _isSchedulingRetry.asStateFlow()

    private val _retryingLogId = MutableStateFlow<Long?>(null)
    val retryingLogId = _retryingLogId.asStateFlow()

    private val _isProcessingSample = MutableStateFlow(false)
    val isProcessingSample = _isProcessingSample.asStateFlow()

    fun setFilter(filter: ActivityFilter) {
        savedState[KEY_FILTER] = filter
    }

    fun setQuery(query: String) {
        savedState[KEY_QUERY] = query
    }

    fun openDetail(id: Long) {
        savedState[KEY_DETAIL] = id
    }

    fun closeDetail() {
        savedState[KEY_DETAIL] = null
    }

    /** Picks up 12/24-hour, locale and day changes when the screen comes back to the foreground. */
    fun refreshFormatting() {
        formatKey.value = currentFormatKey()
    }

    fun retryAllFailedTransactions() {
        if (_isSchedulingRetry.value || failedRetryWork.value != null) return
        _isSchedulingRetry.value = true
        viewModelScope.launch {
            try {
                scheduleFailedTransactionRetry(getApplication())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _events.send(UiMessage("Could not schedule retries: ${error.message ?: "Unknown error"}"))
            } finally {
                _isSchedulingRetry.value = false
            }
        }
    }

    /** Processes a sample SMS exactly like a received one and reports what happened. */
    fun processSampleSms(sender: String, body: String) {
        if (_isProcessingSample.value || sender.isBlank() || body.isBlank()) return
        _isProcessingSample.value = true
        viewModelScope.launch {
            val message = try {
                // Finish even if the screen goes away, so the log is never left "Processing".
                val result = withContext(NonCancellable + Dispatchers.IO) {
                    DataMaintenanceLock.withLock {
                        processor.ingest(sender.trim(), body.trim(), System.currentTimeMillis())
                    }
                }
                sampleSmsMessage(result)
            } catch (error: Exception) {
                UiMessage("Failed: ${error.message ?: "Something went wrong"}")
            } finally {
                _isProcessingSample.value = false
            }
            _events.send(message)
        }
    }

    fun retryTransaction(id: Long) {
        if (_retryingLogId.value != null) return
        _retryingLogId.value = id
        viewModelScope.launch {
            val message = try {
                val outcome = withContext(NonCancellable + Dispatchers.IO) {
                    DataMaintenanceLock.withLock { processor.retryFailed(id) }
                }
                retryMessage(outcome)
            } catch (error: Exception) {
                UiMessage("Retry failed: ${error.message ?: "Something went wrong"}")
            } finally {
                _retryingLogId.value = null
            }
            _events.send(message)
        }
    }

    fun deleteSmsLog(id: Long) {
        if (selectedLogId.value == id) closeDetail()
        viewModelScope.launch {
            val message = try {
                db.withTransaction {
                    pendingDao.deleteBySmsLogId(id)
                    smsLogDao.deleteById(id)
                }
                UiMessage("Deleted from FireSMS")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                UiMessage("Delete failed: ${error.message ?: "Something went wrong"}")
            }
            _events.send(message)
        }
    }

    private fun currentFormatKey() = FormatKey(
        locale = Locale.getDefault(),
        is24Hour = DateFormat.is24HourFormat(getApplication()),
        zone = ZoneId.systemDefault(),
        today = LocalDate.now()
    )

    private fun formattersFor(key: FormatKey): ActivityFormatters =
        cachedFormatters?.takeIf { it.key == key } ?: ActivityFormatters.create(key).also { cachedFormatters = it }

    private companion object {
        const val KEY_FILTER = "activity_filter"
        const val KEY_QUERY = "activity_query"
        const val KEY_DETAIL = "activity_detail_id"
    }
}

// ─── Formatting (pure, unit tested) ────────────────────────────

internal data class FormatKey(val locale: Locale, val is24Hour: Boolean, val zone: ZoneId, val today: LocalDate)

internal class ActivityFormatters(
    val key: FormatKey,
    val time: DateTimeFormatter,
    val dateTime: DateTimeFormatter,
    val headerSameYear: DateTimeFormatter,
    val headerOtherYear: DateTimeFormatter
) {
    companion object {
        fun create(key: FormatKey): ActivityFormatters {
            val locale = key.locale
            fun best(skeleton: String, fallback: () -> DateTimeFormatter): DateTimeFormatter = runCatching {
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            }.getOrElse { fallback() }
            return ActivityFormatters(
                key = key,
                time = best(if (key.is24Hour) "Hm" else "hma") {
                    DateTimeFormatter.ofPattern(if (key.is24Hour) "HH:mm" else "h:mm a", locale)
                },
                dateTime = best(if (key.is24Hour) "yMMMdHm" else "yMMMdhma") {
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
                },
                headerSameYear = best("EEEEMMMMd") { DateTimeFormatter.ofPattern("EEEE, MMMM d", locale) },
                headerOtherYear = best("EEEEMMMMdy") { DateTimeFormatter.ofPattern("EEEE, MMMM d, y", locale) }
            )
        }
    }
}

internal fun dateHeaderLabel(
    date: LocalDate,
    today: LocalDate,
    sameYear: DateTimeFormatter,
    otherYear: DateTimeFormatter
): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(if (date.year == today.year) sameYear else otherYear)
}

internal fun amountDirection(type: String?): AmountDirection = when (type?.trim()?.lowercase()) {
    "withdrawal" -> AmountDirection.OUT
    "deposit" -> AmountDirection.IN
    else -> AmountDirection.NEUTRAL
}

/** Withdrawals read "−₹12.00", deposits "+₹12.00", transfers carry no sign. */
internal fun signedAmount(formatted: String, direction: AmountDirection): String = when (direction) {
    AmountDirection.OUT -> "−$formatted"
    AmountDirection.IN -> "+$formatted"
    AmountDirection.NEUTRAL -> formatted
}

internal fun StoredTransactionSplit.formattedAmount(value: String): String {
    val amount = formatDisplayAmount(value)
    return when {
        !currencySymbol.isNullOrBlank() -> "$currencySymbol$amount"
        !currencyCode.isNullOrBlank() -> "$currencyCode $amount"
        else -> amount
    }
}

internal fun StoredTransactionSnapshot.formattedTotal(): String? {
    val first = transactions.firstOrNull() ?: return null
    if (transactions.size == 1) return first.amount.takeIf { it.isNotBlank() }?.let { first.formattedAmount(it) }
    val total = transactions.fold(BigDecimal.ZERO) { sum, split ->
        sum + (split.amount.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO)
    }.toPlainString()
    return first.formattedAmount(total)
}

private fun StoredTransactionSnapshot.headline(): String? =
    groupTitle?.takeIf { it.isNotBlank() } ?: transactions.firstOrNull()?.description?.takeIf { it.isNotBlank() }

private fun ActivitySummary.localDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()

internal fun buildActivityRow(summary: ActivitySummary, time: DateTimeFormatter, zone: ZoneId): ActivityRow {
    val snapshot = decodeTransactionSnapshot(summary.transactionSnapshotJson)
    val first = snapshot?.transactions?.firstOrNull()
    val direction = amountDirection(first?.type)
    val accountPath = first?.let {
        listOfNotNull(
            it.sourceName?.takeIf(String::isNotBlank),
            it.destinationName?.takeIf(String::isNotBlank)
        ).joinToString(" → ").takeIf(String::isNotBlank)
    }
    val metadata = listOfNotNull(
        first?.type?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
        Instant.ofEpochMilli(summary.receivedAt).atZone(zone).format(time),
        first?.categoryName?.takeIf { it.isNotBlank() },
        snapshot?.transactions?.size?.takeIf { it > 1 }?.let { "$it splits" }
    ).joinToString(" · ")
    return ActivityRow(
        id = summary.id,
        headline = snapshot?.headline() ?: summary.sender,
        amount = snapshot?.formattedTotal()?.let { signedAmount(it, direction) },
        direction = direction,
        accountPath = accountPath,
        metadata = metadata,
        preview = if (first == null) summary.bodyPreview.replace('\n', ' ') else null,
        errorMessage = summary.errorMessage?.takeIf { it.isNotBlank() },
        status = summary.status,
        isModified = summary.locallyModifiedAt != null
    )
}

internal fun buildActivityDetail(log: SmsLog, formatters: ActivityFormatters, zone: ZoneId): ActivityDetail {
    val snapshot = decodeTransactionSnapshot(log.transactionSnapshotJson)
    val direction = amountDirection(snapshot?.transactions?.firstOrNull()?.type)
    fun format(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(formatters.dateTime)
    return ActivityDetail(
        log = log,
        transaction = snapshot,
        headline = snapshot?.headline() ?: log.sender,
        amount = snapshot?.formattedTotal()?.let { signedAmount(it, direction) },
        direction = direction,
        splits = snapshot?.transactions.orEmpty().map { split ->
            val splitDirection = amountDirection(split.type)
            ActivitySplitDetail(
                split = split,
                amount = split.amount.takeIf { it.isNotBlank() }
                    ?.let { signedAmount(split.formattedAmount(it), splitDirection) },
                direction = splitDirection,
                date = formatSplitDate(split.date, formatters.dateTime, zone)
            )
        },
        received = format(log.receivedAt),
        processed = log.processedAt?.let(::format),
        isModified = log.locallyModifiedAt != null
    )
}

/** Firefly dates arrive as ISO-8601 with an offset; FireSMS stores "yyyy-MM-dd HH:mm:ss". */
internal fun formatSplitDate(raw: String, formatter: DateTimeFormatter, zone: ZoneId): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val parsed = runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value.replace(' ', 'T').take(19)).atZone(zone) }.getOrNull()
    return parsed?.format(formatter) ?: value
}

// ─── Messages (pure, unit tested) ──────────────────────────────

internal fun healthLines(hasSmsPermission: Boolean, counts: ActivityCounts): List<HealthLine> = buildList {
    if (!hasSmsPermission) add(HealthLine.PermissionOff)
    if (counts.unparsed > 0) add(HealthLine.Unparsed(counts.unparsed))
    if (counts.failed > 0) add(HealthLine.Failed(counts.failed))
    if (counts.queued > 0) add(HealthLine.Queued(counts.queued))
    if (isEmpty()) add(HealthLine.AllGood)
}

internal fun healthLineTitle(line: HealthLine): String = when (line) {
    HealthLine.PermissionOff -> "SMS access is off"
    is HealthLine.Unparsed ->
        "${line.count} ${if (line.count == 1) "message needs" else "messages need"} a rule"
    is HealthLine.Failed ->
        "${line.count} ${if (line.count == 1) "transaction" else "transactions"} couldn't sync"
    is HealthLine.Queued -> "${line.count} waiting to sync — will retry automatically"
    HealthLine.AllGood -> "Everything is up to date"
}

internal fun healthLineMessage(line: HealthLine): String = when (line) {
    HealthLine.PermissionOff -> "Tap to allow SMS access so FireSMS can receive bank messages."
    is HealthLine.Unparsed -> "Teach FireSMS how to read these messages."
    is HealthLine.Failed -> "Review the errors, then retry."
    is HealthLine.Queued -> "Firefly couldn't be reached. FireSMS keeps trying in the background."
    HealthLine.AllGood -> "FireSMS is listening for matching bank messages."
}

private fun quoted(description: String?) = description?.takeIf { it.isNotBlank() }?.let { "“$it”" }

internal fun sampleSmsMessage(result: IngestResult): UiMessage = when (result) {
    IngestResult.NoRuleForSender -> UiMessage("No rule matches this sender", UiMessageAction.OPEN_RULES)
    IngestResult.Duplicate -> UiMessage("Duplicate: this SMS was already processed")
    is IngestResult.Processed -> when (val outcome = result.outcome) {
        is Outcome.Created -> UiMessage("Synced ${quoted(outcome.description) ?: "transaction"} to Firefly")
        is Outcome.Queued ->
            UiMessage("Firefly is unreachable. Waiting to sync — will retry automatically")
        Outcome.Unparsed ->
            UiMessage("The sender matches, but no rule could read this message", UiMessageAction.OPEN_UNPARSED)
        Outcome.NotConfigured -> UiMessage("Firefly isn't set up", UiMessageAction.OPEN_SETTINGS)
        is Outcome.Failed -> UiMessage("Failed: ${outcome.message?.takeIf { it.isNotBlank() } ?: "Firefly rejected the transaction"}")
    }
}

internal fun retryMessage(outcome: Outcome?): UiMessage = when (outcome) {
    null -> UiMessage("Nothing to retry: this item has already been handled")
    is Outcome.Created -> UiMessage("Synced ${quoted(outcome.description) ?: "transaction"} to Firefly")
    is Outcome.Queued -> UiMessage("Firefly is still unreachable. Waiting to sync — will retry automatically")
    Outcome.Unparsed -> UiMessage("No rule can read this message any more", UiMessageAction.OPEN_UNPARSED)
    Outcome.NotConfigured -> UiMessage("Firefly isn't set up", UiMessageAction.OPEN_SETTINGS)
    is Outcome.Failed ->
        UiMessage("Retry failed: ${outcome.message?.takeIf { it.isNotBlank() } ?: "Firefly rejected the transaction"}")
}
