package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationPlan
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.util.Collections

const val STUDENT_PROBLEM_ORGANIZATION_REQUEST_VERSION = 1

enum class StudentProblemOrganizationReviewSource {
    LOCAL_POLICY_ACCEPTED,
    USER_CORRECTED,
}

enum class StudentProblemOrganizationFacetDimension {
    PROBLEM_FAMILY,
    SOLUTION_FAMILY,
    REPRESENTATION_FORM,
}

data class StudentProblemOrganizationProvenance(
    val modelProviderId: String,
    val modelId: String,
    val requestedModelVersion: String,
    val resultModelVersion: String,
    val providerConfigurationVersion: String,
    val requestVersion: Int,
    val reviewedRequestVersion: Int,
    val modelTaskSchemaVersion: Int,
    val organizationPlanSchemaVersion: Int,
    val reviewSource: StudentProblemOrganizationReviewSource,
    val reviewVersion: String,
    val reviewIssuerKeyId: String,
    val reviewIssuerVersion: String,
    val reviewIssuedAtEpochMillis: Long,
    val reviewExpiresAtEpochMillis: Long,
) {
    init {
        modelProviderId.requireStoreText("Organization model provider id", MAX_ID_CHARS)
        modelId.requireStoreText("Organization model id", MAX_ID_CHARS)
        requestedModelVersion.requireStoreText(
            "Requested organization model version",
            MAX_VERSION_CHARS,
        )
        resultModelVersion.requireStoreText(
            "Result organization model version",
            MAX_VERSION_CHARS,
        )
        require(requestedModelVersion == resultModelVersion) {
            "Organization result model version does not match the requested model version"
        }
        providerConfigurationVersion.requireStoreText(
            "Organization provider configuration version",
            MAX_VERSION_CHARS,
        )
        require(requestVersion == STUDENT_PROBLEM_ORGANIZATION_REQUEST_VERSION) {
            "Unsupported student-problem organization request version"
        }
        require(reviewedRequestVersion == requestVersion) {
            "Reviewed organization request version does not match the submitted request"
        }
        require(
            modelTaskSchemaVersion in
                ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION..
                    ModelTaskRequest.CURRENT_SCHEMA_VERSION,
        ) {
            "Organization model-task schema does not support exact v3 evidence"
        }
        require(organizationPlanSchemaVersion == ProblemOrganizationPlan.SCHEMA_VERSION) {
            "Only the current reviewed organization plan schema may be persisted"
        }
        reviewVersion.requireStoreText("Organization review version", MAX_VERSION_CHARS)
        reviewIssuerKeyId.requireStoreText("Organization review issuer key id", MAX_ID_CHARS)
        reviewIssuerVersion.requireStoreText(
            "Organization review issuer version",
            MAX_VERSION_CHARS,
        )
        require(
            reviewIssuedAtEpochMillis >= 0 &&
                reviewExpiresAtEpochMillis > reviewIssuedAtEpochMillis,
        ) {
            "Organization review proof validity interval is invalid"
        }
    }
}

/**
 * A reviewed classification whose stable catalog identity comes only from a knowledge-authority
 * proof. Model-produced labels and node ids are deliberately absent.
 */
data class StudentProblemOrganizationClassification(
    val classificationId: String,
    val dimension: StudentProblemClassificationDimension,
    val curriculumDisplayName: String?,
    val verifiedKnowledgeReference: VerifiedKnowledgeReferenceProof,
    val resultCanonicalFingerprint: String,
    val recordedAtEpochMillis: Long,
) {
    init {
        classificationId.requireStoreText("Organization classification id", MAX_ID_CHARS)
        require(
            if (dimension == StudentProblemClassificationDimension.CURRICULUM_SECTION) {
                !curriculumDisplayName.isNullOrBlank()
            } else {
                curriculumDisplayName == null
            },
        ) {
            "Only a curriculum section may carry a reviewed display name"
        }
        curriculumDisplayName?.requireStoreText(
            "Organization curriculum display name",
            MAX_ORGANIZATION_LABEL_CHARS,
        )
        requireSha256(
            resultCanonicalFingerprint,
            "Organization classification result fingerprint",
        )
        require(recordedAtEpochMillis >= 0) {
            "Organization classification time must not be negative"
        }
    }

    val knowledgeNode: KnowledgeNodeRef
        get() = verifiedKnowledgeReference.ref
}

/**
 * A reviewed solution-step relation. [knowledgeReferenceId] is a local opaque relation identity;
 * the catalog node itself is accepted only through [verifiedKnowledgeReference].
 */
data class StudentProblemStepKnowledgeBinding(
    val bindingId: String,
    val solutionAnalysisId: String,
    val stepId: String,
    val stepOrdinal: Int,
    val knowledgeReferenceId: String,
    val verifiedKnowledgeReference: VerifiedKnowledgeReferenceProof,
    val bindingCanonicalFingerprint: String,
    val recordedAtEpochMillis: Long,
) {
    init {
        bindingId.requireStoreText("Step-knowledge binding id", MAX_ID_CHARS)
        solutionAnalysisId.requireStoreText("Step-knowledge solution id", MAX_ID_CHARS)
        stepId.requireStoreText("Step-knowledge step id", MAX_ID_CHARS)
        require(stepOrdinal > 0) { "Step-knowledge ordinal must be positive" }
        knowledgeReferenceId.requireOpaqueOrganizationIdentity(
            "Step-knowledge reference id",
        )
        requireSha256(
            bindingCanonicalFingerprint,
            "Step-knowledge binding fingerprint",
        )
        require(recordedAtEpochMillis >= 0) {
            "Step-knowledge binding time must not be negative"
        }
    }

    val knowledgeNode: KnowledgeNodeRef
        get() = verifiedKnowledgeReference.ref
}

