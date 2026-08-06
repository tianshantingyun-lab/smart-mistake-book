package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceKind
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

internal class RoomStudentProblemOrganizationPort(
    private val dao: StudentProblemOrganizationDao,
    override val learnerId: String,
    private val knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
    private val reviewProofVerifier: StudentProblemOrganizationReviewAuthority.Verifier,
) : StudentProblemOrganizationPort {
    init {
        learnerId.requireStoreText("Organization owner learner id", MAX_ID_CHARS)
    }

    override suspend fun organize(
        command: OrganizeStudentProblemCommand,
        commitFence: StudentProblemOrganizationCommitFence,
    ): OrganizeStudentProblemResult {
        require(command.problemRevision.problem.learnerId == learnerId) {
            "Organization command crosses the learner-bound capability"
        }
        command.problemRevision.problem.subject.requireHighSchoolSubject()
        val proofs =
            command.classifications.map {
                it.verifiedKnowledgeReference
            } +
                command.stepKnowledgeBindings.map {
                    it.verifiedKnowledgeReference
                }
        check(proofs.all(knowledgeReferenceVerifier::verifies)) {
            "Organization knowledge proof was not issued by this runtime's knowledge authority"
        }
        requireOwnerIssuedReviewProof(command)
        requireExactCapturedEvidence(command)
        val result =
            commitFence.linearizeCommit {
                dao.organize(command.toBundle())
            }
        return OrganizeStudentProblemResult(
            created = result.created,
            receipt = result.receipt.toDomainReceipt(),
        )
    }

    override suspend fun readReceipt(
        requestId: String,
        requestCanonicalFingerprint: String,
    ): StudentProblemOrganizationReceipt? {
        requestId.requireStoreText("Organization request id", MAX_ID_CHARS)
        requireSha256(
            requestCanonicalFingerprint,
            "Organization request fingerprint",
        )
        val receipt = dao.readReceiptByRequestId(requestId) ?: return null
        check(receipt.learnerId == learnerId) {
            "Organization receipt belongs to another learner"
        }
        if (receipt.requestCanonicalFingerprint != requestCanonicalFingerprint) {
            throw StudentProblemOrganizationConflictException(
                "Organization request id is already bound to another payload",
            )
        }
        return receipt.toDomainReceipt()
    }

    private fun requireOwnerIssuedReviewProof(
        command: OrganizeStudentProblemCommand,
    ) {
        val proof =
            checkNotNull(command.verifiedReviewProof) {
                "Organization acceptance requires an owner-issued review proof"
            }
        check(reviewProofVerifier.verifies(proof)) {
            "Organization review proof was not issued by this runtime owner"
        }
        val revision = command.problemRevision
        val provenance = command.provenance
        val knowledgeSnapshots =
            (
                command.classifications.map {
                    it.verifiedKnowledgeReference
                } +
                    command.stepKnowledgeBindings.map {
                        it.verifiedKnowledgeReference
                    }
            ).map {
                it.manifestFingerprint to it.activationGeneration
            }.distinct()
        check(knowledgeSnapshots.size == 1) {
            "Organization proof must bind one knowledge catalog generation"
        }
        val knowledgeSnapshot = knowledgeSnapshots.single()
        check(
            proof.learnerId == learnerId &&
                proof.problemId == revision.problem.problemId &&
                proof.basisRevisionId == revision.revisionId &&
                proof.basisRevisionNumber == revision.revisionNumber &&
                proof.basisDocumentCanonicalFingerprint ==
                revision.documentCanonicalFingerprint &&
                proof.requestId == command.requestId &&
                proof.requestCanonicalFingerprint ==
                command.requestCanonicalFingerprint &&
                proof.requestVersion == provenance.requestVersion &&
                proof.modelProviderId == provenance.modelProviderId &&
                proof.modelId == provenance.modelId &&
                proof.modelVersion == provenance.resultModelVersion &&
                proof.providerConfigurationVersion ==
                provenance.providerConfigurationVersion &&
                proof.modelTaskSchemaVersion == provenance.modelTaskSchemaVersion &&
                proof.organizationPlanSchemaVersion ==
                provenance.organizationPlanSchemaVersion &&
                proof.mappingPolicyVersion ==
                STUDENT_PROBLEM_ORGANIZATION_MAPPING_POLICY_VERSION &&
                proof.knowledgeManifestFingerprint == knowledgeSnapshot.first &&
                proof.knowledgeActivationGeneration == knowledgeSnapshot.second &&
                proof.finalCommandCanonicalFingerprint ==
                command.payloadCanonicalFingerprint &&
                proof.reviewSource == provenance.reviewSource.name &&
                proof.reviewVersion == provenance.reviewVersion &&
                proof.issuerKeyId == provenance.reviewIssuerKeyId &&
                proof.issuerVersion == provenance.reviewIssuerVersion &&
                proof.issuedAtEpochMillis == provenance.reviewIssuedAtEpochMillis &&
                proof.expiresAtEpochMillis == provenance.reviewExpiresAtEpochMillis &&
                command.completedAtEpochMillis in
                proof.issuedAtEpochMillis..proof.expiresAtEpochMillis,
        ) {
            "Owner-issued review proof does not match the exact organization payload"
        }
    }

    override suspend fun readCurrent(
        problemRevision: StudentProblemRevisionRef,
    ): StudentProblemOrganizationReceipt? {
        require(problemRevision.problem.learnerId == learnerId) {
            "Organization read crosses the learner-bound capability"
        }
        val receipt = dao.readCurrentReceipt(problemRevision.revisionId) ?: return null
        check(
            receipt.learnerId == learnerId &&
                receipt.subject == problemRevision.problem.subject.name &&
                receipt.problemId == problemRevision.problem.problemId &&
                receipt.practiceUnitId == problemRevision.problem.practiceUnitId &&
                receipt.basisRevisionId == problemRevision.revisionId &&
                receipt.basisRevisionNumber == problemRevision.revisionNumber &&
                receipt.basisDocumentCanonicalFingerprint ==
                problemRevision.documentCanonicalFingerprint,
        ) {
            "Current organization receipt does not match the exact requested revision"
        }
        return receipt.toDomainReceipt()
    }

    override suspend fun readKnowledgeSnapshot(
        receiptId: String,
    ): StudentProblemOrganizationKnowledgeSnapshot? {
        receiptId.requireStoreText("Organization receipt id", MAX_ID_CHARS)
        val receipt = dao.readReceiptById(receiptId) ?: return null
        check(receipt.learnerId == learnerId) {
            "Organization knowledge snapshot belongs to another learner"
        }
        val snapshots = dao.readKnowledgeSnapshots(receiptId)
        check(snapshots.size == 1) {
            "Organization receipt does not bind exactly one knowledge snapshot"
        }
        val snapshot = snapshots.single()
        return StudentProblemOrganizationKnowledgeSnapshot(
            manifestFingerprint = checkNotNull(snapshot.knowledgeManifestFingerprint),
            activationGeneration = checkNotNull(snapshot.knowledgeActivationGeneration),
        )
    }

    override suspend fun readCurrentKnowledgeSnapshot(
        problemRevision: StudentProblemRevisionRef,
    ): StudentProblemOrganizationKnowledgeSnapshot? {
        val receipt = readCurrent(problemRevision) ?: return null
        val snapshot = readKnowledgeSnapshot(receipt.receiptId) ?: return null
        val currentAfterRead = readCurrent(problemRevision) ?: return null
        return snapshot.takeIf {
            currentAfterRead.receiptId == receipt.receiptId &&
                currentAfterRead.payloadCanonicalFingerprint ==
                receipt.payloadCanonicalFingerprint
        }
    }

    override suspend fun readCurrentKnowledgeAttribution(
        problemRevision: StudentProblemRevisionRef,
        requiredKnowledgeSnapshot: StudentProblemOrganizationKnowledgeSnapshot,
    ): StudentProblemKnowledgeAttributionSnapshot? {
        require(problemRevision.problem.learnerId == learnerId) {
            "Knowledge attribution read crosses the learner-bound capability"
        }
        val stored =
            dao.readCurrentKnowledgeAttribution(problemRevision.revisionId)
                ?: return null
        val receipt = stored.receipt
        checkReceiptMatchesRevision(receipt, problemRevision)
        check(
            stored.classifications.size == receipt.classificationCount &&
                stored.stepKnowledgeBindings.size == receipt.stepKnowledgeBindingCount &&
                stored.errorAttributions.size == receipt.errorAttributionCount,
        ) {
            "Knowledge attribution rows do not match their organization receipt"
        }
        val classificationSnapshots =
            stored.classifications.map { classification ->
                StudentProblemOrganizationKnowledgeSnapshot(
                    manifestFingerprint =
                        checkNotNull(classification.knowledgeManifestFingerprint) {
                            "Organization classification lacks a knowledge manifest"
                        },
                    activationGeneration =
                        checkNotNull(classification.knowledgeActivationGeneration) {
                            "Organization classification lacks a knowledge generation"
                        },
                )
            }.distinct()
        check(
            classificationSnapshots.size == 1 &&
                classificationSnapshots.single() == requiredKnowledgeSnapshot,
        ) {
            "Organization attribution is not from the required current knowledge generation"
        }
        val classificationDimensions =
            stored.classifications.map { classification ->
                check(
                    classification.organizationReceiptId == receipt.receiptId &&
                        classification.problemId == problemRevision.problem.problemId &&
                        classification.basisRevisionId == problemRevision.revisionId &&
                        classification.status == StudentProblemClassificationStatus.ACCEPTED.name &&
                        classification.supersedesClassificationId == null &&
                        classification.modelProviderId == receipt.modelProviderId &&
                        classification.modelId == receipt.modelId &&
                        classification.recordedAtEpochMillis <= receipt.completedAtEpochMillis,
                ) {
                    "Organization classification does not match its exact receipt"
                }
                enumValueOrCorrupt<StudentProblemClassificationDimension>(
                    classification.dimension,
                    "organization classification dimension",
                ) to classification.requireKnowledgeNode(problemRevision.problem.subject)
            }
        check(
            classificationDimensions.count {
                it.first == StudentProblemClassificationDimension.CURRICULUM_SECTION
            } == 1 &&
                classificationDimensions.any {
                    it.first == StudentProblemClassificationDimension.KNOWLEDGE
                },
        ) {
            "Organization attribution lacks its reviewed classification hierarchy"
        }
        val acceptedKnowledgeNodes =
            classificationDimensions
                .filter { it.first == StudentProblemClassificationDimension.KNOWLEDGE }
                .mapTo(linkedSetOf()) { it.second }
        check(
            acceptedKnowledgeNodes.size ==
                classificationDimensions.count {
                    it.first == StudentProblemClassificationDimension.KNOWLEDGE
                },
        ) {
            "Organization attribution repeats a reviewed knowledge node"
        }

        val stepAttributions =
            stored.stepKnowledgeBindings.map { binding ->
                check(
                    binding.organizationReceiptId == receipt.receiptId &&
                        binding.basisRevisionId == problemRevision.revisionId &&
                        binding.knowledgeContentCanonicalFingerprint ==
                        requiredKnowledgeSnapshot.manifestFingerprint &&
                        binding.knowledgeActivationGeneration ==
                        requiredKnowledgeSnapshot.activationGeneration &&
                        binding.recordedAtEpochMillis <= receipt.completedAtEpochMillis,
                ) {
                    "Step knowledge attribution does not match its exact receipt and catalog"
                }
                val knowledgeNode = binding.requireKnowledgeNode(problemRevision.problem.subject)
                check(knowledgeNode in acceptedKnowledgeNodes) {
                    "Step knowledge attribution is not an accepted reviewed classification"
                }
                StudentProblemStepKnowledgeAttribution(
                    binding =
                        ProblemKnowledgeBindingRef(
                            bindingId = binding.bindingId,
                            problemRevision = problemRevision,
                            knowledgeNode = knowledgeNode,
                            bindingCanonicalFingerprint =
                                binding.bindingCanonicalFingerprint,
                        ),
                    solutionAnalysisId = binding.solutionAnalysisId,
                    stepId = binding.stepId,
                    stepOrdinal = binding.stepOrdinal,
                    knowledgeReferenceId = binding.knowledgeReferenceId,
                    recordedAtEpochMillis = binding.recordedAtEpochMillis,
                )
            }
        check(
            stepAttributions.mapTo(linkedSetOf()) { it.binding.knowledgeNode } ==
                acceptedKnowledgeNodes,
        ) {
            "Reviewed knowledge classifications are not exactly represented by solution steps"
        }

        val evidenceByAttribution =
            stored.errorEvidence.groupBy(StudentProblemErrorEvidenceEntity::attributionId)
        check(
            stored.errorEvidence.all { evidence ->
                evidence.basisRevisionId == problemRevision.revisionId
            },
        ) {
            "Error evidence crosses the exact organization revision"
        }
        val resolvedErrors =
            stored.errorAttributions.mapNotNull { attribution ->
                check(
                    attribution.organizationReceiptId == receipt.receiptId &&
                        attribution.basisRevisionId == problemRevision.revisionId &&
                        attribution.modelProviderId == receipt.modelProviderId &&
                        attribution.modelId == receipt.modelId &&
                        attribution.recordedAtEpochMillis <= receipt.completedAtEpochMillis,
                ) {
                    "Error attribution does not match its exact receipt"
                }
                val evidenceRows = evidenceByAttribution[attribution.attributionId].orEmpty()
                check(evidenceRows.map(StudentProblemErrorEvidenceEntity::ordinal) == evidenceRows.indices.toList()) {
                    "Error attribution evidence order is incomplete"
                }
                when (
                    enumValueOrCorrupt<ProblemErrorAttributionResolutionStatus>(
                        attribution.resolutionStatus,
                        "organization error resolution status",
                    )
                ) {
                    ProblemErrorAttributionResolutionStatus.UNRESOLVED -> {
                        check(
                            attribution.solutionAnalysisId == null &&
                                attribution.stepOrdinal == null &&
                                attribution.atomicReferenceId == null &&
                                evidenceRows.isEmpty(),
                        ) {
                            "Unresolved organization error carries invented attribution evidence"
                        }
                        null
                    }

                    ProblemErrorAttributionResolutionStatus.RESOLVED -> {
                        val solutionAnalysisId = checkNotNull(attribution.solutionAnalysisId)
                        val stepOrdinal = checkNotNull(attribution.stepOrdinal)
                        val knowledgeReferenceId = checkNotNull(attribution.atomicReferenceId)
                        val matchingBinding =
                            stepAttributions.singleOrNull { binding ->
                                binding.solutionAnalysisId == solutionAnalysisId &&
                                    binding.stepOrdinal == stepOrdinal &&
                                    binding.knowledgeReferenceId == knowledgeReferenceId
                            } ?: error(
                                "Resolved organization error lacks one exact reviewed knowledge binding",
                            )
                        StudentProblemResolvedErrorKnowledgeAttribution(
                            attributionId = attribution.attributionId,
                            solutionAnalysisId = solutionAnalysisId,
                            stepOrdinal = stepOrdinal,
                            knowledgeReferenceId = knowledgeReferenceId,
                            knowledgeBindingId = matchingBinding.binding.bindingId,
                            evidenceRefs =
                                evidenceRows.map { evidence ->
                                    ProblemErrorEvidenceRef(
                                        blockId = evidence.blockId,
                                        sourceAssetId = evidence.sourceAssetId,
                                        evidenceKind =
                                            enumValueOrCorrupt<ProblemErrorEvidenceKind>(
                                                evidence.evidenceKind,
                                                "organization error evidence kind",
                                            ),
                                    )
                                },
                            resultCanonicalFingerprint =
                                attribution.resultCanonicalFingerprint,
                            recordedAtEpochMillis = attribution.recordedAtEpochMillis,
                        )
                    }
                }
            }
        check(
            evidenceByAttribution.keys ==
                stored.errorAttributions
                    .filter {
                        it.resolutionStatus ==
                            ProblemErrorAttributionResolutionStatus.RESOLVED.name
                    }.mapTo(linkedSetOf(), StudentProblemErrorAttributionEntity::attributionId),
        ) {
            "Organization error evidence is orphaned or incomplete"
        }
        return StudentProblemKnowledgeAttributionSnapshot(
            receiptId = receipt.receiptId,
            requestId = receipt.requestId,
            requestCanonicalFingerprint = receipt.requestCanonicalFingerprint,
            organizationRevision = receipt.organizationRevision,
            problemRevision = problemRevision,
            organizationPayloadCanonicalFingerprint = receipt.payloadCanonicalFingerprint,
            knowledgeSnapshot = requiredKnowledgeSnapshot,
            stepAttributions = stepAttributions,
            resolvedErrorAttributions = resolvedErrors,
            completedAtEpochMillis = receipt.completedAtEpochMillis,
        )
    }

    private suspend fun requireExactCapturedEvidence(
        command: OrganizeStudentProblemCommand,
    ) {
        val evidence =
            command.errorAttributions.flatMap { it.evidenceRefs }
        if (evidence.isEmpty()) return
        val revision =
            dao.readRevision(command.problemRevision.revisionId)
                ?: throw StudentProblemOrganizationConflictException(
                    "Organization target revision does not exist",
                )
        check(
            revision.problemId == command.problemRevision.problem.problemId &&
                revision.revisionNumber == command.problemRevision.revisionNumber &&
                revision.documentCanonicalFingerprint ==
                command.problemRevision.documentCanonicalFingerprint,
        ) {
            "Organization evidence target does not match persisted immutable content"
        }
        val captured =
            CapturedQuestionDocumentCodec.decode(
                checkNotNull(revision.capturedQuestionDocumentWire) {
                    "Resolved organization errors require an exact captured-question snapshot"
                },
            )
        check(
            CapturedQuestionDocumentFingerprint.of(captured) ==
                command.problemRevision.documentCanonicalFingerprint,
        ) {
            "Captured-question snapshot does not match the exact organization revision"
        }
        val evidenceKeys =
            captured.blockEvidence.mapTo(hashSetOf()) {
                it.blockId to it.sourceAssetId
            }
        check(
            evidence.all {
                it.blockId to it.sourceAssetId in evidenceKeys
            },
        ) {
            "Organization error attribution references evidence outside the exact revision"
        }
    }
}

