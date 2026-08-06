package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

internal data class StudentProblemOrganizationBundle(
    val receipt: StudentProblemOrganizationReceiptEntity,
    val errorOccurrences: List<StudentProblemOrganizationOccurrenceBindingEntity>,
    val solutionAnalysis: StudentProblemSolutionAnalysisEntity,
    val solutionSteps: List<StudentProblemSolutionStepEntity>,
    val errorAttributions: List<StudentProblemErrorAttributionEntity>,
    val errorEvidence: List<StudentProblemErrorEvidenceEntity>,
    val classifications: List<StudentProblemClassificationResultEntity>,
    val stepKnowledgeBindings: List<StudentProblemStepKnowledgeBindingEntity>,
    val facets: List<StudentProblemOrganizationFacetEntity>,
)

internal data class StudentProblemOrganizationDaoResult(
    val created: Boolean,
    val receipt: StudentProblemOrganizationReceiptEntity,
)

internal data class StudentProblemOrganizationKnowledgeSnapshotRow(
    val knowledgeManifestFingerprint: String?,
    val knowledgeActivationGeneration: Long?,
)

internal data class StudentProblemKnowledgeAttributionDaoSnapshot(
    val receipt: StudentProblemOrganizationReceiptEntity,
    val classifications: List<StudentProblemClassificationResultEntity>,
    val stepKnowledgeBindings: List<StudentProblemStepKnowledgeBindingEntity>,
    val errorAttributions: List<StudentProblemErrorAttributionEntity>,
    val errorEvidence: List<StudentProblemErrorEvidenceEntity>,
)

class StudentProblemOrganizationConflictException(
    message: String,
) : IllegalStateException(message)

@Dao
internal abstract class StudentProblemOrganizationDao {
    @Query(
        """
        SELECT receipt_id, request_id, request_canonical_fingerprint,
               request_version, reviewed_request_version, organization_revision,
               supersedes_receipt_id, previous_payload_canonical_fingerprint,
               learner_id, subject, problem_id, practice_unit_id, basis_revision_id,
               basis_revision_number, basis_document_canonical_fingerprint,
               model_provider_id, model_id, requested_model_version,
               result_model_version, provider_configuration_version,
               model_task_schema_version, organization_plan_schema_version,
               review_source, review_version, review_issuer_key_id,
               review_issuer_version, review_issued_at_epoch_millis,
               review_expires_at_epoch_millis,
               payload_canonical_fingerprint, error_occurrence_count, classification_count,
               step_knowledge_binding_count, error_attribution_count, facet_count,
               status, completed_at_epoch_millis
        FROM student_problem_organization_receipt
        WHERE receipt_id = :receiptId
        LIMIT 1
        """,
    )
    abstract suspend fun readReceiptById(
        receiptId: String,
    ): StudentProblemOrganizationReceiptEntity?

    @Query(
        """
        SELECT receipt_id, request_id, request_canonical_fingerprint,
               request_version, reviewed_request_version, organization_revision,
               supersedes_receipt_id, previous_payload_canonical_fingerprint,
               learner_id, subject, problem_id, practice_unit_id, basis_revision_id,
               basis_revision_number, basis_document_canonical_fingerprint,
               model_provider_id, model_id, requested_model_version,
               result_model_version, provider_configuration_version,
               model_task_schema_version, organization_plan_schema_version,
               review_source, review_version, review_issuer_key_id,
               review_issuer_version, review_issued_at_epoch_millis,
               review_expires_at_epoch_millis,
               payload_canonical_fingerprint, error_occurrence_count, classification_count,
               step_knowledge_binding_count, error_attribution_count, facet_count,
               status, completed_at_epoch_millis
        FROM student_problem_organization_receipt
        WHERE request_id = :requestId
        LIMIT 1
        """,
    )
    abstract suspend fun readReceiptByRequestId(
        requestId: String,
    ): StudentProblemOrganizationReceiptEntity?

