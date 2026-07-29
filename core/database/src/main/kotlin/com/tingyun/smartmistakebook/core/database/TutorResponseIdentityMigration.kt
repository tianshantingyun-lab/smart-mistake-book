package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val TUTOR_RESPONSE_IDENTITY_MIGRATION_34_35 = object : Migration(34, 35) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `tutor_turn_response` ADD COLUMN `evidence_request_id` TEXT",
        )
    }
}
