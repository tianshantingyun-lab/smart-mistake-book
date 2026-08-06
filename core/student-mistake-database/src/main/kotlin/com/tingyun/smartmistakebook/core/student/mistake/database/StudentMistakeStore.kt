package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionCandidate
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.io.Closeable
import kotlin.jvm.JvmSynthetic
import kotlinx.coroutines.flow.Flow

const val STUDENT_MISTAKE_DATABASE_NAME = "student-mistakes.db"

enum class StudentPracticeUnitKind {
    WHOLE_PROBLEM,
    PROBLEM_PART,
    SHARED_STIMULUS_GROUP,
}

enum class StudentMistakeEntryState {
    NONE,
    ACTIVE,
    ARCHIVED,
    TRASHED,
}

enum class StudentProblemLifecycleState {
    ACTIVE,
    ARCHIVED,
    TOMBSTONED,
}

enum class StudentProblemClassificationDimension {
    CURRICULUM_SECTION,
    KNOWLEDGE,
}

enum class StudentProblemClassificationStatus {
    CANDIDATE,
    ACCEPTED,
    REJECTED,
    SUPERSEDED,
    REVOKED,
}

enum class StudentReviewQueueState {
    READY,
    PRESENTED,
    COMPLETED,
    SKIPPED,
    REMOVED,
}

enum class StudentReviewSelfReportKind {
    DONE,
    STUCK,
}

data class StudentProblemImageReference(
    val imageReferenceId: String,
    val localContentUri: String,
    val contentCanonicalFingerprint: String,
    val mediaType: String,
    val ordinal: Int,
    val widthPixels: Int? = null,
    val heightPixels: Int? = null,
    val byteSize: Long? = null,
    val selectedRegions: List<NormalizedSourceRegion> = emptyList(),
) {
    init {
        imageReferenceId.requireStoreText("Image-reference id", MAX_ID_CHARS)
        localContentUri.requireStoreText("Image content URI", MAX_URI_CHARS)
        requireSha256(contentCanonicalFingerprint, "Image content fingerprint")
        mediaType.requireStoreText("Image media type", MAX_MEDIA_TYPE_CHARS)
        require(ordinal >= 0) { "Image ordinal must not be negative" }
        require((widthPixels == null) == (heightPixels == null)) {
            "Image width and height must either both be present or both be absent"
        }
        require(widthPixels == null || widthPixels in 1..MAX_IMAGE_DIMENSION_PIXELS) {
            "Image width is outside the supported range"
        }
        require(heightPixels == null || heightPixels in 1..MAX_IMAGE_DIMENSION_PIXELS) {
            "Image height is outside the supported range"
        }
        require(byteSize == null || byteSize in 1..MAX_IMAGE_BYTE_SIZE) {
            "Image byte size is outside the supported range"
        }
        require(selectedRegions.size <= MAX_SELECTED_REGIONS_PER_IMAGE) {
            "Image has too many selected regions"
        }
        require(selectedRegions.all(NormalizedSourceRegion::isValidStoreRegion)) {
            "Image selected region is invalid"
        }
        require(selectedRegions.distinct().size == selectedRegions.size) {
            "Image selected regions must be unique"
        }
    }
}

/**
 * One immutable, student-facing step from the solution analysis attached to an exact revision.
 *
 * Knowledge acceptance is deliberately absent here. Model-produced labels remain analysis text;
 * accepted knowledge references continue to require [VerifiedKnowledgeReferenceProof].
 */
data class StudentProblemSolutionStep(
    val stepId: String,
    val ordinal: Int,
    val summaryMarkdown: String,
    val reasoningMarkdown: String,
    val resultMarkdown: String? = null,
    val stepCanonicalFingerprint: String,
) {
    init {
        stepId.requireStoreText("Solution step id", MAX_ID_CHARS)
        require(ordinal > 0) { "Solution step ordinal must be positive" }
        summaryMarkdown.requireStoreText("Solution step summary", MAX_ANALYSIS_CHARS)
        reasoningMarkdown.requireStoreText("Solution step reasoning", MAX_ANALYSIS_CHARS)
        require(resultMarkdown == null || resultMarkdown.isNotBlank()) {
            "Solution step result must not be blank"
        }
        resultMarkdown?.requireStoreText("Solution step result", MAX_ANALYSIS_CHARS)
        requireSha256(stepCanonicalFingerprint, "Solution step fingerprint")
    }
}

data class StudentProblemSolutionAnalysis(
    val solutionAnalysisId: String,
    val problemRevision: StudentProblemRevisionRef,
    val summaryMarkdown: String,
    val finalAnswerMarkdown: String? = null,
    val steps: List<StudentProblemSolutionStep>,
    val modelProviderId: String,
    val modelId: String,
    val analyzerVersion: String,
    val resultCanonicalFingerprint: String,
    val recordedAtEpochMillis: Long,
) {
    init {
        solutionAnalysisId.requireStoreText("Solution analysis id", MAX_ID_CHARS)
        summaryMarkdown.requireStoreText("Solution analysis summary", MAX_DOCUMENT_CHARS)
        require(finalAnswerMarkdown == null || finalAnswerMarkdown.isNotBlank()) {
            "Solution final answer must not be blank"
        }
        finalAnswerMarkdown?.requireStoreText("Solution final answer", MAX_DOCUMENT_CHARS)
        require(steps.isNotEmpty() && steps.size <= MAX_SOLUTION_STEPS) {
            "Solution step count is outside the supported range"
        }
        require(steps.map(StudentProblemSolutionStep::ordinal) == (1..steps.size).toList()) {
            "Solution steps must use contiguous one-based ordinals"
        }
        require(steps.map(StudentProblemSolutionStep::stepId).distinct().size == steps.size) {
            "Solution step ids must be unique"
        }
        modelProviderId.requireStoreText("Solution model provider id", MAX_ID_CHARS)
        modelId.requireStoreText("Solution model id", MAX_ID_CHARS)
        analyzerVersion.requireStoreText("Solution analyzer version", MAX_VERSION_CHARS)
        requireSha256(resultCanonicalFingerprint, "Solution analysis fingerprint")
        require(recordedAtEpochMillis >= 0) {
            "Solution analysis time must not be negative"
        }
    }
}

/**
 * Immutable, evidence-grounded error fact retained by the student-mistake authority.
 *
 * Model confidence is accepted only as transient review input. It is deliberately discarded when
 * the candidate crosses this boundary and is never exposed again by a database read.
 */
