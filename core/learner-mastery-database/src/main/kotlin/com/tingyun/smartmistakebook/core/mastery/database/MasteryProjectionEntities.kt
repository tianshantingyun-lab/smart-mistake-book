package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "mastery_calibration_snapshot",
    primaryKeys = ["subject", "calibration_version", "snapshot_fingerprint"],
    indices = [
        Index(value = ["profile_id"], unique = true),
        Index(value = ["snapshot_fingerprint"], unique = true),
        Index(
            value = [
                "subject",
                "calibration_version",
                "profile_id",
                "snapshot_fingerprint",
            ],
            unique = true,
        ),
    ],
)
internal data class MasteryCalibrationSnapshotEntity(
    val subject: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "calibration_version")
    val calibrationVersion: String,
    @ColumnInfo(name = "projection_policy_version")
    val projectionPolicyVersion: String,
    @ColumnInfo(name = "prior_log_odds_micros")
    val priorLogOddsMicros: Long,
    @ColumnInfo(name = "positive_log_likelihood_micros")
    val positiveLogLikelihoodMicros: Long,
    @ColumnInfo(name = "negative_log_likelihood_micros")
    val negativeLogLikelihoodMicros: Long,
    @ColumnInfo(name = "steady_threshold_micros")
    val steadyThresholdMicros: Long,
    @ColumnInfo(name = "reinforcement_threshold_micros")
    val reinforcementThresholdMicros: Long,
    @ColumnInfo(name = "steady_minimum_observation_count")
    val steadyMinimumObservationCount: Long,
    @ColumnInfo(name = "material_score_delta_micros")
    val materialScoreDeltaMicros: Long,
    @ColumnInfo(name = "maximum_absolute_log_odds_micros")
    val maximumAbsoluteLogOddsMicros: Long,
    @ColumnInfo(name = "probability_transform_version")
    val probabilityTransformVersion: Long,
    @ColumnInfo(name = "legacy_probability_bridge_version")
    val legacyProbabilityBridgeVersion: Long,
    @ColumnInfo(name = "initial_stability_millis")
    val initialStabilityMillis: Long,
    @ColumnInfo(name = "minimum_stability_millis")
    val minimumStabilityMillis: Long,
    @ColumnInfo(name = "maximum_stability_millis")
    val maximumStabilityMillis: Long,
    @ColumnInfo(name = "positive_stability_gain_millis")
    val positiveStabilityGainMillis: Long,
    @ColumnInfo(name = "negative_stability_scale_micros")
    val negativeStabilityScaleMicros: Long,
    @ColumnInfo(name = "recall_half_life_scale_micros")
    val recallHalfLifeScaleMicros: Long,
    @ColumnInfo(name = "presentation_mass_cap_micros")
    val presentationMassCapMicros: Long,
    @ColumnInfo(name = "problem_family_mass_cap_micros")
    val problemFamilyMassCapMicros: Long,
    @ColumnInfo(name = "second_family_observation_scale_micros")
    val secondFamilyObservationScaleMicros: Long,
    @ColumnInfo(name = "repeated_family_observation_scale_micros")
    val repeatedFamilyObservationScaleMicros: Long,
    @ColumnInfo(name = "stable_conflict_floor_millis")
    val stableConflictFloorMillis: Long,
    @ColumnInfo(name = "local_verified_mass_micros")
    val localVerifiedMassMicros: Long,
    @ColumnInfo(name = "deterministic_rubric_mass_micros")
    val deterministicRubricMassMicros: Long,
    @ColumnInfo(name = "model_reviewed_mass_micros")
    val modelReviewedMassMicros: Long,
    @ColumnInfo(name = "one_hint_scale_micros")
    val oneHintScaleMicros: Long,
    @ColumnInfo(name = "multiple_hints_scale_micros")
    val multipleHintsScaleMicros: Long,
    @ColumnInfo(name = "unknown_assistance_scale_micros")
    val unknownAssistanceScaleMicros: Long,
    @ColumnInfo(name = "one_retry_scale_micros")
    val oneRetryScaleMicros: Long,
    @ColumnInfo(name = "multiple_retries_scale_micros")
    val multipleRetriesScaleMicros: Long,
    @ColumnInfo(name = "model_attribution_cap_micros")
    val modelAttributionCapMicros: Long,
    @ColumnInfo(name = "open_response_cap_micros")
    val openResponseCapMicros: Long,
    @ColumnInfo(name = "snapshot_fingerprint")
    val snapshotFingerprint: String,
)

