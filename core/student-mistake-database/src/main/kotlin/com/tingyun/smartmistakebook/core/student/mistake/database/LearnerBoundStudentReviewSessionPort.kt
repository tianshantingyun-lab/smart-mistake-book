package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

enum class StudentReviewResponseForm {
    CHOICE,
    NUMERIC,
    VISUAL_TARGET,
}

enum class StudentReviewVerificationOutcome {
    CORRECT,
    INCORRECT,
}

enum class StudentReviewSessionStatus {
    ACTIVE,
    COMPLETED,
}

data class StudentReviewScheduleUpdate(
    val nextAvailableAtEpochMillis: Long,
    val nextDueAtEpochMillis: Long?,
    val schedulingPolicyVersion: String,
) {
    init {
        require(nextAvailableAtEpochMillis >= 0) {
            "Next review availability must not be negative"
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
}

data class StartStudentReviewSessionCommand(
    val sessionId: String,
    val planId: String,
    val expectedPlanCanonicalFingerprint: String,
    val startedAtEpochMillis: Long,
) {
    init {
        sessionId.requireStoreText("Review session id", MAX_ID_CHARS)
        planId.requireStoreText("Review plan id", MAX_ID_CHARS)
        requireSha256(
            expectedPlanCanonicalFingerprint,
            "Expected review-plan fingerprint",
        )
        require(startedAtEpochMillis >= 0) {
            "Review session start time must not be negative"
        }
    }

    internal fun canonicalFingerprint(learnerId: String): String =
        CanonicalSha256(SESSION_COMMAND_DOMAIN)
            .field("sessionId", sessionId)
            .field("learnerId", learnerId)
            .field("planId", planId)
            .field("planCanonicalFingerprint", expectedPlanCanonicalFingerprint)
            .field("startedAtEpochMillis", startedAtEpochMillis)
            .finish()

    private companion object {
        const val SESSION_COMMAND_DOMAIN = "student-review-session-command-v1"
    }
}

data class SubmitStudentReviewResponseCommand(
    val transitionId: String,
    val sessionId: String,
    val queueItemId: String,
    val expectedSessionVersion: Long,
    val presentationId: String,
    val observationId: String,
    val submissionId: String,
    val responseForm: StudentReviewResponseForm,
    val responseCanonicalFingerprint: String,
    val verificationOutcome: StudentReviewVerificationOutcome,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val verificationPolicyVersion: String,
    val elapsedDurationMillis: Long?,
    val schedule: StudentReviewScheduleUpdate,
    val capturedAtEpochMillis: Long,
) {
    init {
        requireReviewTransitionIdentity(
            transitionId = transitionId,
            sessionId = sessionId,
            queueItemId = queueItemId,
            expectedSessionVersion = expectedSessionVersion,
            presentationId = presentationId,
            occurredAtEpochMillis = capturedAtEpochMillis,
            schedule = schedule,
        )
        observationId.requireStoreText("Review observation id", MAX_ID_CHARS)
        submissionId.requireStoreText("Review submission id", MAX_ID_CHARS)
        requireSha256(responseCanonicalFingerprint, "Review response fingerprint")
        require(attemptOrdinal >= 1) { "Review attempt ordinal must be positive" }
        require(hintCount >= 0) { "Review hint count must not be negative" }
        verificationPolicyVersion.requireStoreText(
            "Review verification-policy version",
            MAX_VERSION_CHARS,
        )
        require(
            elapsedDurationMillis == null ||
                elapsedDurationMillis in 0..MAX_REVIEW_ELAPSED_DURATION_MILLIS,
        ) {
            "Review elapsed duration is outside the supported range"
        }
    }

    internal fun canonicalFingerprint(learnerId: String): String =
        reviewTransitionFingerprint(
            action = StudentReviewSessionActionKind.RESPONSE,
            learnerId = learnerId,
            transitionId = transitionId,
            sessionId = sessionId,
            queueItemId = queueItemId,
            expectedSessionVersion = expectedSessionVersion,
            presentationId = presentationId,
            occurredAtEpochMillis = capturedAtEpochMillis,
            schedule = schedule,
        ).field("observationId", observationId)
            .field("submissionId", submissionId)
            .field("responseForm", responseForm.name)
            .field("responseCanonicalFingerprint", responseCanonicalFingerprint)
            .field("verificationOutcome", verificationOutcome.name)
            .field("attemptOrdinal", attemptOrdinal)
            .field("hintCount", hintCount)
            .field("answerWasRevealed", answerWasRevealed)
            .field("verificationPolicyVersion", verificationPolicyVersion)
            .nullableField("elapsedDurationMillis", elapsedDurationMillis?.toString())
            .finish()
}

data class ReportStudentReviewStuckCommand(
    val transitionId: String,
    val sessionId: String,
    val queueItemId: String,
    val expectedSessionVersion: Long,
    val presentationId: String,
    val elapsedDurationMillis: Long,
    val schedule: StudentReviewScheduleUpdate,
    val reportedAtEpochMillis: Long,
) {
    init {
        requireReviewTransitionIdentity(
            transitionId = transitionId,
            sessionId = sessionId,
            queueItemId = queueItemId,
            expectedSessionVersion = expectedSessionVersion,
            presentationId = presentationId,
            occurredAtEpochMillis = reportedAtEpochMillis,
            schedule = schedule,
        )
        require(elapsedDurationMillis in 0..MAX_REVIEW_ELAPSED_DURATION_MILLIS) {
            "Review elapsed duration is outside the supported range"
        }
    }

    internal fun canonicalFingerprint(learnerId: String): String =
        reviewTransitionFingerprint(
            action = StudentReviewSessionActionKind.STUCK,
            learnerId = learnerId,
            transitionId = transitionId,
            sessionId = sessionId,
            queueItemId = queueItemId,
            expectedSessionVersion = expectedSessionVersion,
            presentationId = presentationId,
            occurredAtEpochMillis = reportedAtEpochMillis,
            schedule = schedule,
        ).field("elapsedDurationMillis", elapsedDurationMillis)
            .finish()
}

/**
 * Whole-problem pacing fact for a learner who finished reviewing the presented item.
 *
 * DONE is deliberately separate from [SubmitStudentReviewResponseCommand]: it advances only the
 * student-owned review queue and must never emit verified-answer or mastery evidence.
 */
data class ReportStudentReviewDoneCommand(
    val transitionId: String,
    val sessionId: String,
    val queueItemId: String,
    val expectedSessionVersion: Long,
    val presentationId: String,
    val elapsedDurationMillis: Long,
    val schedule: StudentReviewScheduleUpdate,
    val reportedAtEpochMillis: Long,
) {
    init {
        requireReviewTransitionIdentity(
            transitionId = transitionId,
            sessionId = sessionId,
            queueItemId = queueItemId,
            expectedSessionVersion = expectedSessionVersion,
            presentationId = presentationId,
            occurredAtEpochMillis = reportedAtEpochMillis,
            schedule = schedule,
        )
        require(elapsedDurationMillis in 0..MAX_REVIEW_ELAPSED_DURATION_MILLIS) {
            "Review elapsed duration is outside the supported range"
        }
    }

    internal fun canonicalFingerprint(learnerId: String): String =
        reviewTransitionFingerprint(
            action = StudentReviewSessionActionKind.DONE,
            learnerId = learnerId,
            transitionId = transitionId,
            sessionId = sessionId,
            queueItemId = queueItemId,
            expectedSessionVersion = expectedSessionVersion,
            presentationId = presentationId,
            occurredAtEpochMillis = reportedAtEpochMillis,
            schedule = schedule,
        ).field("elapsedDurationMillis", elapsedDurationMillis)
            .finish()
}

/**
 * Auditable reason for removing an unpresented item that can no longer be reviewed safely.
 *
 * This is queue maintenance only. It does not delete the saved problem and does not represent a
 * learning or mastery event.
 */
enum class StudentReviewQueueRemovalReason {
    PENDING_KNOWLEDGE_ATTRIBUTION,
    SAVED_REVISION_INELIGIBLE,
}

data class RemoveUnreadyStudentReviewQueueItemCommand(
    val maintenanceId: String,
    val planId: String,
    val expectedPlanCanonicalFingerprint: String,
    val queueItemId: String,
    val expectedProblemRevision: StudentProblemRevisionRef,
    val reason: StudentReviewQueueRemovalReason,
    val changedAtEpochMillis: Long,
) {
    init {
        maintenanceId.requireStoreText("Review queue-maintenance id", MAX_ID_CHARS)
        planId.requireStoreText("Review plan id", MAX_ID_CHARS)
        requireSha256(
            expectedPlanCanonicalFingerprint,
            "Expected review-plan fingerprint",
        )
        queueItemId.requireStoreText("Review queue-item id", MAX_ID_CHARS)
        require(changedAtEpochMillis >= 0) {
            "Review queue-maintenance time must not be negative"
        }
    }

    internal fun canonicalFingerprint(learnerId: String): String =
        CanonicalSha256(MAINTENANCE_COMMAND_DOMAIN)
            .field("maintenanceId", maintenanceId)
            .field("learnerId", learnerId)
            .field("planId", planId)
            .field("expectedPlanCanonicalFingerprint", expectedPlanCanonicalFingerprint)
            .field("queueItemId", queueItemId)
            .field("expectedProblemRevision", expectedProblemRevision.canonicalFingerprint)
            .field("reason", reason.name)
            .field("changedAtEpochMillis", changedAtEpochMillis)
            .finish()

    internal fun auditReasonCode(learnerId: String): String =
        "review-unready:${reason.name}:${canonicalFingerprint(learnerId)}"

    private companion object {
        const val MAINTENANCE_COMMAND_DOMAIN = "student-review-queue-maintenance-command-v1"
    }
}

data class RevealStudentReviewAnswerCommand(
    val transitionId: String,
    val revealId: String,
    val sessionId: String,
    val queueItemId: String,
    val expectedSessionVersion: Long,
    val presentationId: String,
    val schedule: StudentReviewScheduleUpdate,
    val revealedAtEpochMillis: Long,
) {
    init {
        revealId.requireStoreText("Review reveal id", MAX_ID_CHARS)
        requireReviewTransitionIdentity(
            transitionId = transitionId,
            sessionId = sessionId,
            queueItemId = queueItemId,
            expectedSessionVersion = expectedSessionVersion,
            presentationId = presentationId,
            occurredAtEpochMillis = revealedAtEpochMillis,
            schedule = schedule,
        )
    }

    internal fun canonicalFingerprint(learnerId: String): String =
        reviewTransitionFingerprint(
            action = StudentReviewSessionActionKind.REVEAL,
            learnerId = learnerId,
            transitionId = transitionId,
            sessionId = sessionId,
            queueItemId = queueItemId,
            expectedSessionVersion = expectedSessionVersion,
            presentationId = presentationId,
            occurredAtEpochMillis = revealedAtEpochMillis,
            schedule = schedule,
        ).field("revealId", revealId)
            .finish()

    internal fun revealCanonicalFingerprint(learnerId: String): String =
        CanonicalSha256(REVEAL_DOMAIN)
            .field("revealId", revealId)
            .field("learnerId", learnerId)
            .field("sessionId", sessionId)
            .field("queueItemId", queueItemId)
            .field("presentationId", presentationId)
            .field("revealedAtEpochMillis", revealedAtEpochMillis)
            .finish()

    private companion object {
        const val REVEAL_DOMAIN = "student-review-reveal-receipt-v1"
    }
}

data class StudentReviewSessionSnapshot(
    val sessionId: String,
    val planId: String,
    val status: StudentReviewSessionStatus,
    val sessionVersion: Long,
    val currentItem: StudentReviewQueueItem?,
    val currentPresentationId: String?,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
) {
    init {
        require(
            (status == StudentReviewSessionStatus.ACTIVE) ==
                (currentItem != null && currentPresentationId != null),
        ) {
            "Active review session must expose exactly one current presentation"
        }
        require(
            (status == StudentReviewSessionStatus.COMPLETED) ==
                (completedAtEpochMillis != null),
        ) {
            "Completed review session must expose its completion time"
        }
    }
}

/**
 * One learner-bound, transactionally consistent review-home read.
 *
 * The plan and active session must come from the same student-mistake database snapshot so a
 * normal start or transition cannot be mistaken for an ownership conflict.
 */
data class StudentReviewHomeAuthoritySnapshot(
    val changeVersion: Long,
    val plan: StudentReviewPlanSnapshot?,
    val activeSession: StudentReviewSessionSnapshot?,
) {
    init {
        require(changeVersion >= 0) {
            "Review-home authority change version must not be negative"
        }
    }
}

sealed interface StartStudentReviewSessionResult {
    data class Ready(
        val session: StudentReviewSessionSnapshot,
    ) : StartStudentReviewSessionResult

    data class ActiveSessionConflict(
        val activeSession: StudentReviewSessionSnapshot,
    ) : StartStudentReviewSessionResult

    data object LegacyActivityConflict : StartStudentReviewSessionResult

    data object ReloadRequired : StartStudentReviewSessionResult
}

sealed interface StudentReviewTransitionResult {
    data class Applied(
        val session: StudentReviewSessionSnapshot,
    ) : StudentReviewTransitionResult

    data class Duplicate(
        val session: StudentReviewSessionSnapshot,
    ) : StudentReviewTransitionResult

    data object ReloadRequired : StudentReviewTransitionResult
}

sealed interface StudentReviewQueueMaintenanceResult {
    data object Applied : StudentReviewQueueMaintenanceResult

    data object Duplicate : StudentReviewQueueMaintenanceResult

    data object ReloadRequired : StudentReviewQueueMaintenanceResult
}

interface LearnerBoundStudentReviewSessionPort {
    suspend fun startOrResume(
        command: StartStudentReviewSessionCommand,
    ): StartStudentReviewSessionResult

    suspend fun readActiveSession(): StudentReviewSessionSnapshot?

    suspend fun readHomeSnapshot(
        localDayEpochDay: Long,
    ): StudentReviewHomeAuthoritySnapshot

    suspend fun removeUnreadyQueueItem(
        command: RemoveUnreadyStudentReviewQueueItemCommand,
    ): StudentReviewQueueMaintenanceResult

    suspend fun submitResponse(
        command: SubmitStudentReviewResponseCommand,
    ): StudentReviewTransitionResult

    suspend fun reportDone(
        command: ReportStudentReviewDoneCommand,
    ): StudentReviewTransitionResult

    suspend fun reportStuck(
        command: ReportStudentReviewStuckCommand,
    ): StudentReviewTransitionResult

    suspend fun revealAnswer(
        command: RevealStudentReviewAnswerCommand,
    ): StudentReviewTransitionResult
}

private fun requireReviewTransitionIdentity(
    transitionId: String,
    sessionId: String,
    queueItemId: String,
    expectedSessionVersion: Long,
    presentationId: String,
    occurredAtEpochMillis: Long,
    schedule: StudentReviewScheduleUpdate,
) {
    transitionId.requireStoreText("Review transition id", MAX_ID_CHARS)
    sessionId.requireStoreText("Review session id", MAX_ID_CHARS)
    queueItemId.requireStoreText("Review queue-item id", MAX_ID_CHARS)
    require(expectedSessionVersion in 1 until Long.MAX_VALUE) {
        "Expected review session version is outside the supported range"
    }
    presentationId.requireStoreText("Review presentation id", MAX_ID_CHARS)
    require(occurredAtEpochMillis >= 0) {
        "Review transition time must not be negative"
    }
    require(schedule.nextAvailableAtEpochMillis >= occurredAtEpochMillis) {
        "Next review availability cannot precede the transition"
    }
}

private fun reviewTransitionFingerprint(
    action: StudentReviewSessionActionKind,
    learnerId: String,
    transitionId: String,
    sessionId: String,
    queueItemId: String,
    expectedSessionVersion: Long,
    presentationId: String,
    occurredAtEpochMillis: Long,
    schedule: StudentReviewScheduleUpdate,
): CanonicalSha256 =
    CanonicalSha256(REVIEW_TRANSITION_DOMAIN)
        .field("action", action.name)
        .field("transitionId", transitionId)
        .field("learnerId", learnerId)
        .field("sessionId", sessionId)
        .field("queueItemId", queueItemId)
        .field("expectedSessionVersion", expectedSessionVersion)
        .field("presentationId", presentationId)
        .field("occurredAtEpochMillis", occurredAtEpochMillis)
        .field("nextAvailableAtEpochMillis", schedule.nextAvailableAtEpochMillis)
        .nullableField("nextDueAtEpochMillis", schedule.nextDueAtEpochMillis?.toString())
        .field("schedulingPolicyVersion", schedule.schedulingPolicyVersion)

private const val REVIEW_TRANSITION_DOMAIN = "student-review-transition-command-v1"
private const val MAX_REVIEW_ELAPSED_DURATION_MILLIS = 24L * 60L * 60L * 1_000L
