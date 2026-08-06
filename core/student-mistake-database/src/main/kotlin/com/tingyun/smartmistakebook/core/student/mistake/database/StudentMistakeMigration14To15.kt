package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the fail-closed trusted-review presentation fence.
 *
 * Existing presentations are deliberately not backfilled: the old schema cannot prove that an
 * explanation was never shown, so absence of a v15 fence makes them ineligible for a strong
 * answer lease. New presentations create their PENDING fence in the review-session transaction.
 */
internal val STUDENT_MISTAKE_MIGRATION_14_15 =
    object : Migration(14, 15) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(STUDENT_TRUSTED_REVIEW_FENCE_V15_TABLE_SQL)
            STUDENT_TRUSTED_REVIEW_FENCE_V15_INDEX_SQL.forEach(connection::execSQL)
            check(connection.countTrustedReviewFences() == 0L) {
                "Trusted review v15 migration must not authorize legacy presentations"
            }
        }
    }

private val STUDENT_TRUSTED_REVIEW_FENCE_V15_TABLE_SQL =
    """
    CREATE TABLE `student_trusted_review_presentation_fence` (
        `fence_id` TEXT NOT NULL,
        `fence_canonical_fingerprint` TEXT NOT NULL,
        `learner_id` TEXT NOT NULL,
        `plan_id` TEXT NOT NULL,
        `session_id` TEXT NOT NULL,
        `queue_item_id` TEXT NOT NULL,
        `presentation_id` TEXT NOT NULL,
        `problem_id` TEXT NOT NULL,
        `basis_revision_id` TEXT NOT NULL,
        `practice_unit_id` TEXT NOT NULL,
        `error_book_entry_id` TEXT NOT NULL,
        `status` TEXT NOT NULL,
        `bound_lease_receipt_id` TEXT,
        `bound_lease_canonical_fingerprint` TEXT,
        `eligible_attempt_receipt_id` TEXT,
        `eligible_at_epoch_millis` INTEGER,
        `created_at_epoch_millis` INTEGER NOT NULL,
        PRIMARY KEY(`fence_id`),
        FOREIGN KEY(`session_id`, `plan_id`, `learner_id`)
            REFERENCES `student_review_session`(`session_id`, `plan_id`, `learner_id`)
            ON UPDATE NO ACTION ON DELETE NO ACTION,
        FOREIGN KEY(`queue_item_id`, `plan_id`, `learner_id`)
            REFERENCES `student_review_queue_item`(`queue_item_id`, `plan_id`, `learner_id`)
            ON UPDATE NO ACTION ON DELETE NO ACTION,
        FOREIGN KEY(`problem_id`)
            REFERENCES `student_problem_document`(`problem_id`)
            ON UPDATE NO ACTION ON DELETE NO ACTION,
        FOREIGN KEY(`basis_revision_id`)
            REFERENCES `student_problem_revision`(`revision_id`)
            ON UPDATE NO ACTION ON DELETE NO ACTION,
        FOREIGN KEY(`practice_unit_id`)
            REFERENCES `student_practice_unit`(`practice_unit_id`)
            ON UPDATE NO ACTION ON DELETE NO ACTION
    )
    """.trimIndent()

private val STUDENT_TRUSTED_REVIEW_FENCE_V15_INDEX_SQL =
    listOf(
        "CREATE UNIQUE INDEX `index_student_trusted_review_presentation_fence_fence_canonical_fingerprint` ON `student_trusted_review_presentation_fence` (`fence_canonical_fingerprint`)",
        "CREATE INDEX `index_student_trusted_review_presentation_fence_session_id_plan_id_learner_id` ON `student_trusted_review_presentation_fence` (`session_id`, `plan_id`, `learner_id`)",
        "CREATE INDEX `index_student_trusted_review_presentation_fence_queue_item_id_plan_id_learner_id` ON `student_trusted_review_presentation_fence` (`queue_item_id`, `plan_id`, `learner_id`)",
        "CREATE INDEX `index_student_trusted_review_presentation_fence_problem_id` ON `student_trusted_review_presentation_fence` (`problem_id`)",
        "CREATE INDEX `index_student_trusted_review_presentation_fence_basis_revision_id` ON `student_trusted_review_presentation_fence` (`basis_revision_id`)",
        "CREATE INDEX `index_student_trusted_review_presentation_fence_practice_unit_id` ON `student_trusted_review_presentation_fence` (`practice_unit_id`)",
        "CREATE INDEX `index_student_trusted_review_presentation_fence_error_book_entry_id` ON `student_trusted_review_presentation_fence` (`error_book_entry_id`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_presentation_fence_learner_id_session_id_presentation_id` ON `student_trusted_review_presentation_fence` (`learner_id`, `session_id`, `presentation_id`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_presentation_fence_bound_lease_receipt_id` ON `student_trusted_review_presentation_fence` (`bound_lease_receipt_id`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_presentation_fence_bound_lease_canonical_fingerprint` ON `student_trusted_review_presentation_fence` (`bound_lease_canonical_fingerprint`)",
        "CREATE UNIQUE INDEX `index_student_trusted_review_presentation_fence_eligible_attempt_receipt_id` ON `student_trusted_review_presentation_fence` (`eligible_attempt_receipt_id`)",
    )

private fun SQLiteConnection.countTrustedReviewFences(): Long =
    prepare("SELECT COUNT(*) FROM student_trusted_review_presentation_fence").use { statement ->
        check(statement.step()) { "Expected one trusted review fence count row" }
        statement.getLong(0)
    }