@ConsistentCopyVisibility
data class StudentProblemErrorAttribution internal constructor(
    val attributionId: String,
    val problemRevision: StudentProblemRevisionRef,
    val solutionAnalysisId: String?,
    val resolutionStatus: ProblemErrorAttributionResolutionStatus,
    val rationaleMarkdown: String,
    val stepOrdinal: Int?,
    val atomicReferenceId: String?,
    val evidenceRefs: List<ProblemErrorEvidenceRef>,
    val modelProviderId: String,
    val modelId: String,
    val analyzerVersion: String,
    val resultCanonicalFingerprint: String,
    val recordedAtEpochMillis: Long,
) {
    constructor(
        attributionId: String,
        problemRevision: StudentProblemRevisionRef,
        solutionAnalysisId: String?,
        candidate: ProblemErrorAttributionCandidate,
        modelProviderId: String,
        modelId: String,
        analyzerVersion: String,
        resultCanonicalFingerprint: String,
        recordedAtEpochMillis: Long,
    ) : this(
        attributionId = attributionId,
        problemRevision = problemRevision,
        solutionAnalysisId = solutionAnalysisId,
        resolutionStatus = candidate.resolutionStatus,
        rationaleMarkdown = candidate.rationaleMarkdown,
        stepOrdinal = candidate.stepOrdinal,
        atomicReferenceId = candidate.atomicReferenceId,
        evidenceRefs = candidate.evidenceRefs.toList(),
        modelProviderId = modelProviderId,
        modelId = modelId,
        analyzerVersion = analyzerVersion,
        resultCanonicalFingerprint = resultCanonicalFingerprint,
        recordedAtEpochMillis = recordedAtEpochMillis,
    )

    init {
        attributionId.requireStoreText("Error-attribution id", MAX_ID_CHARS)
        solutionAnalysisId?.requireStoreText("Error-attribution solution id", MAX_ID_CHARS)
        rationaleMarkdown.requireStoreText("Error-attribution rationale", 1_200)
        require(evidenceRefs.size <= 8 && evidenceRefs.distinct().size == evidenceRefs.size) {
            "Error-attribution evidence must be bounded and unique"
        }
        when (resolutionStatus) {
            ProblemErrorAttributionResolutionStatus.RESOLVED -> {
                require(stepOrdinal != null && stepOrdinal > 0) {
                    "Resolved error attribution requires a solution step"
                }
                atomicReferenceId?.requireStoreText(
                    "Resolved error attribution knowledge reference",
                    MAX_ID_CHARS,
                ) ?: error("Resolved error attribution requires a knowledge reference")
                require(evidenceRefs.isNotEmpty()) {
                    "Resolved error attribution requires exact captured evidence"
                }
            }

            ProblemErrorAttributionResolutionStatus.UNRESOLVED -> {
                require(stepOrdinal == null && atomicReferenceId == null && evidenceRefs.isEmpty()) {
                    "Unresolved error attribution cannot carry invented evidence"
                }
            }
        }
        modelProviderId.requireStoreText("Error-attribution model provider id", MAX_ID_CHARS)
        modelId.requireStoreText("Error-attribution model id", MAX_ID_CHARS)
        analyzerVersion.requireStoreText("Error-attribution analyzer version", MAX_VERSION_CHARS)
        requireSha256(resultCanonicalFingerprint, "Error-attribution fingerprint")
        require(recordedAtEpochMillis >= 0) {
            "Error-attribution time must not be negative"
        }
    }
}

data class CommitStudentProblemCommand(
    val revision: StudentProblemRevisionRef,
    val title: String?,
    val stemMarkdown: String,
    val practiceUnitKind: StudentPracticeUnitKind,
    val practiceUnitTitle: String,
    val itemFamilyId: String,
    val estimatedDurationSeconds: Int,
    val sourceBundleId: String?,
    val partIds: List<String>,
    val originalImages: List<StudentProblemImageReference>,
    val committedAtEpochMillis: Long,
    val errorBookEntryId: String? = null,
    val capturedQuestionDocument: CapturedQuestionDocument? = null,
    val solutionAnalysis: StudentProblemSolutionAnalysis? = null,
    val errorAttributions: List<StudentProblemErrorAttribution> = emptyList(),
) {
    init {
        require(title == null || title.isNotBlank()) { "Problem title must not be blank" }
        stemMarkdown.requireStoreText("Problem stem", MAX_DOCUMENT_CHARS)
        practiceUnitTitle.requireStoreText("Practice-unit title", MAX_LABEL_CHARS)
        itemFamilyId.requireStoreText("Item-family id", MAX_ID_CHARS)
        require(estimatedDurationSeconds in 1..MAX_ESTIMATED_DURATION_SECONDS) {
            "Estimated duration is outside the supported range"
        }
        require(sourceBundleId == null || sourceBundleId.isNotBlank()) {
            "Source-bundle id must not be blank"
        }
        require(partIds.size <= MAX_PARTS && partIds.none(String::isBlank)) {
            "Problem part ids are invalid"
        }
        require(partIds.distinct().size == partIds.size) { "Problem part ids must be unique" }
        require(originalImages.size <= MAX_IMAGES) { "A problem has too many original images" }
        require(originalImages.map(StudentProblemImageReference::ordinal) == originalImages.indices.toList()) {
            "Original images must use contiguous ordinal order"
        }
        require(committedAtEpochMillis >= 0) { "Commit time must not be negative" }
        errorBookEntryId?.requireStoreText("Error-book entry id", MAX_ID_CHARS)
        capturedQuestionDocument?.let { document ->
            require(
                CapturedQuestionDocumentFingerprint.of(document) ==
                    revision.documentCanonicalFingerprint,
            ) {
                "Captured question snapshot does not match the exact revision fingerprint"
            }
        }
        require(
            solutionAnalysis == null || solutionAnalysis.problemRevision == revision,
        ) {
            "Solution analysis must target the committed revision"
        }
        require(errorAttributions.size <= MAX_ERROR_ATTRIBUTIONS) {
            "Problem has too many error-attribution records"
        }
        require(
            errorAttributions.all { it.problemRevision == revision },
        ) {
            "Every error attribution must target the committed revision"
        }
        require(
            errorAttributions.map(StudentProblemErrorAttribution::attributionId).distinct().size ==
                errorAttributions.size,
        ) {
            "Error-attribution ids must be unique"
        }
        require(
            errorAttributions.map(StudentProblemErrorAttribution::attributionId) ==
                errorAttributions.map(StudentProblemErrorAttribution::attributionId).sorted(),
        ) {
            "Error attributions must use stable id order"
        }
        if (errorAttributions.isNotEmpty()) {
            require(capturedQuestionDocument != null) {
                "Specific error attributions require an exact captured-question snapshot"
            }
        }
        val evidenceKeys =
            capturedQuestionDocument
                ?.blockEvidence
                ?.mapTo(hashSetOf()) { evidence ->
                    evidence.blockId to evidence.sourceAssetId
                }.orEmpty()
        errorAttributions.forEach { attribution ->
            val stepOrdinal = attribution.stepOrdinal
            if (stepOrdinal != null) {
                val analysis = checkNotNull(solutionAnalysis) {
                    "Resolved error attribution requires immutable solution analysis"
                }
                require(attribution.solutionAnalysisId == analysis.solutionAnalysisId) {
                    "Error attribution must reference the committed solution analysis"
                }
                require(stepOrdinal in 1..analysis.steps.size) {
                    "Error attribution references a missing solution step"
                }
            } else {
                require(attribution.solutionAnalysisId == null) {
                    "Unresolved error attribution cannot reference a solution analysis"
                }
            }
            require(
                attribution.evidenceRefs.all { evidence ->
                    evidence.blockId to evidence.sourceAssetId in evidenceKeys
                },
            ) {
                "Error attribution must reference exact captured block evidence"
            }
        }
        when (practiceUnitKind) {
            StudentPracticeUnitKind.WHOLE_PROBLEM ->
                require(partIds.isEmpty()) { "A whole problem cannot name individual parts" }

            StudentPracticeUnitKind.PROBLEM_PART,
            StudentPracticeUnitKind.SHARED_STIMULUS_GROUP,
            ->
                require(partIds.isNotEmpty()) { "A partial practice unit must name its parts" }
        }
    }
}

