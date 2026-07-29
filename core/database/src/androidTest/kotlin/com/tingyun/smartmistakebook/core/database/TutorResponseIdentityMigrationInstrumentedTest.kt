package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorResponseIdentityMigrationInstrumentedTest {
    @Test
    fun versionThirtyFourPreservesLegacyChoiceWithoutInventingEvidenceRequestId() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-response-identity-v35-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 34)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { it.seedLegacyChoice() }

            StudyDatabaseFactory.open(context, databaseName).use { store ->
                val migrated = store.observeTutorTurnResponses(LEGACY_SESSION_ID).first().single()
                assertEquals("legacy-choice", migrated.selectedChoiceId)
                assertEquals("legacy feedback", migrated.feedbackMarkdown)
                assertNull(migrated.evidenceRequestId)
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertEquals("evidence_request_id", database.evidenceRequestColumn())
                assertNull(database.legacyEvidenceRequestId())
                assertEquals("ok", database.scalarText("PRAGMA integrity_check"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun SQLiteDatabase.seedLegacyChoice() {
        execSQL(
            """
            INSERT INTO tutor_turn_response(
                session_id, question_document_id, revision_number, cycle_ordinal, turn_ordinal,
                diagnostic_stem_markdown, selected_choice_id, selected_choice_markdown,
                selection_was_correct, feedback_markdown, requested_move, solution_revealed,
                choice_submitted_at_epoch_millis, submitted_at_epoch_millis, updated_at_epoch_millis
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                LEGACY_SESSION_ID,
                "legacy-question",
                2,
                1,
                1,
                "legacy stem",
                "legacy-choice",
                "legacy option",
                1,
                "legacy feedback",
                null,
                0,
                1_000,
                1_000,
                1_000,
            ),
        )
    }

    private fun SQLiteDatabase.evidenceRequestColumn(): String =
        rawQuery("PRAGMA table_info(`tutor_turn_response`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "evidence_request_id") {
                    return cursor.getString(nameIndex)
                }
            }
            error("Missing evidence_request_id")
        }

    private fun SQLiteDatabase.legacyEvidenceRequestId(): String? =
        rawQuery(
            "SELECT evidence_request_id FROM tutor_turn_response WHERE session_id = ?",
            arrayOf(LEGACY_SESSION_ID),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SQLiteDatabase.scalarText(query: String): String =
        rawQuery(query, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        const val LEGACY_SESSION_ID = "legacy-response-session"
    }
}
