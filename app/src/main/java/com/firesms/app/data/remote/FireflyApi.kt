package com.firesms.app.data.remote

import com.firesms.app.domain.model.ParsedTransaction
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TransactionRequest(
    @SerialName("transactions") val transactions: List<TransactionBody>
)

@Serializable
data class TransactionBody(
    val type: String,
    val date: String,
    val amount: String,
    val description: String,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("source_name") val sourceName: String? = null,
    @SerialName("destination_name") val destinationName: String? = null,
    @SerialName("external_id") val externalId: String,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("budget_name") val budgetName: String? = null,
    val notes: String = "Imported from FireSMS"
)

@Serializable
data class TransactionResponse(
    val data: TransactionData
)

@Serializable
data class TransactionData(
    val id: String
)

// Firefly account list response
@Serializable
data class AccountListResponse(
    val data: List<AccountResource>
)

@Serializable
data class AccountResource(
    val id: String,
    val attributes: AccountAttributes
)

@Serializable
data class AccountAttributes(
    val name: String,
    val type: String
)

// Firefly transaction detail response
@Serializable
data class FireflyTransactionResponse(
    val data: FireflyTransactionData
)

@Serializable
data class FireflyTransactionData(
    val id: String,
    val attributes: FireflyTransactionAttributes
)

@Serializable
data class FireflyTransactionAttributes(
    @SerialName("group_title") val groupTitle: String? = null,
    val transactions: List<FireflyTransactionSplit>
)

@Serializable
data class FireflyTransactionSplit(
    @SerialName("transaction_journal_id") val transactionJournalId: String? = null,
    val type: String = "",
    val date: String = "",
    val amount: String = "",
    @SerialName("currency_code") val currencyCode: String? = null,
    @SerialName("currency_symbol") val currencySymbol: String? = null,
    val description: String = "",
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("source_name") val sourceName: String? = null,
    @SerialName("destination_id") val destinationId: String? = null,
    @SerialName("destination_name") val destinationName: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("budget_name") val budgetName: String? = null,
    val notes: String? = null,
    val order: Int = 0
)

// Update request — same shape as TransactionRequest but with split fields
@Serializable
data class TransactionUpdateRequest(
    @SerialName("group_title") val groupTitle: String? = null,
    @SerialName("transactions") val transactions: List<TransactionUpdateSplit>
)

@Serializable
data class TransactionUpdateSplit(
    @SerialName("transaction_journal_id") val transactionJournalId: String? = null,
    val type: String,
    val date: String,
    val amount: String,
    val description: String,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("source_name") val sourceName: String? = null,
    @SerialName("destination_id") val destinationId: String? = null,
    @SerialName("destination_name") val destinationName: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("budget_name") val budgetName: String? = null,
    val notes: String? = null,
    val order: Int = 0
)

// Category and budget list responses
@Serializable
data class CategoryListResponse(
    val data: List<CategoryResource>
)

@Serializable
data class CategoryResource(
    val id: String,
    val attributes: CategoryAttributes
)

@Serializable
data class CategoryAttributes(
    val name: String
)

@Serializable
data class BudgetListResponse(
    val data: List<BudgetResource>
)

@Serializable
data class BudgetResource(
    val id: String,
    val attributes: BudgetAttributes
)

@Serializable
data class BudgetAttributes(
    val name: String
)

@Serializable
data class AutocompleteTransactionItem(
    val id: String,
    val name: String
)

/**
 * Build a TransactionRequest from a ParsedTransaction and externalId.
 * Serializes the date with the device-local offset instead of bare UTC.
 */
