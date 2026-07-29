package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.IncrementalLearningEvent
import com.tingyun.smartmistakebook.core.model.LearningLedgerEvent
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewCase
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidate
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidateStatus
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

const val MAX_REVIEW_COMPLETION_HISTORY_DAYS = 1_500
const val MAX_KNOWLEDGE_RECALL_CANDIDATES = 512

class AttemptIdempotencyConflictException(submissionId: String) :
    IllegalStateException("submissionId $submissionId was already used for a different payload")

class AssessmentSequenceConflictException(message: String) : IllegalStateException(message)

class ImmutablePayloadConflictException(entityType: String, entityId: String) :
    IllegalStateException("$entityType $entityId already exists with a different payload")

class LearningObservationSourceAuthorityException(candidateId: String) :
    IllegalStateException(
        "Learning observation candidate $candidateId lacks matching local source authority",
    )

class ProblemOrganizationAuthorityConflictException(problemRevisionId: String) :
    IllegalStateException("A user correction already owns organization for $problemRevisionId")

class ProblemOrganizationWorkReauthorizationConflictException(workId: String) :
    IllegalStateException("Organization work $workId was reauthorized with a different payload")

class ProjectionCasConflictException(message: String) : IllegalStateException(message)

class LearningLedgerIntegrityException(message: String) : IllegalStateException(message)

class ProblemDraftEditWorkspaceConflictException(message: String) : IllegalStateException(message)

class ProblemDraftEditWorkspaceIntegrityException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class DatabaseContractViolationException(message: String) : IllegalArgumentException(message)

/** Stable strings stored in SQLite. Values are append-only once released. */
object StudyDbValue {
    object KnowledgeRelationType {
        const val PREREQUISITE_OF = "PREREQUISITE_OF"
    }

    object KnowledgeGroundingStatus {
        const val PENDING = "PENDING"
        const val RESOLVED = "RESOLVED"
        const val DISMISSED = "DISMISSED"
    }

    object KnowledgeResearchReviewStatus {
        const val PENDING_REVIEW = "PENDING_REVIEW"
        const val APPROVED = "APPROVED"
        const val REJECTED = "REJECTED"
        const val APPLIED = "APPLIED"
    }

    object ProblemDraftStatus {
        const val EDITING = "EDITING"
        const val COMMITTED = "COMMITTED"
        const val ABANDONED = "ABANDONED"
    }

    object ProblemOrganizationWorkStatus {
        const val PENDING = "PENDING"
        const val RUNNING = "RUNNING"
        const val RETRY = "RETRY"
        const val WAITING_AUTHORIZATION = "WAITING_AUTHORIZATION"
        const val SUCCEEDED = "SUCCEEDED"
        const val PERMANENT_FAILURE = "PERMANENT_FAILURE"
    }

    object ProblemErrorAttributionResolution {
        const val RESOLVED = "RESOLVED"
        const val UNRESOLVED = "UNRESOLVED"
    }

    object ProblemDraftAuthor {
        const val CAPTURE_IMPORT = "CAPTURE_IMPORT"
        const val LOCAL_OCR = "LOCAL_OCR"
        const val OPTIONAL_REMOTE_OCR = "OPTIONAL_REMOTE_OCR"
        const val USER = "USER"
    }

    object CaptureOrigin {
        const val LIBRARY = "LIBRARY"
        const val TUTOR = "TUTOR"
    }

    object SourceAssetType {
        const val CAMERA = "CAMERA"
        const val PHOTO_PICKER = "PHOTO_PICKER"
    }

    object BatchImportStatus {
        const val PROCESSING = "PROCESSING"
        const val PAUSED = "PAUSED"
        const val COMPLETED = "COMPLETED"
    }

    object BatchImportPageStatus {
        const val QUEUED = "QUEUED"
        const val IMPORTING = "IMPORTING"
        const val READY = "READY"
        const val FAILED = "FAILED"
        const val SKIPPED = "SKIPPED"
    }

    object BatchImportBoundaryStatus {
        const val PENDING = "PENDING"
        const val CHECKING = "CHECKING"
        const val SAME_QUESTION = "SAME_QUESTION"
        const val NEXT_QUESTION = "NEXT_QUESTION"
        const val KEPT_SEPARATE = "KEPT_SEPARATE"
        const val FAILED = "FAILED"
    }

    object ErrorBookStatus {
        const val ACTIVE = "ACTIVE"
        const val ARCHIVED = "ARCHIVED"
        const val TRASHED = "TRASHED"
    }

