package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds explicit canonical source-fact provenance without guessing associations for legacy rows.
 */
internal val LEARNING_OBSERVATION_SOURCE_FACT_MIGRATION_35_36 =
    object : Migration(35, 36) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                ALTER TABLE `learning_observation_source_authority`
                ADD COLUMN `source_fact_id` TEXT
                    REFERENCES `learning_observation_source_fact`(`source_fact_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_learning_observation_source_authority_source_fact_id`
                ON `learning_observation_source_authority` (`source_fact_id`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                ALTER TABLE `learning_observation_candidate`
                ADD COLUMN `source_fact_id` TEXT
                    REFERENCES `learning_observation_source_fact`(`source_fact_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_learning_observation_candidate_source_fact_id`
                ON `learning_observation_candidate` (`source_fact_id`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                ALTER TABLE `attributed_learning_observation_event`
                ADD COLUMN `source_fact_id` TEXT
                    REFERENCES `learning_observation_source_fact`(`source_fact_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_attributed_learning_observation_event_source_fact_id`
                ON `attributed_learning_observation_event` (`source_fact_id`)
                """.trimIndent(),
            )
            // A legacy observation may already have polluted every projection derived from the
            // learner ledger. Keep immutable ledger/audit rows; source_fact_id NULL is an explicit
            // projection tombstone that consumes its sequence without changing mastery. Force a
            // clean rebuild so the tombstone semantics are applied from the ledger boundary. Room
            // migration connections do not provide reliable FK cascades, so delete every snapshot
            // child explicitly, deepest dependencies first.
            listOf(
                "independent_correct_observation",
                "applied_learning_observation_record",
                "applied_tutor_answer_exposure_record",
                "learner_problem_memory_state",
                "learner_knowledge_mastery_state",
                "applied_attempt_record",
                "applied_correction_record",
                "applied_answer_reveal_record",
                "presentation_projection_state",
            ).forEach { tableName ->
                connection.execSQL(
                    """
                    DELETE FROM `$tableName`
                    WHERE `learner_id` IN (
                        SELECT DISTINCT `learner_id`
                        FROM `attributed_learning_observation_event`
                        WHERE `source_fact_id` IS NULL
                    )
                    """.trimIndent(),
                )
            }
            // Consumption rows are not children of learner_projection_snapshot.
            connection.execSQL(
                """
                DELETE FROM `projection_consumption`
                WHERE `learner_id` IN (
                    SELECT DISTINCT `learner_id`
                    FROM `attributed_learning_observation_event`
                    WHERE `source_fact_id` IS NULL
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                DELETE FROM `learner_projection_snapshot`
                WHERE `learner_id` IN (
                    SELECT DISTINCT `learner_id`
                    FROM `attributed_learning_observation_event`
                    WHERE `source_fact_id` IS NULL
                )
                """.trimIndent(),
            )
        }
    }
