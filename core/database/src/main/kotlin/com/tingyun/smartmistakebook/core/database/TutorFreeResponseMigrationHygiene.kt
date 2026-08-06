package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** Durable, content-free marker left by v48→v49 until file-level sanitization succeeds. */
internal const val TUTOR_FREE_RESPONSE_MIGRATION_HYGIENE_FINGERPRINT =
    "46e9651b70af1e6bdad4c41f6f560f04453ae197588b50c9cd2faab56efc4e88"
internal const val TUTOR_FREE_RESPONSE_MIGRATION_HYGIENE_SUBJECT =
    "tutor-free-response-migration-hygiene"

internal data class StudyDatabasePreOpenState(
    val version: Int,
    val hygienePending: Boolean,
)

internal fun inspectStudyDatabaseBeforeRoomOpen(
    context: Context,
    databaseName: String,
): StudyDatabasePreOpenState {
    val path = context.getDatabasePath(databaseName)
    if (!path.isFile) return StudyDatabasePreOpenState(version = 0, hygienePending = false)
    return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
        StudyDatabasePreOpenState(
            version = sqlite.version,
            hygienePending = sqlite.hasTutorFreeResponseMigrationHygieneMarker(),
        )
    }
}

/**
 * Must run only while the owning Room database is closed. It never removes WAL/SHM files itself;
 * SQLite checkpoints them under its own locks, vacuums the main file, then truncates the new WAL.
 */
internal fun runTutorFreeResponseMigrationHygieneExclusive(
    context: Context,
    databaseName: String,
) {
    val path = context.getDatabasePath(databaseName)
    require(path.isFile) { "Study database is missing during migration hygiene" }
    SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
        if (!sqlite.hasTutorFreeResponseMigrationHygieneMarker()) return
        sqlite.requireSecureDeleteEnabled()
        sqlite.requireUncontendedWalCheckpoint()
        sqlite.execSQL("VACUUM")
        sqlite.requireUncontendedWalCheckpoint()
        sqlite.execSQL(
            "DELETE FROM model_task_operation WHERE operation_fingerprint = ?",
            arrayOf<Any>(TUTOR_FREE_RESPONSE_MIGRATION_HYGIENE_FINGERPRINT),
        )
        sqlite.requireUncontendedWalCheckpoint()
        check(!sqlite.hasTutorFreeResponseMigrationHygieneMarker()) {
            "Tutor free-response migration hygiene marker was not cleared"
        }
    }
}

private fun SQLiteDatabase.hasTutorFreeResponseMigrationHygieneMarker(): Boolean =
    rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'model_task_operation'",
        null,
    ).use { tables ->
        if (!tables.moveToFirst()) return@use false
        rawQuery(
            "SELECT 1 FROM model_task_operation WHERE operation_fingerprint = ? LIMIT 1",
            arrayOf(TUTOR_FREE_RESPONSE_MIGRATION_HYGIENE_FINGERPRINT),
        ).use { cursor -> cursor.moveToFirst() }
    }

private fun SQLiteDatabase.requireUncontendedWalCheckpoint() {
    rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
        check(cursor.moveToFirst()) { "SQLite did not return a WAL checkpoint result" }
        check(cursor.getInt(0) == 0) { "Study database WAL is still owned by another connection" }
    }
}

private fun SQLiteDatabase.requireSecureDeleteEnabled() {
    rawQuery("PRAGMA secure_delete = ON", null).use { cursor ->
        check(cursor.moveToFirst()) { "SQLite did not return the secure_delete mode" }
        check(cursor.getInt(0) != 0) { "SQLite secure_delete could not be enabled" }
    }
}
