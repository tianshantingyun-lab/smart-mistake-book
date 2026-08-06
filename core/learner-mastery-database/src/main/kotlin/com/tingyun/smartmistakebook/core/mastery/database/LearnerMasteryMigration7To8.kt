package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the lossless terminal legacy snapshot ledger.
 *
 * Existing v7 semantic migration rows are deliberately left untouched. They are not sufficient
 * evidence for the current mastery model and are never treated as a completed v8 import.
 */
internal val LEARNER_MASTERY_MIGRATION_7_8 =
    object : Migration(7, 8) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `mastery_legacy_observation_snapshot_page` (
                    `learner_id` TEXT NOT NULL,
                    `source_generation` TEXT NOT NULL,
                    `batch_sequence` INTEGER NOT NULL,
                    `source_page_canonical_fingerprint` TEXT NOT NULL,
                    `after_occurred_at_epoch_millis` INTEGER,
                    `after_source_fact_id` TEXT,
                    `terminal_occurred_at_epoch_millis` INTEGER,
                    `terminal_source_fact_id` TEXT,
                    `snapshot_count` INTEGER NOT NULL,
                    `final_batch` INTEGER NOT NULL,
                    `page_receipt_canonical_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(`learner_id`, `source_generation`, `batch_sequence`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_mastery_legacy_observation_snapshot_page_learner_id_source_generation_source_page_canonical_fingerprint`
                ON `mastery_legacy_observation_snapshot_page`(
                    `learner_id`,
                    `source_generation`,
                    `source_page_canonical_fingerprint`
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `mastery_legacy_observation_snapshot` (
                    `learner_id` TEXT NOT NULL,
                    `source_generation` TEXT NOT NULL,
                    `batch_sequence` INTEGER NOT NULL,
                    `snapshot_ordinal` INTEGER NOT NULL,
                    `source_fact_id` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `fact_kind` TEXT NOT NULL,
                    `anchor_id` TEXT NOT NULL,
                    `subject` TEXT NOT NULL,
                    `conversation_generation` INTEGER,
                    `conversation_id` TEXT,
                    `turn_receipt_id` TEXT,
                    `evidence_request_id` TEXT,
                    `response_fingerprint` TEXT NOT NULL,
                    `response_summary` TEXT NOT NULL,
                    `occurred_at_epoch_millis` INTEGER NOT NULL,
                    `source_version` TEXT NOT NULL,
                    `source_payload_canonical_fingerprint` TEXT NOT NULL,
                    `proof_present` INTEGER NOT NULL,
                    `source_proof_canonical_fingerprint` TEXT,
                    `source_reference_id` TEXT,
                    `target_kind` TEXT,
                    `target_database` TEXT,
                    `target_id` TEXT,
                    `target_version` TEXT,
                    `target_canonical_fingerprint` TEXT,
                    `attested_at_epoch_millis` INTEGER,
                    `source_record_canonical_fingerprint` TEXT NOT NULL,
                    `snapshot_canonical_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(
                        `learner_id`,
                        `source_generation`,
                        `batch_sequence`,
                        `snapshot_ordinal`
                    ),
                    FOREIGN KEY(
                        `learner_id`,
                        `source_generation`,
                        `batch_sequence`
                    ) REFERENCES `mastery_legacy_observation_snapshot_page`(
                        `learner_id`,
                        `source_generation`,
                        `batch_sequence`
                    ) ON UPDATE NO ACTION ON DELETE RESTRICT
                        DEFERRABLE INITIALLY DEFERRED
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_mastery_legacy_observation_snapshot_learner_id_source_generation_source_fact_id`
                ON `mastery_legacy_observation_snapshot`(
                    `learner_id`,
                    `source_generation`,
                    `source_fact_id`
                )
                """.trimIndent(),
            )
            installLearnerMasteryImmutableLedgerGuards(
                connection = connection,
                tableNames =
                    LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES -
                    setOf(
                        LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
                        LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
                    ),
                includeCutoverInsertGuards = true,
                includeRawSnapshotGuards = true,
            )
        }
    }
