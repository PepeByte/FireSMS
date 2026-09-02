package com.firesms.app.data.local

import androidx.room.*
import com.firesms.app.data.local.entity.PendingTransaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingTransactionDao {
    @Query("SELECT * FROM pending_transactions ORDER BY next_retry_at ASC")
    fun getAll(): Flow<List<PendingTransaction>>

    @Query("SELECT * FROM pending_transactions")
    suspend fun getAllForBackup(): List<PendingTransaction>

    @Query("SELECT * FROM pending_transactions WHERE retries < max_retries AND next_retry_at <= :currentTime")
    suspend fun getDueRetries(currentTime: Long): List<PendingTransaction>

    @Query("SELECT COUNT(*) FROM pending_transactions")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE retries < max_retries")
    fun countPending(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE retries < max_retries")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: PendingTransaction): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<PendingTransaction>)

    @Update
    suspend fun update(transaction: PendingTransaction)

    @Query("UPDATE pending_transactions SET retries = :retries, next_retry_at = :nextRetryAt, last_error = :lastError WHERE id = :id")
    suspend fun updateRetry(id: Long, retries: Int, nextRetryAt: Long, lastError: String?)

    @Delete
    suspend fun delete(transaction: PendingTransaction)

    @Query("DELETE FROM pending_transactions WHERE sms_log_id = :smsLogId")
    suspend fun deleteBySmsLogId(smsLogId: Long)

    @Query("DELETE FROM pending_transactions WHERE sms_log_id IN (:smsLogIds)")
    suspend fun deleteBySmsLogIds(smsLogIds: List<Long>)

    @Query("DELETE FROM pending_transactions")
    suspend fun clearAll()
}
