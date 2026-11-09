package com.firesms.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Output
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.ui.components.FireSmsAutocompleteField
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.components.FireSmsScreen
import com.firesms.app.ui.components.fireSmsCardBorder
import com.firesms.app.ui.components.fireSmsCardColors
import com.firesms.app.ui.components.rememberUnsavedChangesGuard
import com.firesms.app.ui.theme.FireSmsTheme
import com.firesms.app.ui.viewmodels.EditRowState
import com.firesms.app.ui.viewmodels.EditTransactionViewModel
import com.firesms.app.ui.viewmodels.SplitAllocation
import com.firesms.app.ui.viewmodels.allocationOf
import com.firesms.app.ui.viewmodels.allocationText
import com.firesms.app.ui.viewmodels.formatMoney
import com.firesms.app.ui.viewmodels.parseEditAmount
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TRANSACTION_TYPES = listOf("withdrawal", "deposit", "transfer")
private val DATE_DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val UNKNOWN_ACCOUNT_HINT = "No account with this name — Firefly may create a new one."
private val AMOUNT_KEYBOARD = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
private val TEXT_KEYBOARD = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)

@Composable
fun EditTransactionScreen(
    smsLogId: Long?,
    onBack: () -> Unit,
    viewModel: EditTransactionViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    // The split whose amount should take focus once it is on screen.
    var focusRowKey by remember { mutableStateOf<Long?>(null) }

    val guardedBack = rememberUnsavedChangesGuard(isDirty = state.isDirty && !state.saving, onBack = onBack)

    LaunchedEffect(smsLogId) {
        viewModel.load(smsLogId)
    }

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            viewModel.clearSaveSuccess()
            onBack()
        }
    }

    LaunchedEffect(state.saveError) {
        val message = state.saveError ?: return@LaunchedEffect
        val detail = state.saveErrorDetail
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = if (detail != null) "Details" else null,
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) errorDetail = detail
        viewModel.clearSaveError()
    }

    val removedSplit = state.removedSplit
    LaunchedEffect(removedSplit?.id) {
        val removal = removedSplit ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Split removed",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove(removal)
        else viewModel.clearRemovedSplit(removal)
    }

    errorDetail?.let { detail ->
        AlertDialog(
            onDismissRequest = { errorDetail = null },
            title = { Text("Error details") },
            text = {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = { TextButton(onClick = { errorDetail = null }) { Text("Close") } }
        )
    }

    val showForm = !state.loading && state.error == null
    val isSplit = state.rows.size > 1
    val currencyLabel = state.currencyLabel
    // Only recomputed, and only re-rendered, when an amount or the total actually changes.
    val allocation by remember {
        derivedStateOf {
            if (state.rows.size > 1) allocationOf(state.originalTotal, state.rows.map { it.amount }) else null
        }
    }
    val accountNames = remember(state.accountsLoaded, state.accountSuggestions) {
        if (state.accountsLoaded) state.accountSuggestions.mapTo(HashSet()) { it.trim().lowercase() } else null
    }

    FireSmsScaffold(
        title = "Edit transaction",
        onBack = guardedBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showForm) {
                SaveBar(
                    allocation = allocation,
                    currencyLabel = currencyLabel,
                    saving = state.saving,
                    onSave = viewModel::save
                )
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
                        "Couldn’t load transaction",
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
                FireSmsScreen(
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TotalCard(
                        total = state.originalTotal,
                        currencyLabel = currencyLabel,
                        splitCount = state.rows.size
                    )

                    if (isSplit) {
                        FireSmsAutocompleteField(
                            value = state.groupTitle,
                            onValueChange = viewModel::setGroupTitle,
                            label = "Group title",
                            suggestions = state.descriptionSuggestions,
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Outlined.Title, contentDescription = null) },
                            isError = state.groupTitleError != null,
                            supportingText = state.groupTitleError ?: "Names the whole split transaction",
                            keyboardOptions = TEXT_KEYBOARD
                        )
                    }

                    DetailsCard(
                        transactionType = state.transactionType,
                        transactionDate = state.transactionDate,
                        sourceName = state.sourceName,
                        destinationName = state.destinationName,
                        accountSuggestions = state.accountSuggestions,
                        accountNames = accountNames,
                        onTypeChange = viewModel::setTransactionType,
                        onDateChange = viewModel::setTransactionDate,
                        onSourceChange = viewModel::setSourceName,
                        onDestinationChange = viewModel::setDestinationName
                    )

                    if (isSplit) {
                        Text("Splits", style = MaterialTheme.typography.titleMedium)
                    }

                    state.rows.forEachIndexed { index, row ->
                        key(row.key) {
                            SplitCard(
                                title = if (isSplit) "Split ${index + 1}" else "Transaction",
                                row = row,
                                categorySuggestions = state.categorySuggestions,
                                budgetSuggestions = state.budgetSuggestions,
                                descriptionSuggestions = state.descriptionSuggestions,
                                canRemove = isSplit,
                                currencyLabel = currencyLabel,
                                requestAmountFocus = focusRowKey == row.key,
                                onAmountFocused = { focusRowKey = null },
                                onUpdate = { viewModel.updateRow(index, it) },
                                onRemove = { viewModel.removeRow(index) }
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val newIndex = viewModel.addRow()
                            focusRowKey = viewModel.state.value.rows.getOrNull(newIndex)?.key
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add split")
                    }
                }
            }
        }
    }
}

