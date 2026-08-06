package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.tingyun.smartmistakebook.core.database.StudyDbValue

@Entity(
    tableName = "problem",
    indices = [Index(value = ["canonical_fingerprint"], unique = true)],
)
internal data class ProblemEntity(
    @PrimaryKey
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    val subject: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "archived_at_epoch_millis")
    val archivedAtEpochMillis: Long? = null,
)

/** Revisions are append-only. No DAO exposes an update or delete operation for this table. */
@Entity(
    tableName = "problem_revision",
    foreignKeys = [
        ForeignKey(
            entity = ProblemEntity::class,
            parentColumns = ["problem_id"],
            childColumns = ["problem_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["problem_id"]),
        Index(value = ["problem_id", "revision_id"], unique = true),
        Index(value = ["problem_id", "revision_number"], unique = true),
        Index(value = ["problem_id", "content_fingerprint"], unique = true),
    ],
)
internal data class ProblemRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    val title: String,
    @ColumnInfo(name = "problem_markdown")
    val problemMarkdown: String,
    @ColumnInfo(name = "question_document_snapshot")
    val questionDocumentSnapshot: String? = null,
    @ColumnInfo(name = "answer_spec_id")
    val answerSpecId: String?,
    @ColumnInfo(name = "answer_spec_snapshot")
    val answerSpecSnapshot: String?,
    @ColumnInfo(name = "answer_verification_status")
    val answerVerificationStatus: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_reference")
    val sourceReference: String?,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "practice_unit",
    foreignKeys = [
        ForeignKey(
            entity = ProblemRevisionEntity::class,
            parentColumns = ["problem_id", "revision_id"],
            childColumns = ["problem_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["problem_id"]),
        Index(value = ["problem_revision_id"]),
        Index(value = ["problem_id", "problem_revision_id"]),
        Index(value = ["practice_unit_id", "problem_id"], unique = true),
        Index(value = ["practice_unit_id", "problem_revision_id"], unique = true),
        Index(value = ["problem_revision_id", "unit_key"], unique = true),
    ],
)
internal data class PracticeUnitEntity(
    @PrimaryKey
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "unit_key")
    val unitKey: String,
    @ColumnInfo(name = "unit_kind")
    val unitKind: String,
    val title: String,
    @ColumnInfo(name = "prompt_markdown")
    val promptMarkdown: String,
    @ColumnInfo(name = "estimated_seconds")
    val estimatedSeconds: Int,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "error_book_entry",
    foreignKeys = [
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_id"],
            childColumns = ["practice_unit_id", "problem_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProblemRevisionEntity::class,
            parentColumns = ["problem_id", "revision_id"],
            childColumns = ["problem_id", "current_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["practice_unit_id"], unique = true),
        Index(value = ["practice_unit_id", "problem_id"]),
        Index(value = ["current_revision_id"]),
        Index(value = ["problem_id", "current_revision_id"]),
        Index(value = ["source_key"], unique = true),
        Index(value = ["status", "updated_at_epoch_millis"]),
    ],
)
internal data class ErrorBookEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "current_revision_id")
    val currentRevisionId: String,
    @ColumnInfo(name = "source_key")
    val sourceKey: String?,
    val status: String,
    @ColumnInfo(name = "accepted_at_epoch_millis")
    val acceptedAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "knowledge_node",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["parent_knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["stable_code"], unique = true),
        Index(value = ["parent_knowledge_node_id"]),
        Index(value = ["subject", "display_name"]),
        Index(value = ["subject", "canonical_name"]),
        Index(value = ["subject", "granularity"]),
    ],
)
internal data class KnowledgeNodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "stable_code")
    val stableCode: String,
    val subject: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "canonical_name", defaultValue = "''")
    val canonicalName: String,
    @ColumnInfo(name = "node_kind", defaultValue = "'TOPIC'")
    val nodeKind: String,
    @ColumnInfo(name = "granularity", defaultValue = "'TOPIC'")
    val granularity: String,
    @ColumnInfo(name = "aliases_text", defaultValue = "''")
    val aliasesText: String,
    @ColumnInfo(name = "boundary_markdown")
    val boundaryMarkdown: String?,
    @ColumnInfo(name = "verification_status", defaultValue = "'MODEL_CANDIDATE'")
    val verificationStatus: String,
    @ColumnInfo(name = "parent_knowledge_node_id")
    val parentKnowledgeNodeId: String?,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "knowledge_source",
    indices = [
        Index(value = ["content_fingerprint"], unique = true),
        Index(value = ["subject", "source_type"]),
    ],
)
internal data class KnowledgeSourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    val subject: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    val title: String,
    val publisher: String?,
    val edition: String?,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String?,
    @ColumnInfo(name = "license_status")
    val licenseStatus: String,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "imported_at_epoch_millis")
    val importedAtEpochMillis: Long,
    @ColumnInfo(
        name = "content_use_policy",
        defaultValue = "'REVIEWED_SYNTHESIS_ONLY'",
    )
    val contentUsePolicy: String = "REVIEWED_SYNTHESIS_ONLY",
    @ColumnInfo(name = "license_expression")
    val licenseExpression: String? = null,
    @ColumnInfo(name = "license_uri")
    val licenseUri: String? = null,
    @ColumnInfo(name = "attribution_text")
    val attributionText: String? = null,
)

