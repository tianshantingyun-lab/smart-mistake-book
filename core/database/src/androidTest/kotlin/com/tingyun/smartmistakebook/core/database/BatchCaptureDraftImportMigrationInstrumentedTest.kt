package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchCaptureDraftImportMigrationInstrumentedTest {
    @Test
    fun versionFortyTwoCreatesTheBatchScopedCaptureTables(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName =
            "batch-capture-import-v42-to-v43-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 42)

            AndroidSQLiteDriver()
                .open(context.getDatabasePath(databaseName).path)
                .use { connection ->
                    BATCH_CAPTURE_DRAFT_IMPORT_MIGRATION_42_43.migrate(connection)
                    connection.execSQL("PRAGMA user_version = 43")
                }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(43, database.version)
                listOf(
                    "batch_capture_content_binding",
                    "capture_draft_batch_import_receipt",
                ).forEach { tableName ->
                    database.rawQuery(
                        """
                        SELECT COUNT(*)
                        FROM sqlite_master
                        WHERE type = 'table' AND name = ?
                        """.trimIndent(),
                        arrayOf(tableName),
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        assertEquals(1, cursor.getInt(0))
                    }
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
