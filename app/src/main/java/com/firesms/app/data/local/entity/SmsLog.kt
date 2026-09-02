package com.firesms.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "sms_log")
data class SmsLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sms_hash") val smsHash: String,
    val sender: String,
    val body: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "processed_at") val processedAt: Long? = null,
    val status: String,
    @ColumnInfo(name = "firefly_id") val fireflyId: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "original_transaction_snapshot_json") val originalTransactionSnapshotJson: String? = null,
    @ColumnInfo(name = "transaction_snapshot_json") val transactionSnapshotJson: String? = null,
    @ColumnInfo(name = "locally_modified_at") val locallyModifiedAt: Long? = null
)