@Entity(
    tableName = "knowledge_node_source_binding",
    primaryKeys = ["knowledge_node_id", "source_id", "source_locator"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KnowledgeSourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["knowledge_node_id"]),
        Index(value = ["source_id"]),
    ],
)
internal data class KnowledgeNodeSourceBindingEntity(
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "source_locator")
    val sourceLocator: String,
    @ColumnInfo(name = "derivation_note")
    val derivationNote: String,
    @ColumnInfo(name = "reviewed_at_epoch_millis")
    val reviewedAtEpochMillis: Long?,
)

@Entity(
    tableName = "knowledge_node_relation",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["prerequisite_knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["dependent_knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = KnowledgeSourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = [
                "prerequisite_knowledge_node_id",
                "dependent_knowledge_node_id",
                "relation_type",
            ],
            unique = true,
        ),
        Index(value = ["dependent_knowledge_node_id"]),
        Index(value = ["source_id"]),
        Index(value = ["subject", "relation_type"]),
        Index(value = ["subject", "dependent_knowledge_node_id"]),
    ],
)
internal data class KnowledgeNodeRelationEntity(
    @PrimaryKey
    @ColumnInfo(name = "relation_id")
    val relationId: String,
    val subject: String,
    @ColumnInfo(name = "prerequisite_knowledge_node_id")
    val prerequisiteKnowledgeNodeId: String,
    @ColumnInfo(name = "dependent_knowledge_node_id")
    val dependentKnowledgeNodeId: String,
    @ColumnInfo(name = "relation_type")
    val relationType: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "source_locator")
    val sourceLocator: String,
    @ColumnInfo(name = "reviewed_at_epoch_millis")
    val reviewedAtEpochMillis: Long,
)

@Entity(
    tableName = "knowledge_grounding_request",
    indices = [
        Index(
            value = ["organization_request_id", "request_ordinal"],
            unique = true,
        ),
        Index(value = ["status", "created_at_epoch_millis"]),
        Index(value = ["subject", "status"]),
        Index(value = ["grounding_key", "status"]),
        Index(value = ["problem_revision_id"]),
    ],
)
internal data class KnowledgeGroundingRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "grounding_request_id")
    val groundingRequestId: String,
    @ColumnInfo(name = "grounding_key")
    val groundingKey: String,
    @ColumnInfo(name = "organization_request_id")
    val organizationRequestId: String,
    @ColumnInfo(name = "organization_request_fingerprint")
    val organizationRequestFingerprint: String,
    @ColumnInfo(name = "request_ordinal")
    val requestOrdinal: Int,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    val subject: String,
    val query: String,
    @ColumnInfo(name = "expected_parent_knowledge_display_name")
    val expectedParentKnowledgeDisplayName: String,
    @ColumnInfo(name = "reason_markdown")
    val reasonMarkdown: String,
    val status: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "knowledge_grounding_resolution",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["resolution_id"], unique = true),
        Index(value = ["knowledge_node_id"]),
        Index(value = ["subject", "resolved_at_epoch_millis"]),
    ],
)
internal data class KnowledgeGroundingResolutionEntity(
    @PrimaryKey
    @ColumnInfo(name = "grounding_key")
    val groundingKey: String,
    @ColumnInfo(name = "resolution_id")
    val resolutionId: String,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "resolved_occurrence_count")
    val resolvedOccurrenceCount: Int,
    @ColumnInfo(name = "linked_practice_unit_count")
    val linkedPracticeUnitCount: Int,
    @ColumnInfo(name = "resolved_at_epoch_millis")
    val resolvedAtEpochMillis: Long,
)