/**
 * Stable, non-display organization axes. Human-readable internal family terms do not cross this
 * port; consumers resolve an opaque id through the owning catalog if presentation is needed.
 */
data class StudentProblemOrganizationFacet(
    val dimension: StudentProblemOrganizationFacetDimension,
    val familyId: String,
    val familyVersion: String,
    val bindingCanonicalFingerprint: String,
) {
    init {
        familyId.requireOpaqueOrganizationIdentity("Organization family id")
        familyVersion.requireOpaqueOrganizationIdentity("Organization family version")
        requireSha256(
            bindingCanonicalFingerprint,
            "Organization family binding fingerprint",
        )
    }
}

data class OrganizeStudentProblemCommand(
    val receiptId: String,
    val requestId: String,
    val requestCanonicalFingerprint: String,
    val organizationRevision: Int,
    val supersedesReceiptId: String?,
    val previousPayloadCanonicalFingerprint: String?,
    val problemRevision: StudentProblemRevisionRef,
    val errorOccurrences: List<StudentProblemErrorOccurrenceRef>,
    val provenance: StudentProblemOrganizationProvenance,
    val solutionAnalysis: StudentProblemSolutionAnalysis,
    val errorAttributions: List<StudentProblemErrorAttribution>,
    val classifications: List<StudentProblemOrganizationClassification>,
    val stepKnowledgeBindings: List<StudentProblemStepKnowledgeBinding>,
    val facets: List<StudentProblemOrganizationFacet>,
    val completedAtEpochMillis: Long,
    val verifiedReviewProof: StudentProblemOrganizationReviewAuthority.Proof? = null,
) {
    init {
        receiptId.requireStoreText("Organization receipt id", MAX_ID_CHARS)
        requestId.requireStoreText("Organization request id", MAX_ID_CHARS)
        requireSha256(
            requestCanonicalFingerprint,
            "Organization request canonical fingerprint",
        )
        require(organizationRevision > 0) {
            "Organization revision must be positive"
        }
        supersedesReceiptId?.requireStoreText(
            "Superseded organization receipt id",
            MAX_ID_CHARS,
        )
        previousPayloadCanonicalFingerprint?.let {
            requireSha256(it, "Previous organization payload fingerprint")
        }
        require(
            if (organizationRevision == 1) {
                supersedesReceiptId == null && previousPayloadCanonicalFingerprint == null
            } else {
                supersedesReceiptId != null && previousPayloadCanonicalFingerprint != null
            },
        ) {
            "Organization revision chain metadata is incomplete"
        }
        require(supersedesReceiptId != receiptId) {
            "An organization receipt cannot supersede itself"
        }
        require(errorOccurrences.size in 1..MAX_ORGANIZATION_ERROR_OCCURRENCES) {
            "Organization error-occurrence count is outside the supported range"
        }
        require(
            errorOccurrences.map(StudentProblemErrorOccurrenceRef::occurrenceId) ==
                errorOccurrences.map(StudentProblemErrorOccurrenceRef::occurrenceId).sorted(),
        ) {
            "Organization error occurrences must use stable occurrence-id order"
        }
        require(
            errorOccurrences.map(StudentProblemErrorOccurrenceRef::occurrenceId)
                .distinct()
                .size == errorOccurrences.size &&
                errorOccurrences
                    .map(StudentProblemErrorOccurrenceRef::occurrenceCanonicalFingerprint)
                    .distinct()
                    .size == errorOccurrences.size,
        ) {
            "Organization error occurrences must be unique"
        }
        require(errorOccurrences.all { it.problemRevision == problemRevision }) {
            "Every organization error occurrence must target the exact problem revision"
        }
        require(solutionAnalysis.problemRevision == problemRevision) {
            "Solution analysis must target the exact organization revision"
        }
        require(
            solutionAnalysis.modelProviderId == provenance.modelProviderId &&
                solutionAnalysis.modelId == provenance.modelId,
        ) {
            "Solution analysis model identity does not match organization provenance"
        }
        require(errorAttributions.size <= MAX_ORGANIZATION_ERROR_ATTRIBUTIONS) {
            "Organization has too many error attributions"
        }
        require(errorAttributions.all { it.problemRevision == problemRevision }) {
            "Every error attribution must target the exact organization revision"
        }
        require(
            errorAttributions.all {
                it.modelProviderId == provenance.modelProviderId &&
                    it.modelId == provenance.modelId
            },
        ) {
            "Error-attribution model identity does not match organization provenance"
        }
        require(
            errorAttributions.map(StudentProblemErrorAttribution::attributionId) ==
                errorAttributions.map(StudentProblemErrorAttribution::attributionId).sorted(),
        ) {
            "Error attributions must use stable id order"
        }
        require(
            errorAttributions.map(StudentProblemErrorAttribution::attributionId).distinct().size ==
                errorAttributions.size,
        ) {
            "Error-attribution ids must be unique"
        }
        require(classifications.size in 2..MAX_ORGANIZATION_CLASSIFICATIONS) {
            "Organization classification count is outside the supported range"
        }
        require(
            classifications.map(StudentProblemOrganizationClassification::classificationId) ==
                classifications
                    .map(StudentProblemOrganizationClassification::classificationId)
                    .sorted(),
        ) {
            "Organization classifications must use stable id order"
        }
        require(
            classifications
                .map(StudentProblemOrganizationClassification::classificationId)
                .distinct()
                .size == classifications.size,
        ) {
            "Organization classification ids must be unique"
        }
        require(
            classifications
                .map {
                    it.verifiedKnowledgeReference.toPersistedProofIdentity()
                }.distinct()
                .size == classifications.size,
        ) {
            "Organization classifications cannot repeat one formally verified reference"
        }
        require(
            classifications.count {
                it.dimension == StudentProblemClassificationDimension.CURRICULUM_SECTION
            } == 1,
        ) {
            "Organization requires exactly one formally verified curriculum section"
        }
        require(
            classifications.any {
                it.dimension == StudentProblemClassificationDimension.KNOWLEDGE
            },
        ) {
            "Organization requires at least one formally verified knowledge point"
        }
        require(
            classifications.all {
                it.knowledgeNode.subject == problemRevision.problem.subject
            },
        ) {
            "Organization classifications must stay inside the exact problem subject"
        }
        val stepsById = solutionAnalysis.steps.associateBy(StudentProblemSolutionStep::stepId)
        val stepsByOrdinal =
            solutionAnalysis.steps.associateBy(StudentProblemSolutionStep::ordinal)
        require(
            stepKnowledgeBindings.size in 1..MAX_ORGANIZATION_STEP_KNOWLEDGE_BINDINGS,
        ) {
            "Organization step-knowledge relation count is outside the supported range"
        }
        require(
            stepKnowledgeBindings.map(StudentProblemStepKnowledgeBinding::bindingId) ==
                stepKnowledgeBindings.map(StudentProblemStepKnowledgeBinding::bindingId).sorted(),
        ) {
            "Step-knowledge bindings must use stable id order"
        }
        require(
            stepKnowledgeBindings.map(StudentProblemStepKnowledgeBinding::bindingId)
                .distinct()
                .size == stepKnowledgeBindings.size,
        ) {
            "Step-knowledge binding ids must be unique"
        }
        require(
            stepKnowledgeBindings
                .map { it.stepOrdinal to it.knowledgeNode.canonicalFingerprint }
                .distinct()
                .size == stepKnowledgeBindings.size,
        ) {
            "A solution step cannot repeat one formally verified knowledge node"
        }
        require(
            stepKnowledgeBindings
                .groupBy(StudentProblemStepKnowledgeBinding::knowledgeReferenceId)
                .values
                .all { bindings ->
                    bindings
                        .map {
                            it.verifiedKnowledgeReference.toPersistedProofIdentity()
                        }.distinct()
                        .size == 1
                },
        ) {
            "One local knowledge-reference id cannot map to multiple formal knowledge nodes"
        }
        stepKnowledgeBindings.forEach { binding ->
            require(binding.solutionAnalysisId == solutionAnalysis.solutionAnalysisId) {
                "Step-knowledge binding targets another solution analysis"
            }
            require(
                stepsById[binding.stepId]?.ordinal == binding.stepOrdinal &&
                    stepsByOrdinal[binding.stepOrdinal]?.stepId == binding.stepId,
            ) {
                "Step-knowledge binding does not match an exact reviewed solution step"
            }
            require(binding.knowledgeNode.subject == problemRevision.problem.subject) {
                "Step-knowledge binding must stay inside the exact problem subject"
            }
        }
        val acceptedKnowledgeProofs =
            classifications
                .filter {
                    it.dimension == StudentProblemClassificationDimension.KNOWLEDGE
                }.map { it.verifiedKnowledgeReference.toPersistedProofIdentity() }
                .toSet()
        require(
            stepKnowledgeBindings.all {
                it.verifiedKnowledgeReference.toPersistedProofIdentity() in
                    acceptedKnowledgeProofs
            },
        ) {
            "Every solution-step knowledge relation must use an accepted knowledge classification"
        }
        require(
            stepKnowledgeBindings
                .mapTo(hashSetOf()) {
                    it.verifiedKnowledgeReference.toPersistedProofIdentity()
                }.containsAll(acceptedKnowledgeProofs),
        ) {
            "Every accepted knowledge point must be observable in a reviewed solution step"
        }
        val proofSnapshots =
            (
                classifications.map {
                    it.verifiedKnowledgeReference.toCatalogSnapshotIdentity()
                } +
                    stepKnowledgeBindings.map {
                        it.verifiedKnowledgeReference.toCatalogSnapshotIdentity()
                    }
            ).toSet()
        require(proofSnapshots.size == 1) {
            "One organization revision must use exactly one formal knowledge catalog snapshot"
        }
        val referencesByStep =
            stepKnowledgeBindings.groupBy(StudentProblemStepKnowledgeBinding::stepOrdinal)
                .mapValues { (_, bindings) ->
                    bindings.mapTo(hashSetOf()) {
                        it.knowledgeReferenceId
                    }
                }
        errorAttributions.forEach { attribution ->
            if (attribution.resolutionStatus == ProblemErrorAttributionResolutionStatus.RESOLVED) {
                require(attribution.solutionAnalysisId == solutionAnalysis.solutionAnalysisId) {
                    "Resolved error attribution must target the reviewed solution analysis"
                }
                require(
                    attribution.atomicReferenceId in
                        referencesByStep[checkNotNull(attribution.stepOrdinal)].orEmpty(),
                ) {
                    "Resolved error attribution must use verified knowledge from its exact step"
                }
            } else {
                require(attribution.solutionAnalysisId == null) {
                    "Unresolved error attribution cannot target a solution analysis"
                }
            }
        }
        if (facets.isNotEmpty()) {
            val dimensions = facets.map(StudentProblemOrganizationFacet::dimension)
            val reviewedProblemFamilyOnly =
                dimensions == listOf(StudentProblemOrganizationFacetDimension.PROBLEM_FAMILY)
            val completeLegacySet =
                dimensions == dimensions.sortedBy { it.name } &&
                    facets.size == StudentProblemOrganizationFacetDimension.entries.size &&
                    dimensions.distinct().size == facets.size &&
                    dimensions.toSet() ==
                    StudentProblemOrganizationFacetDimension.entries.toSet()
            require(
                reviewedProblemFamilyOnly || completeLegacySet,
            ) {
                "Organization facets must be empty, one reviewed problem family, or one complete legacy facet set"
            }
        }
        require(
            completedAtEpochMillis >= 0 &&
                completedAtEpochMillis in
                provenance.reviewIssuedAtEpochMillis..provenance.reviewExpiresAtEpochMillis &&
                solutionAnalysis.recordedAtEpochMillis <= completedAtEpochMillis &&
                errorAttributions.all { it.recordedAtEpochMillis <= completedAtEpochMillis } &&
                classifications.all { it.recordedAtEpochMillis <= completedAtEpochMillis } &&
                stepKnowledgeBindings.all { it.recordedAtEpochMillis <= completedAtEpochMillis },
        ) {
            "Organization completion time precedes reviewed content"
        }
    }

    val payloadCanonicalFingerprint: String
        get() = canonicalOrganizationPayloadFingerprint()

    fun withVerifiedReview(
        proof: StudentProblemOrganizationReviewAuthority.Proof,
    ): OrganizeStudentProblemCommand = copy(verifiedReviewProof = proof)
}

