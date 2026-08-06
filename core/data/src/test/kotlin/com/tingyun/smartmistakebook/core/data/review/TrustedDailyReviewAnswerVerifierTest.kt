package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentTrustedReviewAnswerPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewAnswerLease
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewAnswerLeaseTestFactory
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewAssistanceKind
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewAssistanceResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewResponse
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewSubmissionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedDailyReviewAnswerVerifierTest {
    @Test
    fun `feature submits only raw input through an owner-issued exact lease`() = runBlocking {
        val lease = lease()
        val owner = FakeTrustedAnswerOwner(lease)
        val ports = StudentTrustedDailyReviewAnswerSubmissionPortFactory.createPorts(owner)
        val submissionPort = checkNotNull(ports.answerSubmissionPorts.open(reviewHome(lease)))

        val result =
            submissionPort.submit(
                DailyReviewRawAnswerSubmission(
                    DailyReviewUserResponse.Numeric(value = "1.20", unit = "m"),
                ),
            )

        assertEquals(DailyReviewAnswerSubmissionResult.Recorded(false), result)
        assertEquals(1, owner.submitCount)
        assertSame(lease, owner.submittedLeases.single())
        assertEquals(
            StudentTrustedReviewResponse.Numeric(value = "1.20", unit = "m"),
            owner.responses.single(),
        )
        assertEquals(
            setOf("response"),
            DailyReviewRawAnswerSubmission::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf()) { it.name },
        )
    }

    @Test
    fun `a lease for another presentation is never exposed to the feature`() = runBlocking {
        val lease = lease()
        val owner = FakeTrustedAnswerOwner(lease)
        val ports = StudentTrustedDailyReviewAnswerSubmissionPortFactory.createPorts(owner)
        val staleHome =
            reviewHome(lease).let { home ->
                home.copy(
                    session =
                        checkNotNull(home.session).copy(
                            currentPresentationId = "different-presentation",
                        ),
                )
            }

        assertNull(ports.answerSubmissionPorts.open(staleHome))
        assertEquals(1, owner.issueCount)
        assertTrue(owner.responses.isEmpty())
    }

    @Test
    fun `an expired owner lease reloads and is revoked locally`() = runBlocking {
        val lease = lease(validThroughEpochMillis = 10_000L)
        val owner = FakeTrustedAnswerOwner(lease, nowEpochMillis = 10_001L)
        val ports = StudentTrustedDailyReviewAnswerSubmissionPortFactory.createPorts(owner)
        val submissionPort = checkNotNull(ports.answerSubmissionPorts.open(reviewHome(lease)))
        val submission =
            DailyReviewRawAnswerSubmission(
                DailyReviewUserResponse.Choice("B"),
            )

        assertEquals(DailyReviewAnswerSubmissionResult.ReloadRequired, submissionPort.submit(submission))
        assertEquals(DailyReviewAnswerSubmissionResult.ReloadRequired, submissionPort.submit(submission))
        assertEquals(1, owner.submitCount)
        assertNull(ports.answerSubmissionPorts.open(reviewHome(lease)))
    }

    @Test
    fun `a recorded submission cannot be replayed through the same feature port`() = runBlocking {
        val lease = lease()
        val owner = FakeTrustedAnswerOwner(lease)
        val ports = StudentTrustedDailyReviewAnswerSubmissionPortFactory.createPorts(owner)
        val submissionPort = checkNotNull(ports.answerSubmissionPorts.open(reviewHome(lease)))
        val submission =
            DailyReviewRawAnswerSubmission(
                DailyReviewUserResponse.VisualTarget("right-node"),
            )

        assertEquals(DailyReviewAnswerSubmissionResult.Recorded(false), submissionPort.submit(submission))
        assertEquals(DailyReviewAnswerSubmissionResult.ReloadRequired, submissionPort.submit(submission))
        assertEquals(1, owner.submitCount)
        assertEquals(
            listOf(StudentTrustedReviewResponse.VisualTarget("right-node")),
            owner.responses,
        )
        assertNull(ports.answerSubmissionPorts.open(reviewHome(lease)))
    }
}

