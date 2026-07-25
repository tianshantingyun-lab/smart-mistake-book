package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val KNOWLEDGE_GROUNDING_RESOLUTION_MIGRATION_19_20 = object : Migration(19, 20) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_grounding_resolution` (
                `grounding_key` TEXT NOT NULL,
                `resolution_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `knowledge_node_id` TEXT NOT NULL,
                `taxonomy_version` TEXT NOT NULL,
                `resolved_occurrence_count` INTEGER NOT NULL,
                `linked_practice_unit_count` INTEGER NOT NULL,
                `resolved_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`grounding_key`),
                FOREIGN KEY(`knowledge_node_id`) REFERENCES `knowledge_node`(`knowledge_node_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_knowledge_grounding_resolution_resolution_id` " +
                "ON `knowledge_grounding_resolution` (`resolution_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_knowledge_grounding_resolution_knowledge_node_id` " +
                "ON `knowledge_grounding_resolution` (`knowledge_node_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_knowledge_grounding_resolution_subject_resolved_at_epoch_millis` " +
                "ON `knowledge_grounding_resolution` (`subject`, `resolved_at_epoch_millis`)",
        )
    }
}