private fun StudentProblemClassificationResultEntity.requireKnowledgeNode(
    requiredSubject: SubjectKind,
): KnowledgeNodeRef =
    KnowledgeNodeRef(
        subject =
            enumValueOrCorrupt<SubjectKind>(
                checkNotNull(knowledgeSubject) {
                    "Organization classification lacks a knowledge subject"
                },
                "organization classification knowledge subject",
            ).also { subject ->
                check(subject == requiredSubject) {
                    "Organization classification crosses the problem subject"
                }
            },
        knowledgeNodeId =
            checkNotNull(knowledgeNodeId) {
                "Organization classification lacks a knowledge node"
            },
        taxonomyVersion =
            checkNotNull(knowledgeTaxonomyVersion) {
                "Organization classification lacks a knowledge taxonomy version"
            },
        knowledgePackVersion =
            checkNotNull(knowledgePackVersion) {
                "Organization classification lacks a knowledge pack version"
            },
    )

private fun StudentProblemStepKnowledgeBindingEntity.requireKnowledgeNode(
    requiredSubject: SubjectKind,
): KnowledgeNodeRef =
    KnowledgeNodeRef(
        subject =
            enumValueOrCorrupt<SubjectKind>(
                knowledgeSubject,
                "step knowledge attribution subject",
            ).also { subject ->
                check(subject == requiredSubject) {
                    "Step knowledge attribution crosses the problem subject"
                }
            },
        knowledgeNodeId = knowledgeNodeId,
        taxonomyVersion = knowledgeTaxonomyVersion,
        knowledgePackVersion = knowledgePackVersion,
    )

