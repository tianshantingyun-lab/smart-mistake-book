package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds only empty current-session coordination tables.
 *
 * No v45 legacy interaction row is copied: those rows do not contain a v44 conversation grant,
 * so treating them as current would manufacture authority after the cutover.
 */
internal val CURRENT_TUTOR_INTERACTION_MIGRATION_45_46 = object : Migration(45, 46) {
    override suspend fun migrate(connection: SQLiteConnection) {
        val alreadyMigrated = connection.tableExists("tutor_current_interaction_scope")
        if (!alreadyMigrated) {
            LegacyBusinessWriteBarrierSchema.requireV45ApplicationTableInventory(connection)
        }
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_current_interaction_scope` (
                `scope_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `conversation_generation` INTEGER NOT NULL,
                `conversation_state_version` INTEGER NOT NULL,
                `authority_conversation_id` TEXT NOT NULL,
                `authority_conversation_generation` INTEGER NOT NULL,
                `authority_conversation_state_version` INTEGER NOT NULL,
                `authority_turn_receipt_id` TEXT NOT NULL,
                `authority_turn_ordinal` INTEGER NOT NULL,
                `authority_request_version` INTEGER NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `question_document_snapshot` TEXT NOT NULL,
                `question_fingerprint` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `problem_anchor_id` TEXT NOT NULL,
                `explanation_mode` TEXT NOT NULL,
                `mode_version` INTEGER NOT NULL,
                `turn_reference_id` TEXT NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `turn_generation` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `attempt_ordinal` INTEGER NOT NULL,
                `hint_count` INTEGER NOT NULL,
                `answer_was_revealed` INTEGER NOT NULL,
                `request_version` INTEGER NOT NULL,
                `learning_write_permission_version` INTEGER NOT NULL,
                `presentation_fingerprint` TEXT NOT NULL,
                `problem_fingerprint` TEXT NOT NULL,
                `problem_family_fingerprint` TEXT NOT NULL,
                `attribution_policy_version` TEXT NOT NULL,
                `response_policy_version` TEXT NOT NULL,
                `rubric_canonical_fingerprint` TEXT NOT NULL,
                `knowledge_authority_fingerprint` TEXT NOT NULL,
                `evaluator` TEXT NOT NULL,
                `evaluator_policy_fingerprint` TEXT NOT NULL,
                `activation_fingerprint` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`scope_id`),
                FOREIGN KEY(
                    `authority_conversation_id`,
                    `learner_id`,
                    `authority_conversation_generation`
                ) REFERENCES `tutor_conversation`(
                    `conversation_id`,
                    `learner_id`,
                    `generation`
                ) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`authority_turn_receipt_id`)
                    REFERENCES `tutor_turn_receipt`(`turn_receipt_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_tutor_current_interaction_scope_authority_conversation_id_learner_id_authority_conversation_generation`
            ON `tutor_current_interaction_scope` (
                `authority_conversation_id`,
                `learner_id`,
                `authority_conversation_generation`
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_tutor_current_interaction_scope_authority_turn_receipt_id`
            ON `tutor_current_interaction_scope` (`authority_turn_receipt_id`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_tutor_current_interaction_scope_learner_id_conversation_id_conversation_generation_question_document_id_question_revision_number`
            ON `tutor_current_interaction_scope` (
                `learner_id`,
                `conversation_id`,
                `conversation_generation`,
                `question_document_id`,
                `question_revision_number`
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_tutor_current_interaction_scope_activation_fingerprint`
            ON `tutor_current_interaction_scope` (`activation_fingerprint`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_current_interaction_head` (
                `learner_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `current_scope_id` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `state_fingerprint` TEXT NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`learner_id`, `conversation_id`),
                FOREIGN KEY(`current_scope_id`)
                    REFERENCES `tutor_current_interaction_scope`(`scope_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_tutor_current_interaction_head_current_scope_id`
            ON `tutor_current_interaction_head` (`current_scope_id`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_current_interaction_event` (
                `event_id` TEXT NOT NULL,
                `scope_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `conversation_generation` INTEGER NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `mode_version` INTEGER NOT NULL,
                `turn_generation` INTEGER NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                `committed_state_fingerprint` TEXT NOT NULL,
                `event_kind` TEXT NOT NULL,
                `authorization_purpose` TEXT NOT NULL,
                `authorization_request_id` TEXT,
                `idempotency_key` TEXT NOT NULL,
                `request_version` INTEGER NOT NULL,
                `payload_fingerprint` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                `recorded_at_epoch_millis` INTEGER NOT NULL,
                `diagnostic_stem_markdown` TEXT,
                `selected_choice_id` TEXT,
                `selected_choice_markdown` TEXT,
                `selection_was_correct` INTEGER,
                `feedback_markdown` TEXT,
                `evidence_request_id` TEXT,
                `requested_move` TEXT,
                `solution_revealed` INTEGER NOT NULL,
                `surface_kind` TEXT,
                `model_task_request_id` TEXT,
                `response_ordinal` INTEGER,
                `scene_source_kind` TEXT,
                `scene_task_request_id` TEXT,
                `scene_id` TEXT,
                `scene_fingerprint` TEXT,
                `hit_proof_id` TEXT,
                `panel_id` TEXT,
                `frame_fingerprint` TEXT,
                `step_index` INTEGER,
                `selected_target_id` TEXT,
                `target_revision_ref` TEXT,
                `target_practice_ref` TEXT,
                `source_kind` TEXT,
                PRIMARY KEY(`learner_id`, `event_id`),
                FOREIGN KEY(`scope_id`)
                    REFERENCES `tutor_current_interaction_scope`(`scope_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_tutor_current_interaction_event_scope_id_event_sequence`
            ON `tutor_current_interaction_event` (`scope_id`, `event_sequence`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_tutor_current_interaction_event_learner_id_idempotency_key`
            ON `tutor_current_interaction_event` (`learner_id`, `idempotency_key`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_tutor_current_interaction_event_learner_id_conversation_id_recorded_at_epoch_millis`
            ON `tutor_current_interaction_event` (
                `learner_id`,
                `conversation_id`,
                `recorded_at_epoch_millis`
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_tutor_current_interaction_event_authorization_request_id`
            ON `tutor_current_interaction_event` (`authorization_request_id`)
            """.trimIndent(),
        )
        if (!alreadyMigrated) {
            LegacyBusinessWriteBarrierSchema.requireV46ApplicationTableInventory(connection)
        }
    }
}
