package com.tingyun.smartmistakebook.core.mastery.database

internal const val LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE =
    "mastery_model_submission_attempt_receipt"
internal const val LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE =
    "mastery_open_response_weak_candidate_receipt"
internal const val LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE =
    "mastery_open_response_model_evaluation_attestation"
internal const val LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE =
    "mastery_open_response_evaluation_knowledge_scope"
internal const val LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE =
    "mastery_open_response_dedicated_decision"
internal const val LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE =
    "mastery_legacy_evidence_review_resolution_audit"

internal val LEARNER_MASTERY_TABLE_NAMES =
    setOf(
        "mastery_source_fact",
        "mastery_source_proof",
        "mastery_problem_binding_authority",
        "mastery_problem_binding_authority_state",
        "mastery_observation_candidate",
        "mastery_candidate_attribution",
        "mastery_admission_receipt",
        LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE,
        "mastery_evidence_review_case",
        "mastery_evidence_review_resolution",
        LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
        "mastery_calibration_snapshot",
        "mastery_learning_event",
        "mastery_learning_evidence_supersession",
        "mastery_learning_event_attribution",
        "mastery_applied_event",
        LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE,
        LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE,
        "mastery_knowledge_projection",
        "mastery_subject_digest",
        "mastery_presentation_node_budget",
        "mastery_problem_family_node_budget",
        "mastery_projection_generation",
        "mastery_projection_shadow",
        "mastery_subject_digest_shadow",
        "mastery_presentation_node_budget_shadow",
        "mastery_problem_family_node_budget_shadow",
        LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE,
        LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE,
        LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE,
        LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE,
        LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE,
        "mastery_cross_store_inbox",
        "mastery_cross_store_outbox",
        "mastery_taxonomy_lineage_decision",
        "mastery_store_metadata",
        "mastery_ledger_sequence",
        "mastery_legacy_fact_migration_checkpoint",
        LEARNER_MASTERY_CUTOVER_FENCE_TABLE,
        LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE,
        LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE,
        LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
        LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
    )

internal val LEARNER_MASTERY_PROJECTION_IDENTITY_COLUMNS =
    setOf("learner_id", "subject", "knowledge_node_id", "taxonomy_version")

internal val LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES =
    setOf(
        "mastery_source_fact",
        "mastery_source_proof",
        "mastery_observation_candidate",
        "mastery_candidate_attribution",
        "mastery_admission_receipt",
        LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE,
        "mastery_evidence_review_case",
        "mastery_evidence_review_resolution",
        LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
        "mastery_calibration_snapshot",
        "mastery_learning_event",
        "mastery_learning_evidence_supersession",
        "mastery_learning_event_attribution",
        "mastery_applied_event",
        LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE,
        LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE,
        LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE,
        LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE,
        LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE,
        LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE,
        LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE,
        "mastery_cross_store_inbox",
        "mastery_taxonomy_lineage_decision",
        "mastery_store_metadata",
        "mastery_legacy_fact_migration_checkpoint",
        LEARNER_MASTERY_CUTOVER_FENCE_TABLE,
        LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE,
        LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE,
        LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
        LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
    )
