package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val LEGACY_AUTHORITY_CUTOVER_JOURNAL_MIGRATION_37_38 =
    object : Migration(37, 38) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `legacy_authority_cutover_stage_receipt` (
                    `stage_ordinal` INTEGER NOT NULL,
                    `stage_name` TEXT NOT NULL,
                    `target_database_name` TEXT NOT NULL,
                    `migrated_record_count` INTEGER NOT NULL,
                    `checkpoint` TEXT NOT NULL,
                    `destination_fingerprint` TEXT NOT NULL,
                    `completed_at_epoch_millis` INTEGER NOT NULL,
                    `predecessor_receipt_fingerprint` TEXT,
                    `receipt_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(`stage_ordinal`),
                    CHECK(`stage_ordinal` > 0),
                    CHECK(`migrated_record_count` >= 0),
                    CHECK(`completed_at_epoch_millis` >= 0)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_legacy_authority_cutover_stage_receipt_stage_name`
                ON `legacy_authority_cutover_stage_receipt` (`stage_name`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_legacy_authority_cutover_stage_receipt_receipt_fingerprint`
                ON `legacy_authority_cutover_stage_receipt` (`receipt_fingerprint`)
                """.trimIndent(),
            )
        }
    }
