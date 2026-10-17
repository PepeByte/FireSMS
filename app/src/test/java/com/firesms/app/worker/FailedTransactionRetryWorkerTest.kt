package com.firesms.app.worker

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.firesms.app.data.local.FailedTransactionRetryCandidate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailedTransactionRetryWorkerTest {
    private val batch = "${FailedTransactionRetryWorker.BATCH_TAG_PREFIX}current"

    private fun work(state: WorkInfo.State, batchTag: String = batch, extraTags: Set<String> = emptySet()) =
        WorkInfo(UUID.randomUUID(), state, extraTags + batchTag)

    @Test
    fun `no unfinished work means retry button is available`() {
        assertNull(failedRetryProgress(emptyList()))
        assertNull(failedRetryProgress(listOf(work(WorkInfo.State.SUCCEEDED))))
        assertNull(failedRetryProgress(listOf(work(WorkInfo.State.CANCELLED))))
    }

    @Test
    fun `progress includes completed and blocked items in the active batch`() {
        val result = failedRetryProgress(listOf(
            work(WorkInfo.State.SUCCEEDED),
            work(WorkInfo.State.RUNNING),
            work(WorkInfo.State.BLOCKED)
        ))!!
        assertEquals(1, result.completed)
        assertEquals(3, result.total)
        assertTrue(result.isRunning)
    }

    @Test
    fun `queued work is not presented as a running retry`() {
        val result = failedRetryProgress(listOf(
            work(WorkInfo.State.ENQUEUED),
            work(WorkInfo.State.BLOCKED)
        ))!!
        assertFalse(result.isRunning)
        assertEquals(0, result.completed)
        assertEquals(2, result.total)
    }

    @Test
    fun `old batches do not inflate progress after scheduling again`() {
        val oldBatch = "${FailedTransactionRetryWorker.BATCH_TAG_PREFIX}old"
        val result = failedRetryProgress(listOf(
            work(WorkInfo.State.SUCCEEDED, oldBatch),
            work(WorkInfo.State.SUCCEEDED, oldBatch),
            work(WorkInfo.State.SUCCEEDED),
            work(WorkInfo.State.ENQUEUED)
        ))!!
        assertEquals(1, result.completed)
        assertEquals(2, result.total)
    }

    @Test
    fun `candidate identity survives work metadata round trip for policy updates`() {
        val info = work(WorkInfo.State.BLOCKED, extraTags = setOf("failed-retry-log:7:original:hash"))
        val candidate = FailedTransactionRetryWorker.candidate(info)!!
        assertEquals(7, candidate.id)
        assertEquals("original:hash", candidate.smsHash)
    }

    @Test
    fun `requests persist original SMS identity and batch membership`() {
        val candidate = FailedTransactionRetryCandidate(7, "original-hash")
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val request = FailedTransactionRetryWorker.request(candidate, constraints, batch)
        assertEquals(7, request.workSpec.input.getLong("log_id", -1))
        assertEquals("original-hash", request.workSpec.input.getString("sms_hash"))
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(candidate, FailedTransactionRetryWorker.candidate(
            WorkInfo(request.id, WorkInfo.State.ENQUEUED, request.tags)
        ))
    }

    @Test
    fun `network policy update keeps request and batch identity`() {
        val candidate = FailedTransactionRetryCandidate(7, "original-hash")
        val original = FailedTransactionRetryWorker.request(candidate, Constraints.NONE, batch)
        val updated = FailedTransactionRetryWorker.request(
            candidate,
            Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build(),
            batch,
            original.id
        )
        assertEquals(original.id, updated.id)
        assertEquals(original.tags, updated.tags)
        assertEquals(original.workSpec.input, updated.workSpec.input)
        assertEquals(NetworkType.UNMETERED, updated.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `invalid candidate metadata is not rescheduled`() {
        assertNull(FailedTransactionRetryWorker.candidate(work(WorkInfo.State.ENQUEUED)))
        assertNull(FailedTransactionRetryWorker.candidate(
            work(WorkInfo.State.ENQUEUED, extraTags = setOf("failed-retry-log:invalid:hash"))
        ))
    }
}
