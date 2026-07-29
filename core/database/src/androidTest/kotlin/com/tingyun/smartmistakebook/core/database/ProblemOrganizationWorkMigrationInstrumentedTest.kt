package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProblemOrganizationWorkMigrationInstrumentedTest {
    @Test
    fun versionThirtyTwoMigratesLegacyCommitReceiptWithoutCreatingOrganizationWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "problem-organization-work-v33-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 32)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use(::insertVersionThirtyTwoData)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            assertEquals(
                null,
                migrated.readProblemOrganizationWorkByCommitReceipt("legacy-commit"),
            )
            migrated.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertEquals("legacy-commit", database.singleText("SELECT command_id FROM problem_draft_commit_receipt"))
                assertEquals("legacy-problem", database.singleText("SELECT problem_id FROM problem"))
                assertEquals("legacy-revision", database.singleText("SELECT revision_id FROM problem_revision"))
                assertEquals("legacy-practice", database.singleText("SELECT practice_unit_id FROM practice_unit"))
                assertEquals("legacy-entry", database.singleText("SELECT entry_id FROM error_book_entry"))
                assertEquals("legacy-draft", database.singleText("SELECT draft_id FROM problem_draft"))
                assertTrue(NEW_TABLES.all { database.rowCount(it) == 0 })
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun insertVersionThirtyTwoData(database: SQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO canonical_source_asset VALUES (
                'legacy-asset', '${"a".repeat(64)}', 'source-assets/legacy.jpg', 'image/jpeg',
                1, 1, 1, 'PHOTO_PICKER', 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_draft VALUES (
                'legacy-draft', 'legacy-asset', 'LIBRARY', 'COMMITTED', 1, 1000, 2000, NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_draft_source_asset VALUES ('legacy-draft', 0, 'legacy-asset', 1000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_draft_revision VALUES (
                'legacy-draft', 1, NULL, 'MATH', '旧题目', '{}', '${"b".repeat(64)}', 'USER', 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem VALUES ('legacy-problem', '${"c".repeat(64)}', 'MATH', 2000, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_revision VALUES (
                'legacy-revision', 'legacy-problem', 1, '旧题目', '题干', NULL, NULL, NULL,
                'UNKNOWN', 'CAPTURE_CONFIRMED', 'legacy-asset', '${"d".repeat(64)}', 2000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO practice_unit VALUES (
                'legacy-practice', 'legacy-problem', 'legacy-revision', 'whole-problem',
                'WHOLE_PROBLEM', '旧题目', '题干', 60, 2000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO error_book_entry VALUES (
                'legacy-entry', 'legacy-practice', 'legacy-problem', 'legacy-revision',
                'capture:legacy-draft', 'ACTIVE', 2000, 2000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_draft_commit_receipt VALUES (
                'legacy-commit', '${"e".repeat(64)}', 'legacy-draft', 1, 'legacy-problem',
                'legacy-revision', 'legacy-practice', 'legacy-entry', 2000
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteDatabase.singleText(sql: String): String =
        rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SQLiteDatabase.rowCount(table: String): Int =
        rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        val NEW_TABLES = listOf(
            "problem_solution_step",
            "problem_step_knowledge_binding",
            "problem_error_attribution_candidate",
            "problem_error_candidate_evidence",
            "problem_organization_work",
        )
    }
}
