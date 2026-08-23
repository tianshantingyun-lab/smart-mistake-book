package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningProjectorTest {
    private val projector = LearningProjector()

    @Test
    fun `attempt ids are idempotent and weighted bindings update every knowledge node`() {
        val attempt = attempt("attempt-1", 1, setOf("kc-a", "kc-b"), positiveEvidence())

        val first = projector.project(LearnerSnapshot.empty("learner-1"), listOf(attempt), 1)
        val replay = projector.project(first.snapshot, listOf(attempt), 1)

        assertEquals(setOf("attempt-1"), first.appliedAttemptIds)
        assertEquals(setOf("attempt-1"), replay.ignoredAttemptIds)
        assertEquals(first.snapshot, replay.snapshot)
        assertEquals(setOf("kc-a", "kc-b"), first.snapshot.knowledgeMasteryStates.keys)
        assertEquals(1.0, first.snapshot.knowledgeMasteryStates.values.sumOf { it.evidenceMass }, 1e-9)
    }

    @Test
    fun `independent error after high mastery enters conflicted state`() {
        val mastered = KnowledgeMasteryState(
            knowledgeNodeId = "kc-a",
            masteryScore = 0.94,
            conservativeMasteryScore = 0.88,
            evidenceMass = 4.0,
            status = MasteryStatus.MASTERED,
            calibrationSupport = CalibrationSupport.SUPPORTED,
            projectorVersion = LearningProjector.VERSION,
            checkpointSequence = 4,
            lastEvidenceAtEpochMillis = 400,
        )
        val snapshot = LearnerSnapshot(
            learnerId = "learner-1",
            knowledgeMasteryStates = mapOf("kc-a" to mastered),
            checkpoint = ProjectionCheckpoint(4, LearningProjector.VERSION, 400),
            generatedAtEpochMillis = 400,
        )

        val result = projector.project(
            snapshot,
            listOf(attempt("attempt-5", 5, setOf("kc-a"), negativeEvidence())),
            5,
        )

        assertEquals(MasteryStatus.CONFLICTED, result.snapshot.knowledgeMasteryStates.getValue("kc-a").status)
        assertEquals(1, result.snapshot.problemMemoryStates.getValue("unit-1").lapseCount)
    }

    @Test
    fun `same attempt id with conflicting payload is rejected deterministically`() {
        val first = attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())
        val conflicting = first.copy(
            evidence = negativeEvidence(),
            problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
        )

        val result = projector.project(
            LearnerSnapshot.empty("learner-1"), listOf(conflicting, first), 1,
        )

        assertTrue(result.appliedAttemptIds.isEmpty())
        assertEquals(setOf("attempt-1"), result.conflictedAttemptIds)
        assertEquals(LearnerSnapshotFreshness.STALE, result.snapshot.freshness)
    }

    @Test
    fun `identical retries in one projection batch apply exactly once`() {
        val attempt = attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())

        val result = projector.project(
            LearnerSnapshot.empty("learner-1"), listOf(attempt, attempt), 1,
        )

        assertEquals(setOf("attempt-1"), result.appliedAttemptIds)
        assertTrue(result.conflictedAttemptIds.isEmpty())
        assertEquals(1, result.snapshot.problemMemoryStates.size)
    }

    @Test
    fun `device clock rollback is recorded and cannot move memory review time backwards`() {
        val expiredCalibration = CalibrationSnapshot(
            CalibrationSupport.SUPPORTED,
            "calibration-source",
            "short-calibration-v1",
            0,
            3_000,
        )
        val firstSeed = attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())
        val first = firstSeed.copy(
            occurredAtEpochMillis = 5_000,
            assessmentSnapshot = firstSeed.assessmentSnapshot.copy(calibration = expiredCalibration),
        )
        val secondSeed = attempt("attempt-2", 2, setOf("kc-a"), positiveEvidence())
        val second = secondSeed.copy(
            occurredAtEpochMillis = 1_000,
            assessmentSnapshot = secondSeed.assessmentSnapshot.copy(calibration = expiredCalibration),
        )

        val result = projector.project(
            LearnerSnapshot.empty("learner-1"), listOf(first, second), 2,
        )

        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")
        assertEquals(5_000, memory.lastReviewedAtEpochMillis)
        assertEquals(1, memory.clockAnomalyCount)
        assertTrue(memory.nextReviewAtEpochMillis >= memory.lastReviewedAtEpochMillis)
        assertEquals(
            5_000L,
            result.snapshot.knowledgeMasteryStates.getValue("kc-a").lastEvidenceAtEpochMillis,
        )
        assertEquals(
            CalibrationSupport.UNKNOWN,
            result.snapshot.knowledgeMasteryStates.getValue("kc-a").calibrationSupport,
        )
    }

    @Test
    fun `valid calibration snapshot carries current support into mastery state`() {
        val result = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())),
            1,
        )

        assertEquals(
            CalibrationSupport.SUPPORTED,
            result.snapshot.knowledgeMasteryStates.getValue("kc-a").calibrationSupport,
        )
    }

    @Test
    fun `visible tutor answer updates only problem memory once and schedules a short review`() {
        val learned = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("attempt-1", 1, setOf("kc-a"), positiveEvidence())),
            1,
        ).snapshot
        val masteryBefore = learned.knowledgeMasteryStates
        val exposure = TutorAnswerExposureOutcome(
            outcomeId = "tutor-exposure-outcome-1",
            exposureId = "tutor-exposure-1",
            sessionId = "tutor-session-1",
            questionDocumentId = "question-document-1",
            questionRevisionNumber = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            problemRevisionId = "revision-1",
            practiceUnitId = "unit-1",
            occurredAtEpochMillis = 2_000,
            eventSequence = 2,
        )

        val projected = projector.project(learned, listOf(exposure), 2)
        val retried = projector.project(projected.snapshot, listOf(exposure), 2)
        val memory = projected.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(setOf(exposure.outcomeId), projected.appliedTutorAnswerExposureOutcomeIds)
        assertEquals(1, memory.answerRevealCount)
        assertEquals(1, memory.lapseCount)
        assertEquals(2_000L + 10 * 60_000L, memory.nextReviewAtEpochMillis)
        assertEquals(masteryBefore, projected.snapshot.knowledgeMasteryStates)
        assertEquals(setOf(exposure.outcomeId), retried.ignoredTutorAnswerExposureOutcomeIds)
        assertEquals(projected.snapshot, retried.snapshot)
    }

    private fun attempt(
        id: String,
        sequence: Long,
        knowledgeNodeIds: Set<String>,
        evidence: LearningEvidence,
    ): Attempt {
        val weight = 1.0 / knowledgeNodeIds.size
        return Attempt(
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
                    CalibrationSupport.SUPPORTED,
                    "calibration-source",
                    "calibration-v1",
                    0,
                    100_000,
                ),
                attributions = knowledgeNodeIds.sorted().mapIndexed { index, knowledgeNodeId ->
                    KnowledgeEvidenceAttribution(
                        bindingId = "binding-$id-$knowledgeNodeId",
                        knowledgeNodeId = knowledgeNodeId,
                        weight = weight,
                        basisRevisionId = "revision-1",
                        taxonomyVersion = "taxonomy-v1",
                        role = if (index == 0) EvidenceAttributionRole.PRIMARY else EvidenceAttributionRole.SECONDARY,
                        certainty = EvidenceAttributionCertainty.DIRECT,
                    )
                },
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
    }

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
