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
     * Per-learner cooldown before the same KC accepts another model evidence
     * write, across ALL conversations. [I] aligned with Khan Academy's 12h
     * mastery-challenge cooldown. Batch-intake note: 50 imported problems on
     * the same KC still produce only ONE model judgment per 12h — that is
     * intentional (Condorcet independence: the model's dialogue judgments on
     * one KC within 12h are correlated, not independent evidence; the
     * independent signal for further problems on that KC comes from real
     * attempts on the attempt channel, not repeated model affirmations).
     */
    const val SAME_KC_COOLDOWN_MILLIS = 12L * 60 * 60 * 1000

    /**
     * Per-conversation cap on accepted MASTERY_UPDATE writes in one session.
     * [I] Sized for batch tutoring sessions: a user can import a full exam
     * sheet (50+ problems, 20+ distinct KCs) and tutor through it in ONE
     * conversation — one model judgment per KC — so 8 would reject legitimate
     * batch learning at problem #9. 50 leaves ~2× headroom over that batch
     * shape while still capping a runaway self-affirmation loop.
     */
    const val MAX_WRITES_PER_CONVERSATION = 50

    /**
     * Per-learner rolling-window cap on accepted MASTERY_UPDATE writes across
     * all conversations. [I] Sized for batch intake at the 1_000+/day scale:
     * a legitimate batch burst (one big import + tutoring session) lands
     * around 50-100 writes/hour, while a runaway loop (3 calls × 2 rounds per
     * respond, dozens of responds) reaches several hundred per hour. 100/h
     * separates the two; the calibration channel (ChatEvidenceGateCalibration)
     * tracks real pressure so the constant can be re-set from data.
     */
    const val MAX_WRITES_PER_LEARNER_WINDOW = 100

    /** Rolling window for the per-learner cap. */
    const val LEARNER_WINDOW_MILLIS = 1L * 60 * 60 * 1000

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
     * A POSITIVE MASTERED judgment must be **verifiable** before it may write
     * at the high tier (Koriat & Bjork 2005 illusions of competence).
     *
     * 档2（spec `2026-09-06-mastery-judgment-gate-evolution.md` §1）：本地的
     * 行为佐证（[GateInput.hasObjectiveSupport]）只对客观作答通道可用；讲题
     * 对话通道本地拿不到可靠语义信号，改判为校验**模型自己的判断是否带出
     * 可核查的证据锚**（[GateInput.evidenceAnchorCount] ≥ 本条）。判断可信度
     * 由档1 的 prompt 规范承担，本门只做机械校验（条数/有无），不查真伪。
     * 门槛值与档1 规范第 1 条（"逐字引用≥2条"）同值。
     */
    const val REQUIRED_EVIDENCE_ANCHORS_FOR_MASTERED = 2

    /**
     * 一条引用短于此长度不算证据锚。消灭的失败：学生一句空话（"懂了"/"会了"）
     * 被引号包住就凑够条数，使证据锚门形同虚设（档1 规范第 2 条正是禁止把
     * 口头声称当事实）。[I] 待产品数据校准。
     */
    const val MIN_EVIDENCE_ANCHOR_CHARS = 4

    /**
     * 逐字引用的机械形态：成对引号包住的片段（中英文双引号、直角/双直角引号）。
     * 非引号叙述不算锚——gate 只数锚，不判语义。
     */
    private val EVIDENCE_ANCHOR_REGEX = Regex(
        "[\"“][^\"“”]{${MIN_EVIDENCE_ANCHOR_CHARS},}[\"”]" +
            "|「[^「」]{${MIN_EVIDENCE_ANCHOR_CHARS},}」" +
            "|『[^『』]{${MIN_EVIDENCE_ANCHOR_CHARS},}』",
    )

    /**
     * 数出 rationale 里逐字引用的证据锚条数（[EVIDENCE_ANCHOR_REGEX]）。
     * 纯函数，讲题通道的执行器用它填 [GateInput.evidenceAnchorCount]。
     */
    fun evidenceAnchorCount(rationale: String): Int = EVIDENCE_ANCHOR_REGEX.findAll(rationale).count()

    // ---- Evidence weight table (research §1.3, FSRS-grade analogy) ----
    // FSRS stability-gain ratios: Hard≈0.29 / Good≈1.0 / Easy≈2.61 (defaults).
    // Self-reported tiers are heavily discounted (Deslauriers 2019; Rozenblit
    // & Keil 2002; Koriat & Bjork 2005). NEGATIVE uses the standard lapse
    // tier (0.35) aligned with the existing ChatEvidenceSubmitted cap.

    /** POSITIVE + STRUGGLING is contradictory; the caller rejects it before lookup. */
    const val WEIGHT_STRUGGLING = 0.35 // NEGATIVE standard lapse tier
    const val WEIGHT_UNCERTAIN_POSITIVE = 0.10 // low-confidence correct ≈ Hard, memory weak (Benjamin 1998)
    const val WEIGHT_CONFIDENT_POSITIVE = 0.15 // dialogue self-report, discounted (Deslauriers)
    const val WEIGHT_MASTERED_POSITIVE = 0.18 // requires verifiable support; Easy ceiling, never above cap

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
        /** claimed MASTERED/POSITIVE with no verifiable support on either route. */
        MASTERED_WITHOUT_EVIDENCE_ANCHOR,
        /** same KC already written within the cooldown window. */
        SAME_KC_IN_COOLDOWN,
        /** per-conversation write quota exhausted. */
        CONVERSATION_QUOTA_EXHAUSTED,
        /** per-learner rolling-window write quota exhausted (multi-session farm guard). */
        LEARNER_WINDOW_QUOTA_EXHAUSTED,
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
        /**
         * 本地可核查的客观佐证：同一会话内该知识点上有客观答对（知识点测验
         * 通道）。讲题通道恒 false——本地无法在语义上佐证"学生懂了"（研究
         * llm-mastery-judgment-regulation §1），它走 [evidenceAnchorCount]。
         */
        val hasObjectiveSupport: Boolean,
        /**
         * 模型 rationale 里的逐字证据锚条数（[evidenceAnchorCount]）。讲题
         * 通道通往 MASTERED 的唯一可核查路径；客观作答通道恒 0。
         */
        val evidenceAnchorCount: Int,
        /** Age of the learner's most recent accepted write to the SAME KC (across all conversations). */
        val sameKcLastWriteAgoMillis: Long?,
        /** Accepted writes in the current conversation. */
        val writesThisConversation: Int,
        /** Accepted writes by this learner in the rolling [LEARNER_WINDOW_MILLIS] window. */
        val writesThisLearnerInWindow: Int,
        val attentionFactor: Double,
    ) {
        init {
            require(intentConfidence in 0.0..1.0) { "Intent confidence must be in 0..1" }
            require(evidenceConfidence in 0.0..1.0) { "Evidence confidence must be in 0..1" }
            require(attentionFactor in 0.0..1.0) { "Attention factor must be in 0..1" }
            require(evidenceAnchorCount >= 0) { "Evidence anchor count must not be negative" }
            require(writesThisConversation >= 0) { "Write count must not be negative" }
            require(writesThisLearnerInWindow >= 0) { "Learner window write count must not be negative" }
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
            !input.hasObjectiveSupport &&
            input.evidenceAnchorCount < REQUIRED_EVIDENCE_ANCHORS_FOR_MASTERED
        ) {
            // 档2 前此处是"无本地行为佐证即拒"，而本地行为佐证在讲题通道恒
            // 不可得（runner 恒传 false）→ MASTERED 永远被拒，0.18 档位成死常数。
            // 现改为双路可核查：本地客观作答（测验通道）或模型逐字证据锚（讲题
            // 通道）。两路都缺才拒——被拒证据落 rejected 观察行，不静默丢弃。
            return GateResult.Rejected(RejectReason.MASTERED_WITHOUT_EVIDENCE_ANCHOR)
        }
        val lastWrite = input.sameKcLastWriteAgoMillis
        if (lastWrite != null && lastWrite < SAME_KC_COOLDOWN_MILLIS) {
            return GateResult.Rejected(RejectReason.SAME_KC_IN_COOLDOWN)
        }
        if (input.writesThisConversation >= MAX_WRITES_PER_CONVERSATION) {
            return GateResult.Rejected(RejectReason.CONVERSATION_QUOTA_EXHAUSTED)
        }
        if (input.writesThisLearnerInWindow >= MAX_WRITES_PER_LEARNER_WINDOW) {
            return GateResult.Rejected(RejectReason.LEARNER_WINDOW_QUOTA_EXHAUSTED)
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
