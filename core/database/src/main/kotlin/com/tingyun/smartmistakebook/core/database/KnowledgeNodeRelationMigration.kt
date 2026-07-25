package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val KNOWLEDGE_NODE_RELATION_MIGRATION_20_21 = object : Migration(20, 21) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_node_relation` (
                `relation_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `prerequisite_knowledge_node_id` TEXT NOT NULL,
                `dependent_knowledge_node_id` TEXT NOT NULL,
                `relation_type` TEXT NOT NULL,
                `source_id` TEXT NOT NULL,
                `source_locator` TEXT NOT NULL,
                `reviewed_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`relation_id`),
                FOREIGN KEY(`prerequisite_knowledge_node_id`)
                    REFERENCES `knowledge_node`(`knowledge_node_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`dependent_knowledge_node_id`)
                    REFERENCES `knowledge_node`(`knowledge_node_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`source_id`)
                    REFERENCES `knowledge_source`(`source_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_knowledge_node_relation_prerequisite_knowledge_node_id_dependent_knowledge_node_id_relation_type` " +
                "ON `knowledge_node_relation` (`prerequisite_knowledge_node_id`, `dependent_knowledge_node_id`, `relation_type`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_relation_dependent_knowledge_node_id` " +
                "ON `knowledge_node_relation` (`dependent_knowledge_node_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_relation_source_id` " +
                "ON `knowledge_node_relation` (`source_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_relation_subject_relation_type` " +
                "ON `knowledge_node_relation` (`subject`, `relation_type`)",
        )
    }
}
