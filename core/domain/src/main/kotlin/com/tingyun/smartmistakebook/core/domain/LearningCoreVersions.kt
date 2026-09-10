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
     */
    const val PROJECTOR = "projector-v6"
    const val FORGETTING_CURVE = "curve-v3"
    const val SKIP_POLICY = "skip-v3"
    const val ATTRIBUTION = "attribution-v2"
    const val LEDGER = "ledger-v2"
    const val PREDICTION_INTERVAL = "prediction-interval-v1"
    const val REVIEW_PLANNER = "review-planner-v6"
    const val SELECTOR = "selector-v6"

    const val PROJECTION_COMPOSITE =
        "learning-core-v6($PROJECTOR,$EVIDENCE,$FORGETTING_CURVE,$SKIP_POLICY,$ATTRIBUTION,$LEDGER)"
    const val REVIEW_COMPOSITE =
        "learning-core-v6($REVIEW_PLANNER,$PROJECTOR,$FORGETTING_CURVE,$SKIP_POLICY,$LEDGER)"
    const val SELECTOR_COMPOSITE =
        "learning-core-v6($SELECTOR,$PROJECTOR,$SKIP_POLICY,$PREDICTION_INTERVAL,$LEDGER)"
}
