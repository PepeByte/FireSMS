package com.firesms.app.data.local

import androidx.room.*
import com.firesms.app.data.local.entity.TitleMappingRule
import kotlinx.coroutines.flow.Flow

@Dao
interface TitleMappingRuleDao {
    @Query("SELECT * FROM title_mapping_rules ORDER BY priority ASC")
    fun getAll(): Flow<List<TitleMappingRule>>

    @Query("SELECT * FROM title_mapping_rules")
    suspend fun getAllForBackup(): List<TitleMappingRule>

    @Query("SELECT * FROM title_mapping_rules WHERE enabled = 1 ORDER BY priority ASC")
    fun getAllEnabled(): Flow<List<TitleMappingRule>>

    @Query("SELECT * FROM title_mapping_rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TitleMappingRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: TitleMappingRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<TitleMappingRule>)

    @Update
    suspend fun update(rule: TitleMappingRule)

    @Delete
    suspend fun delete(rule: TitleMappingRule)

    @Query("DELETE FROM title_mapping_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM title_mapping_rules")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM title_mapping_rules")
    fun count(): Flow<Int>
}
