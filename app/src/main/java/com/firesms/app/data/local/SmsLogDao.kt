package com.firesms.app.data.local

import androidx.paging.PagingSource
import androidx.room.*
import com.firesms.app.data.local.entity.SmsLog
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

/** The columns an Activity list row needs: no full SMS body and no original snapshot. */
data class ActivitySummary(
    val id: Long,
    val sender: String,
    @ColumnInfo(name = "body_preview") val bodyPreview: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    val status: String,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "transaction_snapshot_json") val transactionSnapshotJson: String?,
    @ColumnInfo(name = "locally_modified_at") val locallyModifiedAt: Long?
)

/** History totals for the Activity health panel and filter chips. */
data class ActivityCounts(
    val total: Int = 0,
    val unparsed: Int = 0,
    val failed: Int = 0,
    val queued: Int = 0,
    val processing: Int = 0,
    val modified: Int = 0,
    /** Failed or queued items that can be retried by hand. */
    val retryable: Int = 0
) {
    val attention: Int get() = unparsed + failed
    val waiting: Int get() = queued + processing
}

@Dao
abstract class SmsLogDao {
    @Query("SELECT * FROM sms_log ORDER BY received_at DESC")
    abstract fun getAllByDateDesc(): Flow<List<SmsLog>>

    @Query("SELECT * FROM sms_log WHERE sms_hash = :hash LIMIT 1")
    abstract suspend fun findByHash(hash: String): SmsLog?