data class StudentProblemOrganizationReceipt(
    val receiptId: String,
    val requestId: String,
    val requestCanonicalFingerprint: String,
    val requestVersion: Int,
    val organizationRevision: Int,
    val supersedesReceiptId: String?,
    val previousPayloadCanonicalFingerprint: String?,
    val problemRevision: StudentProblemRevisionRef,
    val modelProviderId: String,
    val modelId: String,
    val modelVersion: String,
    val providerConfigurationVersion: String,
    val modelTaskSchemaVersion: Int,
    val organizationPlanSchemaVersion: Int,
    val reviewSource: StudentProblemOrganizationReviewSource,
    val reviewVersion: String,
    val reviewIssuerKeyId: String,
    val reviewIssuerVersion: String,
    val reviewIssuedAtEpochMillis: Long,
    val reviewExpiresAtEpochMillis: Long,
    val payloadCanonicalFingerprint: String,
    val errorOccurrenceCount: Int,
    val classificationCount: Int,
    val stepKnowledgeBindingCount: Int,
    val errorAttributionCount: Int,
    val facetCount: Int,
    val completedAtEpochMillis: Long,
)

data class OrganizeStudentProblemResult(
    val created: Boolean,
    val receipt: StudentProblemOrganizationReceipt,
)

data class StudentProblemOrganizationKnowledgeSnapshot(
    val manifestFingerprint: String,
    val activationGeneration: Long,
) {
    init {
        requireSha256(manifestFingerprint, "Organization knowledge manifest fingerprint")
        require(activationGeneration > 0) {
            "Organization knowledge activation generation must be positive"
        }
    }
}

