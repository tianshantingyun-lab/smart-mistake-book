package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier

/**
 * Gate and weight table for model-issued mastery evidence (MASTERY_UPDATE).
 *
 * Design contract (research `docs/research/tutor-evidence-gate-research.md`):
 * the model is a ~p≈0.8 "independent voter", not an authority — it supplies
 * only the *semantic* elements it alone can judge from the dialogue
 * (direction, understanding tier, difficulty tier, knowledge-node anchor).
 * Every numeric decision is local and constant: the tier→weight mapping,
 * the confidence thresholds, the cooldown, the per-session quota, and the
 * attention floor. Nothing the model says can scale a weight.
 *
 * All parameters marked [I] are engineering priors anchored to the research
 * record and must be re-calibrated from product data before they are treated
 * as measured (see the research doc §4 calibration path).
 */
object MasteryWriteGate {

    // ---- Thresholds (research §4) ----

    /**
     * Evidence-qualification confidence (spec model-intent-routing §9.4).
     * Under the p≈0.8 LLM-as-judge ceiling this is the lower bound for
     * accepting a single model vote as an evidence write.
     */
    const val EVIDENCE_CONFIDENCE_THRESHOLD = 0.7

    /**
     * Per-conversation cooldown before the same KC accepts another model
     * evidence write. [I] aligned with Khan Academy's 12h mastery-challenge
     * cooldown; keeps repeated "I understand now" affirmations from stacking
     * into one session.
     */
    const val SAME_KC_COOLDOWN_MILLIS = 12L * 60 * 60 * 1000

    /** Per-conversation cap on accepted MASTERY_UPDATE writes in one session. [I] */
    const val MAX_WRITES_PER_CONVERSATION = 8

    /**
     * Attention floor: below this factor a write is rejected instead of
     * merely down-weighted. [I] — encoding under divided attention sharply
     * impairs memory (Craik 1996), so heavily distracted affirmations carry
     * no trustworthy learning signal.
     */
    const val ATTENTION_REJECT_FLOOR = 0.4

    /** Attention factor at/below which a write is rejected (same constant, named for the gate). */
    const val MIN_ATTENTION_FACTOR = ATTENTION_REJECT_FLOOR

    /**
     * A MASTERED (or POSITIVE high-tier) judgment requires behavioral
     * support — a correct objective answer in the same session — before it
     * may write at the high tier (Koriat & Bjork 2005 illusions of
     * competence; Nelson & Dunlosky 1991 delayed-JOL). Without support the
     * write is either rejected or downgraded to CONFIDENT by the caller.
     */
    const val REQUIRES_BEHAVIORAL_SUPPORT_FOR_MASTERED = true

    // ---- Evidence weight table (research §1.3, FSRS-grade analogy) ----
    // FSRS stability-gain ratios: Hard≈0.29 / Good≈1.0 / Easy≈2.61 (defaults).
    // Self-reported tiers are heavily discounted (Deslauriers 2019; Rozenblit
    // & Keil 2002; Koriat & Bjork 2005). NEGATIVE uses the standard lapse
    // tier (0.35) aligned with the existing ChatEvidenceSubmitted cap.

    /** POSITIVE + STRUGGLING is contradictory; the caller rejects it before lookup. */
    const val WEIGHT_STRUGGLING = 0.35 // NEGATIVE standard lapse tier
    const val WEIGHT_UNCERTAIN_POSITIVE = 0.10 // low-confidence correct ≈ Hard, memory weak (Benjamin 1998)
    const val WEIGHT_CONFIDENT_POSITIVE = 0.15 // dialogue self-report, discounted (Deslauriers)
    const val WEIGHT_MASTERED_POSITIVE = 0.18 // requires behavioral support; Easy ceiling, never above cap

    const val MAX_EVIDENCE_WEIGHT = 0.35 // aligned with ChatEvidenceSubmitted.MAX_CHAT_EVIDENCE_WEIGHT

    /**
     * Positive-evidence weight for a model-judged understanding tier.
     * STRUGGLING is excluded: a struggling student claiming positive
     * understanding is a self-report contradiction the gate rejects.
     */
    fun positiveWeightFor(understanding: TutorUnderstandingTier): Double = when (understanding) {
        TutorUnderstandingTier.STRUGGLING -> WEIGHT_STRUGGLING // unreachable for POSITIVE; kept for totality
        TutorUnderstandingTier.UNCERTAIN -> WEIGHT_UNCERTAIN_POSITIVE
        TutorUnderstandingTier.CONFIDENT -> WEIGHT_CONFIDENT_POSITIVE
        TutorUnderstandingTier.MASTERED -> WEIGHT_MASTERED_POSITIVE
    }

    /** Negative-evidence weight — a lapse is a lapse regardless of claimed understanding. */
    fun negativeWeight(): Double = WEIGHT_STRUGGLING

    // ---- Rejection reasons ----