@Entity(
    tableName = "mastery_learning_event",
    foreignKeys = [
        ForeignKey(
            entity = MasteryObservationCandidateEntity::class,
            parentColumns = ["candidate_id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasterySourceFactEntity::class,
            parentColumns = ["source_fact_id"],
            childColumns = ["source_fact_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasterySourceProofEntity::class,
            parentColumns = ["source_fact_id", "proof_fingerprint"],
            childColumns = ["source_fact_id", "source_proof_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasteryCalibrationSnapshotEntity::class,
            parentColumns = [
                "subject",
                "calibration_version",
                "profile_id",
                "snapshot_fingerprint",
            ],
            childColumns = [
                "subject",
                "calibration_version",
                "calibration_profile_id",
                "calibration_snapshot_fingerprint",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["candidate_id"], unique = true),
        Index(value = ["source_fact_id"], unique = true),
        Index(value = ["source_fact_id", "source_proof_fingerprint"], unique = true),
        Index(value = ["learner_id", "event_sequence"], unique = true),
        Index(value = ["learner_id", "subject", "occurred_at_epoch_millis"]),
        Index(
            name = "index_mastery_learning_event_directional_budget_replay",
            value = ["learner_id", "occurred_at_epoch_millis", "event_id", "direction"],
            unique = true,
        ),
        Index(value = ["canonical_fingerprint"], unique = true),
        Index(value = ["event_id", "canonical_fingerprint"], unique = true),
        Index(
            value = [
                "subject",
                "calibration_version",
                "calibration_profile_id",
                "calibration_snapshot_fingerprint",
            ],
        ),
    ],
)
internal data class MasteryLearningEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "source_proof_fingerprint")
    val sourceProofFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    val direction: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "admitted_at_epoch_millis")
    val admittedAtEpochMillis: Long,
    @ColumnInfo(name = "projection_policy_version")
    val projectionPolicyVersion: String,
    @ColumnInfo(
        name = "admission_policy_version",
        defaultValue = "'learner-mastery-admission-legacy'",
    )
    val admissionPolicyVersion: String = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
    @ColumnInfo(
        name = "calibration_version",
        defaultValue = "'learner-mastery-calibration-legacy'",
    )
    val calibrationVersion: String = LEARNER_MASTERY_CALIBRATION_VERSION,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "problem_family_fingerprint")
    val problemFamilyFingerprint: String? = null,
    @ColumnInfo(name = "presentation_fingerprint", defaultValue = "''")
    val presentationFingerprint: String = "",
    @ColumnInfo(name = "evidence_quality_micros", defaultValue = "0")
    val evidenceQualityMicros: Long = 0L,
    @ColumnInfo(name = "independently_answered", defaultValue = "0")
    val independentlyAnswered: Boolean = false,
    @ColumnInfo(name = "calibration_snapshot_fingerprint")
    val calibrationSnapshotFingerprint: String? = null,
    @ColumnInfo(name = "calibration_profile_id")
    val calibrationProfileId: String? = null,
    @ColumnInfo(name = "review_resolution_fingerprint")
    val reviewResolutionFingerprint: String? = null,
)

/**
 * Immutable active-head transition for one admitted learning observation.
 *
 * Both source facts and both learning events remain in the ledger. Projection queries treat only
 * the original event as inactive; a later correction can itself be superseded by targeting the
 * replacement source fact.
 */
@Entity(
    tableName = "mastery_learning_evidence_supersession",
    foreignKeys = [
        ForeignKey(
            entity = MasterySourceFactEntity::class,
            parentColumns = ["source_fact_id"],
            childColumns = ["original_source_fact_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasteryLearningEventEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["original_event_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasterySourceFactEntity::class,
            parentColumns = ["source_fact_id"],
            childColumns = ["replacement_source_fact_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["original_source_fact_id"], unique = true),
        Index(value = ["original_source_fact_canonical_fingerprint"], unique = true),
        Index(value = ["original_event_id"], unique = true),
        Index(value = ["replacement_source_fact_id"], unique = true),
        Index(value = ["replacement_event_id"], unique = true),
        Index(value = ["learner_id", "subject", "superseded_at_epoch_millis"]),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["canonical_fingerprint"], unique = true),
    ],
)
internal data class MasteryLearningEvidenceSupersessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "supersession_id")
    val supersessionId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "original_source_fact_id")
    val originalSourceFactId: String,
    @ColumnInfo(name = "original_source_fact_canonical_fingerprint")
    val originalSourceFactCanonicalFingerprint: String,
    @ColumnInfo(name = "original_event_id")
    val originalEventId: String,
    @ColumnInfo(name = "original_event_canonical_fingerprint")
    val originalEventCanonicalFingerprint: String,
    @ColumnInfo(name = "replacement_source_fact_id")
    val replacementSourceFactId: String,
    @ColumnInfo(name = "replacement_source_fact_canonical_fingerprint")
    val replacementSourceFactCanonicalFingerprint: String,
    @ColumnInfo(name = "replacement_candidate_id")
    val replacementCandidateId: String,
    @ColumnInfo(name = "replacement_candidate_canonical_fingerprint")
    val replacementCandidateCanonicalFingerprint: String,
    @ColumnInfo(name = "replacement_event_id")
    val replacementEventId: String,
    val authority: String,
    @ColumnInfo(name = "authority_version")
    val authorityVersion: String,
    @ColumnInfo(name = "correction_evidence_fingerprint")
    val correctionEvidenceFingerprint: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "superseded_at_epoch_millis")
    val supersededAtEpochMillis: Long,
)