fun buildCreateRequest(
    transaction: ParsedTransaction,
    externalId: String
): TransactionRequest {
    val notes = buildString {
        append("Imported from FireSMS")
        if (!transaction.notes.isNullOrBlank()) {
            append("\n")
            append(transaction.notes)
        }
    }
    val dateStr = java.time.LocalDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(transaction.date),
        java.time.ZoneId.systemDefault()
    ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    return TransactionRequest(listOf(TransactionBody(
        type = transaction.transactionType,
        date = dateStr,
        amount = transaction.amount,
        description = transaction.description,
        sourceId = transaction.sourceAccountId,
        sourceName = transaction.sourceAccountName,
        destinationName = transaction.destinationAccountName,
        externalId = externalId,
        categoryName = transaction.categoryName,
        budgetName = transaction.budgetName,
        notes = notes
    )))
}

class FireflyApi(private val token: String) {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            header("Authorization", "Bearer $token")
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun createTransaction(
        baseUrl: String,
        transaction: ParsedTransaction,
        externalId: String
    ): String {
        val url = "${baseUrl.trimEnd('/')}/api/v1/transactions"
        val body = buildCreateRequest(transaction, externalId)
        return createTransactionRaw(baseUrl, body)
    }

    suspend fun testConnection(baseUrl: String): Boolean {
        return try {
            val url = "${baseUrl.trimEnd('/')}/api/v1/about"
            val response = client.get(url)
            response.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun createTransactionRaw(
        baseUrl: String,
        payload: TransactionRequest
    ): String {
        val url = "${baseUrl.trimEnd('/')}/api/v1/transactions"
        try {
            val response = client.post(url) { setBody(payload) }
            if (response.status.value in 400..499) {
                throw FireflyError(response.status.value, response.bodyAsText())
            }
            if (!response.status.isSuccess()) {
                throw FireflyUnavailable(response.status.value, response.bodyAsText())
            }
            val txResponse = response.body<TransactionResponse>()
            return txResponse.data.id
        } catch (e: FireflyError) {
            throw e
        } catch (e: Exception) {
            // Network/timeout/etc — treat as unavailable for retry
            throw FireflyUnavailable(0, e.message ?: e.toString())
        }
    }

    suspend fun getTransaction(baseUrl: String, transactionId: String): FireflyTransactionResponse {
        val url = "${baseUrl.trimEnd('/')}/api/v1/transactions/$transactionId"
        val response = client.get(url)
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    suspend fun getCategories(baseUrl: String): List<CategoryResource> {
        val url = "${baseUrl.trimEnd('/')}/api/v1/categories"
        val response = client.get(url)
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText())
        }
        val result = response.body<CategoryListResponse>()
        return result.data
    }

    suspend fun getBudgets(baseUrl: String): List<BudgetResource> {
        val url = "${baseUrl.trimEnd('/')}/api/v1/budgets"
        val response = client.get(url)
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText())
        }
        val result = response.body<BudgetListResponse>()
        return result.data
    }

    suspend fun getAutocompleteTransactions(baseUrl: String): List<AutocompleteTransactionItem> {
        val url = "${baseUrl.trimEnd('/')}/api/v1/autocomplete/transactions"
        val response = client.get(url)
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText())
        }
        // Firefly autocomplete endpoints return bare JSON arrays (no "data" wrapper).
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun updateTransaction(
        baseUrl: String,
        transactionId: String,
        request: TransactionUpdateRequest
    ): FireflyTransactionResponse {
        val url = "${baseUrl.trimEnd('/')}/api/v1/transactions/$transactionId"
        val response = client.put(url) { setBody(request) }
        if (response.status.value in 400..499) {
            throw FireflyError(response.status.value, response.bodyAsText())
        }
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    suspend fun getAccounts(baseUrl: String, type: String = "asset"): List<AccountResource> {
        val url = "${baseUrl.trimEnd('/')}/api/v1/accounts?type=$type"
        val response = client.get(url)
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText())
        }
        val result = response.body<AccountListResponse>()
        return result.data
    }

    fun close() {
        client.close()
    }
}

class FireflyError(val statusCode: Int, responseBody: String) : Exception(
    "Firefly returned HTTP $statusCode: $responseBody"
)

class FireflyUnavailable(val statusCode: Int, responseBody: String) : Exception(
    "Firefly unavailable (HTTP $statusCode): $responseBody"
)