data class StudentProblemDocument(
    val revision: StudentProblemRevisionRef,
    val title: String?,
    val stemMarkdown: String,
    val practiceUnitKind: StudentPracticeUnitKind,
    val practiceUnitTitle: String,
    val itemFamilyId: String,
    val estimatedDurationSeconds: Int,
    val sourceBundleId: String?,
    val partIds: List<String>,
    val originalImages: List<StudentProblemImageReference>,
    val lifecycleState: StudentProblemLifecycleState,
    val mistakeState: StudentMistakeEntryState,
    val favorite: Boolean,
    val errorBookEntryId: String? = null,
    val capturedQuestionDocument: CapturedQuestionDocument? = null,
    val solutionAnalysis: StudentProblemSolutionAnalysis? = null,
    val errorAttributions: List<StudentProblemErrorAttribution> = emptyList(),
)

data class StudentMistakeDetailQuery(
    val learnerId: String,
    val problem: StudentProblemRef,
    val errorBookEntryId: String,
) {
    init {
        learnerId.requireStoreText("Detail learner id", MAX_ID_CHARS)
        problem.subject.requireHighSchoolSubject()
        require(problem.learnerId == learnerId) {
            "Detail problem must belong to the authenticated learner"
        }
        errorBookEntryId.requireStoreText("Error-book entry id", MAX_ID_CHARS)
    }
}

data class StudentProblemRevisionHistoryCursor(
    val revisionNumber: Int,
    val revisionId: String,
) {
    init {
        require(revisionNumber > 0) { "Revision-history cursor number must be positive" }
        revisionId.requireStoreText("Revision-history cursor id", MAX_ID_CHARS)
    }
}

data class StudentProblemRevisionHistoryQuery(
    val learnerId: String,
    val problem: StudentProblemRef,
    val errorBookEntryId: String,
    val cursor: StudentProblemRevisionHistoryCursor? = null,
    val limit: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        learnerId.requireStoreText("Revision-history learner id", MAX_ID_CHARS)
        problem.subject.requireHighSchoolSubject()
        require(problem.learnerId == learnerId) {
            "Revision-history problem must belong to the authenticated learner"
        }
        errorBookEntryId.requireStoreText("Error-book entry id", MAX_ID_CHARS)
        require(limit in 1..MAX_PAGE_SIZE) {
            "Revision-history page size is outside the supported range"
        }
    }
}

data class StudentProblemRevisionHistoryItem(
    val revision: StudentProblemRevisionRef,
    val title: String?,
    val stemPreview: String,
    val committedAtEpochMillis: Long,
    val hasCapturedQuestionDocument: Boolean,
    val hasSolutionAnalysis: Boolean,
    val errorAttributionCount: Int,
)

data class StudentProblemRevisionHistoryPage(
    val items: List<StudentProblemRevisionHistoryItem>,
    val nextCursor: StudentProblemRevisionHistoryCursor?,
)

data class SetStudentProblemLifecycleCommand(
    val problem: StudentProblemRef,
    val state: StudentProblemLifecycleState,
    val changedAtEpochMillis: Long,
) {
    init {
        require(changedAtEpochMillis >= 0) {
            "Problem lifecycle change time must not be negative"
        }
    }
}

data class SetStudentProblemCollectionCommand(
    val problem: StudentProblemRef,
    val mistakeState: StudentMistakeEntryState,
    val favorite: Boolean,
    val changedAtEpochMillis: Long,
) {
    init {
        require(changedAtEpochMillis >= 0) { "Collection change time must not be negative" }
    }
}

data class StudentProblemClassificationResult(
    val classificationId: String,
    val problemRevision: StudentProblemRevisionRef,
    val dimension: StudentProblemClassificationDimension,
    val labelId: String,
    val displayName: String?,
    val knowledgeNode: KnowledgeNodeRef?,
    val modelProviderId: String,
    val modelId: String,
    val classifierVersion: String,
    val resultCanonicalFingerprint: String,
    val status: StudentProblemClassificationStatus,
    val supersedesClassificationId: String? = null,
    val recordedAtEpochMillis: Long,
) {
    init {
        classificationId.requireStoreText("Classification id", MAX_ID_CHARS)
        labelId.requireStoreText("Classification label id", MAX_ID_CHARS)
        modelProviderId.requireStoreText("Model provider id", MAX_ID_CHARS)
        modelId.requireStoreText("Model id", MAX_ID_CHARS)
        classifierVersion.requireStoreText("Classifier version", MAX_VERSION_CHARS)
        requireSha256(resultCanonicalFingerprint, "Classification fingerprint")
        require(
            supersedesClassificationId == null ||
                supersedesClassificationId != classificationId,
        ) {
            "A classification result cannot supersede itself"
        }
        supersedesClassificationId?.requireStoreText(
            "Superseded classification id",
            MAX_ID_CHARS,
        )
        require(recordedAtEpochMillis >= 0) {
            "Classification time must not be negative"
        }
        require(
            dimension != StudentProblemClassificationDimension.KNOWLEDGE ||
                knowledgeNode != null,
        ) {
            "Knowledge classifications require a stable knowledge-node reference"
        }
        require(
            if (dimension == StudentProblemClassificationDimension.KNOWLEDGE) {
                displayName == null
            } else {
                !displayName.isNullOrBlank()
            },
        ) {
            "Knowledge classifications omit a display summary; curriculum sections require a compatibility summary"
        }
        displayName?.requireStoreText("Classification display name", MAX_LABEL_CHARS)
        require(
            knowledgeNode == null ||
                knowledgeNode.subject == problemRevision.problem.subject,
        ) {
            "Classification knowledge reference must stay within the problem subject"
        }
        require(knowledgeNode == null || labelId == knowledgeNode.knowledgeNodeId) {
            "Classification label id must be the stable knowledge-node id when a proof is present"
        }
        require(
            status != StudentProblemClassificationStatus.REVOKED ||
                supersedesClassificationId != null,
        ) {
            "A revocation must name the accepted classification it revokes"
        }
        require(
            supersedesClassificationId == null ||
                status == StudentProblemClassificationStatus.ACCEPTED ||
                status == StudentProblemClassificationStatus.REVOKED ||
                status == StudentProblemClassificationStatus.SUPERSEDED,
        ) {
            "Only a replacement, revocation, or store-owned superseded record may name prior history"
        }
    }
}

/**
 * Compatibility text for callers that still require a non-empty curriculum summary.
 *
 * Student storage never persists a catalog display name. The knowledge runtime resolves the
 * current human-readable name from [StudentProblemClassificationResult.knowledgeNode] when one is
 * available.
 */
internal const val STUDENT_CLASSIFICATION_DISPLAY_PLACEHOLDER = "已分类"

data class RecordStudentProblemClassificationsCommand(
    val problemRevision: StudentProblemRevisionRef,
    val results: List<StudentProblemClassificationResult>,
    val verifiedKnowledgeReferences: Map<String, VerifiedKnowledgeReferenceProof> = emptyMap(),
) {
    init {
        require(results.isNotEmpty() && results.size <= MAX_CLASSIFICATIONS) {
            "Classification result count is outside the supported range"
        }
        require(results.all { it.problemRevision == problemRevision }) {
            "Every classification result must target the command revision"
        }
        require(results.map(StudentProblemClassificationResult::classificationId).distinct().size == results.size) {
            "Classification ids must be unique"
        }
        require(results.none { it.status == StudentProblemClassificationStatus.SUPERSEDED }) {
            "SUPERSEDED is a store-owned history state"
        }
        val verificationRequired =
            results
                .filter {
                    it.knowledgeNode != null &&
                        (
                            it.status == StudentProblemClassificationStatus.ACCEPTED ||
                                it.status == StudentProblemClassificationStatus.REVOKED
                        )
                }
                .associateBy(StudentProblemClassificationResult::classificationId)
        require(verifiedKnowledgeReferences.keys == verificationRequired.keys) {
            "Every accepted or revoked stable classification reference requires exactly one verified proof"
        }
        verificationRequired.forEach { (classificationId, result) ->
            val proof = checkNotNull(verifiedKnowledgeReferences[classificationId])
            require(proof.ref == result.knowledgeNode) {
                "Verified knowledge reference does not match the classification reference"
            }
        }
    }
}