@Entity(
    tableName = "mastery_learning_event_attribution",
    primaryKeys = ["event_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = MasteryLearningEventEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = [
                "event_id",
                "knowledge_node_id",
                "taxonomy_version",
                "knowledge_pack_version",
            ],
            unique = true,
        ),
        Index(value = ["subject", "knowledge_node_id", "taxonomy_version", "event_id"]),
        Index(value = ["knowledge_node_ref_fingerprint"]),
        Index(
            name = "index_mastery_event_attribution_knowledge_ref_cover",
            value = [
                "knowledge_node_ref_fingerprint",
                "subject",
                "knowledge_node_id",
                "taxonomy_version",
                "knowledge_pack_version",
            ],
        ),
    ],
)
internal data class MasteryLearningEventAttributionEntity(
    @ColumnInfo(name = "event_id")
    val eventId: String,
    val ordinal: Int,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String,
    @ColumnInfo(name = "knowledge_node_ref_fingerprint")
    val knowledgeNodeRefFingerprint: String,
    @ColumnInfo(name = "evidence_mass_micros")
    val evidenceMassMicros: Long,
)

@Entity(
    tableName = "mastery_applied_event",
    foreignKeys = [
        ForeignKey(
            entity = MasteryLearningEventEntity::class,
            parentColumns = ["event_id", "canonical_fingerprint"],
            childColumns = ["event_id", "event_canonical_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["event_id", "event_canonical_fingerprint"], unique = true),
        Index(value = ["learner_id", "event_sequence"], unique = true),
        Index(value = ["application_fingerprint"], unique = true),
    ],
)
internal data class MasteryAppliedEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "event_canonical_fingerprint")
    val eventCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "projection_policy_version")
    val projectionPolicyVersion: String,
    @ColumnInfo(name = "application_fingerprint")
    val applicationFingerprint: String,
    @ColumnInfo(name = "applied_at_epoch_millis")
    val appliedAtEpochMillis: Long,
)

