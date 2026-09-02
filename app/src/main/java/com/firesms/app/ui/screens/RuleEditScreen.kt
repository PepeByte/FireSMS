package com.firesms.app.ui.screens

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.ui.components.FireSmsResultBanner
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.components.FireSmsScreen
import com.firesms.app.domain.rules.ImportedRuleFields
import com.firesms.app.domain.rules.RuleJsonImportResult
import com.firesms.app.ui.components.FireSmsSection
import com.firesms.app.ui.viewmodels.RuleEditViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditScreen(
    ruleId: String?,
    sourceSmsLogId: Long? = null,
    onBack: () -> Unit,
    viewModel: RuleEditViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(ruleId, sourceSmsLogId) {
        if (ruleId != null) {
            viewModel.loadRule(ruleId)
        } else if (sourceSmsLogId != null) {
            viewModel.loadSourceSms(sourceSmsLogId)
        }
    }

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            onBack()
        }
    }

    val clipboard = LocalClipboard.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var showImportDialog by remember { mutableStateOf(false) }
    var importJson by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<ImportedRuleFields?>(null) }
    var pendingImportRepairedEscapes by remember { mutableStateOf(false) }
    var showTestDialog by remember { mutableStateOf(false) }
    var testSender by remember { mutableStateOf("") }
    var testBody by remember { mutableStateOf("") }

    LaunchedEffect(state.sampleSender, state.sampleBody) {
        if (testSender.isBlank()) testSender = state.sampleSender
        if (testBody.isBlank()) testBody = state.sampleBody
    }

    val isNew = ruleId == null

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
            snackbarHostState.showSnackbar(message)
        }
    }

    FireSmsScaffold(
        title = if (isNew) "New parser rule" else "Edit parser rule",
        onBack = onBack,
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
            if (state.saveError != null) {
                FireSmsResultBanner(
                    text = state.saveError!!,
                    isError = true
                )
            }

            if (state.sampleBody.isNotBlank()) {
                FireSmsSection(
                    title = "Message to teach",
                    subtitle = "This message will stay available while you build and test the rule."
                ) {
                    Text(state.sampleSender, style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.sampleBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { showTestDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Preview rule with this message")
                    }
                }
            }

            FireSmsSection(
                title = "Import with AI",
                subtitle = "Copy the generator instructions to your LLM, then paste its JSON response here."
            ) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                val prompt = viewModel.loadGeneratorPrompt().getOrThrow()
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText(
                                            "FireSMS AI rule generator",
                                            prompt
                                        )
                                    )
                                )
                                snackbarHostState.showSnackbar("AI generator instructions copied")
                            } catch (_: Exception) {
                                snackbarHostState.showSnackbar("Could not copy generator instructions")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Copy AI generator instructions")
                }
                Button(
                    onClick = {
                        importError = null
                        showImportDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Load rule JSON")
                }
                Text(
                    "SMS messages may contain sensitive financial information. Review or redact personal values before sharing them with an external AI service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FireSmsSection(title = "Rule identity") {
                OutlinedTextField(
                    value = state.id,
                    onValueChange = { viewModel.updateId(it) },
                    label = { Text("Rule ID (optional)") },
                    placeholder = { Text("Generated automatically when left blank") },
                    singleLine = true,
                    enabled = isNew,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { viewModel.updateName(it) },
                    label = { Text("Rule name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.senderPattern,
                    onValueChange = { viewModel.updateSenderPattern(it) },
                    label = { Text("Sender pattern (regex)") },
                    placeholder = { Text("Regex to match SMS sender address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.bodyPattern,
                    onValueChange = { viewModel.updateBodyPattern(it) },
                    label = { Text("Body pattern (regex)") },
                    placeholder = { Text("Regex with named groups to extract data from SMS body") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FireSmsSection(title = "Transaction mapping") {
                OutlinedTextField(
                    value = state.amountTemplate,
                    onValueChange = { viewModel.updateAmountTemplate(it) },
                    label = { Text("Amount template") },
                    placeholder = { Text("{{amount}}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val descSources = listOf("template", "regex_group")
                val descSourceLabels = mapOf("template" to "Template", "regex_group" to "Regex group")
                var descSourceExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = descSourceExpanded,
                    onExpandedChange = { descSourceExpanded = it }
                ) {
                    OutlinedTextField(
                        value = descSourceLabels[state.descriptionSource] ?: state.descriptionSource,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Description source") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = descSourceExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                    )
                    ExposedDropdownMenu(expanded = descSourceExpanded, onDismissRequest = { descSourceExpanded = false }) {
                        descSources.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(descSourceLabels[source] ?: source) },
                                onClick = {
                                    viewModel.updateDescriptionSource(source)
                                    descSourceExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.descriptionValue,
                    onValueChange = { viewModel.updateDescriptionValue(it) },
                    label = { Text("Description value") },
                    placeholder = { Text("Group name or static text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val txTypeLabels = mapOf("withdrawal" to "Withdrawal", "deposit" to "Deposit", "transfer" to "Transfer")
                val txTypes = listOf("withdrawal", "deposit", "transfer")
                var txTypeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = txTypeExpanded,
                    onExpandedChange = { txTypeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = txTypeLabels[state.type] ?: state.type,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Transaction type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = txTypeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                    )
                    ExposedDropdownMenu(expanded = txTypeExpanded, onDismissRequest = { txTypeExpanded = false }) {
                        txTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(txTypeLabels[type] ?: type) },
                                onClick = {
                                    viewModel.updateType(type)
                                    txTypeExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.priority.toString(),
                    onValueChange = { it.toIntOrNull()?.let { n -> viewModel.updatePriority(n) } },
                    label = { Text("Priority") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FireSmsSection(title = "Accounts") {
                OutlinedTextField(
                    value = state.sourceAccountKeyword ?: "",
                    onValueChange = { viewModel.updateSourceAccountKeyword(it.ifBlank { null }) },
                    label = { Text("Source account keyword") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                var sourceAcctExpanded by remember { mutableStateOf(false) }
                val filteredAccounts = remember(state.accounts, state.sourceAccountName) {
                    val query = state.sourceAccountName ?: ""
                    if (query.isBlank()) state.accounts
                    else state.accounts.filter { it.attributes.name.contains(query, ignoreCase = true) }
                }
                Box {
                    OutlinedTextField(
                        value = state.sourceAccountName ?: "",
                        onValueChange = {
                            viewModel.selectSourceAccount(it, "")
                            sourceAcctExpanded = it.isNotEmpty()
                            if (state.accounts.isEmpty()) viewModel.fetchAccounts()
                        },
                        label = { Text("Source account name") },
                        placeholder = { Text("Type to search Firefly accounts") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = sourceAcctExpanded && filteredAccounts.isNotEmpty(),
                        onDismissRequest = { sourceAcctExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        filteredAccounts.take(15).forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.attributes.name) },
                                onClick = {
                                    viewModel.selectSourceAccount(account.attributes.name, account.id)
                                    sourceAcctExpanded = false
                                }
                            )
                        }
                    }
                    if (state.accountsLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter))
                    }
                }
                OutlinedTextField(
                    value = state.destinationAccountName ?: "",
                    onValueChange = { viewModel.updateDestinationAccountName(it.ifBlank { null }) },
                    label = { Text("Destination account name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FireSmsSection(
                title = "Date extraction",
                subtitle = "Optional"
            ) {
                OutlinedTextField(
                    value = state.datePattern,
                    onValueChange = { viewModel.updateDatePattern(it) },
                    label = { Text("Date pattern") },
                    placeholder = { Text("{{date_group}} or regex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.dateFormat,
                    onValueChange = { viewModel.updateDateFormat(it) },
                    label = { Text("Date format") },
                    placeholder = { Text("dd/MM/yy, yyyy-MM-dd'T'HH:mm:ss") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FireSmsSection(
                title = "Remarks extraction",
                subtitle = "Optional"
            ) {
                OutlinedTextField(
                    value = state.remarksPattern,
                    onValueChange = { viewModel.updateRemarksPattern(it) },
                    label = { Text("Remarks pattern") },
                    placeholder = { Text("Regex to extract remarks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.remarksTemplate,
                    onValueChange = { viewModel.updateRemarksTemplate(it) },
                    label = { Text("Remarks template") },
                    placeholder = { Text("{{remark_group}} or custom text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FireSmsSection(title = "Test rule") {
                Button(
                    onClick = { showTestDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test rule")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Load rule JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste the JSON generated by your LLM. Only the body, amount, description, type, date, and remarks fields will be changed.",
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
                        placeholder = { Text("{ \"bodyPattern\": \"...\" }") },
                        minLines = 7,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (importError != null) {
                        FireSmsResultBanner(
                            text = importError!!,
                            isError = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (val result = viewModel.parseRuleJson(importJson)) {
                            is RuleJsonImportResult.Error -> importError = result.message
                            is RuleJsonImportResult.Success -> {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                pendingImport = result.fields
                                pendingImportRepairedEscapes = result.repairedInvalidEscapes
                                showImportDialog = false
                            }
                        }
                    },
                    enabled = importJson.isNotBlank()
                ) { Text("Load") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showTestDialog) {
        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = { Text("Test parser rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = testSender,
                        onValueChange = { testSender = it },
                        label = { Text("SMS sender") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = testBody,
                        onValueChange = { testBody = it },
                        label = { Text("SMS body") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.testResult != null) {
                        FireSmsResultBanner(
                            text = state.testResult!!,
                            isError = state.testResult!!.startsWith("No match", ignoreCase = true) ||
                                state.testResult!!.contains("error", ignoreCase = true) ||
                                state.testResult!!.contains("add a", ignoreCase = true)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.testRule(testSender, testBody) },
                    enabled = testSender.isNotBlank() && testBody.isNotBlank() && !state.testing
                ) { Text(if (state.testing) "Testing…" else "Run test") }
            },
            dismissButton = {
                TextButton(onClick = { showTestDialog = false }) { Text("Close") }
            }
        )
    }
}
