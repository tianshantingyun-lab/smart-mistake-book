package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.tingyun.smartmistakebook.core.database.port.VisualInteractionAttemptRecord

@RunWith(AndroidJUnit4::class)
class VisualInteractionAttemptMigrationInstrumentedTest {

    @Test
    fun versionThirtyThreeMigratesToVisualInteractionAttemptAndRoundTripsRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "visual-interaction-v33-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 33)
            val store = StudyDatabaseFactory.open(context, databaseName)

            runBlocking { assertEquals(STUDY_DATABASE_VERSION, store.readDatabaseVersion()) }

            store.recordVisualInteractionAttempt(
                VisualInteractionAttemptRecord(
                    attemptId = "visual-audit-1",
                    problemRevisionId = "revision-audit",
                    actionKind = "DragPoint",
                    actionPayload = """{"type":"drag","elementId":"beaker","toX":3.0,"toY":4.0}""",
                    feasible = true,
                    feedback = "操作正确",
                    attemptedAtEpochMillis = 1_500,
                ),
            )
            // Duplicate attempts are ignored, not duplicated (IGNORE conflict).
            store.recordVisualInteractionAttempt(
                VisualInteractionAttemptRecord(
                    attemptId = "visual-audit-1",
                    problemRevisionId = "revision-audit",
                    actionKind = "DragPoint",
                    actionPayload = """{"type":"drag","elementId":"beaker","toX":9.0,"toY":9.0}""",
                    feasible = false,
                    feedback = "再试一次",
                    attemptedAtEpochMillis = 1_600,
                ),
            )

            val rows = store.readVisualInteractionAttempts("revision-audit")
            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals("visual-audit-1", row.attemptId)
            assertEquals("revision-audit", row.problemRevisionId)
            assertEquals("DragPoint", row.actionKind)
            assertTrue(row.feasible)
            assertEquals("操作正确", row.feedback)
            assertEquals(1_500, row.attemptedAtEpochMillis)
            assertEquals(emptyList<VisualInteractionAttemptRecord>(), store.readVisualInteractionAttempts("missing"))

            store.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migratedDatabaseMatchesExportedSchemaThirtyFourSqliteMaster() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val referenceName = "visual-interaction-ref-${System.nanoTime()}.db"
        val migratedName = "visual-interaction-mig-${System.nanoTime()}.db"
        context.deleteDatabase(referenceName)
        context.deleteDatabase(migratedName)
        try {
            // Reference: built purely from the exported 34.json createSql.
            createDatabaseFromExportedSchema(context, referenceName, version = STUDY_DATABASE_VERSION)
            // Candidate: 33.json schema pushed through the real migration chain.
            createDatabaseFromExportedSchema(context, migratedName, version = 33)
            runBlocking {
                val migrated = StudyDatabaseFactory.open(context, migratedName)
                // Room migrates lazily: touch the database so the chain runs
                // before the sqlite_master comparison.
                migrated.readDatabaseVersion()
                migrated.close()
            }
            assertStructurallyEqual(
                context.getDatabasePath(referenceName),
                context.getDatabasePath(migratedName),
            )
        } finally {
            context.deleteDatabase(referenceName)
            context.deleteDatabase(migratedName)
        }
    }

    private fun readSqliteMaster(path: File): List<List<String?>> {
        val database = SQLiteDatabase.openDatabase(
            path.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val cursor = database.rawQuery(
                "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name",
                null,
            )
            val rows = mutableListOf<List<String?>>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    rows += listOf(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                    )
                }
            }
            return rows
        } finally {
            database.close()
        }
    }
}
