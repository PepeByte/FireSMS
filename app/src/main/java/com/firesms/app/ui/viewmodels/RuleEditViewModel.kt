package com.firesms.app.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.data.remote.AccountResource
import com.firesms.app.data.remote.FireflyApi
import com.firesms.app.domain.parser.RuleDiagnosis
import com.firesms.app.domain.parser.SmsParser
import com.firesms.app.domain.rules.ImportedRuleFields
import com.firesms.app.domain.rules.RuleAccounts
import com.firesms.app.domain.rules.RuleJsonImportResult
import com.firesms.app.domain.rules.RuleJsonImporter
import com.firesms.app.domain.rules.RuleValidator
import com.firesms.app.domain.rules.SenderPatterns
import com.firesms.app.domain.rules.withLegacyDepositAccountResolved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The editor fields a save error can point at. */
enum class RuleField {
    ID, NAME, SENDER_PATTERN, BODY_PATTERN, AMOUNT_TEMPLATE, DESCRIPTION_SOURCE, DESCRIPTION_VALUE,
    TYPE, PRIORITY, OWN_ACCOUNT, OTHER_ACCOUNT, DATE_PATTERN, DATE_FORMAT, REMARKS_PATTERN, REMARKS_TEMPLATE
}

/** A problem that stops the rule from saving, shown on [field] when there is one. */
data class RuleFieldProblem(val field: RuleField?, val message: String)

/** The live result of running the rule against the sample message. */
sealed interface RulePreview {
    /** No sample message to test against. */
    data object NoSample : RulePreview

    /** A pattern the test needs is still blank. */
    data class Incomplete(val message: String) : RulePreview

    /** The fields changed and the result is being recomputed. */
    data object Checking : RulePreview

    /** [problem] is a validation issue that would stop the rule from saving, if any. */
    data class Result(val diagnosis: RuleDiagnosis, val problem: String?) : RulePreview
}

private const val DEFAULT_PRIORITY = 10
private const val PREVIEW_DEBOUNCE_MS = 300L
private val NON_SLUG_CHARS = Regex("[^a-z0-9]+")

private val VALIDATOR_FIELDS = mapOf(
    "bodyPattern" to RuleField.BODY_PATTERN,
    "amountTemplate" to RuleField.AMOUNT_TEMPLATE,
    "descriptionSource" to RuleField.DESCRIPTION_SOURCE,
    "descriptionValue" to RuleField.DESCRIPTION_VALUE,
    "type" to RuleField.TYPE,
    "datePattern" to RuleField.DATE_PATTERN,
    "dateFormat" to RuleField.DATE_FORMAT,
    "remarksPattern" to RuleField.REMARKS_PATTERN,
    "remarksTemplate" to RuleField.REMARKS_TEMPLATE
)

