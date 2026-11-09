package com.firesms.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import com.firesms.app.ui.theme.FireSmsTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// ─── Scaffold ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FireSmsScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    // The bar tints itself once content scrolls underneath it.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost
    ) { padding ->
        content(padding)
    }
}

enum class FireSmsTopLevelDestination(val label: String) {
    ACTIVITY("Activity"),
    AUTOMATION("Automation"),
    SETTINGS("Settings")
}

@Composable
fun FireSmsBottomBar(
    selected: FireSmsTopLevelDestination,
    onActivity: () -> Unit,
    onAutomation: () -> Unit,
    onSettings: () -> Unit
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        FireSmsTopLevelDestination.entries.forEach { destination ->
            val onClick = when (destination) {
                FireSmsTopLevelDestination.ACTIVITY -> onActivity
                FireSmsTopLevelDestination.AUTOMATION -> onAutomation
                FireSmsTopLevelDestination.SETTINGS -> onSettings
            }
            val isSelected = selected == destination
            val icon: ImageVector = when (destination) {
                FireSmsTopLevelDestination.ACTIVITY ->
                    if (isSelected) Icons.AutoMirrored.Filled.ReceiptLong else Icons.AutoMirrored.Outlined.ReceiptLong
                FireSmsTopLevelDestination.AUTOMATION ->
                    if (isSelected) Icons.Filled.AutoFixHigh else Icons.Outlined.AutoFixHigh
                FireSmsTopLevelDestination.SETTINGS ->
                    if (isSelected) Icons.Filled.Settings else Icons.Outlined.Settings
            }
            NavigationBarItem(
                selected = isSelected,
                onClick = onClick,
                // The visible label already names the destination.
                icon = { Icon(icon, contentDescription = null) },
                alwaysShowLabel = true,
                label = { Text(destination.label) }
            )
        }
    }
}

// ─── Screen wrapper ────────────────────────────────────────────

@Composable
fun FireSmsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .align(Alignment.TopCenter)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

// ─── Section card ──────────────────────────────────────────────

@Composable
fun FireSmsSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        content()
        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

// ─── Empty state ───────────────────────────────────────────────

@Composable
fun FireSmsEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (icon != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(16.dp).size(32.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

// ─── Status pill ───────────────────────────────────────────────

@Composable
fun FireSmsStatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

// ─── Result banner ─────────────────────────────────────────────

@Composable
fun FireSmsResultBanner(
    text: String,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            }
        )
    }
}

// ─── Preference option (selectable row with radio) ─────────────

@Composable
fun FireSmsPreferenceOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            // The whole row is the radio target, so the button itself is not separately focusable.
            RadioButton(selected = selected, onClick = null)
        }
    }
}

// ─── Confirm dialog ────────────────────────────────────────────

@Composable
fun FireSmsConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    dismissText: String = "Cancel"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            if (destructive) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(confirmText)
                }
            } else {
                TextButton(onClick = onConfirm) { Text(confirmText) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
}

// ─── Unsaved changes guard ─────────────────────────────────────

/**
 * Intercepts system back and the toolbar back arrow while [isDirty], asking before edits are thrown away.
 * Use the returned lambda as the screen's `onBack`.
 */
@Composable
fun rememberUnsavedChangesGuard(isDirty: Boolean, onBack: () -> Unit): () -> Unit {
    var showDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = isDirty) { showDialog = true }
    if (showDialog) {
        FireSmsConfirmDialog(
            title = "Discard changes?",
            message = "Your unsaved changes will be lost.",
            confirmText = "Discard",
            dismissText = "Keep editing",
            onConfirm = {
                showDialog = false
                onBack()
            },
            onDismiss = { showDialog = false }
        )
    }
    return { if (isDirty) showDialog = true else onBack() }
}

// ─── List card ─────────────────────────────────────────────────

@Composable
fun FireSmsListCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    headline: @Composable RowScope.() -> Unit,
    supporting: @Composable ColumnScope.() -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val content: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = headline)
                if (trailing != null) {
                    trailing()
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(content = supporting)
            if (actions != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions
                )
            }
        }
    }
    // Card(onClick) clips the ripple to the rounded shape; a .clickable modifier would not.
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = fireSmsCardColors(),
            border = fireSmsCardBorder(),
            content = content
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = fireSmsCardColors(),
            border = fireSmsCardBorder(),
            content = content
        )
    }
}

/** The one card style used for list items and form groups across the app. */
@Composable
fun fireSmsCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    contentColor = MaterialTheme.colorScheme.onSurface
)

@Composable
fun fireSmsCardBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

// ─── Status helpers ────────────────────────────────────────────

fun statusLabel(status: String): String = when (status) {
    "success" -> "Synced"
    "failed" -> "Failed"
    "queued" -> "Waiting to sync"
    "unparsed" -> "Needs rule"
    "pending" -> "Processing"
    else -> "Unknown"
}

@Composable
fun statusColor(status: String): Color = when (status) {
    "success" -> FireSmsTheme.colors.success
    "failed" -> MaterialTheme.colorScheme.error
    "queued", "unparsed" -> FireSmsTheme.colors.warning
    "pending" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
