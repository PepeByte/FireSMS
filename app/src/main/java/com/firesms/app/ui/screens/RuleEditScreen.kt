package com.firesms.app.ui.screens

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.data.remote.AccountResource
import com.firesms.app.domain.parser.DateResolution
import com.firesms.app.domain.parser.RuleDiagnosis
import com.firesms.app.domain.rules.ImportedRuleFields
import com.firesms.app.domain.rules.RuleJsonImportResult
import com.firesms.app.domain.rules.RuleValidator
import com.firesms.app.domain.rules.SenderPatterns
import com.firesms.app.ui.components.FireSmsResultBanner
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.components.FireSmsScreen
import com.firesms.app.ui.components.FireSmsSection
import com.firesms.app.ui.components.FireSmsStatusPill
import com.firesms.app.ui.components.FireflyAccountField
import com.firesms.app.ui.components.rememberUnsavedChangesGuard
import com.firesms.app.ui.theme.FireSmsTheme
import com.firesms.app.ui.viewmodels.RuleEditViewModel
import com.firesms.app.ui.viewmodels.RuleField
import com.firesms.app.ui.viewmodels.RulePreview
import com.firesms.app.ui.viewmodels.accountRoleText
import com.firesms.app.ui.viewmodels.checkOtherAccount
import com.firesms.app.ui.viewmodels.checkOwnAccount
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DESCRIPTION_SOURCES = listOf("template" to "Template", "regex_group" to "Regex group")
private val TRANSACTION_TYPES = listOf("withdrawal" to "Withdrawal", "deposit" to "Deposit", "transfer" to "Transfer")
private val PRIORITY_INPUT = Regex("-?[0-9]{0,6}")
private val PREVIEW_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

