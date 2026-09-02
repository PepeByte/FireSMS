package com.firesms.app.data.local

import androidx.room.*
import com.firesms.app.data.local.entity.ParserRule
import kotlinx.coroutines.flow.Flow

@Dao
interface ParserRuleDao {
    @Query("SELECT * FROM parser_rules ORDER BY priority ASC")
    fun getAll(): Flow<List<ParserRule>>

    @Query("SELECT * FROM parser_rules")
    suspend fun getAllForBackup(): List<ParserRule>

    @Query("SELECT * FROM parser_rules WHERE enabled = 1 ORDER BY priority ASC")
    fun getAllEnabledOrderedByPriority(): Flow<List<ParserRule>>

    @Query("SELECT * FROM parser_rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ParserRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: ParserRule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<ParserRule>)

    @Update
    suspend fun update(rule: ParserRule)

    @Delete
    suspend fun delete(rule: ParserRule)

    @Query("DELETE FROM parser_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM parser_rules")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM parser_rules")
    fun count(): Flow<Int>
}
