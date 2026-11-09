package com.firesms.app.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.firesms.app.data.remote.isCleartextUrl
import com.firesms.app.ui.components.FireSmsStatusPill
import com.firesms.app.ui.components.fireSmsCardBorder
import com.firesms.app.ui.components.fireSmsCardColors
import com.firesms.app.ui.theme.FireSmsTheme

// Shared by onboarding and Settings so both describe the connection and permissions identically.

internal const val CLEARTEXT_WARNING =
    "Your access token will be sent unencrypted. Use https unless this server is only on your home network."

// ─── Connection fields ─────────────────────────────────────────

@Composable
internal fun FireflyConnectionFields(
    url: String,
    onUrlChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text("Server URL") },
            placeholder = { Text("https://firefly.example.com") },
            singleLine = true,
            supportingText = if (isCleartextUrl(url)) {
                {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = FireSmsTheme.colors.warning,
                            modifier = Modifier.padding(end = 4.dp).size(16.dp)
                        )
                        Text(CLEARTEXT_WARNING, color = FireSmsTheme.colors.warning)
                    }
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            label = { Text("Personal access token") },
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (tokenVisible) "Hide access token" else "Show access token"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                onDone()
            }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ─── Permissions ───────────────────────────────────────────────

/**
 * Tracks one runtime permission, re-checked every time the screen resumes so a change made in
 * system settings shows up on return. A null [permission] means the API level doesn't need it.
 */
@Stable
internal class TrackedPermission(private val context: Context, val permission: String?) {
    var granted by mutableStateOf(isGranted())
        private set

    /** The last request was denied and Android won't show its dialog again. */
    var permanentlyDenied by mutableStateOf(false)
        private set

    fun refresh() {
        granted = isGranted()
        if (granted) permanentlyDenied = false
    }

    fun onRequestResult(isGranted: Boolean) {
        granted = isGranted
        // Denied without a rationale means "don't ask again" (or a second denial): only app
        // settings can grant it now.
        val activity = context.findActivity()
        permanentlyDenied = !isGranted && permission != null && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    private fun isGranted(): Boolean = permission == null ||
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
internal fun rememberTrackedPermission(permission: String?): TrackedPermission {
    val context = LocalContext.current
    val tracked = remember(context, permission) { TrackedPermission(context, permission) }
    LifecycleResumeEffect(tracked) {
        tracked.refresh()
        onPauseOrDispose { }
    }
    return tracked
}

internal fun openAppSettings(context: Context) {
    context.startFirst(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    )
}

// ─── Battery optimisation ──────────────────────────────────────

@Stable
internal class BatteryOptimizationState(private val context: Context) {
    /** True when Android lets FireSMS run in the background without battery restrictions. */
    var unrestricted by mutableStateOf(check())
        private set

    fun refresh() {
        unrestricted = check()
    }

    private fun check(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }
}

@Composable
internal fun rememberBatteryOptimizationState(): BatteryOptimizationState {
    val context = LocalContext.current
    val state = remember(context) { BatteryOptimizationState(context) }
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    return state
}

/**
 * Asks Android to exempt FireSMS from battery optimisation, falling back to the battery
 * optimisation list and then the app's settings page on phones that hide the direct prompt.
 */
@SuppressLint("BatteryLife")
internal fun requestUnrestrictedBattery(context: Context, alreadyUnrestricted: Boolean) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.fromParts("package", context.packageName, null)
    )
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val details = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    if (alreadyUnrestricted) context.startFirst(list, details) else context.startFirst(direct, list, details)
}

internal const val BATTERY_EXPLANATION =
    "Some phones delay or stop background sync to save battery, so queued transactions can reach " +
        "Firefly III late. Unrestricted battery use lets FireSMS sync on time."

// ─── Rows and pills ────────────────────────────────────────────

/** One status row: label and explanation on the left, a status pill on the right. */
@Composable
internal fun SetupStatusRow(
    title: String,
    description: String,
    statusLabel: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    action: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            FireSmsStatusPill(label = statusLabel, color = statusColor)
        }
        action?.invoke(this)
    }
}

/**
 * A permission row with its pill and, when not granted, the action that can still grant it:
 * the system prompt, or app settings once Android has stopped asking.
 */
@Composable
internal fun PermissionStatusRow(
    title: String,
    description: String,
    permission: TrackedPermission,
    required: Boolean,
    onRequest: (() -> Unit)?
) {
    val context = LocalContext.current
    val (label, color) = when {
        permission.granted -> "Allowed" to FireSmsTheme.colors.success
        permission.permanentlyDenied -> "Blocked" to MaterialTheme.colorScheme.error
        required -> "Not allowed" to MaterialTheme.colorScheme.error
        else -> "Not allowed" to FireSmsTheme.colors.warning
    }
    SetupStatusRow(
        title = title,
        description = description,
        statusLabel = label,
        statusColor = color,
        action = when {
            permission.granted -> null
            permission.permanentlyDenied -> {
                {
                    Text(
                        "Android won't ask again. Open app settings, choose Permissions, and allow it there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
                }
            }
            onRequest != null -> {
                { OutlinedButton(onClick = onRequest) { Text("Allow") } }
            }
            else -> null
        }
    )
}

@Composable
internal fun BatteryStatusRow(state: BatteryOptimizationState) {
    val context = LocalContext.current
    SetupStatusRow(
        title = "Background sync",
        description = BATTERY_EXPLANATION,
        statusLabel = if (state.unrestricted) "Unrestricted" else "Optimised",
        statusColor = if (state.unrestricted) FireSmsTheme.colors.success else FireSmsTheme.colors.warning
    ) {
        if (state.unrestricted) {
            TextButton(onClick = { requestUnrestrictedBattery(context, alreadyUnrestricted = true) }) {
                Text("Battery settings")
            }
        } else {
            OutlinedButton(onClick = { requestUnrestrictedBattery(context, alreadyUnrestricted = false) }) {
                Text("Allow unrestricted battery use")
            }
        }
    }
}

/** Groups rows in the shared card style. */
@Composable
internal fun SetupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = fireSmsCardColors(),
        border = fireSmsCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

// ─── Helpers ───────────────────────────────────────────────────

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Starts the first intent the device can handle; some OEM builds drop settings screens. */
private fun Context.startFirst(vararg intents: Intent) {
    val activity = findActivity()
    for (intent in intents) {
        try {
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        } catch (_: ActivityNotFoundException) {
            continue
        } catch (_: SecurityException) {
            continue
        }
    }
}
