package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.domain.ReviewPacingLevel
import com.tingyun.smartmistakebook.core.domain.ReviewExamTarget
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

/**
 * One bounded request for the review landing page.
 *
 * The daily duration is a planning budget, not a requested number of questions. The repository
 * may keep an already-persisted plan for the same local day instead of replacing it.
 */
data class ReviewHomeRequest(
    val localDayEpochDay: Long,
    val timeZoneId: String,
    val requestedAtEpochMillis: Long,
    val timeBudgetSeconds: Int = ReviewPacingLevel.STANDARD.timeBudgetSeconds,
    val pacingLevel: ReviewPacingLevel = ReviewPacingLevel.STANDARD,
    val examTarget: ReviewExamTarget? = null,
) {
    init {
        require(timeZoneId.isBoundedReviewHomeText()) {
            "Review-home time-zone id is invalid"
        }
        require(requestedAtEpochMillis >= 0L) {
            "Review-home request time must not be negative"
        }
        require(
            timeBudgetSeconds in
                LearnerBoundDailyReviewPlanPort.MIN_TIME_BUDGET_SECONDS..
                    LearnerBoundDailyReviewPlanPort.MAX_TIME_BUDGET_SECONDS,
        ) {
            "Review-home time budget is outside the supported range"
        }
    }
}

data class ReviewHomePlanSummary(
    val planId: String,
    val canonicalFingerprint: String,
    val localDayEpochDay: Long,
    val timeZoneId: String,
    val timeBudgetSeconds: Int,
    val scheduledItemCount: Int,
    val completedItemCount: Int,
    val skippedItemCount: Int,
    val remainingItemCount: Int,
    val remainingEstimatedSeconds: Int,
) {
    init {
        require(planId.isBoundedReviewHomeText()) { "Review-home plan id is invalid" }
        require(SHA_256.matches(canonicalFingerprint)) {
            "Review-home plan fingerprint is invalid"
        }
        require(timeZoneId.isBoundedReviewHomeText()) {
            "Review-home plan time-zone id is invalid"
        }
        require(timeBudgetSeconds >= 0) { "Review-home plan budget must not be negative" }
        require(
            scheduledItemCount >= 0 &&
                completedItemCount >= 0 &&
                skippedItemCount >= 0 &&
                remainingItemCount >= 0,
        ) {
            "Review-home plan counts must not be negative"
        }
        require(
            completedItemCount + skippedItemCount + remainingItemCount ==
                scheduledItemCount,
        ) {
            "Review-home plan progress must account for every scheduled item"
        }
        require(remainingEstimatedSeconds in 0..timeBudgetSeconds) {
            "Review-home remaining duration must stay inside the persisted budget"
        }
    }

    val isComplete: Boolean
        get() = scheduledItemCount > 0 && remainingItemCount == 0
}

data class ReviewKnowledgeMastery(
    val historicalState: KnowledgeMasteryState,
    val currentRecallState: KnowledgeMasteryState,
    val trend: KnowledgeMasteryTrend,
)

/**
 * Learner-facing knowledge metadata comes only from the active reviewed knowledge pack.
 *
 * [parentRef] and [parentDisplayName] carry the one hierarchy relation needed by the compact
 * review preview. Learner state remains a nullable, independent mastery-db projection.
 */
data class ReviewKnowledgePoint(
    val ref: KnowledgeNodeRef,
    val displayName: String,
    val parentRef: KnowledgeNodeRef?,
    val parentDisplayName: String?,
    val mastery: ReviewKnowledgeMastery?,
) {
    init {
        require(displayName.isBoundedReviewHomeText()) {
            "Review knowledge display name is invalid"
        }
        require((parentRef == null) == (parentDisplayName == null)) {
            "Review knowledge parent reference and display name must appear together"
        }
        require(parentRef == null || parentRef.subject == ref.subject) {
            "Review knowledge hierarchy cannot cross subjects"
        }
        require(parentDisplayName == null || parentDisplayName.isBoundedReviewHomeText()) {
            "Review knowledge parent display name is invalid"
        }
    }
}

