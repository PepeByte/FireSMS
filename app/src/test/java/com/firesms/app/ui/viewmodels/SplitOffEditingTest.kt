package com.firesms.app.ui.viewmodels

import com.firesms.app.data.local.SplitOffRecord
import com.firesms.app.data.local.decodeSplitOffs
import com.firesms.app.data.local.encodeSplitOffs
import com.firesms.app.data.local.newSplitOffExternalId
import com.firesms.app.data.remote.FireflyTransactionSplit
import com.firesms.app.data.remote.LinkTypeAttributes
import com.firesms.app.data.remote.LinkTypeResource
import com.firesms.app.data.remote.relatedLinkTypeId
import com.firesms.app.data.remote.transactionWebUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitOffEditingTest {

    private val withdrawal = SharedPaymentFields(
        type = "withdrawal",
        date = "2026-11-25T19:42:00+05:45",
        sourceId = "1",
        sourceName = "Nabil",
        destinationId = "7",
        destinationName = "Cash"
    )

    private fun piece(
        amount: String = "1000",
        account: String = "Friend – Meetups",
        accountId: String? = null
    ) = SplitOffState(
        key = 1,
        externalId = "hash-split-abcd1234",
        accountName = account,
        accountId = accountId,
        parts = listOf(SplitOffPart(key = 2, amount = amount, description = "Momo House"))
    )

    // ── Allocation ──

    @Test
    fun `a new split-off takes half of the payer's share`() {
        val result = allocateSplitOff("2000.00", listOf("2000.00"), emptyList())
        assertEquals(listOf("1000.00"), result.amounts)
        assertEquals("1000.00", result.newAmount)
    }

    @Test
    fun `an odd paisa stays with the payer`() {
        val result = allocateSplitOff("1001.01", listOf("1001.01"), emptyList())
        assertEquals(listOf("500.51"), result.amounts)
        assertEquals("500.50", result.newAmount)
    }

    @Test
    fun `a second split-off halves the payer's share again, not the first split-off`() {
        val result = allocateSplitOff("2000.00", listOf("1000.00"), listOf("1000.00"))
        assertEquals(listOf("500.00"), result.amounts)
        assertEquals("500.00", result.newAmount)
    }

    @Test
    fun `a new split-off takes the unallocated rest first`() {
        val result = allocateSplitOff("2000.00", listOf("1500.00"), emptyList())
        assertEquals(listOf("1500.00"), result.amounts)
        assertEquals("500.00", result.newAmount)
    }

    @Test
    fun `changing a split-off amount moves the difference to the payer's last split`() {
        assertEquals(listOf("800.00"), rebalanceForSplitOff("2000", listOf("1000.00"), listOf("1200")))
        assertEquals(
            listOf("300.00", "900.00"),
            rebalanceForSplitOff("2000", listOf("300.00", "700.00"), listOf("800"))
        )
        assertEquals(listOf("500.00"), rebalanceForSplitOff("2000", listOf("1000.00"), listOf("1000", "500")))
    }

    @Test
    fun `clearing and retyping a split-off amount still balances`() {
        assertEquals(listOf("2000.00"), rebalanceForSplitOff("2000", listOf("1000.00"), listOf("")))
        assertEquals(listOf("1988.00"), rebalanceForSplitOff("2000", listOf("2000.00"), listOf("12.")))
    }

    @Test
    fun `rebalancing never empties the payer's split`() {
        assertNull(rebalanceForSplitOff("2000", listOf("1000.00"), listOf("2000")))
        assertNull(rebalanceForSplitOff("", listOf("1000.00"), listOf("500")))
    }

    @Test
    fun `removing a split-off gives its amount back to the payer`() {
        assertEquals(listOf("2000.00"), returnSplitOffAmount(listOf("1000.00"), "1000"))
        assertEquals(listOf("1000.00"), returnSplitOffAmount(listOf("1000.00"), "not a number"))
    }

    // ── Validation ──

    @Test
    fun `the payer's share and split-offs must add up to the payment`() {
        assertTrue(validateEdit(listOf("1000"), "", "2000", listOf("1000")).isValid)
        val unbalanced = validateEdit(listOf("1000"), "", "2000", listOf("900"))
        assertFalse(unbalanced.isValid)
        assertTrue(unbalanced.totalError!!.contains("1900.00"))
    }

    @Test
    fun `without split-offs a single transaction can still change its amount`() {
        assertTrue(validateEdit(listOf("1500"), "", "2000").isValid)
    }

    @Test
    fun `split-offs need an account, a positive amount and a withdrawal or deposit`() {
        val result = validateSplitOffs("withdrawal", listOf(piece(amount = "0", account = " ")))
        assertEquals("Enter an amount above zero", result.amountErrors[0]?.get(0))
        assertEquals("Choose an account", result.accountErrors[0])
        assertEquals(null, result.typeError)
        assertTrue(validateSplitOffs("transfer", listOf(piece())).typeError != null)
        assertTrue(validateSplitOffs("transfer", emptyList()).isValid)
    }

    // ── Requests ──

    @Test
    fun `a withdrawal split-off keeps the payer's source and goes to its own account`() {
        val body = buildSplitOffCreateRequest(withdrawal, piece()).transactions.single()
        assertEquals("withdrawal", body.type)
        assertEquals("1000.00", body.amount)
        assertEquals("1", body.sourceId)
        assertEquals("Nabil", body.sourceName)
        assertNull(body.destinationId)
        assertEquals("Friend – Meetups", body.destinationName)
        assertEquals("hash-split-abcd1234", body.externalId)
        assertEquals(withdrawal.date, body.date)
        assertNull(body.categoryName)
        assertNull(body.budgetName)
    }

    @Test
    fun `a deposit split-off comes from its own account into the payer's account`() {
        val deposit = withdrawal.copy(type = "deposit", sourceId = "9", sourceName = "Employer", destinationId = "1", destinationName = "Nabil")
        val body = buildSplitOffCreateRequest(deposit, piece(accountId = "42")).transactions.single()
        assertEquals("deposit", body.type)
        assertEquals("42", body.sourceId)
        assertEquals("Friend – Meetups", body.sourceName)
        assertEquals("1", body.destinationId)
        assertEquals("Nabil", body.destinationName)
    }

    @Test
    fun `an update keeps the split-off's journal id`() {
        val base = piece()
        val split = buildSplitOffUpdateRequest(
            withdrawal, base.copy(parts = base.parts.map { it.copy(transactionJournalId = "55") })
        ).transactions.single()
        assertEquals("55", split.transactionJournalId)
        assertEquals("Friend – Meetups", split.destinationName)
    }

    @Test
    fun `a stored split-off reads its account from the other party's side`() {
        val record = SplitOffRecord(fireflyId = "12", externalId = "x", transactionJournalId = "30")
        var key = 10L
        val withdrawalState = splitOffFromFirefly(
            1, record,
            listOf(
                FireflyTransactionSplit(
                    transactionJournalId = "31", type = "withdrawal", amount = "1000.000000000000",
                    sourceName = "Nabil", destinationId = "42", destinationName = "Friend"
                )
            ),
            groupTitle = null
        ) { key++ }
        assertEquals("Friend", withdrawalState.accountName)
        assertEquals("42", withdrawalState.accountId)
        assertEquals("1000.00", withdrawalState.amount)
        assertEquals("31", withdrawalState.transactionJournalId)
        assertTrue(withdrawalState.isSaved)

        val depositState = splitOffFromFirefly(
            1, record,
            listOf(FireflyTransactionSplit(type = "deposit", amount = "5", sourceName = "Friend", destinationName = "Nabil")),
            groupTitle = null
        ) { key++ }
        assertEquals("Friend", depositState.accountName)
    }

    // ── Storage and Firefly helpers ──

    @Test
    fun `split-off records survive a round trip and bad JSON reads as none`() {
        val records = listOf(SplitOffRecord(fireflyId = "12", externalId = "x", amount = "1000.00", linked = true))
        assertEquals(records, decodeSplitOffs(encodeSplitOffs(records)))
        assertNull(encodeSplitOffs(emptyList()))
        assertEquals(emptyList(), decodeSplitOffs("{broken"))
        assertEquals(emptyList(), decodeSplitOffs(null))
    }

    @Test
    fun `each split-off gets its own external id based on the SMS hash`() {
        val first = newSplitOffExternalId("abc")
        assertTrue(first.startsWith("abc-split-"))
        assertNotEquals(first, newSplitOffExternalId("abc"))
    }

    @Test
    fun `the Related link type is found by name or by its wording`() {
        fun type(id: String, name: String, outward: String? = null) =
            LinkTypeResource(id, LinkTypeAttributes(name = name, outward = outward))
        assertEquals("1", relatedLinkTypeId(listOf(type("3", "Refund"), type("1", "Related"))))
        assertEquals("4", relatedLinkTypeId(listOf(type("4", "Verwandt", outward = "relates to"))))
        assertNull(relatedLinkTypeId(listOf(type("3", "Refund"))))
    }

    @Test
    fun `the Firefly web link ignores a pasted api suffix`() {
        assertEquals("https://ff.example/transactions/show/12", transactionWebUrl("ff.example/api/v1/", "12"))
        assertEquals("http://10.0.0.2:8080/transactions/show/5", transactionWebUrl("http://10.0.0.2:8080", "5"))
    }
}

