package com.tingyun.smartmistakebook.core.database

import androidx.room3.executeSQL
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.dao.EVENT_KIND_LEARNING_OBSERVATION
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.AdmittedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedTutorProblemIdentity
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewReason
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewStatus
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidate
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidateStatus
import com.tingyun.smartmistakebook.core.model.LearningObservationDirection
import com.tingyun.smartmistakebook.core.model.LearningObservationEvidenceLevel
import com.tingyun.smartmistakebook.core.model.LearningObservationIndependence
import com.tingyun.smartmistakebook.core.model.LearningObservationKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningObservationDatabaseInstrumentedTest {
    private lateinit var store: RoomStudyDatabase
    private var databaseClockEpochMillis: Long = NOW

    @Before
    fun setUp() = runBlocking {
        databaseClockEpochMillis = NOW
        store = StudyDatabaseFactory.openInMemory(
            context = ApplicationProvider.getApplicationContext(),
            clock = { databaseClockEpochMillis },
        )
        store.seedFixture(seed())
        store.saveAssessmentEvidenceSnapshot(assessmentSnapshot())
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun candidateSubmissionIsIdempotentAndStatusTransitionUsesCas() = runBlocking {
        val candidate = candidate()

        assertTrue(submitCandidate(candidate).created)
        assertFalse(submitCandidate(candidate).created)
        registerAuthority(candidate)

        val transition = LearningObservationCandidateStatusChangeCommand(
            candidateId = candidate.candidateId,
            expectedStatus = LearningObservationCandidateStatus.PENDING_CONFIRMATION,
            newStatus = LearningObservationCandidateStatus.READY,
            expectedRetryCount = 0,
            incrementRetry = false,
            updatedAtEpochMillis = NOW + 1,
        )
        val transitioned = store.compareAndSetLearningObservationCandidateStatus(transition)
        val replay = store.compareAndSetLearningObservationCandidateStatus(transition)
        val stale = store.compareAndSetLearningObservationCandidateStatus(
            LearningObservationCandidateStatusChangeCommand(
                candidateId = candidate.candidateId,
                expectedStatus = LearningObservationCandidateStatus.PENDING_CONFIRMATION,
                newStatus = LearningObservationCandidateStatus.REJECTED,
                expectedRetryCount = 0,
                incrementRetry = false,
                updatedAtEpochMillis = NOW + 2,
            ),
        )

        assertTrue(transitioned.updated)
        assertEquals(LearningObservationCandidateStatus.READY, transitioned.candidate.status)
        assertFalse(replay.updated)
        assertEquals(transitioned.candidate, replay.candidate)
        assertFalse(stale.updated)
        assertEquals(LearningObservationCandidateStatus.READY, stale.candidate.status)
    }

    @Test
    fun directReadyOrMaterializedSubmissionIsRejectedEvenWithSourceAuthority() = runBlocking {
        val initial = candidate(candidateId = "candidate-initial-state-gate")
        val directReady = initial.copy(status = LearningObservationCandidateStatus.READY)
        val directMaterialized =
            initial.copy(status = LearningObservationCandidateStatus.MATERIALIZED)

        assertIllegalArgument { submitCandidate(directReady) }
        assertIllegalArgument { submitCandidate(directMaterialized) }
        assertNull(store.readLearningObservationCandidate(initial.candidateId))

        registerAuthority(initial)
        assertIllegalArgument { submitCandidate(directReady) }
        assertIllegalArgument {
            submitCandidate(
                initial.copy(status = LearningObservationCandidateStatus.REJECTED),
            )
        }

        val ready = ready(initial)
        assertEquals(LearningObservationCandidateStatus.READY, ready.status)
        val materialized = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = ready.candidateId,
                eventId = "observation-initial-state-gate",
                confirmedAtEpochMillis = NOW + 2,
            ),
        )

        assertNotNull(materialized.event)
        assertEquals(
            LearningObservationCandidateStatus.MATERIALIZED,
            store.readLearningObservationCandidate(initial.candidateId)?.status,
        )
    }

    @Test
    fun publicCasCannotSetMaterializedOrTransitionOutOfTerminalStates() = runBlocking {
        val ready = ready(candidate(candidateId = "candidate-public-materialized-cas"))
        LearningObservationCandidateStatus.entries.forEach { expectedStatus ->
            assertIllegalArgument {
                store.compareAndSetLearningObservationCandidateStatus(
                    LearningObservationCandidateStatusChangeCommand(
                        candidateId = ready.candidateId,
                        expectedStatus = expectedStatus,
                        newStatus = LearningObservationCandidateStatus.MATERIALIZED,
                        expectedRetryCount = ready.retryCount,
                        incrementRetry = false,
                        updatedAtEpochMillis = NOW + 2,
                    ),
                )
            }
        }
        assertEquals(
            LearningObservationCandidateStatus.READY,
            store.readLearningObservationCandidate(ready.candidateId)?.status,
        )

        val materialized = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = ready.candidateId,
                eventId = "observation-public-materialized-cas",
                confirmedAtEpochMillis = NOW + 2,
            ),
        )
        assertNotNull(materialized.event)
        assertEquals(
            LearningObservationCandidateStatus.MATERIALIZED,
            store.readLearningObservationCandidate(ready.candidateId)?.status,
        )
        LearningObservationCandidateStatus.entries.forEach { nextStatus ->
            assertIllegalArgument {
                store.compareAndSetLearningObservationCandidateStatus(
                    LearningObservationCandidateStatusChangeCommand(
                        candidateId = ready.candidateId,
                        expectedStatus = LearningObservationCandidateStatus.MATERIALIZED,
                        newStatus = nextStatus,
                        expectedRetryCount = ready.retryCount,
                        incrementRetry = false,
                        updatedAtEpochMillis = NOW + 3,
                    ),
                )
            }
        }

        val rejectable = candidate(candidateId = "candidate-rejected-terminal")
        registerAuthority(rejectable)
        submitCandidate(rejectable)
        val rejected = store.compareAndSetLearningObservationCandidateStatus(
            LearningObservationCandidateStatusChangeCommand(
                candidateId = rejectable.candidateId,
                expectedStatus = rejectable.status,
                newStatus = LearningObservationCandidateStatus.REJECTED,
                expectedRetryCount = rejectable.retryCount,
                incrementRetry = false,
                updatedAtEpochMillis = NOW + 1,
            ),
        ).candidate
        assertEquals(LearningObservationCandidateStatus.REJECTED, rejected.status)
        LearningObservationCandidateStatus.entries.forEach { nextStatus ->
            assertIllegalArgument {
                store.compareAndSetLearningObservationCandidateStatus(
                    LearningObservationCandidateStatusChangeCommand(
                        candidateId = rejected.candidateId,
                        expectedStatus = LearningObservationCandidateStatus.REJECTED,
                        newStatus = nextStatus,
                        expectedRetryCount = rejected.retryCount,
                        incrementRetry = false,
                        updatedAtEpochMillis = NOW + 2,
                    ),
                )
            }
        }
    }

    @Test
    fun provenanceRejectsChangedPayloadAndDifferentCandidateIdentity() = runBlocking {
        val original = candidate(
            candidateId = "candidate-provenance",
            sourceReferenceId = "choice-one-real-source",
        )
        val alternateSourceFact = candidate(
            candidateId = "candidate-provenance-alternate-fact",
            sourceReferenceId = "choice-alternate-source",
        )
        persistTutorModelFact(alternateSourceFact)
        registerAuthority(original)
        submitCandidate(original)

        assertImmutableConflict {
            submitCandidate(original.copy(evidenceWeight = 0.1))
        }
        assertImmutableConflict {
            submitCandidate(
                candidate(
                    candidateId = "candidate-provenance-alias",
                    sourceReferenceId = original.sourceReferenceId,
                    sourceFactId = original.sourceFactId,
                    occurredAtEpochMillis = original.occurredAtEpochMillis,
                ),
            )
        }
        assertIllegalArgument {
            store.submitLearningObservationCandidate(
                original.copy(sourceFactId = alternateSourceFact.sourceFactId),
            )
        }

        assertEquals(
            original,
            store.readLearningObservationCandidate(original.candidateId),
        )
        assertNull(store.readLearningObservationCandidate("candidate-provenance-alias"))
    }

    @Test
    fun canonicalSourceFactCanAuthorizeOnlyOneAuthorityAndOneCandidate() = runBlocking {
        val first = candidate(candidateId = "candidate-single-fact-first")
        submitCandidate(first)
        val second = candidate(
            candidateId = "candidate-single-fact-second",
            sourceReferenceId = first.sourceReferenceId,
            sourceFactId = first.sourceFactId,
            occurredAtEpochMillis = first.occurredAtEpochMillis,
        )

        assertImmutableConflict {
            store.registerLearningObservationSourceAuthority(
                authority(second, practiceUnitId = OTHER_UNIT),
            )
        }
        assertImmutableConflict {
            store.submitLearningObservationCandidate(second)
        }

        assertNull(store.readLearningObservationCandidate(second.candidateId))
        val ledger = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(0L, ledger.ledgerHeadSequence)
        assertTrue(ledger.events.isEmpty())
        assertEquals(ProjectionBatchStopReason.END_OF_LEDGER, ledger.stopReason)
    }

    @Test
    fun mathSourceFactCannotAuthorizeChemistryProblem() = runBlocking {
        val chemistryCandidate = candidate(
            candidateId = "candidate-cross-subject-authority",
            practiceUnitId = CHEMISTRY_UNIT,
            problemRevisionId = CHEMISTRY_REVISION,
            bindingId = "binding-chemistry",
            knowledgeNodeId = "knowledge-chemistry",
            sourceSubject = SubjectKind.MATH,
        )
        persistTutorModelFact(chemistryCandidate, sourceSubject = SubjectKind.MATH)

        listOf(
            authority(chemistryCandidate).copy(learnerId = "other-learner"),
            authority(chemistryCandidate).copy(
                source = LearningObservationSource.TUTOR_VISUAL_TARGET,
            ),
            authority(chemistryCandidate).copy(
                sourceReferenceId = "caller-selected-source-reference",
            ),
        ).forEach { mismatchedAuthority ->
            assertIllegalArgument {
                store.registerLearningObservationSourceAuthority(mismatchedAuthority)
            }
        }
        assertIllegalArgument {
            store.registerLearningObservationSourceAuthority(authority(chemistryCandidate))
        }
        assertNull(
            store.readLearningObservationSourceAuthority(
                learnerId = chemistryCandidate.learnerId,
                source = chemistryCandidate.source,
                sourceReferenceId = chemistryCandidate.sourceReferenceId,
            ),
        )
        assertSourceAuthorityFailure {
            store.submitLearningObservationCandidate(chemistryCandidate)
        }
        val ledger = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(0L, ledger.ledgerHeadSequence)
        assertTrue(ledger.events.isEmpty())
        assertEquals(ProjectionBatchStopReason.END_OF_LEDGER, ledger.stopReason)
    }

    @Test
    fun sourceFactCannotAuthorizeDifferentProblemWithSameSubject() = runBlocking {
        val candidate = candidate(candidateId = "candidate-same-subject-cross-problem")
        persistTutorModelFact(candidate)

        assertIllegalArgument {
            store.registerLearningObservationSourceAuthority(
                authority(
                    candidate,
                    practiceUnitId = OTHER_MATH_UNIT,
                    problemRevisionId = OTHER_MATH_REVISION,
                ),
            )
        }
        assertNull(
            store.readLearningObservationSourceAuthority(
                learnerId = candidate.learnerId,
                source = candidate.source,
                sourceReferenceId = candidate.sourceReferenceId,
            ),
        )
    }

    @Test
    fun sourceFactFromDifferentTutorTurnCannotCreateFirstAuthority() = runBlocking {
        val firstTurn = candidate(candidateId = "candidate-authority-first-turn")
        val secondTurn = candidate(candidateId = "candidate-authority-second-turn")
        persistTutorModelFact(firstTurn)
        persistTutorModelFact(secondTurn)
        val secondFixture = tutorModelFactFixture(
            candidate = secondTurn,
            sourceSubject = subjectForProblemRevision(
                requireNotNull(secondTurn.problemRevisionId),
            ),
        )
        store.database.withWriteTransaction {
            executeSQL(
                """
                UPDATE learning_observation_source_fact
                SET conversation_id = '${secondFixture.conversationId}',
                    turn_receipt_id = '${secondFixture.turnReceiptId}'
                WHERE source_fact_id = '${firstTurn.sourceFactId}'
                """.trimIndent(),
            )
        }

        assertIllegalArgument {
            store.registerLearningObservationSourceAuthority(authority(firstTurn))
        }
        assertNull(
            store.readLearningObservationSourceAuthority(
                learnerId = firstTurn.learnerId,
                source = firstTurn.source,
                sourceReferenceId = firstTurn.sourceReferenceId,
            ),
        )
    }

    @Test
    fun sameSubjectWrongCapturedSessionCannotCreateFirstAuthority() = runBlocking {
        val candidate = candidate(candidateId = "candidate-wrong-captured-session")
        persistTutorModelFact(candidate)
        replaceCapturedResponseWithWrongSameSubjectSession(candidate)

        assertIllegalArgument {
            store.registerLearningObservationSourceAuthority(authority(candidate))
        }
        assertNull(
            store.database.learningObservationDao()
                .findSourceFactProof(requireNotNull(candidate.sourceFactId)),
        )
    }

    @Test
    fun sourceAndEvidenceRequestKindMustMatchExactly() = runBlocking {
        val candidate = candidate(candidateId = "candidate-cross-kind")
        persistTutorModelFact(candidate)
        store.database.withWriteTransaction {
            executeSQL(
                """
                UPDATE tutor_evidence_request
                SET kind = 'FREE_RESPONSE'
                WHERE evidence_request_id = '${candidate.sourceReferenceId}'
                """.trimIndent(),
            )
        }

        assertIllegalArgument {
            store.registerLearningObservationSourceAuthority(authority(candidate))
        }
        assertNull(
            store.database.learningObservationDao()
                .findSourceFactProof(requireNotNull(candidate.sourceFactId)),
        )
    }

    @Test
    fun commitBeforeResponseCreatesExactCapturedProofForCommittedTarget() = runBlocking {
        val candidate = candidate(candidateId = "candidate-different-fingerprint-domains")
        persistTutorModelFact(candidate)

        val result = store.registerLearningObservationSourceAuthority(authority(candidate))
        val proof = requireNotNull(
            store.database.learningObservationDao()
                .findSourceFactProof(requireNotNull(candidate.sourceFactId)),
        )

        assertTrue(result.created)
        assertEquals(candidate.sourceFactId, result.authority.sourceFactId)
        assertEquals("COMMITTED_PRACTICE_UNIT", proof.targetKind)
        assertEquals("MISTAKE_COLLECTION", proof.targetDatabase)
        assertEquals(candidate.practiceUnitId, proof.targetId)
        assertEquals(candidate.problemRevisionId, proof.targetVersion)
        assertTrue(proof.targetCreatedAtEpochMillis < candidate.occurredAtEpochMillis)
        assertTrue(proof.attestedAtEpochMillis >= candidate.occurredAtEpochMillis)
        assertTrue(LearningObservationSourceFactProofFingerprint.isValid(proof))
        store.database.withWriteTransaction {
            executeSQL(
                """
                DELETE FROM tutor_turn_response
                WHERE evidence_request_id = '${candidate.sourceReferenceId}'
                """.trimIndent(),
            )
        }
        assertFalse(
            store.registerLearningObservationSourceAuthority(authority(candidate)).created,
        )
    }

    @Test
    fun sourceAuthorityIsImmutableAndRequiredForSubmitReadyAndMaterialization() = runBlocking {
        val authorized = candidate(
            candidateId = "candidate-authority",
            sourceReferenceId = "choice-authority",
        )
        val authority = authority(authorized)
        assertTrue(registerAuthorityResult(authorized).created)
        assertFalse(store.registerLearningObservationSourceAuthority(authority).created)
        assertImmutableConflict {
            store.registerLearningObservationSourceAuthority(
                authority.copy(practiceUnitId = OTHER_UNIT),
            )
        }

        val missing = candidate(
            candidateId = "candidate-authority-missing",
            sourceReferenceId = "choice-authority-missing",
        )
        persistTutorModelFact(missing)
        assertSourceAuthorityFailure {
            store.submitLearningObservationCandidate(missing)
        }
        assertNull(store.readLearningObservationCandidate(missing.candidateId))

        val readyGate = candidate(candidateId = "candidate-authority-ready-gate")
        submitCandidate(readyGate)
        clearAuthoritySourceFact(readyGate)
        assertSourceAuthorityFailure { markReady(readyGate) }

        val materializationGate = ready(
            candidate(candidateId = "candidate-authority-materialization-gate"),
        )
        clearAuthoritySourceFact(materializationGate)
        val missingResult = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = materializationGate.candidateId,
                eventId = "observation-authority-materialization-gate",
                confirmedAtEpochMillis = NOW + 2,
            ),
        )
        assertEquals(
            LearningEvidenceReviewReason.SOURCE_AUTHORITY_MISSING,
            requireNotNull(missingResult.reviewCase).reason,
        )

        val legacyAuthority = candidate(
            candidateId = "candidate-legacy-null-authority",
        )
        persistTutorModelFact(legacyAuthority)
        insertLegacyNullSourceFactAuthority(legacyAuthority)
        assertSourceAuthorityFailure {
            store.submitLearningObservationCandidate(legacyAuthority)
        }
        assertIllegalArgument {
            store.registerLearningObservationSourceAuthority(
                authority(legacyAuthority).copy(sourceFactId = null),
            )
        }

        val ledger = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(0L, ledger.ledgerHeadSequence)
        assertTrue(ledger.events.isEmpty())
        assertEquals(ProjectionBatchStopReason.END_OF_LEDGER, ledger.stopReason)
    }

    @Test
    fun successfulMaterializationResolvesEarlierNotReadyReview() = runBlocking {
        val candidate = candidate(candidateId = "candidate-review-resolution")
        submitCandidate(candidate)
        registerAuthority(candidate)
        val command = MaterializeLearningObservationCommand(
            candidateId = candidate.candidateId,
            eventId = "observation-review-resolution",
            confirmedAtEpochMillis = NOW + 2,
        )

        val blocked = store.materializeLearningObservation(command)
        val openCase = requireNotNull(blocked.reviewCase)
        assertEquals(LearningEvidenceReviewReason.CANDIDATE_NOT_READY, openCase.reason)
        assertEquals(LearningEvidenceReviewStatus.OPEN, openCase.status)

        markReady(candidate)
        assertNotNull(store.materializeLearningObservation(command).event)

        val resolved = requireNotNull(
            store.readLearningEvidenceReviewCase(openCase.reviewCaseId),
        )
        assertEquals(LearningEvidenceReviewStatus.RESOLVED, resolved.status)
        assertEquals(command.confirmedAtEpochMillis, resolved.resolvedAtEpochMillis)
    }

    @Test
    fun attemptAndObservationShareOneGlobalLearnerSequence() = runBlocking {
        val firstAttempt = store.recordAttempt(
            attemptCommand(
                submissionId = "submission-before-observation",
                attemptId = "attempt-before-observation",
                presentationId = "presentation-before-observation",
                occurredAtEpochMillis = NOW,
            ),
        )
        val observation = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = ready(
                    candidate(candidateId = "candidate-between-attempts"),
                ).candidateId,
                eventId = "observation-between-attempts",
                confirmedAtEpochMillis = NOW + 2,
            ),
        )
        val secondAttempt = store.recordAttempt(
            attemptCommand(
                submissionId = "submission-after-observation",
                attemptId = "attempt-after-observation",
                presentationId = "presentation-after-observation",
                occurredAtEpochMillis = NOW + 3,
            ),
        )
        val batch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)

        assertEquals(1L, firstAttempt.attempt.eventSequence)
        assertEquals(2L, requireNotNull(observation.event).eventSequence)
        assertEquals(3L, secondAttempt.attempt.eventSequence)
        assertEquals(listOf(1L, 2L, 3L), batch.events.map { it.event.eventSequence })
        assertTrue(batch.events[0].event is Attempt)
        assertTrue(batch.events[1].event is AdmittedLearningObservationEvent)
        assertTrue(batch.events[2].event is Attempt)
        assertEquals(3L, batch.ledgerHeadSequence)
    }

    @Test
    fun legacyAttemptIdentityRejectsObservationWithoutConsumingSequence() = runBlocking {
        val sharedEventId = "shared-attempt-observation-id"
        store.recordAttempt(
            attemptCommand(
                submissionId = "submission-before-observation-collision",
                attemptId = sharedEventId,
                presentationId = "presentation-before-observation-collision",
                occurredAtEpochMillis = NOW,
            ),
        )
        val readyCandidate = ready(
            candidate(candidateId = "candidate-after-attempt-collision"),
        )

        assertImmutableConflict {
            store.materializeLearningObservation(
                MaterializeLearningObservationCommand(
                    candidateId = readyCandidate.candidateId,
                    eventId = sharedEventId,
                    confirmedAtEpochMillis = NOW + 2,
                ),
            )
        }

        assertEquals(
            LearningObservationCandidateStatus.READY,
            store.readLearningObservationCandidate(readyCandidate.candidateId)?.status,
        )
        val batch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(1L, batch.ledgerHeadSequence)
        assertEquals(listOf(sharedEventId), batch.events.map { it.event.ledgerEventId })
        assertTrue(batch.events.single().event is Attempt)
    }

    @Test
    fun observationIdentityRejectsAttemptWithoutConsumingSequence() = runBlocking {
        val sharedEventId = "shared-observation-attempt-id"
        val readyCandidate = ready(
            candidate(candidateId = "candidate-before-attempt-collision"),
        )
        val observation = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = readyCandidate.candidateId,
                eventId = sharedEventId,
                confirmedAtEpochMillis = NOW + 2,
            ),
        )

        assertImmutableConflict {
            store.recordAttempt(
                attemptCommand(
                    submissionId = "submission-after-observation-collision",
                    attemptId = sharedEventId,
                    presentationId = "presentation-after-observation-collision",
                    occurredAtEpochMillis = NOW + 3,
                ),
            )
        }

        assertEquals(
            LearningObservationCandidateStatus.MATERIALIZED,
            store.readLearningObservationCandidate(readyCandidate.candidateId)?.status,
        )
        val batch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(1L, requireNotNull(observation.event).eventSequence)
        assertEquals(1L, batch.ledgerHeadSequence)
        assertEquals(listOf(sharedEventId), batch.events.map { it.event.ledgerEventId })
        assertTrue(batch.events.single().event is AdmittedLearningObservationEvent)
    }

    @Test
    fun concurrentAttemptAndObservationCanClaimSharedIdentityOnlyOnce() = runBlocking {
        val sharedEventId = "shared-concurrent-ledger-id"
        val readyCandidate = ready(
            candidate(candidateId = "candidate-concurrent-identity"),
        )

        val outcomes = coroutineScope {
            listOf(
                async(Dispatchers.Default) {
                    try {
                        store.recordAttempt(
                            attemptCommand(
                                submissionId = "submission-concurrent-identity",
                                attemptId = sharedEventId,
                                presentationId = "presentation-concurrent-identity",
                                occurredAtEpochMillis = NOW + 2,
                            ),
                        )
                        "attempt-created"
                    } catch (_: ImmutablePayloadConflictException) {
                        "attempt-conflict"
                    }
                },
                async(Dispatchers.Default) {
                    try {
                        store.materializeLearningObservation(
                            MaterializeLearningObservationCommand(
                                candidateId = readyCandidate.candidateId,
                                eventId = sharedEventId,
                                confirmedAtEpochMillis = NOW + 2,
                            ),
                        )
                        "observation-created"
                    } catch (_: ImmutablePayloadConflictException) {
                        "observation-conflict"
                    }
                },
            ).awaitAll()
        }

        assertTrue(
            outcomes == listOf("attempt-created", "observation-conflict") ||
                outcomes == listOf("attempt-conflict", "observation-created"),
        )
        val batch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(1L, batch.ledgerHeadSequence)
        assertEquals(listOf(1L), batch.events.map { it.event.eventSequence })
        assertEquals(listOf(sharedEventId), batch.events.map { it.event.ledgerEventId })
        val expectedCandidateStatus = if (outcomes.first() == "attempt-created") {
            LearningObservationCandidateStatus.READY
        } else {
            LearningObservationCandidateStatus.MATERIALIZED
        }
        assertEquals(
            expectedCandidateStatus,
            store.readLearningObservationCandidate(readyCandidate.candidateId)?.status,
        )
    }

    @Test
    fun materializationRetryReusesOneGlobalSequenceAndOneOutboxRow() = runBlocking {
        val candidate = ready(candidate())
        val command = MaterializeLearningObservationCommand(
            candidateId = candidate.candidateId,
            eventId = "observation-1",
            confirmedAtEpochMillis = NOW + 2,
        )

        val first = store.materializeLearningObservation(command)
        val replay = store.materializeLearningObservation(command)
        val batch = store.loadProjectionBatch("learning-observation-test", LEARNER, 10)
        val event = requireNotNull(first.event)
        val admission = requireNotNull(
            store.database.learningObservationDao().findEventAdmission(event.eventId),
        )
        val proof = requireNotNull(
            store.database.learningObservationDao()
                .findSourceFactProof(requireNotNull(event.sourceFactId)),
        )

        assertTrue(first.created)
        assertFalse(replay.created)
        assertEquals(first.event, replay.event)
        assertEquals(1L, event.eventSequence)
        assertEquals(1, batch.events.size)
        assertTrue(batch.events.single().event is AdmittedLearningObservationEvent)
        assertEquals(
            LearningLedgerFingerprint.learningObservation(event),
            admission.rawEventCanonicalFingerprint,
        )
        assertEquals(proof.proofFingerprint, admission.sourceFactProofFingerprint)
        assertEquals(
            LearningLedgerFingerprint.learningObservationAdmission(
                admission.rawEventCanonicalFingerprint,
                admission.sourceFactProofFingerprint,
                admission.policyVersion,
            ),
            admission.admissionFingerprint,
        )
        assertEquals(EVENT_KIND_LEARNING_OBSERVATION, batch.events.single().outbox.eventKind)
        assertEquals(1L, batch.ledgerHeadSequence)
    }

    @Test
    fun materializationReplayRejectsPersistedEventEvidenceUpgrade() = runBlocking {
        val candidate = ready(candidate(candidateId = "candidate-replay-upgrade"))
        val command = MaterializeLearningObservationCommand(
            candidateId = candidate.candidateId,
            eventId = "observation-replay-upgrade",
            confirmedAtEpochMillis = NOW + 2,
        )
        val first = store.materializeLearningObservation(command)
        assertEquals(
            LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
            requireNotNull(first.event).evidenceLevel,
        )

        store.database.withWriteTransaction {
            executeSQL(
                """
                UPDATE attributed_learning_observation_event
                SET evidence_level = 'HIGH_CONFIDENCE'
                WHERE event_id = '${command.eventId}'
                """.trimIndent(),
            )
        }

        val replay = store.materializeLearningObservation(command)
        assertNull(replay.event)
        assertEquals(
            LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED,
            requireNotNull(replay.reviewCase).reason,
        )
        assertEquals(
            LearningObservationCandidateStatus.MATERIALIZED,
            store.readLearningObservationCandidate(candidate.candidateId)?.status,
        )
        assertEquals(1L, store.loadProjectionBatch(PROJECTION, LEARNER, 10).ledgerHeadSequence)
    }

    @Test
    fun subjectConflictCreatesReviewBeforeSequenceAllocation() = runBlocking {
        val conflicting = ready(
            candidate(
                candidateId = "candidate-conflict",
                bindingId = "binding-physics",
                knowledgeNodeId = "knowledge-physics",
            ),
        )
        val conflict = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = conflicting.candidateId,
                eventId = "observation-conflict",
                confirmedAtEpochMillis = NOW + 2,
            ),
        )

        assertNull(conflict.event)
        assertNotNull(conflict.reviewCase)

        val valid = ready(candidate(candidateId = "candidate-valid"))
        val materialized = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = valid.candidateId,
                eventId = "observation-valid",
                confirmedAtEpochMillis = NOW + 3,
            ),
        )
        assertEquals(1L, requireNotNull(materialized.event).eventSequence)
    }

    @Test
    fun lowConfidenceCandidateNeverReceivesLedgerSequence() = runBlocking {
        val lowConfidence = ready(
            candidate(
                candidateId = "candidate-low",
                evidenceLevel = LearningObservationEvidenceLevel.LOW_CONFIDENCE,
            ),
        )

        val result = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = lowConfidence.candidateId,
                eventId = "observation-low",
                confirmedAtEpochMillis = NOW + 2,
            ),
        )
        val batch = store.loadProjectionBatch("learning-observation-test", LEARNER, 10)

        assertNull(result.event)
        assertNotNull(result.reviewCase)
        assertEquals(0L, batch.ledgerHeadSequence)
        assertTrue(batch.events.isEmpty())

        val reviewedMedium = ready(
            candidate(candidateId = "candidate-reviewed-medium"),
        )
        val materialized = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = reviewedMedium.candidateId,
                eventId = "observation-reviewed-medium",
                confirmedAtEpochMillis = NOW + 3,
            ),
        )
        val mediumEvent = requireNotNull(materialized.event)
        val afterReview = store.loadProjectionBatch("learning-observation-test", LEARNER, 10)

        assertEquals(
            LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
            mediumEvent.evidenceLevel,
        )
        assertEquals(reviewedMedium.sourceFactId, mediumEvent.sourceFactId)
        assertEquals(1L, mediumEvent.eventSequence)
        assertEquals(
            listOf(mediumEvent),
            afterReview.events.map { (it.event as AdmittedLearningObservationEvent).observation },
        )
    }

    @Test
    fun canonicalSourceFactGateRejectsNullMissingScopeMismatchAndStrongModelEvidence() =
        runBlocking {
            val canonical = candidate(candidateId = "candidate-canonical")
            persistTutorModelFact(canonical)

            listOf(
                candidate(
                    candidateId = "candidate-null-fact",
                    sourceFactId = null,
                ),
                candidate(
                    candidateId = "candidate-missing-fact",
                    sourceFactId = "source-fact-missing",
                ),
                candidate(
                    candidateId = "candidate-wrong-learner",
                    learnerId = "other-learner",
                    sourceFactId = canonical.sourceFactId,
                ),
                candidate(
                    candidateId = "candidate-wrong-time",
                    sourceFactId = canonical.sourceFactId,
                    occurredAtEpochMillis = canonical.occurredAtEpochMillis + 1,
                ),
                canonical.copy(
                    candidateId = "candidate-wrong-source-reference",
                    sourceReferenceId = "caller-selected-source-reference",
                ),
                candidate(
                    candidateId = "candidate-strong-model",
                    sourceFactId = canonical.sourceFactId,
                    evidenceLevel = LearningObservationEvidenceLevel.HIGH_CONFIDENCE,
                ),
            ).forEach { rejected ->
                assertIllegalArgument {
                    store.submitLearningObservationCandidate(rejected)
                }
                assertNull(store.readLearningObservationCandidate(rejected.candidateId))
            }

            val wrongSource = canonical.copy(
                candidateId = "candidate-wrong-source",
                source = LearningObservationSource.TUTOR_VISUAL_TARGET,
                sourceReferenceId = "visual-wrong-source",
            )
            assertIllegalArgument {
                store.submitLearningObservationCandidate(wrongSource)
            }
            assertNull(store.readLearningObservationCandidate(wrongSource.candidateId))
            assertEquals(0L, store.loadProjectionBatch(PROJECTION, LEARNER, 10).ledgerHeadSequence)
        }

    @Test
    fun materializationRevalidatesPersistedEvidenceAndSameTimeSourceFactIdentity() = runBlocking {
        val elevated = ready(candidate(candidateId = "candidate-elevated-after-submit"))
        val elevatedFingerprint = LearningLedgerFingerprint.learningObservationCandidate(
            elevated.copy(evidenceLevel = LearningObservationEvidenceLevel.HIGH_CONFIDENCE),
        )
        store.database.withWriteTransaction {
            executeSQL(
                """
                UPDATE learning_observation_candidate
                SET evidence_level = 'HIGH_CONFIDENCE',
                    payload_fingerprint = '$elevatedFingerprint'
                WHERE candidate_id = '${elevated.candidateId}'
                """.trimIndent(),
            )
        }
        val elevatedResult = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = elevated.candidateId,
                eventId = "observation-elevated-after-submit",
                confirmedAtEpochMillis = NOW + 2,
            ),
        )
        assertNull(elevatedResult.event)
        assertEquals(
            LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED,
            requireNotNull(elevatedResult.reviewCase).reason,
        )

        val original = ready(candidate(candidateId = "candidate-source-swap"))
        val alternate = candidate(
            candidateId = "candidate-source-swap-alternate",
            occurredAtEpochMillis = original.occurredAtEpochMillis,
        )
        persistTutorModelFact(alternate)
        store.database.withWriteTransaction {
            executeSQL(
                """
                UPDATE learning_observation_candidate
                SET source_fact_id = '${alternate.sourceFactId}'
                WHERE candidate_id = '${original.candidateId}'
                """.trimIndent(),
            )
        }
        val swappedResult = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = original.candidateId,
                eventId = "observation-source-swap",
                confirmedAtEpochMillis = NOW + 3,
            ),
        )
        assertNull(swappedResult.event)
        assertEquals(
            LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED,
            requireNotNull(swappedResult.reviewCase).reason,
        )
        assertEquals(0L, store.loadProjectionBatch(PROJECTION, LEARNER, 10).ledgerHeadSequence)
    }

    @Test
    fun quarantinedLegacyObservationConsumesSequenceBeforeLaterObservation() = runBlocking {
        val legacy = requireNotNull(
            store.materializeLearningObservation(
                MaterializeLearningObservationCommand(
                    candidateId = ready(
                        candidate(candidateId = "candidate-quarantined-legacy"),
                    ).candidateId,
                    eventId = "observation-quarantined-legacy",
                    confirmedAtEpochMillis = NOW + 2,
                ),
            ).event,
        )
        val later = requireNotNull(
            store.materializeLearningObservation(
                MaterializeLearningObservationCommand(
                    candidateId = ready(
                        candidate(candidateId = "candidate-after-quarantine"),
                    ).candidateId,
                    eventId = "observation-after-quarantine",
                    confirmedAtEpochMillis = NOW + 3,
                ),
            ).event,
        )
        val legacyFingerprint = LearningLedgerFingerprint.learningObservation(
            legacy.copy(sourceFactId = null),
        )
        store.database.withWriteTransaction {
            executeSQL(
                """
                DELETE FROM learning_observation_event_admission
                WHERE event_id = '${legacy.eventId}'
                """.trimIndent(),
            )
            executeSQL(
                """
                UPDATE attributed_learning_observation_event
                SET source_fact_id = NULL,
                    canonical_fingerprint = '$legacyFingerprint'
                WHERE event_id = '${legacy.eventId}'
                """.trimIndent(),
            )
            executeSQL(
                """
                UPDATE projection_outbox
                SET canonical_fingerprint = '$legacyFingerprint'
                WHERE event_kind = '$EVENT_KIND_LEARNING_OBSERVATION'
                  AND event_id = '${legacy.eventId}'
                """.trimIndent(),
            )
        }

        val batch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(ProjectionBatchStopReason.END_OF_LEDGER, batch.stopReason)
        assertEquals(listOf(1L, 2L), batch.events.map { it.event.eventSequence })
        val projected = LearningProjector().project(
            previous = LearnerSnapshot.empty(LEARNER, LearningProjector.VERSION),
            events = batch.events.map(PersistedIncrementalLearningEvent::event),
            knownLedgerHeadSequence = batch.ledgerHeadSequence,
            authoritativePresentationStates = batch.authoritativePresentationStates,
        )
        assertEquals(2L, projected.snapshot.checkpoint.lastSequence)
        assertEquals(
            setOf(later.eventId),
            projected.snapshot.appliedLearningObservationRecords.keys,
        )
        val mastery = projected.snapshot.knowledgeMasteryStates.getValue("knowledge-math")
        assertEquals(0.2, mastery.evidenceMass, 0.0)
        assertEquals(later.eventSequence, mastery.checkpointSequence)

        store.commitProjection(
            ProjectionCommit(
                projectionName = PROJECTION,
                learnerId = LEARNER,
                expectedPreviousCheckpoint = 0,
                expectedPreviousStateVersion = 0,
                mode = ProjectionCommitMode.INCREMENTAL,
                knownLedgerHeadSequence = batch.ledgerHeadSequence,
                consumedLedgerEvents = batch.events.map { persisted ->
                    ConsumedLedgerEventReceipt(
                        eventKind = persisted.outbox.eventKind,
                        eventId = persisted.outbox.eventId,
                        eventSequence = persisted.outbox.outboxSequence,
                        canonicalFingerprint = persisted.canonicalFingerprint,
                    )
                },
                presentationProjectionStates = projected.presentationProjectionStates,
                snapshot = projected.snapshot,
            ),
        )
        assertTrue(store.loadProjectionBatch(PROJECTION, LEARNER, 10).events.isEmpty())
    }

    @Test
    fun delayedConfirmationFullReplayCommitReloadUsesOccurrenceTimeline() = runBlocking {
        val positiveOccurredAt = NOW - 43_200_000
        val negativeOccurredAt = NOW - 86_400_000
        val positiveConfirmedAt = NOW + 864_000_000
        val negativeConfirmedAt = NOW + 1_728_000_000
        val positive = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = ready(
                    candidate(
                        candidateId = "candidate-delayed-positive",
                        occurredAtEpochMillis = positiveOccurredAt,
                    ),
                ).candidateId,
                eventId = "observation-delayed-positive",
                confirmedAtEpochMillis = positiveConfirmedAt,
            ),
        )
        val positiveEvent = requireNotNull(positive.event)
        assertEquals(positiveConfirmedAt, positiveEvent.confirmedAtEpochMillis)

        val projector = LearningProjector()
        val firstBatch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        val firstProjection = projector.project(
            previous = LearnerSnapshot.empty(LEARNER, LearningProjector.VERSION),
            events = firstBatch.events.map(PersistedIncrementalLearningEvent::event),
            knownLedgerHeadSequence = firstBatch.ledgerHeadSequence,
            authoritativePresentationStates = firstBatch.authoritativePresentationStates,
        )
        val persistedPositive = store.commitProjection(
            ProjectionCommit(
                projectionName = PROJECTION,
                learnerId = LEARNER,
                expectedPreviousCheckpoint = 0,
                expectedPreviousStateVersion = 0,
                mode = ProjectionCommitMode.INCREMENTAL,
                knownLedgerHeadSequence = firstBatch.ledgerHeadSequence,
                consumedLedgerEvents = firstBatch.events.map { persisted ->
                    ConsumedLedgerEventReceipt(
                        eventKind = EVENT_KIND_LEARNING_OBSERVATION,
                        eventId = persisted.event.ledgerEventId,
                        eventSequence = persisted.event.eventSequence,
                        canonicalFingerprint = persisted.canonicalFingerprint,
                    )
                },
                presentationProjectionStates = firstProjection.presentationProjectionStates,
                snapshot = firstProjection.snapshot,
            ),
        )
        assertEquals(1L, persistedPositive.snapshot.checkpoint.lastSequence)

        val negative = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = ready(
                    candidate(
                        candidateId = "candidate-delayed-negative",
                        direction = LearningObservationDirection.NEGATIVE,
                        evidenceWeight = 0.25,
                        independence = LearningObservationIndependence.UNKNOWN,
                        occurredAtEpochMillis = negativeOccurredAt,
                    ),
                ).candidateId,
                eventId = "observation-delayed-negative",
                confirmedAtEpochMillis = negativeConfirmedAt,
            ),
        )
        val negativeEvent = requireNotNull(negative.event)
        assertEquals(negativeConfirmedAt, negativeEvent.confirmedAtEpochMillis)

        val lateBatch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(1L, lateBatch.previousCheckpoint)
        assertEquals(2L, lateBatch.ledgerHeadSequence)
        assertEquals(listOf(negativeEvent.eventId), lateBatch.events.map { it.event.ledgerEventId })
        val incremental = projector.project(
            previous = persistedPositive.snapshot,
            events = lateBatch.events.map(PersistedIncrementalLearningEvent::event),
            knownLedgerHeadSequence = lateBatch.ledgerHeadSequence,
            authoritativePresentationStates = lateBatch.authoritativePresentationStates,
        )
        assertTrue(incremental.requiresFullReplay)
        assertEquals(persistedPositive.snapshot, incremental.snapshot)

        val ledger = store.loadLearningLedger(LEARNER)
        assertEquals(LearningLedgerReadStatus.COMPLETE, ledger.status)
        val events = ledger.validPrefix.map(PersistedLearningLedgerEvent::event)
        val replay = projector.replay(LEARNER, events)
        val mastery = replay.snapshot.knowledgeMasteryStates.getValue("knowledge-math")

        assertEquals(positiveOccurredAt, mastery.lastEvidenceAtEpochMillis)
        assertNull(mastery.lastIndependentErrorAtEpochMillis)
        assertNull(mastery.lastIndependentErrorSequence)
        assertEquals(positiveOccurredAt, replay.snapshot.checkpoint.projectedAtEpochMillis)
        assertEquals(positiveOccurredAt, replay.snapshot.generatedAtEpochMillis)
        assertEquals(2L, replay.snapshot.checkpoint.lastSequence)
        assertEquals(2L, replay.snapshot.knownLedgerHeadSequence)

        store.commitProjection(
            ProjectionCommit(
                projectionName = PROJECTION,
                learnerId = LEARNER,
                expectedPreviousCheckpoint = 1,
                expectedPreviousStateVersion = persistedPositive.stateVersion,
                mode = ProjectionCommitMode.FULL_REPLAY,
                knownLedgerHeadSequence = 2,
                consumedLedgerEvents = lateBatch.events.map { persisted ->
                    ConsumedLedgerEventReceipt(
                        eventKind = EVENT_KIND_LEARNING_OBSERVATION,
                        eventId = persisted.event.ledgerEventId,
                        eventSequence = persisted.event.eventSequence,
                        canonicalFingerprint = persisted.canonicalFingerprint,
                    )
                },
                presentationProjectionStates = replay.presentationProjectionStates,
                snapshot = replay.snapshot,
            ),
        )

        val reloaded = requireNotNull(store.readCurrentLearnerSnapshot(PROJECTION, LEARNER))
        assertEquals(replay.snapshot, reloaded.snapshot)
        assertEquals(2L, reloaded.stateVersion)
        assertEquals(2L, reloaded.knownLedgerHeadSequence)
        assertEquals(2L, reloaded.snapshot.checkpoint.lastSequence)
        assertEquals(
            setOf(positiveEvent.eventId, negativeEvent.eventId),
            reloaded.snapshot.appliedLearningObservationRecords.keys,
        )
        assertNull(
            reloaded.snapshot.knowledgeMasteryStates.getValue("knowledge-math")
                .lastIndependentErrorAtEpochMillis,
        )
    }

    @Test
    fun loadProjectCommitReloadPreservesMasteryAndAppliesObservationOnce() = runBlocking {
        val candidate = ready(candidate())
        val materialized = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = candidate.candidateId,
                eventId = "observation-crash-replay",
                confirmedAtEpochMillis = NOW + 2,
            ),
        )
        val event = requireNotNull(materialized.event)
        val firstLoad = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        val abandonedProjection = LearningProjector().project(
            previous = LearnerSnapshot.empty(LEARNER, LearningProjector.VERSION),
            events = firstLoad.events.map { it.event },
            knownLedgerHeadSequence = firstLoad.ledgerHeadSequence,
            authoritativePresentationStates = firstLoad.authoritativePresentationStates,
        )
        val afterCrash = store.loadProjectionBatch(PROJECTION, LEARNER, 10)

        assertEquals(firstLoad.events, afterCrash.events)
        val projected = LearningProjector().project(
            previous = LearnerSnapshot.empty(LEARNER, LearningProjector.VERSION),
            events = afterCrash.events.map { it.event },
            knownLedgerHeadSequence = afterCrash.ledgerHeadSequence,
            authoritativePresentationStates = afterCrash.authoritativePresentationStates,
        )
        assertEquals(abandonedProjection, projected)
        store.commitProjection(
            ProjectionCommit(
                projectionName = PROJECTION,
                learnerId = LEARNER,
                expectedPreviousCheckpoint = afterCrash.previousCheckpoint,
                expectedPreviousStateVersion = 0,
                mode = ProjectionCommitMode.INCREMENTAL,
                knownLedgerHeadSequence = afterCrash.ledgerHeadSequence,
                consumedLedgerEvents = afterCrash.events.map { persisted ->
                    ConsumedLedgerEventReceipt(
                        eventKind = persisted.outbox.eventKind,
                        eventId = persisted.event.ledgerEventId,
                        eventSequence = persisted.event.eventSequence,
                        canonicalFingerprint = persisted.canonicalFingerprint,
                    )
                },
                presentationProjectionStates = projected.presentationProjectionStates,
                snapshot = projected.snapshot,
            ),
        )

        val reloaded = requireNotNull(
            store.readCurrentLearnerSnapshot(PROJECTION, LEARNER),
        )
        assertEquals(projected.snapshot.knowledgeMasteryStates, reloaded.snapshot.knowledgeMasteryStates)
        assertTrue(reloaded.snapshot.knowledgeMasteryStates.containsKey("knowledge-math"))
        assertEquals(
            setOf(event.eventId),
            reloaded.snapshot.appliedLearningObservationRecords.keys,
        )
        assertTrue(store.loadProjectionBatch(PROJECTION, LEARNER, 10).events.isEmpty())
        val duplicate = LearningProjector().project(
            previous = reloaded.snapshot,
            events = firstLoad.events.map { it.event },
            knownLedgerHeadSequence = firstLoad.ledgerHeadSequence,
            authoritativePresentationStates = firstLoad.authoritativePresentationStates,
        )
        assertEquals(reloaded.snapshot, duplicate.snapshot)
        assertEquals(setOf(event.eventId), duplicate.ignoredLearningObservationEventIds)
    }

    private suspend fun ready(candidate: LearningObservationCandidate): LearningObservationCandidate {
        submitCandidate(candidate)
        return markReady(candidate)
    }

    private suspend fun submitCandidate(
        candidate: LearningObservationCandidate,
    ): LearningObservationCandidateWriteResult {
        persistTutorModelFact(candidate)
        store.registerLearningObservationSourceAuthority(authority(candidate))
        return store.submitLearningObservationCandidate(candidate)
    }

    private suspend fun persistTutorModelFact(
        candidate: LearningObservationCandidate,
        sourceSubject: SubjectKind = subjectForProblemRevision(
            requireNotNull(candidate.problemRevisionId),
        ),
    ) {
        require(candidate.source == LearningObservationSource.TUTOR_CHOICE)
        val fixture = tutorModelFactFixture(candidate, sourceSubject)
        persistCapturedProblemMapping(
            fixture = fixture,
            practiceUnitId = requireNotNull(candidate.practiceUnitId),
            problemRevisionId = requireNotNull(candidate.problemRevisionId),
            committedAtEpochMillis = candidate.occurredAtEpochMillis - 1,
        )
        store.persistSucceededModelTask(
            request = fixture.planRequest,
            output = fixture.planOutput,
        )
        databaseClockEpochMillis = candidate.occurredAtEpochMillis
        store.createTutorConversation(
            CreateTutorConversationCommand(
                conversationId = fixture.conversationId,
                learnerId = candidate.learnerId,
                idempotencyKey = "create-${candidate.candidateId}",
                payloadFingerprint = sha256("create-${candidate.candidateId}"),
            ),
        )
        val turn = store.allocateTutorTurn(
            AllocateTutorTurnCommand(
                turnReceiptId = fixture.turnReceiptId,
                learnerId = candidate.learnerId,
                conversationId = fixture.conversationId,
                conversationGeneration = 1,
                expectedConversationStateVersion = 0,
                expectedTurnOrdinal = 1,
                clientTurnId = "client-${candidate.candidateId}",
                payloadFingerprint = sha256("turn-${candidate.candidateId}"),
                subject = sourceSubject,
                problemAnchorId = fixture.anchorId,
                requestVersion = 1,
                explanationMode = TutorExplanationMode.GUIDED,
                modeVersion = 1,
                directiveFingerprint = fixture.directiveFingerprint,
                studentMessageFingerprint = sha256("student-${candidate.candidateId}"),
                studentMessageSummary = "我选择了一个答案。",
                occurredAtEpochMillis = candidate.occurredAtEpochMillis,
            ),
        ).receipt
        store.prepareTutorEvidenceRequest(
            PrepareTutorEvidenceRequestCommand(
                evidenceRequestId = fixture.evidenceRequestId,
                learnerId = candidate.learnerId,
                conversationId = fixture.conversationId,
                conversationGeneration = 1,
                conversationStateVersion = turn.conversationStateVersion,
                turnReceiptId = fixture.turnReceiptId,
                turnOrdinal = turn.turnOrdinal,
                subject = sourceSubject,
                problemAnchorId = fixture.anchorId,
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = 1,
                explanationMode = TutorExplanationMode.GUIDED,
                modeVersion = 1,
                directiveFingerprint = fixture.directiveFingerprint,
                idempotencyKey = "prepare-${candidate.candidateId}",
                payloadFingerprint = sha256("prepare-${candidate.candidateId}"),
            ),
        )
        store.recordTutorChoice(fixture.choice)
        val factKind = if (candidate.direction == LearningObservationDirection.NEGATIVE) {
            LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE
        } else {
            LearningObservationFactKind.MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE
        }
        val finalized = store.finalizeTutorEvidenceRequest(
            FinalizeTutorEvidenceRequestCommand(
                learnerId = candidate.learnerId,
                conversationId = fixture.conversationId,
                conversationGeneration = 1,
                conversationStateVersion = turn.conversationStateVersion,
                turnReceiptId = fixture.turnReceiptId,
                turnOrdinal = turn.turnOrdinal,
                subject = sourceSubject,
                problemAnchorId = fixture.anchorId,
                evidenceRequestId = fixture.evidenceRequestId,
                expectedEvidenceStateVersion = 0,
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = 1,
                explanationMode = TutorExplanationMode.GUIDED,
                modeVersion = 1,
                directiveFingerprint = fixture.directiveFingerprint,
                terminalStatus = TutorEvidenceRequestStatus.SUBMITTED,
                idempotencyKey = "finalize-${candidate.candidateId}",
                payloadFingerprint = sha256("finalize-${candidate.candidateId}"),
                submission = TutorEvidenceSubmission(
                    sourceFactId = fixture.sourceFactId,
                    source = LearningObservationSource.TUTOR_CHOICE,
                    factKind = factKind,
                    questionFingerprint = fixture.questionFingerprint,
                    revisionFingerprint = fixture.revisionFingerprint,
                    fingerprintVersion = CapturedTutorProblemIdentity.fingerprintVersion,
                    responseFingerprint = fixture.responseFingerprint,
                    responseSummary = fixture.choice.selectedChoiceMarkdown,
                    occurredAtEpochMillis = candidate.occurredAtEpochMillis,
                    sourceVersion = "captured-choice-source-v1",
                ),
            ),
        )
        assertEquals(fixture.sourceFactId, finalized.sourceFact?.sourceFactId)
    }

    private suspend fun persistCapturedProblemMapping(
        fixture: TutorModelFactFixture,
        practiceUnitId: String,
        problemRevisionId: String,
        committedAtEpochMillis: Long,
    ) {
        val problemId = problemIdForRevision(problemRevisionId)
        val errorBookEntryId = errorBookEntryIdForProblem(problemId)
        if (store.readTutorSession(fixture.sessionId) == null) {
            val assetHash = sha256("asset-content-${fixture.suffix}")
            store.createProblemDraft(
                CreateProblemDraftCommand(
                    sourceAsset = CanonicalSourceAssetRecord(
                        sourceAssetId = fixture.sourceAssetId,
                        contentSha256 = assetHash,
                        relativePath = "source-assets/$assetHash.jpg",
                        mimeType = "image/jpeg",
                        byteSize = 1_024,
                        width = 800,
                        height = 600,
                        sourceType = StudyDbValue.SourceAssetType.PHOTO_PICKER,
                        createdAtEpochMillis = fixture.captureCreatedAtEpochMillis,
                    ),
                    draftId = fixture.draftId,
                    origin = StudyDbValue.CaptureOrigin.TUTOR,
                    initialRevision = ProblemDraftRevisionRecord(
                        draftId = fixture.draftId,
                        revisionNumber = 1,
                        basisRevisionNumber = null,
                        subject = null,
                        title = "待确认",
                        questionDocument = fixture.initialDocument,
                        documentFingerprint =
                            CapturedQuestionDocumentFingerprint.of(fixture.initialDocument),
                        author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
                        createdAtEpochMillis = fixture.captureCreatedAtEpochMillis,
                    ),
                ),
            )
            store.confirmTutorSession(
                ConfirmTutorSessionCommand(
                    sessionId = fixture.sessionId,
                    draftId = fixture.draftId,
                    expectedRevisionNumber = 1,
                    confirmedRevision = ProblemDraftRevisionRecord(
                        draftId = fixture.draftId,
                        revisionNumber = 2,
                        basisRevisionNumber = 1,
                        subject = fixture.subject.name,
                        title = "已确认题目",
                        questionDocument = fixture.confirmedDocument,
                        documentFingerprint = fixture.revisionFingerprint,
                        author = StudyDbValue.ProblemDraftAuthor.USER,
                        createdAtEpochMillis = fixture.confirmedAtEpochMillis,
                    ),
                    createdAtEpochMillis = fixture.confirmedAtEpochMillis,
                ),
            )
        }
        store.database.withWriteTransaction {
            executeSQL(
                """
                UPDATE problem_draft
                SET status = 'COMMITTED',
                    updated_at_epoch_millis = $committedAtEpochMillis
                WHERE draft_id = '${fixture.draftId}'
                  AND current_revision_number = 2
                """.trimIndent(),
            )
            executeSQL(
                """
                INSERT OR IGNORE INTO problem_draft_commit_receipt(
                    command_id, payload_fingerprint, draft_id, draft_revision_number,
                    problem_id, problem_revision_id, practice_unit_id,
                    error_book_entry_id, committed_at_epoch_millis
                ) VALUES(
                    'commit-${fixture.suffix}', '${sha256("commit-${fixture.suffix}")}',
                    '${fixture.draftId}', 2, '$problemId', '$problemRevisionId',
                    '$practiceUnitId', '$errorBookEntryId', $committedAtEpochMillis
                )
                """.trimIndent(),
            )
        }
    }

    private suspend fun replaceCapturedResponseWithWrongSameSubjectSession(
        candidate: LearningObservationCandidate,
    ) {
        val wrongFixture = tutorModelFactFixture(
            candidateId = "wrong-${candidate.candidateId}",
            learnerId = candidate.learnerId,
            evidenceRequestId = candidate.sourceReferenceId,
            sourceSubject = SubjectKind.MATH,
            selectionWasCorrect = candidate.direction != LearningObservationDirection.NEGATIVE,
            occurredAtEpochMillis = candidate.occurredAtEpochMillis,
        )
        persistCapturedProblemMapping(
            fixture = wrongFixture,
            practiceUnitId = OTHER_MATH_UNIT,
            problemRevisionId = OTHER_MATH_REVISION,
            committedAtEpochMillis = candidate.occurredAtEpochMillis - 1,
        )
        store.database.withWriteTransaction {
            executeSQL(
                """
                DELETE FROM tutor_turn_response
                WHERE evidence_request_id = '${candidate.sourceReferenceId}'
                """.trimIndent(),
            )
        }
        store.recordTutorChoice(wrongFixture.choice)
    }

    private suspend fun StudyDatabasePort.persistSucceededModelTask(
        request: ModelTaskRequest,
        output: ModelTaskOutput,
    ) {
        var snapshot = createModelTask(
            CreateModelTaskCommand(
                taskId = "task:${request.requestId}",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                occurredAtEpochMillis = request.occurredAtEpochMillis,
            ),
        ).snapshot
        while (snapshot.status != ModelTaskStatus.SUCCEEDED) {
            val transition = when (snapshot.status) {
                ModelTaskStatus.WAITING_FOR_MODEL -> transition(
                    snapshot = snapshot,
                    nextStatus = ModelTaskStatus.QUEUED,
                    stage = ModelTaskStage.PREPARING,
                    occurredAtEpochMillis = request.occurredAtEpochMillis + 1,
                )
                ModelTaskStatus.QUEUED -> transition(
                    snapshot = snapshot,
                    nextStatus = ModelTaskStatus.RUNNING,
                    stage = ModelTaskStage.VALIDATING_OUTPUT,
                    occurredAtEpochMillis = request.occurredAtEpochMillis + 2,
                )
                ModelTaskStatus.RUNNING, ModelTaskStatus.STREAMING -> transition(
                    snapshot = snapshot,
                    nextStatus = ModelTaskStatus.SUCCEEDED,
                    stage = ModelTaskStage.COMPLETE,
                    occurredAtEpochMillis = request.occurredAtEpochMillis + 3,
                    output = output,
                )
                else -> error("Unexpected model task status ${snapshot.status}")
            }
            snapshot = transitionModelTask(transition).snapshot
        }
    }

    private fun transition(
        snapshot: ModelTaskSnapshot,
        nextStatus: ModelTaskStatus,
        stage: ModelTaskStage,
        occurredAtEpochMillis: Long,
        output: ModelTaskOutput? = null,
    ) = TransitionModelTaskCommand(
        taskId = snapshot.taskId,
        expectedStateVersion = snapshot.stateVersion,
        expectedStatus = snapshot.status,
        nextStatus = nextStatus,
        stage = stage,
        userMessage = nextStatus.name,
        attemptCount = snapshot.attemptCount,
        output = output,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private fun tutorModelFactFixture(
        candidate: LearningObservationCandidate,
        sourceSubject: SubjectKind,
    ) = tutorModelFactFixture(
        candidateId = candidate.candidateId,
        learnerId = candidate.learnerId,
        evidenceRequestId = candidate.sourceReferenceId,
        sourceSubject = sourceSubject,
        selectionWasCorrect = candidate.direction != LearningObservationDirection.NEGATIVE,
        occurredAtEpochMillis = candidate.occurredAtEpochMillis,
    )

    private fun tutorModelFactFixture(
        candidateId: String,
        learnerId: String,
        evidenceRequestId: String,
        sourceSubject: SubjectKind,
        selectionWasCorrect: Boolean,
        occurredAtEpochMillis: Long,
    ): TutorModelFactFixture {
        require(occurredAtEpochMillis >= 10)
        val suffix = candidateId
        val sourceAssetId = "asset-$suffix"
        val draftId = "draft-$suffix"
        val sessionId = "session-$suffix"
        val questionDocumentId = "document-$draftId"
        val initialDocument = capturedDocument(
            questionDocumentId = questionDocumentId,
            assetId = sourceAssetId,
            markdown = "等待确认。",
            provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
            writingLayer = WritingLayer.UNKNOWN,
        )
        val confirmedDocument = capturedDocument(
            questionDocumentId = questionDocumentId,
            assetId = sourceAssetId,
            markdown = when (sourceSubject) {
                SubjectKind.CHEMISTRY -> "根据题目条件判断化学推理是否成立。"
                else -> "根据题目条件判断数学推理是否成立。"
            },
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
        )
        val revisionFingerprint = CapturedQuestionDocumentFingerprint.of(confirmedDocument)
        val item = diagnosticItem(suffix, sourceSubject)
        val planInput = TutorPlanInput(
            sessionId = sessionId,
            draftRevisionNumber = 2,
            subject = sourceSubject.name,
            questionDocument = confirmedDocument.document,
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            cycleOrdinal = 1,
            turnOrdinal = 1,
        )
        val planOutput = TutorPlanOutput(
            sessionId = sessionId,
            draftRevisionNumber = 2,
            questionDocumentId = questionDocumentId,
            plan = TutorTurnPlan(
                openingMarkdown = "先核对当前推理。",
                diagnosticItem = item,
                interactionDirective = null,
                solutionMarkdown = "按题目条件逐步判断。",
                alternateMethodMarkdown = "也可以从等价关系判断。",
                difficultyReasonMarkdown = "关键是条件与结论是否对应。",
                targetedEvidenceLabels = emptyList(),
                inferredKnowledgeLabels = listOf(
                    when (sourceSubject) {
                        SubjectKind.CHEMISTRY -> "化学条件判断"
                        else -> "数学条件判断"
                    },
                ),
            ),
            modelVersion = "instrumented-test-model",
            cycleOrdinal = 1,
            turnOrdinal = 1,
        )
        val planRequest = ModelTaskRequest(
            requestId = evidenceRequestId,
            input = planInput,
            occurredAtEpochMillis = occurredAtEpochMillis - 4,
        )
        val directiveFingerprint = directiveFingerprint(
            requestId = evidenceRequestId,
            sessionId = sessionId,
            draftId = draftId,
            revisionFingerprint = revisionFingerprint,
            input = planInput,
            output = planOutput,
            item = item,
        )
        val questionFingerprint = CapturedTutorProblemIdentity.questionFingerprint(draftId)
        val conversationId = opaqueId(
            "captured-choice-conversation-v2",
            learnerId,
            sessionId,
            draftId,
            "2",
            questionDocumentId,
        )
        val anchorId = opaqueId(
            "captured-choice-anchor-v1",
            learnerId,
            sourceSubject.name,
            questionFingerprint,
            revisionFingerprint,
            CapturedTutorProblemIdentity.fingerprintVersion,
        )
        val turnReceiptId = opaqueId(
            "captured-choice-turn-v1",
            learnerId,
            sessionId,
            draftId,
            "2",
            questionDocumentId,
            evidenceRequestId,
            "1",
            "1",
            directiveFingerprint,
        )
        val selectedChoice = item.choices.single { choice ->
            (choice.id == item.correctChoiceId) == selectionWasCorrect
        }
        val choice = PersistTutorChoiceCommand(
            sessionId = sessionId,
            questionDocumentId = questionDocumentId,
            revisionNumber = 2,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            diagnosticStemMarkdown = item.stemMarkdown,
            selectedChoiceId = selectedChoice.id,
            selectedChoiceMarkdown = selectedChoice.markdown,
            selectionWasCorrect = selectionWasCorrect,
            feedbackMarkdown = checkNotNull(selectedChoice.feedbackMarkdown),
            choiceSubmittedAtEpochMillis = occurredAtEpochMillis,
            evidenceRequestId = evidenceRequestId,
        )
        val responseFingerprint = canonicalChoiceResponseFingerprint(choice)
        val sourceFactId = opaqueId(
            "captured-choice-fact-v1",
            learnerId,
            evidenceRequestId,
            responseFingerprint,
        )
        return TutorModelFactFixture(
            suffix = suffix,
            subject = sourceSubject,
            sourceAssetId = sourceAssetId,
            draftId = draftId,
            sessionId = sessionId,
            evidenceRequestId = evidenceRequestId,
            initialDocument = initialDocument,
            confirmedDocument = confirmedDocument,
            captureCreatedAtEpochMillis = occurredAtEpochMillis - 10,
            confirmedAtEpochMillis = occurredAtEpochMillis - 8,
            revisionFingerprint = revisionFingerprint,
            planRequest = planRequest,
            planOutput = planOutput,
            directiveFingerprint = directiveFingerprint,
            conversationId = conversationId,
            anchorId = anchorId,
            turnReceiptId = turnReceiptId,
            questionFingerprint = questionFingerprint,
            choice = choice,
            responseFingerprint = responseFingerprint,
            sourceFactId = sourceFactId,
        )
    }

    private fun capturedDocument(
        questionDocumentId: String,
        assetId: String,
        markdown: String,
        provenance: QuestionBlockProvenance,
        reviewStatus: QuestionBlockReviewStatus,
        writingLayer: WritingLayer,
    ) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = questionDocumentId,
            title = "题目",
            blocks = listOf(ContentBlock.Paragraph("stem", markdown)),
        ),
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = assetId,
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = writingLayer,
                provenance = provenance,
                confidence = null,
                reviewStatus = reviewStatus,
                producerVersion = "instrumented-test-v1",
            ),
        ),
    )

    private fun diagnosticItem(
        suffix: String,
        subject: SubjectKind,
    ) = TutorAssessmentItem(
        id = "diagnostic-$suffix",
        stemMarkdown = when (subject) {
            SubjectKind.CHEMISTRY -> "当前化学推理是否成立？"
            else -> "当前数学推理是否成立？"
        },
        choices = listOf(
            TutorChoice(
                id = "valid-$suffix",
                markdown = "成立",
                feedbackMarkdown = "条件与结论相符。",
            ),
            TutorChoice(
                id = "invalid-$suffix",
                markdown = "不成立",
                feedbackMarkdown = "需要重新核对条件。",
            ),
        ),
        correctChoiceId = "valid-$suffix",
    )

    private fun directiveFingerprint(
        requestId: String,
        sessionId: String,
        draftId: String,
        revisionFingerprint: String,
        input: TutorPlanInput,
        output: TutorPlanOutput,
        item: TutorAssessmentItem,
    ): String {
        val digest = CanonicalSha256("captured-choice-directive-v1")
            .field("planRequestId", requestId)
            .field("sessionId", sessionId)
            .field("draftId", draftId)
            .field("draftRevisionNumber", input.draftRevisionNumber)
            .field("questionDocumentId", input.questionDocument.id)
            .field("revisionFingerprint", revisionFingerprint)
            .field("cycleOrdinal", input.cycleOrdinal)
            .field("turnOrdinal", input.turnOrdinal)
            .field("diagnosticItemId", item.id)
            .field("stemMarkdown", item.stemMarkdown)
            .nullableField("promptMarkdown", item.promptMarkdown)
            .field("choiceCount", item.choices.size)
        item.choices.forEachIndexed { index, choice ->
            digest.field("choice[$index].id", choice.id)
                .field("choice[$index].markdown", choice.markdown)
                .nullableField("choice[$index].feedbackMarkdown", choice.feedbackMarkdown)
                .field("choice[$index].followUpCount", choice.followUpIds.size)
            choice.followUpIds.forEachIndexed { followUpIndex, followUpId ->
                digest.field("choice[$index].followUp[$followUpIndex]", followUpId)
            }
        }
        digest.field("correctChoiceId", item.correctChoiceId)
            .field("initialFollowUpCount", item.initialFollowUpIds.size)
        item.initialFollowUpIds.forEachIndexed { index, followUpId ->
            digest.field("initialFollowUp[$index]", followUpId)
        }
        val knowledgeNodes = item.knowledgeNodeIds.sorted()
        digest.field("knowledgeNodeCount", knowledgeNodes.size)
        knowledgeNodes.forEachIndexed { index, node ->
            digest.field("knowledgeNode[$index]", node)
        }
        return digest.field("outputCycleOrdinal", output.cycleOrdinal)
            .field("outputTurnOrdinal", output.turnOrdinal)
            .finish()
    }

    private fun canonicalChoiceResponseFingerprint(
        choice: PersistTutorChoiceCommand,
    ): String = CanonicalSha256("captured-choice-response-v1")
        .field("sessionId", choice.sessionId)
        .field("questionDocumentId", choice.questionDocumentId)
        .field("revisionNumber", choice.revisionNumber)
        .field("cycleOrdinal", choice.cycleOrdinal)
        .field("turnOrdinal", choice.turnOrdinal)
        .nullableField("diagnosticStemMarkdown", choice.diagnosticStemMarkdown)
        .nullableField("selectedChoiceId", choice.selectedChoiceId)
        .nullableField("selectedChoiceMarkdown", choice.selectedChoiceMarkdown)
        .nullableField("selectionWasCorrect", choice.selectionWasCorrect.toString())
        .nullableField("feedbackMarkdown", choice.feedbackMarkdown)
        .nullableField("requestedMove", null)
        .field("solutionRevealed", false)
        .field("submittedAtEpochMillis", choice.choiceSubmittedAtEpochMillis)
        .field("updatedAtEpochMillis", choice.choiceSubmittedAtEpochMillis)
        .nullableField(
            "choiceSubmittedAtEpochMillis",
            choice.choiceSubmittedAtEpochMillis.toString(),
        )
        .nullableField("evidenceRequestId", choice.evidenceRequestId)
        .finish()

    private fun opaqueId(domain: String, vararg values: String): String {
        val digest = CanonicalSha256(domain).field("valueCount", values.size)
        values.forEachIndexed { index, value ->
            digest.field("value[$index]", value)
        }
        return "$domain:${digest.finish()}"
    }

    private fun sha256(value: String): String =
        CanonicalSha256("learning-observation-instrumented-test-v1")
            .field("value", value)
            .finish()

    private fun subjectForProblemRevision(problemRevisionId: String): SubjectKind =
        when (problemRevisionId) {
            REVISION, OTHER_MATH_REVISION -> SubjectKind.MATH
            CHEMISTRY_REVISION -> SubjectKind.CHEMISTRY
            else -> error("Unknown test problem revision $problemRevisionId")
        }

    private fun problemIdForRevision(problemRevisionId: String): String =
        when (problemRevisionId) {
            REVISION -> PROBLEM
            OTHER_MATH_REVISION -> OTHER_MATH_PROBLEM
            CHEMISTRY_REVISION -> CHEMISTRY_PROBLEM
            else -> error("Unknown test problem revision $problemRevisionId")
        }

    private fun errorBookEntryIdForProblem(problemId: String): String =
        when (problemId) {
            PROBLEM -> ERROR_BOOK_ENTRY
            OTHER_MATH_PROBLEM -> OTHER_MATH_ERROR_BOOK_ENTRY
            CHEMISTRY_PROBLEM -> CHEMISTRY_ERROR_BOOK_ENTRY
            else -> error("Unknown test problem $problemId")
        }

    private data class TutorModelFactFixture(
        val suffix: String,
        val subject: SubjectKind,
        val sourceAssetId: String,
        val draftId: String,
        val sessionId: String,
        val evidenceRequestId: String,
        val initialDocument: CapturedQuestionDocument,
        val confirmedDocument: CapturedQuestionDocument,
        val captureCreatedAtEpochMillis: Long,
        val confirmedAtEpochMillis: Long,
        val revisionFingerprint: String,
        val planRequest: ModelTaskRequest,
        val planOutput: TutorPlanOutput,
        val directiveFingerprint: String,
        val conversationId: String,
        val anchorId: String,
        val turnReceiptId: String,
        val questionFingerprint: String,
        val choice: PersistTutorChoiceCommand,
        val responseFingerprint: String,
        val sourceFactId: String,
    )

    private suspend fun markReady(
        candidate: LearningObservationCandidate,
    ): LearningObservationCandidate =
        store.compareAndSetLearningObservationCandidateStatus(
            LearningObservationCandidateStatusChangeCommand(
                candidateId = candidate.candidateId,
                expectedStatus = candidate.status,
                newStatus = LearningObservationCandidateStatus.READY,
                expectedRetryCount = candidate.retryCount,
                incrementRetry = false,
                updatedAtEpochMillis = NOW + 1,
            ),
        ).candidate

    private fun candidate(
        candidateId: String = "candidate-1",
        learnerId: String = LEARNER,
        sourceReferenceId: String = "choice-$candidateId",
        sourceFactId: String? = AUTO_SOURCE_FACT_ID,
        practiceUnitId: String = UNIT,
        problemRevisionId: String = REVISION,
        bindingId: String = "binding-math",
        knowledgeNodeId: String = "knowledge-math",
        direction: LearningObservationDirection = LearningObservationDirection.POSITIVE,
        evidenceLevel: LearningObservationEvidenceLevel =
            LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
        evidenceWeight: Double = 0.2,
        independence: LearningObservationIndependence =
            LearningObservationIndependence.ASSISTED,
        occurredAtEpochMillis: Long = NOW - 10,
        sourceSubject: SubjectKind? = null,
    ): LearningObservationCandidate {
        val resolvedSubject = sourceSubject ?: subjectForProblemRevision(problemRevisionId)
        val resolvedSourceFactId = if (sourceFactId == AUTO_SOURCE_FACT_ID) {
            tutorModelFactFixture(
                candidateId = candidateId,
                learnerId = learnerId,
                evidenceRequestId = sourceReferenceId,
                sourceSubject = resolvedSubject,
                selectionWasCorrect = direction != LearningObservationDirection.NEGATIVE,
                occurredAtEpochMillis = occurredAtEpochMillis,
            ).sourceFactId
        } else {
            sourceFactId
        }
        return LearningObservationCandidate(
            candidateId = candidateId,
            learnerId = learnerId,
            source = LearningObservationSource.TUTOR_CHOICE,
            sourceReferenceId = sourceReferenceId,
            sourceFactId = resolvedSourceFactId,
            practiceUnitId = practiceUnitId,
            problemRevisionId = problemRevisionId,
            direction = direction,
            evidenceLevel = evidenceLevel,
            evidenceWeight = evidenceWeight,
            independence = independence,
            proposedAttributions = listOf(
                LearningObservationKnowledgeAttribution(
                    bindingId = bindingId,
                    knowledgeNodeId = knowledgeNodeId,
                    weight = 1.0,
                    basisRevisionId = problemRevisionId,
                    taxonomyVersion = "taxonomy-v1",
                    role = EvidenceAttributionRole.PRIMARY,
                    certainty = EvidenceAttributionCertainty.DIRECT,
                ),
            ),
            occurredAtEpochMillis = occurredAtEpochMillis,
            modelVersion = "model-v1",
            evidenceLocator = "response:$candidateId",
            status = LearningObservationCandidateStatus.PENDING_CONFIRMATION,
            retryCount = 0,
            createdAtEpochMillis = NOW,
            updatedAtEpochMillis = NOW,
        )
    }

    private fun authority(
        candidate: LearningObservationCandidate,
        practiceUnitId: String = requireNotNull(candidate.practiceUnitId),
        problemRevisionId: String = requireNotNull(candidate.problemRevisionId),
    ) = LearningObservationSourceAuthorityRecord(
        learnerId = candidate.learnerId,
        source = candidate.source,
        sourceReferenceId = candidate.sourceReferenceId,
        practiceUnitId = practiceUnitId,
        problemRevisionId = problemRevisionId,
        sourcePayloadFingerprint = "source-fingerprint:${candidate.sourceReferenceId}",
        verifiedAtEpochMillis = NOW,
        sourceFactId = candidate.sourceFactId,
    )

    private suspend fun registerAuthority(candidate: LearningObservationCandidate) {
        registerAuthorityResult(candidate)
    }

    private suspend fun registerAuthorityResult(
        candidate: LearningObservationCandidate,
    ): LearningObservationSourceAuthorityWriteResult {
        persistTutorModelFact(candidate)
        return store.registerLearningObservationSourceAuthority(authority(candidate))
    }

    private suspend fun clearAuthoritySourceFact(candidate: LearningObservationCandidate) {
        store.database.withWriteTransaction {
            executeSQL(
                """
                UPDATE learning_observation_source_authority
                SET source_fact_id = NULL
                WHERE learner_id = '${candidate.learnerId}'
                  AND source = '${candidate.source.name}'
                  AND source_reference_id = '${candidate.sourceReferenceId}'
                """.trimIndent(),
            )
        }
    }

    private suspend fun insertLegacyNullSourceFactAuthority(
        candidate: LearningObservationCandidate,
    ) {
        store.database.withWriteTransaction {
            executeSQL(
                """
                INSERT INTO learning_observation_source_authority(
                    learner_id, source, source_reference_id, practice_unit_id,
                    problem_revision_id, source_payload_fingerprint, verified_at_epoch_millis,
                    source_fact_id
                ) VALUES(
                    '${candidate.learnerId}', '${candidate.source.name}',
                    '${candidate.sourceReferenceId}', '${candidate.practiceUnitId}',
                    '${candidate.problemRevisionId}',
                    'legacy-source-fingerprint:${candidate.sourceReferenceId}', $NOW, NULL
                )
                """.trimIndent(),
            )
        }
    }

    private fun attemptCommand(
        submissionId: String,
        attemptId: String,
        presentationId: String,
        occurredAtEpochMillis: Long,
    ) = AttemptWriteCommand(
        learnerId = LEARNER,
        submissionId = submissionId,
        attemptId = attemptId,
        presentationId = presentationId,
        assessmentSnapshotId = ASSESSMENT_SNAPSHOT,
        submittedResponse = AttemptSubmittedResponse.Choice(
            choiceId = "choice-a",
            choiceMarkdown = "A",
            submittedAtEpochMillis = occurredAtEpochMillis,
        ),
        evidence = LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = 1.0,
            reason = LearningEvidenceReason.INDEPENDENT_CORRECT,
        ),
        problemMemoryOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
        occurredAtEpochMillis = occurredAtEpochMillis,
        durationSeconds = 30,
        studyDay = StudyDayContext(
            epochDay = 20_000,
            timeZoneId = "Asia/Shanghai",
            utcOffsetMinutes = 480,
        ),
    )

    private fun assessmentSnapshot() = AssessmentEvidenceSnapshot(
        snapshotId = ASSESSMENT_SNAPSHOT,
        assessmentItemId = "assessment-observation",
        practiceUnitId = UNIT,
        problemRevisionId = REVISION,
        answerSpecId = "answer-1",
        itemFamilyId = "family-observation",
        sourceBundleId = "bundle-observation",
        taxonomyVersion = "taxonomy-v1",
        verification = AssessmentSnapshotVerification.VERIFIED,
        calibration = CalibrationSnapshot(
            support = CalibrationSupport.SUPPORTED,
            sourceId = "calibration-source",
            version = "calibration-v1",
            validFromEpochMillis = 0,
            validUntilEpochMillis = NOW + 100_000,
        ),
        attributions = listOf(
            KnowledgeEvidenceAttribution(
                bindingId = "binding-math",
                knowledgeNodeId = "knowledge-math",
                weight = 1.0,
                basisRevisionId = REVISION,
                taxonomyVersion = "taxonomy-v1",
                role = EvidenceAttributionRole.PRIMARY,
                certainty = EvidenceAttributionCertainty.DIRECT,
            ),
        ),
        capturedAtEpochMillis = NOW - 1,
    )

    private suspend fun assertImmutableConflict(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ImmutablePayloadConflictException")
        } catch (_: ImmutablePayloadConflictException) {
            Unit
        }
    }

    private suspend fun assertSourceAuthorityFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected LearningObservationSourceAuthorityException")
        } catch (_: LearningObservationSourceAuthorityException) {
            Unit
        }
    }

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun seed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(PROBLEM, FINGERPRINT_D, "MATH", NOW - 100),
            ProblemSeedRecord(OTHER_MATH_PROBLEM, FINGERPRINT_F, "MATH", NOW - 100),
            ProblemSeedRecord(
                CHEMISTRY_PROBLEM,
                "chemistry-problem-fingerprint",
                "CHEMISTRY",
                NOW - 100,
            ),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = REVISION,
                problemId = PROBLEM,
                revisionNumber = 1,
                title = "函数",
                problemMarkdown = "求解。",
                answerSpecId = "answer-1",
                answerSpecSnapshot = "答案",
                answerVerificationStatus = StudyDbValue.VerificationStatus.VERIFIED,
                sourceType = "IMPORT",
                sourceReference = null,
                contentFingerprint = FINGERPRINT_E,
                createdAtEpochMillis = NOW - 90,
            ),
            ProblemRevisionSeedRecord(
                revisionId = OTHER_MATH_REVISION,
                problemId = OTHER_MATH_PROBLEM,
                revisionNumber = 1,
                title = "数列",
                problemMarkdown = "求和。",
                answerSpecId = "answer-other-math",
                answerSpecSnapshot = "答案",
                answerVerificationStatus = StudyDbValue.VerificationStatus.VERIFIED,
                sourceType = "IMPORT",
                sourceReference = null,
                contentFingerprint = FINGERPRINT_G,
                createdAtEpochMillis = NOW - 89,
            ),
            ProblemRevisionSeedRecord(
                revisionId = CHEMISTRY_REVISION,
                problemId = CHEMISTRY_PROBLEM,
                revisionNumber = 1,
                title = "化学反应",
                problemMarkdown = "判断反应。 ",
                answerSpecId = "chemistry-answer-1",
                answerSpecSnapshot = "反应成立",
                answerVerificationStatus = StudyDbValue.VerificationStatus.VERIFIED,
                sourceType = "IMPORT",
                sourceReference = null,
                contentFingerprint = "chemistry-revision-fingerprint",
                createdAtEpochMillis = NOW - 90,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = UNIT,
                problemId = PROBLEM,
                problemRevisionId = REVISION,
                unitKey = "whole",
                unitKind = "WHOLE",
                title = "函数",
                promptMarkdown = "求解。",
                estimatedSeconds = 60,
                createdAtEpochMillis = NOW - 80,
            ),
            PracticeUnitSeedRecord(
                practiceUnitId = OTHER_UNIT,
                problemId = PROBLEM,
                problemRevisionId = REVISION,
                unitKey = "alternate",
                unitKind = "ALTERNATE",
                title = "函数变式",
                promptMarkdown = "求解变式。",
                estimatedSeconds = 60,
                createdAtEpochMillis = NOW - 79,
            ),
            PracticeUnitSeedRecord(
                practiceUnitId = OTHER_MATH_UNIT,
                problemId = OTHER_MATH_PROBLEM,
                problemRevisionId = OTHER_MATH_REVISION,
                unitKey = "whole",
                unitKind = "WHOLE",
                title = "数列",
                promptMarkdown = "求和。",
                estimatedSeconds = 60,
                createdAtEpochMillis = NOW - 79,
            ),
            PracticeUnitSeedRecord(
                practiceUnitId = CHEMISTRY_UNIT,
                problemId = CHEMISTRY_PROBLEM,
                problemRevisionId = CHEMISTRY_REVISION,
                unitKey = "chemistry-whole",
                unitKind = "WHOLE",
                title = "化学反应",
                promptMarkdown = "判断反应。",
                estimatedSeconds = 60,
                createdAtEpochMillis = NOW - 78,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                ERROR_BOOK_ENTRY,
                UNIT,
                PROBLEM,
                REVISION,
                null,
                acceptedAtEpochMillis = NOW - 75,
                updatedAtEpochMillis = NOW - 75,
            ),
            ErrorBookEntrySeedRecord(
                OTHER_MATH_ERROR_BOOK_ENTRY,
                OTHER_MATH_UNIT,
                OTHER_MATH_PROBLEM,
                OTHER_MATH_REVISION,
                null,
                acceptedAtEpochMillis = NOW - 75,
                updatedAtEpochMillis = NOW - 75,
            ),
            ErrorBookEntrySeedRecord(
                CHEMISTRY_ERROR_BOOK_ENTRY,
                CHEMISTRY_UNIT,
                CHEMISTRY_PROBLEM,
                CHEMISTRY_REVISION,
                null,
                acceptedAtEpochMillis = NOW - 75,
                updatedAtEpochMillis = NOW - 75,
            ),
        ),
        knowledgeNodes = listOf(
            KnowledgeNodeSeedRecord(
                knowledgeNodeId = "knowledge-math",
                stableCode = "math.function",
                subject = "MATH",
                displayName = "函数",
                parentKnowledgeNodeId = null,
                taxonomyVersion = "taxonomy-v1",
                createdAtEpochMillis = NOW - 70,
            ),
            KnowledgeNodeSeedRecord(
                knowledgeNodeId = "knowledge-physics",
                stableCode = "physics.motion",
                subject = "PHYSICS",
                displayName = "运动",
                parentKnowledgeNodeId = null,
                taxonomyVersion = "taxonomy-v1",
                createdAtEpochMillis = NOW - 70,
            ),
            KnowledgeNodeSeedRecord(
                knowledgeNodeId = "knowledge-chemistry",
                stableCode = "chemistry.reaction",
                subject = "CHEMISTRY",
                displayName = "化学反应",
                parentKnowledgeNodeId = null,
                taxonomyVersion = "taxonomy-v1",
                createdAtEpochMillis = NOW - 70,
            ),
        ),
        knowledgeBindings = listOf(
            binding("binding-math", "knowledge-math"),
            binding("binding-physics", "knowledge-physics"),
            KnowledgeBindingSeedRecord(
                bindingId = "binding-other-math",
                practiceUnitId = OTHER_MATH_UNIT,
                knowledgeNodeId = "knowledge-math",
                basisRevisionId = OTHER_MATH_REVISION,
                strength = 1.0,
                sourceType = "VERIFIED",
                taxonomyVersion = "taxonomy-v1",
                acceptedAtEpochMillis = NOW - 60,
            ),
            KnowledgeBindingSeedRecord(
                bindingId = "binding-chemistry",
                practiceUnitId = CHEMISTRY_UNIT,
                knowledgeNodeId = "knowledge-chemistry",
                basisRevisionId = CHEMISTRY_REVISION,
                strength = 1.0,
                sourceType = "VERIFIED",
                taxonomyVersion = "taxonomy-v1",
                acceptedAtEpochMillis = NOW - 60,
            ),
        ),
    )

    private fun binding(id: String, knowledgeNodeId: String) = KnowledgeBindingSeedRecord(
        bindingId = id,
        practiceUnitId = UNIT,
        knowledgeNodeId = knowledgeNodeId,
        basisRevisionId = REVISION,
        strength = 1.0,
        sourceType = "VERIFIED",
        taxonomyVersion = "taxonomy-v1",
        acceptedAtEpochMillis = NOW - 60,
    )

    private companion object {
        const val AUTO_SOURCE_FACT_ID = "__auto_tutor_source_fact_id__"
        const val LEARNER = "learner-observation"
        const val PROJECTION = "learning-observation-projection"
        const val PROBLEM = "problem-observation"
        const val REVISION = "revision-observation"
        const val UNIT = "unit-observation"
        const val OTHER_UNIT = "unit-observation-other"
        const val OTHER_MATH_PROBLEM = "problem-observation-other-math"
        const val OTHER_MATH_REVISION = "revision-observation-other-math"
        const val OTHER_MATH_UNIT = "unit-observation-other-math"
        const val CHEMISTRY_PROBLEM = "problem-observation-chemistry"
        const val CHEMISTRY_REVISION = "revision-observation-chemistry"
        const val CHEMISTRY_UNIT = "unit-observation-chemistry"
        const val ERROR_BOOK_ENTRY = "entry-observation"
        const val OTHER_MATH_ERROR_BOOK_ENTRY = "entry-observation-other-math"
        const val CHEMISTRY_ERROR_BOOK_ENTRY = "entry-observation-chemistry"
        const val ASSESSMENT_SNAPSHOT = "snapshot-observation"
        const val NOW = 1_728_000_000_000L
        val FINGERPRINT_A = "a".repeat(64)
        val FINGERPRINT_B = "b".repeat(64)
        val FINGERPRINT_C = "c".repeat(64)
        val FINGERPRINT_D = "d".repeat(64)
        val FINGERPRINT_E = "e".repeat(64)
        val FINGERPRINT_F = "f".repeat(64)
        val FINGERPRINT_G = "0".repeat(64)
    }
}
