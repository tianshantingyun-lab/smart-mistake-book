package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val TUTOR_LEARNING_EVIDENCE_SESSION_MIGRATION_43_44 =
    object : Migration(43, 44) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `tutor_learning_evidence_finalization_receipt` (
                    `evidence_request_id` TEXT NOT NULL,
                    `learner_id` TEXT NOT NULL,
                    `conversation_id` TEXT NOT NULL,
                    `conversation_generation` INTEGER NOT NULL,
                    `conversation_state_version` INTEGER NOT NULL,
                    `turn_receipt_id` TEXT NOT NULL,
                    `turn_ordinal` INTEGER NOT NULL,
                    `subject` TEXT NOT NULL,
                    `session_anchor_id` TEXT NOT NULL,
                    `evidence_kind` TEXT NOT NULL,
                    `request_version` INTEGER NOT NULL,
                    `mode_version` INTEGER NOT NULL,
                    `idempotency_key` TEXT NOT NULL,
                    `candidate_fingerprint` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `mastery_receipt_id` TEXT,
                    `mastery_receipt_fingerprint` TEXT,
                    `state_version` INTEGER NOT NULL,
                    `intent_created_at_epoch_millis` INTEGER NOT NULL,
                    `acknowledged_at_epoch_millis` INTEGER,
                    PRIMARY KEY(`evidence_request_id`),
                    FOREIGN KEY(`evidence_request_id`)
                        REFERENCES `tutor_evidence_request`(`evidence_request_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                `index_tutor_learning_evidence_finalization_receipt_learner_id_conversation_id_conversation_generation_idempotency_key`
                ON `tutor_learning_evidence_finalization_receipt`
                (`learner_id`, `conversation_id`, `conversation_generation`, `idempotency_key`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_tutor_learning_evidence_finalization_receipt_turn_receipt_id`
                ON `tutor_learning_evidence_finalization_receipt` (`turn_receipt_id`)
                """.trimIndent(),
            )
        }
    }