private fun RoomStudentProblemOrganizationPort.checkReceiptMatchesRevision(
    receipt: StudentProblemOrganizationReceiptEntity,
    problemRevision: StudentProblemRevisionRef,
) {
    check(
        receipt.status == STUDENT_PROBLEM_ORGANIZATION_COMPLETED_STATUS &&
            receipt.learnerId == learnerId &&
            receipt.subject == problemRevision.problem.subject.name &&
            receipt.problemId == problemRevision.problem.problemId &&
            receipt.practiceUnitId == problemRevision.problem.practiceUnitId &&
            receipt.basisRevisionId == problemRevision.revisionId &&
            receipt.basisRevisionNumber == problemRevision.revisionNumber &&
            receipt.basisDocumentCanonicalFingerprint ==
            problemRevision.documentCanonicalFingerprint,
    ) {
        "Current organization attribution does not match the exact requested revision"
    }
}

private fun OrganizeStudentProblemCommand.toBundle(): StudentProblemOrganizationBundle {
    val receiptEntity =
        StudentProblemOrganizationReceiptEntity(
            receiptId = receiptId,
            requestId = requestId,
            requestCanonicalFingerprint = requestCanonicalFingerprint,
            requestVersion = provenance.requestVersion,
            reviewedRequestVersion = provenance.reviewedRequestVersion,
            organizationRevision = organizationRevision,
            supersedesReceiptId = supersedesReceiptId,
            previousPayloadCanonicalFingerprint = previousPayloadCanonicalFingerprint,
            learnerId = problemRevision.problem.learnerId,
            subject = problemRevision.problem.subject.name,
            problemId = problemRevision.problem.problemId,
            practiceUnitId = problemRevision.problem.practiceUnitId,
            basisRevisionId = problemRevision.revisionId,
            basisRevisionNumber = problemRevision.revisionNumber,
            basisDocumentCanonicalFingerprint =
                problemRevision.documentCanonicalFingerprint,
            modelProviderId = provenance.modelProviderId,
            modelId = provenance.modelId,
            requestedModelVersion = provenance.requestedModelVersion,
            resultModelVersion = provenance.resultModelVersion,
            providerConfigurationVersion = provenance.providerConfigurationVersion,
            modelTaskSchemaVersion = provenance.modelTaskSchemaVersion,
            organizationPlanSchemaVersion = provenance.organizationPlanSchemaVersion,
            reviewSource = provenance.reviewSource.name,
            reviewVersion = provenance.reviewVersion,
            reviewIssuerKeyId = provenance.reviewIssuerKeyId,
            reviewIssuerVersion = provenance.reviewIssuerVersion,
            reviewIssuedAtEpochMillis = provenance.reviewIssuedAtEpochMillis,
            reviewExpiresAtEpochMillis = provenance.reviewExpiresAtEpochMillis,
            payloadCanonicalFingerprint = payloadCanonicalFingerprint,
            errorOccurrenceCount = errorOccurrences.size,
            classificationCount = classifications.size,
            stepKnowledgeBindingCount = stepKnowledgeBindings.size,
            errorAttributionCount = errorAttributions.size,
            facetCount = facets.size,
            status = STUDENT_PROBLEM_ORGANIZATION_COMPLETED_STATUS,
            completedAtEpochMillis = completedAtEpochMillis,
        )
    val analysisEntity =
        StudentProblemSolutionAnalysisEntity(
            solutionAnalysisId = solutionAnalysis.solutionAnalysisId,
            basisRevisionId = problemRevision.revisionId,
            organizationReceiptId = receiptId,
            summaryMarkdown = solutionAnalysis.summaryMarkdown,
            finalAnswerMarkdown = solutionAnalysis.finalAnswerMarkdown,
            modelProviderId = solutionAnalysis.modelProviderId,
            modelId = solutionAnalysis.modelId,
            analyzerVersion = solutionAnalysis.analyzerVersion,
            resultCanonicalFingerprint = solutionAnalysis.resultCanonicalFingerprint,
            recordedAtEpochMillis = solutionAnalysis.recordedAtEpochMillis,
        )
    val stepEntities =
        solutionAnalysis.steps.map { step ->
            StudentProblemSolutionStepEntity(
                solutionAnalysisId = solutionAnalysis.solutionAnalysisId,
                basisRevisionId = problemRevision.revisionId,
                stepId = step.stepId,
                ordinal = step.ordinal,
                summaryMarkdown = step.summaryMarkdown,
                reasoningMarkdown = step.reasoningMarkdown,
                resultMarkdown = step.resultMarkdown,
                stepCanonicalFingerprint = step.stepCanonicalFingerprint,
            )
        }
    val attributionEntities =
        errorAttributions.map { attribution ->
            StudentProblemErrorAttributionEntity(
                attributionId = attribution.attributionId,
                basisRevisionId = problemRevision.revisionId,
                organizationReceiptId = receiptId,
                solutionAnalysisId = attribution.solutionAnalysisId,
                resolutionStatus = attribution.resolutionStatus.name,
                rationaleMarkdown = attribution.rationaleMarkdown,
                stepOrdinal = attribution.stepOrdinal,
                atomicReferenceId = attribution.atomicReferenceId,
                modelProviderId = attribution.modelProviderId,
                modelId = attribution.modelId,
                analyzerVersion = attribution.analyzerVersion,
                resultCanonicalFingerprint = attribution.resultCanonicalFingerprint,
                recordedAtEpochMillis = attribution.recordedAtEpochMillis,
            )
        }
    val evidenceEntities =
        errorAttributions.flatMap { attribution ->
            attribution.evidenceRefs.mapIndexed { ordinal, evidence ->
                evidence.toEntity(
                    attributionId = attribution.attributionId,
                    basisRevisionId = problemRevision.revisionId,
                    ordinal = ordinal,
                )
            }
        }
    val classificationEntities =
        classifications.map { classification ->
            val proof = classification.verifiedKnowledgeReference
            StudentProblemClassificationResultEntity(
                classificationId = classification.classificationId,
                problemId = problemRevision.problem.problemId,
                basisRevisionId = problemRevision.revisionId,
                organizationReceiptId = receiptId,
                dimension = classification.dimension.name,
                labelId = proof.ref.knowledgeNodeId,
                knowledgeSubject = proof.ref.subject.name,
                knowledgeNodeId = proof.ref.knowledgeNodeId,
                knowledgeTaxonomyVersion = proof.ref.taxonomyVersion,
                knowledgePackVersion = proof.ref.knowledgePackVersion,
                knowledgeManifestFingerprint = proof.manifestFingerprint,
                knowledgeActivationGeneration = proof.activationGeneration,
                modelProviderId = provenance.modelProviderId,
                modelId = provenance.modelId,
                classifierVersion = provenance.reviewVersion,
                resultCanonicalFingerprint =
                    classification.resultCanonicalFingerprint,
                status = StudentProblemClassificationStatus.ACCEPTED.name,
                supersedesClassificationId = null,
                recordedAtEpochMillis = classification.recordedAtEpochMillis,
            )
        }
    val bindingEntities =
        stepKnowledgeBindings.map { binding ->
            val proof = binding.verifiedKnowledgeReference
            StudentProblemStepKnowledgeBindingEntity(
                bindingId = binding.bindingId,
                organizationReceiptId = receiptId,
                basisRevisionId = problemRevision.revisionId,
                solutionAnalysisId = binding.solutionAnalysisId,
                stepId = binding.stepId,
                stepOrdinal = binding.stepOrdinal,
                knowledgeReferenceId = binding.knowledgeReferenceId,
                knowledgeSubject = proof.ref.subject.name,
                knowledgeNodeId = proof.ref.knowledgeNodeId,
                knowledgeTaxonomyVersion = proof.ref.taxonomyVersion,
                knowledgePackVersion = proof.ref.knowledgePackVersion,
                knowledgeContentCanonicalFingerprint = proof.manifestFingerprint,
                knowledgeActivationGeneration = proof.activationGeneration,
                bindingCanonicalFingerprint = binding.bindingCanonicalFingerprint,
                recordedAtEpochMillis = binding.recordedAtEpochMillis,
            )
        }
    val facetEntities =
        facets.map { facet ->
            StudentProblemOrganizationFacetEntity(
                organizationReceiptId = receiptId,
                dimension = facet.dimension.name,
                familyId = facet.familyId,
                familyVersion = facet.familyVersion,
                bindingCanonicalFingerprint = facet.bindingCanonicalFingerprint,
            )
        }
    val occurrenceBindings =
        errorOccurrences.map { occurrence ->
            StudentProblemOrganizationOccurrenceBindingEntity(
                organizationReceiptId = receiptId,
                occurrenceId = occurrence.occurrenceId,
                occurrenceCanonicalFingerprint =
                    occurrence.occurrenceCanonicalFingerprint,
            )
        }
    return StudentProblemOrganizationBundle(
        receipt = receiptEntity,
        errorOccurrences = occurrenceBindings,
        solutionAnalysis = analysisEntity,
        solutionSteps = stepEntities,
        errorAttributions = attributionEntities,
        errorEvidence = evidenceEntities,
        classifications = classificationEntities,
        stepKnowledgeBindings = bindingEntities,
        facets = facetEntities,
    )
}

