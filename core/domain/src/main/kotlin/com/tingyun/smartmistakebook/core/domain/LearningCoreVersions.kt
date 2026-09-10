package com.tingyun.smartmistakebook.core.domain

/** Single source of truth for persisted learning-algorithm version identities. */
object LearningCoreVersions {
    const val EVIDENCE = "evidence-v4"

    /**
     * v5 → v6（2026-09-11）：`projectChatEvidence` 开始写 `lastEvidenceAt` /
     * `lastEvidenceDirection`（审计 AUDIT-ALGORITHM-2026-09-09 §3.5）。改的是
     * **同一条账本事件的投影结果**，增量投影不会重算已消费的事件，因此必须靠
     * 版本不匹配触发全量重放（`StudyProjectionDrainer` 的
     * `requiresReplay → commitFullReplay`），否则已有库里的 KC 仍旧带着旧的
     * null 时钟，修复对它们静默无效。
     *
     * v6 → v7（2026-09-11）：跨日判定的 delta_t 改由
     * `lastReviewedAtEpochMillis` + 事件的 UTC 偏移现算，不再读
     * `ProblemMemoryState.lastReviewedEpochDay`（审计 §3.7——`projectTutorAnswerExposure`
     * 曾让该字段落入 UTC 日序默认值，使同一本地日的复习被误判为跨日）。这是一次
     * **数值口径变更**：受影响的卡（曝光态之后的复习、以及 v42 迁移按 UTC 回填过
     * 日序的存量行）必须重算，否则新旧混用。
     */
    const val PROJECTOR = "projector-v7"
    const val FORGETTING_CURVE = "curve-v3"
    const val SKIP_POLICY = "skip-v3"
    const val ATTRIBUTION = "attribution-v2"
    const val LEDGER = "ledger-v2"
    const val PREDICTION_INTERVAL = "prediction-interval-v1"
    const val REVIEW_PLANNER = "review-planner-v6"
    const val SELECTOR = "selector-v6"

    const val PROJECTION_COMPOSITE =
        "learning-core-v7($PROJECTOR,$EVIDENCE,$FORGETTING_CURVE,$SKIP_POLICY,$ATTRIBUTION,$LEDGER)"
    const val REVIEW_COMPOSITE =
        "learning-core-v7($REVIEW_PLANNER,$PROJECTOR,$FORGETTING_CURVE,$SKIP_POLICY,$LEDGER)"
    const val SELECTOR_COMPOSITE =
        "learning-core-v7($SELECTOR,$PROJECTOR,$SKIP_POLICY,$PREDICTION_INTERVAL,$LEDGER)"
}