    object RelationType {
        const val SAME_KNOWLEDGE = "SAME_KNOWLEDGE"
        const val SAME_ERROR_PATTERN = "SAME_ERROR_PATTERN"
        const val VARIANT_OF = "VARIANT_OF"
        const val PREREQUISITE_OF = "PREREQUISITE_OF"
        const val SAME_SOURCE_BUNDLE = "SAME_SOURCE_BUNDLE"
        const val SHARES_STIMULUS = "SHARES_STIMULUS"
        const val CONTINUATION_OF = "CONTINUATION_OF"
        const val ANSWER_FOR = "ANSWER_FOR"
        const val SAME_FIGURE_PATTERN = "SAME_FIGURE_PATTERN"
        const val POSSIBLE_DUPLICATE = "POSSIBLE_DUPLICATE"
        const val DERIVED_FROM = "DERIVED_FROM"
    }

    object RelationStatus {
        const val ACTIVE = "ACTIVE"
        const val STALE = "STALE"
        const val REJECTED = "REJECTED"
    }

    object AssessmentEventType {
        const val PRESENTED = "PRESENTED"
        const val HINT_REVEALED = "HINT_REVEALED"
        const val ANSWER_REVEALED = "ANSWER_REVEALED"
        const val RESPONSE_SUBMITTED = "RESPONSE_SUBMITTED"
        const val CANCELLED = "CANCELLED"
    }

    object AssessmentEligibility {
        const val SESSION_ONLY = "SESSION_ONLY"
        const val ATTEMPT_ELIGIBLE = "ATTEMPT_ELIGIBLE"
        const val BLOCKED = "BLOCKED"
    }

    object ScoringMode {
        const val AUTO_VERIFIED = "AUTO_VERIFIED"
        const val USER_SELF_REPORT = "USER_SELF_REPORT"
        const val RUBRIC_ASSISTED = "RUBRIC_ASSISTED"
    }

    object VerificationStatus {
        const val VERIFIED = "VERIFIED"
        const val USER_ASSERTED = "USER_ASSERTED"
        const val UNKNOWN = "UNKNOWN"
    }

    object OutboxStatus {
        const val PENDING = "PENDING"
    }

    object ReviewStatus {
        const val PLANNED = "PLANNED"
        const val IN_PROGRESS = "IN_PROGRESS"
        const val COMPLETED = "COMPLETED"
        const val SKIPPED = "SKIPPED"
        const val CANCELLED = "CANCELLED"
    }
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
    /** Legacy v1/v2 commands remain readable but cannot persist error-attribution candidates. */
    val planSchemaVersion: Int = 2,
    val solutionSteps: List<ProblemSolutionStepSeedRecord> = emptyList(),
    val errorAttributionCandidates: List<ProblemErrorAttributionCandidateSeedRecord> = emptyList(),
    /** Present only for a v3 organization generated from this exact import occurrence. */
    val sourceCommitReceiptCommandId: String? = null,
)

data class ProblemSolutionStepSeedRecord(
    val stepOrdinal: Int,
    val summaryMarkdown: String,
    val knowledgeReferences: List<ProblemStepKnowledgeReferenceSeedRecord>,
)

data class ProblemStepKnowledgeReferenceSeedRecord(
    val knowledgeReferenceId: String,
    val knowledgeNodeId: String,
)

data class ProblemErrorAttributionCandidateSeedRecord(
    val candidateOrdinal: Int,
    val resolutionStatus: String,
    val stepOrdinal: Int?,
    val knowledgeReferenceId: String?,
    val knowledgeNodeId: String?,
    val rationaleMarkdown: String,
    val confidence: Double,
    val modelVersion: String,
    val evidence: List<ProblemErrorAttributionEvidenceSeedRecord>,
)

data class ProblemErrorAttributionEvidenceSeedRecord(
    val blockId: String,
    val sourceAssetId: String,
    val evidenceKind: String,
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
    val problemOrganizationAuthorization: ProblemOrganizationAuthorizationGrant? = null,
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
    val evidenceRequestId: String? = null,
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
    val evidenceRequestId: String? = null,
) {
    init {
        require(choiceSubmittedAtEpochMillis >= 0)
        require(evidenceRequestId == null || evidenceRequestId.isNotBlank())
    }
}

data class PersistTutorEvidenceCancellationCommand(
    val learnerId: String,
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val evidenceRequestId: String,
    val cancelledAtEpochMillis: Long,
) {
    init {
        require(learnerId.isNotBlank() && sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && evidenceRequestId.isNotBlank())
        require(cancelledAtEpochMillis >= 0)
    }
}

data class TutorVisualTargetEvidenceRecord(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val surfaceKind: String,
    val modelTaskRequestId: String,
    val responseOrdinal: Int?,
    val sceneSourceKind: String,
    val sceneTaskRequestId: String,
    val sceneId: String,
    val sceneFingerprint: String,
    val hitProofId: String,
    val panelId: String,
    val frameFingerprint: String,
    val stepIndex: Int,
    val selectedTargetId: String,
    val selectionWasCorrect: Boolean,
    val submittedAtEpochMillis: Long,
)

