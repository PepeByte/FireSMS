package com.firesms.app.data.remote

import com.firesms.app.domain.model.ParsedTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CreateRequestEncodingTest {

    // Mirrors the Ktor client configuration: encodeDefaults is false.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `default note and destination id are sent to Firefly`() {
        val request = buildCreateRequest(
            ParsedTransaction(
                amount = "10",
                description = "Salary",
                destinationAccountName = "Checking",
                destinationAccountId = "42",
                transactionType = "deposit",
                date = 0L
            ),
            externalId = "hash"
        )

        val split = json.parseToJsonElement(json.encodeToString(TransactionRequest.serializer(), request))
            .jsonObject["transactions"]!!.jsonArray[0].jsonObject

        assertEquals("Imported from FireSMS", split["notes"]?.jsonPrimitive?.content)
        assertEquals("42", split["destination_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `queued payloads written before destination_id still decode`() {
        val legacy = """{"transactions":[{"type":"withdrawal","date":"2026-01-01 10:00:00","amount":"5",""" +
            """"description":"Shop","external_id":"hash"}]}"""
        val decoded = json.decodeFromString(TransactionRequest.serializer(), legacy)
        assertEquals("Imported from FireSMS", decoded.transactions[0].notes)
        assertEquals(null, decoded.transactions[0].destinationId)
    }
}