// ─── Sections ──────────────────────────────────────────────────

@Composable
private fun TotalCard(total: String, currencyLabel: String, splitCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Total", style = MaterialTheme.typography.labelLarge)
            Text(
                parseEditAmount(total)?.let { formatMoney(it, currencyLabel) } ?: total,
                style = MaterialTheme.typography.headlineSmall
            )
            if (splitCount > 1) {
                Text(
                    "Split into $splitCount parts",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AllocationLine(allocation: SplitAllocation, currencyLabel: String) {
    val color = if (allocation.isBalanced) FireSmsTheme.colors.success else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (allocation.isBalanced) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Text(
            allocationText(allocation, currencyLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}

@Composable
private fun SaveBar(
    allocation: SplitAllocation?,
    currencyLabel: String,
    saving: Boolean,
    onSave: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (allocation != null) {
                    AllocationLine(allocation, currencyLabel)
                }
                Button(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Saving…")
                    } else {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsCard(
    transactionType: String,
    transactionDate: String,
    sourceName: String,
    destinationName: String,
    accountSuggestions: List<String>,
    accountNames: Set<String>?,
    onTypeChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onSourceChange: (String) -> Unit,
    onDestinationChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = fireSmsCardColors(),
        border = fireSmsCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Details", style = MaterialTheme.typography.titleMedium)

            DateField(value = transactionDate, onChange = onDateChange)

            FireSmsAutocompleteField(
                value = sourceName,
                onValueChange = onSourceChange,
                label = "Source account",
                suggestions = accountSuggestions,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Outlined.Output, contentDescription = null) },
                supportingText = unknownAccountHint(sourceName, accountNames)
            )

            FireSmsAutocompleteField(
                value = destinationName,
                onValueChange = onDestinationChange,
                label = "Destination account",
                suggestions = accountSuggestions,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Input, contentDescription = null) },
                supportingText = unknownAccountHint(destinationName, accountNames)
            )

            var typeExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = typeExpanded,
                onExpandedChange = { typeExpanded = it }
            ) {
                OutlinedTextField(
                    value = transactionType.replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type") },
                    leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                )
                ExposedDropdownMenu(
                    expanded = typeExpanded,
                    onDismissRequest = { typeExpanded = false }
                ) {
                    TRANSACTION_TYPES.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                onTypeChange(type)
                                typeExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun unknownAccountHint(name: String, accountNames: Set<String>?): String? {
    val wanted = name.trim().lowercase()
    if (accountNames == null || wanted.isEmpty() || wanted in accountNames) return null
    return UNKNOWN_ACCOUNT_HINT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(value: String, onChange: (String) -> Unit) {
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    val parsedDate = remember(value) { parseFireflyDate(value) }
    val displayText = parsedDate?.format(DATE_DISPLAY_FORMAT) ?: value

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("Date") },
            leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // A read-only field swallows taps; this overlay opens the picker instead.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClickLabel = "Change date") { showDatePicker = true }
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            // DatePicker works in UTC midnights.
            initialSelectedDateMillis = (parsedDate?.toLocalDate() ?: LocalDate.now())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedDateMillis = datePickerState.selectedDateMillis
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    selectedDateMillis?.let { dateMillis ->
        val timePickerState = rememberTimePickerState(
            initialHour = parsedDate?.hour ?: 12,
            initialMinute = parsedDate?.minute ?: 0
        )
        fun finish(time: LocalTime?) {
            onChange(pickedDateTime(dateMillis, time, parsedDate))
            selectedDateMillis = null
        }
        AlertDialog(
            // Skipping the time still applies the picked date, keeping the original time.
            onDismissRequest = { finish(null) },
            title = { Text("Select time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = { finish(LocalTime.of(timePickerState.hour, timePickerState.minute)) }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { finish(null) }) { Text("Keep time") }
            }
        )
    }
}

@Composable
private fun SplitCard(
    title: String,
    row: EditRowState,
    categorySuggestions: List<String>,
    budgetSuggestions: List<String>,
    descriptionSuggestions: List<String>,
    canRemove: Boolean,
    currencyLabel: String,
    requestAmountFocus: Boolean,
    onAmountFocused: () -> Unit,
    onUpdate: (EditRowState) -> Unit,
    onRemove: () -> Unit
) {
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(requestAmountFocus) {
        if (requestAmountFocus) {
            amountFocus.requestFocus()
            onAmountFocused()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = fireSmsCardColors(),
        border = fireSmsCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Remove $title",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FireSmsAutocompleteField(
                    value = row.description,
                    onValueChange = { onUpdate(row.copy(description = it)) },
                    label = "Description",
                    suggestions = descriptionSuggestions,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    keyboardOptions = TEXT_KEYBOARD
                )

                OutlinedTextField(
                    value = row.amount,
                    onValueChange = { onUpdate(row.copy(amount = it)) },
                    label = { Text("Amount") },
                    prefix = currencyLabel.takeIf { it.isNotBlank() }?.let { label ->
                        { Text(label) }
                    },
                    singleLine = true,
                    isError = row.amountError != null,
                    supportingText = row.amountError?.let { error -> { Text(error) } },
                    keyboardOptions = AMOUNT_KEYBOARD,
                    modifier = Modifier.fillMaxWidth().focusRequester(amountFocus)
                )

                FireSmsAutocompleteField(
                    value = row.categoryName,
                    onValueChange = { onUpdate(row.copy(categoryName = it)) },
                    label = "Category (optional)",
                    suggestions = categorySuggestions,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Category, contentDescription = null) }
                )

                FireSmsAutocompleteField(
                    value = row.budgetName,
                    onValueChange = { onUpdate(row.copy(budgetName = it)) },
                    label = "Budget (optional)",
                    suggestions = budgetSuggestions,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null) }
                )

                OutlinedTextField(
                    value = row.notes,
                    onValueChange = { onUpdate(row.copy(notes = it)) },
                    label = { Text("Notes (optional)") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ─── Date helpers ──────────────────────────────────────────────

/** Firefly returns RFC 3339 dates with an offset; values without one are treated as device-local. */
private fun parseFireflyDate(value: String): OffsetDateTime? {
    if (value.isBlank()) return null
    return runCatching { OffsetDateTime.parse(value) }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toOffsetDateTime()
        }.getOrNull()
}

/**
 * Combines the date picked in the DatePicker (a UTC midnight) with [time], or with the original
 * time when the time step was skipped. The original offset is kept so the day does not shift.
 */
internal fun pickedDateTime(
    dateUtcMillis: Long,
    time: LocalTime?,
    original: OffsetDateTime?,
    fallbackOffset: ZoneOffset = ZoneId.systemDefault().rules.getOffset(Instant.now())
): String {
    val date = Instant.ofEpochMilli(dateUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val resolvedTime = time ?: original?.toLocalTime() ?: LocalTime.NOON
    val offset = original?.offset ?: fallbackOffset
    return OffsetDateTime.of(date, resolvedTime, offset).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
