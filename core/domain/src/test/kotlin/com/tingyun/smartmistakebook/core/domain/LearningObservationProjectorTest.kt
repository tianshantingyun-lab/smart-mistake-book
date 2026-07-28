package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LearningObservationDirection
import com.tingyun.smartmistakebook.core.model.LearningObservationEvidenceLevel
import com.tingyun.smartmistakebook.core.model.LearningObservationIndependence
import com.tingyun.smartmistakebook.core.model.LearningObservationKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningObservationProjectorTest {
    private val projector = LearningProjector()

    @Test
    fun `confirmed observation updates only direct mastery and never problem memory`() {
        val event = observation(
            sequence = 1,
            direction = LearningObservationDirection.POSITIVE,
            attributions = listOf(
                attribution(
                    bindingId = "binding-direct",
                    knowledgeNodeId = "knowledge-direct",
                    certainty = EvidenceAttributionCertainty.DIRECT,
                ),
                attribution(
                    bindingId = "binding-ambiguous",
                    knowledgeNodeId = "knowledge-ambiguous",
                    certainty = EvidenceAttributionCertainty.AMBIGUOUS,
                ),
            ),
        )

        val result = projector.project(
            previous = LearnerSnapshot.empty("learner-1", LearningProjector.VERSION),
            events = listOf(event),
            knownLedgerHeadSequence = 1,
        )

        assertTrue(result.snapshot.problemMemoryStates.isEmpty())
        assertEquals(setOf("knowledge-direct"), result.snapshot.knowledgeMasteryStates.keys)
        assertEquals(setOf(event.eventId), result.appliedLearningObservationEventIds)
        assertEquals(setOf(event.eventId), result.ambiguousLearningObservationEventIds)
    }

    @Test
    fun `positive and negative observations persist and exact crash replay is idempotent`() {
        val positive = observation(
            sequence = 1,
            direction = LearningObservationDirection.POSITIVE,
        )
        val first = projector.project(
            previous = LearnerSnapshot.empty("learner-1", LearningProjector.VERSION),
            events = listOf(positive),
            knownLedgerHeadSequence = 1,
        )
        val afterPositive = first.snapshot.knowledgeMasteryStates.getValue("knowledge-direct")

        val crashReplay = projector.project(
            previous = first.snapshot,
            events = listOf(positive),
            knownLedgerHeadSequence = 1,
        )
        assertEquals(first.snapshot, crashReplay.snapshot)
        assertEquals(setOf(positive.eventId), crashReplay.ignoredLearningObservationEventIds)

        val negative = observation(
            sequence = 2,
            direction = LearningObservationDirection.NEGATIVE,
            eventId = "observation-negative",
            candidateId = "candidate-negative",
            occurredAtEpochMillis = 2_000,
        )
        val second = projector.project(
            previous = first.snapshot,
            events = listOf(negative),
            knownLedgerHeadSequence = 2,
        )
        val afterNegative = second.snapshot.knowledgeMasteryStates.getValue("knowledge-direct")

        assertTrue(afterNegative.probabilityIndependentCorrect < afterPositive.probabilityIndependentCorrect)
        assertTrue(afterNegative.evidenceMass > afterPositive.evidenceMass)
        assertEquals(2_000L, afterNegative.lastEvidenceAtEpochMillis)
        assertFalse(second.snapshot.problemMemoryStates.containsKey("unit-1"))
    }

    @Test
    fun `delayed confirmation preserves occurrence time in incremental and full replay`() {
        val positive = observation(
            sequence = 1,
            direction = LearningObservationDirection.POSITIVE,
            occurredAtEpochMillis = 2_000,
            confirmedAtEpochMillis = 1_000_000,
        )
        val afterPositive = projector.project(
            previous = LearnerSnapshot.empty("learner-1", LearningProjector.VERSION),
            events = listOf(positive),
            knownLedgerHeadSequence = 1,
        )
        val positiveMastery =
            afterPositive.snapshot.knowledgeMasteryStates.getValue("knowledge-direct")

        assertEquals(2_000L, positiveMastery.lastEvidenceAtEpochMillis)
        assertEquals(null, positiveMastery.lastIndependentErrorAtEpochMillis)
        assertEquals(2_000L, afterPositive.snapshot.checkpoint.projectedAtEpochMillis)
        assertEquals(2_000L, afterPositive.snapshot.generatedAtEpochMillis)

        val negative = observation(
            sequence = 2,
            direction = LearningObservationDirection.NEGATIVE,
            eventId = "observation-delayed-negative",
            candidateId = "candidate-delayed-negative",
            occurredAtEpochMillis = 1_000,
            confirmedAtEpochMillis = 2_000_000,
        )
        val incremental = projector.project(
            previous = afterPositive.snapshot,
            events = listOf(negative),
            knownLedgerHeadSequence = 2,
        )
        val incrementalMastery =
            incremental.snapshot.knowledgeMasteryStates.getValue("knowledge-direct")

        assertEquals(2_000L, incrementalMastery.lastEvidenceAtEpochMillis)
        assertEquals(1_000L, incrementalMastery.lastIndependentErrorAtEpochMillis)
        assertEquals(2_000L, incremental.snapshot.checkpoint.projectedAtEpochMillis)
        assertEquals(2_000L, incremental.snapshot.generatedAtEpochMillis)

        val replay = projector.replay("learner-1", listOf(negative, positive))

        assertEquals(incremental.snapshot, replay.snapshot)
        assertEquals(
            incrementalMastery,
            replay.snapshot.knowledgeMasteryStates.getValue("knowledge-direct"),
        )
    }

    @Test
    fun `full replay includes observations and is deterministic`() {
        val first = observation(1, LearningObservationDirection.POSITIVE)
        val second = observation(
            sequence = 2,
            direction = LearningObservationDirection.NEGATIVE,
            occurredAtEpochMillis = 2_000,
        )

        val replay = projector.replay("learner-1", listOf(second, first))
        val repeated = projector.replay("learner-1", listOf(second, first))

        assertEquals(replay, repeated)
        assertEquals(
            setOf(first.eventId, second.eventId),
            replay.appliedLearningObservationEventIds,
        )
        assertEquals(
            setOf(first.eventId, second.eventId),
            replay.snapshot.appliedLearningObservationRecords.keys,
        )
        assertEquals(2L, replay.snapshot.checkpoint.lastSequence)
        assertTrue(replay.snapshot.problemMemoryStates.isEmpty())
    }

    @Test
    fun `gap defers later observation and out of order recovery catches up`() {
        val first = observation(1, LearningObservationDirection.POSITIVE)
        val second = observation(2, LearningObservationDirection.POSITIVE)
        val third = observation(3, LearningObservationDirection.POSITIVE)

        val waiting = projector.project(
            LearnerSnapshot.empty("learner-1", LearningProjector.VERSION),
            listOf(third, first),
            3,
        )

        assertEquals(setOf(first.eventId), waiting.appliedLearningObservationEventIds)
        assertEquals(setOf(third.eventId), waiting.deferredLearningObservationEventIds)
        assertEquals(2L, waiting.missingSequence)
        assertEquals(1L, waiting.snapshot.checkpoint.lastSequence)
        assertEquals(ProjectionStatus.WAITING_FOR_GAP, waiting.snapshot.projectionStatus)

        val caughtUp = projector.project(waiting.snapshot, listOf(third, second), 3)

        assertEquals(
            setOf(second.eventId, third.eventId),
            caughtUp.appliedLearningObservationEventIds,
        )
        assertTrue(caughtUp.deferredLearningObservationEventIds.isEmpty())
        assertEquals(null, caughtUp.missingSequence)
        assertEquals(3L, caughtUp.snapshot.checkpoint.lastSequence)
        assertEquals(ProjectionStatus.CURRENT, caughtUp.snapshot.projectionStatus)
    }

    @Test
    fun `same observation event id with different payload conflicts`() {
        val original = observation(1, LearningObservationDirection.POSITIVE)
        val conflicting = original.copy(evidenceWeight = 0.4)

        val result = projector.project(
            LearnerSnapshot.empty("learner-1", LearningProjector.VERSION),
            listOf(conflicting, original),
            1,
        )

        assertTrue(result.appliedLearningObservationEventIds.isEmpty())
        assertEquals(setOf(original.eventId), result.conflictedLearningObservationEventIds)
        assertTrue(result.deferredLearningObservationEventIds.isEmpty())
        assertEquals(0L, result.snapshot.checkpoint.lastSequence)
        assertEquals(ProjectionStatus.CONFLICTED, result.snapshot.projectionStatus)
        assertTrue(result.snapshot.knowledgeMasteryStates.isEmpty())
    }

    @Test
    fun `mixed attempt correction and observation replay preserves every ledger type`() {
        val wrong = attempt(
            id = "attempt-1",
            sequence = 1,
            knowledgeNodeId = "knowledge-attempt",
            evidence = negativeEvidence(),
        )
        val observed = observation(
            sequence = 2,
            direction = LearningObservationDirection.POSITIVE,
            occurredAtEpochMillis = 2_000,
        )
        val correction = AttemptCorrection(
            correctionId = "correction-1",
            attemptId = wrong.attemptId,
            replacementEvidence = positiveEvidence(),
            replacementMemoryOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
            reasonMarkdown = "人工复核确认原答案正确",
            occurredAtEpochMillis = 3_000,
            eventSequence = 3,
        )

        val replay = projector.replay("learner-1", listOf(observed, correction, wrong))
        val memory = replay.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(setOf(wrong.attemptId), replay.appliedAttemptIds)
        assertEquals(setOf(wrong.attemptId), replay.correctedAttemptIds)
        assertEquals(setOf(observed.eventId), replay.appliedLearningObservationEventIds)
        assertEquals(
            setOf(correction.correctionId),
            replay.snapshot.appliedCorrectionRecords.keys,
        )
        assertEquals(
            setOf("knowledge-attempt", "knowledge-direct"),
            replay.snapshot.knowledgeMasteryStates.keys,
        )
        assertEquals(1, memory.independentCorrectCount)
        assertEquals(0, memory.lapseCount)
        assertEquals(3L, replay.snapshot.checkpoint.lastSequence)
        assertEquals(3_000L, replay.snapshot.correctionWatermarkEpochMillis)
    }

    private fun observation(
        sequence: Long,
        direction: LearningObservationDirection,
        eventId: String = "observation-$sequence",
        candidateId: String = "candidate-$sequence",
        occurredAtEpochMillis: Long = 1_000,
        confirmedAtEpochMillis: Long = occurredAtEpochMillis,
        attributions: List<LearningObservationKnowledgeAttribution> = listOf(
            attribution(
                bindingId = "binding-direct",
                knowledgeNodeId = "knowledge-direct",
                certainty = EvidenceAttributionCertainty.DIRECT,
            ),
        ),
    ) = AttributedLearningObservationEvent(
        eventId = eventId,
        candidateId = candidateId,
        learnerId = "learner-1",
        practiceUnitId = "unit-1",
        problemRevisionId = "revision-1",
        direction = direction,
        evidenceLevel = LearningObservationEvidenceLevel.CONFIRMED,
        evidenceWeight = 0.8,
        independence = LearningObservationIndependence.INDEPENDENT,
        attributions = attributions,
        occurredAtEpochMillis = occurredAtEpochMillis,
        confirmedAtEpochMillis = confirmedAtEpochMillis,
        modelVersion = "model-v1",
        evidenceLocator = "response:$eventId",
        eventSequence = sequence,
    )

    private fun attribution(
        bindingId: String,
        knowledgeNodeId: String,
        certainty: EvidenceAttributionCertainty,
    ) = LearningObservationKnowledgeAttribution(
        bindingId = bindingId,
        knowledgeNodeId = knowledgeNodeId,
        weight = 1.0,
        basisRevisionId = "revision-1",
        taxonomyVersion = "taxonomy-v1",
        role = EvidenceAttributionRole.PRIMARY,
        certainty = certainty,
    )

    private fun attempt(
        id: String,
        sequence: Long,
        knowledgeNodeId: String,
        evidence: LearningEvidence,
    ) = Attempt(
        attemptId = id,
        presentationId = "presentation-$id",
        responseOrdinal = 1,
        assessmentSnapshot = AssessmentEvidenceSnapshot(
            snapshotId = "snapshot-$id",
            assessmentItemId = "assessment-$id",
            practiceUnitId = "unit-1",
            problemRevisionId = "revision-1",
            answerSpecId = "answer-1",
            itemFamilyId = "family-$id",
            sourceBundleId = "source-$id",
            taxonomyVersion = "taxonomy-v1",
            verification = AssessmentSnapshotVerification.VERIFIED,
            calibration = CalibrationSnapshot(
                support = CalibrationSupport.SUPPORTED,
                sourceId = "calibration-source",
                version = "calibration-v1",
                validFromEpochMillis = 0,
                validUntilEpochMillis = 100_000,
            ),
            attributions = listOf(
                KnowledgeEvidenceAttribution(
                    bindingId = "binding-$id-$knowledgeNodeId",
                    knowledgeNodeId = knowledgeNodeId,
                    weight = 1.0,
                    basisRevisionId = "revision-1",
                    taxonomyVersion = "taxonomy-v1",
                    role = EvidenceAttributionRole.PRIMARY,
                    certainty = EvidenceAttributionCertainty.DIRECT,
                ),
            ),
            capturedAtEpochMillis = 0,
        ),
        evidence = evidence,
        problemMemoryOutcome = if (evidence.signedWeight > 0) {
            ProblemMemoryOutcome.INDEPENDENT_RECALL
        } else {
            ProblemMemoryOutcome.RETRIEVAL_FAILURE
        },
        occurredAtEpochMillis = sequence * 1_000,
        durationSeconds = 60,
        studyDay = StudyDayContext(sequence, "Asia/Shanghai", 480),
        eventSequence = sequence,
    )

    private fun positiveEvidence() = LearningEvidence(
        LearningEvidenceDirection.POSITIVE,
        1.0,
        LearningEvidenceReason.INDEPENDENT_CORRECT,
    )

    private fun negativeEvidence() = LearningEvidence(
        LearningEvidenceDirection.NEGATIVE,
        1.0,
        LearningEvidenceReason.INDEPENDENT_INCORRECT,
    )
}
