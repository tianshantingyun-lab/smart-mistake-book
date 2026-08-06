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
class TutorLearningEvidenceSessionMigrationInstrumentedTest {
    @Test
    fun versionFortyThreeCreatesOnlyTheV44TutorEvidenceReceiptTable(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-evidence-session-v43-to-v44-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 43)

            AndroidSQLiteDriver()
                .open(context.getDatabasePath(databaseName).path)
                .use { connection ->
                    TUTOR_LEARNING_EVIDENCE_SESSION_MIGRATION_43_44.migrate(connection)
                    connection.execSQL("PRAGMA user_version = 44")
                }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(44, database.version)
                database.rawQuery(
                    """
                    SELECT COUNT(*)
                    FROM sqlite_master
                    WHERE type = 'table'
                      AND name = 'tutor_learning_evidence_finalization_receipt'
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                database.rawQuery(
                    """
                    SELECT COUNT(*)
                    FROM pragma_table_info('tutor_evidence_request')
                    WHERE name = 'terminal_source_fact_id'
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
