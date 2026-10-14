package com.firesms.app.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.data.remote.AccountResource
import com.firesms.app.data.remote.FireflyApi
import com.firesms.app.domain.parser.SmsParser
import com.firesms.app.domain.rules.ImportedRuleFields
import com.firesms.app.domain.rules.RuleJsonImportResult
import com.firesms.app.domain.rules.RuleJsonImporter
import com.firesms.app.domain.rules.RuleAccounts
import com.firesms.app.domain.rules.RuleValidator
import com.firesms.app.domain.rules.withLegacyDepositAccountResolved
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
    val priority: Int = 10,
    val datePattern: String = "",
    val dateFormat: String = "",
    val remarksPattern: String = "",
    val remarksTemplate: String = "",
    /** All Firefly accounts; empty until loaded. */
    val accounts: List<AccountResource> = emptyList(),
    val accountsLoading: Boolean = false,
    val accountsLoaded: Boolean = false,
    val accountsError: String? = null,
    val saveSuccess: Boolean = false,
    val saveError: String? = null,
    val saving: Boolean = false,
    val sampleSender: String = "",
    val sampleBody: String = "",
    val testing: Boolean = false,
    val testResult: String? = null
) {
    val ownAccountCandidates: List<AccountResource>
        get() = accounts.ofTypes(RuleAccounts.OWN_ACCOUNT_TYPES)
    val otherAccountCandidates: List<AccountResource>
        get() = accounts.ofTypes(RuleAccounts.otherAccountTypes(type))

    /**
     * Changing the type changes which other accounts fit. Without loaded accounts the stored ID
     * cannot be checked and may be the wrong kind, so keep only the name.
     */
    fun withTransactionType(newType: String): RuleEditState {
        val keepOtherId = accountsLoaded || newType == type
        return copy(type = newType, otherAccountId = if (keepOtherId) otherAccountId else null)
            .withResolvedAccounts()
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

class RuleEditViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val dao = db.parserRuleDao()
    private val prefs = (application as FireSmsApp).preferencesManager
    private val parser = SmsParser(dao)

    private val _state = MutableStateFlow(RuleEditState())
    val state: StateFlow<RuleEditState> = _state.asStateFlow()

    /** ID of the stored rule being edited; null while creating a new rule. */
    private var loadedRuleId: String? = null

    fun loadRule(ruleId: String) {
        if (loadedRuleId == ruleId) return
        viewModelScope.launch {
            val stored = dao.getById(ruleId) ?: return@launch
            loadedRuleId = stored.id
            val rule = stored.withLegacyDepositAccountResolved()
            _state.value = _state.value.copy(
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
                type = rule.type,
                priority = rule.priority,
                datePattern = rule.datePattern ?: "",
                dateFormat = rule.dateFormat ?: "",
                remarksPattern = rule.remarksPattern ?: "",
                remarksTemplate = rule.remarksTemplate ?: ""
            ).withResolvedAccounts()
        }
    }

    fun loadSourceSms(smsLogId: Long) {
        viewModelScope.launch {
            val log = db.smsLogDao().getById(smsLogId) ?: return@launch
            val current = _state.value
            if (current.sampleBody.isNotBlank()) return@launch
            val senderSlug = log.sender.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "bank" }
            _state.value = current.copy(
                id = current.id.ifBlank { "sms-$senderSlug-${UUID.randomUUID().toString().take(8)}" },
                name = current.name.ifBlank { "${log.sender} transactions" },
                senderPattern = current.senderPattern.ifBlank { Regex.escape(log.sender) },
                sampleSender = log.sender,
                sampleBody = log.body
            )
        }
    }

    fun updateId(id: String) { _state.value = _state.value.copy(id = id, saveError = null) }
    fun updateName(name: String) { _state.value = _state.value.copy(name = name, saveError = null) }
    fun updateSenderPattern(p: String) { _state.value = _state.value.copy(senderPattern = p, saveError = null) }
    fun updateBodyPattern(p: String) { _state.value = _state.value.copy(bodyPattern = p, saveError = null) }
    fun updateAmountTemplate(t: String) { _state.value = _state.value.copy(amountTemplate = t) }
    fun updateDescriptionSource(s: String) { _state.value = _state.value.copy(descriptionSource = s) }
    fun updateDescriptionValue(v: String) { _state.value = _state.value.copy(descriptionValue = v) }
    fun updateAccountKeyword(k: String) { _state.value = _state.value.copy(accountKeyword = k, saveError = null) }
    fun updateType(t: String) { _state.value = _state.value.copy(saveError = null).withTransactionType(t) }

    /** Typing unlinks the picked account; an exact name match links it again without changing the text. */
    fun updateOwnAccountName(name: String) {
        val s = _state.value
        _state.value = s.copy(ownAccountName = name, ownAccountId = s.ownAccountCandidates.idForName(name), saveError = null)
    }

    fun selectOwnAccount(account: AccountResource) {
        _state.value = _state.value.copy(
            ownAccountName = account.attributes.name,
            ownAccountId = account.id,
            saveError = null
        )
    }

    fun updateOtherAccountName(name: String) {
        val s = _state.value
        _state.value = s.copy(otherAccountName = name, otherAccountId = s.otherAccountCandidates.idForName(name), saveError = null)
    }

    fun selectOtherAccount(account: AccountResource) {
        _state.value = _state.value.copy(
            otherAccountName = account.attributes.name,
            otherAccountId = account.id,
            saveError = null
        )
    }
    fun updatePriority(p: Int) { _state.value = _state.value.copy(priority = p) }
    fun updateDatePattern(p: String) { _state.value = _state.value.copy(datePattern = p) }
    fun updateDateFormat(f: String) { _state.value = _state.value.copy(dateFormat = f) }
    fun updateRemarksPattern(p: String) { _state.value = _state.value.copy(remarksPattern = p) }
    fun updateRemarksTemplate(t: String) { _state.value = _state.value.copy(remarksTemplate = t) }

    fun parseRuleJson(input: String): RuleJsonImportResult = try {
        RuleJsonImporter.parse(input)
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

    fun applyImportedRuleFields(fields: ImportedRuleFields) {
        // An import can change the transaction type, which changes the suitable other accounts.
        _state.value = _state.value.withTransactionType(fields.type).withImportedRuleFields(fields)
    }

    fun loadGeneratorPrompt(): Result<String> = runCatching {
        getApplication<Application>().assets
            .open("SKILL.md")
            .bufferedReader()
            .use { it.readText() }
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
                _state.value = _state.value.copy(
                    accountsLoading = false,
                    accountsError = "Connect Firefly III in Settings to check account names."
                )
                return@launch
            }
            val api = FireflyApi(token)
            try {
                val accounts = api.getAccounts(baseUrl, "all")
                _state.value = _state.value.copy(
                    accounts = accounts,
                    accountsLoading = false,
                    accountsLoaded = true
                ).withResolvedAccounts()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    accountsLoading = false,
                    accountsError = "Couldn't load Firefly accounts. Names will be sent as typed."
                )
            } finally {
                api.close()
            }
        }
    }

    fun save() {
        val current = _state.value
        val validationError = validate(current) ?: validateRuleAccounts(
            transactionType = current.type,
            ownName = current.ownAccountName,
            ownId = current.ownAccountId,
            accountKeyword = current.accountKeyword,
            otherName = current.otherAccountName,
            otherId = current.otherAccountId,
            accountsLoaded = current.accountsLoaded
        )
        if (validationError != null) {
            _state.value = current.copy(saveError = validationError)
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, saveError = null)
            try {
                val state = _state.value
                val id = state.id.trim().ifBlank { "rule-${UUID.randomUUID()}" }
                if (loadedRuleId == null && dao.getById(id) != null) {
                    _state.value = _state.value.copy(
                        saveError = "A rule with ID \"$id\" already exists. Choose another ID or leave it blank.",
                        saving = false
                    )
                    return@launch
                }
                dao.upsert(state.toParserRule(id))
                loadedRuleId = id
                _state.value = _state.value.copy(id = id, saveSuccess = true, saving = false)
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    saveError = error.message ?: error.toString(),
                    saving = false
                )
            }
        }
    }

    fun testRule(sender: String, body: String) {
        val current = _state.value
        val validationError = validate(current)
        if (validationError != null) {
            _state.value = current.copy(testResult = validationError)
            return
        }
        if (sender.isBlank() || body.isBlank()) {
            _state.value = current.copy(testResult = "Enter an SMS sender and message to run the test")
            return
        }

        _state.value = current.copy(testing = true, testResult = null)
        viewModelScope.launch {
            val resultText = try {
                val rule = _state.value.toParserRule(_state.value.id.ifBlank { "test-rule" })
                // Regex matching can be slow; keep it off the main thread but update state on it.
                val result = withContext(Dispatchers.Default) { parser.testRule(rule, sender, body) }
                result?.let {
                    buildString {
                        append("Parsed: ${it.transactionType} ${it.amount}")
                        append(" — ${it.description}")
                        append(" | ${it.sourceAccountName ?: "?"} → ${it.destinationAccountName ?: "(no name)"}")
                        if (!it.notes.isNullOrBlank()) append(" | Notes: ${it.notes}")
                    }
                } ?: "No match"
            } catch (error: Exception) {
                "Error: ${error.message}"
            }
            _state.value = _state.value.copy(testing = false, testResult = resultText)
        }
    }

    private fun validate(state: RuleEditState): String? {
        if (state.name.isBlank()) return "Add a name for this rule"
        if (state.senderPattern.isBlank()) return "Add a sender pattern"
        if (state.bodyPattern.isBlank()) return "Add a message body pattern"
        runCatching { Regex(state.senderPattern) }
            .onFailure { return "Sender pattern is not valid regex: ${it.message}" }
        return RuleValidator.validate(
            bodyPattern = state.bodyPattern,
            amountTemplate = state.amountTemplate,
            descriptionSource = state.descriptionSource,
            descriptionValue = state.descriptionValue,
            type = state.type,
            datePattern = state.datePattern,
            dateFormat = state.dateFormat,
            remarksPattern = state.remarksPattern,
            remarksTemplate = state.remarksTemplate,
            label = { FIELD_LABELS[it] ?: it }
        )
    }

    private companion object {
        val FIELD_LABELS = mapOf(
            "bodyPattern" to "Body pattern",
            "amountTemplate" to "Amount template",
            "descriptionSource" to "Description source",
            "descriptionValue" to "Description value",
            "type" to "Transaction type",
            "datePattern" to "Date pattern",
            "dateFormat" to "Date format",
            "remarksPattern" to "Remarks pattern",
            "remarksTemplate" to "Remarks template"
        )
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
        priority = priority,
        datePattern = datePattern.ifBlank { null },
        dateFormat = dateFormat.ifBlank { null },
        remarksPattern = remarksPattern.ifBlank { null },
        remarksTemplate = remarksTemplate.ifBlank { null }
    )
}
