package com.firesms.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "pending_transactions")
data class PendingTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sms_log_id") val smsLogId: Long,
    @ColumnInfo(name = "firefly_payload") val fireflyPayload: String,
    val retries: Int = 0,
    @ColumnInfo(name = "max_retries") val maxRetries: Int = 5,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