data class ReviewHomeProblemPreview(
    val queueItemId: String,
    val problemRevision: StudentProblemRevisionRef,
    val title: String?,
    val problemMarkdown: String,
    val estimatedDurationSeconds: Int,
    val knowledgePoints: List<ReviewKnowledgePoint>,
) {
    init {
        require(queueItemId.isBoundedReviewHomeText()) {
            "Review-home queue item id is invalid"
        }
        require(title == null || title.isBoundedReviewHomeText()) {
            "Review-home problem title is invalid"
        }
        require(
            problemMarkdown.isNotBlank() &&
                problemMarkdown.length <= MAX_PROBLEM_MARKDOWN_CHARS,
        ) {
            "Review problem Markdown is invalid"
        }
        require(estimatedDurationSeconds > 0) {
            "Review-home item duration must be positive"
        }
        require(
            knowledgePoints.isNotEmpty() &&
                knowledgePoints.size <= MAX_REVIEW_HOME_KNOWLEDGE_POINTS,
        ) {
            "Review-home item must have a bounded reviewed knowledge classification"
        }
        require(
            knowledgePoints.all { point ->
                point.ref.subject == problemRevision.problem.subject
            },
        ) {
            "Review-home knowledge points must stay in the problem subject"
        }
    }

    val stemPreview: String
        get() = problemMarkdown.take(MAX_STEM_PREVIEW_CHARS)

    companion object {
        const val MAX_STEM_PREVIEW_CHARS = 512
        const val MAX_PROBLEM_MARKDOWN_CHARS = 200_000
        const val MAX_REVIEW_HOME_KNOWLEDGE_POINTS = 32
    }
}

enum class ReviewHomeSessionStatus {
    ACTIVE,
    COMPLETED,
}

data class ReviewHomeSession(
    val sessionId: String,
    val planId: String,
    val status: ReviewHomeSessionStatus,
    val version: Long,
    val currentQueueItemId: String?,
    val currentPresentationId: String?,
    val currentPresentationStartedAtEpochMillis: Long?,
) {
    init {
        require(sessionId.isBoundedReviewHomeText()) {
            "Review-home session id is invalid"
        }
        require(planId.isBoundedReviewHomeText()) { "Review-home session plan id is invalid" }
        require(version > 0L) { "Review-home session version must be positive" }
        val hasCompletePresentation =
            currentQueueItemId != null &&
                currentPresentationId != null &&
                currentPresentationStartedAtEpochMillis != null
        val hasNoPresentation =
            currentQueueItemId == null &&
                currentPresentationId == null &&
                currentPresentationStartedAtEpochMillis == null
        require(
            when (status) {
                ReviewHomeSessionStatus.ACTIVE -> hasCompletePresentation
                ReviewHomeSessionStatus.COMPLETED -> hasNoPresentation
            },
        ) {
            "Review-home presentation fields do not match the session status"
        }
        require(
            currentPresentationStartedAtEpochMillis == null ||
                currentPresentationStartedAtEpochMillis >= 0L,
        ) {
            "Review presentation start time must not be negative"
        }
    }
}

enum class ReviewHomeUnavailableReason {
    PLAN_UNAVAILABLE,
    PLAN_CONFLICT,
    ACTIVE_SESSION_CONFLICT,
    SAVED_PROBLEM_UNAVAILABLE,
    KNOWLEDGE_CLASSIFICATION_UNAVAILABLE,
    KNOWLEDGE_DISPLAY_UNAVAILABLE,
    MASTERY_CONTEXT_UNAVAILABLE,
}

sealed interface ReviewHomeState {
    data class Ready(
        val plan: ReviewHomePlanSummary,
        val session: ReviewHomeSession?,
        val nextProblem: ReviewHomeProblemPreview?,
    ) : ReviewHomeState {
        init {
            require(plan.remainingItemCount > 0 || nextProblem == null) {
                "A completed review plan cannot expose another problem"
            }
            require(
                nextProblem == null ||
                    session?.currentQueueItemId == null ||
                    nextProblem.queueItemId == session.currentQueueItemId,
            ) {
                "Review-home preview must match the active session item"
            }
        }
    }

