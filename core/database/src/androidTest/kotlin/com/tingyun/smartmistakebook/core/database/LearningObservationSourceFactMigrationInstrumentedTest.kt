package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewReason
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidate
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidateStatus
import com.tingyun.smartmistakebook.core.model.LearningObservationDirection
import com.tingyun.smartmistakebook.core.model.LearningObservationEvidenceLevel
import com.tingyun.smartmistakebook.core.model.LearningObservationIndependence
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningObservationSourceFactMigrationInstrumentedTest {
    @Test
    fun versionThirtyFivePreservesV1FingerprintsWithoutInventingSourceFacts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "learning-observation-source-fact-v36-${System.nanoTime()}.db"
        val legacyCandidate = legacyCandidate()
        val legacyEvent = legacyEvent(legacyCandidate)
        val legacyCandidateFingerprint =
            LearningLedgerFingerprint.learningObservationCandidate(legacyCandidate)
        val legacyEventFingerprint = LearningLedgerFingerprint.learningObservation(legacyEvent)
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 35)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.seedLegacyObservation(
                    candidate = legacyCandidate,
                    event = legacyEvent,
                    candidateFingerprint = legacyCandidateFingerprint,
                    eventFingerprint = legacyEventFingerprint,
                )
            }

            StudyDatabaseFactory.open(context, databaseName).use { store ->
                val migratedCandidate = requireNotNull(
                    store.readLearningObservationCandidate(legacyCandidate.candidateId),
                )
                val migratedEvent = requireNotNull(
                    store.readAttributedLearningObservation(legacyEvent.eventId),
                )

                assertNull(migratedCandidate.sourceFactId)
                assertNull(migratedEvent.sourceFactId)
                assertEquals(
                    legacyCandidateFingerprint,
                    LearningLedgerFingerprint.learningObservationCandidate(migratedCandidate),
                )
                assertEquals(
                    legacyEventFingerprint,
                    LearningLedgerFingerprint.learningObservation(migratedEvent),
                )

                val replay = store.materializeLearningObservation(
                    MaterializeLearningObservationCommand(
                        candidateId = legacyCandidate.candidateId,
                        eventId = legacyEvent.eventId,
                        confirmedAtEpochMillis = legacyEvent.confirmedAtEpochMillis,
                    ),
                )
                assertNull(replay.event)
                assertEquals(
                    LearningEvidenceReviewReason.SOURCE_FACT_MISSING,
                    requireNotNull(replay.reviewCase).reason,
                )
                assertEquals(
                    LearningObservationCandidateStatus.READY,
                    store.readLearningObservationCandidate(legacyCandidate.candidateId)?.status,
                )
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertNull(
                    database.scalarNullableText(
                        "SELECT source_fact_id FROM learning_observation_candidate",
                    ),
                )
                assertNull(
                    database.scalarNullableText(
                        "SELECT source_fact_id FROM attributed_learning_observation_event",
                    ),
                )
                assertEquals(
                    legacyCandidateFingerprint,
                    database.scalarText(
                        "SELECT payload_fingerprint FROM learning_observation_candidate",
                    ),
                )
                assertEquals(
                    legacyEventFingerprint,
                    database.scalarText(
                        "SELECT canonical_fingerprint FROM attributed_learning_observation_event",
                    ),
                )
                assertTrue(
                    database.hasIndex(
                        "learning_observation_candidate",
                        "index_learning_observation_candidate_source_fact_id",
                    ),
                )
                assertTrue(
                    database.hasIndex(
                        "attributed_learning_observation_event",
                        "index_attributed_learning_observation_event_source_fact_id",
                    ),
                )
                assertTrue(
                    database.hasSourceFactForeignKey("learning_observation_candidate"),
                )
                assertTrue(
                    database.hasSourceFactForeignKey("attributed_learning_observation_event"),
                )
                assertEquals(0, database.scalarInt("SELECT COUNT(*) FROM projection_outbox"))
                assertEquals(0, database.scalarInt("SELECT COUNT(*) FROM learning_sequence"))
                assertEquals("ok", database.scalarText("PRAGMA integrity_check"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun SQLiteDatabase.seedLegacyObservation(
        candidate: LearningObservationCandidate,
        event: AttributedLearningObservationEvent,
        candidateFingerprint: String,
        eventFingerprint: String,
    ) {
        execSQL(
            """
            INSERT INTO problem(
                problem_id, canonical_fingerprint, subject, created_at_epoch_millis
            ) VALUES(?, ?, 'MATH', ?)
            """.trimIndent(),
            arrayOf<Any?>(PROBLEM_ID, "problem-fingerprint", 900L),
        )
        execSQL(
            """
            INSERT INTO problem_revision(
                revision_id, problem_id, revision_number, title, problem_markdown,
                answer_verification_status, source_type, content_fingerprint,
                created_at_epoch_millis
            ) VALUES(?, ?, 1, 'Legacy', 'Legacy problem', 'UNVERIFIED', 'MANUAL', ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(REVISION_ID, PROBLEM_ID, "revision-fingerprint", 900L),
        )
        execSQL(
            """
            INSERT INTO practice_unit(
                practice_unit_id, problem_id, problem_revision_id, unit_key, unit_kind,
                title, prompt_markdown, estimated_seconds, created_at_epoch_millis
            ) VALUES(?, ?, ?, 'legacy-unit', 'SHORT_ANSWER', 'Legacy', 'Legacy prompt', 30, ?)
            """.trimIndent(),
            arrayOf<Any?>(UNIT_ID, PROBLEM_ID, REVISION_ID, 900L),
        )
        execSQL(
            """
            INSERT INTO learning_observation_candidate(
                candidate_id, learner_id, source, source_reference_id, practice_unit_id,
                problem_revision_id, direction, evidence_level, evidence_weight, independence,
                occurred_at_epoch_millis, model_version, evidence_locator, status, retry_count,
                payload_fingerprint, created_at_epoch_millis, updated_at_epoch_millis
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                candidate.candidateId,
                candidate.learnerId,
                candidate.source.name,
                candidate.sourceReferenceId,
                candidate.practiceUnitId,
                candidate.problemRevisionId,
                candidate.direction.name,
                candidate.evidenceLevel.name,
                candidate.evidenceWeight,
                candidate.independence.name,
                candidate.occurredAtEpochMillis,
                candidate.modelVersion,
                candidate.evidenceLocator,
                candidate.status.name,
                candidate.retryCount,
                candidateFingerprint,
                candidate.createdAtEpochMillis,
                candidate.updatedAtEpochMillis,
            ),
        )
        execSQL(
            """
            INSERT INTO attributed_learning_observation_event(
                event_id, candidate_id, learner_id, practice_unit_id, problem_revision_id,
                subject, direction, evidence_level, evidence_weight, independence,
                occurred_at_epoch_millis, confirmed_at_epoch_millis, model_version,
                evidence_locator, event_sequence, canonical_fingerprint
            ) VALUES(?, ?, ?, ?, ?, 'MATH', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                event.eventId,
                event.candidateId,
                event.learnerId,
                event.practiceUnitId,
                event.problemRevisionId,
                event.direction.name,
                event.evidenceLevel.name,
                event.evidenceWeight,
                event.independence.name,
                event.occurredAtEpochMillis,
                event.confirmedAtEpochMillis,
                event.modelVersion,
                event.evidenceLocator,
                event.eventSequence,
                eventFingerprint,
            ),
        )
    }

    private fun legacyCandidate() = LearningObservationCandidate(
        candidateId = "legacy-candidate",
        learnerId = "legacy-learner",
        source = LearningObservationSource.TUTOR_CHOICE,
        sourceReferenceId = "legacy-choice",
        practiceUnitId = UNIT_ID,
        problemRevisionId = REVISION_ID,
        direction = LearningObservationDirection.POSITIVE,
        evidenceLevel = LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
        evidenceWeight = 0.25,
        independence = LearningObservationIndependence.UNKNOWN,
        proposedAttributions = emptyList(),
        occurredAtEpochMillis = 1_000,
        modelVersion = "legacy-model",
        evidenceLocator = "legacy:choice",
        status = LearningObservationCandidateStatus.READY,
        retryCount = 0,
        createdAtEpochMillis = 1_100,
        updatedAtEpochMillis = 1_100,
    )

    private fun legacyEvent(
        candidate: LearningObservationCandidate,
    ) = AttributedLearningObservationEvent(
        eventId = "legacy-event",
        candidateId = candidate.candidateId,
        learnerId = candidate.learnerId,
        practiceUnitId = requireNotNull(candidate.practiceUnitId),
        problemRevisionId = requireNotNull(candidate.problemRevisionId),
        direction = candidate.direction,
        evidenceLevel = LearningObservationEvidenceLevel.CONFIRMED,
        evidenceWeight = candidate.evidenceWeight,
        independence = candidate.independence,
        attributions = emptyList(),
        occurredAtEpochMillis = candidate.occurredAtEpochMillis,
        confirmedAtEpochMillis = 1_200,
        modelVersion = candidate.modelVersion,
        evidenceLocator = candidate.evidenceLocator,
        eventSequence = 1,
    )

    private fun SQLiteDatabase.hasIndex(table: String, expected: String): Boolean =
        rawQuery("PRAGMA index_list(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == expected) return true
            }
            false
        }

    private fun SQLiteDatabase.hasSourceFactForeignKey(table: String): Boolean =
        rawQuery("PRAGMA foreign_key_list(`$table`)", null).use { cursor ->
            val tableIndex = cursor.getColumnIndexOrThrow("table")
            val fromIndex = cursor.getColumnIndexOrThrow("from")
            val toIndex = cursor.getColumnIndexOrThrow("to")
            while (cursor.moveToNext()) {
                if (cursor.getString(tableIndex) == "learning_observation_source_fact" &&
                    cursor.getString(fromIndex) == "source_fact_id" &&
                    cursor.getString(toIndex) == "source_fact_id"
                ) {
                    return true
                }
            }
            false
        }

    private fun SQLiteDatabase.scalarNullableText(query: String): String? =
        rawQuery(query, null).use { cursor ->
            check(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun SQLiteDatabase.scalarText(query: String): String =
        rawQuery(query, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SQLiteDatabase.scalarInt(query: String): Int =
        rawQuery(query, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val PROBLEM_ID = "legacy-problem"
        const val REVISION_ID = "legacy-revision"
        const val UNIT_ID = "legacy-unit"
    }
}