data class StudentMistakeSearchCursor(
    val changedAtEpochMillis: Long,
    val problemId: String,
) {
    init {
        require(changedAtEpochMillis >= 0) { "Search cursor time must not be negative" }
        problemId.requireStoreText("Search cursor problem id", MAX_ID_CHARS)
    }
}

data class StudentMistakeSearchQuery(
    val learnerId: String,
    val subject: SubjectKind? = null,
    val text: String? = null,
    val curriculumSectionLabelId: String? = null,
    val knowledgeNode: KnowledgeNodeRef? = null,
    val favoriteOnly: Boolean = false,
    val cursor: StudentMistakeSearchCursor? = null,
    val limit: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        learnerId.requireStoreText("Learner id", MAX_ID_CHARS)
        subject?.requireHighSchoolSubject()
        require(text == null || (text.isNotBlank() && text.length <= MAX_SEARCH_CHARS)) {
            "Search text is invalid"
        }
        curriculumSectionLabelId?.requireStoreText(
            "Curriculum-section label id",
            MAX_ID_CHARS,
        )
        knowledgeNode?.subject?.requireHighSchoolSubject()
        require(subject == null || knowledgeNode == null || subject == knowledgeNode.subject) {
            "Search subject and knowledge-node subject must agree"
        }
        require(limit in 1..MAX_PAGE_SIZE) { "Search page size is outside the supported range" }
    }
}

data class StudentMistakeSearchItem(
    val problemRevision: StudentProblemRevisionRef,
    val title: String?,
    val stemPreview: String,
    val practiceUnitTitle: String,
    val estimatedDurationSeconds: Int,
    val favorite: Boolean,
    val changedAtEpochMillis: Long,
)

data class StudentMistakeSearchPage(
    val items: List<StudentMistakeSearchItem>,
    val nextCursor: StudentMistakeSearchCursor?,
)

data class StudentReviewCandidateCursor(
    val availableAtEpochMillis: Long,
    val candidateId: String,
) {
    init {
        require(availableAtEpochMillis >= 0) {
            "Review-candidate cursor time must not be negative"
        }
        candidateId.requireStoreText("Review-candidate cursor id", MAX_ID_CHARS)
    }
}

data class StudentReviewCandidateQuery(
    val learnerId: String,
    val nowEpochMillis: Long,
    val cursor: StudentReviewCandidateCursor? = null,
    val limit: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        learnerId.requireStoreText("Learner id", MAX_ID_CHARS)
        require(nowEpochMillis >= 0) { "Review-candidate read time must not be negative" }
        require(limit in 1..MAX_PAGE_SIZE) {
            "Review-candidate page size is outside the supported range"
        }
    }
}

data class StudentReviewCandidatePage(
    val items: List<StudentReviewCandidate>,
    val nextCursor: StudentReviewCandidateCursor?,
)

data class StudentReviewCandidateWithKnowledge(
    val candidate: StudentReviewCandidate,
    val acceptedKnowledgeNodes: Set<KnowledgeNodeRef>,
    val reviewedProblemFamilyId: String? = null,
    val reviewedProblemFamilyBindingDocumentFingerprint: String? = null,
) {
    init {
        require(acceptedKnowledgeNodes.size <= MAX_CLASSIFICATIONS) {
            "Review candidate has too many accepted knowledge references"
        }
        reviewedProblemFamilyId?.requireStoreText(
            "Reviewed problem-family id",
            MAX_ID_CHARS,
        )
        reviewedProblemFamilyBindingDocumentFingerprint?.let { fingerprint ->
            requireSha256(fingerprint, "Reviewed problem-family document fingerprint")
        }
        require(
            (reviewedProblemFamilyId == null) ==
                (reviewedProblemFamilyBindingDocumentFingerprint == null),
        ) {
            "Reviewed problem-family binding must carry its exact basis document"
        }
        require(
            acceptedKnowledgeNodes.all {
                it.subject == candidate.problemRevision.problem.subject
            },
        ) {
            "Review candidate knowledge references must stay within the problem subject"
        }
    }
}

data class StudentReviewCandidateWithKnowledgePage(
    val items: List<StudentReviewCandidateWithKnowledge>,
    val nextCursor: StudentReviewCandidateCursor?,
)

data class TransitionStudentReviewQueueItemCommand(
    val learnerId: String,
    val queueItemId: String,
    val expectedState: StudentReviewQueueState,
    val nextState: StudentReviewQueueState,
    val changedAtEpochMillis: Long,
) {
    init {
        learnerId.requireStoreText("Learner id", MAX_ID_CHARS)
        queueItemId.requireStoreText("Review queue-item id", MAX_ID_CHARS)
        require(changedAtEpochMillis >= 0) {
            "Review queue state-change time must not be negative"
        }
        require(expectedState.canTransitionTo(nextState)) {
            "Review queue state transition is not allowed"
        }
    }
}

enum class StudentMistakeInboundDisposition {
    APPLIED,
    DUPLICATE,
    REAUTHENTICATION_REQUIRED,
}

/**
 * Immutable proof that an envelope was read from the student-mistake outbox.
 *
 * The constructor is deliberately private: a structurally valid public envelope is not proof that
 * the student authority emitted it. Relay destinations must require this source-issued wrapper.
 */
/**
 * Learner-bound relay owner capability.
 *
 * Business store users never receive this interface. The authority runtime keeps the sole
 * production instance private and hands it only to the core:data relay owner.
 */
interface StudentMistakeRelayCapability {
    val learnerId: String

    suspend fun acceptInboundBatch(
        messages: List<VerifiedLearnerMasteryDelivery>,
        receivedAtEpochMillis: Long,
    ): List<StudentMistakeInboundDisposition>

    suspend fun acceptInbound(
        message: VerifiedLearnerMasteryDelivery,
        receivedAtEpochMillis: Long,
    ): StudentMistakeInboundDisposition

    suspend fun readPending(
        nowEpochMillis: Long,
        limit: Int = DEFAULT_MESSAGE_BATCH_SIZE,
    ): List<StudentOutboxDelivery>

    suspend fun markDelivered(
        message: StudentOutboxDelivery,
        deliveredAtEpochMillis: Long,
    )

    suspend fun readMasteryRelayReauthenticationStatus():
        StudentMasteryRelayReauthenticationStatus

    suspend fun reauthorizeMasteryRelaySource(
        command: StudentMasteryRelayReauthorizationCommand,
    ): StudentMasteryRelayReauthenticationStatus
}

