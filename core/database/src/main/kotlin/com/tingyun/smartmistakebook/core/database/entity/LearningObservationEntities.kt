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
    ],
    indices = [
        Index(value = ["practice_unit_id", "problem_revision_id"]),
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
    ],
    indices = [
        Index(value = ["practice_unit_id", "problem_revision_id"]),
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
    ],
    indices = [
        Index(value = ["candidate_id"], unique = true),
        Index(value = ["practice_unit_id", "problem_revision_id"]),
        Index(value = ["learner_id", "event_sequence"], unique = true),
        Index(value = ["learner_id", "event_id"], unique = true),
        Index(value = ["learner_id", "subject", "occurred_at_epoch_millis"]),
        Index(value = ["canonical_fingerprint"], unique = true),
        Index(value = ["event_id", "practice_unit_id"], unique = true),
    ],
)
internal data class AttributedLearningObservationEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "candidate_id")
    val candidateId: String,
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
