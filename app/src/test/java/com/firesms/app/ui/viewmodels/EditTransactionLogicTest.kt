package com.firesms.app.ui.viewmodels

import com.firesms.app.data.remote.FireflyError
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditTransactionLogicTest {

    // ── Amount parsing ──

    @Test
    fun `amounts accept a comma as the decimal separator or as thousands separators`() {
        assertEquals(BigDecimal("12.50"), parseEditAmount(" 12,50 "))
        assertEquals(BigDecimal("1000.50"), parseEditAmount("1,000.50"))
        assertEquals(BigDecimal("1000000"), parseEditAmount("1,000,000"))
        assertNull(parseEditAmount(""))
        assertNull(parseEditAmount("abc"))
    }

    // ── New split allocation ──

    @Test
    fun `new split takes the unallocated rest`() {
        val result = allocateNewSplit("100.00", listOf("60.00"))
        assertEquals(listOf("60.00"), result.amounts)
        assertEquals("40.00", result.newAmount)
    }

    @Test
    fun `new split halves the last split when the total is fully allocated`() {
        val result = allocateNewSplit("100.00", listOf("100.00"))
        assertEquals(listOf("50.00"), result.amounts)
        assertEquals("50.00", result.newAmount)
    }

    @Test
    fun `halving an odd amount keeps the extra cent on the existing split`() {
        val result = allocateNewSplit("100.01", listOf("60.00", "40.01"))
        assertEquals(listOf("60.00", "20.01"), result.amounts)
        assertEquals("20.00", result.newAmount)
        val sum = (result.amounts + result.newAmount).sumOf { BigDecimal(it) }
        assertEquals(BigDecimal("100.01"), sum)
    }

    @Test
    fun `halving skips trailing splits too small to divide`() {
        val result = allocateNewSplit("10.01", listOf("10.00", "0.01"))
        assertEquals(listOf("5.00", "0.01"), result.amounts)
        assertEquals("5.00", result.newAmount)
    }

    @Test
    fun `new split is left empty when over-allocated or the total is unknown`() {
        assertEquals(NewSplitAmounts(listOf("120.00"), ""), allocateNewSplit("100.00", listOf("120.00")))
        assertEquals(NewSplitAmounts(listOf("5"), ""), allocateNewSplit("", listOf("5")))
        assertEquals(NewSplitAmounts(listOf("0.01"), ""), allocateNewSplit("0.01", listOf("0.01")))
    }

    @Test
    fun `invalid amounts count as unallocated`() {
        assertEquals("100.00", allocateNewSplit("100.00", listOf("", "abc")).newAmount)
    }

    // ── Allocation summary ──

    @Test
    fun `allocation text reports what is left, over or balanced`() {
        assertEquals(
            "Allocated ₹60.00 of ₹100.00 · ₹40.00 left",
            allocationText(allocationOf("100", listOf("60"))!!, "₹")
        )
        assertEquals(
            "Allocated INR 105.00 of INR 100.00 · INR 5.00 over",
            allocationText(allocationOf("100", listOf("60", "45"))!!, "INR")
        )
        val balanced = allocationOf("100.00", listOf("60", "40.00"))!!
        assertTrue(balanced.isBalanced)
        assertEquals("Allocated 100.00 of 100.00 · balanced", allocationText(balanced, ""))
    }

    // ── Validation ──

    @Test
    fun `single transaction needs only a positive amount`() {
        assertTrue(validateEdit(listOf("10"), groupTitle = "", total = "99").isValid)
        val blank = validateEdit(listOf(" "), groupTitle = "", total = "10")
        assertEquals(mapOf(0 to "Required"), blank.amountErrors)
        val zero = validateEdit(listOf("0"), groupTitle = "", total = "10")
        assertEquals(mapOf(0 to "Enter an amount above zero"), zero.amountErrors)
    }

    @Test
    fun `splits require a group title`() {
        val result = validateEdit(listOf("50", "50"), groupTitle = " ", total = "100")
        assertEquals("Required for split transactions", result.groupTitleError)
        assertNull(result.totalError)
        assertEquals("Fix the highlighted fields", result.summary)
    }

    @Test
    fun `splits must add up to the total`() {
        val result = validateEdit(listOf("50", "40"), groupTitle = "Groceries", total = "100")
        assertFalse(result.isValid)
        assertEquals("Split amounts (90.00) must add up to the total (100.00)", result.totalError)
        assertEquals(result.totalError, result.summary)
        assertTrue(validateEdit(listOf("50", "50.00"), groupTitle = "Groceries", total = "100.00").isValid)
    }

    @Test
    fun `amount errors are reported per split`() {
        val result = validateEdit(listOf("50", "", "x"), groupTitle = "G", total = "100")
        assertEquals(setOf(1, 2), result.amountErrors.keys)
        assertNull(result.totalError)
    }

    // ── Firefly errors ──

    @Test
    fun `validation errors name the split and field`() {
        val raw = """Firefly returned HTTP 422: {"message":"The given data was invalid.",""" +
            """"errors":{"transactions.1.source_name":["The selected transactions.1.source_name is invalid."]}}"""
        val result = describeSaveError(FireflyError(422, raw.substringAfter("422: ")))
        assertEquals(
            "Firefly didn't accept the changes: The selected split 2 source account is invalid.",
            result.message
        )
        assertEquals("Firefly returned HTTP 422: " + raw.substringAfter("422: "), result.detail)
    }

    @Test
    fun `truncated or non-json bodies still give a readable message`() {
        assertEquals(
            "Firefly didn't accept the changes: The given data was invalid.",
            describeFireflyError(422, """HTTP 422: {"message":"The given data was invalid.","errors":{"transac…""").message
        )
        assertEquals(
            "Firefly rejected the access token. Check it in Settings.",
            describeFireflyError(401, "HTTP 401: <html>Unauthorized</html>").message
        )
        assertEquals(
            "Firefly couldn't save the changes (HTTP 409).",
            describeFireflyError(409, "HTTP 409: conflict").message
        )
    }
}
