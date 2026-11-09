package com.firesms.app.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.FireSmsApp
import com.firesms.app.ui.components.FireSmsConfirmDialog
import com.firesms.app.ui.components.FireSmsResultBanner
import com.firesms.app.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

private const val PAGE_WELCOME = 0
private const val PAGE_PERMISSIONS = 1
private const val PAGE_CONNECTION = 2
private const val PAGE_BATTERY = 3
private const val ONBOARDING_PAGE_COUNT = 5

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val app = context.applicationContext as FireSmsApp
    val savedUrl by settingsViewModel.fireflyUrl.collectAsStateWithLifecycle()
    val savedToken by settingsViewModel.fireflyToken.collectAsStateWithLifecycle()
    val testResult by settingsViewModel.testResult.collectAsStateWithLifecycle()
    val isTesting by settingsViewModel.isTesting.collectAsStateWithLifecycle()

    var urlInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var showUnverifiedDialog by remember { mutableStateOf(false) }

    val smsPermission = rememberTrackedPermission(Manifest.permission.RECEIVE_SMS)
    val notificationPermission = rememberTrackedPermission(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
    )
    val battery = rememberBatteryOptimizationState()

    LaunchedEffect(Unit) {
        if (app.preferencesManager.isOnboardingCompleted()) onComplete()
    }
    LaunchedEffect(savedUrl, savedToken) {
        if (urlInput.isBlank()) urlInput = savedUrl
        if (tokenInput.isBlank()) tokenInput = savedToken
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result[Manifest.permission.RECEIVE_SMS]?.let(smsPermission::onRequestResult)
        notificationPermission.permission?.let { permission ->
            result[permission]?.let(notificationPermission::onRequestResult)
        }
    }

    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == ONBOARDING_PAGE_COUNT - 1
    val goToPage: (Int) -> Unit = { page -> scope.launch { pagerState.animateScrollToPage(page) } }
    val finish: () -> Unit = {
        scope.launch {
            app.preferencesManager.setOnboardingCompleted()
            onComplete()
        }
    }

    BackHandler(enabled = pagerState.currentPage > 0) { goToPage(pagerState.currentPage - 1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PageIndicator(
            current = pagerState.currentPage,
            count = ONBOARDING_PAGE_COUNT,
            modifier = Modifier.padding(top = 20.dp)
        )

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                PAGE_WELCOME -> SetupPage(
                    icon = Icons.Filled.AutoAwesome,
                    title = "Your Firefly transaction assistant",
                    description = "FireSMS reads matching bank messages on this device and sends the transaction details to your Firefly III server."
                ) {
                    SetupNote("Private by design", "Messages and parser rules stay on your device unless a transaction is sent to your server.")
                }
                PAGE_PERMISSIONS -> SetupPage(
                    icon = Icons.Filled.Sms,
                    title = "Allow message access",
                    description = "FireSMS needs SMS access to recognise bank messages. Notifications let you know when a transaction is added."
                ) {
                    SetupCard {
                        PermissionStatusRow(
                            title = "SMS access",
                            description = "Required",
                            permission = smsPermission,
                            required = true,
                            onRequest = null
                        )
                        PermissionStatusRow(
                            title = "Notifications",
                            description = "Recommended",
                            permission = notificationPermission,
                            required = false,
                            onRequest = null
                        )
                    }
                    // One prompt for everything Android can still ask for; blocked permissions
                    // show their own "Open app settings" action above.
                    val requestable = buildList {
                        if (!smsPermission.granted && !smsPermission.permanentlyDenied) {
                            add(Manifest.permission.RECEIVE_SMS)
                        }
                        notificationPermission.permission?.let { permission ->
                            if (!notificationPermission.granted && !notificationPermission.permanentlyDenied) {
                                add(permission)
                            }
                        }
                    }
                    val allGranted = smsPermission.granted && notificationPermission.granted
                    if (!allGranted) {
                        Button(
                            onClick = { permissionLauncher.launch(requestable.toTypedArray()) },
                            enabled = requestable.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Allow access")
                        }
                    }
                }
                PAGE_CONNECTION -> SetupPage(
                    icon = Icons.Filled.Cloud,
                    title = "Connect Firefly III",
                    description = "Enter your server address and personal access token. You can change these later in Settings."
                ) {
                    FireflyConnectionFields(
                        url = urlInput,
                        onUrlChange = {
                            urlInput = it
                            settingsViewModel.clearTestResult()
                        },
                        token = tokenInput,
                        onTokenChange = {
                            tokenInput = it
                            settingsViewModel.clearTestResult()
                        },
                        onDone = {
                            if (urlInput.isNotBlank() && tokenInput.isNotBlank() && !isTesting) {
                                settingsViewModel.testConnection(urlInput, tokenInput, saveOnSuccess = true)
                            }
                        }
                    )
                    Button(
                        onClick = { settingsViewModel.testConnection(urlInput, tokenInput, saveOnSuccess = true) },
                        enabled = urlInput.isNotBlank() && tokenInput.isNotBlank() && !isTesting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isTesting) "Checking connection…" else "Test connection")
                    }
                    when (val result = testResult) {
                        SettingsViewModel.TestResult.Success -> FireSmsResultBanner(
                            text = "Connected to Firefly III. Your connection is saved.",
                            isError = false
                        )
                        is SettingsViewModel.TestResult.Failure -> FireSmsResultBanner(
                            text = "Connection failed: ${result.message}",
                            isError = true
                        )
                        null -> Text(
                            "Details are saved once the connection test succeeds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                PAGE_BATTERY -> SetupPage(
                    icon = Icons.Filled.BatteryChargingFull,
                    title = "Keep sync on time",
                    description = "Optional. If Firefly III can't be reached, FireSMS queues transactions and syncs them in the background."
                ) {
                    SetupCard { BatteryStatusRow(battery) }
                    Text(
                        "You can change this later in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> SetupPage(
                    icon = Icons.Filled.CheckCircle,
                    title = "Ready when you are",
                    description = "Next, add a parser rule for your bank. FireSMS will show any message it cannot understand under Needs attention."
                ) {
                    SetupNote("What happens next", "Matching SMS → parser rule → transaction preview → Firefly III")
                    Text(
                        "You can import a rule, build one from a sample message, or configure it later under Automation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (pagerState.currentPage > 0) {
                OutlinedButton(
                    onClick = { goToPage(pagerState.currentPage - 1) },
                    modifier = Modifier.weight(1f)
                ) { Text("Back") }
            }
            Button(
                onClick = {
                    when {
                        isLastPage -> finish()
                        // Only a tested connection is saved silently; anything else is confirmed.
                        pagerState.currentPage == PAGE_CONNECTION &&
                            testResult != SettingsViewModel.TestResult.Success -> showUnverifiedDialog = true
                        else -> goToPage(pagerState.currentPage + 1)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isLastPage) "Open FireSMS" else "Continue")
            }
        }

        if (!isLastPage) {
            TextButton(
                onClick = finish,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text("Set up later")
            }
        }
    }

    if (showUnverifiedDialog) {
        val hasDetails = urlInput.isNotBlank() && tokenInput.isNotBlank()
        FireSmsConfirmDialog(
            title = "Connection not verified — continue anyway?",
            message = if (hasDetails) {
                "FireSMS couldn't confirm that these details work. They'll be saved, and transactions " +
                    "will wait in the queue until the connection works. You can change them in Settings."
            } else {
                "You haven't connected Firefly III yet. Transactions will wait in the queue until you " +
                    "add a connection in Settings."
            },
            confirmText = "Continue anyway",
            dismissText = "Stay here",
            destructive = false,
            onConfirm = {
                showUnverifiedDialog = false
                if (hasDetails) settingsViewModel.saveSettings(urlInput, tokenInput)
                goToPage(PAGE_CONNECTION + 1)
            },
            onDismiss = { showUnverifiedDialog = false }
        )
    }
}

@Composable
private fun PageIndicator(current: Int, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { contentDescription = "Step ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 24.dp else 8.dp, label = "pageIndicator")
            Box(
                modifier = Modifier
                    .size(width = width, height = 8.dp)
                    .background(
                        color = if (index <= current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun SetupPage(
    icon: ImageVector,
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    // verticalScroll gives the column unbounded height, so Arrangement.Center inside it does
    // nothing; the Box centres the column while it fits and lets it scroll once it doesn't.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SetupNote(title: String, body: String) {
    SetupCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