private class FakeTrustedAnswerOwner(
    private val issuedLease: StudentTrustedReviewAnswerLease,
    var nowEpochMillis: Long = issuedLease.issuedAtEpochMillis,
) : LearnerBoundStudentTrustedReviewAnswerPort {
    override val learnerId: String = issuedLease.problemRevision.problem.learnerId
    var issueCount: Int = 0
    var submitCount: Int = 0
    val submittedLeases = mutableListOf<StudentTrustedReviewAnswerLease>()
    val responses = mutableListOf<StudentTrustedReviewResponse>()

    override suspend fun issueCurrentLease(): StudentTrustedReviewAnswerLease {
        issueCount += 1
        return issuedLease
    }

    override suspend fun submitResponse(
        lease: StudentTrustedReviewAnswerLease,
        response: StudentTrustedReviewResponse,
    ): StudentTrustedReviewSubmissionResult {
        submitCount += 1
        submittedLeases += lease
        if (lease !== issuedLease || nowEpochMillis > issuedLease.validThroughEpochMillis) {
            return StudentTrustedReviewSubmissionResult.ReloadRequired
        }
        responses += response
        return StudentTrustedReviewSubmissionResult.Recorded(
            duplicate = false,
            resultingSessionVersion = issuedLease.expectedSessionVersion + 1L,
        )
    }

    override suspend fun recordAssistance(
        lease: StudentTrustedReviewAnswerLease,
        assistanceEventId: String,
        kind: StudentTrustedReviewAssistanceKind,
    ): StudentTrustedReviewAssistanceResult = StudentTrustedReviewAssistanceResult.Recorded
}

private fun lease(
    issuedAtEpochMillis: Long = 9_000L,
    validThroughEpochMillis: Long = 11_000L,
): StudentTrustedReviewAnswerLease =
    StudentTrustedReviewAnswerLeaseTestFactory.issue(
        "review-plan-1",
        "review-session-1",
        "review-queue-1",
        5L,
        "review-presentation-1",
        problemRevision(),
        issuedAtEpochMillis,
        validThroughEpochMillis,
    )

private fun problemRevision(): StudentProblemRevisionRef =
    StudentProblemRevisionRef(
        problem =
            StudentProblemRef(
                learnerId = "local-learner",
                subject = SubjectKind.MATH,
                problemId = "problem-1",
                practiceUnitId = "practice-1",
            ),
        revisionId = "revision-3",
        revisionNumber = 3,
        documentCanonicalFingerprint = "b".repeat(64),
    )

private fun reviewHome(lease: StudentTrustedReviewAnswerLease): ReviewHomeState.Ready {
    val knowledge =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = "quadratic-function",
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
        )
    return ReviewHomeState.Ready(
        plan =
            ReviewHomePlanSummary(
                planId = lease.planId,
                canonicalFingerprint = "c".repeat(64),
                localDayEpochDay = 20_000L,
                timeZoneId = "Asia/Shanghai",
                timeBudgetSeconds = 900,
                scheduledItemCount = 1,
                completedItemCount = 0,
                skippedItemCount = 0,
                remainingItemCount = 1,
                remainingEstimatedSeconds = 180,
            ),
        session =
            ReviewHomeSession(
                sessionId = lease.sessionId,
                planId = lease.planId,
                status = ReviewHomeSessionStatus.ACTIVE,
                version = lease.expectedSessionVersion,
                currentQueueItemId = lease.queueItemId,
                currentPresentationId = lease.presentationId,
                currentPresentationStartedAtEpochMillis = 8_500L,
            ),
        nextProblem =
            ReviewHomeProblemPreview(
                queueItemId = lease.queueItemId,
                problemRevision = lease.problemRevision,
                title = "二次函数",
                problemMarkdown = "求函数的最值。",
                estimatedDurationSeconds = 180,
                knowledgePoints =
                    listOf(
                        ReviewKnowledgePoint(
                            ref = knowledge,
                            displayName = "二次函数",
                            parentRef = null,
                            parentDisplayName = null,
                            mastery = null,
                        ),
                    ),
            ),
    )
}
