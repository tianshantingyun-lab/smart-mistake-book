package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

internal const val LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE =
    "mastery_projection_budget_rebuild_receipt"
internal const val LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE =
    "mastery_directional_budget_migration_receipt"
internal const val LEARNER_MASTERY_DIRECTIONAL_BUDGET_POLICY_VERSION =
    "learner-mastery-evidence-budget-v2"
internal const val LEARNER_MASTERY_DIRECTIONAL_BUDGET_REBUILD_ALGORITHM_VERSION =
    "learner-mastery-directional-budget-rebuild-v2"
internal const val DIRECTIONAL_BUDGET_REBUILD_EPOCH =
    "presentation-fingerprint-direction-budget-v3"
internal const val DIRECTIONAL_BUDGET_REBUILD_REQUIRED_METADATA_KEY =
    "presentation_fingerprint_budget_rebuild_required_v2"
internal const val DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY =
    "presentation_fingerprint_budget_rebuild_completed_v2"
internal const val DIRECTIONAL_BUDGET_GENERATION_EPOCH_METADATA_PREFIX =
    "presentation_fingerprint_budget_generation_epoch_v2:"

internal fun directionalBudgetGenerationEpochMetadataKey(generationId: Long): String {
    require(generationId > 0L) { "Projection generation id must be positive" }
    return DIRECTIONAL_BUDGET_GENERATION_EPOCH_METADATA_PREFIX + generationId
}

internal enum class MasteryProjectionGenerationState {
    BUILDING,
    ACTIVE,
    RETIRED,
    BLOCKED,
}

/**
 * Durable control row for one derived-state generation.
 *
 * Immutable learning events remain the source of truth. A BUILDING generation owns only shadow
 * rows and can be discarded or resumed. ACTIVE is switched only after the captured immutable
 * ledger watermarks still match and the shadow snapshot has a canonical fingerprint.
 */
@Entity(
    tableName = "mastery_projection_generation",
    indices = [
        Index(value = ["state", "generation_id"]),
        Index(value = ["lease_expires_at_epoch_millis"]),
    ],
)
internal data class MasteryProjectionGenerationEntity(
    @PrimaryKey
    @ColumnInfo(name = "generation_id")
    val generationId: Long,
    val state: String,
    @ColumnInfo(name = "target_projection_policy_version")
    val targetProjectionPolicyVersion: String,
    @ColumnInfo(name = "target_calibration_version")
    val targetCalibrationVersion: String,
    @ColumnInfo(name = "source_event_count")
    val sourceEventCount: Long,
    @ColumnInfo(name = "source_supersession_count")
    val sourceSupersessionCount: Long,
    val stage: String,
    @ColumnInfo(name = "cursor_learner_id")
    val cursorLearnerId: String,
    @ColumnInfo(name = "cursor_subject")
    val cursorSubject: String,
    @ColumnInfo(name = "cursor_event_sequence")
    val cursorEventSequence: Long,
    @ColumnInfo(name = "cursor_ordinal")
    val cursorOrdinal: Int,
    @ColumnInfo(name = "cursor_occurred_at_epoch_millis", defaultValue = "-1")
    val cursorOccurredAtEpochMillis: Long = -1L,
    @ColumnInfo(name = "cursor_event_id", defaultValue = "''")
    val cursorEventId: String = "",
    @ColumnInfo(name = "cursor_direction", defaultValue = "''")
    val cursorDirection: String = "",
    @ColumnInfo(name = "lease_owner_id")
    val leaseOwnerId: String?,
    @ColumnInfo(name = "lease_expires_at_epoch_millis")
    val leaseExpiresAtEpochMillis: Long?,
    @ColumnInfo(name = "snapshot_fingerprint")
    val snapshotFingerprint: String?,
    @ColumnInfo(name = "projection_row_count")
    val projectionRowCount: Long?,
    @ColumnInfo(name = "subject_digest_row_count")
    val subjectDigestRowCount: Long?,
    @ColumnInfo(name = "presentation_budget_row_count")
    val presentationBudgetRowCount: Long? = null,
    @ColumnInfo(name = "problem_family_budget_row_count")
    val problemFamilyBudgetRowCount: Long? = null,
    @ColumnInfo(name = "budget_input_snapshot_fingerprint")
    val budgetInputSnapshotFingerprint: String? = null,
    @ColumnInfo(name = "budget_output_fingerprint")
    val budgetOutputFingerprint: String? = null,
    @ColumnInfo(name = "budget_input_row_count", defaultValue = "0")
    val budgetInputRowCount: Long = 0L,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "activated_at_epoch_millis")
    val activatedAtEpochMillis: Long?,
    @ColumnInfo(name = "calibration_release_id")
    val calibrationReleaseId: String? = null,
    @ColumnInfo(name = "calibration_release_fingerprint")
    val calibrationReleaseFingerprint: String? = null,
    @ColumnInfo(name = "projection_input_set_fingerprint")
    val projectionInputSetFingerprint: String? = null,
    @ColumnInfo(name = "projection_implementation_fingerprint")
    val projectionImplementationFingerprint: String? = null,
    @ColumnInfo(name = "generation_manifest_fingerprint")
    val generationManifestFingerprint: String? = null,
)

