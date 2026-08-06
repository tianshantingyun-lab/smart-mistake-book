package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the owner-only trusted review answer ledger.
 *
 * The migration intentionally creates empty tables. Legacy answer snapshots, model solution text,
 * and existing review receipts do not prove a structured grading rule and are never backfilled.
 */
internal val STUDENT_MISTAKE_MIGRATION_13_14 =
    object : Migration(13, 14) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STUDENT_TRUSTED_REVIEW_V14_TABLE_SQL.forEach(connection::execSQL)
            STUDENT_TRUSTED_REVIEW_V14_INDEX_SQL.forEach(connection::execSQL)
            createStudentReviewReceiptImmutabilityTriggers(
                connection = connection,
                tableNames = STUDENT_TRUSTED_REVIEW_V14_TABLES,
            )
            verifyStudentReviewReceiptImmutabilityTriggers(
                connection = connection,
                tableNames = STUDENT_TRUSTED_REVIEW_V14_TABLES,
            )
            check(
                STUDENT_TRUSTED_REVIEW_V14_TABLES.all { tableName ->
                    connection.countRows(tableName) == 0L
                },
            ) {
                "Trusted review v14 migration must not synthesize answer or attempt facts"
            }
        }
    }

private val STUDENT_TRUSTED_REVIEW_V14_TABLES =
    listOf(
        "student_trusted_review_answer_rule",
        "student_trusted_review_lease_receipt",
        "student_trusted_review_attempt_receipt",
        "student_trusted_review_assistance_receipt",
    )