/**
 * Persisted, student-authority-owned relation between one reviewed solution step and one exact
 * catalog reference. This is evidence provenance, not a mastery mutation and not a reconstructed
 * knowledge-authority proof.
 */
data class StudentProblemStepKnowledgeAttribution(
    val binding: ProblemKnowledgeBindingRef,
    val solutionAnalysisId: String,
    val stepId: String,
    val stepOrdinal: Int,
    val knowledgeReferenceId: String,
    val recordedAtEpochMillis: Long,
) {
    init {
        solutionAnalysisId.requireStoreText("Knowledge attribution solution id", MAX_ID_CHARS)
        stepId.requireStoreText("Knowledge attribution step id", MAX_ID_CHARS)
        require(stepOrdinal > 0) { "Knowledge attribution step ordinal must be positive" }
        knowledgeReferenceId.requireOpaqueOrganizationIdentity(
            "Knowledge attribution reference id",
        )
        require(recordedAtEpochMillis >= 0) {
            "Knowledge attribution time must not be negative"
        }
    }
}

/**
 * A resolved error relation whose evidence and knowledge binding were accepted in the same
 * organization receipt. Rationale text is deliberately omitted from this low-cost read surface.
 */
class StudentProblemResolvedErrorKnowledgeAttribution(
    val attributionId: String,
    val solutionAnalysisId: String,
    val stepOrdinal: Int,
    val knowledgeReferenceId: String,
    val knowledgeBindingId: String,
    evidenceRefs: List<ProblemErrorEvidenceRef>,
    val resultCanonicalFingerprint: String,
    val recordedAtEpochMillis: Long,
) {
    val evidenceRefs: List<ProblemErrorEvidenceRef> =
        Collections.unmodifiableList(evidenceRefs.toList())

    init {
        attributionId.requireStoreText("Resolved error attribution id", MAX_ID_CHARS)
        solutionAnalysisId.requireStoreText(
            "Resolved error attribution solution id",
            MAX_ID_CHARS,
        )
        require(stepOrdinal > 0) { "Resolved error attribution step ordinal must be positive" }
        knowledgeReferenceId.requireOpaqueOrganizationIdentity(
            "Resolved error attribution reference id",
        )
        knowledgeBindingId.requireStoreText(
            "Resolved error attribution binding id",
            MAX_ID_CHARS,
        )
        require(this.evidenceRefs.isNotEmpty() && this.evidenceRefs.size <= 8) {
            "Resolved error attribution requires bounded exact evidence"
        }
        require(this.evidenceRefs.distinct().size == this.evidenceRefs.size) {
            "Resolved error attribution evidence must be unique"
        }
        requireSha256(
            resultCanonicalFingerprint,
            "Resolved error attribution result fingerprint",
        )
        require(recordedAtEpochMillis >= 0) {
            "Resolved error attribution time must not be negative"
        }
    }
}

