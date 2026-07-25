package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val KNOWLEDGE_RETRIEVAL_INDEX_MIGRATION_23_24 = object : Migration(23, 24) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_knowledge_node_relation_subject_dependent_knowledge_node_id`
            ON `knowledge_node_relation` (`subject`, `dependent_knowledge_node_id`)
            """.trimIndent(),
        )
    }
}
