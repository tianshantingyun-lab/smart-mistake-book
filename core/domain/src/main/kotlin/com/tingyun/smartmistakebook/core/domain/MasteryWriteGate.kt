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
     * 任意正向判断都至少要有 1 条**已核实**引文锚（引文真的出现在学生会话文本里）。
     *
     * 消灭的失败：CONFIDENT/UNCERTAIN 档此前不校验锚，模型对着学生的开放式作答口头说句
     * "他懂了"就能入库——提示词里"引文会被本地逐条比对"对纯文字作答形同虚设
     * （学生会话文本此前根本不落库，见 `TutorRespondCommands.recordStudentTurnIfNeeded`）。
     * 现在两处都补齐：语料真实存在，且任何正向都至少要引用到一处。
     * 负向不受限（下调误伤小，与既有姿态一致）。
     */
    const val REQUIRED_EVIDENCE_ANCHORS_FOR_POSITIVE = 1

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
     *
     * 只做机械计数，**不核对引文真伪**——需要核对时用
     * [verifiedEvidenceAnchorCount]，它要求每条形如引号的片段真的出现在
     * 会话文本里。
     */
    fun evidenceAnchorCount(rationale: String): Int = EVIDENCE_ANCHOR_REGEX.findAll(rationale).count()

    /**
     * 只数**引文真的出现在 [verifiableText] 里**的证据锚。
     *
     * 消灭的失败：档2 的门把"rationale 里有 ≥2 对引号"当成可核查性证明，而引号
     * 内容从不与会话原文比对。模型（或其被题面注入的内容）只要写
     * `学生说"因为""所以"` 就凑够 2 条锚，从而以 MASTERED 档写入掌握度——
     * 档1 规范第 1 条要求的是"逐字引用学生原话或可观察行为"，本函数是这条
     * 规范在本地唯一可机械执行的部分：引文必须是会话文本的真实子串。
     *
     * 比对前折叠大小写与全部空白：中文正文在 Markdown 里常被换行/缩进切断，
     * 逐字节相等会把合法引文误判为伪造。不折叠标点——标点差异是引文不忠实的
     * 真实信号。
     *
     * @param verifiableText 本会话学生确实产出过的文本（原话 + 可观察作答）。
     *   空串表示本地没有可核查文本，此时任何锚都得不到证实（返回 0），
     *   与"缺佐证即不写高置信档"的既有姿态一致。
     */
    fun verifiedEvidenceAnchorCount(rationale: String, verifiableText: String): Int {
        if (verifiableText.isBlank()) return 0
        val corpus = foldForAnchorComparison(verifiableText)
        if (corpus.isEmpty()) return 0
        return EVIDENCE_ANCHOR_REGEX.findAll(rationale).count { match ->
            val anchor = foldForAnchorComparison(match.value.trim('"', '“', '”', '「', '」', '『', '』'))
            anchor.isNotEmpty() && corpus.contains(anchor)
        }
    }

    private fun foldForAnchorComparison(value: String): String =
        value.lowercase().filterNot(Char::isWhitespace)

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

    // ---- Anchor-class safety padding (ADR 0001 / D9) ----

    /** `learner_chat_evidence.anchor_class` 落库值：当前题已确认绑定的知识点。 */
    const val ANCHOR_CLASS_CONFIRMED = "CONFIRMED"

    /** `learner_chat_evidence.anchor_class` 落库值：当前题本地检索候选（未确认绑定）。 */
    const val ANCHOR_CLASS_CANDIDATE = "CANDIDATE"

    /** `learner_chat_evidence.anchor_class` 落库值：其余披露（前置 / 工具发现）。 */
    const val ANCHOR_CLASS_DISCLOSED = "DISCLOSED"

    /**
     * 写口上**唯一**的数值分支（D9 降权安全垫）：写入代号锚定等级不是 [ANCHOR_CLASS_CONFIRMED]
     * （且非 NULL）时，门已放行的档位权重减半。
     *
     * NULL（anchor_class 列引入前写入的 legacy 行）按**全权重**对待——历史不追溯降权
     * （向后兼容用例钉在 MasteryWriteGateTest 与投影层）。CANDIDATE 与 DISCLOSED 数值待遇
     * 相同，二者区分只用于审计与后续校准（ADR 0001 §6）。
     */
    const val UNCONFIRMED_ANCHOR_WEIGHT_FACTOR = 0.5

    /**
     * 一条被门放行的写入**落库**的证据权重：[anchorClass] 为 NULL 或
     * [ANCHOR_CLASS_CONFIRMED] → 全权重；CANDIDATE / DISCLOSED → 减半。
     *
     * 降权在**写入时**施加（core:data 的 runner），`learner_chat_evidence.weight` 列存的就是
     * 减半后的值：账本事件、投影器积分、重放都按存库权重逐位进行，投影层不再感知
     * anchor_class——"唯一数值分支"只有一个落点，不会出现两处各减一次。
     */
    fun effectiveEvidenceWeight(baseWeight: Double, anchorClass: String?): Double =
        if (anchorClass != null && anchorClass != ANCHOR_CLASS_CONFIRMED) {
            baseWeight * UNCONFIRMED_ANCHOR_WEIGHT_FACTOR
        } else {
            baseWeight
        }

    // ---- Rejection reasons ----

    enum class RejectReason {
        /**
         * intent confidence below the routing threshold (spec §9.4)。
         *
         * 历史审计值：`evaluate` 不再产出它（意图门在授权层，2026-09-21 起 GateInput 也删掉了
         * 那个只供对照的常量），但旧 rejected 行的 `rejected_reason` 列可能仍是这个字符串，
         * 展示层（feature:review 的拒因→文案映射）要继续认它。
         */
        INTENT_BELOW_ROUTE_CONFIDENCE,
        /** evidence confidence below θ_evidence. */
        EVIDENCE_BELOW_CONFIDENCE,
        /** knowledgeNodeId is not a real bound knowledge node of the current problem. */
        KNOWLEDGE_NODE_NOT_ANCHORED,
        /** claimed MASTERED/POSITIVE with no verifiable support on either route. */
        MASTERED_WITHOUT_EVIDENCE_ANCHOR,
        /** 正向判断连一条已核实引文锚都没有（开放式作答的底线要求）。 */
        POSITIVE_WITHOUT_EVIDENCE_ANCHOR,
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
        /**
         * 会话内学生刚答错过检查题，模型仍判 POSITIVE——行为证据推翻口头声明
         * （研究 tutor-evidence-gate §3.2）。
         */
        OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE,
    }

    data class GateInput(
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
        /**
         * 会话内学生的**客观作答**是否推翻了这条正向判断（
         * [TutorSessionObjectiveRecord.contradictsPositiveClaim]）。
         *
         * 与 [hasObjectiveSupport] 是两个不同的轴：那个是"本地有正确作答可佐证"
         * （按知识点锚定，只有客观作答通道给得出），这个是"本地有错误作答在
         * 反驳"（按会话成立，讲题通道才拿得到）。学生答错自己的检查题，
         * 模型却宣称懂了——研究 §3.2 要求冲突时行为证据胜出，此判断降级为
         * 观察记录（被拒 ≠ 删除，见 runner 的 rejected 行）。
         */
        val objectiveAnswersContradictPositive: Boolean,
        /** Age of the learner's most recent accepted write to the SAME KC (across all conversations). */
        val sameKcLastWriteAgoMillis: Long?,
        /** Accepted writes in the current conversation. */
        val writesThisConversation: Int,
        /** Accepted writes by this learner in the rolling [LEARNER_WINDOW_MILLIS] window. */
        val writesThisLearnerInWindow: Int,
        val attentionFactor: Double,
    ) {
        init {
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
        if (input.direction == TutorEvidenceDirection.POSITIVE &&
            input.objectiveAnswersContradictPositive
        ) {
            // 客观作答是本地的行为证据，模型的正向判断是口头声明。研究 §3.2：
            // 冲突时行为证据胜出，口头声明降级为观察记录。这里只挡 POSITIVE——
            // NEGATIVE 与"学生答错"方向一致，不构成冲突。
            return GateResult.Rejected(RejectReason.OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE)
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
        if (input.direction == TutorEvidenceDirection.POSITIVE &&
            !input.hasObjectiveSupport &&
            input.evidenceAnchorCount < REQUIRED_EVIDENCE_ANCHORS_FOR_POSITIVE
        ) {
            // 正向底线：至少引用到一处学生真说过/真做过的东西。开放式作答的对错只能
            // 靠模型语义判断，这条是本地唯一能机械执行的可核查性要求（研究 §4(iii)1：
            // 无逐字证据锚 → 拒写）。拒写落观察行，不静默丢弃。
            return GateResult.Rejected(RejectReason.POSITIVE_WITHOUT_EVIDENCE_ANCHOR)
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
}
