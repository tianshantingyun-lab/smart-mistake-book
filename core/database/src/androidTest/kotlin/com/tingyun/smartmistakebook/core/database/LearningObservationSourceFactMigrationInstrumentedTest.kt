package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewReason
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidate
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidateStatus
import com.tingyun.smartmistakebook.core.model.LearningObservationDirection
import com.tingyun.smartmistakebook.core.model.LearningObservationEvidenceLevel
import com.tingyun.smartmistakebook.core.model.LearningObservationIndependence
import com.tingyun.smartmistakebook.core.model.LearningObservationKnowledgeAttribution
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
    fun versionThirtyFiveQuarantinesLegacyProjectionWithoutChangingAuditFingerprints() = runBlocking {
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
                val legacyAuthority = requireNotNull(
                    store.readLearningObservationSourceAuthority(
                        learnerId = legacyCandidate.learnerId,
                        source = legacyCandidate.source,
                        sourceReferenceId = legacyCandidate.sourceReferenceId,
                    ),
                )
                assertNull(legacyAuthority.sourceFactId)

                val incremental = store.loadProjectionBatch(
                    projectionName = PROJECTION,
                    learnerId = legacyCandidate.learnerId,
                    limit = 10,
                )
                assertEquals(1L, incremental.ledgerHeadSequence)
                assertTrue(incremental.events.isEmpty())
                assertEquals(ProjectionBatchStopReason.CONFLICT, incremental.stopReason)
                assertEquals(1L, incremental.blockedAtSequence)

                val fullReplay = store.loadLearningLedger(legacyCandidate.learnerId)
                assertTrue(fullReplay.validPrefix.isEmpty())
                assertEquals(LearningLedgerReadStatus.CONFLICT, fullReplay.status)
                assertEquals(1L, fullReplay.blockedAtSequence)
                assertNull(
                    store.readCurrentLearnerSnapshot(
                        projectionName = PROJECTION,
                        learnerId = legacyCandidate.learnerId,
                    ),
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
                    LearningObservationCandidateStatus.MATERIALIZED,
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
                assertNull(
                    database.scalarNullableText(
                        """
                        SELECT source_fact_id
                        FROM learning_observation_source_authority
                        WHERE source_reference_id = '${legacyCandidate.sourceReferenceId}'
                        """.trimIndent(),
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
                    database.hasUniqueIndex(
                        "learning_observation_source_authority",
                        "index_learning_observation_source_authority_source_fact_id",
                    ),
                )
                assertTrue(
                    database.hasUniqueIndex(
                        "learning_observation_candidate",
                        "index_learning_observation_candidate_source_fact_id",
                    ),
                )
                assertTrue(
                    database.hasUniqueIndex(
                        "attributed_learning_observation_event",
                        "index_attributed_learning_observation_event_source_fact_id",
                    ),
                )
                assertTrue(
                    database.hasSourceFactForeignKey("learning_observation_source_authority"),
                )
                assertTrue(
                    database.hasSourceFactForeignKey("learning_observation_candidate"),
                )
                assertTrue(
                    database.hasSourceFactForeignKey("attributed_learning_observation_event"),
                )
                assertEquals(1, database.scalarInt("SELECT COUNT(*) FROM projection_outbox"))
                assertEquals(1, database.scalarInt("SELECT COUNT(*) FROM learning_sequence"))
                assertEquals(1, database.scalarInt("SELECT COUNT(*) FROM learning_event_identity"))
                assertEquals(
                    1,
                    database.scalarInt("SELECT COUNT(*) FROM attributed_learning_observation_event"),
                )
                AFFECTED_PROJECTION_TABLES.forEach { tableName ->
                    assertEquals(
                        "$tableName retained legacy projection pollution",
                        0,
                        database.projectionRowCount(tableName, legacyCandidate.learnerId),
                    )
                }
                assertEquals(
                    1,
                    database.projectionRowCount(
                        "learner_projection_snapshot",
                        UNAFFECTED_LEARNER,
                    ),
                )
                assertEquals(
                    1,
                    database.projectionRowCount(
                        "learner_knowledge_mastery_state",
                        UNAFFECTED_LEARNER,
                    ),
                )
                assertEquals(0, database.foreignKeyViolationCount())
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
        // Projection sentinel rows below intentionally omit unrelated source-ledger parents.
        // The migration must remove every affected row before Room re-enables FK validation.
        execSQL("PRAGMA foreign_keys = OFF")
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
            INSERT INTO knowledge_node(
                knowledge_node_id, stable_code, subject, display_name, taxonomy_version,
                created_at_epoch_millis
            ) VALUES(?, 'legacy.math', 'MATH', 'Legacy math', 'taxonomy-v1', ?)
            """.trimIndent(),
            arrayOf<Any?>(KNOWLEDGE_NODE_ID, 900L),
        )
        execSQL(
            """
            INSERT INTO practice_unit_knowledge_binding(
                binding_id, practice_unit_id, knowledge_node_id, basis_revision_id, strength,
                source_type, taxonomy_version, accepted_at_epoch_millis
            ) VALUES(?, ?, ?, ?, 1.0, 'VERIFIED', 'taxonomy-v1', ?)
            """.trimIndent(),
            arrayOf<Any?>(BINDING_ID, UNIT_ID, KNOWLEDGE_NODE_ID, REVISION_ID, 900L),
        )
        execSQL(
            """
            INSERT INTO learning_observation_source_authority(
                learner_id, source, source_reference_id, practice_unit_id, problem_revision_id,
                source_payload_fingerprint, verified_at_epoch_millis
            ) VALUES(?, ?, ?, ?, ?, 'legacy-authority-fingerprint', ?)
            """.trimIndent(),
            arrayOf<Any?>(
                candidate.learnerId,
                candidate.source.name,
                candidate.sourceReferenceId,
                UNIT_ID,
                REVISION_ID,
                950L,
            ),
        )
        execSQL(
            """
            INSERT INTO learning_observation_source_authority(
                learner_id, source, source_reference_id, practice_unit_id, problem_revision_id,
                source_payload_fingerprint, verified_at_epoch_millis
            ) VALUES(?, ?, 'legacy-choice-second', ?, ?, 'legacy-authority-second', ?)
            """.trimIndent(),
            arrayOf<Any?>(
                candidate.learnerId,
                candidate.source.name,
                UNIT_ID,
                REVISION_ID,
                951L,
            ),
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
        candidate.proposedAttributions.forEachIndexed { ordinal, attribution ->
            execSQL(
                """
                INSERT INTO learning_observation_candidate_attribution(
                    candidate_id, ordinal, binding_id, knowledge_node_id, weight,
                    basis_revision_id, taxonomy_version, role, certainty
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    candidate.candidateId,
                    ordinal,
                    attribution.bindingId,
                    attribution.knowledgeNodeId,
                    attribution.weight,
                    attribution.basisRevisionId,
                    attribution.taxonomyVersion,
                    attribution.role.name,
                    attribution.certainty.name,
                ),
            )
        }
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
        event.attributions.forEachIndexed { ordinal, attribution ->
            execSQL(
                """
                INSERT INTO learning_observation_event_attribution(
                    event_id, practice_unit_id, ordinal, binding_id, knowledge_node_id, weight,
                    basis_revision_id, taxonomy_version, role, certainty
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    event.eventId,
                    event.practiceUnitId,
                    ordinal,
                    attribution.bindingId,
                    attribution.knowledgeNodeId,
                    attribution.weight,
                    attribution.basisRevisionId,
                    attribution.taxonomyVersion,
                    attribution.role.name,
                    attribution.certainty.name,
                ),
            )
        }
        execSQL(
            """
            INSERT INTO learning_event_identity(event_id, event_kind)
            VALUES(?, 'ATTRIBUTED_LEARNING_OBSERVATION')
            """.trimIndent(),
            arrayOf<Any?>(event.eventId),
        )
        execSQL(
            """
            INSERT INTO learning_sequence(learner_id, last_allocated_sequence)
            VALUES(?, ?)
            """.trimIndent(),
            arrayOf<Any?>(event.learnerId, event.eventSequence),
        )
        execSQL(
            """
            INSERT INTO projection_outbox(
                outbox_id, learner_id, outbox_sequence, event_kind, event_id,
                canonical_fingerprint, status, created_at_epoch_millis
            ) VALUES(?, ?, ?, 'ATTRIBUTED_LEARNING_OBSERVATION', ?, ?, 'PENDING', ?)
            """.trimIndent(),
            arrayOf<Any?>(
                OUTBOX_ID,
                event.learnerId,
                event.eventSequence,
                event.eventId,
                eventFingerprint,
                event.confirmedAtEpochMillis,
            ),
        )
        execSQL(
            """
            INSERT INTO learner_projection_snapshot(
                projection_name, learner_id, state_version, checkpoint_sequence,
                known_ledger_head_sequence, projector_version, projected_at_epoch_millis,
                generated_at_epoch_millis, correction_watermark_epoch_millis, freshness,
                projection_status
            ) VALUES(?, ?, 1, ?, ?, 'legacy-projector', ?, ?, NULL, 'CURRENT', 'CURRENT')
            """.trimIndent(),
            arrayOf<Any?>(
                PROJECTION,
                event.learnerId,
                event.eventSequence,
                event.eventSequence,
                event.confirmedAtEpochMillis,
                event.confirmedAtEpochMillis,
            ),
        )
        execSQL(
            """
            INSERT INTO learner_knowledge_mastery_state(
                projection_name, learner_id, knowledge_node_id,
                probability_independent_correct, lower_bound_independent_correct, evidence_mass,
                last_independent_error_at_epoch_millis, last_independent_error_sequence, status,
                calibration_support, projector_version, checkpoint_sequence,
                last_evidence_at_epoch_millis, conflict_since_sequence
            ) VALUES(?, ?, ?, 0.75, 0.60, 0.25, NULL, NULL, 'LEARNING', 'UNKNOWN',
                'legacy-projector', ?, ?, NULL)
            """.trimIndent(),
            arrayOf<Any?>(
                PROJECTION,
                event.learnerId,
                KNOWLEDGE_NODE_ID,
                event.eventSequence,
                event.occurredAtEpochMillis,
            ),
        )
        execSQL(
            """
            INSERT INTO independent_correct_observation(
                projection_name, learner_id, knowledge_node_id, ordinal, item_family_id,
                study_day_epoch_day, is_study_day_trusted, occurred_at_epoch_millis,
                event_sequence, binding_id, evidence_weight, calibration_support,
                calibration_source_id, calibration_version, calibration_valid_from_epoch_millis,
                calibration_valid_until_epoch_millis
            ) VALUES(?, ?, ?, 0, 'legacy-family', 20000, 1, ?, ?, ?, 0.25, 'UNKNOWN',
                'legacy-calibration', 'legacy-v1', 0, 9223372036854775807)
            """.trimIndent(),
            arrayOf<Any?>(
                PROJECTION,
                event.learnerId,
                KNOWLEDGE_NODE_ID,
                event.occurredAtEpochMillis,
                event.eventSequence,
                BINDING_ID,
            ),
        )
        execSQL(
            """
            INSERT INTO learner_problem_memory_state(
                projection_name, learner_id, practice_unit_id, stability_days, difficulty,
                last_reviewed_at_epoch_millis, next_review_at_epoch_millis,
                independent_correct_count, assisted_correct_count, lapse_count,
                answer_reveal_count, last_lapse_at_epoch_millis, clock_anomaly_count,
                last_clock_anomaly_at_epoch_millis, last_attempt_id, projector_version,
                checkpoint_sequence
            ) VALUES(?, ?, ?, 1.0, 0.5, ?, ?, 0, 0, 0, 0, NULL, 0, NULL,
                'legacy-attempt', 'legacy-projector', ?)
            """.trimIndent(),
            arrayOf<Any?>(
                PROJECTION,
                event.learnerId,
                UNIT_ID,
                event.occurredAtEpochMillis,
                event.occurredAtEpochMillis + 86_400_000,
                event.eventSequence,
            ),
        )
        execSQL(
            """
            INSERT INTO applied_learning_observation_record(
                projection_name, learner_id, event_id, canonical_fingerprint, event_sequence
            ) VALUES(?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                PROJECTION,
                event.learnerId,
                event.eventId,
                eventFingerprint,
                event.eventSequence,
            ),
        )
        execSQL(
            """
            INSERT INTO applied_attempt_record(
                projection_name, learner_id, attempt_id, canonical_fingerprint,
                event_sequence, presentation_id, response_ordinal
            ) VALUES(?, ?, 'legacy-attempt', 'legacy-attempt-fingerprint', 1,
                'legacy-presentation', 1)
            """.trimIndent(),
            arrayOf<Any?>(PROJECTION, event.learnerId),
        )
        execSQL(
            """
            INSERT INTO applied_correction_record(
                projection_name, learner_id, correction_id, attempt_id,
                canonical_fingerprint, event_sequence
            ) VALUES(?, ?, 'legacy-correction', 'legacy-attempt',
                'legacy-correction-fingerprint', 1)
            """.trimIndent(),
            arrayOf<Any?>(PROJECTION, event.learnerId),
        )
        execSQL(
            """
            INSERT INTO applied_answer_reveal_record(
                projection_name, learner_id, outcome_id, presentation_id,
                canonical_fingerprint, event_sequence
            ) VALUES(?, ?, 'legacy-answer-reveal', 'legacy-presentation',
                'legacy-answer-reveal-fingerprint', 1)
            """.trimIndent(),
            arrayOf<Any?>(PROJECTION, event.learnerId),
        )
        execSQL(
            """
            INSERT INTO applied_tutor_answer_exposure_record(
                projection_name, learner_id, outcome_id, exposure_id,
                canonical_fingerprint, event_sequence
            ) VALUES(?, ?, 'legacy-tutor-exposure', 'legacy-exposure',
                'legacy-tutor-exposure-fingerprint', 1)
            """.trimIndent(),
            arrayOf<Any?>(PROJECTION, event.learnerId),
        )
        execSQL(
            """
            INSERT INTO presentation_projection_state(
                projection_name, learner_id, presentation_id, terminal_outcome_id,
                terminal_outcome, terminal_event_sequence, memory_projected,
                memory_projection_sequence, last_response_ordinal, state_version
            ) VALUES(?, ?, 'legacy-presentation', 'legacy-answer-reveal',
                'ANSWER_REVEALED', 1, 1, 1, 1, 1)
            """.trimIndent(),
            arrayOf<Any?>(PROJECTION, event.learnerId),
        )
        execSQL(
            """
            INSERT INTO projection_consumption(
                projection_name, learner_id, outbox_id, outbox_sequence, projector_version,
                consumed_at_epoch_millis
            ) VALUES(?, ?, ?, ?, 'legacy-projector', ?)
            """.trimIndent(),
            arrayOf<Any?>(
                PROJECTION,
                event.learnerId,
                OUTBOX_ID,
                event.eventSequence,
                event.confirmedAtEpochMillis,
            ),
        )
        execSQL(
            """
            INSERT INTO learner_projection_snapshot(
                projection_name, learner_id, state_version, checkpoint_sequence,
                known_ledger_head_sequence, projector_version, projected_at_epoch_millis,
                generated_at_epoch_millis, correction_watermark_epoch_millis, freshness,
                projection_status
            ) VALUES(?, ?, 1, 0, 0, 'unaffected-projector', 900, 900, NULL,
                'CURRENT', 'CURRENT')
            """.trimIndent(),
            arrayOf<Any?>(UNAFFECTED_PROJECTION, UNAFFECTED_LEARNER),
        )
        execSQL(
            """
            INSERT INTO learner_knowledge_mastery_state(
                projection_name, learner_id, knowledge_node_id,
                probability_independent_correct, lower_bound_independent_correct, evidence_mass,
                last_independent_error_at_epoch_millis, last_independent_error_sequence, status,
                calibration_support, projector_version, checkpoint_sequence,
                last_evidence_at_epoch_millis, conflict_since_sequence
            ) VALUES(?, ?, ?, 0.50, 0.40, 0.0, NULL, NULL, 'UNKNOWN', 'UNKNOWN',
                'unaffected-projector', 0, NULL, NULL)
            """.trimIndent(),
            arrayOf<Any?>(UNAFFECTED_PROJECTION, UNAFFECTED_LEARNER, KNOWLEDGE_NODE_ID),
        )
        AFFECTED_PROJECTION_TABLES.forEach { tableName ->
            check(projectionRowCount(tableName, event.learnerId) == 1) {
                "Missing affected projection sentinel in $tableName"
            }
        }
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
        proposedAttributions = listOf(
            LearningObservationKnowledgeAttribution(
                bindingId = BINDING_ID,
                knowledgeNodeId = KNOWLEDGE_NODE_ID,
                weight = 1.0,
                basisRevisionId = REVISION_ID,
                taxonomyVersion = "taxonomy-v1",
                role = EvidenceAttributionRole.PRIMARY,
                certainty = EvidenceAttributionCertainty.DIRECT,
            ),
        ),
        occurredAtEpochMillis = 1_000,
        modelVersion = "legacy-model",
        evidenceLocator = "legacy:choice",
        status = LearningObservationCandidateStatus.MATERIALIZED,
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
        evidenceLevel = candidate.evidenceLevel,
        evidenceWeight = candidate.evidenceWeight,
        independence = candidate.independence,
        attributions = candidate.proposedAttributions,
        occurredAtEpochMillis = candidate.occurredAtEpochMillis,
        confirmedAtEpochMillis = 1_200,
        modelVersion = candidate.modelVersion,
        evidenceLocator = candidate.evidenceLocator,
        eventSequence = 1,
    )

    private fun SQLiteDatabase.hasUniqueIndex(table: String, expected: String): Boolean =
        rawQuery("PRAGMA index_list(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == expected) {
                    return cursor.getInt(uniqueIndex) == 1
                }
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

    private fun SQLiteDatabase.projectionRowCount(tableName: String, learnerId: String): Int =
        scalarInt("SELECT COUNT(*) FROM `$tableName` WHERE learner_id = '$learnerId'")

    private fun SQLiteDatabase.foreignKeyViolationCount(): Int =
        rawQuery("PRAGMA foreign_key_check", null).use { cursor -> cursor.count }

    private companion object {
        const val PROBLEM_ID = "legacy-problem"
        const val REVISION_ID = "legacy-revision"
        const val UNIT_ID = "legacy-unit"
        const val KNOWLEDGE_NODE_ID = "legacy-knowledge"
        const val BINDING_ID = "legacy-binding"
        const val PROJECTION = "legacy-projection"
        const val OUTBOX_ID = "legacy-observation-outbox"
        const val UNAFFECTED_PROJECTION = "unaffected-projection"
        const val UNAFFECTED_LEARNER = "unaffected-learner"
        val AFFECTED_PROJECTION_TABLES = listOf(
            "independent_correct_observation",
            "applied_learning_observation_record",
            "applied_tutor_answer_exposure_record",
            "learner_problem_memory_state",
            "learner_knowledge_mastery_state",
            "applied_attempt_record",
            "applied_correction_record",
            "applied_answer_reveal_record",
            "presentation_projection_state",
            "projection_consumption",
            "learner_projection_snapshot",
        )
    }
}
