package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v44: error_book_entry gains `user_note` (NULL by default) — the learner's
 * private note about why this mistake happened. It lives on the entry (not on
 * the append-only revision) so it can be edited freely; it is user-private
 * text and never leaves the device in a model egress payload.
 */
internal val ENTRY_USER_NOTE_MIGRATION_43_44 = object : Migration(43, 44) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `error_book_entry` " +
                "ADD COLUMN `user_note` TEXT DEFAULT NULL",
        )
    }
}