class SplitOffActivityTest {
    @Test
    fun `the Activity row sums split-offs in the transaction's currency`() {
        val json = encodeSplitOffs(
            listOf(
                SplitOffRecord(fireflyId = "1", externalId = "a", amount = "500.00"),
                SplitOffRecord(fireflyId = "2", externalId = "b", amount = "250.5")
            )
        )
        val first = com.firesms.app.data.local.StoredTransactionSplit(amount = "1000", currencySymbol = "Rs")
        assertEquals("Split off Rs750.50", splitOffSummary(json, first))
        assertNull(splitOffSummary(null, first))
    }
}

/** What the editor shows after a save stopped at each step of: create → reduce original → delete. */
class SplitOffRecoveryTest {
    private fun piece(amount: String) =
        SplitOffState(key = 1, fireflyId = "12", externalId = "x", parts = listOf(SplitOffPart(key = 2, amount = amount)))

    @Test
    fun `a finished split shows the whole payment and is not interrupted`() {
        val result = recoverPayment("1000.00", listOf("1000.00"), listOf(piece("1000.00")), emptyList(), emptyList(), emptyList())
        assertEquals("2000.00", result.paymentTotal)
        assertEquals(listOf("1000.00"), result.rowAmounts)
        assertFalse(result.interrupted)
    }

