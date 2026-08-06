package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "learning_observation_source_authority",
    primaryKeys = ["learner_id", "source", "source_reference_id"],
    foreignKeys = [
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LearningObservationSourceFactEntity::class,
            parentColumns = ["source_fact_id"],
            childColumns = ["source_fact_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["practice_unit_id", "problem_revision_id"]),
        Index(value = ["source_fact_id"], unique = true),
    ],
)
internal data class LearningObservationSourceAuthorityEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val source: String,
    @ColumnInfo(name = "source_reference_id")
    val sourceReferenceId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "source_payload_fingerprint")
    val sourcePayloadFingerprint: String,
    @ColumnInfo(name = "verified_at_epoch_millis")
    val verifiedAtEpochMillis: Long,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String?,
)

/**
 * Immutable, locally-attested bridge from a source fact to a versioned projection target.
 *
 * Target references are intentionally self-contained instead of foreign-keying into problem or
 * knowledge tables so the proof remains valid when those stores are physically separated.
 */
@Entity(
    tableName = "learning_observation_source_fact_proof",
    foreignKeys = [
        ForeignKey(
            entity = LearningObservationSourceFactEntity::class,
            parentColumns = ["source_fact_id"],
            childColumns = ["source_fact_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["learner_id", "subject", "anchor_id"]),
        Index(value = ["target_database", "target_id", "target_version"]),
        Index(value = ["proof_kind", "target_kind"]),
        Index(value = ["proof_fingerprint"], unique = true),
        Index(value = ["source_fact_id", "proof_fingerprint"], unique = true),
    ],
)
internal data class LearningObservationSourceFactProofEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "proof_kind")
    val proofKind: String,
    @ColumnInfo(name = "target_kind")
    val targetKind: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "anchor_id")
    val anchorId: String,
    @ColumnInfo(name = "source_reference_id")
    val sourceReferenceId: String,
    @ColumnInfo(name = "source_fingerprint")
    val sourceFingerprint: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String?,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long?,
    @ColumnInfo(name = "turn_receipt_id")
    val turnReceiptId: String?,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String?,
    @ColumnInfo(name = "source_locator_kind")
    val sourceLocatorKind: String,
    @ColumnInfo(name = "source_locator_id")
    val sourceLocatorId: String,
    @ColumnInfo(name = "target_database")
    val targetDatabase: String,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "target_version")
    val targetVersion: String,
    @ColumnInfo(name = "target_fingerprint")
    val targetFingerprint: String,
    @ColumnInfo(name = "target_created_at_epoch_millis")
    val targetCreatedAtEpochMillis: Long,
    @ColumnInfo(name = "attested_at_epoch_millis")
    val attestedAtEpochMillis: Long,
    @ColumnInfo(name = "proof_fingerprint")
    val proofFingerprint: String,
)