/**
 * Exact persisted attribution for the current organization revision.
 *
 * The caller must still obtain fresh knowledge-authority proofs for [knowledgeSnapshot] before
 * using these relations as tutor or mastery evidence. This object contains no SQL handle, model
 * text, mastery writer, or authority-proof construction surface.
 */
class StudentProblemKnowledgeAttributionSnapshot(
    val receiptId: String,
    val requestId: String,
    val requestCanonicalFingerprint: String,
    val organizationRevision: Int,
    val problemRevision: StudentProblemRevisionRef,
    val organizationPayloadCanonicalFingerprint: String,
    val knowledgeSnapshot: StudentProblemOrganizationKnowledgeSnapshot,
    stepAttributions: List<StudentProblemStepKnowledgeAttribution>,
    resolvedErrorAttributions: List<StudentProblemResolvedErrorKnowledgeAttribution>,
    val completedAtEpochMillis: Long,
) {
    val stepAttributions: List<StudentProblemStepKnowledgeAttribution> =
        Collections.unmodifiableList(stepAttributions.toList())
    val resolvedErrorAttributions: List<StudentProblemResolvedErrorKnowledgeAttribution> =
        Collections.unmodifiableList(resolvedErrorAttributions.toList())

    init {
        receiptId.requireStoreText("Knowledge attribution receipt id", MAX_ID_CHARS)
        requestId.requireStoreText("Knowledge attribution request id", MAX_ID_CHARS)
        requireSha256(
            requestCanonicalFingerprint,
            "Knowledge attribution request fingerprint",
        )
        require(organizationRevision > 0) {
            "Knowledge attribution organization revision must be positive"
        }
        requireSha256(
            organizationPayloadCanonicalFingerprint,
            "Knowledge attribution payload fingerprint",
        )
        require(
            this.stepAttributions.isNotEmpty() &&
                this.stepAttributions.size <= MAX_ORGANIZATION_STEP_KNOWLEDGE_BINDINGS,
        ) {
            "Knowledge attribution step bindings are outside the supported range"
        }
        require(
            this.stepAttributions.map { it.binding.bindingId } ==
                this.stepAttributions.map { it.binding.bindingId }.sorted() &&
                this.stepAttributions.map { it.binding.bindingId }.distinct().size ==
                this.stepAttributions.size,
        ) {
            "Knowledge attribution step bindings must use unique stable order"
        }
        require(
            this.stepAttributions.all {
                it.binding.problemRevision == problemRevision &&
                    it.binding.knowledgeNode.subject == problemRevision.problem.subject
            },
        ) {
            "Knowledge attribution step binding crosses problem identity or subject"
        }
        require(
            this.resolvedErrorAttributions.size <= MAX_ORGANIZATION_ERROR_ATTRIBUTIONS &&
                this.resolvedErrorAttributions.map { it.attributionId } ==
                this.resolvedErrorAttributions.map { it.attributionId }.sorted() &&
                this.resolvedErrorAttributions.map { it.attributionId }.distinct().size ==
                this.resolvedErrorAttributions.size,
        ) {
            "Resolved error attributions must use unique stable order"
        }
        val bindingsById = this.stepAttributions.associateBy { it.binding.bindingId }
        require(
            this.resolvedErrorAttributions.all { error ->
                bindingsById[error.knowledgeBindingId]?.let { binding ->
                    binding.solutionAnalysisId == error.solutionAnalysisId &&
                        binding.stepOrdinal == error.stepOrdinal &&
                        binding.knowledgeReferenceId == error.knowledgeReferenceId
                } == true
            },
        ) {
            "Resolved error attribution does not match its exact reviewed step binding"
        }
        require(
            this.stepAttributions.all { it.recordedAtEpochMillis <= completedAtEpochMillis } &&
                this.resolvedErrorAttributions.all {
                    it.recordedAtEpochMillis <= completedAtEpochMillis
                } &&
                completedAtEpochMillis >= 0,
        ) {
            "Knowledge attribution completion time precedes reviewed content"
        }
    }

    val canonicalFingerprint: String =
        CanonicalSha256(KNOWLEDGE_ATTRIBUTION_SNAPSHOT_DOMAIN)
            .field("receiptId", receiptId)
            .field("requestId", requestId)
            .field("requestCanonicalFingerprint", requestCanonicalFingerprint)
            .field("organizationRevision", organizationRevision)
            .field("problemRevision", problemRevision.canonicalFingerprint)
            .field("organizationPayload", organizationPayloadCanonicalFingerprint)
            .field("knowledgeManifest", knowledgeSnapshot.manifestFingerprint)
            .field("knowledgeGeneration", knowledgeSnapshot.activationGeneration)
            .field("stepAttributionCount", this.stepAttributions.size)
            .apply {
                this@StudentProblemKnowledgeAttributionSnapshot.stepAttributions
                    .forEachIndexed { index, attribution ->
                        field("step[$index].binding", attribution.binding.canonicalFingerprint)
                        field("step[$index].solution", attribution.solutionAnalysisId)
                        field("step[$index].stepId", attribution.stepId)
                        field("step[$index].ordinal", attribution.stepOrdinal)
                        field("step[$index].reference", attribution.knowledgeReferenceId)
                        field("step[$index].recordedAt", attribution.recordedAtEpochMillis)
                    }
            }
            .field("resolvedErrorCount", this.resolvedErrorAttributions.size)
            .apply {
                this@StudentProblemKnowledgeAttributionSnapshot.resolvedErrorAttributions
                    .forEachIndexed { index, attribution ->
                        field("error[$index].id", attribution.attributionId)
                        field("error[$index].solution", attribution.solutionAnalysisId)
                        field("error[$index].step", attribution.stepOrdinal)
                        field("error[$index].reference", attribution.knowledgeReferenceId)
                        field("error[$index].binding", attribution.knowledgeBindingId)
                        field("error[$index].evidenceCount", attribution.evidenceRefs.size)
                        attribution.evidenceRefs.forEachIndexed { evidenceIndex, evidence ->
                            field(
                                "error[$index].evidence[$evidenceIndex].block",
                                evidence.blockId,
                            )
                            field(
                                "error[$index].evidence[$evidenceIndex].asset",
                                evidence.sourceAssetId,
                            )
                            field(
                                "error[$index].evidence[$evidenceIndex].kind",
                                evidence.evidenceKind.name,
                            )
                        }
                        field("error[$index].result", attribution.resultCanonicalFingerprint)
                        field("error[$index].recordedAt", attribution.recordedAtEpochMillis)
                    }
            }
            .field("completedAt", completedAtEpochMillis)
            .finish()
}

