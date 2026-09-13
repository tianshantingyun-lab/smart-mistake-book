package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationReport
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LearningModelVersion
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ReviewReason
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class StudyDataStatus {
    LOADING,
    READY,
    ERROR,
}

data class StudyCatalogEntry(
    val entryId: String,
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val subject: String,
    val title: String,
    val problemMarkdown: String,
    val sourceKey: String?,
    val isCuratedExample: Boolean,
    val chapterLabels: List<String> = emptyList(),
    val knowledgeLabels: List<String> = emptyList(),
    val masteryStatus: MasteryStatus = MasteryStatus.UNKNOWN,
    val nextReviewAtEpochMillis: Long?,
    val retrievability: Double?,
    val questionMemory: StudyQuestionMemory? = null,
)

/** Local, explainable projection for one exact practice unit. Model text never becomes truth here. */
data class StudyQuestionMemory(
    val independentRecallCount: Int,
    val assistedRecallCount: Int,
    val retrievalFailureCount: Int,
    val answerRevealCount: Int,
    val lastReviewedAtEpochMillis: Long,
    val nextReviewAtEpochMillis: Long,
    val retrievabilityAtSnapshot: Double,
    val projectionIsCurrent: Boolean,
) {
    init {
        require(
            independentRecallCount >= 0 && assistedRecallCount >= 0 &&
                retrievalFailureCount >= 0 && answerRevealCount >= 0,
        ) { "Question-memory counts must not be negative" }
        require(lastReviewedAtEpochMillis >= 0 && nextReviewAtEpochMillis >= lastReviewedAtEpochMillis) {
            "Question-memory review times are invalid"
        }
        require(retrievabilityAtSnapshot.isFinite() && retrievabilityAtSnapshot in 0.0..1.0) {
            "Question-memory retrievability must be between zero and one"
        }
    }
}

data class StudyReviewOverview(
    val planId: String? = null,
    val scheduledCount: Int = 0,
    val estimatedSeconds: Int = 0,
    val reasons: Set<ReviewReason> = emptySet(),
    /** Persisted planner order. Review UI must not substitute catalog order. */
    val scheduledPracticeUnitIds: List<String> = emptyList(),
    val activeSessionId: String? = null,
    /** Zero-based index into [scheduledPracticeUnitIds]. */
    val currentOrdinal: Int = 0,
    val sessionStateVersion: Long? = null,
    val completedToday: Boolean = false,
    val completionStreakDays: Int = 0,
    /**
     * Intake backlog (spec batch-intake §1): never-attempted questions NOT
     * yet introduced into any daily plan. They accrue no learning pressure;
     * the review surface renders them as a coverage promise
     * ("每天约 X 题新学，M 天覆盖").
     */
    val intakeBacklogCount: Int = 0,
    /** Median estimated seconds of the backlog items (preview per-day math). */
    val intakeMedianEstimateSeconds: Int = 0,
) {
    init {
        require(completionStreakDays >= 0) { "Review completion streak must not be negative" }
        require(intakeBacklogCount >= 0) { "Intake backlog must not be negative" }
        require(intakeMedianEstimateSeconds >= 0) { "Intake estimate must not be negative" }
    }
}

enum class StudyReviewSessionStatus {
    ACTIVE,
    COMPLETED,
}

data class StudyReviewSessionProgress(
    val sessionId: String,
    val planId: String,
    /** Number of queue items completed; also the zero-based index of the next item while active. */
    val currentOrdinal: Int,
    val queueSize: Int,
    val stateVersion: Long,
    val status: StudyReviewSessionStatus,
) {
    init {
        require(sessionId.isNotBlank()) { "Review session id must not be blank" }
        require(planId.isNotBlank()) { "Review plan id must not be blank" }
        require(currentOrdinal in 0..queueSize) { "Review ordinal must belong to the queue" }
        require(stateVersion >= 0) { "Review session version must not be negative" }
        require(
            (status == StudyReviewSessionStatus.COMPLETED) == (currentOrdinal == queueSize),
        ) { "A review session is complete exactly when every queued item is complete" }
    }
}

