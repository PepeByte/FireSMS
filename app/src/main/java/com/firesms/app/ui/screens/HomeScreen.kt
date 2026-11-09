package com.firesms.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SmsFailed
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.firesms.app.data.local.ActivityCounts
import com.firesms.app.ui.components.FireSmsBottomBar
import com.firesms.app.ui.components.FireSmsConfirmDialog
import com.firesms.app.ui.components.FireSmsEmptyState
import com.firesms.app.ui.components.FireSmsListCard
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.components.FireSmsStatusPill
import com.firesms.app.ui.components.FireSmsTopLevelDestination
import com.firesms.app.ui.components.statusColor
import com.firesms.app.ui.components.statusLabel
import com.firesms.app.ui.theme.FireSmsTheme
import com.firesms.app.ui.viewmodels.ActivityDetail
import com.firesms.app.ui.viewmodels.ActivityFilter
import com.firesms.app.ui.viewmodels.ActivityListItem
import com.firesms.app.ui.viewmodels.ActivityRow
import com.firesms.app.ui.viewmodels.AmountDirection
import com.firesms.app.ui.viewmodels.HealthLine
import com.firesms.app.ui.viewmodels.HomeViewModel
import com.firesms.app.ui.viewmodels.UiMessageAction
import com.firesms.app.ui.viewmodels.healthLineMessage
import com.firesms.app.ui.viewmodels.healthLineTitle
import com.firesms.app.ui.viewmodels.healthLines
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToRules: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToUnparsed: () -> Unit,
    onNavigateToEditTransaction: (Long) -> Unit,
    /** Opens this SMS log's detail sheet, e.g. from a "couldn't sync" notification; then [onDetailRequestHandled]. */
    initialDetailLogId: Long? = null,
    onDetailRequestHandled: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val activityItems = viewModel.activity.collectAsLazyPagingItems()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val selectedLogId by viewModel.selectedLogId.collectAsStateWithLifecycle()
    // Only the record that is actually selected; a deleted or not-yet-loaded record shows no sheet.
    val openDetail = detail?.takeIf { it.log.id == selectedLogId }
    val retryingLogId by viewModel.retryingLogId.collectAsStateWithLifecycle()
    val retryWork by viewModel.failedRetryWork.collectAsStateWithLifecycle()
    val isSchedulingRetry by viewModel.isSchedulingRetry.collectAsStateWithLifecycle()
    val isProcessingSample by viewModel.isProcessingSample.collectAsStateWithLifecycle()
    val isRetryingFailed = isSchedulingRetry || retryWork != null
    val snackbarHostState = remember { SnackbarHostState() }
    val smsPermission = rememberSmsPermissionState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showTestDialog by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showRetryConfirm by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshFormatting() }

    LaunchedEffect(initialDetailLogId) {
        if (initialDetailLogId != null && initialDetailLogId > 0) {
            viewModel.openDetail(initialDetailLogId)
            onDetailRequestHandled()
        }
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { message ->
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = message.text,
                    actionLabel = message.action?.label,
                    duration = if (message.action != null) SnackbarDuration.Long else SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    when (message.action) {
                        UiMessageAction.OPEN_SETTINGS -> onNavigateToSettings()
                        UiMessageAction.OPEN_UNPARSED -> onNavigateToUnparsed()
                        UiMessageAction.OPEN_RULES -> onNavigateToRules()
                        UiMessageAction.SHOW_ATTENTION -> viewModel.setFilter(ActivityFilter.NEEDS_ATTENTION)
                        null -> Unit
                    }
                }
            }
        }
    }

    FireSmsScaffold(
        title = "Activity",
        // The detail sheet is its own window; it hosts the snackbar while it is open.
        snackbarHost = { if (openDetail == null) SnackbarHost(snackbarHostState) },
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
                        text = { Text(if (isProcessingSample) "Processing sample SMS…" else "Process sample SMS") },
                        enabled = !isProcessingSample,
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
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            val refreshState = activityItems.loadState.refresh
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                counts?.let { loadedCounts ->
                    item(key = "health", contentType = "health") {
                        ActivityHealthPanel(
                            lines = healthLines(smsPermission.isGranted, loadedCounts),
                            onRequestPermission = smsPermission.request,
                            onReviewUnparsed = onNavigateToUnparsed,
                            onShowFailed = { viewModel.setFilter(ActivityFilter.NEEDS_ATTENTION) }
                        )
                    }

                    if (loadedCounts.retryable > 0) {
                        item(key = "retry-failed", contentType = "retry") {
                            RetryAllButton(
                                counts = loadedCounts,
                                isRetrying = isRetryingFailed,
                                progressText = retryWork?.takeIf { it.isRunning }
                                    ?.let { "Retrying ${it.completed} / ${it.total}" },
                                onClick = { showRetryConfirm = true }
                            )
                        }
                    }
                }

                item(key = "search", contentType = "search") {
                    ActivitySearchField(query = query, onQueryChange = viewModel::setQuery)
                }

                item(key = "filters", contentType = "filters") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ActivityFilter.entries.forEach { option ->
                            val count = counts?.let { option.count(it) } ?: 0
                            FilterChip(
                                selected = filter == option,
                                onClick = { viewModel.setFilter(option) },
                                label = { Text(if (count > 0) "${option.label} ($count)" else option.label) }
                            )
                        }
                    }
                }

                when {
                    activityItems.itemCount == 0 && (refreshState is LoadState.Loading || counts == null) -> {
                        item(key = "loading", contentType = "loading") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    activityItems.itemCount == 0 -> {
                        item(key = "empty", contentType = "empty") {
                            ActivityEmptyState(
                                hasAnyActivity = (counts?.total ?: 0) > 0,
                                filter = filter,
                                query = query.trim(),
                                loadFailed = refreshState is LoadState.Error
                            )
                        }
                    }
                    else -> {
                        items(
                            count = activityItems.itemCount,
                            key = activityItems.itemKey { it.key },
                            contentType = activityItems.itemContentType { it.contentType }
                        ) { index ->
                            when (val item = activityItems[index]) {
                                is ActivityListItem.Header -> Text(
                                    item.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                )
                                is ActivityListItem.Entry -> SmsLogCard(
                                    row = item.row,
                                    onClick = { viewModel.openDetail(item.row.id) }
                                )
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRetryConfirm) {
        val total = counts?.retryable ?: 0
        FireSmsConfirmDialog(
            title = "Retry $total ${if (total == 1) "transaction" else "transactions"} now?",
            message = "FireSMS sends failed transactions and those waiting to sync again, using your current " +
                "rules and settings. Synced and unparsed messages are not included.",
            confirmText = "Retry now",
            destructive = false,
            onConfirm = {
                showRetryConfirm = false
                viewModel.retryAllFailedTransactions()
            },
            onDismiss = { showRetryConfirm = false }
        )
    }

    if (showTestDialog) {
        SampleSmsDialog(
            onDismiss = { showTestDialog = false },
            onProcess = { sender, body ->
                showTestDialog = false
                viewModel.processSampleSms(sender, body)
            }
        )
    }

    openDetail?.let { loaded ->
        ActivityDetailSheet(
            detail = loaded,
            isRetrying = retryingLogId == loaded.log.id,
            retryEnabled = !isRetryingFailed && retryingLogId == null,
            snackbarHostState = snackbarHostState,
            onDismiss = viewModel::closeDetail,
            onEditTransaction = { onNavigateToEditTransaction(loaded.log.id) },
            onRetry = { viewModel.retryTransaction(loaded.log.id) },
            onDelete = { viewModel.deleteSmsLog(loaded.log.id) }
        )
    }
}

private fun ActivityFilter.count(counts: ActivityCounts): Int = when (this) {
    ActivityFilter.ALL -> 0
    ActivityFilter.NEEDS_ATTENTION -> counts.attention
    ActivityFilter.WAITING -> counts.waiting
    ActivityFilter.MODIFIED -> counts.modified
}

// ─── SMS permission ────────────────────────────────────────────

private class SmsPermissionState(val isGranted: Boolean, val request: () -> Unit)

/**
 * Re-checks RECEIVE_SMS on every resume instead of on every recomposition. Requesting falls back to
 * the app's system settings page once Android stops showing the permission dialog.
 */
@Composable
private fun rememberSmsPermissionState(): SmsPermissionState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var isGranted by remember { mutableStateOf(context.hasSmsPermission()) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var rationaleBeforeRequest by remember { mutableStateOf(false) }
    fun shouldShowRationale() =
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS) == true

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        isGranted = granted
        if (!granted && !shouldShowRationale()) {
            if (rationaleBeforeRequest) {
                // The user just chose "Don't allow" a second time; next tap goes to settings.
                permanentlyDenied = true
            } else {
                // No dialog was shown at all: the permission is already permanently denied.
                permanentlyDenied = true
                context.openAppDetailsSettings()
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isGranted = context.hasSmsPermission()
        if (isGranted) permanentlyDenied = false
    }

    return SmsPermissionState(isGranted) {
        if (permanentlyDenied) {
            context.openAppDetailsSettings()
        } else {
            rationaleBeforeRequest = shouldShowRationale()
            launcher.launch(Manifest.permission.RECEIVE_SMS)
        }
    }
}

private fun Context.hasSmsPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppDetailsSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

// ─── Health panel ──────────────────────────────────────────────

@Composable
private fun ActivityHealthPanel(
    lines: List<HealthLine>,
    onRequestPermission: () -> Unit,
    onReviewUnparsed: () -> Unit,
    onShowFailed: () -> Unit
) {
    val hasError = lines.any { it is HealthLine.PermissionOff }
    val allGood = lines.singleOrNull() == HealthLine.AllGood
    val (containerColor, contentColor) = when {
        hasError -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        allGood -> FireSmsTheme.colors.successContainer to FireSmsTheme.colors.onSuccessContainer
        else -> FireSmsTheme.colors.warningContainer to FireSmsTheme.colors.onWarningContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large
    ) {
        Column {
            lines.forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider(color = contentColor.copy(alpha = 0.12f))
                val (onClick, actionLabel) = when (line) {
                    HealthLine.PermissionOff -> onRequestPermission to "Allow"
                    is HealthLine.Unparsed -> onReviewUnparsed to "Review"
                    is HealthLine.Failed -> onShowFailed to "Show"
                    is HealthLine.Queued, HealthLine.AllGood -> null to null
                }
                HealthPanelLine(
                    icon = line.icon(),
                    title = healthLineTitle(line),
                    message = healthLineMessage(line),
                    actionLabel = actionLabel,
                    onClick = onClick
                )
            }
        }
    }
}

private fun HealthLine.icon(): ImageVector = when (this) {
    HealthLine.PermissionOff -> Icons.Outlined.SmsFailed
    is HealthLine.Unparsed -> Icons.AutoMirrored.Outlined.Rule
    is HealthLine.Failed -> Icons.Outlined.ErrorOutline
    is HealthLine.Queued -> Icons.Outlined.CloudSync
    HealthLine.AllGood -> Icons.Outlined.CheckCircle
}

@Composable
private fun HealthPanelLine(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String?,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
        if (actionLabel != null) {
            Text(actionLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RetryAllButton(
    counts: ActivityCounts,
    isRetrying: Boolean,
    progressText: String?,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = onClick,
            enabled = !isRetrying,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    progressText != null -> progressText
                    isRetrying -> "Retry queued"
                    counts.failed == 0 -> "Sync now (${counts.retryable})"
                    counts.queued == 0 -> "Retry failed (${counts.retryable})"
                    else -> "Retry now (${counts.retryable})"
                }
            )
        }
        if (isRetrying && progressText == null) {
            Text(
                "Retries start when your network preference allows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActivitySearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search description, sender or amount") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun ActivityEmptyState(hasAnyActivity: Boolean, filter: ActivityFilter, query: String, loadFailed: Boolean) {
    val (icon, title, message) = when {
        loadFailed -> Triple(Icons.Outlined.ErrorOutline, "Couldn't load activity", "Close and reopen FireSMS to try again.")
        !hasAnyActivity -> Triple(
            Icons.Outlined.Inbox,
            "No activity yet",
            "Transactions will appear here when FireSMS receives a matching bank message."
        )
        query.isNotEmpty() -> Triple(
            Icons.Outlined.SearchOff,
            "No matches",
            "Nothing matches “$query”. Try a description, sender or amount."
        )
        filter == ActivityFilter.NEEDS_ATTENTION -> Triple(
            Icons.Outlined.TaskAlt, "Nothing needs attention", "Every message has been read and synced."
        )
        filter == ActivityFilter.WAITING -> Triple(
            Icons.Outlined.CloudDone, "Nothing waiting to sync", "Everything FireSMS read has reached Firefly."
        )
        filter == ActivityFilter.MODIFIED -> Triple(
            Icons.Outlined.EditNote,
            "No modified transactions",
            "Transactions changed in Firefly after FireSMS created them appear here."
        )
        else -> Triple(Icons.Outlined.Inbox, "Nothing in this view", "Try another filter to see more activity.")
    }
    FireSmsEmptyState(title = title, message = message, icon = icon)
}

// ─── Activity card ─────────────────────────────────────────────

@Composable
private fun amountColor(direction: AmountDirection): Color =
    if (direction == AmountDirection.IN) FireSmsTheme.colors.success else MaterialTheme.colorScheme.onSurface

@Composable
private fun SmsLogCard(row: ActivityRow, onClick: () -> Unit) {
    FireSmsListCard(
        onClick = onClick,
        headline = {
            Text(
                row.headline,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.amount != null) {
                    Text(
                        row.amount,
                        style = MaterialTheme.typography.titleMedium,
                        color = amountColor(row.direction),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (row.isModified || row.status != "success") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (row.isModified) {
                            FireSmsStatusPill(label = "Modified", color = MaterialTheme.colorScheme.secondary)
                        }
                        if (row.status != "success") {
                            FireSmsStatusPill(label = statusLabel(row.status), color = statusColor(row.status))
                        }
                    }
                }
            }
        },
        supporting = {
            if (row.accountPath != null) {
                Text(
                    row.accountPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (row.preview != null) {
                Text(
                    row.preview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (row.metadata.isNotBlank()) {
                Text(
                    row.metadata,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (row.errorMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    row.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.status == "queued") {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

// ─── Sample SMS dialog ─────────────────────────────────────────

@Composable
private fun SampleSmsDialog(onDismiss: () -> Unit, onProcess: (sender: String, body: String) -> Unit) {
    var sender by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    val canProcess = sender.isNotBlank() && body.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Process sample SMS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "FireSMS processes this message as if it had just arrived. If a rule matches, " +
                        "it creates a real transaction in Firefly III.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it },
                    label = { Text("Sender") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Message") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onProcess(sender, body) }, enabled = canProcess) { Text("Process") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─── Detail sheet ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityDetailSheet(
    detail: ActivityDetail,
    isRetrying: Boolean,
    retryEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onEditTransaction: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val log = detail.log
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun copy(label: String, text: String) {
        coroutineScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Box {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (detail.amount != null) {
                        Text(
                            detail.amount,
                            style = MaterialTheme.typography.headlineMedium,
                            color = amountColor(detail.direction)
                        )
                    }
                    Text(detail.headline, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FireSmsStatusPill(label = statusLabel(log.status), color = statusColor(log.status))
                        if (detail.isModified) {
                            FireSmsStatusPill(label = "Modified", color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (detail.isModified) {
                        DetailItem(
                            label = "Modified",
                            value = "The Firefly transaction was changed after FireSMS created it."
                        )
                    }
                    log.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                        DetailItem(
                            label = if (log.status == "queued") "Why it's waiting" else "Error",
                            value = error,
                            valueColor = if (log.status == "queued") null else MaterialTheme.colorScheme.error,
                            trailing = {
                                IconButton(onClick = { copy("Error", error) }) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy error")
                                }
                            }
                        )
                    }
                    detail.transaction?.groupTitle?.takeIf { it.isNotBlank() && detail.splits.size > 1 }?.let {
                        DetailItem(label = "Group title", value = it)
                    }
                    detail.splits.forEachIndexed { index, split ->
                        val s = split.split
                        val lines = listOfNotNull(
                            listOfNotNull(
                                split.amount.takeIf { detail.splits.size > 1 },
                                s.type.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
                            ).joinToString(" · ").takeIf { it.isNotBlank() },
                            listOfNotNull(
                                s.sourceName?.takeIf { it.isNotBlank() },
                                s.destinationName?.takeIf { it.isNotBlank() }
                            ).joinToString(" → ").takeIf { it.isNotBlank() },
                            split.date,
                            listOfNotNull(
                                s.categoryName?.takeIf { it.isNotBlank() }?.let { "Category: $it" },
                                s.budgetName?.takeIf { it.isNotBlank() }?.let { "Budget: $it" }
                            ).joinToString(" · ").takeIf { it.isNotBlank() },
                            s.notes?.takeIf { it.isNotBlank() }
                        )
                        DetailItem(
                            label = if (detail.splits.size > 1) "Split ${index + 1}" else "Transaction",
                            value = s.description.ifBlank { "Untitled transaction" },
                            supporting = lines.joinToString("\n").takeIf { it.isNotBlank() }
                        )
                    }
                    DetailItem(label = "Sender", value = log.sender)
                    DetailItem(label = "Received", value = detail.received)
                    detail.processed?.let { DetailItem(label = "Processed", value = it) }
                    log.fireflyId?.let { DetailItem(label = "Firefly ID", value = it) }
                    DetailItem(label = "Original SMS", value = log.body)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { copy("SMS", log.body) }) { Text("Copy") }
                    if (detail.canRetry || isRetrying) {
                        TextButton(onClick = onRetry, enabled = retryEnabled && !isRetrying) {
                            if (isRetrying) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Retrying…")
                            } else {
                                Text("Retry")
                            }
                        }
                    }
                    if (log.fireflyId != null) {
                        Button(onClick = onEditTransaction) { Text("Edit") }
                    }
                }
            }
            SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    if (showDeleteConfirm) {
        FireSmsConfirmDialog(
            title = "Delete from FireSMS?",
            message = "This removes the SMS and its history from FireSMS. It will not delete anything from Firefly III.",
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
private fun DetailItem(
    label: String,
    value: String,
    supporting: String? = null,
    valueColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = {
            Text(value, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
