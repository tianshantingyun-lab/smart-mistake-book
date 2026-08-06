package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val LEARNER_MASTERY_MIGRATION_6_7 =
    object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `mastery_cutover_fence` (
                    `singleton_key` TEXT NOT NULL,
                    `cutover_generation` INTEGER NOT NULL,
                    `student_import_evidence_fingerprint` TEXT NOT NULL,
                    `mastery_import_evidence_fingerprint` TEXT NOT NULL,
                    `cutover_intent_fingerprint` TEXT NOT NULL,
                    `fence_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(`singleton_key`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `mastery_cutover_completion_receipt` (
                    `singleton_key` TEXT NOT NULL,
                    `cutover_generation` INTEGER NOT NULL,
                    `cutover_intent_fingerprint` TEXT NOT NULL,
                    `authority_fence_fingerprint` TEXT NOT NULL,
                    `learner_id` TEXT NOT NULL,
                    `source_generation` TEXT NOT NULL,
                    `migration_ledger_canonical_digest` TEXT NOT NULL,
                    `ledger_binding_fingerprint` TEXT NOT NULL,
                    `receipt_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(`singleton_key`),
                    FOREIGN KEY(`singleton_key`)
                        REFERENCES `mastery_cutover_fence`(`singleton_key`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `mastery_legacy_fact_migration_destination_record` (
                    `learner_id` TEXT NOT NULL,
                    `source_generation` TEXT NOT NULL,
                    `batch_sequence` INTEGER NOT NULL,
                    `observation_ordinal` INTEGER NOT NULL,
                    `source_fact_id` TEXT NOT NULL,
                    `source_fact_canonical_fingerprint` TEXT NOT NULL,
                    `candidate_id` TEXT NOT NULL,
                    `candidate_canonical_fingerprint` TEXT NOT NULL,
                    `destination_record_canonical_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(
                        `learner_id`,
                        `source_generation`,
                        `batch_sequence`,
                        `observation_ordinal`
                    ),
                    FOREIGN KEY(
                        `learner_id`,
                        `source_generation`,
                        `batch_sequence`
                    ) REFERENCES `mastery_legacy_fact_migration_checkpoint`(
                        `learner_id`,
                        `source_generation`,
                        `batch_sequence`
                    ) ON UPDATE NO ACTION ON DELETE RESTRICT
                        DEFERRABLE INITIALLY DEFERRED,
                    FOREIGN KEY(`source_fact_id`)
                        REFERENCES `mastery_source_fact`(`source_fact_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`candidate_id`)
                        REFERENCES `mastery_observation_candidate`(`candidate_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_mastery_legacy_fact_migration_destination_record_learner_id_source_generation_source_fact_id`
                ON `mastery_legacy_fact_migration_destination_record`
                    (`learner_id`, `source_generation`, `source_fact_id`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_mastery_legacy_fact_migration_destination_record_learner_id_source_generation_candidate_id`
                ON `mastery_legacy_fact_migration_destination_record`
                    (`learner_id`, `source_generation`, `candidate_id`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_mastery_legacy_fact_migration_destination_record_source_fact_id`
                ON `mastery_legacy_fact_migration_destination_record` (`source_fact_id`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_mastery_legacy_fact_migration_destination_record_candidate_id`
                ON `mastery_legacy_fact_migration_destination_record` (`candidate_id`)
                """.trimIndent(),
            )
            installLearnerMasteryImmutableLedgerGuards(
                connection = connection,
                tableNames =
                    LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES -
                    setOf(
                        LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
                        LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
                        LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
                        LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
                    ),
                includeCutoverInsertGuards = true,
                includeRawSnapshotGuards = false,
            )
        }
    }
