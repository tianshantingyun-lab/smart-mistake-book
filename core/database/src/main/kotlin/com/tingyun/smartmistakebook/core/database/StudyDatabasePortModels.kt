package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CaptureMergeSessionReceiptReference
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
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof

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

    object KnowledgeReferenceStatus {
        const val VERIFIED_AT_CONFIRMATION = "VERIFIED_AT_CONFIRMATION"
        const val PENDING_REATTRIBUTION = "PENDING_REATTRIBUTION"
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
    /**
     * Process-local catalog proof used only while admitting a new organization command.
     *
     * Persistence flattens its stable KnowledgeNodeRef fields and activation provenance. The
     * authority seal itself is never serialized.
     */
    val verifiedKnowledgeReference: VerifiedKnowledgeReferenceProof? = null,
) {
    init {
        require(
            verifiedKnowledgeReference == null ||
                verifiedKnowledgeReference.ref.knowledgeNodeId == knowledgeNodeId,
        ) {
            "Verified knowledge reference must match its binding node id"
        }
    }
}

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
    val knowledgeBindings: List<ConfirmedKnowledgeBindingRecord> = emptyList(),
)

/** Existing reviewed binding provenance; presentation adapters receive no table or DAO handle. */
data class ConfirmedKnowledgeBindingRecord(
    val knowledgeNodeId: String,
    val subject: String,
    val taxonomyVersion: String,
    val knowledgePackVersion: String,
    val manifestFingerprint: String,
    val activationGeneration: Long,
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
    /** Original zero-based question-page order when the legacy source can prove it. */
    val pageIndex: Int? = null,
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

/**
 * Temporary-session-only compare-and-merge command.
 *
 * This command never creates a mistake, learning observation, mastery event, or knowledge record.
 */
data class MergeProblemDraftSourceBundleCommand(
    val receiptReference: String,
    val batchJobId: String,
    val batchPageIndex: Int,
    val primaryDraftId: String,
    val expectedPrimaryRevisionNumber: Int,
    val expectedPrimarySessionVersion: Long,
    val expectedPrimaryAssetOrderFingerprint: String,
    val expectedPrimarySourceAssetIds: List<String>,
    val followingDraftId: String,
    val expectedFollowingRevisionNumber: Int,
    val expectedFollowingSessionVersion: Long,
    val expectedFollowingAssetOrderFingerprint: String,
    val expectedFollowingSourceAssetIds: List<String>,
    val mergedAssetOrderFingerprint: String,
    val mergedSessionVersion: Long,
    val requestCanonicalFingerprint: String,
    val mergedAtEpochMillis: Long,
) {
    init {
        require(batchJobId.isNotBlank())
        require(batchPageIndex >= 0)
        require(primaryDraftId.isNotBlank() && followingDraftId.isNotBlank())
        require(primaryDraftId != followingDraftId)
        require(expectedPrimaryRevisionNumber > 0 && expectedFollowingRevisionNumber > 0)
        require(expectedPrimarySessionVersion > 0 && expectedFollowingSessionVersion > 0)
        require(mergedSessionVersion > 0)
        listOf(
            receiptReference,
            expectedPrimaryAssetOrderFingerprint,
            expectedFollowingAssetOrderFingerprint,
            mergedAssetOrderFingerprint,
            requestCanonicalFingerprint,
        ).forEach { fingerprint ->
            require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        }
        require(
            receiptReference ==
                CaptureMergeSessionReceiptReference.forBatchBoundary(
                    jobId = batchJobId,
                    pageIndex = batchPageIndex,
                    primaryDraftSessionId = primaryDraftId,
                    followingDraftSessionId = followingDraftId,
                ),
        )
        require(
            expectedPrimarySourceAssetIds.isNotEmpty() &&
                expectedFollowingSourceAssetIds.isNotEmpty(),
        )
        val mergedSourceAssetIds =
            expectedPrimarySourceAssetIds + expectedFollowingSourceAssetIds
        require(mergedSourceAssetIds.size <= 8)
        require(
            mergedSourceAssetIds.all(String::isNotBlank) &&
                mergedSourceAssetIds.distinct().size == mergedSourceAssetIds.size,
        )
        require(mergedAtEpochMillis >= 0)
    }
}

data class CaptureDraftMergeSessionReceiptRecord(
    val receiptReference: String,
    val batchJobId: String,
    val batchPageIndex: Int,
    val primaryDraftId: String,
    val followingDraftId: String,
    val mergedDraftId: String,
    val assetOrderFingerprint: String,
    val sessionVersion: Long,
    val sourceAssetCount: Int,
    val requestCanonicalFingerprint: String,
    val mergedAtEpochMillis: Long,
) {
    init {
        require(batchJobId.isNotBlank() && batchPageIndex >= 0)
        require(primaryDraftId.isNotBlank() && followingDraftId.isNotBlank())
        require(primaryDraftId != followingDraftId && mergedDraftId == primaryDraftId)
        listOf(
            receiptReference,
            assetOrderFingerprint,
            requestCanonicalFingerprint,
        ).forEach { fingerprint ->
            require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        }
        require(sessionVersion > 0 && sourceAssetCount in 2..8)
        require(mergedAtEpochMillis >= 0)
        require(
            receiptReference ==
                CaptureMergeSessionReceiptReference.forBatchBoundary(
                    jobId = batchJobId,
                    pageIndex = batchPageIndex,
                    primaryDraftSessionId = primaryDraftId,
                    followingDraftSessionId = followingDraftId,
                ),
        )
    }
}

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
    val legacyStudentSaveClaim: LegacyCaptureStudentSaveClaim? = null,
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
    val boundaryClaimedAtEpochMillis: Long?,
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
    val sourceFactId: String? = null,
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
        sourceFactId?.let { require(it.isNotBlank()) { "sourceFactId must not be blank" } }
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

