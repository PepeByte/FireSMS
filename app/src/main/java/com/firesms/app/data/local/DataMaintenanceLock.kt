package com.firesms.app.data.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes destructive restore/cleanup work with SMS and retry processing inside this app process.
 */
object DataMaintenanceLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
