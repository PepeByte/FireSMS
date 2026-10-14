package com.firesms.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class AmountFormatTest {

    @Test
    fun `firefly amounts drop trailing zeros but keep two decimals`() {
        assertEquals("12.34", formatDisplayAmount("12.340000000000"))
        assertEquals("50.00", formatDisplayAmount("50.000000000000"))
        assertEquals("1500.00", formatDisplayAmount("1500"))
        assertEquals("0.125", formatDisplayAmount("0.125000000000"))
        assertEquals("abc", formatDisplayAmount("abc"))
    }
}