data class StudyKnowledgeSummary(
    val knowledgeNodeId: String,
    val displayName: String,
    val status: MasteryStatus,
    val conservativeMasteryScore: Double,
    val evidenceMass: Double = 0.0,
    val independentCorrectObservationCount: Int = 0,
    val lastEvidenceAtEpochMillis: Long? = null,
    val lastIndependentErrorAtEpochMillis: Long? = null,
    val subject: SubjectKind = SubjectKind.GENERAL,
    val topicPath: List<String> = emptyList(),
) {
    init {
        require(knowledgeNodeId.isNotBlank()) { "Knowledge summary id must not be blank" }
        require(displayName.isNotBlank()) { "Knowledge summary name must not be blank" }
        require(
            conservativeMasteryScore.isFinite() &&
                conservativeMasteryScore in 0.0..1.0,
        ) { "Knowledge summary lower bound must be between zero and one" }
        require(evidenceMass.isFinite() && evidenceMass >= 0.0) {
            "Knowledge summary evidence mass must not be negative"
        }
        require(independentCorrectObservationCount >= 0) {
            "Knowledge summary observation count must not be negative"
        }
        require(lastEvidenceAtEpochMillis == null || lastEvidenceAtEpochMillis >= 0) {
            "Knowledge summary latest evidence time must not be negative"
        }
        require(lastIndependentErrorAtEpochMillis == null || lastIndependentErrorAtEpochMillis >= 0) {
            "Knowledge summary latest error time must not be negative"
        }
        require(topicPath.size <= 6 && topicPath.all(String::isNotBlank)) {
            "Knowledge summary topic path is invalid"
        }
    }
}

data class StudyProfileOverview(
    val hasLearningEvidence: Boolean = false,
    val recordedAttemptCount: Int = 0,
    val newlyMasteredCount: Int = 0,
    val weaknesses: List<StudyKnowledgeSummary> = emptyList(),
    /** Strong, independent evidence exposed separately so tutors can skip obvious foundations. */
    val strengths: List<StudyKnowledgeSummary> = emptyList(),
    val projectionIsCurrent: Boolean = true,
)

/** One immutable view shared by Review, Tutor, Library, and Profile. */
data class StudyExperienceSnapshot(
    val status: StudyDataStatus = StudyDataStatus.LOADING,
    val catalog: List<StudyCatalogEntry> = emptyList(),
    val pendingCorrectionCount: Int = 0,
    val review: StudyReviewOverview = StudyReviewOverview(),
    val profile: StudyProfileOverview = StudyProfileOverview(),
    val knowledgeCoverage: StudyKnowledgeCoverageOverview = StudyKnowledgeCoverageOverview(),
    /** Stable entry point for the curated Tutor example; null while no verified item is available. */
    val tutorPracticeUnitId: String? = null,
    /** Deterministic decision derived from the same learner snapshot as every other root tab. */
    val tutorDecision: AdaptiveDecision? = null,
    val tutorExampleSaved: Boolean = false,
    val failureMessage: String? = null,
) {
    init {
        require(pendingCorrectionCount >= 0) { "Pending correction count must not be negative" }
    }

    val mistakeCount: Int
        get() = catalog.size
}

