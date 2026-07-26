package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val TUTOR_VISUAL_TARGET_EVIDENCE_MIGRATION_28_29 = object : Migration(28, 29) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_visual_target_evidence` (
                `model_task_request_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `surface_kind` TEXT NOT NULL,
                `response_ordinal` INTEGER,
                `selected_target_id` TEXT NOT NULL,
                `selection_was_correct` INTEGER NOT NULL,
                `submitted_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`model_task_request_id`),
                FOREIGN KEY(`model_task_request_id`) REFERENCES `model_task`(`request_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_visual_target_evidence_session_id` " +
                "ON `tutor_visual_target_evidence` (`session_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_tutor_visual_target_evidence_session_id_cycle_ordinal_turn_ordinal` " +
                "ON `tutor_visual_target_evidence` " +
                "(`session_id`, `cycle_ordinal`, `turn_ordinal`)",
        )
    }
}
