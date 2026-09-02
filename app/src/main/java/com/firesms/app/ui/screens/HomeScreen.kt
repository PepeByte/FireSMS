package com.firesms.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.ui.components.FireSmsBottomBar
import com.firesms.app.ui.components.FireSmsConfirmDialog
import com.firesms.app.ui.components.FireSmsEmptyState
import com.firesms.app.ui.components.FireSmsListCard
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.components.FireSmsTopLevelDestination
import com.firesms.app.ui.components.FireSmsStatusPill
import com.firesms.app.ui.components.statusColor
import com.firesms.app.ui.components.statusLabel
import com.firesms.app.data.local.StoredTransactionSnapshot
import com.firesms.app.data.local.StoredTransactionSplit
import com.firesms.app.ui.viewmodels.HomeTransactionItem
import com.firesms.app.ui.viewmodels.HomeViewModel
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToRules: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToUnparsed: () -> Unit,
    onNavigateToEditTransaction: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val logs by viewModel.logs.collectAsState()
    val unparsedCount by viewModel.unparsedCount.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val context = LocalContext.current

    var showTestDialog by remember { mutableStateOf(false) }
    var testSender by remember { mutableStateOf("") }
    var testBody by remember { mutableStateOf("") }
    var detailLogId by remember { mutableStateOf<Long?>(null) }
    var activityFilter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val smsPermissionStatus = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECEIVE_SMS
    )
    val hasSmsPermission = smsPermissionStatus == PackageManager.PERMISSION_GRANTED
    val detailLog = detailLogId?.let { selectedId -> logs.firstOrNull { it.log.id == selectedId } }
    val filteredLogs = remember(logs, activityFilter) {
        logs.filter { item ->
            when (activityFilter) {
                ActivityFilter.ALL -> true
                ActivityFilter.NEEDS_ATTENTION -> item.log.status in setOf("failed", "unparsed")
                ActivityFilter.PENDING -> item.log.status == "pending"
                ActivityFilter.MODIFIED -> item.isModified
            }
        }
    }
    val groupedLogs = remember(filteredLogs) {
        filteredLogs.groupBy { item -> activityDateLabel(item.log.receivedAt) }
    }

    FireSmsScaffold(
        title = "Activity",
        actions = {
            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Process sample SMS") },
                        onClick = {
                            showMoreMenu = false
                            showTestDialog = true
                        }
                    )
                }
            }
        },
        bottomBar = {
            FireSmsBottomBar(
                selected = FireSmsTopLevelDestination.ACTIVITY,
                onActivity = {},
                onAutomation = onNavigateToRules,
                onSettings = onNavigateToSettings
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    ActivityHealthPanel(
                        hasSmsPermission = hasSmsPermission,
                        pendingCount = pendingCount,
                        unparsedCount = unparsedCount,
                        onNeedsAttention = onNavigateToUnparsed,
                        onPermissionSettings = onNavigateToSettings
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ActivityFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = activityFilter == filter,
                                onClick = { activityFilter = filter },
                                label = { Text(filter.label) }
                            )
                        }
                    }
                }

                if (filteredLogs.isEmpty()) {
                    item {
                        FireSmsEmptyState(
                            title = if (logs.isEmpty()) "No activity yet" else "Nothing in this view",
                            message = if (logs.isEmpty()) {
                                "Transactions will appear here when FireSMS receives a matching bank message."
                            } else {
                                "Try another filter to see more activity."
                            }
                        )
                    }
                } else {
                    groupedLogs.forEach { (dateLabel, dateItems) ->
                        item(key = "header-$dateLabel") {
                            Text(
                                dateLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                        items(dateItems, key = { it.log.id }) { item ->
                            SmsLogCard(item = item, onClick = { detailLogId = item.log.id })
                        }
                    }
                }
            }
        }
    }



    if (showTestDialog) {
        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = { Text("Add sample SMS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = testSender,
                        onValueChange = { testSender = it },
                        label = { Text("Sender") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = testBody,
                        onValueChange = { testBody = it },
                        label = { Text("Body") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (testSender.isNotBlank() && testBody.isNotBlank()) {
                        viewModel.submitTestSms(testSender, testBody)
                        testSender = ""
                        testBody = ""
                        showTestDialog = false
                    }
                }) { Text("Save sample") }
            },
            dismissButton = {
                TextButton(onClick = { showTestDialog = false }) { Text("Cancel") }
            }
        )
    }

    detailLog?.let { item ->
        SmsLogDetailDialog(
            item = item,
            onDismiss = { detailLogId = null },
            onEditTransaction = onNavigateToEditTransaction,
            onRetry = { viewModel.retryFailedTransaction(item.log) },
            onDelete = { viewModel.deleteSmsLog(item.log); detailLogId = null }
        )
    }
}

