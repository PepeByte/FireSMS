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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TransactionRequest(
    @SerialName("transactions") val transactions: List<TransactionBody>
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TransactionBody(
    val type: String,
    val date: String,
    val amount: String,
    val description: String,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("source_name") val sourceName: String? = null,
    @SerialName("destination_id") val destinationId: String? = null,
    @SerialName("destination_name") val destinationName: String? = null,
    @SerialName("external_id") val externalId: String,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("budget_name") val budgetName: String? = null,
    // Always sent: with encodeDefaults = false a note equal to the default would be dropped.
    @EncodeDefault val notes: String = "Imported from FireSMS"
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
    val data: List<AccountResource>,
    val meta: ListMeta? = null
)

@Serializable
data class ListMeta(
    val pagination: Pagination? = null
)

@Serializable
data class Pagination(
    @SerialName("total_pages") val totalPages: Int = 1
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
        destinationId = transaction.destinationAccountId,
        destinationName = transaction.destinationAccountName,
        externalId = externalId,
        categoryName = transaction.categoryName,
        budgetName = transaction.budgetName,
        notes = notes
    )))
}

class FireflyApi(private val token: String) {

    /**
     * Tokens are pasted out of Firefly's UI and password managers, which routinely add a trailing
     * newline or spaces. OkHttp rejects such a value before sending it, which surfaced as a
     * cryptic "IllegalHeaderValueException" instead of an authentication problem.
     */
    private val authToken = token.trim()

    /**
     * One client for the whole process so that connections and TLS sessions are reused. Creating a
     * client per call forced a new TCP + TLS handshake for every request, which made screens that
     * issue several calls in a row noticeably slow on a phone network.
     */
    private val client = sharedClient

    private fun HttpRequestBuilder.authorize() {
        header(HttpHeaders.Authorization, "Bearer $authToken")
        accept(ContentType.Application.Json)
    }

    suspend fun createTransaction(
        baseUrl: String,
        transaction: ParsedTransaction,
        externalId: String
    ): String {
        return createTransactionRaw(baseUrl, buildCreateRequest(transaction, externalId))
    }

    /**
     * Returns whether the server accepted the token; network errors propagate to the caller.
     *
     * Firefly answers an unauthenticated request with a 302 to /login, and the HTTP client follows
     * redirects, so checking only the final status reported "connected" for a wrong token. The
     * redirect target is inspected instead: a real API response never lands on an HTML login page.
     * Firefly may be installed under a subpath (e.g. https://host/firefly), so the suffix is what
     * matters, not a leading slash.
     */
    suspend fun testConnection(baseUrl: String): Boolean {
        val url = apiUrl(baseUrl, "about")
        val response = client.get(url) { authorize() }
        return response.status.isSuccess() &&
            response.request.url.encodedPath.contains("/api/") &&
            response.contentType()?.match(ContentType.Application.Json) == true
    }

