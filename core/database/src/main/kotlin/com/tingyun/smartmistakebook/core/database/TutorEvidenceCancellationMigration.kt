package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val TUTOR_EVIDENCE_CANCELLATION_MIGRATION_30_31 = object : Migration(30, 31) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_evidence_cancellation` (
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `revision_number` INTEGER NOT NULL,
                `evidence_request_id` TEXT NOT NULL,
                `cancelled_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(
                    `learner_id`,
                    `session_id`,
                    `question_document_id`,
                    `revision_number`,
                    `evidence_request_id`
                )
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_tutor_evidence_cancellation_session_id_question_document_id_revision_number`
            ON `tutor_evidence_cancellation` (
                `session_id`,
                `question_document_id`,
                `revision_number`
            )
            """.trimIndent(),
        )
    }
}
