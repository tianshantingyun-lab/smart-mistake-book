package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val BATCH_BOUNDARY_CLAIM_TIME_MIGRATION_41_42 =
    object : Migration(41, 42) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val existingColumns = connection.prepare(
                "PRAGMA table_info(`batch_import_page`)",
            ).use { statement ->
                buildSet {
                    while (statement.step()) add(statement.getText(1))
                }
            }
            if ("boundary_claimed_at_epoch_millis" !in existingColumns) {
                connection.execSQL(
                    """
                    ALTER TABLE `batch_import_page`
                    ADD COLUMN `boundary_claimed_at_epoch_millis` INTEGER
                    """.trimIndent(),
                )
            }
            connection.execSQL(
                """
                UPDATE `batch_import_page`
                SET `boundary_claimed_at_epoch_millis` = `updated_at_epoch_millis`
                WHERE `boundary_after_status` = 'CHECKING'
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `batch_import_boundary_resolution_receipt` (
                    `job_id` TEXT NOT NULL,
                    `page_index` INTEGER NOT NULL,
                    `primary_draft_session_id` TEXT NOT NULL,
                    `following_draft_session_id` TEXT NOT NULL,
                    `resolution` TEXT NOT NULL,
                    `boundary_claimed_at_epoch_millis` INTEGER NOT NULL,
                    `capture_merge_receipt_ref` TEXT,
                    `occurred_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`job_id`, `page_index`),
                    FOREIGN KEY(`job_id`, `page_index`)
                        REFERENCES `batch_import_page`(`job_id`, `page_index`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
        }
    }