data class StudyChoiceSubmission(
    val requestId: String,
    val presentationId: String,
    val practiceUnitId: String,
    val selectedChoiceId: String,
    val responseOrdinal: Int,
    val durationSeconds: Int,
    val occurredAtEpochMillis: Long,
    /** Real hint level shown before the response; zero when no hint UI is active (A3). */
    val hintCount: Int = 0,
    /** Silent interaction signals (spec §2.14), collected without UI prompts. */
    val scrollUpCount: Int = 0,
    val interruptionCount: Int = 0,
    val awayMillis: Long = 0,
) {
    init {
        require(requestId.isNotBlank()) { "Choice request id must not be blank" }
        require(scrollUpCount >= 0 && interruptionCount >= 0) {
            "Interaction counts must not be negative"
        }
        require(awayMillis >= 0) { "Away time must not be negative" }
        require(presentationId.isNotBlank()) { "Presentation id must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(selectedChoiceId.isNotBlank()) { "Selected choice id must not be blank" }
        require(responseOrdinal > 0) { "Response ordinal must be positive" }
        require(durationSeconds >= 0) { "Response duration must not be negative" }
        require(occurredAtEpochMillis >= 0) { "Response time must not be negative" }
        require(hintCount >= 0) { "Hint count must not be negative" }
    }
}

data class StudyChoiceSubmissionResult(
    val attemptId: String,
    val created: Boolean,
    val isCorrect: Boolean,
    val evidenceReason: LearningEvidenceReason,
)

/** One review answer durably recorded together with the exact queue transition it completes. */
data class StudyReviewChoiceSubmissionResult(
    val attempt: StudyChoiceSubmissionResult,
    override val progress: StudyReviewSessionProgress,
    /** Null exactly when [progress] completed the persisted review session. */
    override val nextPracticeUnitId: String?,
) : StudyReviewAdvanceResult {
    init {
        require(
            (progress.status == StudyReviewSessionStatus.COMPLETED) ==
                (nextPracticeUnitId == null),
        ) { "A completed review session must not expose a next practice unit" }
        require(nextPracticeUnitId == null || nextPracticeUnitId.isNotBlank()) {
            "Next review practice-unit id must not be blank"
        }
    }
}

/** Common persisted review transition, independent of the UI used by the exact saved question. */
sealed interface StudyReviewAdvanceResult {
    val progress: StudyReviewSessionProgress
    val nextPracticeUnitId: String?
}

data class StudyAnswerRevealRequest(
    val requestId: String,
    val presentationId: String,
    val practiceUnitId: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "Reveal request id must not be blank" }
        require(presentationId.isNotBlank()) { "Presentation id must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Reveal time must not be negative" }
    }
}

data class StudyAnswerRevealResult(
    val outcomeId: String,
    val created: Boolean,
    val explanationMarkdown: String,
)

/**
 * Trusted application boundary. Feature code submits user intent; evidence and persistence facts
 * are derived here from verified catalog data instead of being supplied by UI code.
 */
interface StudyExperienceRepository : AutoCloseable {
    val snapshot: StateFlow<StudyExperienceSnapshot>

    suspend fun initialize()

    suspend fun refresh() = initialize()

    /** Saves the currently tutored problem as an exact, immutable mistake entry. */
    suspend fun saveTutorProblem(command: SaveTutorProblemCommand): SaveTutorProblemReceipt

    suspend fun teachingArtifact(practiceUnitId: String): VerifiedTeachingArtifact?

    suspend fun submitChoice(submission: StudyChoiceSubmission): StudyChoiceSubmissionResult

    /** Atomically records one canonical answer and advances its persisted review queue item. */
    suspend fun submitReviewChoice(
        sessionId: String,
        expectedStateVersion: Long,
        submission: StudyChoiceSubmission,
    ): StudyReviewChoiceSubmissionResult

    /**
     * 讲题判定的结算：这道无工件题在讲题会话里被检查过之后，把判定落成复习 attempt 并推进队列。
     * 判定合成（行为证据胜出）与定价（非独立、题目级 HARD/AGAIN）见 [TutorJudgedReviewSettlement]。
     * 幂等：同一（复习会话, 队列项）重复调用只生效一次；没有判定时不写、不推进。
     */
    suspend fun settleTutorJudgedReview(
        settlement: TutorJudgedReviewSettlement,
    ): TutorJudgedReviewSettlementResult

