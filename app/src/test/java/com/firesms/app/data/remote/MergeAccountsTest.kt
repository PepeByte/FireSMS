package com.firesms.app.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals

class MergeAccountsTest {
    private fun account(id: String, name: String, type: String) =
        AccountResource(id, AccountAttributes(name, type))

    @Test
    fun addsLiabilitiesMissingFromAll() {
        val all = listOf(account("1", "Bank", "asset"), account("2", "Loan", "liabilities"))
        val liabilities = listOf(account("2", "Loan", "liabilities"), account("3", "Visa", "liabilities"))

        val merged = mergeAccounts(all, liabilities)

        assertEquals(listOf("1", "2", "3"), merged.map { it.id })
    }
}
