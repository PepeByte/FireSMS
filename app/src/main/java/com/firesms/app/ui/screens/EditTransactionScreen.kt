package com.firesms.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import com.firesms.app.ui.components.FireSmsAutocompleteField
import com.firesms.app.ui.components.FireSmsResultBanner
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.viewmodels.EditRowState
import com.firesms.app.ui.viewmodels.EditTransactionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(
    smsLogId: Long?,
    onBack: () -> Unit,
    viewModel: EditTransactionViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAdvanced by remember { mutableStateOf(false) }
    val currencyLabel = state.currencySymbol.ifBlank { state.currencyCode }

    LaunchedEffect(smsLogId) {
        viewModel.load(smsLogId)
    }

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            viewModel.clearSaveSuccess()
            onBack()
        }
    }

    FireSmsScaffold(
        title = "Edit transaction",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            if (!state.loading && state.error == null) {
                TextButton(
                    onClick = { viewModel.save() },
                    enabled = !state.saving
                ) {
                    Text(if (state.saving) "Saving..." else "Save")
                }
            }
        }
    ) { padding ->
        when {
            state.loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Loading transaction…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            state.error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Couldn\u2019t load transaction",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.error ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) {
                        Text("Back")
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    state.saveError?.let { error ->
                        FireSmsResultBanner(text = error, isError = true)
                    }

                    // ── Group title — only for split transactions ──
                    if (state.rows.size > 1) {
                        FireSmsAutocompleteField(
                            value = state.groupTitle,
                            onValueChange = { v -> viewModel.updateTransactionField { it.copy(groupTitle = v) } },
                            label = "Group Title",
                            suggestions = state.descriptionSuggestions,
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                    }

                    // ── Advanced toggle ──
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAdvanced = !showAdvanced }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Transaction details",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Icon(
                                if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle details"
                            )
                        }

                        AnimatedVisibility(
                            visible = showAdvanced,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column(
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = state.originalTotal,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Total") },
                                        prefix = currencyLabel.takeIf { it.isNotBlank() }?.let { label ->
                                            { Text(label) }
                                        },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Date field with pickers
                                    var showDatePicker by remember { mutableStateOf(false) }
                                    var showTimePicker by remember { mutableStateOf(false) }
                                    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }

                                    val dateDisplayText = remember(state.transactionDate) {
                                        if (state.transactionDate.isNotBlank()) {
                                            try {
                                                LocalDateTime.parse(state.transactionDate, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                                            } catch (_: Exception) {
                                                state.transactionDate
                                            }
                                        } else ""
                                    }

                                    Box(modifier = Modifier.weight(1f)) {
                                        OutlinedTextField(
                                            value = dateDisplayText,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("Date") },
                                            leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clickable { showDatePicker = true }
                                        )
                                    }

                                    if (showDatePicker) {
                                        val datePickerState = rememberDatePickerState(
                                            initialSelectedDateMillis = try {
                                                LocalDateTime.parse(state.transactionDate, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                            } catch (_: Exception) { System.currentTimeMillis() }
                                        )
                                        DatePickerDialog(
                                            onDismissRequest = { showDatePicker = false },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    selectedDateMillis = datePickerState.selectedDateMillis
                                                    showDatePicker = false
                                                    showTimePicker = true
                                                }) { Text("OK") }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                                            }
                                        ) {
                                            DatePicker(state = datePickerState)
                                        }
                                    }

                                    if (showTimePicker && selectedDateMillis != null) {
                                        val timePickerState = rememberTimePickerState(
                                            initialHour = try {
                                                LocalDateTime.parse(state.transactionDate, DateTimeFormatter.ISO_LOCAL_DATE_TIME).hour
                                            } catch (_: Exception) { 12 },
                                            initialMinute = try {
                                                LocalDateTime.parse(state.transactionDate, DateTimeFormatter.ISO_LOCAL_DATE_TIME).minute
                                            } catch (_: Exception) { 0 }
                                        )
                                        AlertDialog(
                                            onDismissRequest = { showTimePicker = false },
                                            title = { Text("Select time") },
                                            text = { TimePicker(state = timePickerState) },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    val date = Instant.ofEpochMilli(selectedDateMillis!!)
                                                        .atZone(ZoneId.systemDefault())
                                                        .toLocalDate()
                                                    val time = LocalTime.of(timePickerState.hour, timePickerState.minute)
                                                    val dateTime = LocalDateTime.of(date, time)
                                                    val formatted = dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                                    viewModel.updateTransactionField { it.copy(transactionDate = formatted) }
                                                    showTimePicker = false
                                                }) { Text("OK") }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                                            }
                                        )
                                    }
                                }

                                FireSmsAutocompleteField(
                                    value = state.sourceName,
                                    onValueChange = { v -> viewModel.updateTransactionField { it.copy(sourceName = v, sourceId = null) } },
                                    label = "Source Account",
                                    suggestions = state.accountSuggestions,
                                    modifier = Modifier.fillMaxWidth(),
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
                                )

                                FireSmsAutocompleteField(
                                    value = state.destinationName,
                                    onValueChange = { v -> viewModel.updateTransactionField { it.copy(destinationName = v, destinationId = null) } },
                                    label = "Destination Account",
                                    suggestions = state.accountSuggestions,
                                    modifier = Modifier.fillMaxWidth(),
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                                )

                                // Type dropdown
                                var typeExpanded by remember { mutableStateOf(false) }
                                val types = listOf("withdrawal", "deposit", "transfer")
                                ExposedDropdownMenuBox(
                                    expanded = typeExpanded,
                                    onExpandedChange = { typeExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = state.transactionType.replaceFirstChar { it.uppercase() },
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Type") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        singleLine = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = typeExpanded,
                                        onDismissRequest = { typeExpanded = false }
                                    ) {
                                        types.forEach { t ->
                                            DropdownMenuItem(
                                                text = { Text(t.replaceFirstChar { it.uppercase() }) },
                                                onClick = {
                                                    viewModel.updateTransactionField { it.copy(transactionType = t) }
                                                    typeExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Transactions",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // ── Split cards ──
                    state.rows.forEachIndexed { index, row ->
                        SplitCard(
                            index = index,
                            row = row,
                            categorySuggestions = state.categorySuggestions,
                            budgetSuggestions = state.budgetSuggestions,
                            descriptionSuggestions = state.descriptionSuggestions,
                            canRemove = state.rows.size > 1,
                            currencyLabel = currencyLabel,
                            onUpdate = { viewModel.updateRow(index, it) },
                            onRemove = { viewModel.removeRow(index) }
                        )
                    }

                    // Add split button at bottom
                    OutlinedButton(
                        onClick = { viewModel.addRow() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add split transaction")
                    }

                    if (state.saving) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitCard(
    index: Int,
    row: EditRowState,
    categorySuggestions: List<String>,
    budgetSuggestions: List<String>,
    descriptionSuggestions: List<String>,
    canRemove: Boolean,
    currencyLabel: String,
    onUpdate: (EditRowState) -> Unit,
    onRemove: () -> Unit
) {
    val title = if (index == 0) "Transaction" else "Split ${index + 1}"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title row with delete button inline
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canRemove) {
                        IconButton(
                            onClick = onRemove,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove $title",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Description first
            FireSmsAutocompleteField(
                value = row.description,
                onValueChange = { onUpdate(row.copy(description = it)) },
                label = "Description",
                suggestions = descriptionSuggestions,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
            )

            // Amount below description
            OutlinedTextField(
                value = row.amount,
                onValueChange = { onUpdate(row.copy(amount = it)) },
                label = { Text("Amount") },
                prefix = currencyLabel.takeIf { it.isNotBlank() }?.let { label ->
                    { Text(label) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            FireSmsAutocompleteField(
                value = row.categoryName,
                onValueChange = { onUpdate(row.copy(categoryName = it)) },
                label = "Category (optional)",
                suggestions = categorySuggestions,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }
            )

            FireSmsAutocompleteField(
                value = row.budgetName,
                onValueChange = { onUpdate(row.copy(budgetName = it)) },
                label = "Budget (optional)",
                suggestions = budgetSuggestions,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) }
            )

            OutlinedTextField(
                value = row.notes,
                onValueChange = { onUpdate(row.copy(notes = it)) },
                label = { Text("Notes (optional)") },
                leadingIcon = { Icon(Icons.Default.Menu, contentDescription = null) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
