package com.firesms.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

private const val MAX_SUGGESTIONS = 15

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FireSmsAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    var expanded by remember { mutableStateOf(false) }
    // Only the first MAX_SUGGESTIONS matches are shown, so stop scanning once they are found.
    val filtered = remember(suggestions, value) {
        if (value.isBlank()) suggestions.take(MAX_SUGGESTIONS)
        else suggestions.asSequence()
            .filter { it.contains(value, ignoreCase = true) }
            .take(MAX_SUGGESTIONS)
            .toList()
    }

    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = it.isNotEmpty()
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = keyboardOptions,
            leadingIcon = leadingIcon,
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
        )
        ExposedDropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false }
        ) {
            filtered.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onValueChange(name)
                        expanded = false
                    }
                )
            }
        }
    }
}
