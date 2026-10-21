package com.firesms.app.data.backup

import com.firesms.app.data.local.AppPreferencesSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupRestoreTest {
    private val originalPreferences = AppPreferencesSnapshot(
        fireflyUrl = "https://original.example.test",
        fireflyToken = "original-test-token",
        onboardingCompleted = true,
        syncNetworkPolicy = "any_network",
        historyRetention = "never"
    )
    private val importedPreferences = originalPreferences.copy(
        fireflyUrl = "https://imported.example.test",
        fireflyToken = "imported-test-token",
        syncNetworkPolicy = "wifi_only"
    )

    /** Models Room rollback and atomic DataStore edits without Android storage dependencies. */
    private inner class Store {
        var preferences = originalPreferences
        var records = "original records"
        var failRecords = false
        var failPreferences = false
        var failCommit = false
        var failCompensation = false
        var replacements = 0
        var beforePreferenceWrite: suspend () -> Unit = {}

        suspend fun restore() = restoreBackupState(
            preferences = importedPreferences,
            readPreferences = { preferences },
            writePreferences = { value ->
                beforePreferenceWrite()
                if (failPreferences && value == importedPreferences) throw IOException("Preference write failed")
                if (failCompensation && value == originalPreferences) throw IOException("Compensation failed")
                preferences = value
            },
            transaction = { block ->
                val previousRecords = records
                try {
                    block()
                    if (failCommit) throw IOException("Commit failed")
                } catch (error: Exception) {
                    records = previousRecords
                    throw error
                }
            },
            replaceRecords = {
                records = "imported records"
                replacements++
                if (failRecords) throw IOException("Record insertion failed")
            }
        )
    }

    @Test
    fun successfulRestoreReplacesRecordsAndPreferences() = runBlocking {
        val store = Store()
        store.restore()
        assertEquals("imported records", store.records)
        assertEquals(importedPreferences, store.preferences)
    }

    @Test
    fun recordFailureKeepsOriginalState() = runBlocking {
        val store = Store().apply { failRecords = true }
        assertFailsWith<IOException> { store.restore() }
        assertEquals("original records", store.records)
        assertEquals(originalPreferences, store.preferences)
    }

    @Test
    fun preferenceFailureRollsBackRecordReplacement() = runBlocking {
        val store = Store().apply { failPreferences = true }
        assertFailsWith<IOException> { store.restore() }
        assertEquals("original records", store.records)
        assertEquals(originalPreferences, store.preferences)
    }

    @Test
    fun commitFailureCompensatesPreferences() = runBlocking {
        val store = Store().apply { failCommit = true }
        assertFailsWith<IOException> { store.restore() }
        assertEquals("original records", store.records)
        assertEquals(originalPreferences, store.preferences)
    }

    @Test
    fun compensationFailureDoesNotHideOriginalError() = runBlocking {
        val store = Store().apply { failCommit = true; failCompensation = true }
        val error = assertFailsWith<IOException> { store.restore() }
        assertEquals("Commit failed", error.message)
        // Coroutine debug stack recovery can wrap/copy an IOException at withContext.
        val suppressed = generateSequence<Throwable>(error) { it.cause }
            .flatMap { it.suppressed.asSequence() }.toList()
        assertEquals("Compensation failed", suppressed.single().message)
        assertEquals("original records", store.records)
    }

    @Test
    fun navigationCancellationCannotInterruptReplacement() = runBlocking {
        val reachedPreferences = CompletableDeferred<Unit>()
        val continueWrite = CompletableDeferred<Unit>()
        val store = Store().apply {
            beforePreferenceWrite = {
                reachedPreferences.complete(Unit)
                continueWrite.await()
            }
        }
        val job = launch { store.restore() }
        reachedPreferences.await()
        job.cancel()
        continueWrite.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("imported records", store.records)
        assertEquals(importedPreferences, store.preferences)
    }

    @Test
    fun cancellationBeforeReplacementDoesNotMutateState() = runBlocking {
        val store = Store()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> { store.restore() }
        }
        job.join()
        assertEquals(0, store.replacements)
        assertEquals("original records", store.records)
        assertEquals(originalPreferences, store.preferences)
    }

    @Test
    fun preferenceReadFailureDoesNotStartTransaction() = runBlocking {
        val failure = IOException("Cannot read preferences")
        var transactionStarted = false
        val error = assertFailsWith<IOException> {
            restoreBackupState(
                preferences = importedPreferences,
                readPreferences = { throw failure },
                writePreferences = { error("Must not write preferences") },
                transaction = { transactionStarted = true; it() },
                replaceRecords = { error("Must not replace records") }
            )
        }
        assertEquals(failure.message, error.message)
        assertEquals(false, transactionStarted)
    }
}
