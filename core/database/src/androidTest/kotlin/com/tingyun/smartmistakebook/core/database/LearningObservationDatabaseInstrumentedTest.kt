package com.tingyun.smartmistakebook.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.dao.EVENT_KIND_LEARNING_OBSERVATION
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewReason
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewStatus
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidate
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidateStatus
import com.tingyun.smartmistakebook.core.model.LearningObservationDirection
import com.tingyun.smartmistakebook.core.model.LearningObservationEvidenceLevel
import com.tingyun.smartmistakebook.core.model.LearningObservationIndependence
import com.tingyun.smartmistakebook.core.model.LearningObservationKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.StudyDayContext
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

    @Before
    fun setUp() = runBlocking {
        store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
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

        assertTrue(store.submitLearningObservationCandidate(candidate).created)
        assertFalse(store.submitLearningObservationCandidate(candidate).created)
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
    fun provenanceRejectsChangedPayloadAndDifferentCandidateIdentity() = runBlocking {
        val original = candidate(
            candidateId = "candidate-provenance",
            sourceReferenceId = "choice-one-real-source",
        )
        registerAuthority(original)
        store.submitLearningObservationCandidate(original)

        assertImmutableConflict {
            store.submitLearningObservationCandidate(original.copy(evidenceWeight = 0.7))
        }
        assertImmutableConflict {
            store.submitLearningObservationCandidate(
                candidate(
                    candidateId = "candidate-provenance-alias",
                    sourceReferenceId = original.sourceReferenceId,
                ),
            )
        }

        assertEquals(
            original,
            store.readLearningObservationCandidate(original.candidateId),
        )
        assertNull(store.readLearningObservationCandidate("candidate-provenance-alias"))
    }

    @Test
    fun sourceAuthorityIsImmutableAndRequiredForReadyOrMaterialization() = runBlocking {
        val authorized = candidate(
            candidateId = "candidate-authority",
            sourceReferenceId = "choice-authority",
        )
        val authority = authority(authorized)
        assertTrue(store.registerLearningObservationSourceAuthority(authority).created)
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
        store.submitLearningObservationCandidate(missing)
        assertSourceAuthorityFailure { markReady(missing) }
        val missingResult = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = missing.candidateId,
                eventId = "observation-authority-missing",
                confirmedAtEpochMillis = NOW + 2,
            ),
        )
        assertEquals(
            LearningEvidenceReviewReason.SOURCE_AUTHORITY_MISSING,
            requireNotNull(missingResult.reviewCase).reason,
        )

        val crossLearner = candidate(
            candidateId = "candidate-cross-learner",
            learnerId = "learner-without-authority",
            sourceReferenceId = authority.sourceReferenceId,
        )
        store.submitLearningObservationCandidate(crossLearner)
        assertSourceAuthorityFailure { markReady(crossLearner) }

        val wrongAnchor = candidate(
            candidateId = "candidate-wrong-anchor",
            sourceReferenceId = "choice-wrong-anchor",
        )
        store.registerLearningObservationSourceAuthority(
            authority(wrongAnchor, practiceUnitId = OTHER_UNIT),
        )
        store.submitLearningObservationCandidate(wrongAnchor)
        assertSourceAuthorityFailure { markReady(wrongAnchor) }
        val mismatchResult = store.materializeLearningObservation(
            MaterializeLearningObservationCommand(
                candidateId = wrongAnchor.candidateId,
                eventId = "observation-wrong-anchor",
                confirmedAtEpochMillis = NOW + 2,
            ),
        )
        assertEquals(
            LearningEvidenceReviewReason.SOURCE_AUTHORITY_MISMATCH,
            requireNotNull(mismatchResult.reviewCase).reason,
        )
        assertEquals(0L, store.loadProjectionBatch(PROJECTION, LEARNER, 10).ledgerHeadSequence)
    }

    @Test
    fun successfulMaterializationResolvesEarlierNotReadyReview() = runBlocking {
        val candidate = candidate(candidateId = "candidate-review-resolution")
        store.submitLearningObservationCandidate(candidate)
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
        assertTrue(batch.events[1].event is AttributedLearningObservationEvent)
        assertTrue(batch.events[2].event is Attempt)
        assertEquals(3L, batch.ledgerHeadSequence)
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

        assertTrue(first.created)
        assertFalse(replay.created)
        assertEquals(first.event, replay.event)
        assertEquals(1L, requireNotNull(first.event).eventSequence)
        assertEquals(1, batch.events.size)
        assertEquals(EVENT_KIND_LEARNING_OBSERVATION, batch.events.single().outbox.eventKind)
        assertEquals(1L, batch.ledgerHeadSequence)
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
        store.submitLearningObservationCandidate(candidate)
        registerAuthority(candidate)
        return markReady(candidate)
    }

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
        practiceUnitId: String = UNIT,
        problemRevisionId: String = REVISION,
        bindingId: String = "binding-math",
        knowledgeNodeId: String = "knowledge-math",
        evidenceLevel: LearningObservationEvidenceLevel =
            LearningObservationEvidenceLevel.HIGH_CONFIDENCE,
        evidenceWeight: Double = 0.8,
    ) = LearningObservationCandidate(
        candidateId = candidateId,
        learnerId = learnerId,
        source = LearningObservationSource.TUTOR_CHOICE,
        sourceReferenceId = sourceReferenceId,
        practiceUnitId = practiceUnitId,
        problemRevisionId = problemRevisionId,
        direction = LearningObservationDirection.POSITIVE,
        evidenceLevel = evidenceLevel,
        evidenceWeight = evidenceWeight,
        independence = LearningObservationIndependence.INDEPENDENT,
        proposedAttributions = listOf(
            LearningObservationKnowledgeAttribution(
                bindingId = bindingId,
                knowledgeNodeId = knowledgeNodeId,
                weight = 1.0,
                basisRevisionId = REVISION,
                taxonomyVersion = "taxonomy-v1",
                role = EvidenceAttributionRole.PRIMARY,
                certainty = EvidenceAttributionCertainty.DIRECT,
            ),
        ),
        occurredAtEpochMillis = NOW - 10,
        modelVersion = "model-v1",
        evidenceLocator = "response:$candidateId",
        status = LearningObservationCandidateStatus.PENDING_CONFIRMATION,
        retryCount = 0,
        createdAtEpochMillis = NOW,
        updatedAtEpochMillis = NOW,
    )

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
    )

    private suspend fun registerAuthority(candidate: LearningObservationCandidate) {
        store.registerLearningObservationSourceAuthority(authority(candidate))
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

    private fun seed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(PROBLEM, "problem-fingerprint", "MATH", NOW - 100),
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
                contentFingerprint = "revision-fingerprint",
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
        ),
        errorBookEntries = emptyList(),
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
        ),
        knowledgeBindings = listOf(
            binding("binding-math", "knowledge-math"),
            binding("binding-physics", "knowledge-physics"),
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
        const val LEARNER = "learner-observation"
        const val PROJECTION = "learning-observation-projection"
        const val PROBLEM = "problem-observation"
        const val REVISION = "revision-observation"
        const val UNIT = "unit-observation"
        const val OTHER_UNIT = "unit-observation-other"
        const val ASSESSMENT_SNAPSHOT = "snapshot-observation"
        const val NOW = 1_728_000_000_000L
    }
}
