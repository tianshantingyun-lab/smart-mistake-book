package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val TUTOR_LEARNING_MEMORY_MIGRATION_33_34 = object : Migration(33, 34) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_conversation` (
                `conversation_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `generation` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `next_turn_ordinal` INTEGER NOT NULL,
                `state_version` INTEGER NOT NULL,
                `create_idempotency_key` TEXT NOT NULL,
                `create_payload_fingerprint` TEXT NOT NULL,
                `archive_idempotency_key` TEXT,
                `archive_payload_fingerprint` TEXT,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                `archived_at_epoch_millis` INTEGER,
                PRIMARY KEY(`conversation_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_tutor_conversation_learner_id_status_updated_at_epoch_millis`
            ON `tutor_conversation` (`learner_id`, `status`, `updated_at_epoch_millis`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
                `index_tutor_conversation_learner_id_create_idempotency_key`
            ON `tutor_conversation` (`learner_id`, `create_idempotency_key`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
                `index_tutor_conversation_conversation_id_learner_id_generation`
            ON `tutor_conversation` (`conversation_id`, `learner_id`, `generation`)
            """.trimIndent(),
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_turn_receipt` (
                `turn_receipt_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `conversation_generation` INTEGER NOT NULL,
                `conversation_state_version` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `client_turn_id` TEXT NOT NULL,
                `payload_fingerprint` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `problem_anchor_id` TEXT,
                `request_version` INTEGER NOT NULL,
                `explanation_mode` TEXT NOT NULL,
                `mode_version` INTEGER NOT NULL,
                `directive_fingerprint` TEXT NOT NULL,
                `student_message_fingerprint` TEXT NOT NULL,
                `student_message_summary` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                `allocated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`turn_receipt_id`),
                FOREIGN KEY(
                    `conversation_id`, `learner_id`, `conversation_generation`
                ) REFERENCES `tutor_conversation`(
                    `conversation_id`, `learner_id`, `generation`
                ) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_tutor_turn_receipt_conversation_id_learner_id_conversation_generation`
            ON `tutor_turn_receipt` (`conversation_id`, `learner_id`, `conversation_generation`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
                `index_tutor_turn_receipt_conversation_id_conversation_generation_turn_ordinal`
            ON `tutor_turn_receipt` (`conversation_id`, `conversation_generation`, `turn_ordinal`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
                `index_tutor_turn_receipt_conversation_id_conversation_generation_client_turn_id`
            ON `tutor_turn_receipt` (`conversation_id`, `conversation_generation`, `client_turn_id`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_tutor_turn_receipt_learner_id_subject_occurred_at_epoch_millis`
            ON `tutor_turn_receipt` (`learner_id`, `subject`, `occurred_at_epoch_millis`)
            """.trimIndent(),
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_evidence_request` (
                `evidence_request_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `conversation_generation` INTEGER NOT NULL,
                `conversation_state_version` INTEGER NOT NULL,
                `turn_receipt_id` TEXT NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `subject` TEXT NOT NULL,
                `problem_anchor_id` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `request_version` INTEGER NOT NULL,
                `explanation_mode` TEXT NOT NULL,
                `mode_version` INTEGER NOT NULL,
                `directive_fingerprint` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `prepare_idempotency_key` TEXT NOT NULL,
                `prepare_payload_fingerprint` TEXT NOT NULL,
                `terminal_idempotency_key` TEXT,
                `terminal_payload_fingerprint` TEXT,
                `terminal_source_fact_id` TEXT,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `resolved_at_epoch_millis` INTEGER,
                PRIMARY KEY(`evidence_request_id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `tutor_conversation`(`conversation_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`turn_receipt_id`) REFERENCES `tutor_turn_receipt`(`turn_receipt_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_evidence_request_conversation_id` ON `tutor_evidence_request` (`conversation_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_evidence_request_turn_receipt_id` ON `tutor_evidence_request` (`turn_receipt_id`)",
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
                `index_tutor_evidence_request_conversation_id_conversation_generation_prepare_idempotency_key`
            ON `tutor_evidence_request` (
                `conversation_id`, `conversation_generation`, `prepare_idempotency_key`
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_tutor_evidence_request_learner_id_status_created_at_epoch_millis`
            ON `tutor_evidence_request` (`learner_id`, `status`, `created_at_epoch_millis`)
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_evidence_request_problem_anchor_id` ON `tutor_evidence_request` (`problem_anchor_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learning_problem_anchor` (
                `anchor_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `question_fingerprint` TEXT NOT NULL,
                `revision_fingerprint` TEXT NOT NULL,
                `fingerprint_version` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`anchor_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
                `index_learning_problem_anchor_learner_id_subject_question_fingerprint_revision_fingerprint_fingerprint_version`
            ON `learning_problem_anchor` (
                `learner_id`, `subject`, `question_fingerprint`, `revision_fingerprint`,
                `fingerprint_version`
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
                `index_learning_problem_anchor_anchor_id_learner_id_subject`
            ON `learning_problem_anchor` (`anchor_id`, `learner_id`, `subject`)
            """.trimIndent(),
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learning_observation_source_fact` (
                `source_fact_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `fact_kind` TEXT NOT NULL,
                `anchor_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `conversation_id` TEXT,
                `conversation_generation` INTEGER,
                `turn_receipt_id` TEXT,
                `evidence_request_id` TEXT,
                `response_fingerprint` TEXT NOT NULL,
                `response_summary` TEXT NOT NULL,
                `payload_fingerprint` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                `source_version` TEXT NOT NULL,
                PRIMARY KEY(`source_fact_id`),
                FOREIGN KEY(
                    `anchor_id`, `learner_id`, `subject`
                ) REFERENCES `learning_problem_anchor`(
                    `anchor_id`, `learner_id`, `subject`
                ) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`conversation_id`) REFERENCES `tutor_conversation`(`conversation_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`turn_receipt_id`) REFERENCES `tutor_turn_receipt`(`turn_receipt_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`evidence_request_id`) REFERENCES `tutor_evidence_request`(`evidence_request_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_learning_observation_source_fact_anchor_id_learner_id_subject`
            ON `learning_observation_source_fact` (`anchor_id`, `learner_id`, `subject`)
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_observation_source_fact_conversation_id` ON `learning_observation_source_fact` (`conversation_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_observation_source_fact_turn_receipt_id` ON `learning_observation_source_fact` (`turn_receipt_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_observation_source_fact_evidence_request_id` ON `learning_observation_source_fact` (`evidence_request_id`)",
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_learning_observation_source_fact_learner_id_subject_occurred_at_epoch_millis`
            ON `learning_observation_source_fact` (`learner_id`, `subject`, `occurred_at_epoch_millis`)
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_observation_source_fact_payload_fingerprint` ON `learning_observation_source_fact` (`payload_fingerprint`)",
        )
    }
}