private fun ProblemErrorEvidenceRef.toEntity(
    attributionId: String,
    basisRevisionId: String,
    ordinal: Int,
): StudentProblemErrorEvidenceEntity =
    StudentProblemErrorEvidenceEntity(
        attributionId = attributionId,
        basisRevisionId = basisRevisionId,
        ordinal = ordinal,
        blockId = blockId,
        sourceAssetId = sourceAssetId,
        evidenceKind = evidenceKind.name,
    )

private fun StudentProblemOrganizationReceiptEntity.toDomainReceipt():
    StudentProblemOrganizationReceipt {
    check(status == STUDENT_PROBLEM_ORGANIZATION_COMPLETED_STATUS) {
        "Only completed organization receipts may cross the port"
    }
    val resolvedSubject =
        enumValueOrCorrupt<SubjectKind>(
            subject,
            "organization receipt subject",
        )
    return StudentProblemOrganizationReceipt(
        receiptId = receiptId,
        requestId = requestId,
        requestCanonicalFingerprint = requestCanonicalFingerprint,
        requestVersion = requestVersion,
        organizationRevision = organizationRevision,
        supersedesReceiptId = supersedesReceiptId,
        previousPayloadCanonicalFingerprint = previousPayloadCanonicalFingerprint,
        problemRevision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = learnerId,
                        subject = resolvedSubject,
                        problemId = problemId,
                        practiceUnitId = practiceUnitId,
                    ),
                revisionId = basisRevisionId,
                revisionNumber = basisRevisionNumber,
                documentCanonicalFingerprint = basisDocumentCanonicalFingerprint,
            ),
        modelProviderId = modelProviderId,
        modelId = modelId,
        modelVersion = resultModelVersion,
        providerConfigurationVersion = providerConfigurationVersion,
        modelTaskSchemaVersion = modelTaskSchemaVersion,
        organizationPlanSchemaVersion = organizationPlanSchemaVersion,
        reviewSource =
            enumValueOrCorrupt(
                reviewSource,
                "organization review source",
            ),
        reviewVersion = reviewVersion,
        reviewIssuerKeyId = reviewIssuerKeyId,
        reviewIssuerVersion = reviewIssuerVersion,
        reviewIssuedAtEpochMillis = reviewIssuedAtEpochMillis,
        reviewExpiresAtEpochMillis = reviewExpiresAtEpochMillis,
        payloadCanonicalFingerprint = payloadCanonicalFingerprint,
        errorOccurrenceCount = errorOccurrenceCount,
        classificationCount = classificationCount,
        stepKnowledgeBindingCount = stepKnowledgeBindingCount,
        errorAttributionCount = errorAttributionCount,
        facetCount = facetCount,
        completedAtEpochMillis = completedAtEpochMillis,
    )
}
