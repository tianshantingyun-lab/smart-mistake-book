package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v32 -> v33: student-model prediction audit tables (audit PR-07 / section 6).
 *
 * Persists every shadow prediction together with its later real outcome so
 * calibration (Brier/log-loss/ECE) can be computed over resolved pairs.
 */
internal val PREDICTION_AUDIT_MIGRATION_32_33 = object : Migration(32, 33) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_model_prediction` (
                `prediction_id` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `model_version` TEXT NOT NULL,
                `algorithm_hash` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `knowledge_node_id` TEXT,
                `feature_fingerprint` TEXT NOT NULL,
                `predicted_score` REAL NOT NULL,
                `conservative_score` REAL NOT NULL,
                `prediction_window_start_epoch_millis` INTEGER NOT NULL,
                `prediction_window_end_epoch_millis` INTEGER NOT NULL,
                `predicted_at_epoch_millis` INTEGER NOT NULL,
                `resolved` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`prediction_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_student_model_prediction_practice_unit_id` " +
                "ON `student_model_prediction` (`practice_unit_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_student_model_prediction_knowledge_node_id` " +
                "ON `student_model_prediction` (`knowledge_node_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_model_prediction_prediction_window_end_epoch_millis` " +
                "ON `student_model_prediction` (`prediction_window_end_epoch_millis`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_student_model_prediction_model_id_model_version` " +
                "ON `student_model_prediction` (`model_id`, `model_version`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `prediction_outcome` (
                `prediction_id` TEXT NOT NULL,
                `observed_at_epoch_millis` INTEGER NOT NULL,
                `was_independent_correct` INTEGER NOT NULL,
                `response_latency_ms` INTEGER,
                `hint_count` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`prediction_id`),
                FOREIGN KEY(`prediction_id`) REFERENCES
                    `student_model_prediction`(`prediction_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_prediction_outcome_prediction_id` " +
                "ON `prediction_outcome` (`prediction_id`)",
        )
    }
}