data class StudentReviewCandidate(
    val candidateId: String,
    val problemRevision: StudentProblemRevisionRef,
    val reasonCodes: Set<String>,
    val itemFamilyId: String,
    val estimatedDurationSeconds: Int,
    val availableAtEpochMillis: Long,
    val dueAtEpochMillis: Long?,
    val sourceEvidence: LearningEvidenceRef?,
    val candidateVersion: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        candidateId.requireStoreText("Review-candidate id", MAX_ID_CHARS)
        require(reasonCodes.isNotEmpty() && reasonCodes.size <= MAX_REVIEW_REASONS) {
            "Review candidate reasons are invalid"
        }
        reasonCodes.forEach { it.requireStoreText("Review reason", MAX_REASON_CHARS) }
        itemFamilyId.requireStoreText("Review item-family id", MAX_ID_CHARS)
        require(estimatedDurationSeconds in 1..MAX_ESTIMATED_DURATION_SECONDS) {
            "Estimated duration is outside the supported range"
        }
        require(availableAtEpochMillis >= 0) { "Availability time must not be negative" }
        require(dueAtEpochMillis == null || dueAtEpochMillis >= 0) {
            "Due time must not be negative"
        }
        require(candidateVersion > 0) { "Candidate version must be positive" }
        require(updatedAtEpochMillis >= 0) { "Candidate update time must not be negative" }
        require(
            sourceEvidence == null ||
                sourceEvidence.learnerId == problemRevision.problem.learnerId,
        ) {
            "Review candidate evidence and problem must belong to the same learner"
        }
    }
}

data class StudentReviewQueueItem(
    val queueItemId: String,
    val problemRevision: StudentProblemRevisionRef,
    val scheduledOrder: Int,
    val estimatedDurationSeconds: Int,
    val reasonCodes: Set<String>,
    val sourceEvidence: LearningEvidenceRef? = null,
    val state: StudentReviewQueueState = StudentReviewQueueState.READY,
) {
    init {
        queueItemId.requireStoreText("Review queue-item id", MAX_ID_CHARS)
        require(scheduledOrder >= 0) { "Review order must not be negative" }
        require(estimatedDurationSeconds in 1..MAX_ESTIMATED_DURATION_SECONDS) {
            "Estimated duration is outside the supported range"
        }
        require(reasonCodes.isNotEmpty() && reasonCodes.size <= MAX_REVIEW_REASONS) {
            "Review queue reasons are invalid"
        }
        reasonCodes.forEach { it.requireStoreText("Review reason", MAX_REASON_CHARS) }
        require(
            sourceEvidence == null ||
                sourceEvidence.learnerId == problemRevision.problem.learnerId,
        ) {
            "Review queue evidence and problem must belong to the same learner"
        }
    }
}

data class StoreStudentReviewQueueCommand(
    val planId: String,
    val planCanonicalFingerprint: String,
    val learnerId: String,
    val localDayEpochDay: Long,
    val timeZoneId: String,
    val timeBudgetSeconds: Int,
    val generatedAtEpochMillis: Long,
    val plannerVersion: String,
    val items: List<StudentReviewQueueItem>,
) {
    init {
        planId.requireStoreText("Review-plan id", MAX_ID_CHARS)
        requireSha256(planCanonicalFingerprint, "Review-plan fingerprint")
        learnerId.requireStoreText("Learner id", MAX_ID_CHARS)
        timeZoneId.requireStoreText("Time-zone id", MAX_ID_CHARS)
        require(timeBudgetSeconds in 0..MAX_REVIEW_BUDGET_SECONDS) {
            "Review time budget is outside the supported range"
        }
        require(generatedAtEpochMillis >= 0) {
            "Review-plan generation time must not be negative"
        }
        plannerVersion.requireStoreText("Review planner version", MAX_VERSION_CHARS)
        require(items.size <= MAX_REVIEW_ITEMS) { "Review plan has too many items" }
        require(items.map(StudentReviewQueueItem::scheduledOrder) == items.indices.toList()) {
            "Review items must use contiguous scheduled order"
        }
        require(items.map(StudentReviewQueueItem::queueItemId).distinct().size == items.size) {
            "Review queue-item ids must be unique"
        }
        require(items.all { it.problemRevision.problem.learnerId == learnerId }) {
            "Every review item must belong to the plan learner"
        }
        require(items.all { it.state == StudentReviewQueueState.READY }) {
            "New review queue items must start READY"
        }
        require(items.sumOf(StudentReviewQueueItem::estimatedDurationSeconds) <= timeBudgetSeconds) {
            "Review queue must fit its time budget"
        }
    }
}

data class StudentReviewPlanSnapshot(
    val planId: String,
    val planCanonicalFingerprint: String,
    val learnerId: String,
    val localDayEpochDay: Long,
    val timeZoneId: String,
    val timeBudgetSeconds: Int,
    val generatedAtEpochMillis: Long,
    val plannerVersion: String,
    val items: List<StudentReviewQueueItem>,
) {
    init {
        planId.requireStoreText("Review-plan id", MAX_ID_CHARS)
        requireSha256(planCanonicalFingerprint, "Review-plan fingerprint")
        learnerId.requireStoreText("Learner id", MAX_ID_CHARS)
        timeZoneId.requireStoreText("Time-zone id", MAX_ID_CHARS)
        require(timeBudgetSeconds in 0..MAX_REVIEW_BUDGET_SECONDS) {
            "Review time budget is outside the supported range"
        }
        require(generatedAtEpochMillis >= 0) {
            "Review-plan generation time must not be negative"
        }
        plannerVersion.requireStoreText("Review planner version", MAX_VERSION_CHARS)
        require(items.size <= MAX_REVIEW_ITEMS) { "Review plan has too many items" }
        require(items.map(StudentReviewQueueItem::scheduledOrder) == items.indices.toList()) {
            "Review items must use contiguous scheduled order"
        }
        require(items.map(StudentReviewQueueItem::queueItemId).distinct().size == items.size) {
            "Review queue-item ids must be unique"
        }
        require(items.all { it.problemRevision.problem.learnerId == learnerId }) {
            "Every review item must belong to the plan learner"
        }
        require(items.sumOf(StudentReviewQueueItem::estimatedDurationSeconds) <= timeBudgetSeconds) {
            "Review queue must fit its time budget"
        }
    }
}

data class RecordReviewSelfReportAndCompleteCommand(
    val selfReportId: String,
    val learnerId: String,
    val queueItemId: String,
    val report: StudentReviewSelfReportKind,
    val reportedAtEpochMillis: Long,
    val nextAvailableAtEpochMillis: Long,
    val nextDueAtEpochMillis: Long? = null,
    val schedulingPolicyVersion: String,
) {
    init {
        selfReportId.requireStoreText("Review self-report id", MAX_ID_CHARS)
        learnerId.requireStoreText("Learner id", MAX_ID_CHARS)
        queueItemId.requireStoreText("Review queue-item id", MAX_ID_CHARS)
        require(reportedAtEpochMillis >= 0) {
            "Review self-report time must not be negative"
        }
        require(nextAvailableAtEpochMillis >= reportedAtEpochMillis) {
            "Next review availability cannot precede the self-report"
        }
        require(
            nextDueAtEpochMillis == null ||
                nextDueAtEpochMillis >= nextAvailableAtEpochMillis,
        ) {
            "Next review due time cannot precede availability"
        }
        schedulingPolicyVersion.requireStoreText(
            "Review scheduling-policy version",
            MAX_VERSION_CHARS,
        )
    }

    val canonicalFingerprint: String
        get() =
            CanonicalSha256(SELF_REPORT_COMMAND_DOMAIN)
                .field("selfReportId", selfReportId)
                .field("learnerId", learnerId)
                .field("queueItemId", queueItemId)
                .field("report", report.name)
                .field("reportedAtEpochMillis", reportedAtEpochMillis)
                .field("nextAvailableAtEpochMillis", nextAvailableAtEpochMillis)
                .nullableField("nextDueAtEpochMillis", nextDueAtEpochMillis?.toString())
                .field("schedulingPolicyVersion", schedulingPolicyVersion)
                .finish()

    private companion object {
        const val SELF_REPORT_COMMAND_DOMAIN = "student-review-self-report-command-v1"
    }
}