@Entity(
    tableName = "mastery_knowledge_projection",
    primaryKeys = [
        "learner_id",
        "subject",
        "knowledge_node_id",
        "taxonomy_version",
    ],
    foreignKeys = [
        ForeignKey(
            entity = MasteryCalibrationSnapshotEntity::class,
            parentColumns = [
                "subject",
                "calibration_version",
                "profile_id",
                "snapshot_fingerprint",
            ],
            childColumns = [
                "subject",
                "calibration_version",
                "calibration_profile_id",
                "calibration_snapshot_fingerprint",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["learner_id", "subject", "mastery_state", "last_evidence_at_epoch_millis"]),
        Index(value = ["learner_id", "subject", "recall_due_at_epoch_millis"]),
        Index(value = ["learner_id", "recall_familiarizing_at_epoch_millis"]),
        Index(value = ["learner_id", "recall_reinforcement_at_epoch_millis"]),
        Index(value = ["learner_id", "subject", "stable_node_identity_fingerprint"]),
        Index(value = ["stable_node_identity_fingerprint"]),
        Index(
            value = [
                "subject",
                "calibration_version",
                "calibration_profile_id",
                "calibration_snapshot_fingerprint",
            ],
        ),
    ],
)
internal data class MasteryKnowledgeProjectionEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "latest_evidence_knowledge_pack_version")
    val latestEvidenceKnowledgePackVersion: String,
    @ColumnInfo(name = "stable_node_identity_fingerprint")
    val stableNodeIdentityFingerprint: String,
    @ColumnInfo(name = "positive_evidence_micros")
    val positiveEvidenceMicros: Long,
    @ColumnInfo(name = "negative_evidence_micros")
    val negativeEvidenceMicros: Long,
    @ColumnInfo(name = "mastery_score_micros")
    val masteryScoreMicros: Long,
    @ColumnInfo(name = "mastery_state")
    val masteryState: String,
    val trend: String,
    @ColumnInfo(name = "observation_count")
    val observationCount: Long,
    @ColumnInfo(name = "memory_stability_millis")
    val memoryStabilityMillis: Long,
    @ColumnInfo(name = "recall_due_at_epoch_millis")
    val recallDueAtEpochMillis: Long,
    @ColumnInfo(name = "last_positive_at_epoch_millis")
    val lastPositiveAtEpochMillis: Long?,
    @ColumnInfo(name = "last_negative_at_epoch_millis")
    val lastNegativeAtEpochMillis: Long?,
    @ColumnInfo(name = "last_evidence_at_epoch_millis")
    val lastEvidenceAtEpochMillis: Long,
    @ColumnInfo(name = "last_event_sequence")
    val lastEventSequence: Long,
    @ColumnInfo(name = "last_ordered_event_id")
    val lastOrderedEventId: String,
    @ColumnInfo(name = "projection_policy_version")
    val projectionPolicyVersion: String,
    @ColumnInfo(name = "evidence_quality_micros", defaultValue = "0")
    val evidenceQualityMicros: Long = 0L,
    @ColumnInfo(name = "independent_problem_family_count", defaultValue = "0")
    val independentProblemFamilyCount: Long = 0L,
    @ColumnInfo(name = "distinct_presentation_count", defaultValue = "0")
    val distinctPresentationCount: Long = 0L,
    @ColumnInfo(name = "historical_log_odds_micros")
    val historicalLogOddsMicros: Long? = null,
    @ColumnInfo(name = "calibration_snapshot_fingerprint")
    val calibrationSnapshotFingerprint: String? = null,
    @ColumnInfo(name = "calibration_profile_id")
    val calibrationProfileId: String? = null,
    @ColumnInfo(name = "calibration_version")
    val calibrationVersion: String? = null,
    @ColumnInfo(name = "recall_familiarizing_at_epoch_millis")
    val recallFamiliarizingAtEpochMillis: Long? = null,
    @ColumnInfo(name = "recall_reinforcement_at_epoch_millis")
    val recallReinforcementAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "mastery_subject_digest",
    primaryKeys = ["learner_id", "subject"],
    indices = [
        Index(value = ["learner_id", "updated_at_epoch_millis"]),
    ],
)
internal data class MasterySubjectDigestEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "needs_reinforcement_count")
    val needsReinforcementCount: Int,
    @ColumnInfo(name = "familiarizing_count")
    val familiarizingCount: Int,
    @ColumnInfo(name = "steady_count")
    val steadyCount: Int,
    @ColumnInfo(name = "last_event_sequence")
    val lastEventSequence: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "projection_policy_version")
    val projectionPolicyVersion: String,
)

