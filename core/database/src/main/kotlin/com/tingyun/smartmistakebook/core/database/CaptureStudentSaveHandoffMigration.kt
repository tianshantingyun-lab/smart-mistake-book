package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val CAPTURE_STUDENT_SAVE_HANDOFF_MIGRATION_38_39 =
    object : Migration(38, 39) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `capture_student_save_handoff` (
                    `intent_id` TEXT NOT NULL,
                    `intent_canonical_fingerprint` TEXT NOT NULL,
                    `learner_id` TEXT NOT NULL,
                    `draft_id` TEXT NOT NULL,
                    `draft_revision_number` INTEGER NOT NULL,
                    `session_id` TEXT,
                    `target_subject` TEXT NOT NULL,
                    `target_problem_id` TEXT NOT NULL,
                    `target_practice_unit_id` TEXT NOT NULL,
                    `target_problem_ref_schema_version` INTEGER NOT NULL,
                    `target_revision_id` TEXT NOT NULL,
                    `target_revision_number` INTEGER NOT NULL,
                    `target_document_canonical_fingerprint` TEXT NOT NULL,
                    `target_revision_ref_schema_version` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    `prepared_at_epoch_millis` INTEGER NOT NULL,
                    `finalized_at_epoch_millis` INTEGER,
                    `target_save_receipt_fingerprint` TEXT,
                    `state_version` INTEGER NOT NULL,
                    `schema_version` INTEGER NOT NULL,
                    PRIMARY KEY(`intent_id`),
                    CHECK(`draft_revision_number` > 0),
                    CHECK(`target_revision_number` > 0),
                    CHECK(`state` IN ('PREPARED', 'FINALIZED')),
                    CHECK(`prepared_at_epoch_millis` >= 0),
                    CHECK(
                        (`state` = 'PREPARED'
                            AND `finalized_at_epoch_millis` IS NULL
                            AND `target_save_receipt_fingerprint` IS NULL
                            AND `state_version` = 1)
                        OR
                        (`state` = 'FINALIZED'
                            AND `finalized_at_epoch_millis` IS NOT NULL
                            AND `target_save_receipt_fingerprint` IS NOT NULL
                            AND `state_version` = 2)
                    )
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_capture_student_save_handoff_learner_id_state_prepared_at_epoch_millis_intent_id`
                ON `capture_student_save_handoff`
                    (`learner_id`, `state`, `prepared_at_epoch_millis`, `intent_id`)
                """.trimIndent(),
            )
        }
    }
