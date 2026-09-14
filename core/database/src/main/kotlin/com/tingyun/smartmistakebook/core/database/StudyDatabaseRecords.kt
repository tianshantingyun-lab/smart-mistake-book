package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.IncrementalLearningEvent
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningLedgerEvent
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.TutorMoveType

/**
 * Command/record types crossing the [StudyDatabasePort] boundary, one file so
 * that a new field has exactly one place to be wired and a missed mapping is a
 * compile error here rather than a silently nulled column. Grouping follows
 * the port/ sub-interfaces (library, tutor, capture, draft, learning, review).
 */

/** Page-local normalized region persisted by the split-import ledger. */
internal data class TrackedSourceRegion(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

/**
 * Split-import ingestion command. At most one job may own one source
 * fingerprint so replays stay idempotent.
 */
data class CreateSplitImportJobCommand(
    val jobId: String,
    val sourceKind: String,
    val sourceFingerprint: String,
    val sourceUri: String,
    val pageCount: Int,
    val createdAtEpochMillis: Long,
) {
    init {
        require(jobId.isNotBlank()) { "Split job id must not be blank" }
        require(sourceKind.isNotBlank()) { "Split job source kind must not be blank" }
        require(sourceFingerprint.isNotEmpty()) { "Split job source fingerprint must not be blank" }
        require(sourceUri.isNotBlank()) { "Split job source image uri must not be blank" }
        require(pageCount >= 1) { "Split job page count must be positive" }
        require(createdAtEpochMillis >= 0) { "Split job creation time must not be negative" }
    }
}

/** One cut piece of a split-import source, in reading order. */
data class SplitImportQuestionSeed(
    private val left: Double,
    private val top: Double,
    private val right: Double,
    private val bottom: Double,
    val pageIndex: Int,
    val prioritised: Boolean = false,
    /** Problem draft pre-created for this region, so review can open it on confirm. */
    val splitDraftId: String? = null,
) {
    init {
        require(pageIndex >= 0) { "Split question page index must not be negative" }
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Split region must be finite"
        }
        require(left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0) {
            "Split region must stay inside the normalized page"
        }
        require(left < right && top < bottom) { "Split region must have positive extent" }
    }

    fun regionLeft(): Double = left

    fun regionTop(): Double = top

    fun regionRight(): Double = right

    fun regionBottom(): Double = bottom
}

data class SplitImportQuestionRecord(
    val jobId: String,
    val questionOrdinal: Int,
    val pageIndex: Int,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val selected: Boolean,
    val confirmState: String,
    val splitDraftId: String?,
)

data class SplitImportJobRecord(
    val jobId: String,
    val sourceKind: String,
    val sourceFingerprint: String,
    val sourceUri: String,
    val pageCount: Int,
    val questionCount: Int,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val questions: List<SplitImportQuestionRecord> = emptyList(),
) {
    init {
        require(jobId.isNotBlank()) { "Split job id must not be blank" }
        require(sourceKind.isNotBlank()) { "Split job source kind must not be blank" }
        require(sourceUri.isNotBlank()) { "Split job source image uri must not be blank" }
        require(pageCount >= 1) { "Split job page count must be positive" }
        require(questionCount >= 0) { "Split job question count must not be negative" }
        require(createdAtEpochMillis >= 0 && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Split job times are invalid"
        }
    }

    val readyForReview: Boolean get() = status == StudyDbValue.SplitImportStatus.READY ||
        status == StudyDbValue.SplitImportStatus.PREPARING
}

data class ProblemSeedRecord(
    val problemId: String,
    val canonicalFingerprint: String,
    val subject: String,
    val createdAtEpochMillis: Long,
)

data class ProblemRevisionSeedRecord(
    val revisionId: String,
    val problemId: String,
    val revisionNumber: Int,
    val title: String,
    val problemMarkdown: String,
    val questionDocumentSnapshot: String? = null,
    val answerSpecId: String?,
    val answerSpecSnapshot: String?,
    val answerVerificationStatus: String,
    val sourceType: String,
    val sourceReference: String?,
    val contentFingerprint: String,
    val createdAtEpochMillis: Long,
)

data class PracticeUnitSeedRecord(
    val practiceUnitId: String,
    val problemId: String,
    val problemRevisionId: String,
    val unitKey: String,
    val unitKind: String,
    val title: String,
    val promptMarkdown: String,
    val estimatedSeconds: Int,
    val createdAtEpochMillis: Long,
)

