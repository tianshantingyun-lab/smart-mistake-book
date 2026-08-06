package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val BATCH_CAPTURE_DRAFT_IMPORT_MIGRATION_42_43 =
    object : Migration(42, 43) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `batch_capture_content_binding` (
                    `batch_job_id` TEXT NOT NULL,
                    `content_sha256` TEXT NOT NULL,
                    `original_draft_id` TEXT NOT NULL,
                    `source_asset_id` TEXT NOT NULL,
                    `bound_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`batch_job_id`, `content_sha256`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `capture_draft_batch_import_receipt` (
                    `receipt_reference` TEXT NOT NULL,
                    `batch_job_id` TEXT NOT NULL,
                    `batch_page_index` INTEGER NOT NULL,
                    `request_fingerprint` TEXT NOT NULL,
                    `original_draft_id` TEXT NOT NULL,
                    `source_asset_id` TEXT NOT NULL,
                    `disposition` TEXT NOT NULL,
                    `imported_at_epoch_millis` INTEGER NOT NULL,
                    `receipt_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(`batch_job_id`, `batch_page_index`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                `index_capture_draft_batch_import_receipt_receipt_reference`
                ON `capture_draft_batch_import_receipt` (`receipt_reference`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                `index_capture_draft_batch_import_receipt_receipt_fingerprint`
                ON `capture_draft_batch_import_receipt` (`receipt_fingerprint`)
                """.trimIndent(),
            )
        }
    }