private enum class ActivityFilter(val label: String) {
    ALL("All"),
    NEEDS_ATTENTION("Attention"),
    PENDING("Pending"),
    MODIFIED("Modified")
}

@Composable
private fun ActivityHealthPanel(
    hasSmsPermission: Boolean,
    pendingCount: Int,
    unparsedCount: Int,
    onNeedsAttention: () -> Unit,
    onPermissionSettings: () -> Unit
) {
    val isError = !hasSmsPermission
    val needsAttention = unparsedCount > 0
    val containerColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        needsAttention -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        needsAttention -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val title = when {
        isError -> "SMS access is off"
        needsAttention -> "$unparsedCount ${if (unparsedCount == 1) "message needs" else "messages need"} your help"
        pendingCount > 0 -> "$pendingCount ${if (pendingCount == 1) "transaction is" else "transactions are"} waiting to sync"
        else -> "Everything is up to date"
    }
    val message = when {
        isError -> "Allow SMS access so FireSMS can receive bank messages."
        needsAttention -> "Teach FireSMS how to understand these messages."
        pendingCount > 0 -> "FireSMS will retry automatically when your connection is available."
        else -> "FireSMS is listening for matching bank messages."
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    isError -> Modifier.clickable(onClick = onPermissionSettings)
                    needsAttention -> Modifier.clickable(onClick = onNeedsAttention)
                    else -> Modifier
                }
            ),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isError || needsAttention) {
                Icon(Icons.Default.Warning, contentDescription = null)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            if (isError || needsAttention) {
                Text(if (isError) "Settings" else "Review", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun activityDateLabel(receivedAt: Long): String {
    val date = Instant.ofEpochMilli(receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()))
    }
}

@Composable
private fun SmsLogCard(item: HomeTransactionItem, onClick: () -> Unit) {
    val log = item.log
    val transaction = item.transaction
    val firstSplit = transaction?.transactions?.firstOrNull()
    val color = statusColor(log.status, MaterialTheme.colorScheme)
    val label = statusLabel(log.status)
    val headline = transaction?.groupTitle?.takeIf { it.isNotBlank() }
        ?: firstSplit?.description?.takeIf { it.isNotBlank() }
        ?: log.sender

    FireSmsListCard(
        onClick = onClick,
        headline = {
            Text(
                headline,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (firstSplit != null) {
                    Text(
                        transaction.formattedTotal(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (item.isModified || log.status != "success") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.isModified) {
                            FireSmsStatusPill(
                                label = "Modified",
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        if (log.status != "success") {
                            FireSmsStatusPill(label = label, color = color)
                        }
                    }
                }
            }
        },
        supporting = {
            if (firstSplit != null) {
                val accountPath = listOfNotNull(
                    firstSplit.sourceName?.takeIf { it.isNotBlank() },
                    firstSplit.destinationName?.takeIf { it.isNotBlank() }
                ).joinToString(" → ")
                if (accountPath.isNotBlank()) {
                    Text(
                        accountPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val metadata = listOfNotNull(
                    firstSplit.type.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.receivedAt)),
                    firstSplit.categoryName?.takeIf { it.isNotBlank() },
                    transaction.transactions.size.takeIf { it > 1 }?.let { "$it splits" }
                ).joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Text(
                        metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    log.body,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (log.errorMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    log.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

@Composable
private fun SmsLogDetailDialog(
    item: HomeTransactionItem,
    onDismiss: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val log = item.log
    val transaction = item.transaction
    val clipboardManager = LocalClipboardManager.current
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val color = statusColor(log.status, MaterialTheme.colorScheme)
    val label = statusLabel(log.status)

    var showDeleteConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (transaction != null) "Transaction details" else "SMS details")
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailRow("Status") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FireSmsStatusPill(label = label, color = color)
                        if (log.status == "failed") {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onRetry,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Retry", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (transaction != null) {
                    DetailRow("Local record") {
                        FireSmsStatusPill(
                            label = if (item.isModified) "Modified" else "Original",
                            color = if (item.isModified) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                    }
                    transaction.groupTitle?.takeIf { it.isNotBlank() }?.let { title ->
                        DetailRow("Group title") {
                            Text(title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    transaction.transactions.forEachIndexed { index, split ->
                        TransactionSplitDetails(
                            split = split,
                            heading = if (transaction.transactions.size > 1) "Split ${index + 1}" else "Transaction"
                        )
                    }
                }
                DetailRow("Sender") {
                    Text(log.sender, style = MaterialTheme.typography.bodyMedium)
                }
                DetailRow("Received") {
                    Text(
                        dateFormat.format(Date(log.receivedAt)),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (log.processedAt != null) {
                    DetailRow("Processed") {
                        Text(
                            dateFormat.format(Date(log.processedAt)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (log.fireflyId != null) {
                    DetailRow("Firefly ID") {
                        Text(log.fireflyId, style = MaterialTheme.typography.bodySmall)
                    }
                }
                DetailRow("Original SMS") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            log.body,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { clipboardManager.setText(AnnotatedString(log.body)) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("Copy", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (log.errorMessage != null) {
                    DetailRow("Error") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                log.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { clipboardManager.setText(AnnotatedString(log.errorMessage)) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text("Copy", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (log.fireflyId != null) {
                Button(onClick = { onEditTransaction(log.id) }) {
                    Text("Edit transaction")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    if (showDeleteConfirm) {
        FireSmsConfirmDialog(
            title = "Delete transaction log?",
            message = "This removes the SMS log from FireSMS. It will not delete anything from Firefly III.",
            confirmText = "Delete",
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
private fun TransactionSplitDetails(split: StoredTransactionSplit, heading: String) {
    DetailRow(heading) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                split.description.ifBlank { "Untitled transaction" },
                style = MaterialTheme.typography.bodyMedium
            )
            val amountAndType = listOfNotNull(
                split.amount.takeIf { it.isNotBlank() }?.let { split.formattedAmount(it) },
                split.type.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            ).joinToString(" · ")
            if (amountAndType.isNotBlank()) {
                Text(amountAndType, style = MaterialTheme.typography.bodySmall)
            }
            val accounts = listOfNotNull(
                split.sourceName?.takeIf { it.isNotBlank() },
                split.destinationName?.takeIf { it.isNotBlank() }
            ).joinToString(" → ")
            if (accounts.isNotBlank()) {
                Text(accounts, style = MaterialTheme.typography.bodySmall)
            }
            if (split.date.isNotBlank()) {
                Text(split.date, style = MaterialTheme.typography.bodySmall)
            }
            val labels = listOfNotNull(
                split.categoryName?.takeIf { it.isNotBlank() }?.let { "Category: $it" },
                split.budgetName?.takeIf { it.isNotBlank() }?.let { "Budget: $it" }
            ).joinToString(" · ")
            if (labels.isNotBlank()) {
                Text(labels, style = MaterialTheme.typography.labelSmall)
            }
            split.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(notes, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun StoredTransactionSnapshot.formattedTotal(): String {
    if (transactions.size == 1) {
        val split = transactions.first()
        return split.formattedAmount(split.amount)
    }
    val total = transactions.fold(BigDecimal.ZERO) { sum, split ->
        sum + (split.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO)
    }.stripTrailingZeros().toPlainString()
    return transactions.firstOrNull()?.formattedAmount(total) ?: total
}

private fun StoredTransactionSplit.formattedAmount(value: String): String = when {
    !currencySymbol.isNullOrBlank() -> "$currencySymbol$value"
    !currencyCode.isNullOrBlank() -> "$currencyCode $value"
    else -> value
}

@Composable
private fun DetailRow(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        content()
    }
}
