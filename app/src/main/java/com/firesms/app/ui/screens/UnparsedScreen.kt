package com.firesms.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.data.local.entity.SmsLog
import com.firesms.app.ui.components.FireSmsConfirmDialog
import com.firesms.app.ui.components.FireSmsEmptyState
import com.firesms.app.ui.components.FireSmsListCard
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.viewmodels.UnparsedViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnparsedScreen(
    onNavigateToRule: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: UnparsedViewModel = viewModel()
) {
    val unparsedLogs by viewModel.unparsedLogs.collectAsState()
    val reprocessResult by viewModel.reprocessResult.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<SmsLog?>(null) }

    LaunchedEffect(reprocessResult) {
        if (reprocessResult != null) {
            snackbarHostState.showSnackbar(reprocessResult!!)
            kotlinx.coroutines.delay(3000)
            viewModel.clearResult()
        }
    }

    FireSmsScaffold(
        title = "Needs attention",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (unparsedLogs.isEmpty()) {
                FireSmsEmptyState(
                    title = "All caught up",
                    message = "Messages FireSMS cannot understand will appear here with steps to fix them.",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(unparsedLogs, key = { it.id }) { log ->
                        UnparsedCard(
                            log = log,
                            onReprocess = { viewModel.reprocess(log) },
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
                viewModel.delete(log)
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
    log: SmsLog,
    onReprocess: () -> Unit,
    onCreateRule: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val received = remember(log.receivedAt) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(log.receivedAt))
    }
    FireSmsListCard(
        headline = {
            Text(log.sender, style = MaterialTheme.typography.titleSmall)
        },
        trailing = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Message actions")
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
                received,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        actions = {
            Button(onClick = onCreateRule) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Teach FireSMS")
            }
            TextButton(onClick = onReprocess) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Try again")
            }
        }
    )
}
