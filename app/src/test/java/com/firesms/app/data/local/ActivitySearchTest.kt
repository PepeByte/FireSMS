package com.firesms.app.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivitySearchTest {
    private fun split(description: String, amount: String) =
        StoredTransactionSplit(type = "withdrawal", amount = amount, description = description)

    @Test
    fun `search text holds descriptions and amounts in lower case`() {
        val json = StoredTransactionSnapshot(transactions = listOf(split("Coffee SHOP", "12.5"))).encode()
        val text = transactionSearchText(json)
        assertTrue("coffee shop" in text)
        assertTrue("12.5" in text)
        assertTrue("12.50" in text, "Display form of the amount is searchable")
    }

    @Test
    fun `split transactions include the group title and total`() {
        val json = StoredTransactionSnapshot(
            groupTitle = "Groceries",
            transactions = listOf(split("Milk", "2.25"), split("Bread", "3.75"))
        ).encode()
        val text = transactionSearchText(json)
        assertTrue("groceries" in text)
        assertTrue("milk" in text && "bread" in text)
        assertTrue("6.00" in text, "Total of the splits is searchable")
    }

    @Test
    fun `missing or unreadable snapshots have no search text`() {
        assertEquals("", transactionSearchText(null))
        assertEquals("", transactionSearchText(""))
        assertEquals("", transactionSearchText("{not json"))
    }

    @Test
    fun `search pattern escapes LIKE wildcards`() {
        assertNull(activitySearchPattern("   "))
        assertEquals("%coffee%", activitySearchPattern(" Coffee "))
        assertEquals("%50\\%%", activitySearchPattern("50%"))
        assertEquals("%a\\_b%", activitySearchPattern("a_b"))
        assertEquals("%a\\\\b%", activitySearchPattern("a\\b"))
    }
}
