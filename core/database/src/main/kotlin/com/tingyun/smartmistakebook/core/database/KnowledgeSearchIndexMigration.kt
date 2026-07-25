package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val KNOWLEDGE_SEARCH_INDEX_MIGRATION_21_22 = object : Migration(21, 22) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_search_feature` (
                `subject` TEXT NOT NULL,
                `search_feature` TEXT NOT NULL,
                `knowledge_node_id` TEXT NOT NULL,
                PRIMARY KEY(`subject`, `search_feature`, `knowledge_node_id`),
                FOREIGN KEY(`knowledge_node_id`) REFERENCES `knowledge_node`(`knowledge_node_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_search_feature_knowledge_node_id` " +
                "ON `knowledge_search_feature` (`knowledge_node_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_search_feature_subject_knowledge_node_id` " +
                "ON `knowledge_search_feature` (`subject`, `knowledge_node_id`)",
        )
    }
}
