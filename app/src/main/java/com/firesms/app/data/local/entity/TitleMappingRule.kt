package com.firesms.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "title_mapping_rules")
data class TitleMappingRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "title_pattern") val titlePattern: String,
    @ColumnInfo(name = "category_name") val categoryName: String = "",
    @ColumnInfo(name = "budget_name") val budgetName: String = "",
    val enabled: Boolean = true,
    val priority: Int = 10,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