@Entity(
    tableName = "learning_observation_candidate",
    foreignKeys = [
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LearningObservationSourceFactEntity::class,
            parentColumns = ["source_fact_id"],
            childColumns = ["source_fact_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["practice_unit_id", "problem_revision_id"]),
        Index(value = ["source_fact_id"], unique = true),
        Index(value = ["learner_id", "status", "retry_count"]),
        Index(value = ["learner_id", "source", "source_reference_id"], unique = true),
        Index(value = ["payload_fingerprint"], unique = true),
    ],
)
internal data class LearningObservationCandidateEntity(
    @PrimaryKey
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val source: String,
    @ColumnInfo(name = "source_reference_id")
    val sourceReferenceId: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String?,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String?,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String?,
    val direction: String,
    @ColumnInfo(name = "evidence_level")
    val evidenceLevel: String,
    @ColumnInfo(name = "evidence_weight")
    val evidenceWeight: Double,
    val independence: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "model_version")
    val modelVersion: String,
    @ColumnInfo(name = "evidence_locator")
    val evidenceLocator: String,
    val status: String,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int,
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "learning_observation_candidate_attribution",
    primaryKeys = ["candidate_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = LearningObservationCandidateEntity::class,
            parentColumns = ["candidate_id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["candidate_id", "binding_id"], unique = true),
        Index(value = ["candidate_id", "knowledge_node_id"], unique = true),
    ],
)
internal data class LearningObservationCandidateAttributionEntity(
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    val ordinal: Int,
    @ColumnInfo(name = "binding_id")
    val bindingId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    val weight: Double,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    val role: String,
    val certainty: String,
)

@Entity(
    tableName = "attributed_learning_observation_event",
    foreignKeys = [
        ForeignKey(
            entity = LearningObservationCandidateEntity::class,
            parentColumns = ["candidate_id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LearningObservationSourceFactEntity::class,
            parentColumns = ["source_fact_id"],
            childColumns = ["source_fact_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["candidate_id"], unique = true),
        Index(value = ["practice_unit_id", "problem_revision_id"]),
        Index(value = ["source_fact_id"], unique = true),
        Index(value = ["learner_id", "event_sequence"], unique = true),
        Index(value = ["learner_id", "event_id"], unique = true),
        Index(value = ["learner_id", "subject", "occurred_at_epoch_millis"]),
        Index(value = ["canonical_fingerprint"], unique = true),
        Index(value = ["event_id", "practice_unit_id"], unique = true),
        Index(value = ["event_id", "canonical_fingerprint"], unique = true),
    ],
)
internal data class AttributedLearningObservationEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String?,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    val subject: String,
    val direction: String,
    @ColumnInfo(name = "evidence_level")
    val evidenceLevel: String,
    @ColumnInfo(name = "evidence_weight")
    val evidenceWeight: Double,
    val independence: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "confirmed_at_epoch_millis")
    val confirmedAtEpochMillis: Long,
    @ColumnInfo(name = "model_version")
    val modelVersion: String,
    @ColumnInfo(name = "evidence_locator")
    val evidenceLocator: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
)

/**
 * Persistence capability that admits one otherwise inert observation event to projection.
 *
 * Both composite foreign keys bind the receipt to the exact immutable event and source proof,
 * instead of merely asserting that rows with the same ids happen to exist.
 */
@Entity(
    tableName = "learning_observation_event_admission",
    foreignKeys = [
        ForeignKey(
            entity = AttributedLearningObservationEventEntity::class,
            parentColumns = ["event_id", "canonical_fingerprint"],
            childColumns = ["event_id", "raw_event_canonical_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LearningObservationSourceFactProofEntity::class,
            parentColumns = ["source_fact_id", "proof_fingerprint"],
            childColumns = ["source_fact_id", "source_fact_proof_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["event_id", "raw_event_canonical_fingerprint"], unique = true),
        Index(value = ["source_fact_id", "source_fact_proof_fingerprint"], unique = true),
        Index(value = ["admission_fingerprint"], unique = true),
    ],
)
internal data class LearningObservationEventAdmissionEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "raw_event_canonical_fingerprint")
    val rawEventCanonicalFingerprint: String,
    @ColumnInfo(name = "source_fact_proof_fingerprint")
    val sourceFactProofFingerprint: String,
    @ColumnInfo(name = "policy_version")
    val policyVersion: String,
    @ColumnInfo(name = "admission_fingerprint")
    val admissionFingerprint: String,
    @ColumnInfo(name = "admitted_at_epoch_millis")
    val admittedAtEpochMillis: Long,
)

@Entity(
    tableName = "learning_observation_event_attribution",
    primaryKeys = ["event_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = AttributedLearningObservationEventEntity::class,
            parentColumns = ["event_id", "practice_unit_id"],
            childColumns = ["event_id", "practice_unit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PracticeUnitKnowledgeBindingEntity::class,
            parentColumns = [
                "binding_id",
                "practice_unit_id",
                "knowledge_node_id",
                "basis_revision_id",
                "taxonomy_version",
            ],
            childColumns = [
                "binding_id",
                "practice_unit_id",
                "knowledge_node_id",
                "basis_revision_id",
                "taxonomy_version",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["event_id", "practice_unit_id"]),
        Index(
            value = [
                "binding_id",
                "practice_unit_id",
                "knowledge_node_id",
                "basis_revision_id",
                "taxonomy_version",
            ],
        ),
        Index(value = ["event_id", "binding_id"], unique = true),
        Index(value = ["event_id", "knowledge_node_id"], unique = true),
    ],
)
internal data class LearningObservationEventAttributionEntity(
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    val ordinal: Int,
    @ColumnInfo(name = "binding_id")
    val bindingId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    val weight: Double,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    val role: String,
    val certainty: String,
)

@Entity(
    tableName = "learning_evidence_review_case",
    foreignKeys = [
        ForeignKey(
            entity = LearningObservationCandidateEntity::class,
            parentColumns = ["candidate_id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["candidate_id"]),
        Index(value = ["learner_id", "status", "created_at_epoch_millis"]),
        Index(value = ["candidate_id", "proposed_event_id", "reason"], unique = true),
    ],
)
internal data class LearningEvidenceReviewCaseEntity(
    @PrimaryKey
    @ColumnInfo(name = "review_case_id")
    val reviewCaseId: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "proposed_event_id")
    val proposedEventId: String,
    val reason: String,
    val detail: String,
    val status: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "resolved_at_epoch_millis")
    val resolvedAtEpochMillis: Long?,
)

@Entity(
    tableName = "applied_learning_observation_record",
    primaryKeys = ["projection_name", "learner_id", "event_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearnerProjectionSnapshotEntity::class,
            parentColumns = ["projection_name", "learner_id"],
            childColumns = ["projection_name", "learner_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AttributedLearningObservationEventEntity::class,
            parentColumns = ["learner_id", "event_id"],
            childColumns = ["learner_id", "event_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["projection_name", "learner_id"]),
        Index(value = ["learner_id", "event_id"]),
        Index(value = ["projection_name", "learner_id", "event_sequence"], unique = true),
    ],
)
internal data class AppliedLearningObservationRecordEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
)
