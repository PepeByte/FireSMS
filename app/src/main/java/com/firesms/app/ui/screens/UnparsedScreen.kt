package com.firesms.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.ui.components.FireSmsConfirmDialog
import com.firesms.app.ui.components.FireSmsEmptyState
import com.firesms.app.ui.components.FireSmsListCard
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.viewmodels.UiMessageAction
import com.firesms.app.ui.viewmodels.UnparsedItem
import com.firesms.app.ui.viewmodels.UnparsedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnparsedScreen(
    onNavigateToRule: (Long) -> Unit,
    onBack: () -> Unit,
    /** Shown as the snackbar action when Firefly isn't set up; no action when null. */
    onOpenSettings: (() -> Unit)? = null,
    viewModel: UnparsedViewModel = viewModel()
) {
    val unparsedLogs by viewModel.unparsedLogs.collectAsStateWithLifecycle()
    val runningIds by viewModel.runningIds.collectAsStateWithLifecycle()
    val isRetryingAll by viewModel.isRetryingAll.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingDelete by remember { mutableStateOf<UnparsedItem?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshFormatting() }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { message ->
                val action = message.action?.takeIf { it == UiMessageAction.OPEN_SETTINGS && onOpenSettings != null }
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = message.text,
                    actionLabel = action?.label,
                    duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed && action == UiMessageAction.OPEN_SETTINGS) {
                    onOpenSettings?.invoke()
                }
            }
        }
    }

    val logs = unparsedLogs
    FireSmsScaffold(
        title = "Needs attention",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            if (!logs.isNullOrEmpty()) {
                TextButton(
                    onClick = viewModel::reprocessAll,
                    enabled = !isRetryingAll && runningIds.isEmpty()
                ) {
                    if (isRetryingAll) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Try all again")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            when {
                logs == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                logs.isEmpty() -> FireSmsEmptyState(
                    title = "All caught up",
                    message = "Messages FireSMS cannot understand will appear here with steps to fix them.",
                    icon = Icons.Outlined.TaskAlt,
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 760.dp)
                )
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs, key = { it.id }, contentType = { "unparsed" }) { log ->
                        UnparsedCard(
                            log = log,
                            isRunning = log.id in runningIds,
                            actionsEnabled = !isRetryingAll && log.id !in runningIds,
                            onReprocess = { viewModel.reprocess(log.id) },
                            onCreateRule = { onNavigateToRule(log.id) },
                            onDelete = { pendingDelete = log }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { log ->
        FireSmsConfirmDialog(
            title = "Delete unparsed message?",
            message = "This removes the SMS from FireSMS. It will not delete anything from Firefly III.",
            confirmText = "Delete",
            onConfirm = {
                viewModel.delete(log.id)
                pendingDelete = null
            },
            onDismiss = {
                pendingDelete = null
            }
        )
    }
}

@Composable
private fun UnparsedCard(
    log: UnparsedItem,
    isRunning: Boolean,
    actionsEnabled: Boolean,
    onReprocess: () -> Unit,
    onCreateRule: () -> Unit,
    onDelete: () -> Unit
) {
    FireSmsListCard(
        headline = {
            Text(
                log.sender,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailing = {
            IconButton(onClick = onDelete, enabled = actionsEnabled) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete message")
            }
        },
        supporting = {
            Text(
                log.body,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                log.received,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        actions = {
            Button(onClick = onCreateRule, enabled = actionsEnabled) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Teach FireSMS")
            }
            TextButton(onClick = onReprocess, enabled = actionsEnabled) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(if (isRunning) "Trying…" else "Try again")
            }
        }
    )
}
