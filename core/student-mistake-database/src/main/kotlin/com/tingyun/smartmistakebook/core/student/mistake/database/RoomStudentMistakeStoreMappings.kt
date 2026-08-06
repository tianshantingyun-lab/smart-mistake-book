package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceKind
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof

internal data class StudentRevisionAuthority(
    val solutionAnalysis: StudentProblemSolutionAnalysis?,
    val errorAttributions: List<StudentProblemErrorAttribution>,
)

internal fun StudentProblemSolutionAnalysis.toEntity(): StudentProblemSolutionAnalysisEntity =
    StudentProblemSolutionAnalysisEntity(
        solutionAnalysisId = solutionAnalysisId,
        basisRevisionId = problemRevision.revisionId,
        summaryMarkdown = summaryMarkdown,
        finalAnswerMarkdown = finalAnswerMarkdown,
        modelProviderId = modelProviderId,
        modelId = modelId,
        analyzerVersion = analyzerVersion,
        resultCanonicalFingerprint = resultCanonicalFingerprint,
        recordedAtEpochMillis = recordedAtEpochMillis,
    )

internal fun StudentProblemSolutionStep.toEntity(
    analysis: StudentProblemSolutionAnalysisEntity,
): StudentProblemSolutionStepEntity =
    StudentProblemSolutionStepEntity(
        solutionAnalysisId = analysis.solutionAnalysisId,
        basisRevisionId = analysis.basisRevisionId,
        stepId = stepId,
        ordinal = ordinal,
        summaryMarkdown = summaryMarkdown,
        reasoningMarkdown = reasoningMarkdown,
        resultMarkdown = resultMarkdown,
        stepCanonicalFingerprint = stepCanonicalFingerprint,
    )

internal fun StudentProblemErrorAttribution.toEntity(): StudentProblemErrorAttributionEntity =
    StudentProblemErrorAttributionEntity(
        attributionId = attributionId,
        basisRevisionId = problemRevision.revisionId,
        solutionAnalysisId = solutionAnalysisId,
        resolutionStatus = resolutionStatus.name,
        rationaleMarkdown = rationaleMarkdown,
        stepOrdinal = stepOrdinal,
        atomicReferenceId = atomicReferenceId,
        modelProviderId = modelProviderId,
        modelId = modelId,
        analyzerVersion = analyzerVersion,
        resultCanonicalFingerprint = resultCanonicalFingerprint,
        recordedAtEpochMillis = recordedAtEpochMillis,
    )

internal fun ProblemErrorEvidenceRef.toEntity(
    attribution: StudentProblemErrorAttribution,
    ordinal: Int,
): StudentProblemErrorEvidenceEntity =
    StudentProblemErrorEvidenceEntity(
        attributionId = attribution.attributionId,
        basisRevisionId = attribution.problemRevision.revisionId,
        ordinal = ordinal,
        blockId = blockId,
        sourceAssetId = sourceAssetId,
        evidenceKind = evidenceKind.name,
    )

internal fun StudentProblemSolutionAnalysisEntity.toDomain(
    revision: StudentProblemRevisionRef,
    steps: List<StudentProblemSolutionStepEntity>,
): StudentProblemSolutionAnalysis {
    check(steps.all { it.basisRevisionId == revision.revisionId }) {
        "Corrupt student mistake store: solution step targets another revision"
    }
    return StudentProblemSolutionAnalysis(
        solutionAnalysisId = solutionAnalysisId,
        problemRevision = revision,
        summaryMarkdown = summaryMarkdown,
        finalAnswerMarkdown = finalAnswerMarkdown,
        steps =
            steps.map { step ->
                StudentProblemSolutionStep(
                    stepId = step.stepId,
                    ordinal = step.ordinal,
                    summaryMarkdown = step.summaryMarkdown,
                    reasoningMarkdown = step.reasoningMarkdown,
                    resultMarkdown = step.resultMarkdown,
                    stepCanonicalFingerprint = step.stepCanonicalFingerprint,
                )
            },
        modelProviderId = modelProviderId,
        modelId = modelId,
        analyzerVersion = analyzerVersion,
        resultCanonicalFingerprint = resultCanonicalFingerprint,
        recordedAtEpochMillis = recordedAtEpochMillis,
    )
}