/**
 * Read-only student authority capability. A caller must supply the independently established
 * current catalog snapshot; stale or mismatched organization data fails closed.
 */
interface LearnerBoundStudentProblemKnowledgeAttributionPort {
    val learnerId: String

    /** Persisted receipt metadata only; this does not assert that the catalog is still active. */
    suspend fun readCurrentKnowledgeSnapshot(
        problemRevision: StudentProblemRevisionRef,
    ): StudentProblemOrganizationKnowledgeSnapshot?

    suspend fun readCurrentKnowledgeAttribution(
        problemRevision: StudentProblemRevisionRef,
        requiredKnowledgeSnapshot: StudentProblemOrganizationKnowledgeSnapshot,
    ): StudentProblemKnowledgeAttributionSnapshot?
}

/**
 * Owner-issued execution fence that brackets the final student-database transaction. It exposes no
 * storage surface; production revocation and transaction completion share its linearization gate.
 */
interface StudentProblemOrganizationCommitFence {
    fun requireCurrentOwner()

    /**
     * Linearizes one complete student-database commit against owner revocation. Production fences
     * keep the reservation until [operation] returns, which is after Room has committed or rolled
     * back its transaction. The default remains a strict check-before/check-after fence for narrow
     * test and compatibility implementations.
     */
    suspend fun <Result> linearizeCommit(
        operation: suspend () -> Result,
    ): Result {
        requireCurrentOwner()
        val result = operation()
        requireCurrentOwner()
        return result
    }
}

/**
 * Narrow student-authority writer. It exposes no Room/DAO/SQL surface and cannot write learner
 * mastery. Every call targets one exact learner/problem/revision and commits one append-only
 * organization revision.
 */
interface StudentProblemOrganizationPort : LearnerBoundStudentProblemKnowledgeAttributionPort {

    suspend fun organize(
        command: OrganizeStudentProblemCommand,
        commitFence: StudentProblemOrganizationCommitFence,
    ): OrganizeStudentProblemResult

