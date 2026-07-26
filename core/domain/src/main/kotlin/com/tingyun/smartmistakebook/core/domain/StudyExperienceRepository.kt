package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ReviewReason
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
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
    val lowerBoundIndependentCorrect: Double,
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
            lowerBoundIndependentCorrect.isFinite() &&
                lowerBoundIndependentCorrect in 0.0..1.0,
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

sealed interface SaveStudyMistakeResult {
    val entryCount: Int

    data class Saved(override val entryCount: Int) : SaveStudyMistakeResult

    data class AlreadySaved(override val entryCount: Int) : SaveStudyMistakeResult
}

data class StudyChoiceSubmission(
    val requestId: String,
    val presentationId: String,
    val practiceUnitId: String,
    val selectedChoiceId: String,
    val responseOrdinal: Int,
    val durationSeconds: Int,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "Choice request id must not be blank" }
        require(presentationId.isNotBlank()) { "Presentation id must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(selectedChoiceId.isNotBlank()) { "Selected choice id must not be blank" }
        require(responseOrdinal > 0) { "Response ordinal must be positive" }
        require(durationSeconds >= 0) { "Response duration must not be negative" }
        require(occurredAtEpochMillis >= 0) { "Response time must not be negative" }
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
    NEEDS_HELP,
}

data class StudyReviewSelfReportSubmission(
    val requestId: String,
    val presentationId: String,
    val practiceUnitId: String,
    val report: StudyReviewSelfReport,
    val durationSeconds: Int,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "Self-report request id must not be blank" }
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

    suspend fun saveTutorExampleMistake(): SaveStudyMistakeResult

    suspend fun teachingArtifact(practiceUnitId: String): VerifiedTeachingArtifact?

    suspend fun submitChoice(submission: StudyChoiceSubmission): StudyChoiceSubmissionResult

    /** Revokes one exact in-flight tutor submission before it can remain learning evidence. */
    fun cancelChoiceSubmission(requestId: String) = Unit

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

    suspend fun revealAnswer(request: StudyAnswerRevealRequest): StudyAnswerRevealResult

    suspend fun startOrResumeReviewSession(
        requestId: String,
        occurredAtEpochMillis: Long,
    ): StudyReviewSessionProgress?
}
