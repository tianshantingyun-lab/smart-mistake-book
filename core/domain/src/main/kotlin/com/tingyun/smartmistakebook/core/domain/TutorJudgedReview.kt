package com.tingyun.smartmistakebook.core.domain

/**
 * 讲题判定的题目级结算：把"刚讲完的那次讲题会话"折算成复习队列当前这一项的 attempt。
 *
 * 证据来源两路，按"行为证据胜出"的既有原则合成（`f506ff7a`）：
 * - 本地核对：本轮检查题（模型出题、本地比对 correctChoiceId）的答对/答错；
 * - 模型判词：该会话里通过 `MasteryWriteGate` 的 accepted 掌握判断方向。
 *
 * 判对/判错的**语义**由模型负责（开放作答无法本地机判），但定价是本地的：
 * 该通道非独立、题目级只落 HARD/AGAIN，依据见
 * `docs/research/model-judged-verdict-pricing.md`。
 *
 * 快照不带知识归属：这条通道只驱动题目级排期，知识点掌握度由模型的
 * chat-evidence 通道单独写，一次会话不对同一 KC 双写。
 */
data class TutorJudgedReviewSettlement(
    val requestId: String,
    /** 复习会话 id（不是讲题会话 id）。 */
    val sessionId: String,
    /** 复习会话的版本号，用来锁定队列里被结算的那一项。 */
    val expectedStateVersion: Long,
    val practiceUnitId: String,
    val presentationId: String,
    val occurredAtEpochMillis: Long,
    val durationSeconds: Int = 0,
) {
    init {
        require(requestId.isNotBlank()) { "Settlement request id must not be blank" }
        require(sessionId.isNotBlank()) { "Review session id must not be blank" }
        require(expectedStateVersion >= 0) { "Expected review-session version must not be negative" }
        require(practiceUnitId.isNotBlank()) { "Settlement practice-unit id must not be blank" }
        require(presentationId.isNotBlank()) { "Settlement presentation id must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Settlement timestamp must not be negative" }
        require(durationSeconds >= 0) { "Settlement duration must not be negative" }
    }
}

/** 结算结果：要么落了一条 attempt 并推进队列，要么什么都没发生（不伪造证据）。 */
data class TutorJudgedReviewSettlementResult(
    val status: TutorJudgedReviewSettlementStatus,
    /** 判定对错的证据（落库的那一条）；[TutorJudgedReviewSettlementStatus.NO_VERDICT] 时为 null。 */
    val isCorrect: Boolean? = null,
    val attemptId: String? = null,
    val created: Boolean = false,
    override val progress: StudyReviewSessionProgress,
    /** Null exactly when [progress] completed the persisted review session. */
    override val nextPracticeUnitId: String?,
) : StudyReviewAdvanceResult {
    init {
        require(
            (status == TutorJudgedReviewSettlementStatus.NO_VERDICT) == (isCorrect == null),
        ) { "Only a settled verdict may carry correctness" }
        require(
            (progress.status == StudyReviewSessionStatus.COMPLETED) ==
                (nextPracticeUnitId == null),
        ) { "A completed review session must not expose a next practice unit" }
        require(nextPracticeUnitId == null || nextPracticeUnitId.isNotBlank()) {
            "Next review practice-unit id must not be blank"
        }
    }
}

enum class TutorJudgedReviewSettlementStatus {
    /** 有了判定，attempt 与队列推进都已落库（[TutorJudgedReviewSettlementResult.created] 区分是否本次新建）。 */
    RECORDED,

    /**
     * 这一轮讲题既没有本地核对的检查题作答，模型也没给出判词——**不写任何证据、不推进队列**。
     * 该复习项保持到期，下次还会出现（产品裁定：无判定时保持阻塞，不伪造"本次未作答"记录）。
     *
     * 代价是知情的（`docs/scenario-registry.md` 的 ReviewSession 行）：没有可用模型时这类题
     * 永远走不到 RECORDED，而生产线上的题都是无工件题 → 整条复习队列停在第 1 题。若将来要
     * 加"本次先跳过"，它必须是零权重、不改记忆、该题保持到期的独立账本语义，**不得**复用
     * `ANSWER_REVEALED`（它会被映射成 AGAIN 惩罚）。
     */
    NO_VERDICT,
}
