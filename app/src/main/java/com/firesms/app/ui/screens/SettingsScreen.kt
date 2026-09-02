package com.firesms.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.data.local.HistoryRetention
import com.firesms.app.ui.components.FireSmsBottomBar
import com.firesms.app.ui.components.FireSmsPreferenceOption
import com.firesms.app.ui.components.FireSmsResultBanner
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.components.FireSmsScreen
import com.firesms.app.ui.components.FireSmsSection
import com.firesms.app.ui.components.FireSmsStatusPill
import com.firesms.app.ui.components.FireSmsTopLevelDestination
import com.firesms.app.ui.viewmodels.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToActivity: () -> Unit,
    onNavigateToAutomation: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val url by viewModel.fireflyUrl.collectAsState()
    val token by viewModel.fireflyToken.collectAsState()
    val syncNetworkPolicy by viewModel.syncNetworkPolicy.collectAsState()
    val historyRetention by viewModel.historyRetention.collectAsState()
    val dataTransferState by viewModel.dataTransferState.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    val context = LocalContext.current

    var urlInput by remember { mutableStateOf(url) }
    var tokenInput by remember { mutableStateOf(token) }
    val snackbarHostState = remember { SnackbarHostState() }
    var didSaveSync by remember { mutableStateOf(false) }
    var connectionSaveCount by remember { mutableIntStateOf(0) }
    var retentionMenuExpanded by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(url, token) {
        urlInput = url
        tokenInput = token
    }

    val smsPermissionStatus = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECEIVE_SMS
    )
    var smsPermissionGranted by remember(smsPermissionStatus) {
        mutableStateOf(smsPermissionStatus == PackageManager.PERMISSION_GRANTED)
    }

    val notificationPermissionStatus = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PackageManager.PERMISSION_GRANTED
        }
    }
    var notificationPermissionGranted by remember(notificationPermissionStatus) {
        mutableStateOf(notificationPermissionStatus == PackageManager.PERMISSION_GRANTED)
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> smsPermissionGranted = granted }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationPermissionGranted = granted }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let(viewModel::exportData)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        pendingImportUri = uri
    }

    FireSmsScaffold(
        title = "Settings",
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            FireSmsBottomBar(
                selected = FireSmsTopLevelDestination.SETTINGS,
                onActivity = onNavigateToActivity,
                onAutomation = onNavigateToAutomation,
                onSettings = {}
            )
        }
    ) { padding ->
        FireSmsScreen(
            modifier = Modifier.padding(padding)
        ) {
            // Sync preference section — auto-saves on selection
            FireSmsSection(
                title = "Sync preference",
                subtitle = "When to sync queued transactions"
            ) {
                FireSmsPreferenceOption(
                    title = "Wi-Fi or mobile data",
                    description = "Sync whenever the device has an internet connection.",
                    selected = syncNetworkPolicy == "any_network",
                    onClick = {
                        viewModel.saveSyncNetworkPolicy("any_network")
                    }
                )
                FireSmsPreferenceOption(
                    title = "Wi-Fi only",
                    description = "Wait for Wi-Fi before retrying queued transactions.",
                    selected = syncNetworkPolicy == "wifi_only",
                    onClick = {
                        viewModel.saveSyncNetworkPolicy("wifi_only")
                    }
                )
            }

            FireSmsSection(
                title = "History",
                subtitle = "Automatically remove old local activity. Firefly III transactions and active queued retries are kept."
            ) {
                ExposedDropdownMenuBox(
                    expanded = retentionMenuExpanded,
                    onExpandedChange = { retentionMenuExpanded = !retentionMenuExpanded }
                ) {
                    val selected = HistoryRetention.fromValue(historyRetention)
                    OutlinedTextField(
                        value = selected.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Keep history for") },
                        supportingText = { Text(selected.description) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = retentionMenuExpanded)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = retentionMenuExpanded,
                        onDismissRequest = { retentionMenuExpanded = false }
                    ) {
                        HistoryRetention.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(option.label)
                                        Text(
                                            option.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    retentionMenuExpanded = false
                                    viewModel.saveHistoryRetention(option.value)
                                }
                            )
                        }
                    }
                }
            }

            FireSmsSection(
                title = "Backup and restore",
                subtitle = "Export or restore history, retry queue, rules, mappings, and settings."
            ) {
                Text(
                    "Backup files are unencrypted and include your Firefly access token, bank SMS messages, and transaction history. Store them securely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val transferInProgress = dataTransferState is SettingsViewModel.DataTransferState.Working
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.clearDataTransferState()
                            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            exportLauncher.launch("firesms-backup-$date.json")
                        },
                        enabled = !transferInProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export data")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.clearDataTransferState()
                            importLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                        },
                        enabled = !transferInProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import data")
                    }
                }
                when (val state = dataTransferState) {
                    is SettingsViewModel.DataTransferState.Working -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(state.action, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is SettingsViewModel.DataTransferState.Success -> {
                        FireSmsResultBanner(text = state.message, isError = false)
                    }
                    is SettingsViewModel.DataTransferState.Failure -> {
                        FireSmsResultBanner(text = state.message, isError = true)
                    }
                    null -> Unit
                }
            }

            // Firefly connection section — saved separately
            FireSmsSection(
                title = "Firefly III connection",
                subtitle = "Saved separately from sync preference."
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        viewModel.clearTestResult()
                    },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://firefly.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = {
                        tokenInput = it
                        viewModel.clearTestResult()
                    },
                    label = { Text("Personal access token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveSettings(urlInput, tokenInput)
                            connectionSaveCount++
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save connection")
                    }
                    OutlinedButton(
                        onClick = { viewModel.testConnection(urlInput, tokenInput) },
                        modifier = Modifier.weight(1f),
                        enabled = urlInput.isNotBlank() && tokenInput.isNotBlank() && !isTesting
                    ) {
                        Text(if (isTesting) "Testing…" else "Test connection")
                    }
                }

                when (val result = testResult) {
                    is SettingsViewModel.TestResult.Success -> {
                        FireSmsResultBanner(
                            text = "Connection successful!",
                            isError = false
                        )
                    }
                    is SettingsViewModel.TestResult.Failure -> {
                        FireSmsResultBanner(
                            text = "Connection failed: ${result.message}",
                            isError = true
                        )
                    }
                    null -> {}
                }
            }

            // Permissions
            FireSmsSection(title = "Permissions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SMS receive permission")
                    if (smsPermissionGranted) {
                        FireSmsStatusPill(label = "Granted", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = { smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS) }) {
                            Text("Request")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Notification permission")
                    if (notificationPermissionGranted) {
                        FireSmsStatusPill(label = "Granted", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text("Request")
                        }
                    }
                }
            }

            FireSmsSection(title = "About") {
                Text(
                    "FireSMS v1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Intercept SMS messages and post transactions to Firefly III",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("Restore FireSMS backup?") },
            text = {
                Text(
                    "This replaces all current local history, queued retries, parser rules, title mappings, and settings. It does not change transactions already stored in Firefly III."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingImportUri = null
                        viewModel.importData(uri)
                    }
                ) { Text("Replace and restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text("Cancel") }
            }
        )
    }

    // Show snackbar when sync preference changes (skip initial emission)
    LaunchedEffect(syncNetworkPolicy) {
        if (didSaveSync) {
            snackbarHostState.showSnackbar("Sync preference saved")
        }
        didSaveSync = true
    }

    // Connection save triggers snackbar
    LaunchedEffect(connectionSaveCount) {
        if (connectionSaveCount > 0) {
            snackbarHostState.showSnackbar("Connection settings saved")
        }
    }
}
