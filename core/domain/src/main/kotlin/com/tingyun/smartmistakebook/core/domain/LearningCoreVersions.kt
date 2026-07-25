package com.tingyun.smartmistakebook.core.domain

/** Single source of truth for persisted learning-algorithm version identities. */
object LearningCoreVersions {
    const val EVIDENCE = "evidence-v3"
    const val PROJECTOR = "projector-v4"
    const val FORGETTING_CURVE = "curve-v2"
    const val SKIP_POLICY = "skip-v3"
    const val ATTRIBUTION = "attribution-v1"
    const val LEDGER = "ledger-v2"
    const val PREDICTION_INTERVAL = "prediction-interval-v1"
    const val REVIEW_PLANNER = "review-planner-v5"
    const val SELECTOR = "selector-v5"

    const val PROJECTION_COMPOSITE =
        "learning-core-v4($PROJECTOR,$EVIDENCE,$FORGETTING_CURVE,$SKIP_POLICY,$ATTRIBUTION,$LEDGER)"
    const val REVIEW_COMPOSITE =
        "learning-core-v4($REVIEW_PLANNER,$PROJECTOR,$FORGETTING_CURVE,$SKIP_POLICY,$LEDGER)"
    const val SELECTOR_COMPOSITE =
        "learning-core-v4($SELECTOR,$PROJECTOR,$SKIP_POLICY,$PREDICTION_INTERVAL,$LEDGER)"
}