data class StudentMistakeMigrationKey(
    val committedAtEpochMillis: Long,
    val problemId: String,
    val revisionNumber: Int,
    val revisionId: String,
) : Comparable<StudentMistakeMigrationKey> {
    init {
        require(committedAtEpochMillis >= 0) { "Migration key time must not be negative" }
        problemId.requireStoreText("Migration key problem id", MAX_ID_CHARS)
        require(revisionNumber > 0) { "Migration key revision number must be positive" }
        revisionId.requireStoreText("Migration key revision id", MAX_ID_CHARS)
    }

    override fun compareTo(other: StudentMistakeMigrationKey): Int =
        compareValuesBy(
            this,
            other,
            StudentMistakeMigrationKey::committedAtEpochMillis,
            StudentMistakeMigrationKey::problemId,
            StudentMistakeMigrationKey::revisionNumber,
            StudentMistakeMigrationKey::revisionId,
        )
}

data class StudentMistakeMigrationRecord(
    val problem: CommitStudentProblemCommand,
    val collection: SetStudentProblemCollectionCommand,
    val importSemanticSnapshot: StudentMistakeImportSemanticSnapshot? = null,
) {
    val key: StudentMistakeMigrationKey =
        StudentMistakeMigrationKey(
            committedAtEpochMillis = problem.committedAtEpochMillis,
            problemId = problem.revision.problem.problemId,
            revisionNumber = problem.revision.revisionNumber,
            revisionId = problem.revision.revisionId,
        )

    init {
        require(collection.problem == problem.revision.problem) {
            "Migration collection state must target the migrated problem"
        }
        require(
            collection.changedAtEpochMillis >= problem.committedAtEpochMillis,
        ) {
            "Migration collection state predates the migrated revision"
        }
        require(
            collection.mistakeState != StudentMistakeEntryState.NONE || collection.favorite,
        ) {
            "Migration cannot create an uncollected placeholder problem"
        }
    }
}

/**
 * A source adapter supplies a bounded keyset page. The student database never opens or joins the
 * source database; it accepts only validated domain records and immutable source fingerprints.
 */
data class ApplyStudentMistakeMigrationPageCommand(
    val migrationId: String,
    val sourceDatabaseCanonicalFingerprint: String,
    val sourcePageCanonicalFingerprint: String,
    val expectedCheckpointCanonicalFingerprint: String?,
    val afterExclusive: StudentMistakeMigrationKey?,
    val records: List<StudentMistakeMigrationRecord>,
    val isLastPage: Boolean,
    val appliedAtEpochMillis: Long,
) {
    init {
        migrationId.requireStoreText("Migration id", MAX_ID_CHARS)
        requireSha256(
            sourceDatabaseCanonicalFingerprint,
            "Migration source database fingerprint",
        )
        requireSha256(sourcePageCanonicalFingerprint, "Migration source page fingerprint")
        expectedCheckpointCanonicalFingerprint?.let {
            requireSha256(it, "Migration expected checkpoint fingerprint")
        }
        require(records.size <= MAX_MIGRATION_PAGE_SIZE) {
            "Migration page exceeds the supported size"
        }
        require(records.isNotEmpty() || isLastPage) {
            "Only the terminal migration page may be empty"
        }
        require(records.map(StudentMistakeMigrationRecord::key).zipWithNext().all { (a, b) -> a < b }) {
            "Migration records must use strict keyset order"
        }
        require(afterExclusive == null || records.firstOrNull()?.key?.let { it > afterExclusive } != false) {
            "Migration page does not start after its exclusive key"
        }
        require(appliedAtEpochMillis >= 0) { "Migration apply time must not be negative" }
    }
}

data class StudentMistakeMigrationCheckpoint(
    val migrationId: String,
    val sourceDatabaseCanonicalFingerprint: String,
    val lastKey: StudentMistakeMigrationKey?,
    val importedRecordCount: Long,
    val completed: Boolean,
    val checkpointCanonicalFingerprint: String,
) {
    init {
        migrationId.requireStoreText("Migration checkpoint id", MAX_ID_CHARS)
        requireSha256(
            sourceDatabaseCanonicalFingerprint,
            "Migration checkpoint source fingerprint",
        )
        require(importedRecordCount >= 0) {
            "Migration checkpoint record count must not be negative"
        }
        requireSha256(checkpointCanonicalFingerprint, "Migration checkpoint fingerprint")
    }
}

data class StudentMistakeMigrationReceipt(
    val migrationId: String,
    val sourcePageCanonicalFingerprint: String,
    val importedRecordCount: Int,
    val checkpoint: StudentMistakeMigrationCheckpoint,
    val receiptCanonicalFingerprint: String,
) {
    init {
        migrationId.requireStoreText("Migration receipt id", MAX_ID_CHARS)
        requireSha256(sourcePageCanonicalFingerprint, "Migration receipt source-page fingerprint")
        require(importedRecordCount >= 0) {
            "Migration receipt record count must not be negative"
        }
        require(checkpoint.migrationId == migrationId) {
            "Migration receipt and checkpoint ids must agree"
        }
        requireSha256(receiptCanonicalFingerprint, "Migration receipt fingerprint")
    }
}

data class StudentMistakeModelReadQuery(
    val subject: SubjectKind,
    val knowledgeNodes: Set<KnowledgeNodeRef> = emptySet(),
    val text: String? = null,
    val limit: Int = DEFAULT_MODEL_READ_PAGE_SIZE,
) {
    init {
        subject.requireHighSchoolSubject()
        require(knowledgeNodes.size <= MAX_MODEL_READ_KNOWLEDGE_REFS) {
            "Model-read knowledge scope is too broad"
        }
        require(knowledgeNodes.all { it.subject == subject }) {
            "Model-read knowledge references must stay inside the fixed subject"
        }
        require(text == null || (text.isNotBlank() && text.length <= MAX_SEARCH_CHARS)) {
            "Model-read search text is invalid"
        }
        require(limit in 1..MAX_MODEL_READ_PAGE_SIZE) {
            "Model-read result limit is outside the supported range"
        }
    }
}

data class StudentMistakeModelProblemSummary(
    val problemRevision: StudentProblemRevisionRef,
    val title: String?,
    val stemPreview: String,
    val practiceUnitTitle: String,
    val estimatedDurationSeconds: Int,
)

/** Read-only, scoped surface suitable for a core:data model-context adapter. */
interface StudentMistakeModelReadPort : Closeable {
    suspend fun readProblemSummaries(
        query: StudentMistakeModelReadQuery,
    ): List<StudentMistakeModelProblemSummary>
}

data class SaveIntentConfirmedStudentMistakeCommand(
    val problem: CommitStudentProblemCommand,
    val intentConfirmationId: String,
    val intentCanonicalFingerprint: String,
    val confirmedAtEpochMillis: Long,
    val favorite: Boolean = false,
) {
    init {
        require(problem.errorBookEntryId != null) {
            "Intent-confirmed mistake save requires an exact error-book entry id"
        }
        intentConfirmationId.requireStoreText("Save intent-confirmation id", MAX_ID_CHARS)
        requireSha256(intentCanonicalFingerprint, "Save intent fingerprint")
        require(confirmedAtEpochMillis in 0..problem.committedAtEpochMillis) {
            "Save intent confirmation must not occur after the commit"
        }
    }
}