internal fun StudentProblemErrorAttributionEntity.toDomain(
    revision: StudentProblemRevisionRef,
    evidence: List<StudentProblemErrorEvidenceEntity>,
): StudentProblemErrorAttribution {
    check(basisRevisionId == revision.revisionId) {
        "Corrupt student mistake store: error attribution targets another revision"
    }
    check(evidence.map(StudentProblemErrorEvidenceEntity::ordinal) == evidence.indices.toList()) {
        "Corrupt student mistake store: error evidence is not contiguous"
    }
    return StudentProblemErrorAttribution(
        attributionId = attributionId,
        problemRevision = revision,
        solutionAnalysisId = solutionAnalysisId,
        resolutionStatus =
            enumValueOrCorrupt<ProblemErrorAttributionResolutionStatus>(
                resolutionStatus,
                "error-attribution resolution status",
            ),
        rationaleMarkdown = rationaleMarkdown,
        stepOrdinal = stepOrdinal,
        atomicReferenceId = atomicReferenceId,
        evidenceRefs =
            evidence.map { row ->
                ProblemErrorEvidenceRef(
                    blockId = row.blockId,
                    sourceAssetId = row.sourceAssetId,
                    evidenceKind =
                        enumValueOrCorrupt<ProblemErrorEvidenceKind>(
                            row.evidenceKind,
                            "error evidence kind",
                        ),
                )
            },
        modelProviderId = modelProviderId,
        modelId = modelId,
        analyzerVersion = analyzerVersion,
        resultCanonicalFingerprint = resultCanonicalFingerprint,
        recordedAtEpochMillis = recordedAtEpochMillis,
    )
}

internal fun StudentProblemRevisionHistoryRow.toDomain(): StudentProblemRevisionHistoryItem =
    StudentProblemRevisionHistoryItem(
        revision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = learnerId,
                        subject = enumValueOrCorrupt(subject, "problem subject"),
                        problemId = problemId,
                        practiceUnitId = practiceUnitId,
                    ),
                revisionId = revisionId,
                revisionNumber = revisionNumber,
                documentCanonicalFingerprint = documentCanonicalFingerprint,
            ),
        title = title,
        stemPreview = stemPreview,
        committedAtEpochMillis = committedAtEpochMillis,
        hasCapturedQuestionDocument = hasCapturedQuestionDocument,
        hasSolutionAnalysis = hasSolutionAnalysis,
        errorAttributionCount = errorAttributionCount,
    )

internal fun CommitStudentProblemCommand.toDocument(
    collection: Pair<StudentMistakeEntryState, Boolean>,
    lifecycleState: StudentProblemLifecycleState,
): StudentProblemDocument =
    StudentProblemDocument(
        revision = revision,
        title = title,
        stemMarkdown = stemMarkdown,
        practiceUnitKind = practiceUnitKind,
        practiceUnitTitle = practiceUnitTitle,
        itemFamilyId = itemFamilyId,
        estimatedDurationSeconds = estimatedDurationSeconds,
        sourceBundleId = sourceBundleId,
        partIds = partIds,
        originalImages = originalImages,
        lifecycleState = lifecycleState,
        mistakeState = collection.first,
        favorite = collection.second,
        errorBookEntryId = errorBookEntryId,
        capturedQuestionDocument = capturedQuestionDocument,
        solutionAnalysis = solutionAnalysis,
        errorAttributions = errorAttributions,
    )

internal fun StudentProblemDocumentEntity.matches(ref: StudentProblemRef): Boolean =
    learnerId == ref.learnerId &&
        subject == ref.subject.name &&
        problemId == ref.problemId &&
        primaryPracticeUnitId == ref.practiceUnitId

