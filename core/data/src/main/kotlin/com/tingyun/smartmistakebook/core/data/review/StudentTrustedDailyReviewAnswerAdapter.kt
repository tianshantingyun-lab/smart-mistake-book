package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentTrustedReviewAnswerPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewAnswerLease
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewAssistanceKind
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewAssistanceResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewResponse
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewSubmissionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Narrows the student owner to feature-safe raw input. Answer rules, correctness verification,
 * attempt facts, response bindings, and database commands never cross this adapter.
 */
internal object StudentTrustedDailyReviewAnswerSubmissionPortFactory {
    data class Ports(
        val answerSubmissionPorts: DailyReviewAnswerSubmissionPortFactory,
        val assistanceActions: DailyReviewAssistanceActionPort,
    )

    fun createPorts(
        owner: LearnerBoundStudentTrustedReviewAnswerPort?,
        onRecorded: suspend () -> Unit = {},
    ): Ports {
        if (owner == null) {
            return Ports(
                answerSubmissionPorts = DailyReviewAnswerSubmissionPortFactory { null },
                assistanceActions =
                    DailyReviewAssistanceActionPort { _, _, _ ->
                        DailyReviewAssistanceResult.PresentationDisqualified
                    },
            )
        }
        val adapter = StudentTrustedDailyReviewAnswerAdapter(owner, onRecorded)
        return Ports(
            answerSubmissionPorts = adapter,
            assistanceActions = adapter,
        )
    }
}

private class StudentTrustedDailyReviewAnswerAdapter(
    private val owner: LearnerBoundStudentTrustedReviewAnswerPort,
    private val onRecorded: suspend () -> Unit,
) : DailyReviewAnswerSubmissionPortFactory,
    DailyReviewAssistanceActionPort {
    private val lock = Mutex()
    private var current: BoundStudentTrustedReviewLease? = null

    override suspend fun open(
        home: ReviewHomeState.Ready,
    ): DailyReviewAnswerSubmissionPort? =
        lock.withLock {
            readOrIssueExactLocked(home)
                ?.takeUnless(BoundStudentTrustedReviewLease::revoked)
                ?.let { bound ->
                    DailyReviewAnswerSubmissionPort { submission ->
                        submit(bound, submission)
                    }
                }
        }

    private suspend fun submit(
        bound: BoundStudentTrustedReviewLease,
        submission: DailyReviewRawAnswerSubmission,
    ): DailyReviewAnswerSubmissionResult {
        val result =
            lock.withLock {
                if (current !== bound || bound.revoked) {
                    return@withLock StudentTrustedReviewSubmissionResult.ReloadRequired
                }
                val ownerResult =
                    try {
                        owner.submitResponse(
                            lease = bound.owner,
                            response = submission.response.toOwnerResponse(),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        StudentTrustedReviewSubmissionResult.StorageUnavailable
                    }
                if (
                    ownerResult is StudentTrustedReviewSubmissionResult.Recorded ||
                    ownerResult == StudentTrustedReviewSubmissionResult.ReloadRequired ||
                    ownerResult == StudentTrustedReviewSubmissionResult.StorageUnavailable
                ) {
                    bound.revoked = true
                }
                ownerResult
            }
        if (result is StudentTrustedReviewSubmissionResult.Recorded) {
            try {
                onRecorded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The owner transaction and durable outbox already committed. Relay is retryable.
            }
        }
        return result.toFeatureResult()
    }

    override suspend fun record(
        home: ReviewHomeState.Ready,
        assistanceEventId: String,
        kind: DailyReviewAssistanceKind,
    ): DailyReviewAssistanceResult =
        lock.withLock {
            val bound = readOrIssueExactLocked(home)
                ?: return@withLock DailyReviewAssistanceResult.PresentationDisqualified
            val result =
                try {
                    owner.recordAssistance(
                        lease = bound.owner,
                        assistanceEventId = assistanceEventId,
                        kind = kind.toOwnerKind(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    StudentTrustedReviewAssistanceResult.Unavailable
                }
            when (result) {
                StudentTrustedReviewAssistanceResult.Recorded ->
                    DailyReviewAssistanceResult.Recorded(duplicate = false)
                StudentTrustedReviewAssistanceResult.Duplicate ->
                    DailyReviewAssistanceResult.Recorded(duplicate = true)
                StudentTrustedReviewAssistanceResult.Unavailable -> {
                    bound.revoked = true
                    DailyReviewAssistanceResult.PresentationDisqualified
                }
            }
        }

    private suspend fun readOrIssueExactLocked(
        home: ReviewHomeState.Ready,
    ): BoundStudentTrustedReviewLease? {
        current
            ?.takeIf { bound -> bound.owner.isExactFor(home) }
            ?.let { return it }
        current = null
        val lease =
            try {
                owner.issueCurrentLease()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return null
        if (owner.learnerId != lease.problemRevision.problem.learnerId || !lease.isExactFor(home)) {
            return null
        }
        return BoundStudentTrustedReviewLease(lease).also { current = it }
    }
}

private class BoundStudentTrustedReviewLease(
    val owner: StudentTrustedReviewAnswerLease,
) {
    var revoked: Boolean = false
}

private fun StudentTrustedReviewAnswerLease.isExactFor(
    home: ReviewHomeState.Ready,
): Boolean {
    val session = home.session ?: return false
    val problem = home.nextProblem ?: return false
    return home.plan.planId == planId &&
        session.planId == planId &&
        session.status == ReviewHomeSessionStatus.ACTIVE &&
        session.sessionId == sessionId &&
        session.version == expectedSessionVersion &&
        session.currentQueueItemId == queueItemId &&
        session.currentPresentationId == presentationId &&
        problem.queueItemId == queueItemId &&
        problem.problemRevision == problemRevision &&
        session.currentPresentationStartedAtEpochMillis
            ?.let { startedAt -> issuedAtEpochMillis >= startedAt } == true
}

private fun DailyReviewUserResponse.toOwnerResponse(): StudentTrustedReviewResponse =
    when (this) {
        is DailyReviewUserResponse.Choice -> StudentTrustedReviewResponse.Choice(choiceId)
        is DailyReviewUserResponse.Numeric -> StudentTrustedReviewResponse.Numeric(value, unit)
        is DailyReviewUserResponse.VisualTarget ->
            StudentTrustedReviewResponse.VisualTarget(targetId)
    }

private fun DailyReviewAssistanceKind.toOwnerKind(): StudentTrustedReviewAssistanceKind =
    when (this) {
        DailyReviewAssistanceKind.HINT -> StudentTrustedReviewAssistanceKind.HINT
        DailyReviewAssistanceKind.ANSWER_REVEAL ->
            StudentTrustedReviewAssistanceKind.ANSWER_REVEAL
    }

private fun StudentTrustedReviewSubmissionResult.toFeatureResult():
    DailyReviewAnswerSubmissionResult =
    when (this) {
        is StudentTrustedReviewSubmissionResult.Recorded ->
            DailyReviewAnswerSubmissionResult.Recorded(
                duplicate = duplicate,
            )
        StudentTrustedReviewSubmissionResult.ReloadRequired ->
            DailyReviewAnswerSubmissionResult.ReloadRequired
        StudentTrustedReviewSubmissionResult.Rejected ->
            DailyReviewAnswerSubmissionResult.Rejected
        StudentTrustedReviewSubmissionResult.StorageUnavailable ->
            DailyReviewAnswerSubmissionResult.VerifierUnavailable
    }