    data class Unavailable(
        val reason: ReviewHomeUnavailableReason,
    ) : ReviewHomeState
}

/** Read-only review landing surface. It cannot save questions or mutate mastery. */
interface DailyReviewRepository {
    suspend fun readHome(
        request: ReviewHomeRequest,
    ): ReviewHomeState
}

data class StartDailyReviewCommand(
    val sessionId: String,
    val planId: String,
    val expectedPlanCanonicalFingerprint: String,
    val startedAtEpochMillis: Long,
) {
    init {
        require(sessionId.isBoundedReviewHomeText()) {
            "Daily review session id is invalid"
        }
        require(planId.isBoundedReviewHomeText()) { "Daily review plan id is invalid" }
        require(SHA_256.matches(expectedPlanCanonicalFingerprint)) {
            "Daily review plan fingerprint is invalid"
        }
        require(startedAtEpochMillis >= 0L) {
            "Daily review session start time must not be negative"
        }
    }
}

sealed interface StartDailyReviewResult {
    data class Ready(
        val session: ReviewHomeSession,
    ) : StartDailyReviewResult

    data class ActiveSessionConflict(
        val activeSession: ReviewHomeSession,
    ) : StartDailyReviewResult

    data object LegacyActivityConflict : StartDailyReviewResult

    data object ReloadRequired : StartDailyReviewResult
}

/** Starts or resumes only a queue already persisted in student-mistakes.db. */
interface DailyReviewSessionActionPort {
    suspend fun startOrResume(
        command: StartDailyReviewCommand,
    ): StartDailyReviewResult
}

sealed interface DailyReviewUserResponse {
    data class Choice(
        val choiceId: String,
    ) : DailyReviewUserResponse {
        init {
            require(choiceId.isBoundedReviewHomeText()) {
                "Daily-review choice id is invalid"
            }
        }
    }

    data class Numeric(
        val value: String,
        val unit: String? = null,
    ) : DailyReviewUserResponse {
        init {
            require(value.isBoundedReviewHomeText()) {
                "Daily-review numeric response is invalid"
            }
            require(unit == null || unit.isBoundedReviewHomeText()) {
                "Daily-review numeric unit is invalid"
            }
        }
    }

    data class VisualTarget(
        val targetId: String,
    ) : DailyReviewUserResponse {
        init {
            require(targetId.isBoundedReviewHomeText()) {
                "Daily-review visual target is invalid"
            }
        }
    }
}

/**
 * The complete feature-to-host answer payload.
 *
 * It deliberately contains no correctness, knowledge, weight, attempt, hint, reveal, session,
 * question-generation, or verification-policy fields. Those facts belong to the local owner.
 */
data class DailyReviewRawAnswerSubmission(
    val response: DailyReviewUserResponse,
)

sealed interface DailyReviewAnswerSubmissionResult {
    data class Recorded(
        val duplicate: Boolean,
    ) : DailyReviewAnswerSubmissionResult

    data object ReloadRequired : DailyReviewAnswerSubmissionResult

    data object Rejected : DailyReviewAnswerSubmissionResult

    data object VerifierUnavailable : DailyReviewAnswerSubmissionResult
}

/**
 * Session-bound, host-issued answer port. Feature and model code can submit only raw learner input.
 */
fun interface DailyReviewAnswerSubmissionPort {
    suspend fun submit(
        submission: DailyReviewRawAnswerSubmission,
    ): DailyReviewAnswerSubmissionResult
}

/**
 * Opens an exact verifier for the currently presented saved question.
 *
 * The production capability carries this factory rather than a verifier from a previous session.
 * A missing or stale verifier returns null and the feature must remain fail-closed.
 */
fun interface DailyReviewAnswerSubmissionPortFactory {
    suspend fun open(
        home: ReviewHomeState.Ready,
    ): DailyReviewAnswerSubmissionPort?
}