    @Query(
        """
        SELECT receipt_id, request_id, request_canonical_fingerprint,
               request_version, reviewed_request_version, organization_revision,
               supersedes_receipt_id, previous_payload_canonical_fingerprint,
               learner_id, subject, problem_id, practice_unit_id, basis_revision_id,
               basis_revision_number, basis_document_canonical_fingerprint,
               model_provider_id, model_id, requested_model_version,
               result_model_version, provider_configuration_version,
               model_task_schema_version, organization_plan_schema_version,
               review_source, review_version, review_issuer_key_id,
               review_issuer_version, review_issued_at_epoch_millis,
               review_expires_at_epoch_millis,
               payload_canonical_fingerprint, error_occurrence_count, classification_count,
               step_knowledge_binding_count, error_attribution_count, facet_count,
               status, completed_at_epoch_millis
        FROM student_problem_organization_receipt
        WHERE basis_revision_id = :basisRevisionId
          AND status = 'COMPLETED'
        ORDER BY organization_revision DESC
        LIMIT 1
        """,
    )
    abstract suspend fun readCurrentReceipt(
        basisRevisionId: String,
    ): StudentProblemOrganizationReceiptEntity?

    @Query(
        """
        SELECT DISTINCT
               knowledge_manifest_fingerprint AS knowledgeManifestFingerprint,
               knowledge_activation_generation AS knowledgeActivationGeneration
        FROM student_problem_classification_result
        WHERE organization_receipt_id = :receiptId
        """,
    )
    abstract suspend fun readKnowledgeSnapshots(
        receiptId: String,
    ): List<StudentProblemOrganizationKnowledgeSnapshotRow>

    @Query(
        """
        SELECT classification_id, problem_id, basis_revision_id, organization_receipt_id,
               dimension, label_id, knowledge_subject, knowledge_node_id,
               knowledge_taxonomy_version, knowledge_pack_version,
               knowledge_manifest_fingerprint, knowledge_activation_generation,
               model_provider_id, model_id, classifier_version,
               result_canonical_fingerprint, status, supersedes_classification_id,
               recorded_at_epoch_millis
        FROM student_problem_classification_result
        WHERE organization_receipt_id = :receiptId
        ORDER BY classification_id ASC
        """,
    )
    protected abstract suspend fun readOrganizationClassifications(
        receiptId: String,
    ): List<StudentProblemClassificationResultEntity>

    @Query(
        """
        SELECT binding_id, organization_receipt_id, basis_revision_id,
               solution_analysis_id, step_id, step_ordinal, knowledge_reference_id,
               knowledge_subject, knowledge_node_id, knowledge_taxonomy_version,
               knowledge_pack_version, knowledge_content_canonical_fingerprint,
               knowledge_activation_generation, binding_canonical_fingerprint,
               recorded_at_epoch_millis
        FROM student_problem_step_knowledge_binding
        WHERE organization_receipt_id = :receiptId
        ORDER BY binding_id ASC
        """,
    )
    protected abstract suspend fun readOrganizationStepKnowledgeBindings(
        receiptId: String,
    ): List<StudentProblemStepKnowledgeBindingEntity>

    @Query(
        """
        SELECT attribution_id, basis_revision_id, organization_receipt_id,
               solution_analysis_id, resolution_status, rationale_markdown,
               step_ordinal, atomic_reference_id, model_provider_id, model_id,
               analyzer_version, result_canonical_fingerprint, recorded_at_epoch_millis
        FROM student_problem_error_attribution
        WHERE organization_receipt_id = :receiptId
        ORDER BY attribution_id ASC
        """,
    )
    protected abstract suspend fun readOrganizationErrorAttributions(
        receiptId: String,
    ): List<StudentProblemErrorAttributionEntity>

    @Query(
        """
        SELECT evidence.attribution_id, evidence.basis_revision_id, evidence.ordinal,
               evidence.block_id, evidence.source_asset_id, evidence.evidence_kind
        FROM student_problem_error_evidence AS evidence
        INNER JOIN student_problem_error_attribution AS attribution
          ON attribution.attribution_id = evidence.attribution_id
         AND attribution.basis_revision_id = evidence.basis_revision_id
        WHERE attribution.organization_receipt_id = :receiptId
        ORDER BY evidence.attribution_id ASC, evidence.ordinal ASC
        """,
    )
    protected abstract suspend fun readOrganizationErrorEvidence(
        receiptId: String,
    ): List<StudentProblemErrorEvidenceEntity>