    /**
     * Persists the model's own teaching-focus output for one tutoring
     * session into the mastery database's advisory layer (three-store closed
     * loop, spec §5). Idempotent per session turn; silent by design.
     */
    suspend fun recordTeachingFocus(
        sessionId: String,
        practiceUnitId: String,
        labels: List<String>,
        cycleOrdinal: Int = 1,
    )

    /**
     * 今天知识点复习计划（spec dual-review-entry §3.2）：从今日错题复习队列的题绑定
     * 知识点范围（[extractReviewKnowledgeScope]）派生候选，用与错题同构的打分
     * （[ReviewPlanner.scoreKnowledgeNode] + [selectKnowledgeReviewQueue]）在时间预算内
     * 排出有序队列。空队列 = 今日无待复习知识点（全已掌握/新鲜）。返回 null = 今日无
     * 复习计划或学习记录不可用。
     */
    suspend fun currentKnowledgeReviewPlan(
        requestId: String,
        occurredAtEpochMillis: Long,
    ): KnowledgeReviewSessionPlan? = null

    /**
     * 知识点复习作答回写（spec dual-review-entry §3.4）：判答（对/错）→ 客观掌握度证据，
     * 走本地 [MasteryWriteGate]（冷却/配额/注意力）门控，Accepted 才落库。答对/答错都是
     * 客观信号，比模型自报更可信，但仍由本地门控做主（对齐 T6 MASTERY_UPDATE）。
     */
    suspend fun submitKnowledgeQuizFeedback(
        requestId: String,
        knowledgeNodeId: String,
        correctChoiceId: String,
        selectedChoiceId: String,
        occurredAtEpochMillis: Long,
        /** Identifies one knowledge-review session; the write quota is per session. */
        conversationId: String,
    ): KnowledgeQuizFeedbackResult

    /** The learner's stored teaching advisories, newest first (read side). */
    fun observeTeachingAdvisories(practiceUnitId: String?): Flow<List<TeachingAdvisoryRecord>>

    /**
     * The knowledge-question lattice (spec §5): the explicit (knowledge
     * node x mistake x mastery) read surface the KC-to-question weight
     * propagation is defined over.
     */
    fun observeKnowledgeQuestionLattice(): Flow<List<KnowledgeQuestionLatticeRow>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /**
     * Persists the silent debrief's misconception summary as a
     * MISCONCEPTION advisory (three-store loop). No-op when the debrief
     * found no misconception.
     */
    suspend fun recordMisconceptionAdvisory(
        sessionId: String,
        practiceUnitId: String,
        payloadMarkdown: String,
        cycleOrdinal: Int = 1,
    )

    /**
     * Replays the collected review_log under FSRS-6 and the legacy
     * exponential baseline (spec §2.20). Null before the harness sample
     * floor is met.
     */
    suspend fun evaluateSchedulingModels(): SchedulingEvaluationReport?

    /**
     * Per-source calibration (spec §2.5/A2): realized recall of the next
     * real attempt after each subjective positive report. Empty before the
     * paired-outcome floor is met; suggestions are advisory only.
     */
    suspend fun sourceCalibrations(): List<SourceCalibration> = emptyList()

    /** Per-planned-reason realized recall (spec §6 recalibration, advisory). */
    suspend fun plannedReasonCalibrations(): List<PlannedReasonCalibration> = emptyList()

    /**
     * Data-driven calibration for the mastery-evidence write gate
     * (research tutor-evidence-gate §4): rejection composition and
     * window-quota pressure from the collected chat evidence. Suggestions
     * only — constants change by human decision, never automatically.
     */
    suspend fun chatEvidenceGateCalibration(): ChatEvidenceGateCalibration.GateCalibrationReport? = null

    /**
     * Reminder minute at the learner's personal peak time bucket midpoint
     * (spec §2.12 use 2). Null before any bucket reaches the sample floor.
     */
    suspend fun suggestedReminderMinute(): Int? = null

