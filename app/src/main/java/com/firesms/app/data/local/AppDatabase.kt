package com.firesms.app.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.data.local.entity.PendingTransaction
import com.firesms.app.data.local.entity.SmsLog
import com.firesms.app.data.local.entity.TitleMappingRule

@Database(
    entities = [SmsLog::class, PendingTransaction::class, ParserRule::class, TitleMappingRule::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smsLogDao(): SmsLogDao
    abstract fun pendingTransactionDao(): PendingTransactionDao
    abstract fun parserRuleDao(): ParserRuleDao
    abstract fun titleMappingRuleDao(): TitleMappingRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = Migration(1, 2) { db ->
            db.execSQL("ALTER TABLE parser_rules ADD COLUMN date_pattern TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE parser_rules ADD COLUMN date_format TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE parser_rules ADD COLUMN remarks_pattern TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE parser_rules ADD COLUMN remarks_template TEXT DEFAULT NULL")
        }

        private val MIGRATION_2_3 = Migration(2, 3) { db ->
            db.execSQL("ALTER TABLE parser_rules ADD COLUMN source_account_name TEXT DEFAULT NULL")
        }

        private val MIGRATION_3_4 = Migration(3, 4) { db ->
            db.execSQL("ALTER TABLE parser_rules ADD COLUMN source_account_id TEXT DEFAULT NULL")
        }

        private val MIGRATION_4_5 = Migration(4, 5) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS title_mapping_rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title_pattern TEXT NOT NULL,
                    category_name TEXT NOT NULL DEFAULT '',
                    budget_name TEXT NOT NULL DEFAULT '',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    priority INTEGER NOT NULL DEFAULT 10,
                    created_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }

        private val MIGRATION_5_6 = Migration(5, 6) { db ->
            db.execSQL("ALTER TABLE sms_log ADD COLUMN original_transaction_snapshot_json TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE sms_log ADD COLUMN transaction_snapshot_json TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE sms_log ADD COLUMN locally_modified_at INTEGER DEFAULT NULL")
        }

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "firesms.db"
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6
                ).build().also { INSTANCE = it }
            }
        }
    }
}