    @Test
    fun `stopped after creating, before reducing the original - the share is reduced again, not double counted`() {
        val result = recoverPayment("2000.00", listOf("2000.00"), emptyList(), listOf(piece("1000.00")), emptyList(), emptyList())
        assertEquals("2000.00", result.paymentTotal)
        assertEquals(listOf("1000.00"), result.rowAmounts)
        assertEquals(1, result.splitOffs.size)
        assertTrue(result.interrupted)
    }

    @Test
    fun `stopped before the original got a removed split-off's amount back - it is returned and the delete finished`() {
        val result = recoverPayment("1000.00", listOf("1000.00"), emptyList(), emptyList(), listOf(piece("1000.00")), emptyList())
        assertEquals("2000.00", result.paymentTotal)
        assertEquals(listOf("2000.00"), result.rowAmounts)
        assertEquals(emptyList(), result.splitOffs)
        assertEquals(1, result.deletedSplitOffs.size)
        assertTrue(result.interrupted)
    }

    @Test
    fun `stopped after the original got the amount back, before deleting - only the delete is left`() {
        val result = recoverPayment("2000.00", listOf("2000.00"), emptyList(), emptyList(), emptyList(), listOf(piece("1000.00")))
        assertEquals("2000.00", result.paymentTotal)
        assertEquals(listOf("2000.00"), result.rowAmounts)
        assertEquals(1, result.deletedSplitOffs.size)
        assertTrue(result.interrupted)
    }

    @Test
    fun `records saved before statuses existed read as finished`() {
        val decoded = decodeSplitOffs("""[{"fireflyId":"12","externalId":"x","amount":"1000.00"}]""").single()
        assertEquals(com.firesms.app.data.local.SplitOffStatus.ACTIVE, decoded.status)
    }
}

/** A split-off that is itself a Firefly split transaction. */
class SplitOffPartsTest {
    private val withdrawal = SharedPaymentFields(
        type = "withdrawal", date = "2026-11-25T19:42:00+05:45",
        sourceId = "1", sourceName = "Nabil", destinationId = "7", destinationName = "Cash"
    )

    private fun piece(vararg amounts: String, groupTitle: String = "") = SplitOffState(
        key = 1,
        externalId = "hash-split-abcd1234",
        accountName = "Friend",
        groupTitle = groupTitle,
        parts = amounts.mapIndexed { i, a -> SplitOffPart(key = 10L + i, amount = a, description = "Part ${i + 1}") }
    )

