package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val STUDENT_MISTAKE_MIGRATION_10_11 =
    object : Migration(10, 11) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `student_problem_identity_receipt` (
                    `receipt_id` TEXT NOT NULL,
                    `receipt_kind` TEXT NOT NULL,
                    `receipt_canonical_fingerprint` TEXT NOT NULL,
                    `issuer_key_id` TEXT NOT NULL,
                    `issuer_version` TEXT NOT NULL,
                    `learner_id` TEXT NOT NULL,
                    `subject` TEXT NOT NULL,
                    `source_kind` TEXT NOT NULL,
                    `source_intent_id` TEXT NOT NULL,
                    `idempotency_key` TEXT NOT NULL,
                    `source_canonical_fingerprint` TEXT NOT NULL,
                    `locator_namespace` TEXT,
                    `locator_version` TEXT,
                    `item_locator_canonical_fingerprint` TEXT,
                    `problem_id` TEXT NOT NULL,
                    `practice_unit_id` TEXT NOT NULL,
                    `revision_id` TEXT NOT NULL,
                    `revision_number` INTEGER NOT NULL,
                    `document_canonical_fingerprint` TEXT NOT NULL,
                    `target_canonical_fingerprint` TEXT NOT NULL,
                    `asset_manifest_canonical_fingerprint` TEXT NOT NULL,
                    `selected_region_canonical_fingerprint` TEXT NOT NULL,
                    `candidate_canonical_fingerprint` TEXT NOT NULL,
                    `review_authority_kind` TEXT,
                    `existing_identity_namespace` TEXT,
                    `existing_identity_version` TEXT,
                    `existing_identity_stable_key` TEXT,
                    `existing_identity_canonical_fingerprint` TEXT,
                    `review_case_id` TEXT,
                    `review_revision` INTEGER,
                    `review_decision_canonical_fingerprint` TEXT,
                    `issued_at_epoch_millis` INTEGER NOT NULL,
                    `expires_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`receipt_id`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_student_problem_identity_receipt_receipt_canonical_fingerprint`
                ON `student_problem_identity_receipt` (`receipt_canonical_fingerprint`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_student_problem_identity_receipt_learner_id_receipt_kind_expires_at_epoch_millis`
                ON `student_problem_identity_receipt`
                    (`learner_id`, `receipt_kind`, `expires_at_epoch_millis`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_student_problem_identity_receipt_learner_id_source_intent_id_idempotency_key`
                ON `student_problem_identity_receipt`
                    (`learner_id`, `source_intent_id`, `idempotency_key`)
                """.trimIndent(),
            )
            createStudentProblemIdentityReceiptImmutabilityTriggers(connection)
        }
    }

internal fun createStudentProblemIdentityReceiptImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        STUDENT_PROBLEM_IDENTITY_RECEIPT_UPDATE_TRIGGER_SQL,
    )
    connection.execSQL(
        STUDENT_PROBLEM_IDENTITY_RECEIPT_DELETE_TRIGGER_SQL,
    )
}

internal const val STUDENT_PROBLEM_IDENTITY_RECEIPT_UPDATE_TRIGGER_NAME =
    "immutable_student_problem_identity_receipt_update"
internal const val STUDENT_PROBLEM_IDENTITY_RECEIPT_DELETE_TRIGGER_NAME =
    "immutable_student_problem_identity_receipt_delete"

internal val STUDENT_PROBLEM_IDENTITY_RECEIPT_UPDATE_TRIGGER_SQL =
    """
    CREATE TRIGGER IF NOT EXISTS $STUDENT_PROBLEM_IDENTITY_RECEIPT_UPDATE_TRIGGER_NAME
    BEFORE UPDATE ON `student_problem_identity_receipt`
    BEGIN
        SELECT RAISE(ABORT, 'immutable student problem identity receipt');
    END
    """.trimIndent()

internal val STUDENT_PROBLEM_IDENTITY_RECEIPT_DELETE_TRIGGER_SQL =
    """
    CREATE TRIGGER IF NOT EXISTS $STUDENT_PROBLEM_IDENTITY_RECEIPT_DELETE_TRIGGER_NAME
    BEFORE DELETE ON `student_problem_identity_receipt`
    BEGIN
        SELECT RAISE(ABORT, 'immutable student problem identity receipt');
    END
    """.trimIndent()

internal val STUDENT_PROBLEM_IDENTITY_RECEIPT_IMMUTABILITY_TRIGGER_SQL =
    mapOf(
        STUDENT_PROBLEM_IDENTITY_RECEIPT_UPDATE_TRIGGER_NAME to
            STUDENT_PROBLEM_IDENTITY_RECEIPT_UPDATE_TRIGGER_SQL,
        STUDENT_PROBLEM_IDENTITY_RECEIPT_DELETE_TRIGGER_NAME to
            STUDENT_PROBLEM_IDENTITY_RECEIPT_DELETE_TRIGGER_SQL,
    )
