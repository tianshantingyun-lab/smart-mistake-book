package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveQuestionSelectorTest {
    private val selector = AdaptiveQuestionSelector()
    private val foundation = AdaptiveQuestionCandidate(
        assessmentItemId = "assessment-foundation",
        knowledgeNodeIds = setOf("kc-a"),
        itemFamilyId = "candidate-family",
        targetKind = AdaptiveTargetKind.FOUNDATION,
        contentVerified = true,
        answerWouldBeRevealed = false,
        informationValue = 0.8,
        predictedCorrectness = prediction(0.65, 0.75),
    )
    private val calibration = foundation.copy(
        assessmentItemId = "assessment-calibration",
        itemFamilyId = "calibration-family",
        targetKind = AdaptiveTargetKind.CALIBRATION,
        informationValue = 1.0,
    )

    @Test
    fun `available candidates are ignored until the user explicitly requests another question`() {
        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(masteredState().copy(status = MasteryStatus.LEARNING)),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                candidates = listOf(foundation, calibration),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.NO_SAFE_CANDIDATE, decision.kind)
        assertEquals(null, decision.selectedAssessmentItemId)
        assertEquals(true, "QUESTION_NOT_REQUESTED" in decision.reasonCodes)
    }

    @Test
    fun `an explicitly requested calibration may use a verified calibration item`() {
        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(masteredState()),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.CALIBRATION,
                userRequestedQuestion = true,
                candidates = listOf(calibration),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.ASK_CALIBRATION, decision.kind)
        assertEquals("assessment-calibration", decision.selectedAssessmentItemId)
    }

    @Test
    fun `two independent correct families on two study days can skip foundation`() {
        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(masteredState()),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(foundation),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.SKIP_MASTERED_FOUNDATION, decision.kind)
        assertEquals(null, decision.selectedAssessmentItemId)
    }

    @Test
    fun `same family repeated on two days does not skip foundation`() {
        val state = masteredState().copy(
            independentCorrectObservations = listOf(
                observation("family-a", 1, 100),
                observation("family-a", 2, 200),
            ),
        )

        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(state),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(foundation, calibration),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.ASK, decision.kind)
        assertEquals("assessment-foundation", decision.selectedAssessmentItemId)
    }

    @Test
    fun `stale snapshot does not trigger an automatic calibration question`() {
        val staleSnapshot = snapshot(masteredState()).copy(freshness = LearnerSnapshotFreshness.STALE)

        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = staleSnapshot,
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(foundation, calibration),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.NO_SAFE_CANDIDATE, decision.kind)
        assertEquals(null, decision.selectedAssessmentItemId)
    }

    @Test
    fun `unknown and conflicted mastery never skips`() {
        listOf(MasteryStatus.UNKNOWN, MasteryStatus.CONFLICTED).forEach { status ->
            val decision = selector.select(
                AdaptiveSelectionRequest(
                    learnerSnapshot = snapshot(masteredState().copy(status = status)),
                    targetKnowledgeNodeIds = setOf("kc-a"),
                    requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                    userRequestedQuestion = true,
                    candidates = listOf(foundation, calibration),
                    decisionAtEpochMillis = 800,
                ),
            )

            assertEquals(AdaptiveDecisionKind.NO_SAFE_CANDIDATE, decision.kind)
        }
    }

    @Test
    fun `a foundation request is never silently replaced by a calibration question`() {
        val multiPurposeItem = foundation.copy(
            predictedCorrectness = null,
            additionalEligibleTargetKinds = setOf(AdaptiveTargetKind.CALIBRATION),
        )

        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(masteredState().copy(status = MasteryStatus.UNKNOWN)),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(multiPurposeItem),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.NO_SAFE_CANDIDATE, decision.kind)
        assertEquals(null, decision.selectedAssessmentItemId)
    }

    @Test
    fun `newer independent error invalidates earlier skip evidence`() {
        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(
                    masteredState().copy(lastIndependentErrorAtEpochMillis = 300),
                ),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(foundation, calibration),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.ASK, decision.kind)
        assertEquals("assessment-foundation", decision.selectedAssessmentItemId)
    }

    @Test
    fun `tiny post error evidence cannot borrow old weight to skip foundation`() {
        val calibrationSnapshot = observation("seed", 1, 100).calibration
        val state = masteredState().copy(
            independentCorrectObservations = listOf(
                IndependentCorrectObservation(
                    "old-a", 1, 100, eventSequence = 1, evidenceWeight = 1.0,
                    calibration = calibrationSnapshot,
                ),
                IndependentCorrectObservation(
                    "old-b", 2, 200, eventSequence = 2, evidenceWeight = 1.0,
                    calibration = calibrationSnapshot,
                ),
                IndependentCorrectObservation(
                    "new-a", 3, 400, eventSequence = 4, evidenceWeight = 0.01,
                    calibration = calibrationSnapshot,
                ),
                IndependentCorrectObservation(
                    "new-b", 4, 500, eventSequence = 5, evidenceWeight = 0.01,
                    calibration = calibrationSnapshot,
                ),
            ),
            lastIndependentErrorAtEpochMillis = 300,
            lastIndependentErrorSequence = 3,
            lastEvidenceAtEpochMillis = 500,
        )

        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(state),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(foundation, calibration),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.ASK, decision.kind)
        assertEquals("assessment-foundation", decision.selectedAssessmentItemId)
    }

    @Test
    fun `event sequence keeps a newer error authoritative without probing with another question`() {
        val state = masteredState().copy(
            independentCorrectObservations = listOf(
                IndependentCorrectObservation("family-a", 1, 5_000, eventSequence = 1),
                IndependentCorrectObservation("family-b", 2, 6_000, eventSequence = 2),
            ),
            lastIndependentErrorAtEpochMillis = 1_000,
            lastIndependentErrorSequence = 3,
        )

        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(state),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(foundation, calibration),
                decisionAtEpochMillis = 7_000,
            ),
        )

        assertEquals(AdaptiveDecisionKind.NO_SAFE_CANDIDATE, decision.kind)
    }

    @Test
    fun `mastery never skips an explicit transfer challenge`() {
        val transfer = foundation.copy(
            assessmentItemId = "assessment-transfer",
            itemFamilyId = "transfer-family",
            targetKind = AdaptiveTargetKind.TRANSFER,
        )

        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(masteredState()),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.TRANSFER,
                userRequestedQuestion = true,
                candidates = listOf(transfer),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.ASK, decision.kind)
        assertEquals("assessment-transfer", decision.selectedAssessmentItemId)
    }

    @Test
    fun `unknown prediction pauses instead of probing with calibration`() {
        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(masteredState().copy(status = MasteryStatus.LEARNING)),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(foundation.copy(predictedCorrectness = null), calibration),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.NO_SAFE_CANDIDATE, decision.kind)
    }

    @Test
    fun `information value cannot override hard challenge corridor`() {
        val tooEasy = foundation.copy(
            informationValue = 1.0,
            predictedCorrectness = prediction(0.9, 0.98),
        )
        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(masteredState().copy(status = MasteryStatus.LEARNING)),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(tooEasy, calibration),
                decisionAtEpochMillis = 800,
            ),
        )

        assertEquals(AdaptiveDecisionKind.NO_SAFE_CANDIDATE, decision.kind)
    }

    @Test
    fun `expired calibration support pauses without adding a question`() {
        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(masteredState()),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(
                    foundation.copy(predictedCorrectness = prediction(0.65, 0.75, 1_000_000)),
                    calibration,
                ),
                decisionAtEpochMillis = 200_000,
            ),
        )

        assertEquals(AdaptiveDecisionKind.NO_SAFE_CANDIDATE, decision.kind)
    }

    @Test
    fun `old mastery evidence cannot skip a fresh foundation check`() {
        val decisionAt = ClearlyMasteredForSkipPolicy.MAX_EVIDENCE_AGE_MILLIS + 1_000
        val longLivedCalibration = CalibrationSnapshot(
            CalibrationSupport.SUPPORTED,
            "calibration-source",
            "calibration-v2",
            0,
            decisionAt + 1,
        )
        val staleMastery = masteredState().copy(
            independentCorrectObservations = masteredState().independentCorrectObservations.map {
                it.copy(calibration = longLivedCalibration)
            },
        )
        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(staleMastery),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(
                    foundation.copy(predictedCorrectness = prediction(0.65, 0.75, decisionAt + 1)),
                    calibration,
                ),
                decisionAtEpochMillis = decisionAt,
            ),
        )

        assertEquals(AdaptiveDecisionKind.NO_SAFE_CANDIDATE, decision.kind)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `adaptive decision cannot precede a correction watermark`() {
        val correctedSnapshot = snapshot(masteredState()).copy(
            correctionWatermarkEpochMillis = 1_200,
        )

        AdaptiveSelectionRequest(
            learnerSnapshot = correctedSnapshot,
            targetKnowledgeNodeIds = setOf("kc-a"),
            requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
            candidates = listOf(foundation),
            decisionAtEpochMillis = 1_199,
        )
    }

    @Test
    fun `adaptive decision at the correction watermark is allowed`() {
        val decision = selector.select(
            AdaptiveSelectionRequest(
                learnerSnapshot = snapshot(masteredState()).copy(
                    correctionWatermarkEpochMillis = 1_200,
                ),
                targetKnowledgeNodeIds = setOf("kc-a"),
                requestedTargetKind = AdaptiveTargetKind.FOUNDATION,
                userRequestedQuestion = true,
                candidates = listOf(foundation),
                decisionAtEpochMillis = 1_200,
            ),
        )

        assertEquals(AdaptiveDecisionKind.SKIP_MASTERED_FOUNDATION, decision.kind)
    }

    private fun snapshot(state: KnowledgeMasteryState) = LearnerSnapshot(
        learnerId = "learner-1",
        knowledgeMasteryStates = mapOf("kc-a" to state),
        checkpoint = ProjectionCheckpoint(8, LearningProjector.VERSION, 800),
        generatedAtEpochMillis = 800,
    )

    private fun masteredState() = KnowledgeMasteryState(
        knowledgeNodeId = "kc-a",
        masteryScore = 0.93,
        conservativeMasteryScore = 0.87,
        evidenceMass = 2.0,
        independentCorrectObservations = listOf(
            observation("family-a", 1, 100),
            observation("family-b", 2, 200),
        ),
        status = MasteryStatus.MASTERED,
        calibrationSupport = CalibrationSupport.SUPPORTED,
        projectorVersion = LearningProjector.VERSION,
        checkpointSequence = 8,
        lastEvidenceAtEpochMillis = 200,
    )

    private fun observation(familyId: String, day: Long, at: Long) =
        IndependentCorrectObservation(
            itemFamilyId = familyId,
            studyDayEpochDay = day,
            occurredAtEpochMillis = at,
            evidenceWeight = 1.0,
            calibration = CalibrationSnapshot(
                CalibrationSupport.SUPPORTED,
                "calibration-source",
                "calibration-v1",
                0,
                100_000,
            ),
        )

    private fun prediction(
        lower: Double,
        upper: Double,
        validUntil: Long = 100_000,
    ) = PredictedCorrectnessInterval(
        lowerBound = lower,
        upperBound = upper,
        modelVersion = "prediction-v1",
        generatedAtEpochMillis = 0,
        validUntilEpochMillis = validUntil,
    )
}
