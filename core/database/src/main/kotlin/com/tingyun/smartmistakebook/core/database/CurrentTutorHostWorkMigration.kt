package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Adds an empty, authority-reference-only recovery journal for current Tutor activation. */
internal val CURRENT_TUTOR_HOST_WORK_MIGRATION_46_47 = object : Migration(46, 47) {
    override suspend fun migrate(connection: SQLiteConnection) {
        val alreadyMigrated = connection.tableExists("tutor_current_host_work")
        if (!alreadyMigrated) {
            LegacyBusinessWriteBarrierSchema.requireV46ApplicationTableInventory(connection)
        }
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_current_host_work` (
                `work_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `subject` TEXT NOT NULL,
                `authority_conversation_id` TEXT NOT NULL,
                `authority_conversation_generation` INTEGER NOT NULL,
                `authority_conversation_state_version` INTEGER NOT NULL,
                `authority_turn_receipt_id` TEXT NOT NULL,
                `authority_turn_ordinal` INTEGER NOT NULL,
                `authority_request_version` INTEGER NOT NULL,
                `authority_directive_fingerprint` TEXT NOT NULL,
                `model_task_request_id` TEXT NOT NULL,
                `model_task_request_fingerprint` TEXT NOT NULL,
                `problem_anchor_id` TEXT NOT NULL,
                `explanation_mode` TEXT NOT NULL,
                `mode_version` INTEGER NOT NULL,
                `learning_writes_allowed` INTEGER NOT NULL,
                `learning_write_permission_version` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `attempt_ordinal` INTEGER NOT NULL,
                `request_version` INTEGER NOT NULL,
                `evidence_request_id` TEXT,
                `pending_interaction_kind` TEXT,
                `target_scope_id` TEXT NOT NULL,
                `target_activation_fingerprint` TEXT NOT NULL,
                `constrained_tutor_content_fingerprint` TEXT NOT NULL,
                `active_scope_id` TEXT,
                `presentation_token` TEXT NOT NULL,
                `payload_fingerprint` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `revocation_reason` TEXT,
                `state_version` INTEGER NOT NULL,
                `state_fingerprint` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`learner_id`, `session_id`),
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
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`active_scope_id`)
                    REFERENCES `tutor_current_interaction_scope`(`scope_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_current_host_work_work_id`
            ON `tutor_current_host_work` (`work_id`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_tutor_current_host_work_learner_id_model_task_request_id`
            ON `tutor_current_host_work` (`learner_id`, `model_task_request_id`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_tutor_current_host_work_authority_conversation_id_learner_id_authority_conversation_generation`
            ON `tutor_current_host_work` (
                `authority_conversation_id`,
                `learner_id`,
                `authority_conversation_generation`
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_tutor_current_host_work_authority_turn_receipt_id`
            ON `tutor_current_host_work` (`authority_turn_receipt_id`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_tutor_current_host_work_active_scope_id`
            ON `tutor_current_host_work` (`active_scope_id`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_tutor_current_host_work_status_updated_at_epoch_millis`
            ON `tutor_current_host_work` (`status`, `updated_at_epoch_millis`)
            """.trimIndent(),
        )
        if (!alreadyMigrated) {
            LegacyBusinessWriteBarrierSchema.requireV47ApplicationTableInventory(connection)
        }
    }
}

/** Persists the user-owned visual request epoch; legacy rows fail closed to no request. */
internal val CURRENT_TUTOR_VISUAL_INTENT_MIGRATION_47_48 = object : Migration(47, 48) {
    override suspend fun migrate(connection: SQLiteConnection) {
        val alreadyMigrated = connection.tableExists("tutor_current_policy")
        if (!alreadyMigrated) {
            LegacyBusinessWriteBarrierSchema.requireV47ApplicationTableInventory(connection)
        }
        if (!connection.columnExists("tutor_current_host_work", "visual_intent")) {
            connection.execSQL(
                "ALTER TABLE `tutor_current_host_work` " +
                    "ADD COLUMN `visual_intent` TEXT NOT NULL DEFAULT 'NONE'",
            )
        }
        if (!connection.columnExists("tutor_current_host_work", "visual_intent_version")) {
            connection.execSQL(
                "ALTER TABLE `tutor_current_host_work` " +
                    "ADD COLUMN `visual_intent_version` INTEGER NOT NULL DEFAULT 0",
            )
        }
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_current_policy` (
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `explanation_mode` TEXT NOT NULL,
                `mode_version` INTEGER NOT NULL,
                `learning_writes_allowed` INTEGER NOT NULL,
                `learning_write_permission_version` INTEGER NOT NULL,
                `visual_intent` TEXT NOT NULL,
                `visual_intent_version` INTEGER NOT NULL,
                `state_fingerprint` TEXT NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`learner_id`, `session_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_tutor_current_policy_updated_at_epoch_millis`
            ON `tutor_current_policy` (`updated_at_epoch_millis`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO `tutor_current_policy` (
                `learner_id`,
                `session_id`,
                `explanation_mode`,
                `mode_version`,
                `learning_writes_allowed`,
                `learning_write_permission_version`,
                `visual_intent`,
                `visual_intent_version`,
                `state_fingerprint`,
                `updated_at_epoch_millis`
            )
            SELECT
                host.`learner_id`,
                host.`session_id`,
                host.`explanation_mode`,
                host.`mode_version`,
                host.`learning_writes_allowed`,
                host.`learning_write_permission_version`,
                host.`visual_intent`,
                host.`visual_intent_version`,
                host.`state_fingerprint`,
                host.`updated_at_epoch_millis`
            FROM `tutor_current_host_work` AS host
            WHERE host.`status` != 'REVOKED'
            """.trimIndent(),
        )
        if (!alreadyMigrated) {
            LegacyBusinessWriteBarrierSchema.requireV48ApplicationTableInventory(connection)
        }
    }
}
