package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorVisualTargetEvidenceMigrationInstrumentedTest {
    @Test
    fun versionTwentyEightUpgradesThroughTwentyNineAndPreservesTutorChoices() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-visual-evidence-v28-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 28)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL("PRAGMA foreign_keys=OFF")
                database.execSQL(
                    """
                    INSERT INTO tutor_turn_response(
                        session_id, question_document_id, revision_number,
                        cycle_ordinal, turn_ordinal, diagnostic_stem_markdown,
                        selected_choice_id, selected_choice_markdown,
                        selection_was_correct, feedback_markdown, requested_move,
                        solution_revealed, choice_submitted_at_epoch_millis,
                        submitted_at_epoch_millis, updated_at_epoch_millis
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        "session",
                        "question",
                        1,
                        1,
                        1,
                        "题干",
                        "choice-a",
                        "A",
                        1,
                        "正确",
                        null,
                        0,
                        10L,
                        10L,
                        10L,
                    ),
                )
            }

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            assertEquals(1, migrated.observeTutorTurnResponses("session").first().size)
            assertTrue(migrated.observeTutorVisualTargetEvidence("session").first().isEmpty())
            migrated.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                database.rawQuery(
                    "PRAGMA table_info(`tutor_visual_target_evidence`)",
                    null,
                ).use { cursor ->
                    val nameColumn = cursor.getColumnIndexOrThrow("name")
                    val columns = buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                    }
                    assertTrue(columns.contains("model_task_request_id"))
                    assertTrue(columns.contains("response_ordinal"))
                    assertTrue(columns.contains("scene_source_kind"))
                    assertTrue(columns.contains("scene_task_request_id"))
                    assertTrue(columns.contains("scene_fingerprint"))
                    assertTrue(columns.contains("hit_proof_id"))
                    assertTrue(columns.contains("frame_fingerprint"))
                    assertTrue(columns.contains("selected_target_id"))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionTwentyNineDropsUnverifiableVisualEvidenceAndAddsProofColumns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-visual-evidence-v29-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 29)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    INSERT INTO tutor_visual_target_evidence(
                        model_task_request_id, session_id, question_document_id,
                        revision_number, cycle_ordinal, turn_ordinal, surface_kind,
                        response_ordinal, selected_target_id, selection_was_correct,
                        submitted_at_epoch_millis
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        "legacy-request",
                        "session",
                        "question",
                        1,
                        1,
                        1,
                        "PLAN",
                        null,
                        "legacy-target",
                        1,
                        10L,
                    ),
                )
            }

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            assertTrue(migrated.observeTutorVisualTargetEvidence("session").first().isEmpty())
            migrated.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                database.rawQuery(
                    "PRAGMA table_info(`tutor_visual_target_evidence`)",
                    null,
                ).use { cursor ->
                    val nameColumn = cursor.getColumnIndexOrThrow("name")
                    val columns = buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                    }
                    assertTrue(columns.contains("scene_source_kind"))
                    assertTrue(columns.contains("scene_task_request_id"))
                    assertTrue(columns.contains("hit_proof_id"))
                    assertTrue(columns.contains("frame_fingerprint"))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