data class ErrorBookEntrySeedRecord(
    val entryId: String,
    val practiceUnitId: String,
    val problemId: String,
    val currentRevisionId: String,
    val sourceKey: String?,
    val status: String = StudyDbValue.ErrorBookStatus.ACTIVE,
    val acceptedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class KnowledgeNodeSeedRecord(
    val knowledgeNodeId: String,
    val stableCode: String,
    val subject: String,
    val displayName: String,
    val parentKnowledgeNodeId: String?,
    val taxonomyVersion: String,
    val createdAtEpochMillis: Long,
    val canonicalName: String = displayName,
    val nodeKind: String = "TOPIC",
    val granularity: String = "TOPIC",
    val aliases: Set<String> = emptySet(),
    val boundaryMarkdown: String? = null,
    val verificationStatus: String = "MODEL_CANDIDATE",
)

data class KnowledgeBindingSeedRecord(
    val bindingId: String,
    val practiceUnitId: String,
    val knowledgeNodeId: String,
    val basisRevisionId: String,
    val strength: Double,
    val sourceType: String,
    val taxonomyVersion: String,
    val acceptedAtEpochMillis: Long,
)

data class KnowledgeSourceSeedRecord(
    val sourceId: String,
    val subject: String,
    val sourceType: String,
    val title: String,
    val publisher: String?,
    val edition: String?,
    val sourceUri: String?,
    val licenseStatus: String,
    val contentFingerprint: String,
    val importedAtEpochMillis: Long,
    val contentUsePolicy: String = "REVIEWED_SYNTHESIS_ONLY",
    val licenseExpression: String? = null,
    val licenseUri: String? = null,
    val attributionText: String? = null,
)

data class KnowledgeNodeSourceBindingSeedRecord(
    val knowledgeNodeId: String,
    val sourceId: String,
    val sourceLocator: String,
    val derivationNote: String,
    val reviewedAtEpochMillis: Long?,
)

data class KnowledgeNodeRelationRecord(
    val relationId: String,
    val subject: String,
    val prerequisiteKnowledgeNodeId: String,
    val dependentKnowledgeNodeId: String,
    val relationType: String,
    val sourceId: String,
    val sourceLocator: String,
    val reviewedAtEpochMillis: Long,
)

/**
 * Reviewed explanatory content for an existing knowledge point.
 *
 * A material deliberately has no problem id, answer key, difficulty, scheduling metadata, or
 * scoring contract. It may contain a worked example inside [contentMarkdown], but it can only be
 * retrieved as teaching context for a question the learner already supplied.
 */
data class KnowledgeTeachingMaterialRecord(
    val materialId: String,
    val stableCode: String,
    val subject: String,
    val materialType: String,
    val title: String,
    val summaryMarkdown: String,
    val applicabilityMarkdown: String,
    val contentMarkdown: String,
    val boundaryMarkdown: String,
    val derivationKind: String,
    val sourceId: String,
    val sourceLocator: String,
    val contentFingerprint: String,
    val reviewedAtEpochMillis: Long,
)

data class KnowledgeTeachingMaterialNodeBindingRecord(
    val materialId: String,
    val knowledgeNodeId: String,
    val role: String,
)

data class KnowledgeGroundingRequestRecord(
    val groundingRequestId: String,
    val groundingKey: String,
    val organizationRequestId: String,
    val organizationRequestFingerprint: String,
    val requestOrdinal: Int,
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val subject: String,
    val query: String,
    val expectedParentKnowledgeDisplayName: String,
    val reasonMarkdown: String,
    val status: String = StudyDbValue.KnowledgeGroundingStatus.PENDING,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** One quiet, student-facing summary of repeated unresolved ontology grounding requests. */
data class KnowledgeGroundingSummaryRecord(
    val groundingKey: String,
    val subject: String,
    val expectedParentKnowledgeDisplayName: String,
    val query: String,
    val relatedQuestionCount: Int,
    val firstObservedAtEpochMillis: Long,
    val lastObservedAtEpochMillis: Long,
)

data class KnowledgeResearchReviewSourceRecord(
    val sourceOrdinal: Int,
    val canonicalSourceUri: String,
    val title: String,
    val publisher: String?,
    val sourceType: String,
    val licenseStatus: String,
    val searchRank: Int,
    val contentType: String,
    val contentLengthBytes: Long,
    val contentFingerprint: String,
    val verifiedAtEpochMillis: Long,
)

data class KnowledgeResearchReviewBundleRecord(
    val bundleId: String,
    val groundingKey: String,
    val subject: String,
    val query: String,
    val expectedParentKnowledgeDisplayName: String,
    val relatedQuestionCount: Int,
    val workflowVersion: String,
    val status: String = StudyDbValue.KnowledgeResearchReviewStatus.PENDING_REVIEW,
    val reviewerReference: String? = null,
    val decisionNote: String? = null,
    val reviewedAtEpochMillis: Long? = null,
    val appliedPackFingerprint: String? = null,
    val appliedAtEpochMillis: Long? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val sources: List<KnowledgeResearchReviewSourceRecord>,
)

data class DecideKnowledgeResearchReviewBundleCommand(
    val bundleId: String,
    val decisionStatus: String,
    val reviewerReference: String,
    val decisionNote: String,
    val decidedAtEpochMillis: Long,
)

data class ApplyApprovedKnowledgeResearchPackCommand(
    val reviewBundleId: String,
    val pack: ApplyReviewedKnowledgePackCommand,
    val appliedAtEpochMillis: Long,
)

data class ResolveKnowledgeGroundingCommand(
    val groundingKey: String,
    val subject: String,
    val knowledgeNodeId: String,
    val resolvedAtEpochMillis: Long,
)

data class ApplyReviewedKnowledgePackCommand(
    val sources: List<KnowledgeSourceSeedRecord>,
    val nodes: List<KnowledgeNodeSeedRecord>,
    val bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    val relations: List<KnowledgeNodeRelationRecord> = emptyList(),
    val resolutions: List<ResolveKnowledgeGroundingCommand>,
)

data class KnowledgeGroundingResolutionRecord(
    val resolutionId: String,
    val groundingKey: String,
    val subject: String,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
    val resolvedOccurrenceCount: Int,
    val linkedPracticeUnitCount: Int,
    val resolvedAtEpochMillis: Long,
)

data class ReviewedKnowledgeCoverageRecord(
    val subject: String,
    val topicCount: Int,
    val atomicKnowledgeCount: Int,
    val reviewedSourceCount: Int,
    val latestReviewedAtEpochMillis: Long,
)

data class ProblemRelationSeedRecord(
    val relationId: String,
    val sourceProblemId: String,
    val targetProblemId: String,
    val relationType: String,
    val status: String,
    val sourceBasisRevisionId: String,
    val targetBasisRevisionId: String,
    val confidence: Double,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class ProblemClassificationBindingRecord(
    val bindingId: String,
    val problemId: String,
    val basisRevisionId: String,
    val dimension: String,
    val labelId: String,
    val displayName: String,
    val taxonomyVersion: String,
    val acceptanceSource: String,
    val acceptedAtEpochMillis: Long,
)

data class ConfirmProblemOrganizationCommand(
    val commandId: String,
    val payloadFingerprint: String,
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val knowledgeNodes: List<KnowledgeNodeSeedRecord>,
    val knowledgeBindings: List<KnowledgeBindingSeedRecord>,
    val classifications: List<ProblemClassificationBindingRecord>,
    val relations: List<ProblemRelationSeedRecord>,
    val acceptedAtEpochMillis: Long,
    /** Exact outgoing relation ids explicitly removed by the user; unrelated rows are preserved. */
    val relationIdsToRemove: Set<String> = emptySet(),
    /** False merges supplied relations and preserves all existing ones; true replaces the set. */
    val replaceRelations: Boolean = false,
)

data class ProblemOrganizationReceiptRecord(
    val commandId: String,
    val payloadFingerprint: String,
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val classificationCount: Int,
    val relationCount: Int,
    val acceptedAtEpochMillis: Long,
)

data class ConfirmProblemOrganizationResult(
    val created: Boolean,
    val receipt: ProblemOrganizationReceiptRecord,
)

data class ConfirmedProblemOrganizationRecord(
    val classifications: List<ProblemClassificationBindingRecord>,
    val relations: List<ProblemRelationSeedRecord>,
    val knowledgeNodeIds: Set<String> = emptySet(),
)

/** Immutable presentation snapshot used by the teaching UI, distinct from learning evidence. */
data class AssessmentItemSnapshotSeedRecord(
    val assessmentItemSnapshotId: String,
    val itemRevision: Int,
    val practiceUnitId: String?,
    val problemRevisionId: String?,
    val tutorContentSnapshotId: String?,
    val promptMarkdown: String,
    val optionsSnapshot: String,
    val answerSpecSnapshot: String,
    val verificationStatus: String,
    val assessmentEligibility: String,
    val scoringMode: String,
    val learnerSnapshotVersion: String,
    val projectionCheckpoint: Long,
    val hintLevelAtPresentation: Int,
    val answerRevealState: String,
    val createdAtEpochMillis: Long,
)

data class AssessmentEventSeedRecord(
    val assessmentEventId: String,
    val assessmentItemSnapshotId: String,
    val eventSequence: Long,
    val eventType: String,
    val hintLevel: Int?,
    val submittedResponse: String?,
    val occurredAtEpochMillis: Long,
)

/** Legacy fixture projection used by mistake-list previews. Learning-core v2 uses LearnerSnapshot. */
data class ProblemMemoryStateRecord(
    val practiceUnitId: String,
    val stabilityDays: Double,
    val difficulty: Double,
    val lastReviewedAtEpochMillis: Long?,
    val nextReviewAtEpochMillis: Long,
    val reviewCount: Int,
    val lapseCount: Int,
    val retrievability: Double,
    val projectionCheckpoint: Long,
    val projectorVersion: String,
    val updatedAtEpochMillis: Long,
)

/** Legacy fixture projection used by pre-v2 seed data. */
data class KnowledgeMasteryStateRecord(
    val knowledgeNodeId: String,
    val masteryProbability: Double,
    val independentCorrectCount: Int,
    val assistedCorrectCount: Int,
    val incorrectCount: Int,
    val evidenceWeightTotal: Double,
    val lastEvidenceAtEpochMillis: Long?,
    val projectionCheckpoint: Long,
    val projectorVersion: String,
    val updatedAtEpochMillis: Long,
)

data class ReviewPlanRecord(
    val reviewPlanId: String,
    val learnerId: String,
    val localDate: String,
    val localDayEpochDay: Long,
    val timeZoneId: String,
    val timeBudgetSeconds: Int,
    val planningAtEpochMillis: Long,
    val status: String,
    val plannerVersion: String,
    val projectionCheckpoint: Long,
    val inputFingerprint: String,
    val planFingerprint: String,
    val planRevision: Int,
    val createdAtEpochMillis: Long,
)

data class ReviewQueueItemRecord(
    val reviewQueueItemId: String,
    val reviewPlanId: String,
    val practiceUnitId: String,
    val knowledgeNodeIds: Set<String>,
    val itemFamilyId: String,
    val sourceBundleId: String?,
    val reasons: Set<String>,
    val ordinal: Int,
    val priorityScore: Double,
    val difficultyBand: String,
    val dueAtEpochMillis: Long?,
    val estimatedSeconds: Int,
    val reasonSnapshot: String,
    val status: String = StudyDbValue.ReviewStatus.PLANNED,
)

data class ReviewSessionRecord(
    val reviewSessionId: String,
    val reviewPlanId: String,
    val status: String,
    val startedAtEpochMillis: Long,
    val lastActiveAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val currentOrdinal: Int,
    val timeBudgetSeconds: Int,
    val projectionCheckpoint: Long,
    /** Zero for creation; each persisted progress transition increments by exactly one. */
    val stateVersion: Long = 0,
)

data class ReviewSessionAdvanceCommand(
    val sessionId: String,
    val expectedStateVersion: Long,
    val reviewQueueItemId: String,
    val practiceUnitId: String,
    val attemptId: String,
    val submissionId: String,
    val presentationId: String,
    val occurredAtEpochMillis: Long,
)

/** Immutable database authority for one accepted review-session transition. */
data class ReviewSessionAdvanceReceipt(
    val sessionId: String,
    val fromVersion: Long,
    val toVersion: Long,
    val reviewQueueItemId: String,
    val practiceUnitId: String,
    val attemptId: String,
    val submissionId: String,
    val presentationId: String,
    val occurredAtEpochMillis: Long,
)

data class ReviewSessionAdvanceResult(
    val created: Boolean,
    val session: ReviewSessionRecord,
    val receipt: ReviewSessionAdvanceReceipt,
)

data class StudySeedBundle(
    val problems: List<ProblemSeedRecord>,
    val revisions: List<ProblemRevisionSeedRecord>,
    val practiceUnits: List<PracticeUnitSeedRecord>,
    val errorBookEntries: List<ErrorBookEntrySeedRecord>,
    val knowledgeNodes: List<KnowledgeNodeSeedRecord> = emptyList(),
    val knowledgeBindings: List<KnowledgeBindingSeedRecord> = emptyList(),
    val relations: List<ProblemRelationSeedRecord> = emptyList(),
    val assessmentItems: List<AssessmentItemSnapshotSeedRecord> = emptyList(),
    val assessmentEvents: List<AssessmentEventSeedRecord> = emptyList(),
    val problemMemoryStates: List<ProblemMemoryStateRecord> = emptyList(),
    val knowledgeMasteryStates: List<KnowledgeMasteryStateRecord> = emptyList(),
    val reviewPlans: List<ReviewPlanRecord> = emptyList(),
    val reviewQueueItems: List<ReviewQueueItemRecord> = emptyList(),
    val reviewSessions: List<ReviewSessionRecord> = emptyList(),
)

data class SeedResult(
    val insertedProblemCount: Int,
    val insertedErrorBookEntryCount: Int,
)

data class MistakeRecord(
    val entryId: String,
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val sourceKey: String?,
    val subject: String,
    val title: String,
    val problemMarkdown: String,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
    val nextReviewAtEpochMillis: Long?,
    val retrievability: Double?,
    val estimatedSeconds: Int = 180,
    val knowledgeNodeIds: Set<String> = emptySet(),
    val chapterLabels: List<String> = emptyList(),
    val knowledgeLabels: List<String> = emptyList(),
    val captureOccurrenceCount: Int = 1,
) {
    init {
        require(estimatedSeconds > 0) { "Mistake estimated duration must be positive" }
        require(captureOccurrenceCount >= 1) {
            "Mistake capture occurrence count must be positive"
        }
        require(knowledgeNodeIds.none(String::isBlank)) {
            "Mistake knowledge-node ids must not be blank"
        }
    }
}

data class LibraryCatalogRow(
    val entryId: String,
    val title: String,
    val problemMarkdown: String,
    val subject: String,
    val chapterLabels: List<String> = emptyList(),
    val knowledgeLabels: List<String> = emptyList(),
    val masteryId: String = "unknown",
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val nextReviewAtEpochMillis: Long?,
    val retrievability: Double?,
) {
    init {
        require(entryId.isNotBlank()) { "Library row entry id must not be blank" }
        require(title.isNotBlank()) { "Library row title must not be blank" }
        require(subject.isNotBlank()) { "Library row subject must not be blank" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Library row update time cannot precede creation"
        }
    }
}

data class LibraryFacetCountRecord(
    val id: String,
    val label: String,
    val count: Int,
) {
    init {
        require(id.isNotBlank() && label.isNotBlank()) {
            "Library facet row must have an id and label"
        }
        require(count >= 0) { "Library facet row count must not be negative" }
    }
}

data class MistakeDetailRecord(
    val entryId: String,
    val problemId: String,
    val problemRevisionId: String,
    val revisionNumber: Int,
    val subject: String,
    val title: String,
    val problemMarkdown: String,
    val questionDocumentSnapshot: String?,
    val contentFingerprint: String,
    val sourceAssets: List<MistakeDetailSourceAssetRecord>,
    val tutorSessionId: String? = null,
    val tutorQuestionRevisionNumber: Int? = null,
    val practiceUnitId: String = "legacy-practice-unit",
    /** Learner's private note on the entry; never leaves the device in a model egress payload. */
    val userNote: String? = null,
    /** True when the entry has been archived (hidden from surfaces, history preserved). */
    val archived: Boolean = false,
)

data class MistakeDetailSourceAssetRecord(
    val role: String,
    val sourceAsset: CanonicalSourceAssetRecord,
)

data class MistakeRevisionSummaryRecord(
    val entryId: String,
    val problemId: String,
    val problemRevisionId: String,
    val revisionNumber: Int,
    val title: String,
    val createdAtEpochMillis: Long,
    val isCurrent: Boolean,
)

data class CanonicalSourceAssetRecord(
    val sourceAssetId: String,
    val contentSha256: String,
    /** Relative to the app-private canonical asset root; absolute paths are forbidden. */
    val relativePath: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val sourceType: String,
    val createdAtEpochMillis: Long,
)

data class ProblemDraftRevisionRecord(
    val draftId: String,
    val revisionNumber: Int,
    val basisRevisionNumber: Int?,
    val subject: String?,
    val title: String,
    val questionDocument: CapturedQuestionDocument,
    val documentFingerprint: String,
    val author: String,
    val createdAtEpochMillis: Long,
)

data class ProblemDraftRecord(
    val draftId: String,
    val sourceAsset: CanonicalSourceAssetRecord,
    val sourceAssets: List<ProblemDraftSourceAssetRecord> = listOf(
        ProblemDraftSourceAssetRecord(pageIndex = 0, sourceAsset = sourceAsset),
    ),
    val origin: String,
    val status: String,
    val currentRevision: ProblemDraftRevisionRecord,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val requestFingerprint: String? = null,
) {
    init {
        require(sourceAssets.isNotEmpty()) { "A problem draft requires at least one source asset" }
        require(sourceAssets.map { it.pageIndex } == sourceAssets.indices.toList()) {
            "Problem draft source pages must be ordered and contiguous from zero"
        }
        require(sourceAssets.map { it.sourceAsset.sourceAssetId }.distinct().size == sourceAssets.size) {
            "Problem draft source assets must be unique"
        }
        require(sourceAssets.first().sourceAsset == sourceAsset) {
            "Problem draft primary source must be page zero"
        }
    }
}

data class ProblemDraftSourceAssetRecord(
    val pageIndex: Int,
    val sourceAsset: CanonicalSourceAssetRecord,
) {
    init {
        require(pageIndex >= 0) { "Problem draft source page index must not be negative" }
    }
}

data class ProblemDraftEditWorkspaceRecord(
    val draftId: String,
    val basisRevisionNumber: Int,
    val workspaceVersion: Long,
    val snapshotSchemaVersion: Int,
    val workspaceSnapshot: String,
    val workspaceFingerprint: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class SaveProblemDraftEditWorkspaceCommand(
    val draftId: String,
    val basisRevisionNumber: Int,
    val expectedWorkspaceVersion: Long,
    val expectedWorkspaceFingerprint: String?,
    val snapshotSchemaVersion: Int,
    val workspaceSnapshot: String,
    val workspaceFingerprint: String,
    val updatedAtEpochMillis: Long,
)

data class ProblemDraftEditWorkspaceWriteResult(
    val created: Boolean,
    val workspace: ProblemDraftEditWorkspaceRecord,
)

data class ConsumeProblemDraftEditWorkspaceCommand(
    val draftId: String,
    val basisRevisionNumber: Int,
    val expectedWorkspaceVersion: Long,
    val expectedWorkspaceFingerprint: String,
)

data class ExpectedProblemDraftEditWorkspace(
    val draftId: String,
    val basisRevisionNumber: Int,
    val workspaceVersion: Long,
    val workspaceFingerprint: String,
    val finalRequestId: String,
    val finalOccurredAtEpochMillis: Long,
)

data class CreateProblemDraftCommand(
    val sourceAsset: CanonicalSourceAssetRecord,
    val draftId: String,
    val origin: String,
    val initialRevision: ProblemDraftRevisionRecord,
    val requestFingerprint: String? = null,
)

data class ProblemDraftWriteResult(
    val created: Boolean,
    val draft: ProblemDraftRecord,
)

data class AppendProblemDraftSourceAssetCommand(
    val draftId: String,
    val expectedRevisionNumber: Int,
    val expectedSourceAssetCount: Int,
    val sourceAsset: CanonicalSourceAssetRecord,
    val appendedAtEpochMillis: Long,
)

data class AppendProblemDraftSourceAssetResult(
    val created: Boolean,
    val draft: ProblemDraftRecord,
)

data class ReplaceProblemDraftCommand(
    val replacedDraftId: String,
    val expectedReplacedRevisionNumber: Int,
    val replacement: CreateProblemDraftCommand,
    val replacedAtEpochMillis: Long,
)

data class ProblemDraftReplacementResult(
    val created: Boolean,
    val replacement: ProblemDraftRecord,
)

data class SplitProblemDraftCommand(
    val replacedDraftId: String,
    val expectedReplacedRevisionNumber: Int,
    val replacements: List<CreateProblemDraftCommand>,
    val splitAtEpochMillis: Long,
)

data class ProblemDraftSplitResult(
    val created: Boolean,
    val replacements: List<ProblemDraftRecord>,
)

data class ReviseProblemDraftCommand(
    val draftId: String,
    val expectedRevisionNumber: Int,
    val revision: ProblemDraftRevisionRecord,
)

data class CommitProblemDraftCommand(
    val commandId: String,
    val draftId: String,
    val expectedRevisionNumber: Int,
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val errorBookEntryId: String,
    val estimatedSeconds: Int,
    val committedAtEpochMillis: Long,
)

data class ProblemDraftCommitReceipt(
    val commandId: String,
    val payloadFingerprint: String,
    val draftId: String,
    val draftRevisionNumber: Int,
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val errorBookEntryId: String,
    val committedAtEpochMillis: Long,
)

data class CommitProblemDraftResult(
    val created: Boolean,
    val receipt: ProblemDraftCommitReceipt,
)

data class ConfirmAndCommitProblemDraftFromWorkspaceCommand(
    val workspace: ExpectedProblemDraftEditWorkspace,
    val commit: CommitProblemDraftCommand,
)

data class ConfirmTutorSessionCommand(
    val sessionId: String,
    val draftId: String,
    val expectedRevisionNumber: Int,
    val confirmedRevision: ProblemDraftRevisionRecord,
    val createdAtEpochMillis: Long,
)

data class ConfirmTutorSessionFromWorkspaceCommand(
    val workspace: ExpectedProblemDraftEditWorkspace,
    val sessionId: String,
)

data class TutorSessionRecord(
    val sessionId: String,
    val draftId: String,
    val draftRevisionNumber: Int,
    val createdAtEpochMillis: Long,
    val origin: String,
    val draftStatus: String,
    val confirmedRevision: ProblemDraftRevisionRecord,
    val sourceAsset: CanonicalSourceAssetRecord,
    val commitReceipt: ProblemDraftCommitReceipt?,
)

data class TutorSessionWriteResult(
    val created: Boolean,
    val session: TutorSessionRecord,
)

data class CommitTutorSessionCommand(
    val sessionId: String,
    val commit: CommitProblemDraftCommand,
)

data class EndTutorSessionCommand(
    val sessionId: String,
    val endedAtEpochMillis: Long,
)

data class EndTutorSessionResult(
    val sessionId: String,
    val draftId: String,
    val endedAtEpochMillis: Long,
    val created: Boolean,
)

data class TutorTurnResponseRecord(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val diagnosticStemMarkdown: String?,
    val selectedChoiceId: String?,
    val selectedChoiceMarkdown: String?,
    val selectionWasCorrect: Boolean?,
    val feedbackMarkdown: String?,
    val requestedMove: String?,
    val solutionRevealed: Boolean,
    val choiceSubmittedAtEpochMillis: Long?,
    val submittedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class PersistTutorChoiceCommand(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val diagnosticStemMarkdown: String,
    val selectedChoiceId: String,
    val selectedChoiceMarkdown: String,
    val selectionWasCorrect: Boolean,
    val feedbackMarkdown: String,
    val choiceSubmittedAtEpochMillis: Long,
) {
    init {
        require(choiceSubmittedAtEpochMillis >= 0)
    }
}

data class PersistTutorMoveCommand(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val requestedMove: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0)
        require(occurredAtEpochMillis >= 0)
        val move = runCatching { TutorMoveType.valueOf(requestedMove) }.getOrNull()
        require(move != null && move != TutorMoveType.REVEAL_SOLUTION) {
            "Persisted tutor moves must be a supported non-reveal move"
        }
    }
}

data class PersistTutorRevealCommand(
    val learnerId: String = "learner:local",
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(learnerId.isNotBlank() && sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0)
        require(occurredAtEpochMillis >= 0)
    }
}

data class PersistTutorAnswerExposureCommand(
    val learnerId: String = "learner:local",
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val surfaceKind: String,
    val modelTaskRequestId: String,
    val responseOrdinal: Int? = null,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(learnerId.isNotBlank() && sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0)
        require(modelTaskRequestId.isNotBlank())
        require(
            surfaceKind == "PLAN_SOLUTION" && responseOrdinal == null ||
                surfaceKind == "RESPOND_REPLY" && responseOrdinal != null && responseOrdinal > 0,
        ) { "Persisted tutor answer surface identity is inconsistent" }
        require(occurredAtEpochMillis >= 0)
    }
}

data class PersistTutorSessionAnchorCommand(
    val learnerId: String = "learner:local",
    val sessionId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val source: String,
    val anchoredAtEpochMillis: Long,
) {
    init {
        require(learnerId.isNotBlank() && sessionId.isNotBlank())
        require(problemRevisionId.isNotBlank() && practiceUnitId.isNotBlank())
        require(source == "DRAFT_COMMIT" || source == "SAVED_MISTAKE")
        require(anchoredAtEpochMillis >= 0)
    }
}

data class TutorSessionProblemAnchorRecord(
    val learnerId: String,
    val sessionId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val source: String,
    val anchoredAtEpochMillis: Long,
)

data class TutorAnswerExposureRecord(
    val exposureId: String,
    val learnerId: String,
    val sessionId: String,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val surfaceKind: String,
    val modelTaskRequestId: String?,
    val responseOrdinal: Int?,
    val exposedAtEpochMillis: Long,
    val outcomeId: String?,
)

data class PendingCaptureDraftRecord(
    val draft: ProblemDraftRecord,
    val editWorkspace: ProblemDraftEditWorkspaceRecord?,
    val latestAssessmentTask: ModelTaskSnapshot?,
    val assessmentTasks: List<ModelTaskSnapshot> = listOfNotNull(latestAssessmentTask),
    val latestParseTask: ModelTaskSnapshot?,
    val tutorSessionId: String?,
    val tutorSessionDraftRevisionNumber: Int?,
)

data class BatchImportPageRecord(
    val jobId: String,
    val pageIndex: Int,
    val sourceUri: String,
    val status: String,
    val resultDraftId: String?,
    val failureCode: String?,
    val attemptCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val boundaryAfterStatus: String,
)

data class BatchImportJobRecord(
    val jobId: String,
    val requestId: String,
    val requestFingerprint: String,
    val status: String,
    val pages: List<BatchImportPageRecord>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class CreateBatchImportJobCommand(
    val jobId: String,
    val requestId: String,
    val requestFingerprint: String,
    val sourceUris: List<String>,
    val occurredAtEpochMillis: Long,
)

data class ResolveBatchImportBoundaryCommand(
    val jobId: String,
    val pageIndex: Int,
    val primaryDraftId: String,
    val followingDraftId: String,
    val resolution: String,
    val occurredAtEpochMillis: Long,
)

/** The caller supplies learning meaning; Room owns eventSequence and canonical persistence. */
data class AttemptWriteCommand(
    val learnerId: String,
    val submissionId: String,
    val attemptId: String,
    val presentationId: String,
    val assessmentSnapshotId: String,
    val submittedResponse: AttemptSubmittedResponse.Choice,
    val evidence: LearningEvidence,
    val problemMemoryOutcome: ProblemMemoryOutcome,
    val occurredAtEpochMillis: Long,
    val durationSeconds: Int,
    val studyDay: StudyDayContext,
    /** Real hint level recorded before the response (wiring A3). */
    val hintCount: Int = 0,
    val revealedBeforeAnswer: Boolean = false,
)

/**
 * One collected review-log row (spec mastery-scheduling 3.1). [sourceId]
 * makes inserts idempotent: UNIQUE(learner_id, source_id).
 */
data class ReviewLogEntry(
    val learnerId: String,
    val practiceUnitId: String,
    val rating: Int,
    val deltaTDays: Double,
    val durationMs: Long,
    val reviewedAtEpochMillis: Long,
    val sourceKind: String,
    val sourceId: String,
    val evidenceWeight: Double,
    val schedulingEligible: Boolean = true,
    val timeBucket: String,
    /** Silent interaction signals (spec §2.14); never surfaced as UI prompts. */
    val scrollUpCount: Int = 0,
    val editCount: Int = 0,
    val interruptionCount: Int = 0,
    val awayMillis: Long = 0,
    val plannedReason: String? = null,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(rating in 1..4) { "Review-log rating must be within 1..4" }
        require(scrollUpCount >= 0 && editCount >= 0 && interruptionCount >= 0) {
            "Interaction counts must not be negative"
        }
        require(awayMillis >= 0) { "Away time must not be negative" }
        require(deltaTDays >= 0.0 && deltaTDays.isFinite()) { "Delta days must not be negative" }
        require(durationMs >= 0) { "Duration must not be negative" }
        require(reviewedAtEpochMillis >= 0 && recordedAtEpochMillis >= 0) {
            "Review-log times must not be negative"
        }
        require(evidenceWeight.isFinite() && evidenceWeight in 0.0..1.0) {
            "Review-log evidence weight must be between zero and one"
        }
    }
}

/** Projection of one review-log row for the evaluation harness/optimizer. */
data class ReviewLogSampleRecord(
    val practiceUnitId: String,
    val reviewedAtEpochMillis: Long,
    val rating: Int,
    val durationMs: Long,
    val timeBucket: String,
    val sourceKind: String,
    val evidenceWeight: Double,
    val scrollUpCount: Int = 0,
    val editCount: Int = 0,
    val interruptionCount: Int = 0,
    val awayMillis: Long = 0,
    val plannedReason: String? = null,
    val deltaTDays: Double? = null,
)

data class AnswerRevealWriteCommand(
    val learnerId: String,
    val assessmentEventId: String,
    val presentationId: String,
    val assessmentSnapshotId: String,
    val contentMarkdown: String,
    val occurredAtEpochMillis: Long,
    val studyDay: StudyDayContext,
)

data class AnswerRevealWriteResult(
    val created: Boolean,
    val outcome: AnswerRevealOutcome,
    val canonicalFingerprint: String,
    val outboxId: String,
)

data class AttemptWriteResult(
    val submissionId: String,
    val created: Boolean,
    val attempt: Attempt,
    val canonicalFingerprint: String,
    val outboxId: String,
)

data class ReviewAttemptWriteCommand(
    val attempt: AttemptWriteCommand,
    val sessionId: String,
    val expectedStateVersion: Long,
    val reviewQueueItemId: String,
    val practiceUnitId: String,
)

data class ReviewAttemptWriteResult(
    val attempt: AttemptWriteResult,
    val advance: ReviewSessionAdvanceResult,
)

data class AttemptPersistenceRecord(
    val learnerId: String,
    val submissionId: String,
    val payloadFingerprint: String,
    val attemptEventCount: Int,
    val outboxCount: Int,
)

/** Canonical database proof that a review item has a durable submitted attempt. */
data class AttemptAdvanceProofRecord(
    val learnerId: String,
    val attemptId: String,
    val submissionId: String,
    val presentationId: String,
    val practiceUnitId: String,
    val occurredAtEpochMillis: Long,
)

/** submissionId binds the correction to the same immutable submission as its target attempt. */
data class AttemptCorrectionRecord(
    val learnerId: String,
    val submissionId: String,
    val correctionId: String,
    val attemptId: String,
    val replacementEvidence: LearningEvidence,
    val replacementMemoryOutcome: ProblemMemoryOutcome,
    val reasonMarkdown: String,
    val occurredAtEpochMillis: Long,
)

data class AttemptCorrectionResult(
    val created: Boolean,
    val correction: AttemptCorrection,
    val canonicalFingerprint: String,
    val outboxId: String,
)

data class ProjectionOutboxRecord(
    val outboxId: String,
    val learnerId: String,
    val outboxSequence: Long,
    val eventKind: String,
    val eventId: String,
    val canonicalFingerprint: String,
    val status: String,
    val createdAtEpochMillis: Long,
)

data class PersistedAttemptP0(
    val learnerId: String,
    val submissionId: String,
    val attempt: Attempt,
    val canonicalFingerprint: String,
    val outbox: ProjectionOutboxRecord,
)

data class PersistedCorrectionP0(
    val learnerId: String,
    val submissionId: String,
    val correction: AttemptCorrection,
    val canonicalFingerprint: String,
    val outbox: ProjectionOutboxRecord,
)

data class PersistedAnswerRevealP0(
    val learnerId: String,
    val assessmentEventId: String,
    val outcome: AnswerRevealOutcome,
    val canonicalFingerprint: String,
    val outbox: ProjectionOutboxRecord,
)

data class PersistedIncrementalLearningEvent(
    val event: IncrementalLearningEvent,
    val canonicalFingerprint: String,
    val outbox: ProjectionOutboxRecord,
)

enum class ProjectionBatchStopReason {
    END_OF_LEDGER,
    LIMIT_REACHED,
    FULL_REPLAY_REQUIRED,
    GAP,
    CONFLICT,
}

data class ProjectionBatch(
    val projectionName: String,
    val learnerId: String,
    val previousCheckpoint: Long,
    val ledgerHeadSequence: Long,
    val events: List<PersistedIncrementalLearningEvent>,
    /** Unbounded persistence authority for every presentation referenced by [events]. */
    val authoritativePresentationStates: Map<String, PresentationProjectionState>,
    val stopReason: ProjectionBatchStopReason,
    val blockedAtSequence: Long? = null,
    val detail: String? = null,
)

enum class LearningLedgerReadStatus {
    COMPLETE,
    GAP,
    CONFLICT,
}

data class PersistedLearningLedgerEvent(
    val event: LearningLedgerEvent,
    val canonicalFingerprint: String,
)

data class LearningLedgerRead(
    val learnerId: String,
    val validPrefix: List<PersistedLearningLedgerEvent>,
    val status: LearningLedgerReadStatus,
    val blockedAtSequence: Long? = null,
    val detail: String? = null,
)

data class ConsumedLedgerEventReceipt(
    val eventKind: String,
    val eventId: String,
    val eventSequence: Long,
    val canonicalFingerprint: String,
)

enum class ProjectionCommitMode {
    INCREMENTAL,
    FULL_REPLAY,
}

data class ProjectionCommit(
    val projectionName: String,
    val learnerId: String,
    val expectedPreviousCheckpoint: Long,
    val expectedPreviousStateVersion: Long,
    val mode: ProjectionCommitMode,
    val knownLedgerHeadSequence: Long,
    val consumedLedgerEvents: List<ConsumedLedgerEventReceipt>,
    /** Projector output; incremental commits return touched states, full replay returns all states. */
    val presentationProjectionStates: Map<String, PresentationProjectionState>,
    val snapshot: LearnerSnapshot,
)

data class PersistedLearnerSnapshot(
    val projectionName: String,
    val stateVersion: Long,
    val knownLedgerHeadSequence: Long,
    val snapshot: LearnerSnapshot,
)

data class ReviewPlanBundle(
    val plan: ReviewPlanRecord,
    val queue: List<ReviewQueueItemRecord>,
    val activeSession: ReviewSessionRecord?,
    val isCurrent: Boolean = false,
    /** Latest persisted session head, including terminal states; null only when never started. */
    val latestSession: ReviewSessionRecord? = null,
)
