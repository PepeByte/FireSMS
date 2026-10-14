package com.firesms.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.firesms.app.data.local.entity.TitleMappingRule
import com.firesms.app.ui.components.FireSmsAutocompleteField
import com.firesms.app.ui.components.FireSmsListCard

@Composable
internal fun TitleMappingCard(
    rule: TitleMappingRule,
    onToggleEnabled: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    FireSmsListCard(
        onClick = onClick,
        headline = {
            Text(rule.titlePattern, style = MaterialTheme.typography.titleSmall)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggleEnabled() }
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Categorization actions")
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
            if (rule.categoryName.isNotBlank()) {
                Text(
                    "Category: ${rule.categoryName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (rule.budgetName.isNotBlank()) {
                Text(
                    "Budget: ${rule.budgetName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (rule.categoryName.isBlank() && rule.budgetName.isBlank()) {
                Text(
                    "No category or budget set",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TitleMappingEditDialog(
    existingRule: TitleMappingRule?,
    categorySuggestions: List<String>,
    budgetSuggestions: List<String>,
    loadingSuggestions: Boolean,
    onDismiss: () -> Unit,
    onSave: (TitleMappingRule) -> Unit
) {
    var titlePattern by remember { mutableStateOf(existingRule?.titlePattern ?: "") }
    var categoryName by remember { mutableStateOf(existingRule?.categoryName ?: "") }
    var budgetName by remember { mutableStateOf(existingRule?.budgetName ?: "") }
    var priority by remember { mutableStateOf((existingRule?.priority ?: 10).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingRule != null) "Edit mapping" else "Add mapping") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = titlePattern,
                    onValueChange = { titlePattern = it },
                    label = { Text("Title pattern (case-insensitive)") },
                    placeholder = { Text("e.g. Payment to Swiggy") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (loadingSuggestions) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Loading suggestions from Firefly…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                FireSmsAutocompleteField(
                    value = categoryName,
                    onValueChange = { categoryName = it },
                    label = "Category (optional)",
                    suggestions = categorySuggestions,
                    modifier = Modifier.fillMaxWidth()
                )

                FireSmsAutocompleteField(
                    value = budgetName,
                    onValueChange = { budgetName = it },
                    label = "Budget (optional)",
                    suggestions = budgetSuggestions,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it },
                    label = { Text("Priority (lower = checked first)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                if (titlePattern.isNotBlank()) {
                    val prio = priority.toIntOrNull() ?: 10
                    val rule = TitleMappingRule(
                        id = existingRule?.id ?: 0,
                        titlePattern = titlePattern.trim(),
                        categoryName = categoryName.trim(),
                        budgetName = budgetName.trim(),
                        enabled = existingRule?.enabled ?: true,
                        priority = prio,
                        createdAt = existingRule?.createdAt ?: System.currentTimeMillis()
                    )
                    onSave(rule)
                }
            },
                enabled = titlePattern.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