private typealias FieldRequesters = Map<RuleField, BringIntoViewRequester>

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.field(requesters: FieldRequesters, field: RuleField): Modifier =
    bringIntoViewRequester(requesters.getValue(field))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RuleEditScreen(
    ruleId: String?,
    sourceSmsLogId: Long? = null,
    onBack: () -> Unit,
    viewModel: RuleEditViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()

    LaunchedEffect(ruleId, sourceSmsLogId) {
        if (ruleId != null) {
            viewModel.loadRule(ruleId)
        } else if (sourceSmsLogId != null) {
            viewModel.loadSourceSms(sourceSmsLogId)
        }
    }

    // Load Firefly accounts up front so both account fields can be checked as the user types.
    LaunchedEffect(Unit) { viewModel.fetchAccounts() }

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            onBack()
        }
    }

    val guardedBack = rememberUnsavedChangesGuard(isDirty = isDirty && !state.saveSuccess, onBack = onBack)

    val clipboard = LocalClipboard.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val requesters: FieldRequesters = remember { RuleField.entries.associateWith { BringIntoViewRequester() } }

    var showImportDialog by remember { mutableStateOf(false) }
    var importJson by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<ImportedRuleFields?>(null) }
    var pendingImportRepairedEscapes by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val isNew = ruleId == null

    // Bring the field with the save error on screen; errors without a field go to a snackbar.
    LaunchedEffect(state.saveErrorCount) {
        val problem = state.saveError ?: return@LaunchedEffect
        val field = problem.field
        if (field == null) {
            snackbarHostState.showSnackbar(problem.message)
            return@LaunchedEffect
        }
        if (field == RuleField.ID && !showAdvanced) {
            showAdvanced = true
            withFrameNanos { }
        }
        requesters.getValue(field).bringIntoView()
        snackbarHostState.showSnackbar("Fix the highlighted field: ${problem.message}")
    }

    LaunchedEffect(showImportDialog, pendingImport) {
        val fields = pendingImport
        if (!showImportDialog && fields != null) {
            // Let the focused dialog field and its IME connection leave composition before
            // updating the editor behind it.
            withFrameNanos { }
            viewModel.applyImportedRuleFields(fields)
            pendingImport = null
            importJson = ""
            importError = null
            val message = if (pendingImportRepairedEscapes) {
                "Rule loaded; invalid regex escapes were repaired"
            } else {
                "Rule fields loaded from JSON"
            }
            pendingImportRepairedEscapes = false
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoImport()
        }
    }

    FireSmsScaffold(
        title = if (isNew) "New parser rule" else "Edit parser rule",
        onBack = guardedBack,
        actions = {
            TextButton(
                onClick = { viewModel.save() },
                enabled = !state.saving
            ) {
                Text(if (state.saving) "Saving…" else "Save")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        FireSmsScreen(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SampleMessageSection(
                sender = state.sampleSender,
                body = state.sampleBody,
                onSenderChange = viewModel::updateSampleSender,
                onBodyChange = viewModel::updateSampleBody
            )

            ImportSection(
                onCopyInstructions = {
                    coroutineScope.launch {
                        val prompt = viewModel.loadGeneratorPrompt().getOrNull()
                        if (prompt == null) {
                            snackbarHostState.showSnackbar("Couldn't copy the generator instructions")
                            return@launch
                        }
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("FireSMS AI rule generator", prompt)))
                        snackbarHostState.showSnackbar("AI generator instructions copied")
                    }
                },
                onLoadJson = {
                    importError = null
                    showImportDialog = true
                }
            )

            RuleMatchSection(
                name = state.name,
                senderPattern = state.senderPattern,
                bodyPattern = state.bodyPattern,
                nameError = state.errorFor(RuleField.NAME),
                senderError = state.errorFor(RuleField.SENDER_PATTERN),
                bodyError = state.errorFor(RuleField.BODY_PATTERN),
                preview = state.preview,
                requesters = requesters,
                onNameChange = viewModel::updateName,
                onSenderPatternChange = viewModel::updateSenderPattern,
                onBodyPatternChange = viewModel::updateBodyPattern
            )

            TransactionMappingSection(
                amountTemplate = state.amountTemplate,
                descriptionSource = state.descriptionSource,
                descriptionValue = state.descriptionValue,
                type = state.type,
                priorityText = state.priorityText,
                amountError = state.errorFor(RuleField.AMOUNT_TEMPLATE),
                descriptionSourceError = state.errorFor(RuleField.DESCRIPTION_SOURCE),
                descriptionValueError = state.errorFor(RuleField.DESCRIPTION_VALUE),
                typeError = state.errorFor(RuleField.TYPE),
                priorityError = state.errorFor(RuleField.PRIORITY),
                requesters = requesters,
                onAmountTemplateChange = viewModel::updateAmountTemplate,
                onDescriptionSourceChange = viewModel::updateDescriptionSource,
                onDescriptionValueChange = viewModel::updateDescriptionValue,
                onTypeChange = viewModel::updateType,
                onPriorityChange = viewModel::updatePriority
            )

            AccountsSection(
                type = state.type,
                ownAccountName = state.ownAccountName,
                ownAccountId = state.ownAccountId,
                otherAccountName = state.otherAccountName,
                otherAccountId = state.otherAccountId,
                accountKeyword = state.accountKeyword,
                ownCandidates = state.ownAccountCandidates,
                otherCandidates = state.otherAccountCandidates,
                accountsLoaded = state.accountsLoaded,
                accountsLoading = state.accountsLoading,
                accountsError = state.accountsError,
                ownError = state.errorFor(RuleField.OWN_ACCOUNT),
                otherError = state.errorFor(RuleField.OTHER_ACCOUNT),
                requesters = requesters,
                onOwnNameChange = viewModel::updateOwnAccountName,
                onOwnSelected = viewModel::selectOwnAccount,
                onOtherNameChange = viewModel::updateOtherAccountName,
                onOtherSelected = viewModel::selectOtherAccount,
                onKeywordChange = viewModel::updateAccountKeyword,
                onRetryAccounts = { viewModel.fetchAccounts(force = true) }
            )

            OptionalPairSection(
                title = "Date extraction",
                subtitle = "Optional. Without it, the SMS received time is used.",
                first = state.datePattern,
                second = state.dateFormat,
                firstLabel = "Date pattern",
                secondLabel = "Date format",
                firstPlaceholder = "{{date}} or a pattern",
                secondPlaceholder = "dd-MMM-yyyy HH:mm or dd/MM/yy",
                firstSupporting = "A {{group}} from the body pattern, or a pattern that finds the date",
                secondSupporting = "How the date is written, e.g. dd-MM-yyyy for 31-12-2026",
                firstError = state.errorFor(RuleField.DATE_PATTERN),
                secondError = state.errorFor(RuleField.DATE_FORMAT),
                firstField = RuleField.DATE_PATTERN,
                secondField = RuleField.DATE_FORMAT,
                requesters = requesters,
                onFirstChange = viewModel::updateDatePattern,
                onSecondChange = viewModel::updateDateFormat
            )

            OptionalPairSection(
                title = "Remarks extraction",
                subtitle = "Optional. Saved as the transaction notes.",
                first = state.remarksTemplate,
                second = state.remarksPattern,
                firstLabel = "Remarks template",
                secondLabel = "Remarks pattern",
                firstPlaceholder = "Ref {{ref}}",
                secondPlaceholder = "{{ref}} or a pattern",
                firstSupporting = "Text with {{group}} placeholders",
                secondSupporting = "Used only when the template is blank",
                firstError = state.errorFor(RuleField.REMARKS_TEMPLATE),
                secondError = state.errorFor(RuleField.REMARKS_PATTERN),
                firstField = RuleField.REMARKS_TEMPLATE,
                secondField = RuleField.REMARKS_PATTERN,
                requesters = requesters,
                onFirstChange = viewModel::updateRemarksTemplate,
                onSecondChange = viewModel::updateRemarksPattern
            )

            AdvancedSection(
                expanded = showAdvanced,
                onToggle = { showAdvanced = !showAdvanced },
                id = state.id,
                isNew = isNew,
                idError = state.errorFor(RuleField.ID),
                requesters = requesters,
                onIdChange = viewModel::updateId
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { if (!importing) showImportDialog = false },
            title = { Text("Load rule JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste the JSON generated by your LLM. Only the body, amount, description, type, date and remarks fields will be changed. You can undo it afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = importJson,
                        onValueChange = {
                            importJson = it
                            importError = null
                        },
                        label = { Text("Rule JSON") },
                        placeholder = { Text("{ \"bodyPattern\": \"…\" }") },
                        isError = importError != null,
                        minLines = 7,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth()
                    )
                    importError?.let { FireSmsResultBanner(text = it, isError = true) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importing = true
                        coroutineScope.launch {
                            val result = viewModel.parseRuleJson(importJson)
                            importing = false
                            when (result) {
                                is RuleJsonImportResult.Error -> importError = result.message
                                is RuleJsonImportResult.Success -> {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    pendingImport = result.fields
                                    pendingImportRepairedEscapes = result.repairedInvalidEscapes
                                    showImportDialog = false
                                }
                            }
                        }
                    },
                    enabled = importJson.isNotBlank() && !importing
                ) { Text(if (importing) "Checking…" else "Load") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }, enabled = !importing) { Text("Cancel") }
            }
        )
    }
}

