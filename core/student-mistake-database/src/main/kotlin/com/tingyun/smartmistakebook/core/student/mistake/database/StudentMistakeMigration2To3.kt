package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds an idempotency receipt for whole-problem self reports and makes pre-v3 active mistakes
 * review-eligible. The migration is additive: no existing table or column is removed.
 */
internal val STUDENT_MISTAKE_MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_review_self_report_receipt` (
                `self_report_id` TEXT NOT NULL,
                `report_canonical_fingerprint` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `queue_item_id` TEXT NOT NULL,
                `report_kind` TEXT NOT NULL,
                `reported_at_epoch_millis` INTEGER NOT NULL,
                `next_available_at_epoch_millis` INTEGER NOT NULL,
                `next_due_at_epoch_millis` INTEGER,
                `scheduling_policy_version` TEXT NOT NULL,
                PRIMARY KEY(`self_report_id`),
                FOREIGN KEY(`queue_item_id`)
                    REFERENCES `student_review_queue_item`(`queue_item_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_review_self_report_receipt_queue_item_id` " +
                "ON `student_review_self_report_receipt` (`queue_item_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_review_self_report_receipt_report_canonical_fingerprint` " +
                "ON `student_review_self_report_receipt` (`report_canonical_fingerprint`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_review_self_report_receipt_learner_id_reported_at_epoch_millis` " +
                "ON `student_review_self_report_receipt` " +
                "(`learner_id`, `reported_at_epoch_millis`)",
        )

        connection.execSQL(
            """
            INSERT INTO `student_review_candidate` (
                `candidate_id`,
                `learner_id`,
                `practice_unit_id`,
                `basis_revision_id`,
                `reason_codes_wire`,
                `item_family_id`,
                `estimated_duration_seconds`,
                `available_at_epoch_millis`,
                `due_at_epoch_millis`,
                `source_evidence_event_kind`,
                `source_evidence_event_id`,
                `source_evidence_sequence`,
                `source_evidence_canonical_fingerprint`,
                `candidate_version`,
                `created_at_epoch_millis`,
                `updated_at_epoch_millis`
            )
            SELECT
                unit.`practice_unit_id`,
                collection.`learner_id`,
                unit.`practice_unit_id`,
                unit.`basis_revision_id`,
                '1:13:saved-mistake',
                unit.`item_family_id`,
                unit.`estimated_duration_seconds`,
                collection.`changed_at_epoch_millis`,
                NULL,
                NULL,
                NULL,
                NULL,
                NULL,
                revision.`revision_number`,
                collection.`changed_at_epoch_millis`,
                collection.`changed_at_epoch_millis`
            FROM `student_practice_unit` AS unit
            INNER JOIN `student_problem_revision` AS revision
                ON revision.`revision_id` = unit.`basis_revision_id`
            INNER JOIN `student_problem_document` AS problem
                ON problem.`problem_id` = unit.`problem_id`
            INNER JOIN `student_problem_collection` AS collection
                ON collection.`practice_unit_id` = unit.`practice_unit_id`
            WHERE problem.`lifecycle_state` = 'ACTIVE'
              AND collection.`mistake_state` = 'ACTIVE'
              AND NOT EXISTS (
                  SELECT 1
                  FROM `student_review_candidate` AS existing
                  WHERE existing.`learner_id` = collection.`learner_id`
                    AND existing.`practice_unit_id` = unit.`practice_unit_id`
              )
            """.trimIndent(),
        )
    }
}
