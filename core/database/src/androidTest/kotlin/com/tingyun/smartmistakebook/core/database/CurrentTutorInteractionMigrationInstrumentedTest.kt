package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrentTutorInteractionMigrationInstrumentedTest {
    @Test
    fun v45ToV46AddsOnlyEmptySessionOwnerTablesWithoutBackfill(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "current-tutor-v45-to-v46-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 45)
            val path = context.getDatabasePath(databaseName).path
            val tablesBefore = SQLiteDatabase.openDatabase(
                path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database -> database.applicationTables() }
            assertFalse(tablesBefore.any { it.startsWith("tutor_current_interaction_") })

            AndroidSQLiteDriver().open(path).use { connection ->
                CURRENT_TUTOR_INTERACTION_MIGRATION_45_46.migrate(connection)
                connection.execSQL("PRAGMA user_version = 46")
            }

            SQLiteDatabase.openDatabase(
                path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(46, database.version)
                assertEquals(
                    tablesBefore + CURRENT_TABLES,
                    database.applicationTables(),
                )
                CURRENT_TABLES.forEach { table ->
                    assertEquals(0, database.rowCount(table))
                }
                assertEquals(
                    setOf("learner_id", "event_id"),
                    database.primaryKeyColumns("tutor_current_interaction_event"),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v46ToV47AddsOnlyEmptyRecoverableHostWorkWithoutInventingActivation(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "current-tutor-v46-to-v47-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 46)
            val path = context.getDatabasePath(databaseName).path
            val tablesBefore = SQLiteDatabase.openDatabase(
                path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database -> database.applicationTables() }
            assertFalse("tutor_current_host_work" in tablesBefore)

            AndroidSQLiteDriver().open(path).use { connection ->
                CURRENT_TUTOR_HOST_WORK_MIGRATION_46_47.migrate(connection)
                connection.execSQL("PRAGMA user_version = 47")
            }

            SQLiteDatabase.openDatabase(
                path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(47, database.version)
                assertEquals(tablesBefore + "tutor_current_host_work", database.applicationTables())
                assertEquals(0, database.rowCount("tutor_current_host_work"))
                assertEquals(
                    setOf("learner_id", "session_id"),
                    database.primaryKeyColumns("tutor_current_host_work"),
                )
                val hostColumns = database.columnNames("tutor_current_host_work")
                assertEquals(true, "authority_directive_fingerprint" in hostColumns)
                assertEquals(true, "constrained_tutor_content_fingerprint" in hostColumns)
                assertEquals(false, "model_output" in hostColumns)
                assertEquals(false, "answer_key" in hostColumns)
                assertEquals(false, "knowledge_proof" in hostColumns)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v47ToV48AddsFailClosedVisualIntentEpochWithoutOtherSchemaChanges(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "current-tutor-v47-to-v48-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 47)
            val path = context.getDatabasePath(databaseName).path
            val tablesBefore = SQLiteDatabase.openDatabase(
                path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use(SQLiteDatabase::applicationTables)

            AndroidSQLiteDriver().open(path).use { connection ->
                CURRENT_TUTOR_VISUAL_INTENT_MIGRATION_47_48.migrate(connection)
                connection.execSQL("PRAGMA user_version = 48")
            }

            SQLiteDatabase.openDatabase(
                path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(48, database.version)
                assertEquals(tablesBefore + "tutor_current_policy", database.applicationTables())
                assertEquals(0, database.rowCount("tutor_current_policy"))
                assertEquals("'NONE'", database.columnDefault("tutor_current_host_work", "visual_intent"))
                assertEquals("0", database.columnDefault("tutor_current_host_work", "visual_intent_version"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v48ToV49RoomOpenPurgesLegacyRawFreeResponsesAndSanitizesDatabaseFiles(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "current-tutor-v48-to-v49-${System.nanoTime()}.db"
            val canary = "legacy-FR-7d90a1c3-\"解析几何\"-\\-Q4m8"
            val escapedCanary = canary.replace("\\", "\\\\").replace("\"", "\\\"")
            context.deleteDatabase(databaseName)
            try {
                createDatabaseFromExportedSchema(context, databaseName, version = 48)
                val path = context.getDatabasePath(databaseName).path
                SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                    sqlite.insertLegacyTutorEvaluation(canary, escapedCanary)
                }

                StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { 50_000L }
                    .use { Unit }

                SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                    assertEquals(49, sqlite.version)
                    assertEquals(0, sqlite.countWhere("model_task", "task_kind = 'TUTOR_EVALUATE'"))
                    assertEquals(
                        0,
                        sqlite.countWhere(
                            "model_task_operation",
                            "task_kind = 'TUTOR_EVALUATE'",
                        ),
                    )
                    assertEquals(0, sqlite.rowCount("model_task_event"))
                    assertTrue(
                        sqlite.columnNames("tutor_free_response_outbox").containsAll(
                            setOf(
                                "lease_generation_id",
                                "dispatch_attempt_count",
                                "next_dispatch_at_epoch_millis",
                                "discard_after_epoch_millis",
                            ),
                        ),
                    )
                }
                assertForbiddenBytesAbsent(context, databaseName, canary, escapedCanary)
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun committedV49MigrationMarkerRecoversAfterCrashAndFinishesFileSanitization(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "current-tutor-v49-hygiene-recovery-${System.nanoTime()}.db"
            val canary = "legacy-FR-crash-913fc7a2-\"电磁感应\"-\\-P6k2"
            val escapedCanary = canary.replace("\\", "\\\\").replace("\"", "\\\"")
            context.deleteDatabase(databaseName)
            try {
                createDatabaseFromExportedSchema(context, databaseName, version = 48)
                val path = context.getDatabasePath(databaseName).path
                SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                    sqlite.insertLegacyTutorEvaluation(canary, escapedCanary)
                }
                AndroidSQLiteDriver().open(path).use { connection ->
                    TUTOR_FREE_RESPONSE_OUTBOX_MIGRATION_48_49.migrate(connection)
                    connection.execSQL("PRAGMA user_version = 49")
                }
                assertTrue(inspectStudyDatabaseBeforeRoomOpen(context, databaseName).hygienePending)

                runTutorFreeResponseMigrationHygieneExclusive(context, databaseName)

                assertFalse(inspectStudyDatabaseBeforeRoomOpen(context, databaseName).hygienePending)
                assertForbiddenBytesAbsent(context, databaseName, canary, escapedCanary)
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    private companion object {
        val CURRENT_TABLES =
            setOf(
                "tutor_current_interaction_scope",
                "tutor_current_interaction_head",
                "tutor_current_interaction_event",
            )
    }
}

private fun SQLiteDatabase.insertLegacyTutorEvaluation(
    rawCanary: String,
    escapedCanary: String,
) {
    val operationFingerprint = "a".repeat(64)
    val requestFingerprint = "b".repeat(64)
    execSQL(
        """
        INSERT INTO model_task_operation (
            operation_fingerprint, subject_id, task_kind, dispatch_count,
            created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (?, 'legacy-subject', 'TUTOR_EVALUATE', 1, 10, 10)
        """.trimIndent(),
        arrayOf(operationFingerprint),
    )
    execSQL(
        """
        INSERT INTO model_task (
            task_id, request_id, request_fingerprint, operation_fingerprint,
            request_snapshot, task_kind, subject_id, tutor_response_ordinal,
            status, state_version, stage, user_message, attempt_count,
            provider_snapshot, output_snapshot, failure_code, failure_message,
            failure_retryable, created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            'legacy-sensitive-task', 'legacy-sensitive-request', ?, ?, ?,
            'TUTOR_EVALUATE', 'legacy-subject', NULL, 'WAITING_FOR_MODEL', 0,
            'WAITING', '等待', 0, NULL, NULL, NULL, NULL, NULL, 10, 10
        )
        """.trimIndent(),
        arrayOf(
            requestFingerprint,
            operationFingerprint,
            "raw=$rawCanary;json=$escapedCanary",
        ),
    )
    execSQL(
        """
        INSERT INTO model_task_event (
            task_id, state_version, previous_status, next_status, stage, user_message,
            attempt_count, provider_snapshot, output_snapshot, failure_code,
            failure_message, failure_retryable, created_at_epoch_millis
        ) VALUES (
            'legacy-sensitive-task', 0, NULL, 'WAITING_FOR_MODEL', 'WAITING', '等待',
            0, NULL, NULL, NULL, NULL, NULL, 10
        )
        """.trimIndent(),
    )
}

private fun SQLiteDatabase.countWhere(table: String, predicate: String): Int =
    rawQuery("SELECT COUNT(*) FROM `$table` WHERE $predicate", null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun assertForbiddenBytesAbsent(
    context: Context,
    databaseName: String,
    vararg forbiddenValues: String,
) {
    val database = context.getDatabasePath(databaseName)
    listOf(database, File(database.path + "-wal"), File(database.path + "-shm"))
        .filter(File::isFile)
        .forEach { file ->
            val bytes = file.readBytes()
            forbiddenValues.forEach { forbidden ->
                assertFalse(
                    "Legacy free response found in ${file.name}",
                    bytes.containsBytes(forbidden.toByteArray(StandardCharsets.UTF_8)),
                )
            }
        }
}

private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    for (start in 0..size - needle.size) {
        if (needle.indices.all { offset -> this[start + offset] == needle[offset] }) return true
    }
    return false
}

private fun SQLiteDatabase.applicationTables(): Set<String> =
    rawQuery(
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
        null,
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                cursor.getString(0).takeIf { name ->
                    name != "android_metadata" &&
                        name != "room_master_table" &&
                        !name.startsWith("sqlite_")
                }?.let(::add)
            }
        }
    }

private fun SQLiteDatabase.rowCount(table: String): Int =
    rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun SQLiteDatabase.primaryKeyColumns(table: String): Set<String> =
    rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                if (cursor.getInt(5) > 0) add(cursor.getString(1))
            }
        }
    }

private fun SQLiteDatabase.columnNames(table: String): Set<String> =
    rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(1))
        }
    }

private fun SQLiteDatabase.columnDefault(table: String, column: String): String? =
    rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == column) return@use cursor.getString(4)
        }
        null
    }