/** Immutable proof that one directional budget generation was rebuilt from exact ledger rows. */
@Entity(
    tableName = LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = MasteryProjectionGenerationEntity::class,
            parentColumns = ["generation_id"],
            childColumns = ["generation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["input_snapshot_fingerprint"]),
        Index(value = ["output_fingerprint"]),
        Index(value = ["completed_at_epoch_millis"]),
    ],
)
internal data class ProjectionBudgetRebuildReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "generation_id")
    val generationId: Long,
    @ColumnInfo(name = "budget_policy_version")
    val budgetPolicyVersion: String,
    @ColumnInfo(name = "algorithm_version")
    val algorithmVersion: String,
    @ColumnInfo(name = "source_event_count")
    val sourceEventCount: Long,
    @ColumnInfo(name = "source_supersession_count")
    val sourceSupersessionCount: Long,
    @ColumnInfo(name = "input_row_count")
    val inputRowCount: Long,
    @ColumnInfo(name = "input_snapshot_fingerprint")
    val inputSnapshotFingerprint: String,
    @ColumnInfo(name = "presentation_budget_row_count")
    val presentationBudgetRowCount: Long,
    @ColumnInfo(name = "problem_family_budget_row_count")
    val problemFamilyBudgetRowCount: Long,
    @ColumnInfo(name = "last_occurred_at_epoch_millis")
    val lastOccurredAtEpochMillis: Long?,
    @ColumnInfo(name = "last_event_id")
    val lastEventId: String?,
    @ColumnInfo(name = "last_attribution_ordinal")
    val lastAttributionOrdinal: Int?,
    @ColumnInfo(name = "last_direction")
    val lastDirection: String?,
    @ColumnInfo(name = "output_fingerprint")
    val outputFingerprint: String,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long,
)

/** Audit-only receipt for discarding directionless v19 derived budgets without touching facts. */
@Entity(
    tableName = LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE,
    indices = [
        Index(value = ["canonical_fingerprint"], unique = true),
        Index(value = ["recorded_at_epoch_millis"]),
    ],
)
internal data class DirectionalBudgetMigrationReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "migration_id")
    val migrationId: String,
    @ColumnInfo(name = "from_schema_version")
    val fromSchemaVersion: Int,
    @ColumnInfo(name = "to_schema_version")
    val toSchemaVersion: Int,
    @ColumnInfo(name = "immutable_event_count")
    val immutableEventCount: Long,
    @ColumnInfo(name = "immutable_attribution_count")
    val immutableAttributionCount: Long,
    @ColumnInfo(name = "supersession_count")
    val supersessionCount: Long,
    @ColumnInfo(name = "discarded_active_presentation_count")
    val discardedActivePresentationCount: Long,
    @ColumnInfo(name = "discarded_active_problem_family_count")
    val discardedActiveProblemFamilyCount: Long,
    @ColumnInfo(name = "discarded_shadow_presentation_count")
    val discardedShadowPresentationCount: Long,
    @ColumnInfo(name = "discarded_shadow_problem_family_count")
    val discardedShadowProblemFamilyCount: Long,
    @ColumnInfo(name = "retired_building_generation_count")
    val retiredBuildingGenerationCount: Long,
    @ColumnInfo(name = "required_rebuild_epoch")
    val requiredRebuildEpoch: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "recorded_at_epoch_millis")
    val recordedAtEpochMillis: Long,
)

@Entity(
    tableName = "mastery_projection_shadow",
    primaryKeys = [
        "generation_id",
        "learner_id",
        "subject",
        "knowledge_node_id",
        "taxonomy_version",
    ],
    indices = [
        Index(value = ["generation_id", "learner_id", "subject"]),
        Index(value = ["generation_id", "stable_node_identity_fingerprint"]),
    ],
)
internal data class MasteryProjectionShadowEntity(
    @ColumnInfo(name = "generation_id")
    val generationId: Long,
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
    @ColumnInfo(name = "evidence_quality_micros")
    val evidenceQualityMicros: Long,
    @ColumnInfo(name = "independent_problem_family_count")
    val independentProblemFamilyCount: Long,
    @ColumnInfo(name = "distinct_presentation_count")
    val distinctPresentationCount: Long,
    @ColumnInfo(name = "historical_log_odds_micros")
    val historicalLogOddsMicros: Long?,
    @ColumnInfo(name = "calibration_snapshot_fingerprint")
    val calibrationSnapshotFingerprint: String?,
    @ColumnInfo(name = "calibration_profile_id")
    val calibrationProfileId: String?,
    @ColumnInfo(name = "calibration_version")
    val calibrationVersion: String?,
    @ColumnInfo(name = "recall_familiarizing_at_epoch_millis")
    val recallFamiliarizingAtEpochMillis: Long?,
    @ColumnInfo(name = "recall_reinforcement_at_epoch_millis")
    val recallReinforcementAtEpochMillis: Long?,
)