data class ProblemOrganizationWorkRecord(
    val workId: String,
    val commitReceiptCommandId: String,
    val status: String,
    val stateVersion: Long,
    val attemptCount: Int,
    val notBeforeEpochMillis: Long,
    val requestId: String?,
    val requestSnapshot: String?,
    val authorizationGrantSnapshot: String? = null,
    val leaseOwner: String?,
    val leaseExpiresAtEpochMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class ProblemOrganizationWorkPreparationRecord(
    val work: ProblemOrganizationWorkRecord,
    val commitReceipt: ProblemDraftCommitReceipt,
)

data class ReauthorizeProblemOrganizationWorkCommand(
    val workId: String,
    val expectedStateVersion: Long,
    val problemId: String,
    val problemRevisionId: String,
    val errorBookEntryId: String,
    val provider: ProviderCapabilitySnapshot,
    val authorizationGrant: ProblemOrganizationAuthorizationGrant,
)

enum class ReauthorizeProblemOrganizationWorkOutcome {
    REAUTHORIZED,
    REPLAYED,
    NOT_APPLIED,
}

data class ReauthorizeProblemOrganizationWorkResult(
    val outcome: ReauthorizeProblemOrganizationWorkOutcome,
    val work: ProblemOrganizationWorkRecord?,
)

data class AuthorizeProblemOrganizationWorkCommand(
    val workId: String,
    val expectedStateVersion: Long,
    val requestId: String,
    val requestSnapshot: String,
    val notBeforeEpochMillis: Long,
    val authorizedAtEpochMillis: Long,
)

data class ProblemOrganizationWorkTransitionCommand(
    val workId: String,
    val expectedStateVersion: Long,
    val leaseOwner: String,
    val occurredAtEpochMillis: Long,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val notBeforeEpochMillis: Long? = null,
    val requestId: String? = null,
)

data class CompleteProblemOrganizationWorkAtomicallyCommand(
    val workId: String,
    val expectedStateVersion: Long,
    val leaseOwner: String,
    val requestId: String,
    val confirmation: ConfirmProblemOrganizationCommand? = null,
    val groundingRequests: List<KnowledgeGroundingRequestRecord> = emptyList(),
)

data class ConfirmAndCompleteProblemOrganizationWorkResult(
    val completed: Boolean,
    val organizationResult: ConfirmProblemOrganizationResult?,
)

data class PersistTutorVisualTargetEvidenceCommand(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val surfaceKind: String,
    val modelTaskRequestId: String,
    val responseOrdinal: Int?,
    val sceneSourceKind: String,
    val sceneTaskRequestId: String,
    val sceneId: String,
    val sceneFingerprint: String,
    val hitProofId: String,
    val panelId: String,
    val frameFingerprint: String,
    val stepIndex: Int,
    val selectedTargetId: String,
    val submittedAtEpochMillis: Long,
) {
    init {
        require(sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0)
        require(modelTaskRequestId.isNotBlank() && sceneTaskRequestId.isNotBlank())
        require(sceneSourceKind == "INLINE" || sceneSourceKind == "GENERATED")
        require(sceneId.isNotBlank() && sceneFingerprint.isSha256Hex())
        require(hitProofId.isNotBlank() && panelId.isNotBlank() && frameFingerprint.isSha256Hex())
        require(stepIndex >= 0 && selectedTargetId.isNotBlank())
        require(sceneSourceKind != "INLINE" || sceneTaskRequestId == modelTaskRequestId)
        require(
            surfaceKind == "PLAN" && responseOrdinal == null ||
                surfaceKind == "FOLLOW_UP" && responseOrdinal != null && responseOrdinal > 0,
        ) { "Persisted tutor visual-target evidence identity is inconsistent" }
        require(submittedAtEpochMillis >= 0)
    }
}

private fun String.isSha256Hex(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }

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
)

data class LearningObservationCandidateWriteResult(
    val created: Boolean,
    val candidate: LearningObservationCandidate,
)

data class LearningObservationSourceAuthorityRecord(
    val learnerId: String,
    val source: LearningObservationSource,
    val sourceReferenceId: String,
    val practiceUnitId: String,
    val problemRevisionId: String,
    val sourcePayloadFingerprint: String,
    val verifiedAtEpochMillis: Long,
) {
    init {
        require(learnerId.isNotBlank()) { "learnerId must not be blank" }
        require(sourceReferenceId.isNotBlank()) { "sourceReferenceId must not be blank" }
        require(practiceUnitId.isNotBlank()) { "practiceUnitId must not be blank" }
        require(problemRevisionId.isNotBlank()) { "problemRevisionId must not be blank" }
        require(
            sourcePayloadFingerprint.isNotBlank() && sourcePayloadFingerprint.length <= 256,
        ) { "sourcePayloadFingerprint must be non-blank and at most 256 characters" }
        require(verifiedAtEpochMillis >= 0) { "verifiedAtEpochMillis cannot be negative" }
    }
}