private val STUDENT_TRUSTED_REVIEW_V14_TABLE_SQL =
    listOf(
        """
        CREATE TABLE `student_trusted_review_answer_rule` (
            `answer_rule_id` TEXT NOT NULL,
            `learner_id` TEXT NOT NULL,
            `problem_id` TEXT NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `question_generation` INTEGER NOT NULL,
            `question_version` TEXT NOT NULL,
            `rule_kind` TEXT NOT NULL,
            `accepted_values_wire` TEXT,
            `correct_values_wire` TEXT,
            `expected_numeric_value` TEXT,
            `absolute_tolerance` TEXT,
            `expected_unit` TEXT,
            `answer_spec_version` TEXT NOT NULL,
            `provenance_kind` TEXT NOT NULL,
            `provenance_reference_id` TEXT NOT NULL,
            `provenance_canonical_fingerprint` TEXT NOT NULL,
            `rule_canonical_fingerprint` TEXT NOT NULL,
            `admitted_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`answer_rule_id`),
            FOREIGN KEY(`problem_id`)
                REFERENCES `student_problem_document`(`problem_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`basis_revision_id`)
                REFERENCES `student_problem_revision`(`revision_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE `student_trusted_review_lease_receipt` (
            `lease_receipt_id` TEXT NOT NULL,
            `lease_canonical_fingerprint` TEXT NOT NULL,
            `learner_id` TEXT NOT NULL,
            `plan_id` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `queue_item_id` TEXT NOT NULL,
            `expected_session_version` INTEGER NOT NULL,
            `presentation_id` TEXT NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `answer_rule_id` TEXT NOT NULL,
            `issued_at_epoch_millis` INTEGER NOT NULL,
            `valid_through_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`lease_receipt_id`),
            FOREIGN KEY(`answer_rule_id`)
                REFERENCES `student_trusted_review_answer_rule`(`answer_rule_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`session_id`, `plan_id`, `learner_id`)
                REFERENCES `student_review_session`(`session_id`, `plan_id`, `learner_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`queue_item_id`, `plan_id`, `learner_id`)
                REFERENCES `student_review_queue_item`(`queue_item_id`, `plan_id`, `learner_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`basis_revision_id`)
                REFERENCES `student_problem_revision`(`revision_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE `student_trusted_review_attempt_receipt` (
            `attempt_receipt_id` TEXT NOT NULL,
            `attempt_canonical_fingerprint` TEXT NOT NULL,
            `submission_idempotency_key` TEXT NOT NULL,
            `lease_receipt_id` TEXT NOT NULL,
            `learner_id` TEXT NOT NULL,
            `plan_id` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `queue_item_id` TEXT NOT NULL,
            `presentation_id` TEXT NOT NULL,
            `attempt_ordinal` INTEGER NOT NULL,
            `retry_count` INTEGER NOT NULL,
            `presentation_started_at_epoch_millis` INTEGER NOT NULL,
            `submitted_at_epoch_millis` INTEGER NOT NULL,
            `elapsed_duration_millis` INTEGER NOT NULL,
            `hint_count` INTEGER NOT NULL,
            `first_hint_at_epoch_millis` INTEGER,
            `last_hint_at_epoch_millis` INTEGER,
            `answer_revealed_at_epoch_millis` INTEGER,
            PRIMARY KEY(`attempt_receipt_id`),
            FOREIGN KEY(`lease_receipt_id`)
                REFERENCES `student_trusted_review_lease_receipt`(`lease_receipt_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`session_id`, `plan_id`, `learner_id`)
                REFERENCES `student_review_session`(`session_id`, `plan_id`, `learner_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`queue_item_id`, `plan_id`, `learner_id`)
                REFERENCES `student_review_queue_item`(`queue_item_id`, `plan_id`, `learner_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE TABLE `student_trusted_review_assistance_receipt` (
            `assistance_event_id` TEXT NOT NULL,
            `assistance_canonical_fingerprint` TEXT NOT NULL,
            `lease_receipt_id` TEXT NOT NULL,
            `learner_id` TEXT NOT NULL,
            `plan_id` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `queue_item_id` TEXT NOT NULL,
            `presentation_id` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `occurred_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`assistance_event_id`),
            FOREIGN KEY(`lease_receipt_id`)
                REFERENCES `student_trusted_review_lease_receipt`(`lease_receipt_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`session_id`, `plan_id`, `learner_id`)
                REFERENCES `student_review_session`(`session_id`, `plan_id`, `learner_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`queue_item_id`, `plan_id`, `learner_id`)
                REFERENCES `student_review_queue_item`(`queue_item_id`, `plan_id`, `learner_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )

private val STUDENT_TRUSTED_REVIEW_V14_INDEX_SQL =
    listOf(
        "CREATE UNIQUE INDEX `index_student_trusted_review_answer_rule_basis_revision_id` ON `student_trusted_review_answer_rule` (`basis_revision_id`)",
        "CREATE INDEX `index_student_trusted_review_answer_rule_problem_id` ON `student_trusted_review_answer_rule` (`problem_id`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_answer_rule_learner_id_basis_revision_id` ON `student_trusted_review_answer_rule` (`learner_id`, `basis_revision_id`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_answer_rule_rule_canonical_fingerprint` ON `student_trusted_review_answer_rule` (`rule_canonical_fingerprint`)",
        "CREATE INDEX `index_student_trusted_review_answer_rule_provenance_canonical_fingerprint` ON `student_trusted_review_answer_rule` (`provenance_canonical_fingerprint`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_lease_receipt_lease_canonical_fingerprint` ON `student_trusted_review_lease_receipt` (`lease_canonical_fingerprint`)",
        "CREATE INDEX `index_student_trusted_review_lease_receipt_answer_rule_id` ON `student_trusted_review_lease_receipt` (`answer_rule_id`)",
        "CREATE INDEX `index_student_trusted_review_lease_receipt_session_id_plan_id_learner_id` ON `student_trusted_review_lease_receipt` (`session_id`, `plan_id`, `learner_id`)",
        "CREATE INDEX `index_student_trusted_review_lease_receipt_queue_item_id_plan_id_learner_id` ON `student_trusted_review_lease_receipt` (`queue_item_id`, `plan_id`, `learner_id`)",
        "CREATE INDEX `index_student_trusted_review_lease_receipt_basis_revision_id` ON `student_trusted_review_lease_receipt` (`basis_revision_id`)",
        "CREATE INDEX `index_student_trusted_review_lease_receipt_learner_id_session_id_presentation_id` ON `student_trusted_review_lease_receipt` (`learner_id`, `session_id`, `presentation_id`)",
        "CREATE INDEX `index_student_trusted_review_lease_receipt_valid_through_epoch_millis` ON `student_trusted_review_lease_receipt` (`valid_through_epoch_millis`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_attempt_receipt_lease_receipt_id` ON `student_trusted_review_attempt_receipt` (`lease_receipt_id`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_attempt_receipt_session_id_presentation_id_attempt_ordinal` ON `student_trusted_review_attempt_receipt` (`session_id`, `presentation_id`, `attempt_ordinal`)",
        "CREATE INDEX `index_student_trusted_review_attempt_receipt_session_id_plan_id_learner_id` ON `student_trusted_review_attempt_receipt` (`session_id`, `plan_id`, `learner_id`)",
        "CREATE INDEX `index_student_trusted_review_attempt_receipt_queue_item_id_plan_id_learner_id` ON `student_trusted_review_attempt_receipt` (`queue_item_id`, `plan_id`, `learner_id`)",
        "CREATE INDEX `index_student_trusted_review_attempt_receipt_learner_id_submitted_at_epoch_millis` ON `student_trusted_review_attempt_receipt` (`learner_id`, `submitted_at_epoch_millis`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_attempt_receipt_attempt_canonical_fingerprint` ON `student_trusted_review_attempt_receipt` (`attempt_canonical_fingerprint`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_attempt_receipt_submission_idempotency_key` ON `student_trusted_review_attempt_receipt` (`submission_idempotency_key`)",
        "CREATE INDEX `index_student_trusted_review_assistance_receipt_lease_receipt_id` ON `student_trusted_review_assistance_receipt` (`lease_receipt_id`)",
        "CREATE INDEX `index_student_trusted_review_assistance_receipt_session_id_plan_id_learner_id` ON `student_trusted_review_assistance_receipt` (`session_id`, `plan_id`, `learner_id`)",
        "CREATE INDEX `index_student_trusted_review_assistance_receipt_queue_item_id_plan_id_learner_id` ON `student_trusted_review_assistance_receipt` (`queue_item_id`, `plan_id`, `learner_id`)",
        "CREATE INDEX `index_student_trusted_review_assistance_receipt_session_id_presentation_id_kind_occurred_at_epoch_millis` ON `student_trusted_review_assistance_receipt` (`session_id`, `presentation_id`, `kind`, `occurred_at_epoch_millis`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_assistance_receipt_assistance_canonical_fingerprint` ON `student_trusted_review_assistance_receipt` (`assistance_canonical_fingerprint`)",
    )

private fun SQLiteConnection.countRows(tableName: String): Long =
    prepare("SELECT COUNT(*) FROM `$tableName`").use { statement ->
        check(statement.step()) { "Expected one count row for $tableName" }
        statement.getLong(0)
    }
