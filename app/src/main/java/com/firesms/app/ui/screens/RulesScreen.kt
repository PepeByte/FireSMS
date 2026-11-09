package com.firesms.app.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.data.local.entity.TitleMappingRule
import com.firesms.app.domain.rules.SenderPatterns
import com.firesms.app.ui.components.FireSmsBottomBar
import com.firesms.app.ui.components.FireSmsConfirmDialog
import com.firesms.app.ui.components.FireSmsEmptyState
import com.firesms.app.ui.components.FireSmsListCard
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.components.FireSmsTopLevelDestination
import com.firesms.app.ui.viewmodels.RulesViewModel
import com.firesms.app.ui.viewmodels.TitleMappingsViewModel

private enum class AutomationTab(val label: String) {
    PARSER_RULES("Parser rules"),
    CATEGORIZATION("Categorization")
}

private val TYPE_LABELS = mapOf("withdrawal" to "Withdrawal", "deposit" to "Deposit", "transfer" to "Transfer")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    onNavigateToEdit: (String) -> Unit,
    onNavigateToNew: () -> Unit,
    onNavigateToActivity: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: RulesViewModel = viewModel(),
    titleMappingsViewModel: TitleMappingsViewModel = viewModel()
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val mappings by titleMappingsViewModel.mappings.collectAsStateWithLifecycle()
    val saveResult by titleMappingsViewModel.saveResult.collectAsStateWithLifecycle()
    val suggestions by titleMappingsViewModel.suggestions.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(AutomationTab.PARSER_RULES) }
    var pendingRuleDelete by remember { mutableStateOf<ParserRule?>(null) }
    var pendingMappingDelete by remember { mutableStateOf<TitleMappingRule?>(null) }
    var showMappingEditor by remember { mutableStateOf(false) }
    var editingMapping by remember { mutableStateOf<TitleMappingRule?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun openMappingEditor(mapping: TitleMappingRule?) {
        editingMapping = mapping
        showMappingEditor = true
        // Suggestions are only needed in the editor, so fetch them on first use.
        titleMappingsViewModel.ensureSuggestionsLoaded()
    }

    LaunchedEffect(saveResult) {
        saveResult?.let { message ->
            snackbarHostState.showSnackbar(message)
            titleMappingsViewModel.clearSaveResult()
        }
    }

    FireSmsScaffold(
        title = "Automation",
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (selectedTab == AutomationTab.PARSER_RULES) onNavigateToNew() else openMappingEditor(null)
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = {
                    Text(if (selectedTab == AutomationTab.PARSER_RULES) "New rule" else "New categorization")
                }
            )
        },
        bottomBar = {
            FireSmsBottomBar(
                selected = FireSmsTopLevelDestination.AUTOMATION,
                onActivity = onNavigateToActivity,
                onAutomation = {},
                onSettings = onNavigateToSettings
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                AutomationTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        // Tab text wraps instead of clipping at large font sizes.
                        text = { Text(tab.label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // The bottom padding keeps the last card clear of the floating button.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (selectedTab) {
                    AutomationTab.PARSER_RULES -> {
                        if (rules.isNotEmpty()) {
                            item(key = "rules-help") {
                                ListExplanation(
                                    "Checked from the lowest priority number; the first match wins."
                                )
                            }
                        }
                        items(rules, key = { "rule-${it.id}" }) { rule ->
                            RuleCard(
                                rule = rule,
                                onToggleEnabled = { viewModel.toggleEnabled(rule) },
                                onClick = { onNavigateToEdit(rule.id) },
                                onDelete = { pendingRuleDelete = rule }
                            )
                        }
                        if (rules.isEmpty()) {
                            item(key = "rules-empty") {
                                FireSmsEmptyState(
                                    title = "No parser rules yet",
                                    message = "Create a rule to match bank SMS messages and extract transaction details.",
                                    icon = Icons.Outlined.Sms,
                                    action = {
                                        Button(onClick = onNavigateToNew) {
                                            Text("Create rule")
                                        }
                                    }
                                )
                            }
                        }
                    }

                    AutomationTab.CATEGORIZATION -> {
                        if (mappings.isNotEmpty()) {
                            item(key = "mappings-help") {
                                ListExplanation(
                                    "Checked from the lowest priority number; the first match wins. A mapping matches when the transaction description contains its text."
                                )
                            }
                        }
                        items(mappings, key = { "mapping-${it.id}" }) { mapping ->
                            TitleMappingCard(
                                rule = mapping,
                                onToggleEnabled = { titleMappingsViewModel.toggleEnabled(mapping) },
                                onClick = { openMappingEditor(mapping) },
                                onDelete = { pendingMappingDelete = mapping }
                            )
                        }
                        if (mappings.isEmpty()) {
                            item(key = "mappings-empty") {
                                FireSmsEmptyState(
                                    title = "No automatic categorization yet",
                                    message = "Assign categories and budgets automatically when a transaction description contains matching text.",
                                    icon = Icons.Outlined.Category,
                                    action = {
                                        Button(onClick = { openMappingEditor(null) }) {
                                            Text("Create categorization")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingRuleDelete?.let { rule ->
        FireSmsConfirmDialog(
            title = "Delete parser rule?",
            message = "${rule.name} will no longer be available to process matching messages.",
            confirmText = "Delete",
            onConfirm = {
                viewModel.deleteRule(rule)
                pendingRuleDelete = null
            },
            onDismiss = { pendingRuleDelete = null }
        )
    }

    pendingMappingDelete?.let { mapping ->
        FireSmsConfirmDialog(
            title = "Delete categorization?",
            message = "Transactions containing \"${mapping.titlePattern}\" will no longer be categorized automatically.",
            confirmText = "Delete",
            onConfirm = {
                titleMappingsViewModel.deleteMapping(mapping)
                pendingMappingDelete = null
            },
            onDismiss = { pendingMappingDelete = null }
        )
    }

    if (showMappingEditor) {
        TitleMappingEditDialog(
            existingRule = editingMapping,
            suggestions = suggestions,
            onRetrySuggestions = titleMappingsViewModel::retrySuggestions,
            onDismiss = { showMappingEditor = false },
            onSave = { mapping ->
                titleMappingsViewModel.upsertMapping(mapping)
                showMappingEditor = false
            }
        )
    }
}

@Composable
private fun ListExplanation(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun RuleCard(
    rule: ParserRule,
    onToggleEnabled: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val typeLabel = TYPE_LABELS[rule.type] ?: rule.type
    val sender = remember(rule.senderPattern) { SenderPatterns.describe(rule.senderPattern) }
    FireSmsListCard(
        onClick = onClick,
        headline = {
            Text(rule.name, style = MaterialTheme.typography.titleSmall)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    modifier = Modifier.semantics { contentDescription = "Enable ${rule.name}" }
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${rule.name}")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        },
        supporting = {
            Text(
                "From $sender",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$typeLabel · Priority ${rule.priority}" + if (rule.enabled) "" else " · Off",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}
