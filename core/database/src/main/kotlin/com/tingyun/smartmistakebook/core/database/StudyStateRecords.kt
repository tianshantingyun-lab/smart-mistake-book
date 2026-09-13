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
 * 播种、评估快照、记忆/掌握状态与复习计划会话的记录（跨 [StudyDatabasePort]）。
 *
 * 从 `StudyDatabaseRecords.kt` 拆出（审计 R-01）。
 */
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
data class ReviewPlanBundle(
    val plan: ReviewPlanRecord,
    val queue: List<ReviewQueueItemRecord>,
    val activeSession: ReviewSessionRecord?,
    val isCurrent: Boolean = false,
    /** Latest persisted session head, including terminal states; null only when never started. */
    val latestSession: ReviewSessionRecord? = null,
)
