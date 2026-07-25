package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttemptResponseMigrationInstrumentedTest {
    @Test
    fun versionFourteenAttemptRemainsV2AndDoesNotGuessAResponse() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "attempt-response-v14-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 14)
            seedLegacyAttempt(context, databaseName)

            var migrated = StudyDatabaseFactory.open(context, databaseName)
            val restored = requireNotNull(migrated.readAttemptP0(ATTEMPT_ID))
            assertEquals(AttemptSubmittedResponse.LegacyUnavailable, restored.attempt.submittedResponse)
            assertEquals(LEGACY_FINGERPRINT, restored.canonicalFingerprint)
            migrated.close()

            migrated = StudyDatabaseFactory.open(context, databaseName)
            assertEquals(
                AttemptSubmittedResponse.LegacyUnavailable,
                migrated.readAttemptP0(ATTEMPT_ID)?.attempt?.submittedResponse,
            )
            migrated.close()

            val database = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                database.query(
                    "attempt_event",
                    arrayOf(
                        "submitted_choice_id",
                        "submitted_choice_markdown",
                        "response_submitted_at_epoch_millis",
                    ),
                    "attempt_id = ?",
                    arrayOf(ATTEMPT_ID),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    assertNull(cursor.getString(0))
                    assertNull(cursor.getString(1))
                    assertTrueSqlNull(cursor, 2)
                }
                assertEquals(STUDY_DATABASE_VERSION, database.version)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionFourteenSolutionRevealDoesNotFabricateTutorExposure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-exposure-v14-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 14)
            seedLegacyTutorReveal(context, databaseName)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            assertNull(migrated.readTutorAnswerExposure(LEGACY_MODEL_TASK_REQUEST_ID))
            assertTrue(migrated.loadLearningLedger("learner:local").validPrefix.isEmpty())
            migrated.close()

            val database = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                assertEquals(0, database.rowCount("tutor_answer_exposure"))
                assertEquals(0, database.rowCount("tutor_answer_exposure_outcome"))
                assertEquals(0, database.rowCount("tutor_session_problem_anchor"))
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun seedLegacyAttempt(context: Context, databaseName: String) {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.execSQL("PRAGMA foreign_keys=OFF")
            database.execSQL(
                """
                INSERT INTO assessment_evidence_snapshot (
                    snapshot_id, assessment_item_id, practice_unit_id, problem_revision_id,
                    answer_spec_id, item_family_id, source_bundle_id, taxonomy_version,
                    verification, calibration_support, calibration_source_id, calibration_version,
                    calibration_valid_from_epoch_millis, calibration_valid_until_epoch_millis,
                    captured_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "snapshot-1", "assessment-1", "unit-1", "revision-1",
                    "answer-1", "family-1", null, "taxonomy-v1",
                    "VERIFIED", "SUPPORTED", "cal-source", "cal-v1",
                    0L, 10_000L, 1_000L,
                ),
            )
            database.execSQL(
                """
                INSERT INTO assessment_evidence_attribution (
                    snapshot_id, binding_id, practice_unit_id, knowledge_node_id, weight,
                    basis_revision_id, taxonomy_version, role, certainty
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "snapshot-1", "binding-1", "unit-1", "knowledge-1", 1.0,
                    "revision-1", "taxonomy-v1", "PRIMARY", "DIRECT",
                ),
            )
            database.execSQL(
                "INSERT INTO attempt_submission (submission_id, learner_id, payload_fingerprint) VALUES (?, ?, ?)",
                arrayOf<Any>(SUBMISSION_ID, LEARNER_ID, LEGACY_FINGERPRINT),
            )
            database.execSQL(
                """
                INSERT INTO attempt_event (
                    attempt_id, learner_id, submission_id, event_sequence, canonical_fingerprint,
                    presentation_id, response_ordinal, assessment_snapshot_id, evidence_direction,
                    evidence_weight, evidence_reason, problem_memory_outcome,
                    occurred_at_epoch_millis, duration_seconds, study_day_epoch_day,
                    study_day_time_zone_id, study_day_utc_offset_minutes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    ATTEMPT_ID, LEARNER_ID, SUBMISSION_ID, 1L, LEGACY_FINGERPRINT,
                    "presentation-1", 1, "snapshot-1", "POSITIVE",
                    1.0, "INDEPENDENT_CORRECT", "INDEPENDENT_RECALL",
                    2_000L, 30, 0L, "UTC", 0,
                ),
            )
            database.execSQL(
                """
                INSERT INTO projection_outbox (
                    outbox_id, learner_id, outbox_sequence, event_kind, event_id,
                    canonical_fingerprint, status, created_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "learning-outbox:$LEARNER_ID:ATTEMPT:$ATTEMPT_ID",
                    LEARNER_ID,
                    1L,
                    "ATTEMPT",
                    ATTEMPT_ID,
                    LEGACY_FINGERPRINT,
                    "PENDING",
                    2_000L,
                ),
            )
        } finally {
            database.close()
        }
    }

    private fun seedLegacyTutorReveal(context: Context, databaseName: String) {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.execSQL(
                """
                INSERT INTO tutor_turn_response (
                    session_id, question_document_id, revision_number, cycle_ordinal, turn_ordinal,
                    diagnostic_stem_markdown, selected_choice_id, selected_choice_markdown,
                    selection_was_correct, feedback_markdown, requested_move, solution_revealed,
                    choice_submitted_at_epoch_millis, submitted_at_epoch_millis,
                    updated_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    LEGACY_SESSION_ID,
                    "legacy-question-document",
                    1,
                    1,
                    1,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    null,
                    3_000L,
                    3_000L,
                ),
            )
        } finally {
            database.close()
        }
    }

    private fun SQLiteDatabase.rowCount(tableName: String): Int = rawQuery(
        "SELECT COUNT(*) FROM $tableName",
        null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun assertTrueSqlNull(cursor: android.database.Cursor, columnIndex: Int) {
        check(cursor.isNull(columnIndex)) { "Expected SQL NULL at column $columnIndex" }
    }

    private companion object {
        const val LEARNER_ID = "learner-legacy"
        const val SUBMISSION_ID = "submission-legacy"
        const val ATTEMPT_ID = "attempt-legacy"
        const val LEGACY_SESSION_ID = "legacy-tutor-session"
        const val LEGACY_MODEL_TASK_REQUEST_ID = "legacy-tutor-plan-request"
        const val LEGACY_FINGERPRINT =
            "7609ec6062b7b077d3d7d4f549591e7e35914aca7982ac9f298b0f611238c1f6"
    }
}