data class LearningObservationSourceAuthorityWriteResult(
    val created: Boolean,
    val authority: LearningObservationSourceAuthorityRecord,
)

data class LearningObservationCandidateStatusChangeCommand(
    val candidateId: String,
    val expectedStatus: LearningObservationCandidateStatus,
    val newStatus: LearningObservationCandidateStatus,
    val expectedRetryCount: Int,
    val incrementRetry: Boolean,
    val updatedAtEpochMillis: Long,
)

data class LearningObservationCandidateStatusCasResult(
    val updated: Boolean,
    val candidate: LearningObservationCandidate,
)

data class MaterializeLearningObservationCommand(
    val candidateId: String,
    val eventId: String,
    val confirmedAtEpochMillis: Long,
)

data class LearningObservationMaterializationResult(
    val created: Boolean,
    val event: AttributedLearningObservationEvent?,
    val canonicalFingerprint: String?,
    val outboxId: String?,
    val reviewCase: LearningEvidenceReviewCase?,
) {
    init {
        require((event == null) != (reviewCase == null)) {
            "Materialization must return either a ledger event or a review case"
        }
        require((event != null) == (canonicalFingerprint != null && outboxId != null)) {
            "Materialized event metadata must be complete"
        }
        require(!created || event != null) { "A review case is never a created ledger event" }
    }
}

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

