package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the append-only model-submission attempt ledger.
 *
 * Historical candidates cannot prove that a model crossed the current host boundary, so this
 * migration intentionally creates an empty table and never manufactures attempt receipts.
 */
internal val LEARNER_MASTERY_MIGRATION_8_9 =
    object : Migration(8, 9) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS
                    `$LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE` (
                        `receipt_fingerprint` TEXT NOT NULL,
                        `request_generation_fingerprint` TEXT NOT NULL,
                        `primary_attempt_key` TEXT,
                        `learner_id` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `source_fact_id` TEXT NOT NULL,
                        `model_version` TEXT NOT NULL,
                        `request_version` TEXT NOT NULL,
                        `mode_version` TEXT NOT NULL,
                        `proposal_fingerprint` TEXT NOT NULL,
                        `terminal_reason` TEXT NOT NULL,
                        `candidate_id` TEXT,
                        `admission_receipt_fingerprint` TEXT,
                        `received_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`receipt_fingerprint`)
                    )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_mastery_model_submission_attempt_receipt_request_generation_fingerprint_proposal_fingerprint`
                ON `$LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE`(
                    `request_generation_fingerprint`,
                    `proposal_fingerprint`
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_mastery_model_submission_attempt_receipt_primary_attempt_key`
                ON `$LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE`(
                    `primary_attempt_key`
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_mastery_model_submission_attempt_receipt_learner_id_subject_received_at_epoch_millis`
                ON `$LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE`(
                    `learner_id`,
                    `subject`,
                    `received_at_epoch_millis`
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_mastery_model_submission_attempt_receipt_source_fact_id_received_at_epoch_millis`
                ON `$LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE`(
                    `source_fact_id`,
                    `received_at_epoch_millis`
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_mastery_model_submission_attempt_receipt_candidate_id`
                ON `$LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE`(
                    `candidate_id`
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_mastery_model_submission_attempt_receipt_admission_receipt_fingerprint`
                ON `$LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE`(
                    `admission_receipt_fingerprint`
                )
                """.trimIndent(),
            )
            installLearnerMasteryImmutableLedgerGuards(
                connection = connection,
                tableNames =
                    LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES -
                        LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
                includeCutoverInsertGuards = true,
                includeRawSnapshotGuards = true,
            )
        }
    }