/** Assistance belongs to the exact presentation, not to a question in the abstract. */
enum class DailyReviewAssistanceKind {
    HINT,
    ANSWER_REVEAL,
}

sealed interface DailyReviewAssistanceResult {
    data class Recorded(
        val duplicate: Boolean,
    ) : DailyReviewAssistanceResult

    /**
     * The learner may still see the requested help, but this presentation must no longer create
     * strong answer evidence. This is deliberately fail-open for teaching and fail-closed for
     * mastery writes.
     */
    data object PresentationDisqualified : DailyReviewAssistanceResult
}

/** Records assistance before the feature publishes the corresponding learner-visible content. */
fun interface DailyReviewAssistanceActionPort {
    suspend fun record(
        home: ReviewHomeState.Ready,
        assistanceEventId: String,
        kind: DailyReviewAssistanceKind,
    ): DailyReviewAssistanceResult
}

enum class DailyReviewPacingSignal {
    DONE,
    STUCK,
}

data class DailyReviewPacingCommand(
    val signal: DailyReviewPacingSignal,
    val transitionId: String,
    val sessionId: String,
    val queueItemId: String,
    val expectedSessionVersion: Long,
    val presentationId: String,
    val nextAvailableAtEpochMillis: Long,
    val nextDueAtEpochMillis: Long?,
    val schedulingPolicyVersion: String,
    val elapsedDurationMillis: Long,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(transitionId.isBoundedReviewHomeText()) {
            "Review pacing transition id is invalid"
        }
        require(sessionId.isBoundedReviewHomeText()) {
            "Review pacing session id is invalid"
        }
        require(queueItemId.isBoundedReviewHomeText()) {
            "Review pacing queue-item id is invalid"
        }
        require(expectedSessionVersion > 0L) {
            "Review pacing session version must be positive"
        }
        require(presentationId.isBoundedReviewHomeText()) {
            "Review pacing presentation id is invalid"
        }
        require(occurredAtEpochMillis >= 0L) {
            "Review pacing time must not be negative"
        }
        require(elapsedDurationMillis in 0L..MAX_PACING_ELAPSED_DURATION_MILLIS) {
            "Review pacing elapsed duration is outside the supported range"
        }
        require(nextAvailableAtEpochMillis >= occurredAtEpochMillis) {
            "Review pacing availability cannot precede the report"
        }
        require(
            nextDueAtEpochMillis == null ||
                nextDueAtEpochMillis >= nextAvailableAtEpochMillis,
        ) {
            "Review pacing due time cannot precede availability"
        }
        require(schedulingPolicyVersion.isBoundedReviewHomeText()) {
            "Review pacing scheduling-policy version is invalid"
        }
    }

    companion object {
        const val MAX_PACING_ELAPSED_DURATION_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

/**
 * Creates one learner-visible whole-problem pacing transition.
 *
 * The implementation owns scheduling and idempotency details; feature code supplies neither
 * intervals nor mastery claims.
 */
fun interface DailyReviewPacingCommandFactory {
    fun create(
        signal: DailyReviewPacingSignal,
        home: ReviewHomeState.Ready,
    ): DailyReviewPacingCommand
}

sealed interface DailyReviewPacingResult {
    data class Recorded(
        val session: ReviewHomeSession,
        val duplicate: Boolean,
    ) : DailyReviewPacingResult

    data object ReloadRequired : DailyReviewPacingResult
}

/**
 * Student-only pacing signal. Implementations must not turn DONE or STUCK into mastery evidence.
 */
interface DailyReviewPacingActionPort {
    suspend fun record(
        command: DailyReviewPacingCommand,
    ): DailyReviewPacingResult
}

private val SHA_256 = Regex("[0-9a-f]{64}")

private fun String.isBoundedReviewHomeText(): Boolean =
    isNotBlank() &&
        this == trim() &&
        length <= 4_096 &&
        none { it.isISOControl() && it != '\n' && it != '\t' }