    suspend fun createTransactionRaw(
        baseUrl: String,
        payload: TransactionRequest
    ): String {
        val url = apiUrl(baseUrl, "transactions")
        try {
            val response = client.post(url) {
                authorize()
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (response.status.value in 400..499) {
                throw FireflyError(response.status.value, response.bodyAsText().abbreviate())
            }
            if (!response.status.isSuccess()) {
                throw FireflyUnavailable(response.status.value, response.bodyAsText().abbreviate())
            }
            val txResponse = response.body<TransactionResponse>()
            return txResponse.data.id
        } catch (e: FireflyError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network/timeout/etc — treat as unavailable for retry
            throw FireflyUnavailable(0, networkErrorMessage(e))
        }
    }

    suspend fun getTransaction(baseUrl: String, transactionId: String): FireflyTransactionResponse {
        val url = apiUrl(baseUrl, "transactions/$transactionId")
        val response = client.get(url) { authorize() }
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText().abbreviate())
        }
        return response.body()
    }

    suspend fun getCategories(baseUrl: String): List<CategoryResource> {
        val url = apiUrl(baseUrl, "categories")
        val response = client.get(url) { authorize() }
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText().abbreviate())
        }
        val result = response.body<CategoryListResponse>()
        return result.data
    }

    suspend fun getBudgets(baseUrl: String): List<BudgetResource> {
        val url = apiUrl(baseUrl, "budgets")
        val response = client.get(url) { authorize() }
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText().abbreviate())
        }
        val result = response.body<BudgetListResponse>()
        return result.data
    }

    suspend fun getAutocompleteTransactions(baseUrl: String): List<AutocompleteTransactionItem> {
        val url = apiUrl(baseUrl, "autocomplete/transactions")
        val response = client.get(url) { authorize() }
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText().abbreviate())
        }
        // Firefly autocomplete endpoints return bare JSON arrays (no "data" wrapper).
        return fireflyJson.decodeFromString(response.bodyAsText())
    }

    suspend fun updateTransaction(
        baseUrl: String,
        transactionId: String,
        request: TransactionUpdateRequest
    ): FireflyTransactionResponse {
        val url = apiUrl(baseUrl, "transactions/$transactionId")
        val response = client.put(url) {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value in 400..499) {
            throw FireflyError(response.status.value, response.bodyAsText().abbreviate())
        }
        if (!response.status.isSuccess()) {
            throw FireflyUnavailable(response.status.value, response.bodyAsText().abbreviate())
        }
        return response.body()
    }

    /** Fetches every page of accounts of [type] (e.g. `asset`, `expense`, `all`). */
    suspend fun getAccounts(baseUrl: String, type: String = "asset"): List<AccountResource> {
        val accounts = mutableListOf<AccountResource>()
        var page = 1
        var totalPages: Int
        do {
            val url = apiUrl(baseUrl, "accounts?type=$type&page=$page")
            val response = client.get(url) { authorize() }
            if (!response.status.isSuccess()) {
                throw FireflyUnavailable(response.status.value, response.bodyAsText().abbreviate())
            }
            val result = response.body<AccountListResponse>()
            accounts += result.data
            totalPages = result.meta?.pagination?.totalPages ?: 1
            page++
        } while (page <= totalPages && page <= MAX_ACCOUNT_PAGES && result.data.isNotEmpty())
        return accounts
    }

    /**
     * The HTTP client is shared for the lifetime of the process, so callers that used to release
     * a per-call client have nothing to do. Kept so existing call sites stay valid.
     */
    fun close() = Unit

    private companion object {
        /** Safety cap on pagination: 100 pages of Firefly's default 50 accounts. */
        const val MAX_ACCOUNT_PAGES = 100

        val fireflyJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        val sharedClient: HttpClient by lazy {
            HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                expectSuccess = false
                install(ContentNegotiation) { json(fireflyJson) }
                install(HttpTimeout) {
                    // Ktor's OkHttp engine only overrides OkHttp's read timeout when
                    // socketTimeoutMillis is set. Leaving it out silently kept OkHttp's 10 s
                    // default, so a slow (but healthy) self-hosted Firefly failed with
                    // "Socket timeout has expired" long before the 30 s request timeout.
                    requestTimeoutMillis = 45_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 45_000
                }
            }
        }
    }
}

/**
 * Builds an API URL from the address the user typed. Pasting the address out of a browser
 * commonly brings a trailing slash, a `/api` suffix (some users copy the docs' base URL), or a
 * trailing newline from a password manager; all of those used to fail deep inside the HTTP stack
 * with errors that never mentioned the URL.
 */
internal fun apiUrl(baseUrl: String, path: String): String {
    var base = baseUrl.trim().trimEnd('/')
    if (!base.startsWith("http://", ignoreCase = true) &&
        !base.startsWith("https://", ignoreCase = true)
    ) {
        base = "http://$base"
    }
    if (base.endsWith("/api/v1", ignoreCase = true)) base = base.dropLast(7)
    else if (base.endsWith("/api", ignoreCase = true)) base = base.dropLast(4)
    return "$base/api/v1/${path.trimStart('/')}"
}

/** Keeps Firefly's HTML error pages from filling the activity log. */
private fun String.abbreviate(limit: Int = 300): String =
    if (length <= limit) this else take(limit).trimEnd() + "…"

/**
 * Converts a transport failure into an explanation the user can act on. Ktor and OkHttp
 * exceptions leak implementation detail ("Socket timeout has expired [url=…]") that reads like
 * an app crash rather than a configuration problem.
 */
fun networkErrorMessage(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.toList()
    fun causedBy(vararg simpleNames: String) =
        chain.any { cause -> simpleNames.any { cause.javaClass.simpleName == it } }
    val detail = chain.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }.orEmpty()

    return when {
        causedBy("UnknownHostException") ->
            "Can't find that server. Check the address and your internet connection."
        causedBy("ConnectException", "NoRouteToHostException") ->
            "Can't reach that server. Check the address, the port, and that Firefly III is running."
        causedBy("SocketTimeoutException", "HttpRequestTimeoutException", "TimeoutCancellationException") ->
            "Firefly took too long to answer. Check that the server is responsive, then try again."
        causedBy("SSLHandshakeException", "SSLPeerUnverifiedException", "CertPathValidatorException") ->
            "Firefly's TLS certificate isn't trusted on this device. Install its certificate " +
                "authority, or use a certificate Android already trusts."
        causedBy("IllegalHeaderValueException") ->
            "The server URL or access token contains spaces or line breaks. Re-enter both without " +
                "extra whitespace."
        causedBy("IllegalArgumentException") && detail.contains("URL", ignoreCase = true) ->
            "That server address isn't a valid URL. Include http:// or https://."
        else -> detail.ifBlank { error.javaClass.simpleName }
    }
}

class FireflyError(val statusCode: Int, responseBody: String) : Exception(
    "Firefly returned HTTP $statusCode: $responseBody"
)

class FireflyUnavailable(val statusCode: Int, responseBody: String) : Exception(
    "Firefly unavailable (HTTP $statusCode): $responseBody"
)