    /** One transaction prevents a superseding organization revision from producing a mixed read. */
    @Transaction
    open suspend fun readCurrentKnowledgeAttribution(
        basisRevisionId: String,
    ): StudentProblemKnowledgeAttributionDaoSnapshot? {
        val receipt = readCurrentReceipt(basisRevisionId) ?: return null
        val result =
            StudentProblemKnowledgeAttributionDaoSnapshot(
                receipt = receipt,
                classifications = readOrganizationClassifications(receipt.receiptId),
                stepKnowledgeBindings =
                    readOrganizationStepKnowledgeBindings(receipt.receiptId),
                errorAttributions = readOrganizationErrorAttributions(receipt.receiptId),
                errorEvidence = readOrganizationErrorEvidence(receipt.receiptId),
            )
        check(readCurrentReceipt(basisRevisionId) == receipt) {
            "Current organization changed during its attribution read"
        }
        return result
    }

    @Query(
        """
        SELECT
          problem.learner_id AS learnerId,
          problem.subject AS subject,
          problem.problem_id AS problemId,
          problem.primary_practice_unit_id AS practiceUnitId,
          problem.lifecycle_state AS lifecycleState,
          collection.mistake_state AS mistakeState,
          revision.revision_id AS revisionId,
          revision.revision_number AS revisionNumber,
          revision.document_canonical_fingerprint AS documentCanonicalFingerprint
        FROM student_problem_revision AS revision
        INNER JOIN student_problem_document AS problem
          ON problem.problem_id = revision.problem_id
        LEFT JOIN student_problem_collection AS collection
          ON collection.practice_unit_id = problem.primary_practice_unit_id
        WHERE revision.revision_id = :revisionId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readExactRevision(
        revisionId: String,
    ): PersistedStudentProblemRevisionRefRow?

    @Query(
        """
        SELECT revision_id, problem_id, revision_number, title, stem_markdown,
               captured_question_document_wire, document_canonical_fingerprint,
               created_at_epoch_millis, updated_at_epoch_millis
        FROM student_problem_revision
        WHERE revision_id = :revisionId
        LIMIT 1
        """,
    )
    abstract suspend fun readRevision(
        revisionId: String,
    ): StudentProblemRevisionEntity?

    @Query(
        """
        SELECT occurrence_id, idempotency_key, schema_version, learner_id, subject,
               problem_id, practice_unit_id, basis_revision_id, basis_revision_number,
               basis_document_canonical_fingerprint, batch_canonical_fingerprint,
               import_source_canonical_fingerprint, occurred_at_epoch_millis,
               imported_at_epoch_millis, attribution_status, evidence_count,
               occurrence_canonical_fingerprint
        FROM student_problem_error_occurrence
        WHERE occurrence_id IN (:occurrenceIds)
        """,
    )
    protected abstract suspend fun readErrorOccurrences(
        occurrenceIds: List<String>,
    ): List<StudentProblemErrorOccurrenceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReceipt(
        receipt: StudentProblemOrganizationReceiptEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSolutionAnalysis(
        analysis: StudentProblemSolutionAnalysisEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSolutionSteps(
        steps: List<StudentProblemSolutionStepEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertErrorAttributions(
        attributions: List<StudentProblemErrorAttributionEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertErrorEvidence(
        evidence: List<StudentProblemErrorEvidenceEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertClassifications(
        classifications: List<StudentProblemClassificationResultEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertStepKnowledgeBindings(
        bindings: List<StudentProblemStepKnowledgeBindingEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertFacets(
        facets: List<StudentProblemOrganizationFacetEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertErrorOccurrenceBindings(
        bindings: List<StudentProblemOrganizationOccurrenceBindingEntity>,
    )

    @Query(
        """
        SELECT COUNT(*)
        FROM student_problem_organization_occurrence_binding
        WHERE organization_receipt_id = :receiptId
        """,
    )
    protected abstract suspend fun countErrorOccurrenceBindings(receiptId: String): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM student_problem_classification_result
        WHERE organization_receipt_id = :receiptId
        """,
    )
    protected abstract suspend fun countClassifications(receiptId: String): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM student_problem_step_knowledge_binding
        WHERE organization_receipt_id = :receiptId
        """,
    )
    protected abstract suspend fun countStepKnowledgeBindings(receiptId: String): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM student_problem_error_attribution
        WHERE organization_receipt_id = :receiptId
        """,
    )
    protected abstract suspend fun countErrorAttributions(receiptId: String): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM student_problem_organization_facet
        WHERE organization_receipt_id = :receiptId
        """,
    )
    protected abstract suspend fun countFacets(receiptId: String): Int

    @Query(
        """
        INSERT INTO student_learner_change(
            learner_id,
            change_version
        )
        VALUES(:learnerId, 1)
        ON CONFLICT(learner_id) DO UPDATE SET
            change_version = student_learner_change.change_version + 1
        """,
    )
    protected abstract suspend fun bumpLearnerChangeVersion(learnerId: String)

    @Transaction
    open suspend fun organize(
        bundle: StudentProblemOrganizationBundle,
    ): StudentProblemOrganizationDaoResult {
        val expected = bundle.receipt
        readReceiptByRequestId(expected.requestId)?.let { existing ->
            existing.requireExactReplay(expected, "request id")
            return StudentProblemOrganizationDaoResult(created = false, receipt = existing)
        }
        readReceiptById(expected.receiptId)?.let { existing ->
            existing.requireExactReplay(expected, "receipt id")
            return StudentProblemOrganizationDaoResult(created = false, receipt = existing)
        }
        requireBundleOwnership(bundle)
        val target =
            readExactRevision(expected.basisRevisionId)
                ?: conflict("Organization target revision does not exist")
        if (
            target.learnerId != expected.learnerId ||
            target.subject != expected.subject ||
            target.problemId != expected.problemId ||
            target.practiceUnitId != expected.practiceUnitId ||
            target.revisionId != expected.basisRevisionId ||
            target.revisionNumber != expected.basisRevisionNumber ||
            target.documentCanonicalFingerprint !=
            expected.basisDocumentCanonicalFingerprint ||
            target.lifecycleState == StudentProblemLifecycleState.TOMBSTONED.name
        ) {
            conflict("Organization target does not match the exact persisted learner revision")
        }
        val storedOccurrences =
            readErrorOccurrences(
                bundle.errorOccurrences.map(
                    StudentProblemOrganizationOccurrenceBindingEntity::occurrenceId,
                ),
            ).associateBy(StudentProblemErrorOccurrenceEntity::occurrenceId)
        if (
            storedOccurrences.size != bundle.errorOccurrences.size ||
            bundle.errorOccurrences.any { binding ->
                val occurrence = storedOccurrences[binding.occurrenceId]
                occurrence == null ||
                    occurrence.learnerId != expected.learnerId ||
                    occurrence.subject != expected.subject ||
                    occurrence.problemId != expected.problemId ||
                    occurrence.practiceUnitId != expected.practiceUnitId ||
                    occurrence.basisRevisionId != expected.basisRevisionId ||
                    occurrence.basisRevisionNumber != expected.basisRevisionNumber ||
                    occurrence.basisDocumentCanonicalFingerprint !=
                    expected.basisDocumentCanonicalFingerprint ||
                    occurrence.occurrenceCanonicalFingerprint !=
                    binding.occurrenceCanonicalFingerprint
            }
        ) {
            conflict("Organization references a missing or mismatched error occurrence")
        }
        val current =
            readCurrentReceipt(expected.basisRevisionId)
        if (expected.organizationRevision == 1) {
            if (
                current != null ||
                expected.supersedesReceiptId != null ||
                expected.previousPayloadCanonicalFingerprint != null
            ) {
                conflict("First organization revision conflicts with existing history")
            }
        } else {
            if (
                current == null ||
                current.receiptId != expected.supersedesReceiptId ||
                current.payloadCanonicalFingerprint !=
                expected.previousPayloadCanonicalFingerprint ||
                current.organizationRevision + 1 != expected.organizationRevision
            ) {
                conflict("Organization revision does not extend the latest completed receipt")
            }
        }
        if (
            insertReceipt(expected) == -1L
        ) {
            val winner =
                readReceiptByRequestId(expected.requestId)
                    ?: readReceiptById(expected.receiptId)
                    ?: conflict("Organization receipt lost an idempotency race")
            winner.requireExactReplay(expected, "idempotency winner")
            return StudentProblemOrganizationDaoResult(created = false, receipt = winner)
        }
        insertErrorOccurrenceBindings(bundle.errorOccurrences)
        insertSolutionAnalysis(bundle.solutionAnalysis)
        if (bundle.solutionSteps.isNotEmpty()) {
            insertSolutionSteps(bundle.solutionSteps)
        }
        if (bundle.errorAttributions.isNotEmpty()) {
            insertErrorAttributions(bundle.errorAttributions)
        }
        if (bundle.errorEvidence.isNotEmpty()) {
            insertErrorEvidence(bundle.errorEvidence)
        }
        insertClassifications(bundle.classifications)
        insertStepKnowledgeBindings(bundle.stepKnowledgeBindings)
        insertFacets(bundle.facets)
        val occurrenceBindingCount =
            countErrorOccurrenceBindings(expected.receiptId)
        val classificationCount =
            countClassifications(expected.receiptId)
        val stepKnowledgeBindingCount =
            countStepKnowledgeBindings(expected.receiptId)
        val errorAttributionCount =
            countErrorAttributions(expected.receiptId)
        val facetCount =
            countFacets(expected.receiptId)
        if (
            occurrenceBindingCount != expected.errorOccurrenceCount ||
            classificationCount != expected.classificationCount ||
            stepKnowledgeBindingCount != expected.stepKnowledgeBindingCount ||
            errorAttributionCount != expected.errorAttributionCount ||
            facetCount != expected.facetCount
        ) {
            conflict("Atomic organization write produced an incomplete receipt")
        }
        bumpLearnerChangeVersion(expected.learnerId)
        return StudentProblemOrganizationDaoResult(created = true, receipt = expected)
    }

    private fun requireBundleOwnership(bundle: StudentProblemOrganizationBundle) {
        val receipt = bundle.receipt
        if (
            receipt.status != STUDENT_PROBLEM_ORGANIZATION_COMPLETED_STATUS ||
            receipt.requestVersion != STUDENT_PROBLEM_ORGANIZATION_REQUEST_VERSION ||
            receipt.reviewedRequestVersion != receipt.requestVersion ||
            receipt.requestedModelVersion != receipt.resultModelVersion ||
            receipt.errorOccurrenceCount != bundle.errorOccurrences.size ||
            receipt.classificationCount != bundle.classifications.size ||
            receipt.stepKnowledgeBindingCount != bundle.stepKnowledgeBindings.size ||
            receipt.errorAttributionCount != bundle.errorAttributions.size ||
            receipt.facetCount != bundle.facets.size ||
            bundle.errorOccurrences.any {
                it.organizationReceiptId != receipt.receiptId
            } ||
            bundle.solutionAnalysis.organizationReceiptId != receipt.receiptId ||
            bundle.solutionAnalysis.basisRevisionId != receipt.basisRevisionId ||
            bundle.solutionSteps.any {
                it.solutionAnalysisId != bundle.solutionAnalysis.solutionAnalysisId ||
                    it.basisRevisionId != receipt.basisRevisionId
            } ||
            bundle.errorAttributions.any {
                it.organizationReceiptId != receipt.receiptId ||
                    it.basisRevisionId != receipt.basisRevisionId
            } ||
            bundle.errorEvidence.any { it.basisRevisionId != receipt.basisRevisionId } ||
            bundle.classifications.any {
                it.organizationReceiptId != receipt.receiptId ||
                    it.basisRevisionId != receipt.basisRevisionId ||
                    it.problemId != receipt.problemId
            } ||
            bundle.stepKnowledgeBindings.any {
                it.organizationReceiptId != receipt.receiptId ||
                    it.basisRevisionId != receipt.basisRevisionId ||
                    it.solutionAnalysisId != bundle.solutionAnalysis.solutionAnalysisId
            } ||
            bundle.facets.any { it.organizationReceiptId != receipt.receiptId }
        ) {
            conflict("Organization bundle crosses a receipt or exact-revision boundary")
        }
    }
}

private fun StudentProblemOrganizationReceiptEntity.requireExactReplay(
    expected: StudentProblemOrganizationReceiptEntity,
    keyKind: String,
) {
    if (this != expected) {
        conflict("Organization $keyKind was replayed with different immutable content")
    }
}

private fun conflict(message: String): Nothing =
    throw StudentProblemOrganizationConflictException(message)
