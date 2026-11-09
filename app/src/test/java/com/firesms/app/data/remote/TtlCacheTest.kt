package com.firesms.app.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TtlCacheTest {

    private var clock = 0L
    private val cache = TtlCache<String, Int>(ttlMillis = 1_000, now = { clock })

    @Test
    fun `fresh entries are served without reloading`() = runBlocking {
        var loads = 0
        assertEquals(1, cache.getOrLoad("a") { ++loads })
        clock = 999
        assertEquals(1, cache.getOrLoad("a") { ++loads })
        assertEquals(1, loads)
    }

    @Test
    fun `expired entries are reloaded`() = runBlocking {
        var loads = 0
        cache.getOrLoad("a") { ++loads }
        clock = 1_000
        assertEquals(2, cache.getOrLoad("a") { ++loads })
    }

    @Test
    fun `keys are independent and can be invalidated`() = runBlocking {
        cache.getOrLoad("a") { 1 }
        cache.getOrLoad("b") { 2 }
        cache.removeIf { it == "a" }
        assertEquals(10, cache.getOrLoad("a") { 10 })
        assertEquals(2, cache.getOrLoad("b") { 20 })
    }

    @Test
    fun `failures are not cached`() = runBlocking {
        assertFailsWith<IllegalStateException> { cache.getOrLoad("a") { error("offline") } }
        assertEquals(5, cache.getOrLoad("a") { 5 })
    }

    @Test
    fun `concurrent callers for one key share a single load`() = runBlocking {
        var loads = 0
        val gate = CompletableDeferred<Unit>()
        val results = List(5) {
            async {
                cache.getOrLoad("a") {
                    gate.await()
                    ++loads
                }
            }
        }
        gate.complete(Unit)
        assertEquals(List(5) { 1 }, results.awaitAll())
        assertEquals(1, loads)
    }
}