internal fun StudentProblemRevisionEntity.toRef(
    problem: StudentProblemDocumentEntity,
): StudentProblemRevisionRef =
    StudentProblemRevisionRef(
        problem =
            StudentProblemRef(
                learnerId = problem.learnerId,
                subject = enumValueOrCorrupt(problem.subject, "problem subject"),
                problemId = problem.problemId,
                practiceUnitId = problem.primaryPracticeUnitId,
            ),
        revisionId = revisionId,
        revisionNumber = revisionNumber,
        documentCanonicalFingerprint = documentCanonicalFingerprint,
    )

internal fun StudentProblemImageReferenceEntity.toDomain(): StudentProblemImageReference =
    StudentProblemImageReference(
        imageReferenceId = imageReferenceId,
        localContentUri = localContentUri,
        contentCanonicalFingerprint = contentCanonicalFingerprint,
        mediaType = mediaType,
        ordinal = ordinal,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        byteSize = byteSize,
        selectedRegions = decodeSelectedRegions(selectedRegionsWire),
    )

internal fun String.toEscapedSearchPattern(): String =
    "%" +
        trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") +
        "%"

internal fun StudentMistakeSearchRow.toDomain(): StudentMistakeSearchItem =
    StudentMistakeSearchItem(
        problemRevision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = learnerId,
                        subject = enumValueOrCorrupt(subject, "problem subject"),
                        problemId = problemId,
                        practiceUnitId = practiceUnitId,
                    ),
                revisionId = revisionId,
                revisionNumber = revisionNumber,
                documentCanonicalFingerprint = documentCanonicalFingerprint,
            ),
        title = title,
        stemPreview = stemPreview,
        practiceUnitTitle = practiceUnitTitle,
        estimatedDurationSeconds = estimatedDurationSeconds,
        favorite = favorite,
        changedAtEpochMillis = changedAtEpochMillis,
    )

internal fun StudentProblemClassificationResult.requiresKnowledgeVerification(): Boolean =
    knowledgeNode != null &&
        (
            status == StudentProblemClassificationStatus.ACCEPTED ||
                status == StudentProblemClassificationStatus.REVOKED
        )

internal fun StudentProblemClassificationResult.toEntity(
    verifiedKnowledgeReference: VerifiedKnowledgeReferenceProof?,
): StudentProblemClassificationResultEntity {
    check(requiresKnowledgeVerification() == (verifiedKnowledgeReference != null)) {
        "Only accepted or revoked knowledge classifications may carry verification proof"
    }
    check(verifiedKnowledgeReference == null || verifiedKnowledgeReference.ref == knowledgeNode) {
        "Verified knowledge proof does not match the persisted reference"
    }
    return StudentProblemClassificationResultEntity(
        classificationId = classificationId,
        problemId = problemRevision.problem.problemId,
        basisRevisionId = problemRevision.revisionId,
        dimension = dimension.name,
        labelId = labelId,
        knowledgeSubject = knowledgeNode?.subject?.name,
        knowledgeNodeId = knowledgeNode?.knowledgeNodeId,
        knowledgeTaxonomyVersion = knowledgeNode?.taxonomyVersion,
        knowledgePackVersion = knowledgeNode?.knowledgePackVersion,
        knowledgeManifestFingerprint = verifiedKnowledgeReference?.manifestFingerprint,
        knowledgeActivationGeneration = verifiedKnowledgeReference?.activationGeneration,
        modelProviderId = modelProviderId,
        modelId = modelId,
        classifierVersion = classifierVersion,
        resultCanonicalFingerprint = resultCanonicalFingerprint,
        status = status.name,
        supersedesClassificationId = supersedesClassificationId,
        recordedAtEpochMillis = recordedAtEpochMillis,
    )
}

internal fun StudentProblemClassificationResultEntity.sameSubmittedResult(
    submitted: StudentProblemClassificationResultEntity,
): Boolean =
    this == submitted ||
        (
            status == StudentProblemClassificationStatus.SUPERSEDED.name &&
                submitted.status == StudentProblemClassificationStatus.ACCEPTED.name &&
                copy(status = StudentProblemClassificationStatus.ACCEPTED.name) == submitted
        )

