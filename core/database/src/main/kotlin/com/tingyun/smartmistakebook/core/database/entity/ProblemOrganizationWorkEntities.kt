package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "problem_solution_step",
    primaryKeys = ["solution_step_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemOrganizationReceiptEntity::class,
            parentColumns = ["command_id"],
            childColumns = ["organization_command_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProblemDraftCommitReceiptEntity::class,
            parentColumns = ["command_id"],
            childColumns = ["source_commit_receipt_command_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["organization_command_id", "step_ordinal"], unique = true),
        Index(value = ["practice_unit_id", "problem_revision_id"]),
        Index(value = ["source_commit_receipt_command_id"]),
    ],
)
internal data class ProblemSolutionStepEntity(
    @ColumnInfo(name = "solution_step_id")
    val solutionStepId: String,
    @ColumnInfo(name = "organization_command_id")
    val organizationCommandId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "source_commit_receipt_command_id")
    val sourceCommitReceiptCommandId: String,
    @ColumnInfo(name = "step_ordinal")
    val stepOrdinal: Int,
    @ColumnInfo(name = "summary_markdown")
    val summaryMarkdown: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "problem_step_knowledge_binding",
    primaryKeys = ["solution_step_id", "knowledge_node_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemSolutionStepEntity::class,
            parentColumns = ["solution_step_id"],
            childColumns = ["solution_step_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["knowledge_node_id"]),
    ],
)
internal data class ProblemStepKnowledgeBindingEntity(
    @ColumnInfo(name = "solution_step_id")
    val solutionStepId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "knowledge_reference_id")
    val knowledgeReferenceId: String,
)

@Entity(
    tableName = "problem_error_attribution_candidate",
    primaryKeys = ["error_attribution_candidate_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemOrganizationReceiptEntity::class,
            parentColumns = ["command_id"],
            childColumns = ["organization_command_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProblemDraftCommitReceiptEntity::class,
            parentColumns = ["command_id"],
            childColumns = ["source_commit_receipt_command_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProblemSolutionStepEntity::class,
            parentColumns = ["solution_step_id"],
            childColumns = ["solution_step_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["organization_command_id", "candidate_ordinal"], unique = true),
        Index(value = ["practice_unit_id", "problem_revision_id"]),
        Index(value = ["source_commit_receipt_command_id"]),
        Index(value = ["solution_step_id"]),
        Index(value = ["knowledge_node_id"]),
        Index(value = ["resolution_status", "created_at_epoch_millis"]),
    ],
)
internal data class ProblemErrorAttributionCandidateEntity(
    @ColumnInfo(name = "error_attribution_candidate_id")
    val errorAttributionCandidateId: String,
    @ColumnInfo(name = "organization_command_id")
    val organizationCommandId: String,
    @ColumnInfo(name = "candidate_ordinal")
    val candidateOrdinal: Int,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "source_commit_receipt_command_id")
    val sourceCommitReceiptCommandId: String,
    @ColumnInfo(name = "resolution_status")
    val resolutionStatus: String,
    @ColumnInfo(name = "solution_step_id")
    val solutionStepId: String?,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String?,
    @ColumnInfo(name = "knowledge_reference_id")
    val knowledgeReferenceId: String?,
    @ColumnInfo(name = "rationale_markdown")
    val rationaleMarkdown: String,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "model_version")
    val modelVersion: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "problem_error_candidate_evidence",
    primaryKeys = ["error_attribution_candidate_id", "evidence_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemErrorAttributionCandidateEntity::class,
            parentColumns = ["error_attribution_candidate_id"],
            childColumns = ["error_attribution_candidate_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CanonicalSourceAssetEntity::class,
            parentColumns = ["source_asset_id"],
            childColumns = ["source_asset_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["source_asset_id"]),
    ],
)
internal data class ProblemErrorCandidateEvidenceEntity(
    @ColumnInfo(name = "error_attribution_candidate_id")
    val errorAttributionCandidateId: String,
    @ColumnInfo(name = "evidence_ordinal")
    val evidenceOrdinal: Int,
    @ColumnInfo(name = "block_id")
    val blockId: String,
    @ColumnInfo(name = "source_asset_id")
    val sourceAssetId: String,
    @ColumnInfo(name = "evidence_kind")
    val evidenceKind: String,
)

@Entity(
    tableName = "problem_organization_work",
    primaryKeys = ["work_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemDraftCommitReceiptEntity::class,
            parentColumns = ["command_id"],
            childColumns = ["commit_receipt_command_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["commit_receipt_command_id"], unique = true),
        Index(value = ["status", "not_before_epoch_millis", "created_at_epoch_millis"]),
        Index(value = ["request_id"], unique = true),
        Index(value = ["lease_expires_at_epoch_millis"]),
    ],
)
internal data class ProblemOrganizationWorkEntity(
    @ColumnInfo(name = "work_id")
    val workId: String,
    @ColumnInfo(name = "commit_receipt_command_id")
    val commitReceiptCommandId: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "not_before_epoch_millis")
    val notBeforeEpochMillis: Long,
    @ColumnInfo(name = "request_id")
    val requestId: String?,
    @ColumnInfo(name = "request_snapshot")
    val requestSnapshot: String?,
    @ColumnInfo(name = "authorization_grant_snapshot")
    val authorizationGrantSnapshot: String?,
    @ColumnInfo(name = "lease_owner")
    val leaseOwner: String?,
    @ColumnInfo(name = "lease_expires_at_epoch_millis")
    val leaseExpiresAtEpochMillis: Long?,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
    @ColumnInfo(name = "failure_message")
    val failureMessage: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)