    @Test
    fun `a split-off's amount is its splits added up`() {
        assertEquals("1000.00", piece("600", "400").amount)
        assertEquals("600.00", piece("600", "").amount)
        assertEquals("", piece("").amount)
    }

    @Test
    fun `adding a split halves the last one, keeping the split-off total, and titles the group`() {
        val result = addSplitOffPart(piece("1000.00"), newKey = 99)
        assertEquals(listOf("500.00", "500.00"), result.parts.map { it.amount })
        assertEquals("1000.00", result.amount)
        assertEquals(99L, result.parts.last().key)
        assertEquals("Part 1", result.groupTitle)
    }

    @Test
    fun `removing a split moves its amount to the split above`() {
        val result = removeSplitOffPart(piece("500", "300", "200"), partKey = 12)
        assertEquals(listOf("500", "500.00"), result.parts.map { it.amount })
        val first = removeSplitOffPart(piece("500", "300"), partKey = 10)
        assertEquals(listOf("800.00"), first.parts.map { it.amount })
        val only = piece("1000")
        assertEquals(only, removeSplitOffPart(only, partKey = 10))
    }

    @Test
    fun `a split-off with several splits needs a group title and valid split amounts`() {
        val result = validateSplitOffs("withdrawal", listOf(piece("600", "")))
        assertEquals("Required for split transactions", result.groupTitleErrors[0])
        assertEquals(mapOf(1 to "Required"), result.amountErrors[0])
        assertTrue(validateSplitOffs("withdrawal", listOf(piece("600", "400", groupTitle = "Meetup"))).isValid)
    }

    @Test
    fun `every split of every split-off counts towards the payment`() {
        val pieces = listOf(piece("600", "400"), piece("250"))
        assertEquals(listOf("600", "400", "250"), splitOffPartAmounts(pieces))
        assertTrue(validateEdit(listOf("750"), "", "2000", splitOffPartAmounts(pieces)).isValid)
        assertFalse(validateEdit(listOf("700"), "", "2000", splitOffPartAmounts(pieces)).isValid)
    }

    @Test
    fun `a split split-off is created as one Firefly split transaction`() {
        val request = buildSplitOffCreateRequest(withdrawal, piece("600", "400", groupTitle = "Meetup"))
        assertEquals("Meetup", request.groupTitle)
        assertEquals(listOf("600.00", "400.00"), request.transactions.map { it.amount })
        assertTrue(request.transactions.all { it.destinationName == "Friend" && it.sourceName == "Nabil" })
        assertTrue(request.transactions.all { it.externalId == "hash-split-abcd1234" })
        assertNull(buildSplitOffCreateRequest(withdrawal, piece("1000", groupTitle = "Meetup")).groupTitle)
    }

    @Test
    fun `an update keeps stored splits' journals and adds new ones without`() {
        val base = piece("600", "400", groupTitle = "Meetup")
        val stored = base.copy(parts = listOf(base.parts[0].copy(transactionJournalId = "55"), base.parts[1]))
        val request = buildSplitOffUpdateRequest(withdrawal, stored)
        assertEquals("Meetup", request.groupTitle)
        assertEquals(listOf("55", null), request.transactions.map { it.transactionJournalId })
        assertEquals(listOf(0, 1), request.transactions.map { it.order })
    }

    @Test
    fun `a stored split split-off loads every split and its group title`() {
        var key = 10L
        val state = splitOffFromFirefly(
            1, SplitOffRecord(fireflyId = "12", externalId = "x"),
            listOf(
                FireflyTransactionSplit(transactionJournalId = "31", type = "withdrawal", amount = "600", destinationName = "Friend", description = "Food"),
                FireflyTransactionSplit(transactionJournalId = "32", type = "withdrawal", amount = "400", destinationName = "Friend", description = "Drinks")
            ),
            groupTitle = "Meetup"
        ) { key++ }
        assertEquals(listOf("31", "32"), state.parts.map { it.transactionJournalId })
        assertEquals("1000.00", state.amount)
        assertEquals("Meetup", state.title)
        assertEquals("31", state.transactionJournalId)
    }

    @Test
    fun `a removed split split-off gives its whole amount back`() {
        val result = recoverPayment(
            "1000.00", listOf("1000.00"), emptyList(), emptyList(),
            listOf(piece("600.00", "400.00").copy(fireflyId = "12")), emptyList()
        )
        assertEquals(listOf("2000.00"), result.rowAmounts)
    }
}
