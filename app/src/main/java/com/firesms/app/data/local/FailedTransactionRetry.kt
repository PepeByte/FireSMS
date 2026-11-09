package com.firesms.app.data.local

import androidx.room.ColumnInfo
import com.firesms.app.data.local.entity.SmsLog

data class FailedTransactionRetryCandidate(
    val id: Long,
    @ColumnInfo(name = "sms_hash") val smsHash: String
)

/** Statuses a user can retry by hand. Queued items are retried early instead of waiting for backoff. */
val RETRYABLE_STATUSES = setOf("failed", "queued")

/** Never submit another create for a log already linked to a Firefly transaction. */
val SmsLog.canRetryFailedTransaction: Boolean
    get() = status in RETRYABLE_STATUSES && fireflyId == null
