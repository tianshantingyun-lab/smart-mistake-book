package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val CAPTURE_DRAFT_MERGE_SESSION_RECEIPT_MIGRATION_40_41 =
    object : Migration(40, 41) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `capture_draft_merge_session_receipt` (
                    `receipt_reference` TEXT NOT NULL,
                    `batch_job_id` TEXT NOT NULL,
                    `batch_page_index` INTEGER NOT NULL,
                    `primary_draft_id` TEXT NOT NULL,
                    `following_draft_id` TEXT NOT NULL,
                    `merged_draft_id` TEXT NOT NULL,
                    `asset_order_fingerprint` TEXT NOT NULL,
                    `session_version` INTEGER NOT NULL,
                    `source_asset_count` INTEGER NOT NULL,
                    `request_canonical_fingerprint` TEXT NOT NULL,
                    `merged_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`batch_job_id`, `batch_page_index`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                `index_capture_draft_merge_session_receipt_receipt_reference`
                ON `capture_draft_merge_session_receipt` (`receipt_reference`)
                """.trimIndent(),
            )
        }
    }
