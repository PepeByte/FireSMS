package com.firesms.app.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.BuildConfig
import com.firesms.app.data.local.HistoryRetention
import com.firesms.app.data.remote.normalizeFireflyUrl
import com.firesms.app.ui.components.FireSmsBottomBar
import com.firesms.app.ui.components.FireSmsConfirmDialog
import com.firesms.app.ui.components.FireSmsPreferenceOption
import com.firesms.app.ui.components.FireSmsResultBanner
import com.firesms.app.ui.components.FireSmsScaffold
import com.firesms.app.ui.components.FireSmsScreen
import com.firesms.app.ui.components.FireSmsSection
import com.firesms.app.ui.components.FireSmsTopLevelDestination
import com.firesms.app.ui.viewmodels.SettingsViewModel
import com.firesms.app.ui.viewmodels.retentionDeletesMoreHistory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToActivity: () -> Unit,
    onNavigateToAutomation: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val url by viewModel.fireflyUrl.collectAsStateWithLifecycle()
    val token by viewModel.fireflyToken.collectAsStateWithLifecycle()
    val syncNetworkPolicy by viewModel.syncNetworkPolicy.collectAsStateWithLifecycle()
    val historyRetention by viewModel.historyRetention.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val dataTransferState by viewModel.dataTransferState.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val updateSnapshot by viewModel.updateSnapshot.collectAsStateWithLifecycle()
    val updateCheckState by viewModel.updateCheckState.collectAsStateWithLifecycle()
    val updatePreferenceError by viewModel.updatePreferenceError.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var urlInput by remember { mutableStateOf(url) }
    var tokenInput by remember { mutableStateOf(token) }
    val snackbarHostState = remember { SnackbarHostState() }
    var syncSaveCount by remember { mutableIntStateOf(0) }
    var retentionMenuExpanded by remember { mutableStateOf(false) }
    var pendingRetention by remember { mutableStateOf<HistoryRetention?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(url, token) {
        urlInput = url
        tokenInput = token
    }

    // ─── Unsaved connection edits ──────────────────────────────
    // Settings is a top-level tab, so leaving happens through the bottom bar as well as back.
    val connectionDirty = normalizeFireflyUrl(urlInput) != normalizeFireflyUrl(url) ||
        tokenInput.trim() != token.trim()
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var backAfterDiscard by remember { mutableStateOf(false) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val leaveSettings: (() -> Unit) -> Unit = { action ->
        if (connectionDirty) pendingLeave = action else action()
    }
    BackHandler(enabled = connectionDirty) { pendingLeave = { backAfterDiscard = true } }
    // Re-dispatch back only once the discarded edits have disabled the handler above.
    LaunchedEffect(backAfterDiscard, connectionDirty) {
        if (backAfterDiscard && !connectionDirty) {
            backAfterDiscard = false
            backDispatcher?.onBackPressed()
        }
    }

    val smsPermission = rememberTrackedPermission(Manifest.permission.RECEIVE_SMS)
    val notificationPermission = rememberTrackedPermission(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
    )
    val battery = rememberBatteryOptimizationState()

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> smsPermission.onRequestResult(granted) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationPermission.onRequestResult(granted) }
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

    LaunchedEffect(viewModel) {
        viewModel.connectionSaved.collect {
            snackbarHostState.showSnackbar("Connection settings saved")
        }
    }

    FireSmsScaffold(
        title = "Settings",
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            FireSmsBottomBar(
                selected = FireSmsTopLevelDestination.SETTINGS,
                onActivity = { leaveSettings(onNavigateToActivity) },
                onAutomation = { leaveSettings(onNavigateToAutomation) },
                onSettings = {}
            )
        }
    ) { padding ->
        FireSmsScreen(
            modifier = Modifier.padding(padding)
        ) {
            FireSmsSection(
                title = "Firefly III connection",
                subtitle = "Server address and access token used to create transactions"
            ) {
                FireflyConnectionFields(
                    url = urlInput,
                    onUrlChange = {
                        urlInput = it
                        viewModel.clearTestResult()
                    },
                    token = tokenInput,
                    onTokenChange = {
                        tokenInput = it
                        viewModel.clearTestResult()
                    },
                    onDone = {}
                )

                val canSubmit = urlInput.isNotBlank() && tokenInput.isNotBlank() && !isTesting
                Button(
                    onClick = {
                        if (connectionDirty) {
                            viewModel.saveAndTestConnection(urlInput, tokenInput)
                        } else {
                            viewModel.testConnection(urlInput, tokenInput)
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            isTesting -> "Testing…"
                            connectionDirty -> "Save and test"
                            else -> "Test connection"
                        }
                    )
                }
                if (connectionDirty) {
                    Text(
                        "Changes aren't saved until you tap Save and test.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (val result = testResult) {
                    is SettingsViewModel.TestResult.Success -> {
                        FireSmsResultBanner(text = "Connected to Firefly III.", isError = false)
                    }
                    is SettingsViewModel.TestResult.Failure -> {
                        FireSmsResultBanner(text = "Connection failed: ${result.message}", isError = true)
                    }
                    null -> {}
                }
            }

            FireSmsSection(
                title = "Permissions",
                subtitle = "What FireSMS is allowed to do on this phone"
            ) {
                SetupCard {
                    PermissionStatusRow(
                        title = "SMS access",
                        description = "Required to recognise bank messages.",
                        permission = smsPermission,
                        required = true,
                        onRequest = { smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS) }
                    )
                    PermissionStatusRow(
                        title = "Notifications",
                        description = "Tells you when a transaction is added, fails, or an update is available.",
                        permission = notificationPermission,
                        required = false,
                        onRequest = notificationPermission.permission?.let { permission ->
                            { notificationPermissionLauncher.launch(permission) }
                        }
                    )
                    BatteryStatusRow(battery)
                }
            }

            FireSmsSection(
                title = "Sync preference",
                subtitle = "When to sync queued transactions"
            ) {
                FireSmsPreferenceOption(
                    title = "Wi-Fi or mobile data",
                    description = "Sync whenever the device has an internet connection.",
                    selected = syncNetworkPolicy == "any_network",
                    onClick = {
                        if (syncNetworkPolicy != "any_network") {
                            viewModel.saveSyncNetworkPolicy("any_network")
                            syncSaveCount++
                        }
                    }
                )
                FireSmsPreferenceOption(
                    title = "Wi-Fi only",
                    description = "Wait for Wi-Fi before retrying queued transactions.",
                    selected = syncNetworkPolicy == "wifi_only",
                    onClick = {
                        if (syncNetworkPolicy != "wifi_only") {
                            viewModel.saveSyncNetworkPolicy("wifi_only")
                            syncSaveCount++
                        }
                    }
                )
            }

            FireSmsSection(
                title = "History",
                subtitle = "Automatically delete local activity older than the chosen period, including " +
                    "unsynced and unparsed messages. Transactions already in Firefly III are not affected."
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
                                    when {
                                        option == selected -> Unit
                                        // Shortening deletes history right away, so ask first.
                                        retentionDeletesMoreHistory(selected, option) -> pendingRetention = option
                                        else -> viewModel.saveHistoryRetention(option.value)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Material You colours exist only on Android 12+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                FireSmsSection(title = "Appearance") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = dynamicColor,
                                onValueChange = viewModel::setDynamicColor,
                                role = Role.Switch
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Use wallpaper colours", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Match FireSMS to your wallpaper instead of the FireSMS blue.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        // The row is the toggle target, so the switch itself isn't separately focusable.
                        Switch(checked = dynamicColor, onCheckedChange = null)
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

            FireSmsSection(
                title = "App updates",
                subtitle = "Checks GitHub daily for stable releases. Nothing downloads automatically."
            ) {
                Text("Installed version: ${BuildConfig.VERSION_NAME}")
                val release = updateSnapshot.availableRelease
                if (release != null) {
                    FireSmsResultBanner(
                        text = "FireSMS ${release.version} is available.",
                        isError = false
                    )
                    Button(onClick = {
                        try {
                            uriHandler.openUri(release.pageUrl)
                        } catch (_: Exception) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("No browser available to open GitHub.")
                            }
                        }
                    }) {
                        Text("View release on GitHub")
                    }
                    TextButton(onClick = { viewModel.ignoreUpdate(release.version) }) {
                        Text("Ignore this update")
                    }
                    Text(
                        "Review the release and download its APK when you're ready. Android will ask you to approve installation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                updateSnapshot.ignoredVersion?.let { version ->
                    Text(
                        "Ignoring FireSMS $version. Newer releases will still be offered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = viewModel::clearIgnoredUpdate) {
                        Text("Stop ignoring this update")
                    }
                }
                OutlinedButton(
                    onClick = viewModel::checkForUpdates,
                    enabled = updateCheckState != SettingsViewModel.UpdateCheckState.Checking
                ) {
                    Text(if (updateCheckState == SettingsViewModel.UpdateCheckState.Checking) "Checking…" else "Check for updates")
                }
                when (val state = updateCheckState) {
                    SettingsViewModel.UpdateCheckState.UpToDate -> {
                        if (release == null) {
                            FireSmsResultBanner(
                                text = if (updateSnapshot.ignoredVersion != null) {
                                    "No other newer stable release is available."
                                } else {
                                    "You're up to date. No newer stable release is available."
                                },
                                isError = false
                            )
                        }
                    }
                    is SettingsViewModel.UpdateCheckState.Failure ->
                        FireSmsResultBanner(text = state.message, isError = true)
                    else -> Unit
                }
                updatePreferenceError?.let { message ->
                    FireSmsResultBanner(text = message, isError = true)
                }
                if (updateSnapshot.lastCheckedAt > 0) {
                    Text(
                        "Last successful check: ${java.text.DateFormat.getDateTimeInstance().format(Date(updateSnapshot.lastCheckedAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "Enable notifications to receive update alerts. Background checks may be delayed by Android battery or network restrictions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FireSmsSection(title = "About") {
                Text(
                    "FireSMS v${BuildConfig.VERSION_NAME}",
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

    pendingLeave?.let { action ->
        FireSmsConfirmDialog(
            title = "Discard connection changes?",
            message = "Your server address and access token edits haven't been saved.",
            confirmText = "Discard",
            dismissText = "Keep editing",
            onConfirm = {
                pendingLeave = null
                urlInput = url
                tokenInput = token
                viewModel.clearTestResult()
                action()
            },
            onDismiss = { pendingLeave = null }
        )
    }

    pendingRetention?.let { option ->
        FireSmsConfirmDialog(
            title = "Keep history for ${option.label}?",
            message = "Older history will be deleted now. Unsynced and unparsed messages are also removed.",
            confirmText = "Delete older history",
            onConfirm = {
                pendingRetention = null
                viewModel.saveHistoryRetention(option.value)
            },
            onDismiss = { pendingRetention = null }
        )
    }

    pendingImportUri?.let { uri ->
        FireSmsConfirmDialog(
            title = "Restore FireSMS backup?",
            message = "This replaces all local history, queued retries, parser rules, title mappings, and " +
                "settings, including your Firefly connection. Transactions already in Firefly III are not changed.",
            confirmText = "Replace and restore",
            onConfirm = {
                pendingImportUri = null
                viewModel.importData(uri)
            },
            onDismiss = { pendingImportUri = null }
        )
    }

    // Only explicit changes show a snackbar; the flow also emits when the stored value first loads.
    LaunchedEffect(syncSaveCount) {
        if (syncSaveCount > 0) {
            snackbarHostState.showSnackbar("Sync preference saved")
        }
    }
}
