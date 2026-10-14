package com.firesms.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.firesms.app.data.remote.AccountResource
import com.firesms.app.ui.viewmodels.AccountCheck

/**
 * Text field that searches Firefly accounts and shows whether the entered name exists there.
 * [candidates] should already be filtered to the account types that fit this field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FireflyAccountField(
    value: String,
    onValueChange: (String) -> Unit,
    onAccountSelected: (AccountResource) -> Unit,
    label: String,
    placeholder: String,
    candidates: List<AccountResource>,
    check: AccountCheck,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val suggestions = remember(candidates, value) {
        val query = value.trim()
        candidates
            .filter { query.isEmpty() || it.attributes.name.contains(query, ignoreCase = true) }
            .take(20)
    }
    val menuVisible = expanded && suggestions.isNotEmpty()

    ExposedDropdownMenuBox(
        expanded = menuVisible,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            isError = check == AccountCheck.NOT_FOUND,
            trailingIcon = {
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    check == AccountCheck.FOUND -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Found in Firefly",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    else -> ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuVisible)
                }
            },
            supportingText = accountCheckMessage(check)?.let { message -> @Composable { Text(message) } },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
        )
        ExposedDropdownMenu(
            expanded = menuVisible,
            onDismissRequest = { expanded = false }
        ) {
            suggestions.forEach { account ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(account.attributes.name)
                            Text(
                                accountTypeLabel(account.attributes.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onAccountSelected(account)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun accountCheckMessage(check: AccountCheck): String? = when (check) {
    AccountCheck.FOUND -> "Found in Firefly"
    AccountCheck.WILL_BE_CREATED -> "Not in Firefly yet. Firefly will create it with the first transaction."
    AccountCheck.NOT_FOUND -> "Not one of your Firefly accounts. Pick one from the list."
    AccountCheck.EMPTY, AccountCheck.UNVERIFIED -> null
}

private fun accountTypeLabel(type: String): String = when (type) {
    "asset" -> "Asset account"
    "expense" -> "Expense account"
    "revenue" -> "Revenue account"
    "liability", "liabilities" -> "Liability"
    else -> type.replaceFirstChar { it.uppercase() }
}
