package com.firesms.app.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-wide, in-memory cache of the Firefly lists that forms use for suggestions: accounts,
 * categories, budgets and transaction-description autocomplete.
 *
 * These lists change rarely but used to be fetched again every time a form opened, which made
 * the edit screen wait on four extra round trips. Entries are keyed by the Firefly base URL (and
 * the account type for accounts), expire after [TTL_MILLIS], and are kept only for the life of the
 * process. Failed loads are not cached, so the next call simply tries again.
 *
 * Usage, from any coroutine:
 * ```
 * val categories = FireflyLookupCache.categories(api, baseUrl)
 * ```
 * Concurrent calls for the same list share one request; calls for different lists run in
 * parallel. Call [invalidate] after something that changes these lists (creating an account,
 * switching server or token) so the next read fetches fresh data.
 */
object FireflyLookupCache {
    /** How long a loaded list is served before it is fetched again. */
    const val TTL_MILLIS: Long = 5 * 60 * 1000L

    private val accountCache = TtlCache<Pair<String, String>, List<AccountResource>>(TTL_MILLIS)
    private val categoryCache = TtlCache<String, List<CategoryResource>>(TTL_MILLIS)
    private val budgetCache = TtlCache<String, List<BudgetResource>>(TTL_MILLIS)
    private val descriptionCache = TtlCache<String, List<AutocompleteTransactionItem>>(TTL_MILLIS)

    /** Accounts of [type] (as accepted by [FireflyApi.getAccounts], e.g. "asset" or "all"). */
    suspend fun accounts(api: FireflyApi, baseUrl: String, type: String = "all"): List<AccountResource> =
        accountCache.getOrLoad(cacheKey(baseUrl) to type) { api.getAccounts(baseUrl, type) }

    suspend fun categories(api: FireflyApi, baseUrl: String): List<CategoryResource> =
        categoryCache.getOrLoad(cacheKey(baseUrl)) { api.getCategories(baseUrl) }

    suspend fun budgets(api: FireflyApi, baseUrl: String): List<BudgetResource> =
        budgetCache.getOrLoad(cacheKey(baseUrl)) { api.getBudgets(baseUrl) }

    suspend fun descriptions(api: FireflyApi, baseUrl: String): List<AutocompleteTransactionItem> =
        descriptionCache.getOrLoad(cacheKey(baseUrl)) { api.getAutocompleteTransactions(baseUrl) }

    /** Drops every cached list for [baseUrl], or for all servers when it is null. */
    suspend fun invalidate(baseUrl: String? = null) {
        val key = baseUrl?.let(::cacheKey)
        accountCache.removeIf { key == null || it.first == key }
        categoryCache.removeIf { key == null || it == key }
        budgetCache.removeIf { key == null || it == key }
        descriptionCache.removeIf { key == null || it == key }
    }

    /** "https://ff.example/" and "https://ff.example" are the same server. */
    private fun cacheKey(baseUrl: String): String = apiUrl(baseUrl, "")
}

/**
 * A small suspend-friendly cache whose entries expire [ttlMillis] after they were loaded.
 * A per-key [Mutex] makes concurrent callers for the same key wait for one load instead of
 * issuing duplicates, while the map itself is guarded by a separate [Mutex].
 */
internal class TtlCache<K : Any, V>(
    private val ttlMillis: Long,
    private val now: () -> Long = System::currentTimeMillis
) {
    private class Entry<V>(val value: V, val loadedAt: Long)

    private val mapLock = Mutex()
    private val entries = HashMap<K, Entry<V>>()
    private val keyLocks = HashMap<K, Mutex>()

    suspend fun getOrLoad(key: K, load: suspend () -> V): V {
        val keyLock = mapLock.withLock {
            freshEntry(key)?.let { return it.value }
            keyLocks.getOrPut(key) { Mutex() }
        }
        return keyLock.withLock {
            // Another caller may have finished loading while this one waited.
            mapLock.withLock { freshEntry(key) }?.let { return it.value }
            val value = load()
            mapLock.withLock { entries[key] = Entry(value, now()) }
            value
        }
    }

    suspend fun removeIf(predicate: (K) -> Boolean) {
        mapLock.withLock { entries.keys.removeAll(predicate) }
    }

    private fun freshEntry(key: K): Entry<V>? =
        entries[key]?.takeIf { now() - it.loadedAt < ttlMillis }
}