@Entity(
    tableName = "mastery_subject_digest_shadow",
    primaryKeys = ["generation_id", "learner_id", "subject"],
    indices = [Index(value = ["generation_id", "learner_id"])],
)
internal data class MasterySubjectDigestShadowEntity(
    @ColumnInfo(name = "generation_id")
    val generationId: Long,
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
    tableName = "mastery_presentation_node_budget_shadow",
    primaryKeys = [
        "generation_id",
        "learner_id",
        "presentation_id",
        "subject",
        "knowledge_node_id",
        "taxonomy_version",
        "direction",
    ],
    indices = [Index(value = ["generation_id", "learner_id", "subject", "direction"])],
)
internal data class MasteryPresentationNodeBudgetShadowEntity(
    @ColumnInfo(name = "generation_id")
    val generationId: Long,
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
    tableName = "mastery_problem_family_node_budget_shadow",
    primaryKeys = [
        "generation_id",
        "learner_id",
        "problem_family_fingerprint",
        "subject",
        "knowledge_node_id",
        "taxonomy_version",
        "direction",
    ],
    indices = [Index(value = ["generation_id", "learner_id", "subject", "direction"])],
)
internal data class MasteryProblemFamilyNodeBudgetShadowEntity(
    @ColumnInfo(name = "generation_id")
    val generationId: Long,
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

internal fun MasteryKnowledgeProjectionEntity.toShadow(
    generationId: Long,
): MasteryProjectionShadowEntity =
    MasteryProjectionShadowEntity(
        generationId = generationId,
        learnerId = learnerId,
        subject = subject,
        knowledgeNodeId = knowledgeNodeId,
        taxonomyVersion = taxonomyVersion,
        latestEvidenceKnowledgePackVersion = latestEvidenceKnowledgePackVersion,
        stableNodeIdentityFingerprint = stableNodeIdentityFingerprint,
        positiveEvidenceMicros = positiveEvidenceMicros,
        negativeEvidenceMicros = negativeEvidenceMicros,
        masteryScoreMicros = masteryScoreMicros,
        masteryState = masteryState,
        trend = trend,
        observationCount = observationCount,
        memoryStabilityMillis = memoryStabilityMillis,
        recallDueAtEpochMillis = recallDueAtEpochMillis,
        lastPositiveAtEpochMillis = lastPositiveAtEpochMillis,
        lastNegativeAtEpochMillis = lastNegativeAtEpochMillis,
        lastEvidenceAtEpochMillis = lastEvidenceAtEpochMillis,
        lastEventSequence = lastEventSequence,
        lastOrderedEventId = lastOrderedEventId,
        projectionPolicyVersion = projectionPolicyVersion,
        evidenceQualityMicros = evidenceQualityMicros,
        independentProblemFamilyCount = independentProblemFamilyCount,
        distinctPresentationCount = distinctPresentationCount,
        historicalLogOddsMicros = historicalLogOddsMicros,
        calibrationSnapshotFingerprint = calibrationSnapshotFingerprint,
        calibrationProfileId = calibrationProfileId,
        calibrationVersion = calibrationVersion,
        recallFamiliarizingAtEpochMillis = recallFamiliarizingAtEpochMillis,
        recallReinforcementAtEpochMillis = recallReinforcementAtEpochMillis,
    )

internal fun MasteryProjectionShadowEntity.toProjection(): MasteryKnowledgeProjectionEntity =
    MasteryKnowledgeProjectionEntity(
        learnerId = learnerId,
        subject = subject,
        knowledgeNodeId = knowledgeNodeId,
        taxonomyVersion = taxonomyVersion,
        latestEvidenceKnowledgePackVersion = latestEvidenceKnowledgePackVersion,
        stableNodeIdentityFingerprint = stableNodeIdentityFingerprint,
        positiveEvidenceMicros = positiveEvidenceMicros,
        negativeEvidenceMicros = negativeEvidenceMicros,
        masteryScoreMicros = masteryScoreMicros,
        masteryState = masteryState,
        trend = trend,
        observationCount = observationCount,
        memoryStabilityMillis = memoryStabilityMillis,
        recallDueAtEpochMillis = recallDueAtEpochMillis,
        lastPositiveAtEpochMillis = lastPositiveAtEpochMillis,
        lastNegativeAtEpochMillis = lastNegativeAtEpochMillis,
        lastEvidenceAtEpochMillis = lastEvidenceAtEpochMillis,
        lastEventSequence = lastEventSequence,
        lastOrderedEventId = lastOrderedEventId,
        projectionPolicyVersion = projectionPolicyVersion,
        evidenceQualityMicros = evidenceQualityMicros,
        independentProblemFamilyCount = independentProblemFamilyCount,
        distinctPresentationCount = distinctPresentationCount,
        historicalLogOddsMicros = historicalLogOddsMicros,
        calibrationSnapshotFingerprint = calibrationSnapshotFingerprint,
        calibrationProfileId = calibrationProfileId,
        calibrationVersion = calibrationVersion,
        recallFamiliarizingAtEpochMillis = recallFamiliarizingAtEpochMillis,
        recallReinforcementAtEpochMillis = recallReinforcementAtEpochMillis,
    )
