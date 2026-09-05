package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate.GateInput
import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate.GateResult
import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate.RejectReason
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MasteryWriteGateTest {

    private fun acceptedInput(
        direction: TutorEvidenceDirection = TutorEvidenceDirection.POSITIVE,
        understanding: TutorUnderstandingTier = TutorUnderstandingTier.CONFIDENT,
        evidenceConfidence: Double = 0.9,
        knowledgeNodeIsAnchored: Boolean = true,
        hasBehavioralSupport: Boolean = true,
        sameKcLastWriteAgoMillis: Long? = null,
        writesThisConversation: Int = 0,
        writesThisLearnerInWindow: Int = 0,
        attentionFactor: Double = 1.0,
    ) = GateInput(
        intentConfidence = 0.9,
        evidenceConfidence = evidenceConfidence,
        direction = direction,
        understanding = understanding,
        knowledgeNodeIsAnchored = knowledgeNodeIsAnchored,
        hasBehavioralSupport = hasBehavioralSupport,
        sameKcLastWriteAgoMillis = sameKcLastWriteAgoMillis,
        writesThisConversation = writesThisConversation,
        writesThisLearnerInWindow = writesThisLearnerInWindow,
        attentionFactor = attentionFactor,
    )

    private fun assertAccepted(input: GateInput): Double {
        val result = MasteryWriteGate.evaluate(input)
        assertTrue("expected accepted but was $result", result is GateResult.Accepted)
        return (result as GateResult.Accepted).weight
    }

    private fun assertRejected(input: GateInput, reason: RejectReason) {
        val result = MasteryWriteGate.evaluate(input)
        assertEquals(GateResult.Rejected(reason), result)
    }

    @Test
    fun `confident positive without behavior support is accepted at the discounted tier`() {
        // CONFIDENT is a dialogue self-report that is already discounted to
        // 0.15, so it does not require behavioral support; only the MASTERED
        // tier claims enough to need an in-session correct answer.
        val weight = assertAccepted(
            acceptedInput(understanding = TutorUnderstandingTier.CONFIDENT, hasBehavioralSupport = false),
        )
        assertEquals(MasteryWriteGate.WEIGHT_CONFIDENT_POSITIVE, weight, 1e-9)
    }

    @Test
    fun `mastered positive requires behavioral support`() {
        // MASTERED without support is rejected (Koriat & Bjork illusions of competence).
        assertRejected(
            acceptedInput(understanding = TutorUnderstandingTier.MASTERED, hasBehavioralSupport = false),
            RejectReason.MASTERED_WITHOUT_BEHAVIORAL_SUPPORT,
        )
        // MASTERED with a correct objective answer in-session is accepted.
        assertTrue(
            MasteryWriteGate.evaluate(
                acceptedInput(understanding = TutorUnderstandingTier.MASTERED, hasBehavioralSupport = true),
            ) is GateResult.Accepted,
        )
    }

    @Test
    fun `positive struggling is a semantic contradiction`() {
        assertRejected(
            acceptedInput(direction = TutorEvidenceDirection.POSITIVE, understanding = TutorUnderstandingTier.STRUGGLING),
            RejectReason.CONTRADICTORY_SEMANTICS,
        )
    }

    @Test
    fun `evidence confidence below threshold is rejected`() {
        assertRejected(
            acceptedInput(evidenceConfidence = 0.69),
            RejectReason.EVIDENCE_BELOW_CONFIDENCE,
        )
    }

    @Test
    fun `unanchored knowledge node is rejected`() {
        assertRejected(
            acceptedInput(knowledgeNodeIsAnchored = false),
            RejectReason.KNOWLEDGE_NODE_NOT_ANCHORED,
        )
    }

    @Test
    fun `same kc write inside cooldown is rejected`() {
        assertRejected(
            acceptedInput(sameKcLastWriteAgoMillis = MasteryWriteGate.SAME_KC_COOLDOWN_MILLIS - 1),
            RejectReason.SAME_KC_IN_COOLDOWN,
        )
        // Exactly at the cooldown boundary is accepted.
        assertTrue(
            MasteryWriteGate.evaluate(
                acceptedInput(sameKcLastWriteAgoMillis = MasteryWriteGate.SAME_KC_COOLDOWN_MILLIS),
            ) is GateResult.Accepted,
        )
    }

    @Test
    fun `conversation quota exhaustion is rejected`() {
        assertRejected(
            acceptedInput(writesThisConversation = MasteryWriteGate.MAX_WRITES_PER_CONVERSATION),
            RejectReason.CONVERSATION_QUOTA_EXHAUSTED,
        )
    }

    @Test
    fun `learner window quota exhaustion is rejected across conversations`() {
        // A multi-session farm (N sessions × up to 8 each) must be capped by
        // the per-learner rolling window even when each single conversation
        // stays under its own quota.
        assertRejected(
            acceptedInput(
                writesThisConversation = 0,
                writesThisLearnerInWindow = MasteryWriteGate.MAX_WRITES_PER_LEARNER_WINDOW,
            ),
            RejectReason.LEARNER_WINDOW_QUOTA_EXHAUSTED,
        )
        // Below the window cap the write proceeds.
        assertTrue(
            MasteryWriteGate.evaluate(
                acceptedInput(
                    writesThisConversation = 0,
                    writesThisLearnerInWindow = MasteryWriteGate.MAX_WRITES_PER_LEARNER_WINDOW - 1,
                ),
            ) is GateResult.Accepted,
        )
    }

    @Test
    fun `attention below reject floor is rejected`() {
        assertRejected(
            acceptedInput(attentionFactor = MasteryWriteGate.MIN_ATTENTION_FACTOR - 0.01),
            RejectReason.ATTENTION_BELOW_FLOOR,
        )
        // At or above the floor is accepted (down-weighting happens upstream).
        assertTrue(
            MasteryWriteGate.evaluate(
                acceptedInput(attentionFactor = MasteryWriteGate.MIN_ATTENTION_FACTOR),
            ) is GateResult.Accepted,
        )
    }

    @Test
    fun `weight table maps understanding tiers to local constants`() {
        assertEquals(MasteryWriteGate.WEIGHT_UNCERTAIN_POSITIVE, MasteryWriteGate.positiveWeightFor(TutorUnderstandingTier.UNCERTAIN), 1e-9)
        assertEquals(MasteryWriteGate.WEIGHT_CONFIDENT_POSITIVE, MasteryWriteGate.positiveWeightFor(TutorUnderstandingTier.CONFIDENT), 1e-9)
        assertEquals(MasteryWriteGate.WEIGHT_MASTERED_POSITIVE, MasteryWriteGate.positiveWeightFor(TutorUnderstandingTier.MASTERED), 1e-9)
        assertEquals(MasteryWriteGate.WEIGHT_STRUGGLING, MasteryWriteGate.negativeWeight(), 1e-9)
        // All positive tiers stay at or below the model-evidence cap.
        listOf(
            TutorUnderstandingTier.UNCERTAIN,
            TutorUnderstandingTier.CONFIDENT,
            TutorUnderstandingTier.MASTERED,
        ).forEach { tier ->
            assertTrue(MasteryWriteGate.positiveWeightFor(tier) <= MasteryWriteGate.MAX_EVIDENCE_WEIGHT)
        }
    }

    @Test
    fun `accepted negative uses the standard lapse weight regardless of understanding`() {
        for (tier in TutorUnderstandingTier.entries) {
            if (tier == TutorUnderstandingTier.STRUGGLING) continue
            val weight = assertAccepted(
                acceptedInput(direction = TutorEvidenceDirection.NEGATIVE, understanding = tier),
            )
            assertEquals(MasteryWriteGate.negativeWeight(), weight, 1e-9)
        }
    }

    @Test
    fun `negative struggling is accepted as the standard lapse tier`() {
        val weight = assertAccepted(
            acceptedInput(direction = TutorEvidenceDirection.NEGATIVE, understanding = TutorUnderstandingTier.STRUGGLING),
        )
        assertEquals(MasteryWriteGate.negativeWeight(), weight, 1e-9)
    }

    @Test
    fun `gate constants match the research calibration table`() {
        assertEquals(0.7, MasteryWriteGate.EVIDENCE_CONFIDENCE_THRESHOLD, 1e-9)
        assertEquals(12L * 60 * 60 * 1000, MasteryWriteGate.SAME_KC_COOLDOWN_MILLIS)
        assertEquals(50, MasteryWriteGate.MAX_WRITES_PER_CONVERSATION)
        assertEquals(100, MasteryWriteGate.MAX_WRITES_PER_LEARNER_WINDOW)
        assertEquals(1L * 60 * 60 * 1000, MasteryWriteGate.LEARNER_WINDOW_MILLIS)
        assertEquals(0.4, MasteryWriteGate.MIN_ATTENTION_FACTOR, 1e-9)
    }
}