internal fun StudentProblemClassificationResultEntity.conflictsWithAccepted(
    other: StudentProblemClassificationResultEntity,
): Boolean {
    if (dimension != other.dimension) return false
    return if (dimension == StudentProblemClassificationDimension.CURRICULUM_SECTION.name) {
        true
    } else {
        knowledgeSubject == other.knowledgeSubject &&
            knowledgeNodeId == other.knowledgeNodeId
    }
}

internal fun StudentProblemClassificationResultEntity.requireKnowledgeNodeRef(): KnowledgeNodeRef =
    KnowledgeNodeRef(
        subject = enumValueOrCorrupt(checkNotNull(knowledgeSubject), "knowledge subject"),
        knowledgeNodeId = checkNotNull(knowledgeNodeId),
        taxonomyVersion = checkNotNull(knowledgeTaxonomyVersion),
        knowledgePackVersion = checkNotNull(knowledgePackVersion),
    )

internal fun StudentProblemClassificationResultEntity.requireKnowledgeVerification(): Pair<String, Long> {
    val manifestFingerprint = checkNotNull(knowledgeManifestFingerprint) {
        "Accepted knowledge classification is missing its verified manifest"
    }
    requireSha256(manifestFingerprint, "Verified knowledge manifest fingerprint")
    val activationGeneration = checkNotNull(knowledgeActivationGeneration) {
        "Accepted knowledge classification is missing its activation generation"
    }
    check(activationGeneration > 0) {
        "Accepted knowledge classification has an invalid activation generation"
    }
    return Pair(manifestFingerprint, activationGeneration)
}

internal fun locallyVerifiedBindingFingerprint(
    classificationId: String,
    revision: StudentProblemRevisionRef,
    knowledgeNode: KnowledgeNodeRef,
    manifestFingerprint: String,
    activationGeneration: Long,
): String =
    CanonicalSha256("student-mistake-problem-knowledge-binding-v1")
        .field("classificationId", classificationId)
        .field("problemRevisionRef", revision.canonicalFingerprint)
        .field("knowledgeNodeRef", knowledgeNode.canonicalFingerprint)
        .field("knowledgeManifestFingerprint", manifestFingerprint)
        .field("knowledgeActivationGeneration", activationGeneration)
        .finish()

internal fun StudentProblemClassificationResultEntity.toDomain(
    revision: StudentProblemRevisionRef,
): StudentProblemClassificationResult {
    val knowledgeColumns =
        listOf(
            knowledgeSubject,
            knowledgeNodeId,
            knowledgeTaxonomyVersion,
            knowledgePackVersion,
        )
    check(knowledgeColumns.all { it == null } || knowledgeColumns.all { it != null }) {
        "Corrupt student mistake store: partial knowledge-node reference"
    }
    val statusValue =
        enumValueOrCorrupt<StudentProblemClassificationStatus>(
            status,
            "classification status",
        )
    val requiresVerification =
        knowledgeNodeId != null &&
            statusValue in
            setOf(
                StudentProblemClassificationStatus.ACCEPTED,
                StudentProblemClassificationStatus.SUPERSEDED,
                StudentProblemClassificationStatus.REVOKED,
            )
    check(
        if (requiresVerification) {
            knowledgeManifestFingerprint != null && knowledgeActivationGeneration != null
        } else {
            knowledgeManifestFingerprint == null && knowledgeActivationGeneration == null
        },
    ) {
        "Corrupt student mistake store: knowledge verification provenance is inconsistent"
    }
    val knowledgeRef =
        knowledgeNodeId?.let {
            KnowledgeNodeRef(
                subject = enumValueOrCorrupt(checkNotNull(knowledgeSubject), "knowledge subject"),
                knowledgeNodeId = it,
                taxonomyVersion = checkNotNull(knowledgeTaxonomyVersion),
                knowledgePackVersion = checkNotNull(knowledgePackVersion),
            )
        }
    val dimensionValue =
        enumValueOrCorrupt<StudentProblemClassificationDimension>(
            dimension,
            "classification dimension",
        )
    return StudentProblemClassificationResult(
        classificationId = classificationId,
        problemRevision = revision,
        dimension = dimensionValue,
        labelId = labelId,
        displayName =
            if (dimensionValue == StudentProblemClassificationDimension.CURRICULUM_SECTION) {
                STUDENT_CLASSIFICATION_DISPLAY_PLACEHOLDER
            } else {
                null
            },
        knowledgeNode = knowledgeRef,
        modelProviderId = modelProviderId,
        modelId = modelId,
        classifierVersion = classifierVersion,
        resultCanonicalFingerprint = resultCanonicalFingerprint,
        status = statusValue,
        supersedesClassificationId = supersedesClassificationId,
        recordedAtEpochMillis = recordedAtEpochMillis,
    )
}

