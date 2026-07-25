package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val KNOWLEDGE_SOURCE_REUSE_RIGHTS_MIGRATION_27_28 = object : Migration(27, 28) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `knowledge_source` ADD COLUMN `content_use_policy` " +
                "TEXT NOT NULL DEFAULT 'REVIEWED_SYNTHESIS_ONLY'",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_source` ADD COLUMN `license_expression` TEXT",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_source` ADD COLUMN `license_uri` TEXT",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_source` ADD COLUMN `attribution_text` TEXT",
        )
    }
}