interface StudyDatabasePort :
    AutoCloseable,
    ModelTaskDatabasePort,
    TutorLearningMemoryDatabasePort {
    fun observeMistakes(): Flow<List<MistakeRecord>>

    fun observeLearningLedgerHead(learnerId: String): Flow<Long> = flowOf(0L)

    fun observeConfirmedProblemOrganization(
        problemId: String,
        problemRevisionId: String,
    ): Flow<ConfirmedProblemOrganizationRecord> = throw UnsupportedOperationException(
        "Problem organization reads are not implemented",
    )

    fun observePendingProblemDraftCount(): Flow<Int>

    fun observeTutorTurnResponses(sessionId: String): Flow<List<TutorTurnResponseRecord>> =
        flowOf(emptyList())

    fun observeTutorVisualTargetEvidence(
        sessionId: String,
    ): Flow<List<TutorVisualTargetEvidenceRecord>> = flowOf(emptyList())

    fun observePendingCaptureDrafts(): Flow<List<PendingCaptureDraftRecord>> =
        throw UnsupportedOperationException("Pending capture reads are not implemented")

    fun observeBatchImportJobs(): Flow<List<BatchImportJobRecord>> = flowOf(emptyList())

    fun observeReviewPlan(reviewPlanId: String): Flow<ReviewPlanBundle?>

    fun observeReviewPlanForSession(sessionId: String): Flow<ReviewPlanBundle?>

    fun observeActiveReviewPlan(learnerId: String): Flow<ReviewPlanBundle?>

    fun observeCurrentReviewPlan(
        learnerId: String,
        localDayEpochDay: Long,
        timeZoneId: String,
    ): Flow<ReviewPlanBundle?>

    fun observeCompletedReviewLocalDays(
        learnerId: String,
        limit: Int,
    ): Flow<List<Long>> = flowOf(emptyList())

    suspend fun countMistakes(): Int

    suspend fun readSubjectKnowledgeNodes(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> = emptyList()

    /**
     * Reads a lightweight, bounded subject slice for local relevance ranking.
     *
     * This is deliberately separate from the small model-context read: callers rank the larger
     * slice locally and only disclose the final bounded result to an external model.
     */
    suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> = readSubjectKnowledgeNodes(subject, limit.coerceAtMost(256))

    suspend fun readKnowledgeNodesByIds(ids: Set<String>): List<KnowledgeNodeSeedRecord> = emptyList()

    suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceSeedRecord> = emptyList()

    suspend fun readKnowledgeNodeSourceBindings(
        knowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingSeedRecord> = emptyList()

    suspend fun readSubjectKnowledgeNodeRelations(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeRelationRecord> = emptyList()

    suspend fun readKnowledgeNodeRelationsForDependents(
        subject: String,
        dependentKnowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeRelationRecord> = emptyList()

    suspend fun readKnowledgeTeachingMaterialsForNodes(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<KnowledgeTeachingMaterialRecord> = emptyList()

    suspend fun readKnowledgeTeachingMaterialsByIds(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialRecord> = emptyList()

    suspend fun readKnowledgeTeachingMaterialNodeBindings(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialNodeBindingRecord> = emptyList()

    suspend fun importKnowledgeNodeRelations(
        relations: List<KnowledgeNodeRelationRecord>,
    ): Unit = throw UnsupportedOperationException("Knowledge-node relation imports are not implemented")

    suspend fun importKnowledgeTeachingMaterials(
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        sources: List<KnowledgeSourceSeedRecord> = emptyList(),
    ): Unit = throw UnsupportedOperationException(
        "Knowledge teaching-material imports are not implemented",
    )

    suspend fun importKnowledgeBase(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    ): Unit = throw UnsupportedOperationException("Knowledge-base imports are not implemented")

    suspend fun applyReviewedKnowledgePack(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = throw UnsupportedOperationException(
        "Reviewed knowledge-pack application is not implemented",
    )

    fun observePendingKnowledgeGroundingRequests(
        limit: Int = 256,
    ): Flow<List<KnowledgeGroundingRequestRecord>> = flowOf(emptyList())

    fun observePendingKnowledgeGroundingSummaries(
        limit: Int = 128,
    ): Flow<List<KnowledgeGroundingSummaryRecord>> = flowOf(emptyList())

    fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRecord>> =
        flowOf(emptyList())

    suspend fun enqueueKnowledgeResearchReviewBundle(
        bundle: KnowledgeResearchReviewBundleRecord,
    ): Unit = throw UnsupportedOperationException(
        "Knowledge research review persistence is not implemented",
    )

    suspend fun readPendingKnowledgeResearchReviewBundles(
        limit: Int = 64,
    ): List<KnowledgeResearchReviewBundleRecord> = emptyList()

    suspend fun readKnowledgeResearchReviewBundle(
        bundleId: String,
    ): KnowledgeResearchReviewBundleRecord? = null

    suspend fun decideKnowledgeResearchReviewBundle(
        command: DecideKnowledgeResearchReviewBundleCommand,
    ): KnowledgeResearchReviewBundleRecord = throw UnsupportedOperationException(
        "Knowledge research review decisions are not implemented",
    )

    suspend fun applyApprovedKnowledgeResearchPack(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = throw UnsupportedOperationException(
        "Approved knowledge research pack application is not implemented",
    )

    suspend fun recordKnowledgeGroundingRequests(
        requests: List<KnowledgeGroundingRequestRecord>,
    ): Unit = throw UnsupportedOperationException("Knowledge-grounding requests are not implemented")

    suspend fun resolveKnowledgeGrounding(
        command: ResolveKnowledgeGroundingCommand,
    ): KnowledgeGroundingResolutionRecord =
        throw UnsupportedOperationException("Knowledge-grounding resolution is not implemented")

    suspend fun readKnowledgeGroundingResolution(
        groundingKey: String,
    ): KnowledgeGroundingResolutionRecord? = null

    suspend fun findMistakeBySourceKey(sourceKey: String): MistakeRecord?

    suspend fun readMistakeDetail(errorBookEntryId: String): MistakeDetailRecord? =
        throw UnsupportedOperationException("Mistake-detail reads are not implemented")

    suspend fun readExactMistakeDetail(
        entryId: String,
        problemId: String,
        problemRevisionId: String,
    ): MistakeDetailRecord? =
        throw UnsupportedOperationException("Exact mistake-detail reads are not implemented")

    suspend fun readCurrentMistakeDetails(
        entryIds: List<String>,
    ): List<MistakeDetailRecord> = entryIds.mapNotNull { entryId ->
        readMistakeDetail(entryId)
    }

    suspend fun readMistakeRevisionHistory(
        errorBookEntryId: String,
    ): List<MistakeRevisionSummaryRecord> = emptyList()

    suspend fun createProblemDraft(command: CreateProblemDraftCommand): ProblemDraftWriteResult

    suspend fun appendProblemDraftSourceAsset(
        command: AppendProblemDraftSourceAssetCommand,
    ): AppendProblemDraftSourceAssetResult = throw UnsupportedOperationException(
        "Problem-draft source append is not implemented",
    )

    suspend fun reviseProblemDraft(command: ReviseProblemDraftCommand): ProblemDraftWriteResult

    suspend fun replaceProblemDraft(
        command: ReplaceProblemDraftCommand,
    ): ProblemDraftReplacementResult = throw UnsupportedOperationException(
        "Atomic capture replacement is not implemented",
    )

    suspend fun splitProblemDraft(
        command: SplitProblemDraftCommand,
    ): ProblemDraftSplitResult = throw UnsupportedOperationException(
        "Atomic capture splitting is not implemented",
    )

    suspend fun readProblemDraft(draftId: String): ProblemDraftRecord?

    suspend fun readCanonicalSourceAsset(sourceAssetId: String): CanonicalSourceAssetRecord? = null

    suspend fun readPendingCaptureDraft(draftId: String): PendingCaptureDraftRecord? =
        throw UnsupportedOperationException("Pending capture reads are not implemented")

    suspend fun createBatchImportJob(
        command: CreateBatchImportJobCommand,
    ): BatchImportJobRecord = throw UnsupportedOperationException(
        "Batch import writes are not implemented",
    )

    suspend fun readBatchImportJob(jobId: String): BatchImportJobRecord? = null

    suspend fun updateBatchImportJobStatus(
        jobId: String,
        expectedStatus: String,
        nextStatus: String,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    suspend fun requeueInterruptedBatchImportPages(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int = 0

    suspend fun claimNextBatchImportPage(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): BatchImportPageRecord? = null

    suspend fun completeBatchImportPage(
        jobId: String,
        pageIndex: Int,
        draftId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    suspend fun claimBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    suspend fun requeueInterruptedBatchImportBoundaries(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int = 0

    suspend fun resolveBatchImportBoundary(
        command: ResolveBatchImportBoundaryCommand,
    ): BatchImportJobRecord = throw UnsupportedOperationException(
        "Batch import page-boundary writes are not implemented",
    )

    suspend fun failBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    suspend fun failBatchImportPage(
        jobId: String,
        pageIndex: Int,
        failureCode: String,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    suspend fun retryBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    suspend fun skipBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    suspend fun finishBatchImportIfSettled(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    suspend fun hasRetainedBatchImportSourceUri(sourceUri: String): Boolean = true

    suspend fun readProblemDraftEditWorkspace(
        draftId: String,
    ): ProblemDraftEditWorkspaceRecord? = throw UnsupportedOperationException(
        "Problem-draft edit-workspace reads are not implemented",
    )

    suspend fun saveProblemDraftEditWorkspace(
        command: SaveProblemDraftEditWorkspaceCommand,
    ): ProblemDraftEditWorkspaceWriteResult = throw UnsupportedOperationException(
        "Problem-draft edit-workspace writes are not implemented",
    )

    suspend fun consumeProblemDraftEditWorkspace(
        command: ConsumeProblemDraftEditWorkspaceCommand,
    ): Boolean = throw UnsupportedOperationException(
        "Problem-draft edit-workspace consumption is not implemented",
    )

    suspend fun commitProblemDraft(command: CommitProblemDraftCommand): CommitProblemDraftResult

    suspend fun readProblemOrganizationWork(
        workId: String,
    ): ProblemOrganizationWorkRecord? = null

    suspend fun readProblemOrganizationWorkByCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemOrganizationWorkRecord? = null

    suspend fun readProblemOrganizationWorkByRequestId(
        requestId: String,
    ): ProblemOrganizationWorkRecord? = null

    suspend fun readProblemOrganizationWorkCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemDraftCommitReceipt? = null

    suspend fun readWaitingProblemOrganizationWork(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRecord? = null

    suspend fun readLatestProblemOrganizationWork(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRecord? = null

    suspend fun claimNextProblemOrganizationWork(
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkRecord? = null

    suspend fun claimProblemOrganizationWork(
        workId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkRecord? = null

    suspend fun readSchedulableProblemOrganizationWorks(
        nowEpochMillis: Long,
        limit: Int,
    ): List<ProblemOrganizationWorkRecord> = emptyList()

    suspend fun readRunningProblemOrganizationWorks(
        limit: Int,
        afterLeaseExpiresAtEpochMillis: Long? = null,
        afterUpdatedAtEpochMillis: Long? = null,
        afterWorkId: String? = null,
    ): List<ProblemOrganizationWorkRecord> = emptyList()

    fun observeSchedulableProblemOrganizationWorks(): Flow<List<ProblemOrganizationWorkRecord>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun authorizeProblemOrganizationWork(
        command: AuthorizeProblemOrganizationWorkCommand,
    ): Boolean = false

    suspend fun reauthorizeProblemOrganizationWork(
        command: ReauthorizeProblemOrganizationWorkCommand,
    ): ReauthorizeProblemOrganizationWorkResult =
        ReauthorizeProblemOrganizationWorkResult(
            outcome = ReauthorizeProblemOrganizationWorkOutcome.NOT_APPLIED,
            work = null,
        )

    suspend fun markProblemOrganizationWorkWaitingAuthorization(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean = false

    suspend fun retryProblemOrganizationWork(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean = false

    suspend fun failProblemOrganizationWorkPermanently(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean = false

    suspend fun completeProblemOrganizationWork(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean = false

    suspend fun confirmAndCompleteProblemOrganizationWork(
        command: CompleteProblemOrganizationWorkAtomicallyCommand,
    ): ConfirmAndCompleteProblemOrganizationWorkResult =
        ConfirmAndCompleteProblemOrganizationWorkResult(
            completed = false,
            organizationResult = null,
        )

    suspend fun confirmAndCommitProblemDraftFromWorkspace(
        command: ConfirmAndCommitProblemDraftFromWorkspaceCommand,
    ): CommitProblemDraftResult = throw UnsupportedOperationException(
        "Atomic workspace-backed problem confirmation is not implemented",
    )

    suspend fun confirmTutorSession(
        command: ConfirmTutorSessionCommand,
    ): TutorSessionWriteResult = throw UnsupportedOperationException(
        "Tutor-session confirmation is not implemented",
    )

    suspend fun confirmTutorSessionFromWorkspace(
        command: ConfirmTutorSessionFromWorkspaceCommand,
    ): TutorSessionWriteResult = throw UnsupportedOperationException(
        "Atomic workspace-backed tutor confirmation is not implemented",
    )

    suspend fun readTutorSession(sessionId: String): TutorSessionRecord? =
        throw UnsupportedOperationException("Tutor-session reads are not implemented")

    suspend fun commitTutorSession(
        command: CommitTutorSessionCommand,
    ): CommitProblemDraftResult = throw UnsupportedOperationException(
        "Tutor-session commit is not implemented",
    )

    suspend fun endTutorSession(
        command: EndTutorSessionCommand,
    ): EndTutorSessionResult = throw UnsupportedOperationException(
        "Tutor-session ending is not implemented",
    )

    suspend fun recordTutorChoice(command: PersistTutorChoiceCommand): TutorTurnResponseRecord =
        throw UnsupportedOperationException("Tutor response writes are not implemented")

    suspend fun recordTutorChoiceUnlessCancelled(
        command: PersistTutorChoiceCommand,
        cancellation: PersistTutorEvidenceCancellationCommand,
    ): TutorTurnResponseRecord? = recordTutorChoice(command)

    suspend fun discardTutorChoice(command: PersistTutorChoiceCommand): Boolean = false

    suspend fun recordTutorVisualTargetEvidence(
        command: PersistTutorVisualTargetEvidenceCommand,
    ): TutorVisualTargetEvidenceRecord = throw UnsupportedOperationException(
        "Tutor visual-target evidence writes are not implemented",
    )

    suspend fun recordTutorVisualTargetEvidenceUnlessCancelled(
        command: PersistTutorVisualTargetEvidenceCommand,
        cancellation: PersistTutorEvidenceCancellationCommand,
    ): TutorVisualTargetEvidenceRecord? = recordTutorVisualTargetEvidence(command)

    suspend fun recordTutorEvidenceCancellation(
        command: PersistTutorEvidenceCancellationCommand,
    ) = Unit

    suspend fun isTutorEvidenceCancelled(
        command: PersistTutorEvidenceCancellationCommand,
    ): Boolean = false

    suspend fun recordTutorMove(command: PersistTutorMoveCommand): TutorTurnResponseRecord =
        throw UnsupportedOperationException("Tutor move writes are not implemented")

    suspend fun revealTutorSolution(command: PersistTutorRevealCommand): TutorTurnResponseRecord =
        throw UnsupportedOperationException("Tutor reveal writes are not implemented")

    suspend fun recordTutorSolutionExposure(
        command: PersistTutorAnswerExposureCommand,
    ): TutorAnswerExposureRecord = throw UnsupportedOperationException(
        "Tutor solution-exposure writes are not implemented",
    )

    suspend fun bindTutorSessionProblemAnchor(
        command: PersistTutorSessionAnchorCommand,
    ): TutorSessionProblemAnchorRecord = TutorSessionProblemAnchorRecord(
        learnerId = command.learnerId,
        sessionId = command.sessionId,
        problemRevisionId = command.problemRevisionId,
        practiceUnitId = command.practiceUnitId,
        source = command.source,
        anchoredAtEpochMillis = command.anchoredAtEpochMillis,
    )

    suspend fun reconcileTutorAnswerExposures(learnerId: String, limit: Int = 100): Int = 0

    suspend fun readTutorAnswerExposure(
        modelTaskRequestId: String,
    ): TutorAnswerExposureRecord? = null

    suspend fun readTutorAnswerExposures(
        modelTaskRequestIds: Set<String>,
    ): List<TutorAnswerExposureRecord> = modelTaskRequestIds.mapNotNull { requestId ->
        readTutorAnswerExposure(requestId)
    }

    suspend fun seedFixture(bundle: StudySeedBundle): SeedResult

    suspend fun saveAssessmentItemSnapshot(item: AssessmentItemSnapshotSeedRecord)

    suspend fun saveAssessmentEvidenceSnapshot(snapshot: AssessmentEvidenceSnapshot)

    suspend fun appendAssessmentEvent(event: AssessmentEventSeedRecord)

    suspend fun recordAttempt(command: AttemptWriteCommand): AttemptWriteResult

    suspend fun submitLearningObservationCandidate(
        candidate: LearningObservationCandidate,
    ): LearningObservationCandidateWriteResult

    /**
     * Registers an immutable local source fact. Tutor/capture ingestion should call this inside
     * the same transaction that persists the referenced source response.
     */
    suspend fun registerLearningObservationSourceAuthority(
        authority: LearningObservationSourceAuthorityRecord,
    ): LearningObservationSourceAuthorityWriteResult = throw UnsupportedOperationException(
        "Learning-observation source authority is not implemented",
    )

    suspend fun readLearningObservationSourceAuthority(
        learnerId: String,
        source: LearningObservationSource,
        sourceReferenceId: String,
    ): LearningObservationSourceAuthorityRecord? = null

    suspend fun compareAndSetLearningObservationCandidateStatus(
        command: LearningObservationCandidateStatusChangeCommand,
    ): LearningObservationCandidateStatusCasResult

    suspend fun materializeLearningObservation(
        command: MaterializeLearningObservationCommand,
    ): LearningObservationMaterializationResult

    suspend fun readLearningObservationCandidate(
        candidateId: String,
    ): LearningObservationCandidate?

    suspend fun readAttributedLearningObservation(
        eventId: String,
    ): AttributedLearningObservationEvent?

    suspend fun readLearningEvidenceReviewCase(
        reviewCaseId: String,
    ): LearningEvidenceReviewCase? = null

    suspend fun recordReviewAttempt(
        command: ReviewAttemptWriteCommand,
    ): ReviewAttemptWriteResult = throw UnsupportedOperationException(
        "Atomic review-attempt writes are not implemented",
    )

    suspend fun recordAnswerReveal(command: AnswerRevealWriteCommand): AnswerRevealWriteResult

    suspend fun reconcileAnswerRevealOutcomes(
        learnerId: String,
        limit: Int = 100,
    ): List<AnswerRevealWriteResult>

    suspend fun appendAttemptCorrection(correction: AttemptCorrectionRecord): AttemptCorrectionResult

    suspend fun findAttemptPersistence(submissionId: String): AttemptPersistenceRecord?

    suspend fun findAttemptAdvanceProof(attemptId: String): AttemptAdvanceProofRecord? = null

    suspend fun markRelationsStaleForRevision(
        problemRevisionId: String,
        updatedAtEpochMillis: Long,
    ): Int

    suspend fun confirmProblemOrganization(
        command: ConfirmProblemOrganizationCommand,
    ): ConfirmProblemOrganizationResult = throw UnsupportedOperationException(
        "Atomic problem organization confirmation is not implemented",
    )

    suspend fun loadProjectionBatch(
        projectionName: String,
        learnerId: String,
        limit: Int,
    ): ProjectionBatch

    suspend fun loadLearningLedger(learnerId: String): LearningLedgerRead

    suspend fun readCurrentLearnerSnapshot(
        projectionName: String,
        learnerId: String,
    ): PersistedLearnerSnapshot?

    suspend fun commitProjection(commit: ProjectionCommit): PersistedLearnerSnapshot

    suspend fun saveReviewPlan(bundle: ReviewPlanBundle)

    suspend fun saveReviewSession(session: ReviewSessionRecord)

    /**
     * Replays an already committed review transition. It must never create a new transition.
     * New answers must enter through [recordReviewAttempt], which creates the attempt and advances
     * its queue item in one write transaction.
     */
    @Deprecated("New review transitions must use recordReviewAttempt")
    suspend fun advanceReviewSession(
        command: ReviewSessionAdvanceCommand,
    ): ReviewSessionAdvanceResult = throw UnsupportedOperationException(
        "Review-session replay is not implemented",
    )

    @Deprecated("P0 inspection only; keep data-layer wiring behind the repository boundary")
    suspend fun readAssessmentSnapshotP0(
        assessmentItemSnapshotId: String,
    ): AssessmentItemSnapshotSeedRecord?

    @Deprecated("P0 inspection only; keep data-layer wiring behind the repository boundary")
    suspend fun readAttemptP0(attemptId: String): PersistedAttemptP0?

    @Deprecated("P0 inspection only; keep data-layer wiring behind the repository boundary")
    suspend fun readCorrectionP0(correctionId: String): PersistedCorrectionP0?

    @Deprecated("P0 inspection only; keep data-layer wiring behind the repository boundary")
    suspend fun readAnswerRevealP0(outcomeId: String): PersistedAnswerRevealP0?
}
