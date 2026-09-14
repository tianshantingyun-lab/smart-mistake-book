package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LearningStateTest {
    @Test
    fun `negative evidence exposes a signed negative weight`() {
        val evidence = LearningEvidence(
            direction = LearningEvidenceDirection.NEGATIVE,
            weight = 0.9,
            reason = LearningEvidenceReason.INCORRECT_AFTER_HINT,
        )

        assertEquals(-0.9, evidence.signedWeight, 0.0)
    }

    @Test
    fun `answer reveal is represented as explicit zero evidence`() {
        val evidence = LearningEvidence(
            direction = LearningEvidenceDirection.NONE,
            weight = 0.0,
            reason = LearningEvidenceReason.ANSWER_REVEALED,
        )

        assertEquals(0.0, evidence.signedWeight, 0.0)
    }

    @Test
    fun `local review self report remains non independent evidence`() {
        val recall = LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = 0.35,
            reason = LearningEvidenceReason.SELF_REPORTED_RECALL,
        )
        val stuck = LearningEvidence(
            direction = LearningEvidenceDirection.NEGATIVE,
            weight = 0.5,
            reason = LearningEvidenceReason.SELF_REPORTED_STUCK,
        )

        assertFalse(recall.isIndependent)
        assertFalse(stuck.isIndependent)
        assertEquals(0.35, recall.signedWeight, 0.0)
        assertEquals(-0.5, stuck.signedWeight, 0.0)
    }

    @Test
    fun `model judged verdicts remain non independent evidence`() {
        // 判分者误差（LLM 判分与人类 κ≈0.70）与探针协助都不允许它们进独立档：
        // isIndependent 驱动「可跳过」与「假掌握」判定，依据见
        // docs/research/model-judged-verdict-pricing.md §0.1。
        val correct = LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = 0.5,
            reason = LearningEvidenceReason.MODEL_JUDGED_CORRECT,
        )
        val incorrect = LearningEvidence(
            direction = LearningEvidenceDirection.NEGATIVE,
            weight = 0.5,
            reason = LearningEvidenceReason.MODEL_JUDGED_INCORRECT,
        )

        assertFalse(correct.isIndependent)
        assertFalse(incorrect.isIndependent)
        assertEquals(0.5, correct.signedWeight, 0.0)
        assertEquals(-0.5, incorrect.signedWeight, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `evidence direction cannot contradict its reason`() {
        LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = 1.0,
            reason = LearningEvidenceReason.INDEPENDENT_INCORRECT,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `presentation reveal cannot exceed its authoritative checkpoint`() {
        PresentationProjectionState(
            presentationId = "presentation-1",
            asOfLedgerSequence = 10,
            memoryProjectionApplied = true,
            answerRevealSequence = 11,
        )
    }

    @Test
    fun `correction watermark participates in the learner decision watermark`() {
        val snapshot = LearnerSnapshot(
            learnerId = "learner-1",
            checkpoint = ProjectionCheckpoint(2, "projector-v1", 200),
            generatedAtEpochMillis = 250,
            correctionWatermarkEpochMillis = 300,
        )

        assertEquals(300L, snapshot.decisionWatermarkEpochMillis)
    }
}