@Entity(
    tableName = "practice_unit_knowledge_binding",
    foreignKeys = [
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "basis_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["practice_unit_id"]),
        Index(value = ["basis_revision_id"]),
        Index(value = ["practice_unit_id", "basis_revision_id"]),
        Index(value = ["knowledge_node_id"]),
        Index(
            value = [
                "binding_id",
                "practice_unit_id",
                "knowledge_node_id",
                "basis_revision_id",
                "taxonomy_version",
            ],
            unique = true,
        ),
        Index(
            value = [
                "practice_unit_id",
                "knowledge_node_id",
                "knowledge_subject",
                "knowledge_taxonomy_version",
                "knowledge_pack_version",
                "basis_revision_id",
                "taxonomy_version",
            ],
            unique = true,
        ),
    ],
)
internal data class PracticeUnitKnowledgeBindingEntity(
    @PrimaryKey
    @ColumnInfo(name = "binding_id")
    val bindingId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "knowledge_subject")
    val knowledgeSubject: String?,
    @ColumnInfo(name = "knowledge_taxonomy_version")
    val knowledgeTaxonomyVersion: String?,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String?,
    @ColumnInfo(name = "knowledge_manifest_fingerprint")
    val knowledgeManifestFingerprint: String?,
    @ColumnInfo(name = "knowledge_activation_generation")
    val knowledgeActivationGeneration: Long?,
    @ColumnInfo(name = "knowledge_reference_status")
    val knowledgeReferenceStatus: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    val strength: Double,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "accepted_at_epoch_millis")
    val acceptedAtEpochMillis: Long,
) {
    init {
        val hasCompleteReference =
            knowledgeSubject != null &&
                knowledgeTaxonomyVersion != null &&
                knowledgePackVersion != null &&
                knowledgeManifestFingerprint != null &&
                knowledgeActivationGeneration != null
        require(
            when (knowledgeReferenceStatus) {
                StudyDbValue.KnowledgeReferenceStatus.VERIFIED_AT_CONFIRMATION ->
                    hasCompleteReference && (knowledgeActivationGeneration ?: 0L) > 0L

                StudyDbValue.KnowledgeReferenceStatus.PENDING_REATTRIBUTION ->
                    !hasCompleteReference &&
                        knowledgeSubject == null &&
                        knowledgeTaxonomyVersion == null &&
                        knowledgePackVersion == null &&
                        knowledgeManifestFingerprint == null &&
                        knowledgeActivationGeneration == null

                else -> false
            },
        ) {
            "Knowledge binding reference provenance is incomplete"
        }
    }
}

@Entity(
    tableName = "problem_relation",
    foreignKeys = [
        ForeignKey(
            entity = ProblemRevisionEntity::class,
            parentColumns = ["problem_id", "revision_id"],
            childColumns = ["source_problem_id", "source_basis_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProblemRevisionEntity::class,
            parentColumns = ["problem_id", "revision_id"],
            childColumns = ["target_problem_id", "target_basis_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["source_problem_id"]),
        Index(value = ["target_problem_id"]),
        Index(value = ["source_basis_revision_id"]),
        Index(value = ["target_basis_revision_id"]),
        Index(value = ["source_problem_id", "source_basis_revision_id"]),
        Index(value = ["target_problem_id", "target_basis_revision_id"]),
        Index(
            value = [
                "source_problem_id",
                "target_problem_id",
                "relation_type",
                "source_basis_revision_id",
                "target_basis_revision_id",
            ],
            unique = true,
        ),
    ],
)
internal data class ProblemRelationEntity(
    @PrimaryKey
    @ColumnInfo(name = "relation_id")
    val relationId: String,
    @ColumnInfo(name = "source_problem_id")
    val sourceProblemId: String,
    @ColumnInfo(name = "target_problem_id")
    val targetProblemId: String,
    @ColumnInfo(name = "relation_type")
    val relationType: String,
    val status: String,
    @ColumnInfo(name = "source_basis_revision_id")
    val sourceBasisRevisionId: String,
    @ColumnInfo(name = "target_basis_revision_id")
    val targetBasisRevisionId: String,
    val confidence: Double,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "problem_classification_binding",
    foreignKeys = [
        ForeignKey(
            entity = ProblemRevisionEntity::class,
            parentColumns = ["problem_id", "revision_id"],
            childColumns = ["problem_id", "basis_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["problem_id", "basis_revision_id"]),
        Index(value = ["dimension", "label_id"]),
        Index(
            value = ["problem_id", "basis_revision_id", "dimension", "label_id"],
            unique = true,
        ),
    ],
)
internal data class ProblemClassificationBindingEntity(
    @PrimaryKey
    @ColumnInfo(name = "binding_id")
    val bindingId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    val dimension: String,
    @ColumnInfo(name = "label_id")
    val labelId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "acceptance_source")
    val acceptanceSource: String,
    @ColumnInfo(name = "accepted_at_epoch_millis")
    val acceptedAtEpochMillis: Long,
)

@Entity(
    tableName = "problem_organization_receipt",
    foreignKeys = [
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["problem_id", "problem_revision_id"]),
        Index(value = ["practice_unit_id", "problem_revision_id"]),
    ],
)
internal data class ProblemOrganizationReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "command_id")
    val commandId: String,
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "classification_count")
    val classificationCount: Int,
    @ColumnInfo(name = "relation_count")
    val relationCount: Int,
    @ColumnInfo(name = "accepted_at_epoch_millis")
    val acceptedAtEpochMillis: Long,
)