    suspend fun readReceipt(
        requestId: String,
        requestCanonicalFingerprint: String,
    ): StudentProblemOrganizationReceipt?

    suspend fun readCurrent(
        problemRevision: StudentProblemRevisionRef,
    ): StudentProblemOrganizationReceipt?

    suspend fun readKnowledgeSnapshot(
        receiptId: String,
    ): StudentProblemOrganizationKnowledgeSnapshot?
}

private const val KNOWLEDGE_ATTRIBUTION_SNAPSHOT_DOMAIN =
    "student-problem-knowledge-attribution-snapshot-v1"

private data class PersistedProofIdentity(
    val refCanonicalFingerprint: String,
    val manifestFingerprint: String,
    val activationGeneration: Long,
)

private data class CatalogSnapshotIdentity(
    val taxonomyVersion: String,
    val knowledgePackVersion: String,
    val manifestFingerprint: String,
    val activationGeneration: Long,
)

private fun VerifiedKnowledgeReferenceProof.toPersistedProofIdentity() =
    PersistedProofIdentity(
        refCanonicalFingerprint = ref.canonicalFingerprint,
        manifestFingerprint = manifestFingerprint,
        activationGeneration = activationGeneration,
    )

private fun VerifiedKnowledgeReferenceProof.toCatalogSnapshotIdentity() =
    CatalogSnapshotIdentity(
        taxonomyVersion = ref.taxonomyVersion,
        knowledgePackVersion = ref.knowledgePackVersion,
        manifestFingerprint = manifestFingerprint,
        activationGeneration = activationGeneration,
    )

private fun String.requireOpaqueOrganizationIdentity(label: String) {
    requireStoreText(label, MAX_ID_CHARS)
    require(matches(OPAQUE_ORGANIZATION_ID)) {
        "$label must be a stable opaque identity without display text"
    }
}