    @Query("SELECT * FROM sms_log WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: Long): SmsLog?

    @Query("SELECT * FROM sms_log WHERE id = :id LIMIT 1")
    abstract fun observeById(id: Long): Flow<SmsLog?>

    @Query("SELECT * FROM sms_log")
    abstract suspend fun getAllForBackup(): List<SmsLog>

    @Query("SELECT * FROM sms_log WHERE status = :status ORDER BY received_at DESC")
    abstract fun getByStatus(status: String): Flow<List<SmsLog>>

    @Query("SELECT id, sms_hash FROM sms_log WHERE status IN ('failed', 'queued') AND firefly_id IS NULL ORDER BY received_at ASC")
    abstract suspend fun getRetryableFailedCandidates(): List<FailedTransactionRetryCandidate>

    // ─── Activity list ─────────────────────────────────────────
    // [pattern] is a LIKE pattern with '\' as the escape character, or null for no search.

    @Query(
        "SELECT " + SUMMARY_COLUMNS + " FROM sms_log WHERE " + SEARCH_CLAUSE +
            " ORDER BY received_at DESC, id DESC"
    )
    abstract fun pageAll(pattern: String?): PagingSource<Int, ActivitySummary>

    @Query(
        "SELECT " + SUMMARY_COLUMNS + " FROM sms_log WHERE status IN (:statuses) AND " + SEARCH_CLAUSE +
            " ORDER BY received_at DESC, id DESC"
    )
    abstract fun pageByStatus(statuses: List<String>, pattern: String?): PagingSource<Int, ActivitySummary>

    @Query(
        "SELECT " + SUMMARY_COLUMNS + " FROM sms_log WHERE locally_modified_at IS NOT NULL AND " + SEARCH_CLAUSE +
            " ORDER BY received_at DESC, id DESC"
    )
    abstract fun pageModified(pattern: String?): PagingSource<Int, ActivitySummary>

    @Query("""
        SELECT COUNT(*) AS total,
               COALESCE(SUM(status = 'unparsed'), 0) AS unparsed,
               COALESCE(SUM(status = 'failed'), 0) AS failed,
               COALESCE(SUM(status = 'queued'), 0) AS queued,
               COALESCE(SUM(status = 'pending'), 0) AS processing,
               COALESCE(SUM(locally_modified_at IS NOT NULL), 0) AS modified,
               COALESCE(SUM(status IN ('failed', 'queued') AND firefly_id IS NULL), 0) AS retryable
        FROM sms_log
    """)
    abstract fun observeCounts(): Flow<ActivityCounts>

    // ─── Writes ────────────────────────────────────────────────
    // Writes that change the transaction snapshot also refresh search_text.

    suspend fun insert(log: SmsLog): Long = insertRow(log.withSearchText())

    suspend fun insertAll(logs: List<SmsLog>) = insertRows(logs.map { it.withSearchText() })

    suspend fun update(log: SmsLog) = updateRow(log.withSearchText())

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertRow(log: SmsLog): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRows(logs: List<SmsLog>)

    @Update
    protected abstract suspend fun updateRow(log: SmsLog)

    @Query("UPDATE sms_log SET status = :status, processed_at = :processedAt, firefly_id = :fireflyId, error_message = :errorMessage WHERE id = :id")
    abstract suspend fun updateStatus(id: Long, status: String, processedAt: Long? = null, fireflyId: String? = null, errorMessage: String? = null)

    suspend fun initializeTransactionSnapshot(id: Long, snapshotJson: String) =
        initializeSnapshotRow(id, snapshotJson, transactionSearchText(snapshotJson))

    @Query("""
        UPDATE sms_log
        SET original_transaction_snapshot_json = COALESCE(original_transaction_snapshot_json, :snapshotJson),
            transaction_snapshot_json = :snapshotJson,
            search_text = :searchText
        WHERE id = :id
    """)
    protected abstract suspend fun initializeSnapshotRow(id: Long, snapshotJson: String, searchText: String)

    suspend fun updateTransactionSnapshotState(id: Long, snapshotJson: String, modifiedAt: Long?) =
        updateSnapshotStateRow(id, snapshotJson, modifiedAt, transactionSearchText(snapshotJson))

    @Query("""
        UPDATE sms_log
        SET original_transaction_snapshot_json = COALESCE(original_transaction_snapshot_json, :snapshotJson),
            transaction_snapshot_json = :snapshotJson,
            locally_modified_at = :modifiedAt,
            search_text = :searchText
        WHERE id = :id
    """)
    protected abstract suspend fun updateSnapshotStateRow(
        id: Long, snapshotJson: String, modifiedAt: Long?, searchText: String
    )

    @Delete
    abstract suspend fun delete(log: SmsLog)

    @Query("DELETE FROM sms_log WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("""
        SELECT id FROM sms_log
        WHERE received_at < :cutoffMillis
          AND NOT EXISTS (
              SELECT 1 FROM pending_transactions
              WHERE pending_transactions.sms_log_id = sms_log.id
                AND pending_transactions.retries < pending_transactions.max_retries
          )
    """)
    abstract suspend fun getDeletableIdsOlderThan(cutoffMillis: Long): List<Long>

    @Query("DELETE FROM sms_log WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM sms_log")
    abstract suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM sms_log WHERE status = :status")
    abstract fun countByStatus(status: String): Flow<Int>

    companion object {
        private const val SUMMARY_COLUMNS =
            "id, sender, substr(body, 1, 200) AS body_preview, received_at, status, error_message, " +
                "transaction_snapshot_json, locally_modified_at"
        private const val SEARCH_CLAUSE =
            "(:pattern IS NULL OR sender LIKE :pattern ESCAPE '\\' OR search_text LIKE :pattern ESCAPE '\\')"
    }
}

private fun SmsLog.withSearchText(): SmsLog = copy(searchText = transactionSearchText(transactionSnapshotJson))

/**
 * Lower-case text Activity search matches against: group title, split descriptions and amounts.
 * Amounts appear both as stored and in display form ("1234.5" and "1234.50"), plus the split total.
 */
fun transactionSearchText(snapshotJson: String?): String {
    val snapshot = decodeTransactionSnapshot(snapshotJson) ?: return ""
    val amounts = snapshot.transactions.mapNotNull { it.amount.trim().toBigDecimalOrNull() }
    val parts = buildList {
        snapshot.groupTitle?.let(::add)
        snapshot.transactions.forEach { split ->
            add(split.description)
            add(split.amount)
        }
        amounts.forEach { add(it.searchForm()) }
        if (amounts.size > 1) add(amounts.fold(BigDecimal.ZERO, BigDecimal::add).searchForm())
    }
    return parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ").lowercase()
}

private fun BigDecimal.searchForm(): String {
    val stripped = stripTrailingZeros()
    return (if (stripped.scale() < 2) stripped.setScale(2) else stripped).toPlainString()
}

/** Builds a case-insensitive "contains" LIKE pattern, or null when there is nothing to search for. */
fun activitySearchPattern(query: String): String? {
    val trimmed = query.trim().lowercase()
    if (trimmed.isEmpty()) return null
    val escaped = buildString {
        trimmed.forEach { char ->
            if (char == '\\' || char == '%' || char == '_') append('\\')
            append(char)
        }
    }
    return "%$escaped%"
}