    enum class RejectReason {
        /** intent confidence below the routing threshold (spec §9.4). */
        INTENT_BELOW_ROUTE_CONFIDENCE,
        /** evidence confidence below θ_evidence. */
        EVIDENCE_BELOW_CONFIDENCE,
        /** knowledgeNodeId is not a real bound knowledge node of the current problem. */
        KNOWLEDGE_NODE_NOT_ANCHORED,
        /** claimed MASTERED/POSITIVE without a correct objective answer in the session. */
        MASTERED_WITHOUT_BEHAVIORAL_SUPPORT,
        /** same KC already written within the cooldown window. */
        SAME_KC_IN_COOLDOWN,
        /** per-conversation write quota exhausted. */
        CONVERSATION_QUOTA_EXHAUSTED,
        /** attention factor below the reject floor. */
        ATTENTION_BELOW_FLOOR,
        /** contradictory semantics (POSITIVE + STRUGGLING). */
        CONTRADICTORY_SEMANTICS,
    }

    data class GateInput(
        val intentConfidence: Double,
        val evidenceConfidence: Double,
        val direction: TutorEvidenceDirection,
        val understanding: TutorUnderstandingTier,
        val knowledgeNodeIsAnchored: Boolean,
        /** True when a correct objective answer exists in the same session (behavioral support). */
        val hasBehavioralSupport: Boolean,
        val sameKcLastWriteAgoMillis: Long?,
        val writesThisConversation: Int,
        val attentionFactor: Double,
    ) {
        init {
            require(intentConfidence in 0.0..1.0) { "Intent confidence must be in 0..1" }
            require(evidenceConfidence in 0.0..1.0) { "Evidence confidence must be in 0..1" }
            require(attentionFactor in 0.0..1.0) { "Attention factor must be in 0..1" }
            require(writesThisConversation >= 0) { "Write count must not be negative" }
            require(sameKcLastWriteAgoMillis == null || sameKcLastWriteAgoMillis >= 0) {
                "Cooldown age must not be negative"
            }
        }
    }

    sealed interface GateResult {
        data class Accepted(val weight: Double) : GateResult
        data class Rejected(val reason: RejectReason) : GateResult
    }

    /**
     * Runs the full gate chain in order. The first failing gate rejects the
     * write. [TutorToolLoop.ROUTE_CONFIDENCE_THRESHOLD] is enforced upstream
     * by the intent authorization; this gate re-checks evidence confidence
     * only, plus the semantic/anchoring/cooldown/quota/attention gates.
     */
    fun evaluate(input: GateInput): GateResult {
        if (input.direction == TutorEvidenceDirection.POSITIVE &&
            input.understanding == TutorUnderstandingTier.STRUGGLING
        ) {
            return GateResult.Rejected(RejectReason.CONTRADICTORY_SEMANTICS)
        }
        if (input.evidenceConfidence < EVIDENCE_CONFIDENCE_THRESHOLD) {
            return GateResult.Rejected(RejectReason.EVIDENCE_BELOW_CONFIDENCE)
        }
        if (!input.knowledgeNodeIsAnchored) {
            return GateResult.Rejected(RejectReason.KNOWLEDGE_NODE_NOT_ANCHORED)
        }
        if (input.direction == TutorEvidenceDirection.POSITIVE &&
            input.understanding == TutorUnderstandingTier.MASTERED &&
            REQUIRES_BEHAVIORAL_SUPPORT_FOR_MASTERED &&
            !input.hasBehavioralSupport
        ) {
            // 研究底稿 §1.3 措辞为"无行为佐证则按 CONFIDENT 降级"；此处有意选
            // "拒 + 观察通道"而非降级：MASTERED 无行为佐证时模型的"掌握"判断
            // 与 CONFIDENT 无法区分（都落 0.15 会让档位边界失效），且宁漏记比
            // 误记安全——被拒证据落 rejected 观察行，后续有真实作答佐证时可补救。
            return GateResult.Rejected(RejectReason.MASTERED_WITHOUT_BEHAVIORAL_SUPPORT)
        }
        val lastWrite = input.sameKcLastWriteAgoMillis
        if (lastWrite != null && lastWrite < SAME_KC_COOLDOWN_MILLIS) {
            return GateResult.Rejected(RejectReason.SAME_KC_IN_COOLDOWN)
        }
        if (input.writesThisConversation >= MAX_WRITES_PER_CONVERSATION) {
            return GateResult.Rejected(RejectReason.CONVERSATION_QUOTA_EXHAUSTED)
        }
        if (input.attentionFactor < MIN_ATTENTION_FACTOR) {
            return GateResult.Rejected(RejectReason.ATTENTION_BELOW_FLOOR)
        }
        val weight = if (input.direction == TutorEvidenceDirection.NEGATIVE) {
            negativeWeight()
        } else {
            positiveWeightFor(input.understanding)
        }
        return GateResult.Accepted(weight)
    }

    /** The intent-authorization route threshold, mirrored here for one-stop visibility. */
    const val ROUTE_CONFIDENCE_THRESHOLD = 0.45
}
