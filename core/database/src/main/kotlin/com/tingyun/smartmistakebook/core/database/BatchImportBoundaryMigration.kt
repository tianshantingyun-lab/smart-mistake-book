package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val BATCH_IMPORT_BOUNDARY_MIGRATION_22_23 = object : Migration(22, 23) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            ALTER TABLE batch_import_page
            ADD COLUMN boundary_after_status TEXT NOT NULL DEFAULT 'PENDING'
            """.trimIndent(),
        )
    }
}