data class RuleEditState(
    val id: String = "",
    val name: String = "",
    val senderPattern: String = "",
    val bodyPattern: String = "",
    val amountTemplate: String = "{{amount}}",
    val descriptionSource: String = "template",
    val descriptionValue: String = "",
    /** The user's own Firefly account: where money leaves from, or arrives into for deposits. */
    val ownAccountName: String = "",
    val ownAccountId: String? = null,
    /** Fallback when no own account is picked: a word in the SMS that names the account. */
    val accountKeyword: String = "",
    /** The other party: merchant, payer, or the receiving account of a transfer. */
    val otherAccountName: String = "",
    val otherAccountId: String? = null,
    val type: String = "withdrawal",
    /** Kept as typed so the field can be cleared while entering a new number. */
    val priorityText: String = DEFAULT_PRIORITY.toString(),
    val datePattern: String = "",
    val dateFormat: String = "",
    val remarksPattern: String = "",
    val remarksTemplate: String = "",
    /** All Firefly accounts; empty until loaded. */
    val accounts: List<AccountResource> = emptyList(),
    /** [accounts] that fit the own-account field, sorted; recomputed only when accounts change. */
    val ownAccountCandidates: List<AccountResource> = emptyList(),
    /** [accounts] that fit the other-account field for [type], sorted. */
    val otherAccountCandidates: List<AccountResource> = emptyList(),
    val accountsLoading: Boolean = false,
    val accountsLoaded: Boolean = false,
    val accountsError: String? = null,
    val saveSuccess: Boolean = false,
    val saveError: RuleFieldProblem? = null,
    /** Increases with every failed save, so the screen can scroll to the field each time. */
    val saveErrorCount: Int = 0,
    val saving: Boolean = false,
    val sampleSender: String = "",
    val sampleBody: String = "",
    /** When the sample SMS arrived; the preview falls back to it when no date is found. */
    val sampleReceivedAt: Long? = null,
    val preview: RulePreview = RulePreview.NoSample
) {
    val priority: Int? get() = priorityText.toIntOrNull()

    /** The save error's message when it belongs to [field]. */
    fun errorFor(field: RuleField): String? = saveError?.takeIf { it.field == field }?.message

    /** Sets the loaded accounts and the candidate lists derived from them. */
    fun withAccounts(accounts: List<AccountResource>): RuleEditState = copy(
        accounts = accounts,
        ownAccountCandidates = accounts.ofTypes(RuleAccounts.OWN_ACCOUNT_TYPES),
        otherAccountCandidates = accounts.ofTypes(RuleAccounts.otherAccountTypes(type))
    )

    /**
     * Changing the type changes which other accounts fit. Without loaded accounts the stored ID
     * cannot be checked and may be the wrong kind, so keep only the name.
     */
    fun withTransactionType(newType: String): RuleEditState {
        val keepOtherId = accountsLoaded || newType == type
        val candidates = if (newType == type) otherAccountCandidates
        else accounts.ofTypes(RuleAccounts.otherAccountTypes(newType))
        return copy(
            type = newType,
            otherAccountId = if (keepOtherId) otherAccountId else null,
            otherAccountCandidates = candidates
        ).withResolvedAccounts()
    }

    /** Re-links both account names to the loaded Firefly accounts. */
    fun withResolvedAccounts(): RuleEditState {
        val (ownName, ownId) = resolveAccount(ownAccountName, ownAccountId, ownAccountCandidates, accountsLoaded)
        val (otherName, otherId) =
            resolveAccount(otherAccountName, otherAccountId, otherAccountCandidates, accountsLoaded)
        return copy(
            ownAccountName = ownName,
            ownAccountId = ownId,
            otherAccountName = otherName,
            otherAccountId = otherId
        )
    }
}

/** The values the user edits; two equal snapshots mean there is nothing to save. */
private data class RuleFormSnapshot(
    val id: String,
    val name: String,
    val senderPattern: String,
    val bodyPattern: String,
    val amountTemplate: String,
    val descriptionSource: String,
    val descriptionValue: String,
    val ownAccountName: String,
    val accountKeyword: String,
    val otherAccountName: String,
    val type: String,
    val priorityText: String,
    val datePattern: String,
    val dateFormat: String,
    val remarksPattern: String,
    val remarksTemplate: String
)

private fun RuleEditState.formSnapshot() = RuleFormSnapshot(
    id, name, senderPattern, bodyPattern, amountTemplate, descriptionSource, descriptionValue,
    ownAccountName, accountKeyword, otherAccountName, type, priorityText,
    datePattern, dateFormat, remarksPattern, remarksTemplate
)

/** Everything the live preview depends on; the preview reruns only when this changes. */
private data class PreviewInput(
    val rule: ParserRule?,
    val sender: String,
    val body: String,
    val receivedAt: Long?
)

