package com.firesms.app.data.local

import androidx.room.*
import com.firesms.app.data.local.entity.SmsLog
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsLogDao {
    @Query("SELECT * FROM sms_log ORDER BY received_at DESC")
    fun getAllByDateDesc(): Flow<List<SmsLog>>

    @Query("SELECT * FROM sms_log WHERE sms_hash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): SmsLog?


    @Query("SELECT * FROM sms_log WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SmsLog?

    @Query("SELECT * FROM sms_log")
    suspend fun getAllForBackup(): List<SmsLog>

    @Query("SELECT * FROM sms_log WHERE status = :status ORDER BY received_at DESC")
    fun getByStatus(status: String): Flow<List<SmsLog>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: SmsLog): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<SmsLog>)

    @Update
    suspend fun update(log: SmsLog)

    @Query("UPDATE sms_log SET status = :status, processed_at = :processedAt, firefly_id = :fireflyId, error_message = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, processedAt: Long? = null, fireflyId: String? = null, errorMessage: String? = null)

    @Query("""
        UPDATE sms_log
        SET original_transaction_snapshot_json = COALESCE(original_transaction_snapshot_json, :snapshotJson),
            transaction_snapshot_json = :snapshotJson
        WHERE id = :id
    """)
    suspend fun initializeTransactionSnapshot(id: Long, snapshotJson: String)

    @Query("""
        UPDATE sms_log
        SET original_transaction_snapshot_json = COALESCE(original_transaction_snapshot_json, :snapshotJson),
            transaction_snapshot_json = :snapshotJson,
            locally_modified_at = :modifiedAt
        WHERE id = :id
    """)
    suspend fun updateTransactionSnapshotState(id: Long, snapshotJson: String, modifiedAt: Long?)

    @Delete
    suspend fun delete(log: SmsLog)

    @Query("""
        SELECT id FROM sms_log
        WHERE received_at < :cutoffMillis
          AND NOT EXISTS (
              SELECT 1 FROM pending_transactions
              WHERE pending_transactions.sms_log_id = sms_log.id
                AND pending_transactions.retries < pending_transactions.max_retries
          )
    """)
    suspend fun getDeletableIdsOlderThan(cutoffMillis: Long): List<Long>

    @Query("DELETE FROM sms_log WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM sms_log")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM sms_log WHERE status = :status")
    fun countByStatus(status: String): Flow<Int>
}
