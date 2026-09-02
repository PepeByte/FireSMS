package com.firesms.app.data.local

import androidx.room.withTransaction

class HistoryCleaner(private val database: AppDatabase) {
    /**
     * Deletes local SMS history before [cutoffMillis], including retry rows tied to it.
     * This never deletes transactions from Firefly III.
     */
    suspend fun clearBefore(cutoffMillis: Long): Int = database.withTransaction {
        val smsLogDao = database.smsLogDao()
        val ids = smsLogDao.getDeletableIdsOlderThan(cutoffMillis)
        if (ids.isEmpty()) return@withTransaction 0

        database.pendingTransactionDao().deleteBySmsLogIds(ids)
        smsLogDao.deleteByIds(ids)
        ids.size
    }

    suspend fun applyRetention(retention: HistoryRetention, nowMillis: Long = System.currentTimeMillis()): Int {
        val cutoff = retention.cutoffMillis(nowMillis) ?: return 0
        return clearBefore(cutoff)
    }
}
