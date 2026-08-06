package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the student-owned capture-save acknowledgement outbox.
 *
 * Existing mistake rows are intentionally not backfilled: legacy rows continue to be handled by
 * the exact migration path, while only new student-first saves create these handoffs.
 */
internal val STUDENT_MISTAKE_MIGRATION_6_7 =
    object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STUDENT_CAPTURE_SAVE_HANDOFF_V7_STATEMENTS.forEach(connection::execSQL)
        }
    }

private val STUDENT_CAPTURE_SAVE_HANDOFF_V7_STATEMENTS =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS `student_capture_save_handoff` (
          `intent_id` TEXT NOT NULL,
          `source_kind` TEXT NOT NULL,
          `source_canonical_fingerprint` TEXT NOT NULL,
          `learner_id` TEXT NOT NULL,
          `draft_id` TEXT NOT NULL,
          `draft_revision_number` INTEGER NOT NULL,
          `session_id` TEXT,
          `basis_revision_number` INTEGER,
          `workspace_version` INTEGER,
          `workspace_canonical_fingerprint` TEXT,
          `confirmation_request_id` TEXT,
          `save_request_id` TEXT,
          `target_subject` TEXT NOT NULL,
          `target_problem_id` TEXT NOT NULL,
          `target_practice_unit_id` TEXT NOT NULL,
          `target_revision_id` TEXT NOT NULL,
          `target_revision_number` INTEGER NOT NULL,
          `target_document_canonical_fingerprint` TEXT NOT NULL,
          `error_book_entry_id` TEXT NOT NULL,
          `target_canonical_fingerprint` TEXT NOT NULL,
          `occurred_at_epoch_millis` INTEGER NOT NULL,
          `acknowledged_at_epoch_millis` INTEGER,
          `schema_version` INTEGER NOT NULL,
          PRIMARY KEY(`intent_id`),
          FOREIGN KEY(`target_revision_id`) REFERENCES `student_problem_revision`(`revision_id`)
            ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_student_capture_save_handoff_draft_id`
        ON `student_capture_save_handoff` (`draft_id`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_student_capture_save_handoff_session_id`
        ON `student_capture_save_handoff` (`session_id`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_student_capture_save_handoff_target_revision_id`
        ON `student_capture_save_handoff` (`target_revision_id`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
          `index_student_capture_save_handoff_target_canonical_fingerprint`
        ON `student_capture_save_handoff` (`target_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
          `index_student_capture_save_handoff_learner_id_acknowledged_at_epoch_millis_occurred_at_epoch_millis_intent_id`
        ON `student_capture_save_handoff`
          (`learner_id`, `acknowledged_at_epoch_millis`, `occurred_at_epoch_millis`, `intent_id`)
        """.trimIndent(),
    )
