package com.firesms.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.firesms.app.data.local.entity.TitleMappingRule
import com.firesms.app.ui.components.FireSmsAutocompleteField
import com.firesms.app.ui.components.FireSmsListCard
import com.firesms.app.ui.components.rememberUnsavedChangesGuard
import com.firesms.app.ui.viewmodels.MappingSuggestions

private const val DEFAULT_MAPPING_PRIORITY = 10
private val MAPPING_PRIORITY_INPUT = Regex("-?[0-9]{0,6}")

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
            Text("Contains \"${rule.titlePattern}\"", style = MaterialTheme.typography.titleSmall)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    modifier = Modifier.semantics {
                        contentDescription = "Enable categorization for ${rule.titlePattern}"
                    }
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Actions for categorization ${rule.titlePattern}"
                        )
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
            Text(
                "Priority ${rule.priority}" + if (rule.enabled) "" else " · Off",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
internal fun TitleMappingEditDialog(
    existingRule: TitleMappingRule?,
    suggestions: MappingSuggestions,
    onRetrySuggestions: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (TitleMappingRule) -> Unit
) {
    val initialPattern = existingRule?.titlePattern.orEmpty()
    val initialCategory = existingRule?.categoryName.orEmpty()
    val initialBudget = existingRule?.budgetName.orEmpty()
    val initialPriority = (existingRule?.priority ?: DEFAULT_MAPPING_PRIORITY).toString()

    var titlePattern by remember { mutableStateOf(initialPattern) }
    var categoryName by remember { mutableStateOf(initialCategory) }
    var budgetName by remember { mutableStateOf(initialBudget) }
    var priority by remember { mutableStateOf(initialPriority) }
    // Errors appear after the first save attempt, then follow the fields as they change.
    var showErrors by remember { mutableStateOf(false) }

    val isDirty = titlePattern != initialPattern || categoryName != initialCategory ||
        budgetName != initialBudget || priority != initialPriority
    val guardedDismiss = rememberUnsavedChangesGuard(isDirty = isDirty, onBack = onDismiss)

    val patternError = if (showErrors && titlePattern.isBlank()) "Enter the text to look for" else null
    val targetError = if (showErrors && categoryName.isBlank() && budgetName.isBlank()) {
        "Choose a category or a budget"
    } else null
    val priorityValue = priority.toIntOrNull()

    AlertDialog(
        // Back asks before discarding edits; a stray tap outside is ignored while there are edits.
        onDismissRequest = guardedDismiss,
        properties = DialogProperties(dismissOnClickOutside = !isDirty),
        title = { Text(if (existingRule != null) "Edit categorization" else "New categorization") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = titlePattern,
                    onValueChange = { titlePattern = it },
                    label = { Text("Description contains") },
                    placeholder = { Text("e.g. Swiggy") },
                    isError = patternError != null,
                    supportingText = { Text(patternError ?: "Not case-sensitive") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                SuggestionsStatus(suggestions, onRetrySuggestions)

                FireSmsAutocompleteField(
                    value = categoryName,
                    onValueChange = { categoryName = it },
                    label = "Category",
                    suggestions = suggestions.categories,
                    isError = targetError != null,
                    supportingText = targetError,
                    modifier = Modifier.fillMaxWidth()
                )

                FireSmsAutocompleteField(
                    value = budgetName,
                    onValueChange = { budgetName = it },
                    label = "Budget",
                    suggestions = suggestions.budgets,
                    isError = targetError != null,
                    supportingText = targetError ?: "Set a category, a budget or both",
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = priority,
                    onValueChange = { text ->
                        val trimmed = text.trim()
                        if (MAPPING_PRIORITY_INPUT.matches(trimmed)) priority = trimmed
                    },
                    label = { Text("Priority") },
                    isError = priorityValue == null,
                    supportingText = {
                        Text(if (priorityValue == null) "Enter a whole number, e.g. 10" else "Lower numbers are checked first")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    showErrors = true
                    val valid = titlePattern.isNotBlank() &&
                        (categoryName.isNotBlank() || budgetName.isNotBlank()) &&
                        priorityValue != null
                    if (valid) {
                        onSave(
                            TitleMappingRule(
                                id = existingRule?.id ?: 0,
                                titlePattern = titlePattern.trim(),
                                categoryName = categoryName.trim(),
                                budgetName = budgetName.trim(),
                                enabled = existingRule?.enabled ?: true,
                                priority = priorityValue ?: DEFAULT_MAPPING_PRIORITY,
                                createdAt = existingRule?.createdAt ?: System.currentTimeMillis()
                            )
                        )
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = guardedDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SuggestionsStatus(suggestions: MappingSuggestions, onRetry: () -> Unit) {
    when {
        suggestions.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Loading suggestions from Firefly…", style = MaterialTheme.typography.bodySmall)
        }
        suggestions.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Couldn't load suggestions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        suggestions.notConnected -> Text(
            "Connect Firefly III in Settings to get category and budget suggestions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