@Entity(
    tableName = "mastery_presentation_node_budget",
    primaryKeys = [
        "learner_id",
        "presentation_id",
        "subject",
        "knowledge_node_id",
        "taxonomy_version",
        "direction",
    ],
    indices = [
        Index(value = ["learner_id", "subject", "direction", "updated_at_epoch_millis"]),
        Index(value = ["stable_node_identity_fingerprint", "direction"]),
    ],
)
internal data class MasteryPresentationNodeBudgetEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    val direction: String,
    @ColumnInfo(name = "stable_node_identity_fingerprint")
    val stableNodeIdentityFingerprint: String,
    @ColumnInfo(name = "consumed_mass_micros")
    val consumedMassMicros: Long,
    @ColumnInfo(name = "last_event_id")
    val lastEventId: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "mastery_problem_family_node_budget",
    primaryKeys = [
        "learner_id",
        "problem_family_fingerprint",
        "subject",
        "knowledge_node_id",
        "taxonomy_version",
        "direction",
    ],
    indices = [
        Index(value = ["learner_id", "subject", "direction", "updated_at_epoch_millis"]),
        Index(value = ["stable_node_identity_fingerprint", "direction"]),
    ],
)
internal data class MasteryProblemFamilyNodeBudgetEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "problem_family_fingerprint")
    val problemFamilyFingerprint: String,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    val direction: String,
    @ColumnInfo(name = "stable_node_identity_fingerprint")
    val stableNodeIdentityFingerprint: String,
    @ColumnInfo(name = "observation_count")
    val observationCount: Long,
    @ColumnInfo(name = "consumed_mass_micros")
    val consumedMassMicros: Long,
    @ColumnInfo(name = "last_event_id")
    val lastEventId: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "mastery_cross_store_inbox",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["envelope_canonical_fingerprint"], unique = true),
        Index(
            value = [
                "source_store",
                "source_store_generation",
                "aggregate_id",
                "aggregate_version",
                "payload_type",
                "payload_version",
            ],
            unique = true,
        ),
    ],
)
internal data class MasteryCrossStoreInboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "source_store")
    val sourceStore: String,
    @ColumnInfo(name = "destination_store")
    val destinationStore: String,
    @ColumnInfo(name = "aggregate_id")
    val aggregateId: String,
    @ColumnInfo(name = "aggregate_version")
    val aggregateVersion: Long,
    @ColumnInfo(name = "payload_type")
    val payloadType: String,
    @ColumnInfo(name = "payload_version")
    val payloadVersion: Int,
    @ColumnInfo(name = "payload_canonical_fingerprint")
    val payloadCanonicalFingerprint: String,
    @ColumnInfo(name = "payload_wire")
    val payloadWire: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "source_store_generation")
    val sourceStoreGeneration: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "received_at_epoch_millis")
    val receivedAtEpochMillis: Long,
    @ColumnInfo(name = "processed_at_epoch_millis")
    val processedAtEpochMillis: Long?,
)

@Entity(
    tableName = "mastery_cross_store_outbox",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["envelope_canonical_fingerprint"], unique = true),
        Index(value = ["destination_store", "published_at_epoch_millis"]),
        Index(value = ["learner_id", "published_at_epoch_millis"]),
        Index(value = ["aggregate_id", "aggregate_version"], unique = true),
    ],
)
internal data class MasteryCrossStoreOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "source_store")
    val sourceStore: String,
    @ColumnInfo(name = "source_store_generation")
    val sourceStoreGeneration: String,
    @ColumnInfo(name = "destination_store")
    val destinationStore: String,
    @ColumnInfo(name = "aggregate_id")
    val aggregateId: String,
    @ColumnInfo(name = "aggregate_version")
    val aggregateVersion: Long,
    @ColumnInfo(name = "payload_type")
    val payloadType: String,
    @ColumnInfo(name = "payload_version")
    val payloadVersion: Int,
    @ColumnInfo(name = "learning_evidence_ref_fingerprint")
    val learningEvidenceRefFingerprint: String,
    @ColumnInfo(name = "problem_revision_ref_fingerprint")
    val problemRevisionRefFingerprint: String?,
    @ColumnInfo(name = "payload_canonical_fingerprint")
    val payloadCanonicalFingerprint: String,
    @ColumnInfo(name = "payload_wire")
    val payloadWire: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "published_at_epoch_millis")
    val publishedAtEpochMillis: Long?,
    @ColumnInfo(name = "learner_id", defaultValue = "''")
    val learnerId: String = "",
)

/**
 * Explicit decision log for taxonomy split/merge/retire handling. Merely activating a new
 * taxonomy never changes a learner projection; a future migration service must append a reviewed
 * decision here and then create new learning events.
 */
@Entity(
    tableName = "mastery_taxonomy_lineage_decision",
    indices = [
        Index(value = ["learner_id", "subject", "status", "reviewed_at_epoch_millis"]),
        Index(
            value = [
                "learner_id",
                "subject",
                "from_knowledge_node_id",
                "from_taxonomy_version",
            ],
        ),
        Index(value = ["decision_fingerprint"], unique = true),
    ],
)
internal data class MasteryTaxonomyLineageDecisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "decision_id")
    val decisionId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "lineage_kind")
    val lineageKind: String,
    @ColumnInfo(name = "from_knowledge_node_id")
    val fromKnowledgeNodeId: String,
    @ColumnInfo(name = "from_taxonomy_version")
    val fromTaxonomyVersion: String,
    @ColumnInfo(name = "to_node_refs_fingerprint")
    val toNodeRefsFingerprint: String?,
    val status: String,
    @ColumnInfo(name = "catalog_manifest_fingerprint")
    val catalogManifestFingerprint: String,
    @ColumnInfo(name = "reviewed_at_epoch_millis")
    val reviewedAtEpochMillis: Long?,
    @ColumnInfo(name = "decision_fingerprint")
    val decisionFingerprint: String,
)