internal fun RuleEditState.withImportedRuleFields(fields: ImportedRuleFields): RuleEditState = copy(
    bodyPattern = fields.bodyPattern,
    amountTemplate = fields.amountTemplate,
    descriptionSource = fields.descriptionSource,
    descriptionValue = fields.descriptionValue,
    type = fields.type,
    datePattern = fields.datePattern,
    dateFormat = fields.dateFormat,
    remarksPattern = fields.remarksPattern,
    remarksTemplate = fields.remarksTemplate
)

/** Puts back the fields an import replaces, as they were in [before]. */
internal fun RuleEditState.withImportUndone(before: RuleEditState): RuleEditState = copy(
    bodyPattern = before.bodyPattern,
    amountTemplate = before.amountTemplate,
    descriptionSource = before.descriptionSource,
    descriptionValue = before.descriptionValue,
    type = before.type,
    datePattern = before.datePattern,
    dateFormat = before.dateFormat,
    remarksPattern = before.remarksPattern,
    remarksTemplate = before.remarksTemplate,
    ownAccountName = before.ownAccountName,
    ownAccountId = before.ownAccountId,
    otherAccountName = before.otherAccountName,
    otherAccountId = before.otherAccountId,
    otherAccountCandidates = accounts.ofTypes(RuleAccounts.otherAccountTypes(before.type))
).withResolvedAccounts()

class RuleEditViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val dao = db.parserRuleDao()
    private val prefs = (application as FireSmsApp).preferencesManager

    private val _state = MutableStateFlow(RuleEditState())
    val state: StateFlow<RuleEditState> = _state.asStateFlow()

    /** The form as loaded (or as first filled in from an SMS); edits are compared with it. */
    private val baseline = MutableStateFlow(RuleEditState().formSnapshot())

    /** True when there are edits that leaving the screen would throw away. */
    val isDirty: StateFlow<Boolean> = combine(_state, baseline) { s, base -> s.formSnapshot() != base }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** ID of the stored rule being edited; null while creating a new rule. */
    private var loadedRuleId: String? = null

    /** The editor before the last JSON import, for Undo. */
    private var beforeImport: RuleEditState? = null

    init {
        viewModelScope.launch {
            _state.map { it.previewInput() }
                .distinctUntilChanged()
                .collectLatest { input -> runPreview(input) }
        }
    }

    private fun resetBaseline() {
        baseline.value = _state.value.formSnapshot()
    }

    fun loadRule(ruleId: String) {
        if (loadedRuleId == ruleId) return
        viewModelScope.launch {
            val stored = dao.getById(ruleId) ?: return@launch
            loadedRuleId = stored.id
            val rule = stored.withLegacyDepositAccountResolved()
            _state.update {
                it.copy(
                    id = rule.id,
                    name = rule.name,
                    senderPattern = rule.senderPattern,
                    bodyPattern = rule.bodyPattern,
                    amountTemplate = rule.amountTemplate,
                    descriptionSource = rule.descriptionSource,
                    descriptionValue = rule.descriptionValue,
                    ownAccountName = rule.sourceAccountName.orEmpty(),
                    ownAccountId = rule.sourceAccountId,
                    accountKeyword = rule.sourceAccountKeyword.orEmpty(),
                    otherAccountName = rule.destinationAccountName.orEmpty(),
                    otherAccountId = rule.destinationAccountId,
                    priorityText = rule.priority.toString(),
                    datePattern = rule.datePattern ?: "",
                    dateFormat = rule.dateFormat ?: "",
                    remarksPattern = rule.remarksPattern ?: "",
                    remarksTemplate = rule.remarksTemplate ?: "",
                    // Set the type directly: the stored account IDs belong to this type already.
                    type = rule.type,
                    otherAccountCandidates = it.accounts.ofTypes(RuleAccounts.otherAccountTypes(rule.type))
                ).withResolvedAccounts()
            }
            resetBaseline()
        }
    }

    fun loadSourceSms(smsLogId: Long) {
        viewModelScope.launch {
            val log = db.smsLogDao().getById(smsLogId) ?: return@launch
            val current = _state.value
            if (current.sampleBody.isNotBlank()) return@launch
            val senderSlug = log.sender.lowercase()
                .replace(NON_SLUG_CHARS, "-")
                .trim('-')
                .ifBlank { "bank" }
            _state.value = current.copy(
                id = current.id.ifBlank { "sms-$senderSlug-${UUID.randomUUID().toString().take(8)}" },
                name = current.name.ifBlank { "${log.sender} transactions" },
                senderPattern = current.senderPattern.ifBlank { SenderPatterns.suggestFor(log.sender) },
                sampleSender = log.sender,
                sampleBody = log.body,
                sampleReceivedAt = log.receivedAt
            )
            // The prefilled values are suggestions, not edits the user would lose.
            resetBaseline()
        }
    }

    /** Applies an edit and clears the save error, which may no longer be true. */
    private inline fun edit(crossinline change: (RuleEditState) -> RuleEditState) {
        _state.update { change(it).copy(saveError = null) }
    }

    fun updateId(id: String) = edit { it.copy(id = id) }
    fun updateName(name: String) = edit { it.copy(name = name) }
    fun updateSenderPattern(p: String) = edit { it.copy(senderPattern = p) }
    fun updateBodyPattern(p: String) = edit { it.copy(bodyPattern = p) }
    fun updateAmountTemplate(t: String) = edit { it.copy(amountTemplate = t) }
    fun updateDescriptionSource(s: String) = edit { it.copy(descriptionSource = s) }
    fun updateDescriptionValue(v: String) = edit { it.copy(descriptionValue = v) }
    fun updateAccountKeyword(k: String) = edit { it.copy(accountKeyword = k) }
    fun updateType(t: String) = edit { it.withTransactionType(t) }
    fun updatePriority(text: String) = edit { it.copy(priorityText = text) }
    fun updateDatePattern(p: String) = edit { it.copy(datePattern = p) }
    fun updateDateFormat(f: String) = edit { it.copy(dateFormat = f) }
    fun updateRemarksPattern(p: String) = edit { it.copy(remarksPattern = p) }
    fun updateRemarksTemplate(t: String) = edit { it.copy(remarksTemplate = t) }

    /** Sample message edits only change the preview, never the rule. */
    fun updateSampleSender(sender: String) = _state.update { it.copy(sampleSender = sender) }
    fun updateSampleBody(body: String) = _state.update { it.copy(sampleBody = body) }

    /** Typing unlinks the picked account; an exact name match links it again without changing the text. */
    fun updateOwnAccountName(name: String) = edit {
        it.copy(ownAccountName = name, ownAccountId = it.ownAccountCandidates.idForName(name))
    }

    fun selectOwnAccount(account: AccountResource) = edit {
        it.copy(ownAccountName = account.attributes.name, ownAccountId = account.id)
    }

    fun updateOtherAccountName(name: String) = edit {
        it.copy(otherAccountName = name, otherAccountId = it.otherAccountCandidates.idForName(name))
    }

    fun selectOtherAccount(account: AccountResource) = edit {
        it.copy(otherAccountName = account.attributes.name, otherAccountId = account.id)
    }

    /** Parses pasted JSON off the main thread; long inputs and the regex checks can be slow. */
    suspend fun parseRuleJson(input: String): RuleJsonImportResult = withContext(Dispatchers.Default) {
        try {
            RuleJsonImporter.parse(input)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Import input crosses a user-controlled boundary. Keep malformed or incompatible
            // payloads from terminating the UI, and retain the stack trace for Logcat diagnosis.
            Log.e("RuleEditViewModel", "Failed to parse imported rule JSON", error)
            val detail = error.message?.takeIf { it.isNotBlank() }
            RuleJsonImportResult.Error(
                buildString {
                    append("Internal JSON parser error (")
                    append(error.javaClass.simpleName)
                    append(")")
                    if (detail != null) append(": ").append(detail)
                }
            )
        }
    }

    fun applyImportedRuleFields(fields: ImportedRuleFields) {
        beforeImport = _state.value
        // An import can change the transaction type, which changes the suitable other accounts.
        edit { it.withTransactionType(fields.type).withImportedRuleFields(fields) }
    }

    /** Restores the fields replaced by the last import. */
    fun undoImport() {
        val before = beforeImport ?: return
        beforeImport = null
        edit { it.withImportUndone(before) }
    }

    suspend fun loadGeneratorPrompt(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().assets
                .open("SKILL.md")
                .bufferedReader()
                .use { it.readText() }
        }
    }

    /** Loads every Firefly account once; both account fields filter it by type. */
    fun fetchAccounts(force: Boolean = false) {
        val s = _state.value
        if (s.accountsLoading || (s.accountsLoaded && !force)) return
        _state.value = s.copy(accountsLoading = true, accountsError = null)
        viewModelScope.launch {
            val baseUrl = prefs.fireflyUrl.first()
            val token = prefs.fireflyToken.first()
            if (baseUrl.isEmpty() || token.isEmpty()) {
                _state.update {
                    it.copy(
                        accountsLoading = false,
                        accountsError = "Connect Firefly III in Settings to check account names."
                    )
                }
                return@launch
            }
            val api = FireflyApi(token)
            try {
                val accounts = com.firesms.app.data.remote.FireflyLookupCache.accounts(api, baseUrl, "all")
                // Linking names to accounts is not an edit; keep an untouched form clean.
                val wasClean = !isDirty.value
                _state.update {
                    it.copy(accountsLoading = false, accountsLoaded = true)
                        .withAccounts(accounts)
                        .withResolvedAccounts()
                }
                if (wasClean) resetBaseline()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        accountsLoading = false,
                        accountsError = "Couldn't load Firefly accounts. Names will be sent as typed."
                    )
                }
            } finally {
                api.close()
            }
        }
    }

    private fun fail(problem: RuleFieldProblem) {
        _state.update { it.copy(saveError = problem, saveErrorCount = it.saveErrorCount + 1, saving = false) }
    }

    fun save() {
        val current = _state.value
        validate(current)?.let { fail(it); return }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, saveError = null) }
            try {
                val state = _state.value
                val id = state.id.trim().ifBlank { "rule-${UUID.randomUUID()}" }
                if (loadedRuleId == null && dao.getById(id) != null) {
                    fail(
                        RuleFieldProblem(
                            RuleField.ID,
                            "A rule with ID \"$id\" already exists. Choose another ID or leave it blank."
                        )
                    )
                    return@launch
                }
                dao.upsert(state.toParserRule(id))
                loadedRuleId = id
                _state.update { it.copy(id = id, saveSuccess = true, saving = false) }
                resetBaseline()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(RuleFieldProblem(null, "Couldn't save the rule: ${error.message ?: error.toString()}"))
            }
        }
    }

    private fun RuleEditState.previewInput(): PreviewInput? {
        if (sampleSender.isBlank() || sampleBody.isBlank()) return null
        val complete = senderPattern.isNotBlank() && bodyPattern.isNotBlank()
        return PreviewInput(
            rule = if (complete) toParserRule(id.ifBlank { "preview" }) else null,
            sender = sampleSender,
            body = sampleBody,
            receivedAt = sampleReceivedAt
        )
    }

    private suspend fun runPreview(input: PreviewInput?) {
        if (input == null) {
            _state.update { it.copy(preview = RulePreview.NoSample) }
            return
        }
        val rule = input.rule
        if (rule == null) {
            _state.update {
                it.copy(preview = RulePreview.Incomplete("Add a sender pattern and a body pattern to see the result."))
            }
            return
        }
        // Mark the old result as outdated straight away; it no longer describes these fields.
        _state.update { it.copy(preview = RulePreview.Checking) }
        delay(PREVIEW_DEBOUNCE_MS)
        val preview = withContext(Dispatchers.Default) {
            val diagnosis = SmsParser.diagnoseRule(
                rule, input.sender, input.body, input.receivedAt ?: System.currentTimeMillis()
            )
            RulePreview.Result(diagnosis, problem = patternProblem(rule))
        }
        _state.update { it.copy(preview = preview) }
    }

    /** Validation problems in the pattern and template fields, which the preview also shows. */
    private fun patternProblem(rule: ParserRule): String? = RuleValidator.validate(
        bodyPattern = rule.bodyPattern,
        amountTemplate = rule.amountTemplate,
        descriptionSource = rule.descriptionSource,
        descriptionValue = rule.descriptionValue,
        type = rule.type,
        datePattern = rule.datePattern.orEmpty(),
        dateFormat = rule.dateFormat.orEmpty(),
        remarksPattern = rule.remarksPattern.orEmpty(),
        remarksTemplate = rule.remarksTemplate.orEmpty()
    )

    private fun validate(state: RuleEditState): RuleFieldProblem? {
        if (state.name.isBlank()) return RuleFieldProblem(RuleField.NAME, "Add a name for this rule.")
        if (state.senderPattern.isBlank()) {
            return RuleFieldProblem(RuleField.SENDER_PATTERN, "Add a sender pattern, e.g. HDFCBK.")
        }
        RuleValidator.patternProblem(state.senderPattern)?.let {
            return RuleFieldProblem(RuleField.SENDER_PATTERN, "Sender pattern isn't a valid pattern: $it")
        }
        if (state.bodyPattern.isBlank()) {
            return RuleFieldProblem(RuleField.BODY_PATTERN, "Add a body pattern that matches the message text.")
        }
        RuleValidator.validateFields(
            bodyPattern = state.bodyPattern,
            amountTemplate = state.amountTemplate,
            descriptionSource = state.descriptionSource,
            descriptionValue = state.descriptionValue,
            type = state.type,
            datePattern = state.datePattern,
            dateFormat = state.dateFormat,
            remarksPattern = state.remarksPattern,
            remarksTemplate = state.remarksTemplate
        )?.let { return RuleFieldProblem(VALIDATOR_FIELDS[it.field], it.message) }
        if (state.priority == null) {
            return RuleFieldProblem(RuleField.PRIORITY, "Enter a whole number for the priority, e.g. 10.")
        }
        return ruleAccountProblem(
            transactionType = state.type,
            ownName = state.ownAccountName,
            ownId = state.ownAccountId,
            accountKeyword = state.accountKeyword,
            otherName = state.otherAccountName,
            otherId = state.otherAccountId,
            accountsLoaded = state.accountsLoaded
        )?.let { (side, message) ->
            RuleFieldProblem(if (side == AccountSide.OWN) RuleField.OWN_ACCOUNT else RuleField.OTHER_ACCOUNT, message)
        }
    }

    private fun RuleEditState.toParserRule(ruleId: String) = ParserRule(
        id = ruleId,
        name = name.trim(),
        senderPattern = senderPattern,
        bodyPattern = bodyPattern,
        amountTemplate = amountTemplate,
        descriptionSource = descriptionSource,
        descriptionValue = descriptionValue,
        sourceAccountKeyword = accountKeyword.trim().ifBlank { null },
        sourceAccountName = ownAccountName.trim().ifBlank { null },
        sourceAccountId = ownAccountId?.takeIf { ownAccountName.isNotBlank() },
        destinationAccountName = otherAccountName.trim().ifBlank { null },
        destinationAccountId = otherAccountId?.takeIf { otherAccountName.isNotBlank() },
        type = type,
        priority = priority ?: DEFAULT_PRIORITY,
        datePattern = datePattern.ifBlank { null },
        dateFormat = dateFormat.ifBlank { null },
        remarksPattern = remarksPattern.ifBlank { null },
        remarksTemplate = remarksTemplate.ifBlank { null }
    )
}
