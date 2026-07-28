package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningObservationMigrationInstrumentedTest {
    @Test
    fun versionThirtyOneBackfillsLedgerIdentitiesWithoutInventingObservationEvidence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "learning-observation-v31-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 31)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    INSERT INTO problem(problem_id, canonical_fingerprint, subject, created_at_epoch_millis)
                    VALUES('legacy-problem', 'legacy-problem-fingerprint', 'MATH', 10)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO learning_sequence(learner_id, last_allocated_sequence)
                    VALUES('legacy-learner', 7)
                    """.trimIndent(),
                )
                database.seedLegacyLedgerEvents()
            }

            StudyDatabaseFactory.open(context, databaseName).use { store ->
                // Room 3 opens lazily; a DAO query forces migration and full schema validation.
                assertEquals(
                    null,
                    store.readLearningObservationSourceAuthority(
                        learnerId = "migration-probe",
                        source = LearningObservationSource.TUTOR_CHOICE,
                        sourceReferenceId = "missing-source",
                    ),
                )
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertEquals(1, database.scalarInt("SELECT COUNT(*) FROM problem"))
                assertEquals(
                    7,
                    database.scalarInt(
                        "SELECT last_allocated_sequence FROM learning_sequence WHERE learner_id = 'legacy-learner'",
                    ),
                )
                assertEquals(
                    0,
                    database.scalarInt("SELECT COUNT(*) FROM learning_observation_candidate"),
                )
                assertEquals(
                    0,
                    database.scalarInt(
                        "SELECT COUNT(*) FROM learning_observation_source_authority",
                    ),
                )
                assertEquals(
                    0,
                    database.scalarInt("SELECT COUNT(*) FROM attributed_learning_observation_event"),
                )
                assertEquals(
                    0,
                    database.scalarInt("SELECT COUNT(*) FROM learning_evidence_review_case"),
                )
                assertEquals(0, database.scalarInt("SELECT COUNT(*) FROM projection_outbox"))
                assertEquals(4, database.scalarInt("SELECT COUNT(*) FROM learning_event_identity"))
                assertEquals(
                    "ATTEMPT",
                    database.scalarString(
                        "SELECT event_kind FROM learning_event_identity WHERE event_id = 'legacy-attempt'",
                    ),
                )
                assertEquals(
                    "ATTEMPT_CORRECTION",
                    database.scalarString(
                        "SELECT event_kind FROM learning_event_identity WHERE event_id = 'legacy-correction'",
                    ),
                )
                assertEquals(
                    "ANSWER_REVEAL_OUTCOME",
                    database.scalarString(
                        "SELECT event_kind FROM learning_event_identity WHERE event_id = 'legacy-reveal'",
                    ),
                )
                assertEquals(
                    "TUTOR_ANSWER_EXPOSURE_OUTCOME",
                    database.scalarString(
                        "SELECT event_kind FROM learning_event_identity WHERE event_id = 'legacy-tutor'",
                    ),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionThirtyOneMigrationFailsClosedForCrossKindEventIdCollision() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "learning-observation-collision-v31-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 31)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.insertLegacyAttempt("shared-event-id")
                database.insertLegacyAnswerReveal("shared-event-id")
            }

            val failure = runCatching {
                StudyDatabaseFactory.open(context, databaseName).use { store ->
                    store.readLearningObservationSourceAuthority(
                        learnerId = "migration-probe",
                        source = LearningObservationSource.TUTOR_CHOICE,
                        sourceReferenceId = "missing-source",
                    )
                }
            }.exceptionOrNull()
            assertNotNull(failure)

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(31, database.version)
                assertEquals(
                    0,
                    database.scalarInt(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'learning_event_identity'",
                    ),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun SQLiteDatabase.seedLegacyLedgerEvents() {
        insertLegacyAttempt("legacy-attempt")
        execSQL(
            """
            INSERT INTO attempt_correction(
                correction_id, learner_id, submission_id, attempt_id, event_sequence,
                canonical_fingerprint, replacement_evidence_direction,
                replacement_evidence_weight, replacement_evidence_reason,
                replacement_memory_outcome, reason_markdown, occurred_at_epoch_millis
            ) VALUES(
                'legacy-correction', 'legacy-learner', 'legacy-submission',
                'legacy-attempt', 2, 'legacy-correction-fingerprint', 'POSITIVE',
                1.0, 'CORRECT_INDEPENDENT', 'RETRIEVAL_SUCCESS', 'legacy', 11
            )
            """.trimIndent(),
        )
        insertLegacyAnswerReveal("legacy-reveal")
        execSQL(
            """
            INSERT INTO tutor_answer_exposure_outcome(
                outcome_id, exposure_id, learner_id, session_id, question_document_id,
                question_revision_number, cycle_ordinal, turn_ordinal, problem_revision_id,
                practice_unit_id, event_sequence, canonical_fingerprint,
                occurred_at_epoch_millis
            ) VALUES(
                'legacy-tutor', 'legacy-exposure', 'legacy-learner', 'legacy-session',
                'legacy-question', 1, 1, 1, 'legacy-revision', 'legacy-unit', 4,
                'legacy-tutor-fingerprint', 13
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteDatabase.insertLegacyAttempt(eventId: String) {
        execSQL(
            """
            INSERT INTO attempt_event(
                attempt_id, learner_id, submission_id, event_sequence, canonical_fingerprint,
                presentation_id, response_ordinal, assessment_snapshot_id,
                evidence_direction, evidence_weight, evidence_reason, problem_memory_outcome,
                occurred_at_epoch_millis, duration_seconds, study_day_epoch_day,
                study_day_time_zone_id, study_day_utc_offset_minutes
            ) VALUES(
                '$eventId', 'legacy-learner', 'legacy-submission', 1,
                'legacy-attempt-fingerprint', 'legacy-presentation', 1, 'legacy-snapshot',
                'POSITIVE', 1.0, 'CORRECT_INDEPENDENT', 'RETRIEVAL_SUCCESS',
                10, 1, 0, 'UTC', 0
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteDatabase.insertLegacyAnswerReveal(eventId: String) {
        execSQL(
            """
            INSERT INTO answer_reveal_outcome(
                outcome_id, learner_id, assessment_event_id, presentation_id,
                assessment_snapshot_id, event_sequence, canonical_fingerprint,
                occurred_at_epoch_millis, study_day_epoch_day, study_day_time_zone_id,
                study_day_utc_offset_minutes
            ) VALUES(
                '$eventId', 'legacy-learner', 'legacy-reveal-event', 'legacy-presentation',
                'legacy-snapshot', 3, 'legacy-reveal-fingerprint', 12, 0, 'UTC', 0
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteDatabase.scalarInt(query: String): Int =
        rawQuery(query, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SQLiteDatabase.scalarString(query: String): String =
        rawQuery(query, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
}