internal fun StudentReviewCandidate.toEntity(): StudentReviewCandidateEntity =
    StudentReviewCandidateEntity(
        candidateId = candidateId,
        learnerId = problemRevision.problem.learnerId,
        practiceUnitId = problemRevision.problem.practiceUnitId,
        basisRevisionId = problemRevision.revisionId,
        reasonCodesWire = encodeCanonicalSet(reasonCodes),
        itemFamilyId = itemFamilyId,
        estimatedDurationSeconds = estimatedDurationSeconds,
        availableAtEpochMillis = availableAtEpochMillis,
        dueAtEpochMillis = dueAtEpochMillis,
        sourceEvidenceEventKind = sourceEvidence?.eventKind,
        sourceEvidenceEventId = sourceEvidence?.eventId,
        sourceEvidenceSequence = sourceEvidence?.eventSequence,
        sourceEvidenceCanonicalFingerprint = sourceEvidence?.eventCanonicalFingerprint,
        candidateVersion = candidateVersion,
        createdAtEpochMillis = updatedAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

internal fun StudentReviewCandidateReadRow.toDomain(): StudentReviewCandidate {
    val evidenceColumns =
        listOf(
            sourceEvidenceEventKind,
            sourceEvidenceEventId,
            sourceEvidenceSequence,
            sourceEvidenceCanonicalFingerprint,
        )
    check(evidenceColumns.all { it == null } || evidenceColumns.all { it != null }) {
        "Corrupt student mistake store: partial review-candidate evidence reference"
    }
    return StudentReviewCandidate(
        candidateId = candidateId,
        problemRevision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = learnerId,
                        subject = enumValueOrCorrupt(subject, "problem subject"),
                        problemId = problemId,
                        practiceUnitId = practiceUnitId,
                    ),
                revisionId = basisRevisionId,
                revisionNumber = revisionNumber,
                documentCanonicalFingerprint = documentCanonicalFingerprint,
            ),
        reasonCodes = decodeOrderedStrings(reasonCodesWire).toSet(),
        itemFamilyId = itemFamilyId,
        estimatedDurationSeconds = estimatedDurationSeconds,
        availableAtEpochMillis = availableAtEpochMillis,
        dueAtEpochMillis = dueAtEpochMillis,
        sourceEvidence =
            sourceEvidenceEventId?.let {
                LearningEvidenceRef(
                    learnerId = learnerId,
                    eventKind = checkNotNull(sourceEvidenceEventKind),
                    eventId = it,
                    eventSequence = checkNotNull(sourceEvidenceSequence),
                    eventCanonicalFingerprint =
                        checkNotNull(sourceEvidenceCanonicalFingerprint),
                )
            },
        candidateVersion = candidateVersion,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun StudentReviewAcceptedKnowledgeRow.toRef(): KnowledgeNodeRef {
    requireSha256(
        knowledgeManifestFingerprint,
        "Accepted review knowledge manifest fingerprint",
    )
    check(knowledgeActivationGeneration > 0) {
        "Corrupt student mistake store: accepted knowledge activation is invalid"
    }
    return KnowledgeNodeRef(
        subject = enumValueOrCorrupt(knowledgeSubject, "review knowledge subject"),
        knowledgeNodeId = knowledgeNodeId,
        taxonomyVersion = knowledgeTaxonomyVersion,
        knowledgePackVersion = knowledgePackVersion,
    )
}

internal fun RecordReviewSelfReportAndCompleteCommand.toEntity():
    StudentReviewSelfReportReceiptEntity =
    StudentReviewSelfReportReceiptEntity(
        selfReportId = selfReportId,
        reportCanonicalFingerprint = canonicalFingerprint,
        learnerId = learnerId,
        queueItemId = queueItemId,
        reportKind = report.name,
        reportedAtEpochMillis = reportedAtEpochMillis,
        nextAvailableAtEpochMillis = nextAvailableAtEpochMillis,
        nextDueAtEpochMillis = nextDueAtEpochMillis,
        schedulingPolicyVersion = schedulingPolicyVersion,
    )

internal fun StudentReviewQueueItem.toEntity(
    planId: String,
    learnerId: String,
    createdAtEpochMillis: Long,
): StudentReviewQueueItemEntity =
    StudentReviewQueueItemEntity(
        queueItemId = queueItemId,
        planId = planId,
        learnerId = learnerId,
        practiceUnitId = problemRevision.problem.practiceUnitId,
        basisRevisionId = problemRevision.revisionId,
        scheduledOrder = scheduledOrder,
        estimatedDurationSeconds = estimatedDurationSeconds,
        reasonCodesWire = encodeCanonicalSet(reasonCodes),
        sourceEvidenceEventKind = sourceEvidence?.eventKind,
        sourceEvidenceEventId = sourceEvidence?.eventId,
        sourceEvidenceSequence = sourceEvidence?.eventSequence,
        sourceEvidenceCanonicalFingerprint = sourceEvidence?.eventCanonicalFingerprint,
        state = state.name,
        createdAtEpochMillis = createdAtEpochMillis,
        stateChangedAtEpochMillis = createdAtEpochMillis,
    )

internal fun StudentReviewQueueReadRow.toDomain(): StudentReviewQueueItem {
    val evidenceColumns =
        listOf(
            sourceEvidenceEventKind,
            sourceEvidenceEventId,
            sourceEvidenceSequence,
            sourceEvidenceCanonicalFingerprint,
        )
    check(evidenceColumns.all { it == null } || evidenceColumns.all { it != null }) {
        "Corrupt student mistake store: partial learning-evidence reference"
    }
    return StudentReviewQueueItem(
        queueItemId = queueItemId,
        problemRevision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = learnerId,
                        subject = enumValueOrCorrupt(subject, "problem subject"),
                        problemId = problemId,
                        practiceUnitId = practiceUnitId,
                    ),
                revisionId = basisRevisionId,
                revisionNumber = revisionNumber,
                documentCanonicalFingerprint = documentCanonicalFingerprint,
            ),
        scheduledOrder = scheduledOrder,
        estimatedDurationSeconds = estimatedDurationSeconds,
        reasonCodes = decodeOrderedStrings(reasonCodesWire).toSet(),
        sourceEvidence =
            sourceEvidenceEventId?.let {
                LearningEvidenceRef(
                    learnerId = learnerId,
                    eventKind = checkNotNull(sourceEvidenceEventKind),
                    eventId = it,
                    eventSequence = checkNotNull(sourceEvidenceSequence),
                    eventCanonicalFingerprint =
                        checkNotNull(sourceEvidenceCanonicalFingerprint),
                )
            },
        state = enumValueOrCorrupt(state, "review queue state"),
    )
}

internal inline fun <reified T : Enum<T>> enumValueOrCorrupt(
    value: String,
    label: String,
): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: error("Corrupt student mistake store: unknown $label '$value'")

internal const val MAX_CLASSIFICATION_ID_BATCH = 400
internal const val INITIAL_REVIEW_REASON = "saved-mistake"
