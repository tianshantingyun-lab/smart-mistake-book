package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val TUTOR_FREE_RESPONSE_OUTBOX_MIGRATION_48_49 = object : Migration(48, 49) {
    override suspend fun migrate(connection: SQLiteConnection) {
        val alreadyMigrated = connection.tableExists("tutor_free_response_outbox")
        if (!alreadyMigrated) {
            LegacyBusinessWriteBarrierSchema.requireV48ApplicationTableInventory(connection)
        }
        // Older builds persisted raw open responses inside model_task.request_snapshot.
        connection.execSQL("PRAGMA secure_delete = ON")
        connection.execSQL(
            """
            DELETE FROM `model_task_event`
            WHERE `task_id` IN (
                SELECT `task_id` FROM `model_task` WHERE `task_kind` = 'TUTOR_EVALUATE'
            )
            """.trimIndent(),
        )
        connection.execSQL("DELETE FROM `model_task` WHERE `task_kind` = 'TUTOR_EVALUATE'")
        connection.execSQL(
            """
            DELETE FROM `model_task_operation`
            WHERE `task_kind` = 'TUTOR_EVALUATE'
              AND NOT EXISTS (
                  SELECT 1 FROM `model_task`
                  WHERE `model_task`.`operation_fingerprint` =
                        `model_task_operation`.`operation_fingerprint`
              )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT OR REPLACE INTO `model_task_operation` (
                `operation_fingerprint`, `subject_id`, `task_kind`, `dispatch_count`,
                `created_at_epoch_millis`, `updated_at_epoch_millis`
            ) VALUES (
                '$TUTOR_FREE_RESPONSE_MIGRATION_HYGIENE_FINGERPRINT',
                '$TUTOR_FREE_RESPONSE_MIGRATION_HYGIENE_SUBJECT',
                'TUTOR_EVALUATE', 0, 0, 0
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_free_response_outbox` (
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `authority_conversation_id` TEXT NOT NULL,
                `conversation_generation` INTEGER NOT NULL,
                `action_token` TEXT NOT NULL,
                `action_expires_at_epoch_millis` INTEGER NOT NULL,
                `work_id` TEXT NOT NULL,
                `work_state_version` INTEGER NOT NULL,
                `work_state_fingerprint` TEXT NOT NULL,
                `presentation_token` TEXT NOT NULL,
                `evidence_request_id` TEXT NOT NULL,
                `answer_binding` TEXT NOT NULL,
                `payload_fingerprint` TEXT NOT NULL,
                `canonical_occurred_at_epoch_millis` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `encrypted_answer` BLOB,
                `nonce` BLOB,
                `key_version` INTEGER NOT NULL,
                `dispatch_attempt_count` INTEGER NOT NULL,
                `next_dispatch_at_epoch_millis` INTEGER NOT NULL,
                `discard_after_epoch_millis` INTEGER NOT NULL,
                `lease_owner_id` TEXT,
                `lease_generation_id` TEXT,
                `lease_token` TEXT,
                `lease_expires_at_epoch_millis` INTEGER,
                `candidate_idempotency_key` TEXT,
                `candidate_receipt_fingerprint` TEXT,
                `claimed_at_epoch_millis` INTEGER NOT NULL,
                `completed_at_epoch_millis` INTEGER,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`learner_id`, `action_token`),
                FOREIGN KEY(
                    `authority_conversation_id`, `learner_id`, `conversation_generation`
                ) REFERENCES `tutor_conversation`(
                    `conversation_id`, `learner_id`, `generation`
                ) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_tutor_free_response_outbox_authority_conversation_id_learner_id_conversation_generation`
            ON `tutor_free_response_outbox` (
                `authority_conversation_id`, `learner_id`, `conversation_generation`
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_tutor_free_response_outbox_learner_id_session_id_status_next_dispatch_at_epoch_millis_lease_expires_at_epoch_millis`
            ON `tutor_free_response_outbox` (
                `learner_id`, `session_id`, `status`, `next_dispatch_at_epoch_millis`,
                `lease_expires_at_epoch_millis`
            )
            """.trimIndent(),
        )
        if (!alreadyMigrated) {
            LegacyBusinessWriteBarrierSchema.requireV49ApplicationTableInventory(connection)
        }
    }
}
