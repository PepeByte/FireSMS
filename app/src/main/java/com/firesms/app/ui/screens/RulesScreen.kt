package com.firesms.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.data.local.entity.TitleMappingRule
import com.firesms.app.ui.components.FireSmsBottomBar
import com.firesms.app.ui.components.FireSmsConfirmDialog
import com.firesms.app.ui.components.FireSmsEmptyState
import com.firesms.app.ui.components.FireSmsListCard
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.components.FireSmsTopLevelDestination
import com.firesms.app.ui.viewmodels.RulesViewModel
import com.firesms.app.ui.viewmodels.TitleMappingsViewModel

private enum class AutomationTab {
    PARSER_RULES,
    CATEGORIZATION
}

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
    val rules by viewModel.rules.collectAsState()
    val mappings by titleMappingsViewModel.mappings.collectAsState()
    val saveResult by titleMappingsViewModel.saveResult.collectAsState()
    val categorySuggestions by titleMappingsViewModel.categorySuggestions.collectAsState()
    val budgetSuggestions by titleMappingsViewModel.budgetSuggestions.collectAsState()
    val loadingSuggestions by titleMappingsViewModel.loadingSuggestions.collectAsState()

    var selectedTab by rememberSaveable { mutableStateOf(AutomationTab.PARSER_RULES) }
    var pendingRuleDelete by remember { mutableStateOf<ParserRule?>(null) }
    var pendingMappingDelete by remember { mutableStateOf<TitleMappingRule?>(null) }
    var showMappingEditor by remember { mutableStateOf(false) }
    var editingMapping by remember { mutableStateOf<TitleMappingRule?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

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
                    if (selectedTab == AutomationTab.PARSER_RULES) {
                        onNavigateToNew()
                    } else {
                        editingMapping = null
                        showMappingEditor = true
                    }
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = {
                    Text(
                        if (selectedTab == AutomationTab.PARSER_RULES) {
                            "New rule"
                        } else {
                            "New categorization"
                        }
                    )
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedTab == AutomationTab.PARSER_RULES,
                        onClick = { selectedTab = AutomationTab.PARSER_RULES },
                        label = { Text("Parser rules") }
                    )
                    FilterChip(
                        selected = selectedTab == AutomationTab.CATEGORIZATION,
                        onClick = { selectedTab = AutomationTab.CATEGORIZATION },
                        label = { Text("Automatic categorization") }
                    )
                }
            }

            when (selectedTab) {
                AutomationTab.PARSER_RULES -> {
                    items(rules, key = { "rule-${it.id}" }) { rule ->
                        RuleCard(
                            rule = rule,
                            onToggleEnabled = { viewModel.toggleEnabled(rule) },
                            onClick = { onNavigateToEdit(rule.id) },
                            onDelete = { pendingRuleDelete = rule }
                        )
                    }
                    if (rules.isEmpty()) {
                        item {
                            FireSmsEmptyState(
                                title = "No parser rules yet",
                                message = "Create a rule to match bank SMS messages and extract transaction details.",
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
                    items(mappings, key = { "mapping-${it.id}" }) { mapping ->
                        TitleMappingCard(
                            rule = mapping,
                            onToggleEnabled = { titleMappingsViewModel.toggleEnabled(mapping) },
                            onClick = {
                                editingMapping = mapping
                                showMappingEditor = true
                            },
                            onDelete = { pendingMappingDelete = mapping }
                        )
                    }
                    if (mappings.isEmpty()) {
                        item {
                            FireSmsEmptyState(
                                title = "No automatic categorization yet",
                                message = "Assign categories and budgets automatically when a transaction description contains matching text.",
                                action = {
                                    Button(
                                        onClick = {
                                            editingMapping = null
                                            showMappingEditor = true
                                        }
                                    ) {
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
            title = "Delete categorization rule?",
            message = "Transactions containing ${mapping.titlePattern} will no longer be categorized automatically.",
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
            categorySuggestions = categorySuggestions,
            budgetSuggestions = budgetSuggestions,
            loadingSuggestions = loadingSuggestions,
            onDismiss = { showMappingEditor = false },
            onSave = { mapping ->
                titleMappingsViewModel.upsertMapping(mapping)
                showMappingEditor = false
            }
        )
    }
}

@Composable
private fun RuleCard(
    rule: ParserRule,
    onToggleEnabled: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val typeLabel = when (rule.type) {
        "withdrawal" -> "Withdrawal"
        "deposit" -> "Deposit"
        "transfer" -> "Transfer"
        else -> rule.type
    }
    FireSmsListCard(
        onClick = onClick,
        headline = {
            Text(rule.name, style = MaterialTheme.typography.titleSmall)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggleEnabled() }
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Rule actions")
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
                "Sender: ${rule.senderPattern}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "$typeLabel · Priority ${rule.priority}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}
