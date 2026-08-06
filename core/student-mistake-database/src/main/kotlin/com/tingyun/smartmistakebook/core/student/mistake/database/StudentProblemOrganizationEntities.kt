package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "student_problem_organization_receipt",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentProblemOrganizationReceiptEntity::class,
            parentColumns = ["receipt_id"],
            childColumns = ["supersedes_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["request_id"], unique = true),
        Index(value = ["basis_revision_id", "organization_revision"], unique = true),
        Index(value = ["basis_revision_id", "status", "organization_revision"]),
        Index(value = ["supersedes_receipt_id"], unique = true),
        Index(value = ["payload_canonical_fingerprint"]),
    ],
)
internal data class StudentProblemOrganizationReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "receipt_id")
    val receiptId: String,
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "request_canonical_fingerprint")
    val requestCanonicalFingerprint: String,
    @ColumnInfo(name = "request_version")
    val requestVersion: Int,
    @ColumnInfo(name = "reviewed_request_version")
    val reviewedRequestVersion: Int,
    @ColumnInfo(name = "organization_revision")
    val organizationRevision: Int,
    @ColumnInfo(name = "supersedes_receipt_id")
    val supersedesReceiptId: String?,
    @ColumnInfo(name = "previous_payload_canonical_fingerprint")
    val previousPayloadCanonicalFingerprint: String?,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "basis_revision_number")
    val basisRevisionNumber: Int,
    @ColumnInfo(name = "basis_document_canonical_fingerprint")
    val basisDocumentCanonicalFingerprint: String,
    @ColumnInfo(name = "model_provider_id")
    val modelProviderId: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "requested_model_version")
    val requestedModelVersion: String,
    @ColumnInfo(name = "result_model_version")
    val resultModelVersion: String,
    @ColumnInfo(name = "provider_configuration_version")
    val providerConfigurationVersion: String,
    @ColumnInfo(name = "model_task_schema_version")
    val modelTaskSchemaVersion: Int,
    @ColumnInfo(name = "organization_plan_schema_version")
    val organizationPlanSchemaVersion: Int,
    @ColumnInfo(name = "review_source")
    val reviewSource: String,
    @ColumnInfo(name = "review_version")
    val reviewVersion: String,
    @ColumnInfo(name = "review_issuer_key_id")
    val reviewIssuerKeyId: String,
    @ColumnInfo(name = "review_issuer_version")
    val reviewIssuerVersion: String,
    @ColumnInfo(name = "review_issued_at_epoch_millis")
    val reviewIssuedAtEpochMillis: Long,
    @ColumnInfo(name = "review_expires_at_epoch_millis")
    val reviewExpiresAtEpochMillis: Long,
    @ColumnInfo(name = "payload_canonical_fingerprint")
    val payloadCanonicalFingerprint: String,
    @ColumnInfo(name = "error_occurrence_count")
    val errorOccurrenceCount: Int,
    @ColumnInfo(name = "classification_count")
    val classificationCount: Int,
    @ColumnInfo(name = "step_knowledge_binding_count")
    val stepKnowledgeBindingCount: Int,
    @ColumnInfo(name = "error_attribution_count")
    val errorAttributionCount: Int,
    @ColumnInfo(name = "facet_count")
    val facetCount: Int,
    val status: String,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_problem_step_knowledge_binding",
    primaryKeys = ["binding_id"],
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemOrganizationReceiptEntity::class,
            parentColumns = ["receipt_id"],
            childColumns = ["organization_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentProblemSolutionStepEntity::class,
            parentColumns = ["solution_analysis_id", "ordinal"],
            childColumns = ["solution_analysis_id", "step_ordinal"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["organization_receipt_id", "binding_id"], unique = true),
        Index(value = ["solution_analysis_id", "step_ordinal"]),
        Index(
            value = [
                "organization_receipt_id",
                "solution_analysis_id",
                "step_ordinal",
                "knowledge_reference_id",
            ],
            unique = true,
        ),
        Index(
            value = [
                "knowledge_subject",
                "knowledge_node_id",
                "knowledge_taxonomy_version",
                "knowledge_pack_version",
            ],
        ),
        Index(value = ["binding_canonical_fingerprint"]),
    ],
)
internal data class StudentProblemStepKnowledgeBindingEntity(
    @ColumnInfo(name = "binding_id")
    val bindingId: String,
    @ColumnInfo(name = "organization_receipt_id")
    val organizationReceiptId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "solution_analysis_id")
    val solutionAnalysisId: String,
    @ColumnInfo(name = "step_id")
    val stepId: String,
    @ColumnInfo(name = "step_ordinal")
    val stepOrdinal: Int,
    @ColumnInfo(name = "knowledge_reference_id")
    val knowledgeReferenceId: String,
    @ColumnInfo(name = "knowledge_subject")
    val knowledgeSubject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "knowledge_taxonomy_version")
    val knowledgeTaxonomyVersion: String,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String,
    @ColumnInfo(name = "knowledge_content_canonical_fingerprint")
    val knowledgeContentCanonicalFingerprint: String,
    @ColumnInfo(name = "knowledge_activation_generation")
    val knowledgeActivationGeneration: Long,
    @ColumnInfo(name = "binding_canonical_fingerprint")
    val bindingCanonicalFingerprint: String,
    @ColumnInfo(name = "recorded_at_epoch_millis")
    val recordedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_problem_organization_facet",
    primaryKeys = ["organization_receipt_id", "dimension"],
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemOrganizationReceiptEntity::class,
            parentColumns = ["receipt_id"],
            childColumns = ["organization_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["dimension", "family_id", "family_version"]),
        Index(value = ["binding_canonical_fingerprint"]),
    ],
)
internal data class StudentProblemOrganizationFacetEntity(
    @ColumnInfo(name = "organization_receipt_id")
    val organizationReceiptId: String,
    val dimension: String,
    @ColumnInfo(name = "family_id")
    val familyId: String,
    @ColumnInfo(name = "family_version")
    val familyVersion: String,
    @ColumnInfo(name = "binding_canonical_fingerprint")
    val bindingCanonicalFingerprint: String,
)

@Entity(
    tableName = "student_problem_organization_occurrence_binding",
    primaryKeys = ["organization_receipt_id", "occurrence_id"],
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemOrganizationReceiptEntity::class,
            parentColumns = ["receipt_id"],
            childColumns = ["organization_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentProblemErrorOccurrenceEntity::class,
            parentColumns = ["occurrence_id"],
            childColumns = ["occurrence_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["occurrence_id"]),
        Index(
            value = [
                "organization_receipt_id",
                "occurrence_canonical_fingerprint",
            ],
            unique = true,
        ),
    ],
)
internal data class StudentProblemOrganizationOccurrenceBindingEntity(
    @ColumnInfo(name = "organization_receipt_id")
    val organizationReceiptId: String,
    @ColumnInfo(name = "occurrence_id")
    val occurrenceId: String,
    @ColumnInfo(name = "occurrence_canonical_fingerprint")
    val occurrenceCanonicalFingerprint: String,
)

internal const val STUDENT_PROBLEM_ORGANIZATION_COMPLETED_STATUS = "COMPLETED"
