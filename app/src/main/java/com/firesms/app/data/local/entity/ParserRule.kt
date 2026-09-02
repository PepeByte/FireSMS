package com.firesms.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "parser_rules")
data class ParserRule(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "sender_pattern") val senderPattern: String,
    @ColumnInfo(name = "body_pattern") val bodyPattern: String,
    @ColumnInfo(name = "amount_template") val amountTemplate: String,
    @ColumnInfo(name = "description_source") val descriptionSource: String,
    @ColumnInfo(name = "description_value") val descriptionValue: String,
    @ColumnInfo(name = "source_account_keyword") val sourceAccountKeyword: String? = null,
    @ColumnInfo(name = "source_account_name") val sourceAccountName: String? = null,
    @ColumnInfo(name = "source_account_id") val sourceAccountId: String? = null,
    @ColumnInfo(name = "destination_account_name") val destinationAccountName: String? = null,
    val type: String,
    val priority: Int = 10,
    @ColumnInfo(name = "date_pattern") val datePattern: String? = null,
    @ColumnInfo(name = "date_format") val dateFormat: String? = null,
    @ColumnInfo(name = "remarks_pattern") val remarksPattern: String? = null,
    @ColumnInfo(name = "remarks_template") val remarksTemplate: String? = null
)
