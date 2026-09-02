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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class RuleEditState(
    val id: String = "",
    val name: String = "",
    val senderPattern: String = "",
    val bodyPattern: String = "",
    val amountTemplate: String = "{{amount}}",
    val descriptionSource: String = "template",
    val descriptionValue: String = "",
    val sourceAccountKeyword: String? = null,
    val sourceAccountName: String? = null,
    val sourceAccountId: String? = null,
    val destinationAccountName: String? = null,
    val type: String = "withdrawal",
    val priority: Int = 10,
    val datePattern: String = "",
    val dateFormat: String = "",
    val remarksPattern: String = "",
    val remarksTemplate: String = "",
    val accounts: List<AccountResource> = emptyList(),
    val accountsLoading: Boolean = false,
    val saveSuccess: Boolean = false,
    val saveError: String? = null,
    val saving: Boolean = false,
    val sampleSender: String = "",
    val sampleBody: String = "",
    val testing: Boolean = false,
    val testResult: String? = null
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

class RuleEditViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as FireSmsApp).database
    private val dao = db.parserRuleDao()
    private val prefs = (application as FireSmsApp).preferencesManager
    private val parser = SmsParser(dao)

    private val _state = MutableStateFlow(RuleEditState())
    val state: StateFlow<RuleEditState> = _state.asStateFlow()

    fun loadRule(ruleId: String) {
        viewModelScope.launch {
            val rule = dao.getById(ruleId) ?: return@launch
            _state.value = RuleEditState(
                id = rule.id,
                name = rule.name,
                senderPattern = rule.senderPattern,
                bodyPattern = rule.bodyPattern,
                amountTemplate = rule.amountTemplate,
                descriptionSource = rule.descriptionSource,
                descriptionValue = rule.descriptionValue,
                sourceAccountKeyword = rule.sourceAccountKeyword,
                sourceAccountName = rule.sourceAccountName,
                sourceAccountId = rule.sourceAccountId,
                destinationAccountName = rule.destinationAccountName,
                type = rule.type,
                priority = rule.priority,
                datePattern = rule.datePattern ?: "",
                dateFormat = rule.dateFormat ?: "",
                remarksPattern = rule.remarksPattern ?: "",
                remarksTemplate = rule.remarksTemplate ?: ""
            )
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
    fun updateSourceAccountKeyword(k: String?) { _state.value = _state.value.copy(sourceAccountKeyword = k) }
    fun updateDestinationAccountName(n: String?) { _state.value = _state.value.copy(destinationAccountName = n) }
    fun updateType(t: String) { _state.value = _state.value.copy(type = t) }
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
        _state.value = _state.value.withImportedRuleFields(fields)
    }

    fun loadGeneratorPrompt(): Result<String> = runCatching {
        getApplication<Application>().assets
            .open("SKILL.md")
            .bufferedReader()
            .use { it.readText() }
    }

    fun selectSourceAccount(name: String, id: String) {
        _state.value = _state.value.copy(sourceAccountName = name, sourceAccountId = id)
    }

    fun fetchAccounts() {
        val s = _state.value
        if (s.accountsLoading || s.accounts.isNotEmpty()) return
        _state.value = s.copy(accountsLoading = true)
        viewModelScope.launch {
            try {
                val baseUrl = prefs.fireflyUrl.first()
                val token = prefs.fireflyToken.first()
                if (baseUrl.isEmpty() || token.isEmpty()) {
                    _state.value = _state.value.copy(accountsLoading = false)
                    return@launch
                }
                val api = FireflyApi(token)
                try {
                    val accounts = api.getAccounts(baseUrl)
                    _state.value = _state.value.copy(accounts = accounts, accountsLoading = false)
                } finally {
                    api.close()
                }
            } catch (_: Exception) {
                _state.value = _state.value.copy(accountsLoading = false)
            }
        }
    }

    fun save() {
        val current = _state.value
        val validationError = validate(current)
        if (validationError != null) {
            _state.value = current.copy(saveError = validationError)
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, saveError = null)
            try {
                val state = _state.value
                val id = state.id.ifBlank { "rule-${UUID.randomUUID()}" }
                dao.upsert(state.toParserRule(id))
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
                val result = withTimeout(5000) { parser.testRule(rule, sender, body) }
                result?.let {
                    buildString {
                        append("Parsed: ${it.transactionType} ${it.amount}")
                        append(" — ${it.description}")
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
        runCatching { Regex(state.bodyPattern) }
            .onFailure { return "Body pattern is not valid regex: ${it.message}" }
        return null
    }

    private fun RuleEditState.toParserRule(ruleId: String) = ParserRule(
        id = ruleId,
        name = name.trim(),
        senderPattern = senderPattern,
        bodyPattern = bodyPattern,
        amountTemplate = amountTemplate,
        descriptionSource = descriptionSource,
        descriptionValue = descriptionValue,
        sourceAccountKeyword = sourceAccountKeyword,
        sourceAccountName = sourceAccountName?.ifBlank { null },
        sourceAccountId = sourceAccountId?.ifBlank { null },
        destinationAccountName = destinationAccountName,
        type = type,
        priority = priority,
        datePattern = datePattern.ifBlank { null },
        dateFormat = dateFormat.ifBlank { null },
        remarksPattern = remarksPattern.ifBlank { null },
        remarksTemplate = remarksTemplate.ifBlank { null }
    )
}