// ─── Sections ──────────────────────────────────────────────────

@Composable
private fun SampleMessageSection(
    sender: String,
    body: String,
    onSenderChange: (String) -> Unit,
    onBodyChange: (String) -> Unit
) {
    FireSmsSection(
        title = "Sample message",
        subtitle = "The rule is checked against this message as you edit it. It isn't saved with the rule."
    ) {
        OutlinedTextField(
            value = sender,
            onValueChange = onSenderChange,
            label = { Text("Sender") },
            placeholder = { Text("e.g. AD-HDFCBK") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = body,
            onValueChange = onBodyChange,
            label = { Text("Message") },
            placeholder = { Text("Paste a bank SMS") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ImportSection(onCopyInstructions: () -> Unit, onLoadJson: () -> Unit) {
    FireSmsSection(
        title = "Import with AI",
        subtitle = "Copy the generator instructions to your LLM, then paste its JSON response here."
    ) {
        OutlinedButton(onClick = onCopyInstructions, modifier = Modifier.fillMaxWidth()) {
            Text("Copy AI generator instructions")
        }
        OutlinedButton(onClick = onLoadJson, modifier = Modifier.fillMaxWidth()) {
            Text("Load rule JSON")
        }
        Text(
            "SMS messages may contain sensitive financial information. Review or redact personal values before sharing them with an external AI service.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleMatchSection(
    name: String,
    senderPattern: String,
    bodyPattern: String,
    nameError: String?,
    senderError: String?,
    bodyError: String?,
    preview: RulePreview,
    requesters: FieldRequesters,
    onNameChange: (String) -> Unit,
    onSenderPatternChange: (String) -> Unit,
    onBodyPatternChange: (String) -> Unit
) {
    FireSmsSection(title = "Rule") {
        RuleTextField(
            value = name,
            onValueChange = onNameChange,
            label = "Rule name",
            error = nameError,
            modifier = Modifier.field(requesters, RuleField.NAME)
        )
        val senderHelp = remember(senderPattern) {
            SenderPatterns.explain(senderPattern)
                ?: "A pattern found in the sender, e.g. HDFCBK also matches AD-HDFCBK and VM-HDFCBK"
        }
        RuleTextField(
            value = senderPattern,
            onValueChange = onSenderPatternChange,
            label = "Sender pattern",
            placeholder = "e.g. HDFCBK",
            supporting = senderHelp,
            error = senderError,
            monospace = true,
            modifier = Modifier.field(requesters, RuleField.SENDER_PATTERN)
        )
        RuleTextField(
            value = bodyPattern,
            onValueChange = onBodyPatternChange,
            label = "Body pattern",
            placeholder = "e.g. debited by Rs\\.(?<amount>[\\d,.]+) at (?<merchant>.+?)\\.",
            supporting = "Capture each value with a named group, e.g. ${RuleValidator.NAMED_GROUP_EXAMPLE}",
            error = bodyError,
            singleLine = false,
            monospace = true,
            modifier = Modifier.field(requesters, RuleField.BODY_PATTERN)
        )
        val groups = remember(bodyPattern) { RuleValidator.namedGroups(bodyPattern).toList() }
        if (groups.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Groups:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                groups.forEach { FireSmsStatusPill(label = it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        RulePreviewCard(preview)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionMappingSection(
    amountTemplate: String,
    descriptionSource: String,
    descriptionValue: String,
    type: String,
    priorityText: String,
    amountError: String?,
    descriptionSourceError: String?,
    descriptionValueError: String?,
    typeError: String?,
    priorityError: String?,
    requesters: FieldRequesters,
    onAmountTemplateChange: (String) -> Unit,
    onDescriptionSourceChange: (String) -> Unit,
    onDescriptionValueChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onPriorityChange: (String) -> Unit
) {
    FireSmsSection(title = "Transaction mapping") {
        RuleTextField(
            value = amountTemplate,
            onValueChange = onAmountTemplateChange,
            label = "Amount template",
            placeholder = "{{amount}}",
            supporting = "Usually {{amount}}: the group that holds the amount",
            error = amountError,
            monospace = true,
            modifier = Modifier.field(requesters, RuleField.AMOUNT_TEMPLATE)
        )
        ChoiceField(
            label = "Description source",
            options = DESCRIPTION_SOURCES,
            selected = descriptionSource,
            onSelect = onDescriptionSourceChange,
            error = descriptionSourceError,
            modifier = Modifier.field(requesters, RuleField.DESCRIPTION_SOURCE)
        )
        RuleTextField(
            value = descriptionValue,
            onValueChange = onDescriptionValueChange,
            label = "Description value",
            placeholder = if (descriptionSource == "regex_group") "merchant" else "{{merchant}}",
            supporting = if (descriptionSource == "regex_group") {
                "The name of a group in the body pattern, e.g. merchant"
            } else {
                "Text with {{group}} placeholders, e.g. Paid to {{merchant}}"
            },
            error = descriptionValueError,
            modifier = Modifier.field(requesters, RuleField.DESCRIPTION_VALUE)
        )
        ChoiceField(
            label = "Transaction type",
            options = TRANSACTION_TYPES,
            selected = type,
            onSelect = onTypeChange,
            error = typeError,
            modifier = Modifier.field(requesters, RuleField.TYPE)
        )
        val priorityInvalid = priorityError != null || priorityText.toIntOrNull() == null
        OutlinedTextField(
            value = priorityText,
            onValueChange = { text ->
                val trimmed = text.trim()
                if (PRIORITY_INPUT.matches(trimmed)) onPriorityChange(trimmed)
            },
            label = { Text("Priority") },
            isError = priorityInvalid,
            supportingText = {
                Text(
                    priorityError
                        ?: if (priorityText.toIntOrNull() == null) "Enter a whole number, e.g. 10"
                        else "Lower numbers are checked first"
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().field(requesters, RuleField.PRIORITY)
        )
    }
}

@Composable
private fun AccountsSection(
    type: String,
    ownAccountName: String,
    ownAccountId: String?,
    otherAccountName: String,
    otherAccountId: String?,
    accountKeyword: String,
    ownCandidates: List<AccountResource>,
    otherCandidates: List<AccountResource>,
    accountsLoaded: Boolean,
    accountsLoading: Boolean,
    accountsError: String?,
    ownError: String?,
    otherError: String?,
    requesters: FieldRequesters,
    onOwnNameChange: (String) -> Unit,
    onOwnSelected: (AccountResource) -> Unit,
    onOtherNameChange: (String) -> Unit,
    onOtherSelected: (AccountResource) -> Unit,
    onKeywordChange: (String) -> Unit,
    onRetryAccounts: () -> Unit
) {
    val accountText = accountRoleText(type)
    FireSmsSection(title = "Accounts", subtitle = accountText.summary) {
        FireflyAccountField(
            value = ownAccountName,
            onValueChange = onOwnNameChange,
            onAccountSelected = onOwnSelected,
            label = accountText.ownLabel,
            placeholder = accountText.ownPlaceholder,
            candidates = ownCandidates,
            check = checkOwnAccount(ownAccountName, ownAccountId, accountsLoaded),
            loading = accountsLoading,
            errorText = ownError,
            modifier = Modifier.field(requesters, RuleField.OWN_ACCOUNT)
        )
        FireflyAccountField(
            value = otherAccountName,
            onValueChange = onOtherNameChange,
            onAccountSelected = onOtherSelected,
            label = accountText.otherLabel,
            placeholder = accountText.otherPlaceholder,
            candidates = otherCandidates,
            check = checkOtherAccount(otherAccountName, otherAccountId, type, accountsLoaded),
            loading = accountsLoading,
            errorText = otherError,
            modifier = Modifier.field(requesters, RuleField.OTHER_ACCOUNT)
        )

        accountsError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRetryAccounts) { Text("Retry") }
            }
        }

        // Show exactly what Firefly will record, so the direction is never a guess.
        val ownDisplay = ownAccountName.trim().ifBlank {
            if (accountKeyword.isNotBlank()) "account matching \"${accountKeyword.trim()}\"" else "?"
        }
        val otherDisplay = otherAccountName.trim().ifBlank {
            if (type == "transfer") "?" else "(no name)"
        }
        val (fromAccount, toAccount) = if (type == "deposit") {
            otherDisplay to ownDisplay
        } else {
            ownDisplay to otherDisplay
        }
        Text(
            "In Firefly: $fromAccount → $toAccount",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        var showKeyword by rememberSaveable { mutableStateOf(false) }
        if (showKeyword || accountKeyword.isNotBlank()) {
            OutlinedTextField(
                value = accountKeyword,
                onValueChange = onKeywordChange,
                label = { Text("Account word in the SMS (optional)") },
                placeholder = { Text("e.g. XX1234") },
                supportingText = {
                    Text("Used only when no account is chosen above. The matching SMS word must be your exact Firefly account name.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            TextButton(onClick = { showKeyword = true }) {
                Text("Take my account name from the SMS instead")
            }
        }
    }
}

@Composable
private fun OptionalPairSection(
    title: String,
    subtitle: String,
    first: String,
    second: String,
    firstLabel: String,
    secondLabel: String,
    firstPlaceholder: String,
    secondPlaceholder: String,
    firstSupporting: String,
    secondSupporting: String,
    firstError: String?,
    secondError: String?,
    firstField: RuleField,
    secondField: RuleField,
    requesters: FieldRequesters,
    onFirstChange: (String) -> Unit,
    onSecondChange: (String) -> Unit
) {
    FireSmsSection(title = title, subtitle = subtitle) {
        RuleTextField(
            value = first,
            onValueChange = onFirstChange,
            label = firstLabel,
            placeholder = firstPlaceholder,
            supporting = firstSupporting,
            error = firstError,
            monospace = true,
            modifier = Modifier.field(requesters, firstField)
        )
        RuleTextField(
            value = second,
            onValueChange = onSecondChange,
            label = secondLabel,
            placeholder = secondPlaceholder,
            supporting = secondSupporting,
            error = secondError,
            monospace = true,
            modifier = Modifier.field(requesters, secondField)
        )
    }
}

@Composable
private fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    id: String,
    isNew: Boolean,
    idError: String?,
    requesters: FieldRequesters,
    onIdChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onToggle) {
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "Hide advanced" else "Advanced")
        }
        AnimatedVisibility(visible = expanded) {
            RuleTextField(
                value = id,
                onValueChange = onIdChange,
                label = "Rule ID",
                placeholder = "Generated automatically",
                supporting = if (isNew) {
                    "Optional. Identifies the rule in backups; leave blank to generate one."
                } else {
                    "Identifies the rule in backups. It can't be changed after saving."
                },
                error = idError,
                enabled = isNew,
                modifier = Modifier.field(requesters, RuleField.ID)
            )
        }
    }
}

// ─── Live preview ──────────────────────────────────────────────

@Composable
private fun RulePreviewCard(preview: RulePreview) {
    val matched = (preview as? RulePreview.Result)?.diagnosis as? RuleDiagnosis.Matched
    val failed = preview is RulePreview.Result && matched == null
    val (container, content) = when {
        matched != null -> FireSmsTheme.colors.successContainer to FireSmsTheme.colors.onSuccessContainer
        failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (preview) {
                RulePreview.NoSample -> PreviewNote("Add a sample message above to check the rule as you type.")
                is RulePreview.Incomplete -> PreviewNote(preview.message)
                RulePreview.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = content)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking the sample message…", style = MaterialTheme.typography.bodySmall)
                }
                is RulePreview.Result -> {
                    DiagnosisContent(preview.diagnosis)
                    preview.problem?.let { problem ->
                        WarningNote("Before saving: $problem")
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun WarningNote(text: String) {
    Surface(
        color = FireSmsTheme.colors.warningContainer,
        contentColor = FireSmsTheme.colors.onWarningContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun DiagnosisContent(diagnosis: RuleDiagnosis) {
    val title = MaterialTheme.typography.titleSmall
    val body = MaterialTheme.typography.bodySmall
    when (diagnosis) {
        is RuleDiagnosis.InvalidPattern -> {
            Text("Can't test yet", style = title)
            Text("${diagnosis.field} isn't a valid pattern. ${diagnosis.detail.substringBefore('\n')}", style = body)
        }
        is RuleDiagnosis.SenderMismatch -> {
            Text("No match", style = title)
            Text("The sender pattern didn't match '${diagnosis.sender}'.", style = body)
        }
        RuleDiagnosis.BodyMismatch -> {
            Text("No match", style = title)
            Text(
                "The sender matched, but the body pattern didn't match the message. Check spaces, punctuation and the parts that change between messages.",
                style = body
            )
        }
        is RuleDiagnosis.AmountInvalid -> {
            Text("No match", style = title)
            Text(
                "The amount template '${diagnosis.template}' resolved to '${diagnosis.resolved}', which isn't a positive number.",
                style = body
            )
            if (diagnosis.missingGroups.isNotEmpty()) {
                Text("Nothing was captured for: ${diagnosis.missingGroups.joinToString()}.", style = body)
            }
            GroupValues(diagnosis.groups)
        }
        is RuleDiagnosis.Matched -> {
            val tx = diagnosis.transaction
            val typeLabel = TRANSACTION_TYPES.firstOrNull { it.first == tx.transactionType }?.second ?: tx.transactionType
            Text("Matches: $typeLabel of ${tx.amount}", style = title)
            Text("Description: ${tx.description}", style = body)
            Text(
                "In Firefly: ${tx.sourceAccountName ?: "?"} → ${tx.destinationAccountName ?: "(no name)"}",
                style = body
            )
            if (!tx.notes.isNullOrBlank()) Text("Notes: ${tx.notes}", style = body)
            GroupValues(diagnosis.groups)
            val dateText = formatPreviewDate(diagnosis.date.millis)
            when (val date = diagnosis.date) {
                is DateResolution.Parsed -> Text("Date: $dateText (read from '${date.text}')", style = body)
                is DateResolution.NotConfigured -> Text("Date: $dateText, the SMS received time", style = body)
                is DateResolution.Fallback -> WarningNote(
                    "Date: $dateText, the SMS received time, because ${date.reason}."
                )
            }
        }
    }
}

@Composable
private fun GroupValues(groups: List<Pair<String, String?>>) {
    if (groups.isEmpty()) return
    Spacer(Modifier.height(2.dp))
    Text("Captured values", style = MaterialTheme.typography.labelMedium)
    groups.forEach { (name, value) ->
        Text(
            if (value == null) "$name: (not found)" else "$name: $value",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    }
}

private fun formatPreviewDate(millis: Long): String =
    PREVIEW_DATE_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

// ─── Field helpers ─────────────────────────────────────────────

/** A text field whose save [error] replaces its [supporting] text. */
@Composable
private fun RuleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    enabled: Boolean = true
) {
    val message = error ?: supporting
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { @Composable { Text(it) } },
        supportingText = message?.let { @Composable { Text(it) } },
        isError = error != null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 5,
        enabled = enabled,
        textStyle = if (monospace) {
            LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
        } else {
            LocalTextStyle.current
        },
        modifier = modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { @Composable { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
