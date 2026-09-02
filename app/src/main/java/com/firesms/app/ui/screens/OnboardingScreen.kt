package com.firesms.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firesms.app.FireSmsApp
import com.firesms.app.ui.components.FireSmsResultBanner
import com.firesms.app.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

private const val ONBOARDING_PAGE_COUNT = 4

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val app = context.applicationContext as FireSmsApp
    val savedUrl by settingsViewModel.fireflyUrl.collectAsState()
    val savedToken by settingsViewModel.fireflyToken.collectAsState()
    val testResult by settingsViewModel.testResult.collectAsState()
    val isTesting by settingsViewModel.isTesting.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var smsPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

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
        smsPermissionGranted = result[Manifest.permission.RECEIVE_SMS] ?: smsPermissionGranted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionGranted =
                result[Manifest.permission.POST_NOTIFICATIONS] ?: notificationPermissionGranted
        }
    }

    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == ONBOARDING_PAGE_COUNT - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Step ${pagerState.currentPage + 1} of $ONBOARDING_PAGE_COUNT",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp)
        )

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> SetupPage(
                    icon = Icons.Default.Home,
                    title = "Your Firefly transaction assistant",
                    description = "FireSMS reads matching bank messages on this device and sends the transaction details to your Firefly III server."
                ) {
                    SetupNote("Private by design", "Messages and parser rules stay on your device unless a transaction is sent to your server.")
                }
                1 -> SetupPage(
                    icon = Icons.Default.Settings,
                    title = "Allow message access",
                    description = "FireSMS needs SMS access to recognize bank messages. Notifications let you know when a transaction is added."
                ) {
                    PermissionRow("SMS access", smsPermissionGranted, required = true)
                    PermissionRow("Notifications", notificationPermissionGranted, required = false)
                    Button(
                        onClick = {
                            val permissions = buildList {
                                if (!smsPermissionGranted) add(Manifest.permission.RECEIVE_SMS)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !notificationPermissionGranted
                                ) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
                        },
                        enabled = !smsPermissionGranted || !notificationPermissionGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (smsPermissionGranted && notificationPermissionGranted) "Access granted" else "Allow access")
                    }
                }
                2 -> SetupPage(
                    icon = Icons.Default.Build,
                    title = "Connect Firefly III",
                    description = "Enter your server address and personal access token. You can change these later in Settings."
                ) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            urlInput = it
                            settingsViewModel.clearTestResult()
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
                            settingsViewModel.clearTestResult()
                        },
                        label = { Text("Personal access token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            settingsViewModel.saveSettings(urlInput, tokenInput)
                            settingsViewModel.testConnection(urlInput, tokenInput)
                        },
                        enabled = urlInput.isNotBlank() && tokenInput.isNotBlank() && !isTesting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isTesting) "Checking connection…" else "Save and test")
                    }
                    when (val result = testResult) {
                        SettingsViewModel.TestResult.Success -> FireSmsResultBanner(
                            text = "Connected to Firefly III",
                            isError = false
                        )
                        is SettingsViewModel.TestResult.Failure -> FireSmsResultBanner(
                            text = "Connection failed: ${result.message}",
                            isError = true
                        )
                        null -> Unit
                    }
                }
                else -> SetupPage(
                    icon = Icons.Default.CheckCircle,
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
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Back") }
            }
            Button(
                onClick = {
                    if (isLastPage) {
                        scope.launch {
                            app.preferencesManager.setOnboardingCompleted()
                            onComplete()
                        }
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isLastPage) "Open FireSMS" else "Continue")
            }
        }

        if (!isLastPage) {
            TextButton(
                onClick = {
                    scope.launch {
                        app.preferencesManager.setOnboardingCompleted()
                        onComplete()
                    }
                },
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text("Set up later")
            }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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

@Composable
private fun PermissionRow(label: String, granted: Boolean, required: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                if (required) "Required" else "Recommended",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (granted) "Granted" else "Not granted",
            style = MaterialTheme.typography.labelLarge,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun SetupNote(title: String, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
