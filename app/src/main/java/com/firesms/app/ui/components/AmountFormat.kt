package com.firesms.app.ui.components

/**
 * Formats an amount for display. Firefly III returns amounts with 12 decimal places
 * (e.g. "12.340000000000"); trailing zeros are dropped, keeping at least two decimals.
 * Values that are not numbers are returned unchanged.
 */
fun formatDisplayAmount(raw: String): String {
    val value = raw.trim().toBigDecimalOrNull() ?: return raw
    val stripped = value.stripTrailingZeros()
    return (if (stripped.scale() < 2) stripped.setScale(2) else stripped).toPlainString()
}