data class SaveTargetConfirmedStudentMistakeCommand(
    val problem: CommitStudentProblemCommand,
    val confirmedAtEpochMillis: Long,
    val favorite: Boolean = false,
) {
    val targetCanonicalFingerprint: String =
        problem.confirmedTargetCanonicalFingerprint(favorite)

    init {
        require(problem.errorBookEntryId != null) {
            "Target-confirmed mistake save requires an exact error-book entry id"
        }
        require(confirmedAtEpochMillis in 0..problem.committedAtEpochMillis) {
            "Target confirmation must not occur after the commit"
        }
    }
}

enum class TargetConfirmedStudentMistakeSaveOutcome {
    CREATED,
    DUPLICATE,
}

data class TargetConfirmedStudentMistakeSaveReceipt(
    val outcome: TargetConfirmedStudentMistakeSaveOutcome,
    val problemRevision: StudentProblemRevisionRef,
    val errorBookEntryId: String,
    val targetCanonicalFingerprint: String,
) {
    init {
        errorBookEntryId.requireStoreText("Target save error-book entry id", MAX_ID_CHARS)
        requireSha256(targetCanonicalFingerprint, "Target save fingerprint")
    }
}

interface StudentMistakeMigrationPort : Closeable {
    suspend fun applyPage(
        command: ApplyStudentMistakeMigrationPageCommand,
    ): StudentMistakeMigrationReceipt

    suspend fun readCheckpoint(migrationId: String): StudentMistakeMigrationCheckpoint?
}

/**
 * Mistake-book business surface for the independent student-owned problem store.
 *
 * It deliberately exposes neither Room, DAO, SQL, mastery projections, nor knowledge-catalog
 * content. Cross-store delivery and acknowledgement are not part of this business capability.
 */
interface StudentMistakeStore : Closeable {
    /**
     * Commits an immutable revision for an already-owned aggregate or an internal import.
     * User-initiated first saves must use [saveConfirmedMistake].
     */
    suspend fun commitProblem(command: CommitStudentProblemCommand): StudentProblemDocument

    suspend fun saveConfirmedMistake(
        command: SaveIntentConfirmedStudentMistakeCommand,
    ): StudentProblemDocument

    suspend fun saveConfirmedMistake(
        command: SaveTargetConfirmedStudentMistakeCommand,
    ): TargetConfirmedStudentMistakeSaveReceipt

    suspend fun findProblem(ref: StudentProblemRef): StudentProblemDocument?

    suspend fun readMistakeDetail(
        query: StudentMistakeDetailQuery,
    ): StudentProblemDocument?

    suspend fun readRevisionHistory(
        query: StudentProblemRevisionHistoryQuery,
    ): StudentProblemRevisionHistoryPage

    suspend fun searchMistakes(query: StudentMistakeSearchQuery): StudentMistakeSearchPage

    fun observeChangeVersion(learnerId: String): Flow<Long>

    suspend fun setCollectionState(command: SetStudentProblemCollectionCommand)

    suspend fun setProblemLifecycle(command: SetStudentProblemLifecycleCommand)

    suspend fun recordClassifications(command: RecordStudentProblemClassificationsCommand)

    suspend fun readClassifications(
        revision: StudentProblemRevisionRef,
    ): List<StudentProblemClassificationResult>

    suspend fun readCurrentClassifications(
        revision: StudentProblemRevisionRef,
    ): List<StudentProblemClassificationResult>

    suspend fun upsertReviewCandidate(candidate: StudentReviewCandidate)

    suspend fun readReviewCandidates(
        query: StudentReviewCandidateQuery,
    ): StudentReviewCandidatePage

    suspend fun readReviewCandidatesWithKnowledge(
        query: StudentReviewCandidateQuery,
    ): StudentReviewCandidateWithKnowledgePage

    suspend fun storeReviewQueue(command: StoreStudentReviewQueueCommand)

    suspend fun readReviewQueue(
        learnerId: String,
        localDayEpochDay: Long,
    ): List<StudentReviewQueueItem>

    suspend fun readReviewPlanSnapshot(
        learnerId: String,
        localDayEpochDay: Long,
    ): StudentReviewPlanSnapshot?

}

private fun CommitStudentProblemCommand.confirmedTargetCanonicalFingerprint(
    favorite: Boolean,
): String {
    val ref = revision.problem
    return CanonicalSha256("student-mistake-confirmed-target-v1")
        .field("problemRevision", revision.canonicalFingerprint)
        .nullableField("title", title)
        .field("stemMarkdown", stemMarkdown)
        .field("practiceUnitKind", practiceUnitKind.name)
        .field("practiceUnitTitle", practiceUnitTitle)
        .field("itemFamilyId", itemFamilyId)
        .field("estimatedDurationSeconds", estimatedDurationSeconds)
        .nullableField("sourceBundleId", sourceBundleId)
        .fingerprintStrings("partId", partIds)
        .fingerprintStrings(
            "originalImage",
            originalImages.map(StudentProblemImageReference::confirmedTargetFingerprint),
        )
        .field("committedAtEpochMillis", committedAtEpochMillis)
        .field("errorBookEntryId", checkNotNull(errorBookEntryId))
        .nullableField(
            "capturedQuestionDocument",
            capturedQuestionDocument?.let(CapturedQuestionDocumentFingerprint::of),
        )
        .nullableField("solutionAnalysis", solutionAnalysis?.confirmedTargetFingerprint())
        .fingerprintStrings(
            "errorAttribution",
            errorAttributions.map(StudentProblemErrorAttribution::confirmedTargetFingerprint),
        )
        .field("collectionProblemId", ref.problemId)
        .field("collectionPracticeUnitId", ref.practiceUnitId)
        .field("collectionLearnerId", ref.learnerId)
        .field("collectionMistakeState", StudentMistakeEntryState.ACTIVE.name)
        .field("collectionFavorite", favorite)
        .field("collectionAddedAtEpochMillis", committedAtEpochMillis)
        .nullableField("collectionArchivedAtEpochMillis", null)
        .nullableField("collectionTrashedAtEpochMillis", null)
        .field("collectionChangedAtEpochMillis", committedAtEpochMillis)
        .field("candidateId", ref.practiceUnitId)
        .field("candidateLearnerId", ref.learnerId)
        .field("candidatePracticeUnitId", ref.practiceUnitId)
        .field("candidateBasisRevisionId", revision.revisionId)
        .fingerprintStrings("candidateReasonCode", listOf("saved-mistake"))
        .field("candidateItemFamilyId", itemFamilyId)
        .field("candidateEstimatedDurationSeconds", estimatedDurationSeconds)
        .field("candidateAvailableAtEpochMillis", committedAtEpochMillis)
        .nullableField("candidateDueAtEpochMillis", null)
        .nullableField("candidateSourceEvidenceEventKind", null)
        .nullableField("candidateSourceEvidenceEventId", null)
        .nullableField("candidateSourceEvidenceSequence", null)
        .nullableField("candidateSourceEvidenceCanonicalFingerprint", null)
        .field("candidateVersion", revision.revisionNumber.toLong())
        .field("candidateCreatedAtEpochMillis", committedAtEpochMillis)
        .field("candidateUpdatedAtEpochMillis", committedAtEpochMillis)
        .finish()
}