    /**
     * Runs the local FSRS-6 parameter optimizer (spec §2.11) over the
     * collected review_log and stores the candidate parameters. Null when
     * the data volume is below the fsrs-rs fitting thresholds.
     */
    suspend fun optimizeSchedulingParameters(): FsrsParameterOptimizer.Result?

    /**
     * Experimental CMRR-style desired-retention recommendation over the
     * learner's current memory states (研究 2026-09-09 §5). Null when too few
     * cards carry memory to simulate anything meaningful.
     */
    suspend fun recommendedDesiredRetention(): OptimalRetention.Recommendation? = null

    /**
     * 开场重教材料（spec §2.16）：该题已是 leech 卡时，返回进入复习会话时**先于作答**
     * 呈现的讲解材料；否则返回 null。
     *
     * 与 [teachingArtifact] 的区别：那一道返回题目的教学工件（题目+选项），这一道返回的是
     * **针对该题所绑定知识点的讲解材料**，用于"先重教、再练"的顺序。它只读、不写事件，
     * 因此重复呈现是安全的——这一点是刻意的：若改用 `revealAnswer`（会记一条"看了答案"
     * 事件）来充当重教开场，学员随后的独立作答会被污染成"看答案后作答"。
     *
     * 默认 null = 该实现不提供重教。与"无材料"是同一结果，调用方无需区分。
     */
    suspend fun reTeachOpening(practiceUnitId: String): ReTeachOpening? = null

    /**
     * 前置补救材料（spec §2.9）：该题有一个前置知识点未达可学门槛时，返回**那个前置
     * 知识点**的讲解材料；否则返回 null。
     *
     * 与 [reTeachOpening] 的区别是**不阻塞**：补救材料与题干并列呈现，学员可以直接作答。
     * 判定用的是排程侧给该题降权时同一条规则（[KnowledgeReadiness]），所以"计划里排在前面的
     * 题"与"会话里给补救的题"不会各说各话。同样只读、不写事件。
     *
     * 默认 null = 该实现不提供前置补救。与"无缺失前置"或"前置无材料"是同一结果。
     */
    suspend fun prerequisiteRemediation(practiceUnitId: String): PrerequisiteRemediation? = null

    suspend fun revealAnswer(request: StudyAnswerRevealRequest): StudyAnswerRevealResult

    suspend fun startOrResumeReviewSession(
        requestId: String,
        occurredAtEpochMillis: Long,
    ): StudyReviewSessionProgress?

    /**
     * Ingests locally judged visual-interaction attempts as ledger evidence
     * (audit §12 / PR-11). Only decisive verdicts for active saved questions
     * with confirmed knowledge bindings are converted; everything else is
     * skipped so visual evidence can never be mis-attributed.
     * @return number of newly created ledger attempts.
     */
    suspend fun ingestVisualInteractionAttempts(): Int = 0

    /** Calibration of the shadow student model over resolved predictions (audit §6.3 / PR-07). */
    suspend fun calibrationReport(): CalibrationReport = CalibrationReport(
        modelVersion = LearningModelVersion(
            modelId = HLRPredictionAuditService.MODEL_ID,
            version = HLRPredictionAuditService.MODEL_VERSION_STRING,
            algorithmHash = HLRPredictionAuditService.ALGORITHM_HASH,
        ),
        totalPredictions = 0,
        resolvedPredictions = 0,
        overallBrierScore = 0.0,
        expectedCalibrationError = 0.0,
        maximumCalibrationDeviation = 0.0,
        buckets = emptyList(),
        generatedAtEpochMillis = 0L,
    )
}

/**
 * Command to save the currently tutored problem into the mistake library.
 * Exactly one of [problemRevisionId] (an existing immutable revision) or
 * [ephemeralProblemId] (a new problem from this conversation) must be supplied.
 */
