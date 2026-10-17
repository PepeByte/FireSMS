package com.firesms.app.data.local

import androidx.room.ColumnInfo
import com.firesms.app.data.local.entity.SmsLog

data class FailedTransactionRetryCandidate(
    val id: Long,
    @ColumnInfo(name = "sms_hash") val smsHash: String
)

/** Never submit another create for a log already linked to a Firefly transaction. */
val SmsLog.canRetryFailedTransaction: Boolean
    get() = status == "failed" && fireflyId == null