private fun OrganizeStudentProblemCommand.canonicalOrganizationPayloadFingerprint(): String {
    val canonical =
        CanonicalSha256(ORGANIZATION_PAYLOAD_FINGERPRINT_DOMAIN)
            .field("receiptId", receiptId)
            .field("requestId", requestId)
            .field("requestCanonicalFingerprint", requestCanonicalFingerprint)
            .field("requestVersion", provenance.requestVersion)
            .field("reviewedRequestVersion", provenance.reviewedRequestVersion)
            .field("organizationRevision", organizationRevision)
            .nullableField("supersedesReceiptId", supersedesReceiptId)
            .nullableField(
                "previousPayloadCanonicalFingerprint",
                previousPayloadCanonicalFingerprint,
            )
            .field("problemRevision", problemRevision.canonicalFingerprint)
            .field("errorOccurrenceCount", errorOccurrences.size)
            .field("modelProviderId", provenance.modelProviderId)
            .field("modelId", provenance.modelId)
            .field("requestedModelVersion", provenance.requestedModelVersion)
            .field("resultModelVersion", provenance.resultModelVersion)
            .field(
                "providerConfigurationVersion",
                provenance.providerConfigurationVersion,
            )
            .field("modelTaskSchemaVersion", provenance.modelTaskSchemaVersion)
            .field(
                "organizationPlanSchemaVersion",
                provenance.organizationPlanSchemaVersion,
            )
            .field("reviewSource", provenance.reviewSource.name)
            .field("reviewVersion", provenance.reviewVersion)
            .field("reviewIssuerKeyId", provenance.reviewIssuerKeyId)
            .field("reviewIssuerVersion", provenance.reviewIssuerVersion)
            .field(
                "reviewIssuedAtEpochMillis",
                provenance.reviewIssuedAtEpochMillis,
            )
            .field(
                "reviewExpiresAtEpochMillis",
                provenance.reviewExpiresAtEpochMillis,
            )
            .field("solutionAnalysisId", solutionAnalysis.solutionAnalysisId)
            .field("solutionSummaryMarkdown", solutionAnalysis.summaryMarkdown)
            .nullableField("solutionFinalAnswerMarkdown", solutionAnalysis.finalAnswerMarkdown)
            .field(
                "solutionResultCanonicalFingerprint",
                solutionAnalysis.resultCanonicalFingerprint,
            )
            .field("solutionAnalyzerVersion", solutionAnalysis.analyzerVersion)
            .field("solutionRecordedAtEpochMillis", solutionAnalysis.recordedAtEpochMillis)
            .field("solutionStepCount", solutionAnalysis.steps.size)
    errorOccurrences.forEachIndexed { index, occurrence ->
        canonical
            .field("errorOccurrence[$index].occurrenceId", occurrence.occurrenceId)
            .field(
                "errorOccurrence[$index].problemRevision",
                occurrence.problemRevision.canonicalFingerprint,
            )
            .field(
                "errorOccurrence[$index].occurrenceCanonicalFingerprint",
                occurrence.occurrenceCanonicalFingerprint,
            )
            .field("errorOccurrence[$index].schemaVersion", occurrence.schemaVersion)
    }
    solutionAnalysis.steps.forEachIndexed { index, step ->
        canonical
            .field("solutionStep[$index].stepId", step.stepId)
            .field("solutionStep[$index].ordinal", step.ordinal)
            .field("solutionStep[$index].summaryMarkdown", step.summaryMarkdown)
            .field("solutionStep[$index].reasoningMarkdown", step.reasoningMarkdown)
            .nullableField("solutionStep[$index].resultMarkdown", step.resultMarkdown)
            .field(
                "solutionStep[$index].stepCanonicalFingerprint",
                step.stepCanonicalFingerprint,
            )
    }
    canonical.field("errorAttributionCount", errorAttributions.size)
    errorAttributions.forEachIndexed { index, attribution ->
        canonical
            .field("error[$index].attributionId", attribution.attributionId)
            .nullableField(
                "error[$index].solutionAnalysisId",
                attribution.solutionAnalysisId,
            )
            .field("error[$index].resolutionStatus", attribution.resolutionStatus.name)
            .field("error[$index].rationaleMarkdown", attribution.rationaleMarkdown)
            .nullableField("error[$index].stepOrdinal", attribution.stepOrdinal?.toString())
            .nullableField("error[$index].atomicReferenceId", attribution.atomicReferenceId)
            .field("error[$index].analyzerVersion", attribution.analyzerVersion)
            .field(
                "error[$index].resultCanonicalFingerprint",
                attribution.resultCanonicalFingerprint,
            )
            .field(
                "error[$index].recordedAtEpochMillis",
                attribution.recordedAtEpochMillis,
            )
            .field("error[$index].evidenceCount", attribution.evidenceRefs.size)
        attribution.evidenceRefs.forEachIndexed { evidenceIndex, evidence ->
            canonical
                .field(
                    "error[$index].evidence[$evidenceIndex].blockId",
                    evidence.blockId,
                )
                .field(
                    "error[$index].evidence[$evidenceIndex].sourceAssetId",
                    evidence.sourceAssetId,
                )
                .field(
                    "error[$index].evidence[$evidenceIndex].kind",
                    evidence.evidenceKind.name,
                )
        }
    }
    canonical.field("classificationCount", classifications.size)
    classifications.forEachIndexed { index, classification ->
        val proof = classification.verifiedKnowledgeReference
        canonical
            .field("classification[$index].classificationId", classification.classificationId)
            .field("classification[$index].dimension", classification.dimension.name)
            .nullableField(
                "classification[$index].curriculumDisplayName",
                classification.curriculumDisplayName,
            )
            .field(
                "classification[$index].knowledgeRef",
                proof.ref.canonicalFingerprint,
            )
            .field(
                "classification[$index].knowledgeContentFingerprint",
                proof.manifestFingerprint,
            )
            .field(
                "classification[$index].knowledgeActivationGeneration",
                proof.activationGeneration,
            )
            .field(
                "classification[$index].resultCanonicalFingerprint",
                classification.resultCanonicalFingerprint,
            )
            .field(
                "classification[$index].recordedAtEpochMillis",
                classification.recordedAtEpochMillis,
            )
    }
    canonical.field("stepKnowledgeBindingCount", stepKnowledgeBindings.size)
    stepKnowledgeBindings.forEachIndexed { index, binding ->
        val proof = binding.verifiedKnowledgeReference
        canonical
            .field("stepKnowledge[$index].bindingId", binding.bindingId)
            .field(
                "stepKnowledge[$index].solutionAnalysisId",
                binding.solutionAnalysisId,
            )
            .field("stepKnowledge[$index].stepId", binding.stepId)
            .field("stepKnowledge[$index].stepOrdinal", binding.stepOrdinal)
            .field(
                "stepKnowledge[$index].knowledgeReferenceId",
                binding.knowledgeReferenceId,
            )
            .field("stepKnowledge[$index].knowledgeRef", proof.ref.canonicalFingerprint)
            .field(
                "stepKnowledge[$index].knowledgeContentFingerprint",
                proof.manifestFingerprint,
            )
            .field(
                "stepKnowledge[$index].knowledgeActivationGeneration",
                proof.activationGeneration,
            )
            .field(
                "stepKnowledge[$index].bindingCanonicalFingerprint",
                binding.bindingCanonicalFingerprint,
            )
            .field(
                "stepKnowledge[$index].recordedAtEpochMillis",
                binding.recordedAtEpochMillis,
            )
    }
    canonical.field("facetCount", facets.size)
    facets.forEachIndexed { index, facet ->
        canonical
            .field("facet[$index].dimension", facet.dimension.name)
            .field("facet[$index].familyId", facet.familyId)
            .field("facet[$index].familyVersion", facet.familyVersion)
            .field(
                "facet[$index].bindingCanonicalFingerprint",
                facet.bindingCanonicalFingerprint,
            )
    }
    return canonical
        .field("completedAtEpochMillis", completedAtEpochMillis)
        .finish()
}

private val OPAQUE_ORGANIZATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
private const val ORGANIZATION_PAYLOAD_FINGERPRINT_DOMAIN =
    "student-problem-organization-payload-v1"
private const val MAX_ORGANIZATION_LABEL_CHARS = 512
private const val MAX_ORGANIZATION_CLASSIFICATIONS = 64
private const val MAX_ORGANIZATION_ERROR_ATTRIBUTIONS = 64
private const val MAX_ORGANIZATION_ERROR_OCCURRENCES = 128
private const val MAX_ORGANIZATION_STEP_KNOWLEDGE_BINDINGS = 128