data class SaveTutorProblemCommand(
    val conversationId: String,
    val problemRevisionId: String? = null,
    val ephemeralProblemId: String? = null,
    val sourceAssetIds: List<String> = emptyList(),
    val logicalOperationId: String,
) {
    init {
        require(conversationId.isNotBlank()) { "Conversation id must not be blank" }
        require(logicalOperationId.isNotBlank()) { "Logical operation id must not be blank" }
        require(
            (problemRevisionId == null) != (ephemeralProblemId == null),
        ) { "Exactly one of problemRevisionId or ephemeralProblemId must be supplied" }
    }
}

/**
 * Scheduling options the learner controls (spec §2.4 r*, §2.20 kill switch).
 * Both flags are read at repository construction; flipping them takes effect
 * on the next launch, keeping any single session's projection model stable.
 */
data class SchedulingOptions(
    /** Target retention r* in the supported 0.7..0.97 band. */
    val desiredRetention: Double = FsrsMemoryUpdateModel.DEFAULT_DESIRED_RETENTION,
    /** Kill switch (spec §2.20): false restores the legacy exponential model. */
    val useFsrsScheduling: Boolean = true,
) {
    init {
        require(desiredRetention in 0.7..0.97) {
            "Desired retention must be within the supported 0.7..0.97 range"
        }
    }
}

/** One user-declared exam (spec §2.17 exam mode). */
@kotlinx.serialization.Serializable
data class ExamCalendarEntry(
    val entryId: String,
    val subject: String,
    /** Local day of the exam, as an epoch day. */
    val examEpochDay: Long,
    val title: String,
) {
    init {
        require(entryId.isNotBlank()) { "Exam entry id must not be blank" }
        require(subject.isNotBlank()) { "Exam subject must not be blank" }
        require(examEpochDay >= 0) { "Exam day must not be negative" }
        require(title.isNotBlank()) { "Exam title must not be blank" }
    }
}

/**
 * Durable scheduling settings owned by the data layer (DataStore-backed in
 * production). Includes the exam calendar and any locally optimized FSRS
 * parameters awaiting the next launch.
 */
interface SchedulingSettingsStore {
    val options: Flow<SchedulingOptions>

    suspend fun setOptions(options: SchedulingOptions)

    val exams: Flow<List<ExamCalendarEntry>>

    suspend fun addExam(entry: ExamCalendarEntry)

    suspend fun removeExam(entryId: String)

    /** Non-null once [FsrsParameterOptimizer] produced a candidate parameter set. */
    val optimizedParameters: Flow<DoubleArray?>

    suspend fun setOptimizedParameters(parameters: DoubleArray?)
}

/**
 * Result of saving a tutored problem.
 */
sealed interface SaveTutorProblemReceipt {
    /** The problem was saved for the first time. */
    data class Saved(val problemId: String, val entryCount: Int) : SaveTutorProblemReceipt

    /** The exact revision was already in the library. */
    data class AlreadySaved(val problemId: String, val entryCount: Int) : SaveTutorProblemReceipt

    /** The referenced problem object no longer exists. */
    data class ReferenceNotFound(val reason: String) : SaveTutorProblemReceipt
}


/** Domain view of one lattice row (core.database's record mapped 1:1). */
data class KnowledgeQuestionLatticeRow(
    val practiceUnitId: String,
    val knowledgeNodeId: String,
    val bindingStrength: Double,
    val basisRevisionId: String,
    val bindingTaxonomyVersion: String,
    val entryId: String?,
    val entryStatus: String?,
    val kcConservativeMastery: Double?,
    val kcStatus: String?,
    val kcLastEvidenceDirection: String?,
    val kcLastEvidenceAt: Long?,
    val questionStabilityDays: Double?,
    val questionDifficulty: Double?,
    val questionNextReviewAt: Long?,
    val questionLapseCount: Int?,
    val questionCrossDayAgain: Int?,
)