private fun StudentProblemImageReference.confirmedTargetFingerprint(): String =
    CanonicalSha256("student-mistake-confirmed-target-image-v1")
        .field("imageReferenceId", imageReferenceId)
        .field("localContentUri", localContentUri)
        .field("contentCanonicalFingerprint", contentCanonicalFingerprint)
        .field("mediaType", mediaType)
        .field("ordinal", ordinal)
        .nullableField("widthPixels", widthPixels?.toString())
        .nullableField("heightPixels", heightPixels?.toString())
        .nullableField("byteSize", byteSize?.toString())
        .fingerprintStrings(
            "selectedRegion",
            selectedRegions.map { region ->
                CanonicalSha256("student-mistake-confirmed-target-region-v1")
                    .field("left", region.left.canonicalDouble())
                    .field("top", region.top.canonicalDouble())
                    .field("right", region.right.canonicalDouble())
                    .field("bottom", region.bottom.canonicalDouble())
                    .finish()
            },
        )
        .finish()

private fun StudentProblemSolutionAnalysis.confirmedTargetFingerprint(): String =
    CanonicalSha256("student-mistake-confirmed-target-solution-v1")
        .field("solutionAnalysisId", solutionAnalysisId)
        .field("problemRevision", problemRevision.canonicalFingerprint)
        .field("summaryMarkdown", summaryMarkdown)
        .nullableField("finalAnswerMarkdown", finalAnswerMarkdown)
        .fingerprintStrings(
            "step",
            steps.map { step ->
                CanonicalSha256("student-mistake-confirmed-target-solution-step-v1")
                    .field("stepId", step.stepId)
                    .field("ordinal", step.ordinal)
                    .field("summaryMarkdown", step.summaryMarkdown)
                    .field("reasoningMarkdown", step.reasoningMarkdown)
                    .nullableField("resultMarkdown", step.resultMarkdown)
                    .field("stepCanonicalFingerprint", step.stepCanonicalFingerprint)
                    .finish()
            },
        )
        .field("modelProviderId", modelProviderId)
        .field("modelId", modelId)
        .field("analyzerVersion", analyzerVersion)
        .field("resultCanonicalFingerprint", resultCanonicalFingerprint)
        .field("recordedAtEpochMillis", recordedAtEpochMillis)
        .finish()

private fun StudentProblemErrorAttribution.confirmedTargetFingerprint(): String =
    CanonicalSha256("student-mistake-confirmed-target-attribution-v1")
        .field("attributionId", attributionId)
        .field("problemRevision", problemRevision.canonicalFingerprint)
        .nullableField("solutionAnalysisId", solutionAnalysisId)
        .field("resolutionStatus", resolutionStatus.name)
        .field("rationaleMarkdown", rationaleMarkdown)
        .nullableField("stepOrdinal", stepOrdinal?.toString())
        .nullableField("atomicReferenceId", atomicReferenceId)
        .fingerprintStrings(
            "evidence",
            evidenceRefs.map { evidence ->
                CanonicalSha256("student-mistake-confirmed-target-evidence-v1")
                    .field("blockId", evidence.blockId)
                    .field("sourceAssetId", evidence.sourceAssetId)
                    .field("evidenceKind", evidence.evidenceKind.name)
                    .finish()
            },
        )
        .field("modelProviderId", modelProviderId)
        .field("modelId", modelId)
        .field("analyzerVersion", analyzerVersion)
        .field("resultCanonicalFingerprint", resultCanonicalFingerprint)
        .field("recordedAtEpochMillis", recordedAtEpochMillis)
        .finish()

private fun CanonicalSha256.fingerprintStrings(
    name: String,
    values: List<String>,
): CanonicalSha256 =
    apply {
        field("${name}Count", values.size)
        values.forEachIndexed { index, value -> field("$name[$index]", value) }
    }

private fun Double.canonicalDouble(): String = java.lang.Double.toHexString(this)

internal fun StudentReviewQueueState.canTransitionTo(
    nextState: StudentReviewQueueState,
): Boolean =
    this == nextState ||
        when (this) {
            StudentReviewQueueState.READY ->
                nextState == StudentReviewQueueState.PRESENTED ||
                    nextState == StudentReviewQueueState.SKIPPED ||
                    nextState == StudentReviewQueueState.REMOVED

            StudentReviewQueueState.PRESENTED ->
                nextState == StudentReviewQueueState.SKIPPED ||
                    nextState == StudentReviewQueueState.REMOVED

            StudentReviewQueueState.COMPLETED,
            StudentReviewQueueState.SKIPPED,
            StudentReviewQueueState.REMOVED,
            -> false
        }

internal fun String.requireStoreText(label: String, maxChars: Int) {
    require(
        isNotBlank() &&
            this == trim() &&
            length <= maxChars &&
            none { it.isISOControl() && it != '\n' && it != '\t' },
    ) {
        "$label is invalid"
    }
}

internal fun requireSha256(value: String, label: String) {
    require(SHA_256.matches(value)) { "$label must be a lowercase SHA-256 value" }
}

internal fun SubjectKind.requireHighSchoolSubject() {
    require(this != SubjectKind.GENERAL) {
        "Student mistake documents require a specific high-school subject"
    }
}

private val SHA_256 = Regex("[0-9a-f]{64}")
internal const val MAX_ID_CHARS = 256
internal const val MAX_VERSION_CHARS = 128
private const val MAX_MEDIA_TYPE_CHARS = 128
internal const val MAX_URI_CHARS = 4_096
internal const val MAX_LABEL_CHARS = 1_024
private const val MAX_SEARCH_CHARS = 256
internal const val MAX_DOCUMENT_CHARS = 256_000
private const val MAX_ANALYSIS_CHARS = 16_000
internal const val MAX_ESTIMATED_DURATION_SECONDS = 7_200
private const val MAX_REVIEW_BUDGET_SECONDS = 8 * 60 * 60
private const val MAX_PARTS = 128
private const val MAX_IMAGES = 64
private const val MAX_IMAGE_DIMENSION_PIXELS = 100_000
private const val MAX_IMAGE_BYTE_SIZE = 512L * 1024 * 1024
private const val MAX_SELECTED_REGIONS_PER_IMAGE = 64
private const val MAX_SOLUTION_STEPS = 128
private const val MAX_ERROR_ATTRIBUTIONS = 64
private const val MAX_CLASSIFICATIONS = 512
private const val MAX_REVIEW_REASONS = 16
private const val MAX_REASON_CHARS = 128
private const val MAX_REVIEW_ITEMS = 512
const val DEFAULT_PAGE_SIZE = 30
const val MAX_PAGE_SIZE = 100
const val DEFAULT_MESSAGE_BATCH_SIZE = 50
const val MAX_MESSAGE_BATCH_SIZE = 500
const val MAX_MIGRATION_PAGE_SIZE = 50
const val DEFAULT_MODEL_READ_PAGE_SIZE = 10
const val MAX_MODEL_READ_PAGE_SIZE = 20
const val MAX_MODEL_READ_KNOWLEDGE_REFS = 8

private fun NormalizedSourceRegion.isValidStoreRegion(): Boolean =
    left.isFinite() &&
        top.isFinite() &&
        right.isFinite() &&
        bottom.isFinite() &&
        left in 0.0..1.0 &&
        top in 0.0..1.0 &&
        right in 0.0..1.0 &&
        bottom in 0.0..1.0 &&
        left < right &&
        top < bottom
