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
                ALTER TABLE `learning_observation_candidate`
                ADD COLUMN `source_fact_id` TEXT
                    REFERENCES `learning_observation_source_fact`(`source_fact_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
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
                CREATE INDEX IF NOT EXISTS
                    `index_attributed_learning_observation_event_source_fact_id`
                ON `attributed_learning_observation_event` (`source_fact_id`)
                """.trimIndent(),
            )
        }
    }
