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
) {
    init {
        require(completionStreakDays >= 0) { "Review completion streak must not be negative" }
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

/** A one-tap report about the student's own saved question, never a claim of verified correctness. */
enum class StudyReviewSelfReport {
    RECALL_COMPLETED,

    /** Struggled through but got there unaided — feeds the HLR assisted-correct bucket. */
    RECALLED_WITH_EFFORT,
    NEEDS_HELP,
}

/**
 * Four-button review rating (spec §2.21): the review UI only asks "费劲吗？"
 * after a correct answer (Again is recorded automatically on a wrong answer),
 * but all four ratings are accepted so detail-page annotations and future
 * surfaces can submit any of them.
 */
enum class StudyReviewRating {
    AGAIN,
    HARD,
    GOOD,
    EASY,
}

data class StudyReviewRatingSubmission(
    val requestId: String,
    val presentationId: String,
    val practiceUnitId: String,
    val rating: StudyReviewRating,
    val durationSeconds: Int,
    val occurredAtEpochMillis: Long,
    /** Silent interaction signals (spec §2.14), collected without UI prompts. */
    val scrollUpCount: Int = 0,
    val interruptionCount: Int = 0,
    val awayMillis: Long = 0,
) {
    init {
        require(requestId.isNotBlank()) { "Rating request id must not be blank" }
        require(scrollUpCount >= 0 && interruptionCount >= 0) {
            "Interaction counts must not be negative"
        }
        require(awayMillis >= 0) { "Away time must not be negative" }
        require(presentationId.isNotBlank()) { "Rating presentation id must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Rating practice unit id must not be blank" }
        require(durationSeconds >= 0) { "Rating duration must not be negative" }
        require(occurredAtEpochMillis >= 0) { "Rating time must not be negative" }
    }
}

data class StudyReviewRatingSubmissionResult(
    val attemptId: String,
    val created: Boolean,
    val rating: StudyReviewRating,
    val evidenceReason: LearningEvidenceReason,
    override val progress: StudyReviewSessionProgress,
    override val nextPracticeUnitId: String?,
    /** True when the anti-farming cooldown (spec §2.7) downgraded the report to observation-only. */
    val evidenceSuppressedByCooldown: Boolean = false,
) : StudyReviewAdvanceResult {
    init {
        require(attemptId.isNotBlank()) { "Rating attempt id must not be blank" }
        require(
            (progress.status == StudyReviewSessionStatus.COMPLETED) ==
                (nextPracticeUnitId == null),
        ) { "A completed review session must not expose a next practice unit" }
        require(nextPracticeUnitId == null || nextPracticeUnitId.isNotBlank()) {
            "Next review practice-unit id must not be blank"
        }
    }
}

data class StudyReviewSelfReportSubmission(
    val requestId: String,
    val presentationId: String,
    val practiceUnitId: String,
    val report: StudyReviewSelfReport,
    val durationSeconds: Int,
    val occurredAtEpochMillis: Long,
    /** Silent interaction signals (spec §2.14), collected without UI prompts. */
    val scrollUpCount: Int = 0,
    val interruptionCount: Int = 0,
    val awayMillis: Long = 0,
) {
    init {
        require(requestId.isNotBlank()) { "Self-report request id must not be blank" }
        require(scrollUpCount >= 0 && interruptionCount >= 0) {
            "Interaction counts must not be negative"
        }
        require(awayMillis >= 0) { "Away time must not be negative" }
        require(presentationId.isNotBlank()) { "Self-report presentation id must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Self-report practice unit id must not be blank" }
        require(durationSeconds >= 0) { "Self-report duration must not be negative" }
        require(occurredAtEpochMillis >= 0) { "Self-report time must not be negative" }
    }
}

data class StudyReviewSelfReportSubmissionResult(
    val attemptId: String,
    val created: Boolean,
    val report: StudyReviewSelfReport,
    val evidenceReason: LearningEvidenceReason,
    override val progress: StudyReviewSessionProgress,
    override val nextPracticeUnitId: String?,
) : StudyReviewAdvanceResult {
    init {
        require(attemptId.isNotBlank()) { "Self-report attempt id must not be blank" }
        require(
            (progress.status == StudyReviewSessionStatus.COMPLETED) ==
                (nextPracticeUnitId == null),
        ) { "A completed review session must not expose a next practice unit" }
        require(nextPracticeUnitId == null || nextPracticeUnitId.isNotBlank()) {
            "Next review practice-unit id must not be blank"
        }
    }
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

    /** Records a conservative question-memory report and atomically advances the persisted queue. */
    suspend fun submitReviewSelfReport(
        sessionId: String,
        expectedStateVersion: Long,
        submission: StudyReviewSelfReportSubmission,
    ): StudyReviewSelfReportSubmissionResult

    /**
     * Records a four-button review rating (spec §2.21) and atomically
     * advances the persisted queue. Anti-farming cooldowns (spec §2.7)
     * downgrade repeated subjective reports to observation-only rows in
     * review_log while the session still advances.
     */
    suspend fun submitReviewRating(
        sessionId: String,
        expectedStateVersion: Long,
        submission: StudyReviewRatingSubmission,
    ): StudyReviewRatingSubmissionResult

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

    /** Declares one exam (spec §2.17): subject plus the local exam day. */
    suspend fun declareExam(entry: ExamCalendarEntry)

    suspend fun removeExam(entryId: String)

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
