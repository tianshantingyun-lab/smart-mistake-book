package com.tingyun.smartmistakebook.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.dao.EVENT_KIND_LEARNING_OBSERVATION
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.AppliedLearningObservationRecord
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidate
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidateStatus
import com.tingyun.smartmistakebook.core.model.LearningObservationDirection
import com.tingyun.smartmistakebook.core.model.LearningObservationEvidenceLevel
import com.tingyun.smartmistakebook.core.model.LearningObservationIndependence
import com.tingyun.smartmistakebook.core.model.LearningObservationKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        val transitioned = store.compareAndSetLearningObservationCandidateStatus(
            LearningObservationCandidateStatusChangeCommand(
                candidateId = candidate.candidateId,
                expectedStatus = LearningObservationCandidateStatus.PENDING_CONFIRMATION,
                newStatus = LearningObservationCandidateStatus.READY,
                expectedRetryCount = 0,
                incrementRetry = false,
                updatedAtEpochMillis = NOW + 1,
            ),
        )
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
        assertFalse(stale.updated)
        assertEquals(LearningObservationCandidateStatus.READY, stale.candidate.status)
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
    fun uncommittedProjectionReloadsSameEventAndAppliedRecordSurvivesCommit() = runBlocking {
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
        val afterCrash = store.loadProjectionBatch(PROJECTION, LEARNER, 10)

        assertEquals(firstLoad.events, afterCrash.events)

        val fingerprint = requireNotNull(materialized.canonicalFingerprint)
        val snapshot = LearnerSnapshot(
            learnerId = LEARNER,
            checkpoint = ProjectionCheckpoint(
                lastSequence = event.eventSequence,
                projectorVersion = PROJECTOR_VERSION,
                projectedAtEpochMillis = event.occurredAtEpochMillis,
            ),
            knownLedgerHeadSequence = event.eventSequence,
            generatedAtEpochMillis = event.occurredAtEpochMillis,
            freshness = LearnerSnapshotFreshness.CURRENT,
            projectionStatus = ProjectionStatus.CURRENT,
            appliedLearningObservationRecords = mapOf(
                event.eventId to AppliedLearningObservationRecord(
                    observationEventId = event.eventId,
                    canonicalFingerprint = fingerprint,
                    eventSequence = event.eventSequence,
                ),
            ),
        )
        store.commitProjection(
            ProjectionCommit(
                projectionName = PROJECTION,
                learnerId = LEARNER,
                expectedPreviousCheckpoint = 0,
                expectedPreviousStateVersion = 0,
                mode = ProjectionCommitMode.INCREMENTAL,
                knownLedgerHeadSequence = event.eventSequence,
                consumedLedgerEvents = listOf(
                    ConsumedLedgerEventReceipt(
                        eventKind = EVENT_KIND_LEARNING_OBSERVATION,
                        eventId = event.eventId,
                        eventSequence = event.eventSequence,
                        canonicalFingerprint = fingerprint,
                    ),
                ),
                presentationProjectionStates = emptyMap(),
                snapshot = snapshot,
            ),
        )

        val persisted = requireNotNull(
            store.readCurrentLearnerSnapshot(PROJECTION, LEARNER),
        )
        assertEquals(
            snapshot.appliedLearningObservationRecords,
            persisted.snapshot.appliedLearningObservationRecords,
        )
        assertTrue(store.loadProjectionBatch(PROJECTION, LEARNER, 10).events.isEmpty())
    }

    private suspend fun ready(candidate: LearningObservationCandidate): LearningObservationCandidate {
        store.submitLearningObservationCandidate(candidate)
        return store.compareAndSetLearningObservationCandidateStatus(
            LearningObservationCandidateStatusChangeCommand(
                candidateId = candidate.candidateId,
                expectedStatus = candidate.status,
                newStatus = LearningObservationCandidateStatus.READY,
                expectedRetryCount = candidate.retryCount,
                incrementRetry = false,
                updatedAtEpochMillis = NOW + 1,
            ),
        ).candidate
    }

    private fun candidate(
        candidateId: String = "candidate-1",
        bindingId: String = "binding-math",
        knowledgeNodeId: String = "knowledge-math",
        evidenceLevel: LearningObservationEvidenceLevel =
            LearningObservationEvidenceLevel.HIGH_CONFIDENCE,
    ) = LearningObservationCandidate(
        candidateId = candidateId,
        learnerId = LEARNER,
        source = LearningObservationSource.TUTOR_CHOICE,
        sourceReferenceId = "choice-$candidateId",
        practiceUnitId = UNIT,
        problemRevisionId = REVISION,
        direction = LearningObservationDirection.POSITIVE,
        evidenceLevel = evidenceLevel,
        evidenceWeight = 0.8,
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
        const val PROJECTOR_VERSION = "learning-observation-projector-v1"
        const val PROBLEM = "problem-observation"
        const val REVISION = "revision-observation"
        const val UNIT = "unit-observation"
        const val NOW = 1_728_000_000_000L
    }
}
