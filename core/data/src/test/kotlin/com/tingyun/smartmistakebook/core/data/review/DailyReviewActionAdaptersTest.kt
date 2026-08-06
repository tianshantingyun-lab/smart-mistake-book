package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentReviewSessionPort
import com.tingyun.smartmistakebook.core.student.mistake.database.RemoveUnreadyStudentReviewQueueItemCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.ReportStudentReviewDoneCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.ReportStudentReviewStuckCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.RevealStudentReviewAnswerCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StartStudentReviewSessionCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StartStudentReviewSessionResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewHomeAuthoritySnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueItem
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueMaintenanceResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewSessionSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewSessionStatus
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewTransitionResult
import com.tingyun.smartmistakebook.core.student.mistake.database.SubmitStudentReviewResponseCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyReviewActionAdaptersTest {
    @Test
    fun sessionActionStartsOnlyThePersistedPlanNamedByTheCaller() = runBlocking {
        val sessions = FakeReviewSessions()
        val action = StudentMistakeDailyReviewSessionActionPort(sessions)

        val result =
            action.startOrResume(
                StartDailyReviewCommand(
                    sessionId = SESSION,
                    planId = PLAN,
                    expectedPlanCanonicalFingerprint = FINGERPRINT,
                    startedAtEpochMillis = NOW,
                ),
            ) as StartDailyReviewResult.Ready

        assertEquals(PLAN, sessions.startCommands.single().planId)
        assertEquals(SESSION, result.session.sessionId)
        assertEquals(ReviewHomeSessionStatus.ACTIVE, result.session.status)
    }

    @Test
    fun doneAndStuckRemainStudentPacingTransitionsWithoutMasterySubmission() = runBlocking {
        val sessions = FakeReviewSessions()
        val ports =
            DailyReviewActionPortsFactory.create(
                sessions = sessions,
            )

        val stuck =
            ports.pacing.record(pacingCommand(DailyReviewPacingSignal.STUCK))
                as DailyReviewPacingResult.Recorded
        val done =
            ports.pacing.record(pacingCommand(DailyReviewPacingSignal.DONE))
                as DailyReviewPacingResult.Recorded

        assertEquals(1, sessions.stuckCommands.size)
        assertEquals(1, sessions.doneCommands.size)
        assertEquals(5_000L, sessions.stuckCommands.single().elapsedDurationMillis)
        assertEquals(5_000L, sessions.doneCommands.single().elapsedDurationMillis)
        assertEquals(false, stuck.duplicate)
        assertEquals(false, done.duplicate)
    }

    @Test
    fun actionFactoryExposesNoAnswerOrMasteryWriteCapability() {
        val exposedTypes =
            DailyReviewActionPorts::class.java.declaredFields
                .map { field -> field.type.name }

        assertEquals(2, exposedTypes.size)
        assertTrue(exposedTypes.any { it.endsWith("DailyReviewSessionActionPort") })
        assertTrue(exposedTypes.any { it.endsWith("DailyReviewPacingActionPort") })
        assertFalse(exposedTypes.any { it.contains("Answer") || it.contains("Evidence") })
    }

    private class FakeReviewSessions : LearnerBoundStudentReviewSessionPort {
        val startCommands = mutableListOf<StartStudentReviewSessionCommand>()
        val doneCommands = mutableListOf<ReportStudentReviewDoneCommand>()
        val stuckCommands = mutableListOf<ReportStudentReviewStuckCommand>()

        override suspend fun startOrResume(
            command: StartStudentReviewSessionCommand,
        ): StartStudentReviewSessionResult {
            startCommands += command
            return StartStudentReviewSessionResult.Ready(activeSession())
        }

        override suspend fun readActiveSession(): StudentReviewSessionSnapshot =
            activeSession()

        override suspend fun readHomeSnapshot(
            localDayEpochDay: Long,
        ): StudentReviewHomeAuthoritySnapshot =
            StudentReviewHomeAuthoritySnapshot(
                changeVersion = 0L,
                plan = null,
                activeSession = activeSession(),
            )

        override suspend fun removeUnreadyQueueItem(
            command: RemoveUnreadyStudentReviewQueueItemCommand,
        ): StudentReviewQueueMaintenanceResult =
            error("Not used by this action adapter")

        override suspend fun submitResponse(
            command: SubmitStudentReviewResponseCommand,
        ): StudentReviewTransitionResult =
            error("Not used by this action adapter")

        override suspend fun reportDone(
            command: ReportStudentReviewDoneCommand,
        ): StudentReviewTransitionResult {
            doneCommands += command
            return StudentReviewTransitionResult.Applied(activeSession())
        }

        override suspend fun reportStuck(
            command: ReportStudentReviewStuckCommand,
        ): StudentReviewTransitionResult {
            stuckCommands += command
            return StudentReviewTransitionResult.Applied(activeSession())
        }

        override suspend fun revealAnswer(
            command: RevealStudentReviewAnswerCommand,
        ): StudentReviewTransitionResult =
            error("Not used by this action adapter")
    }

    private companion object {
        const val PLAN = "review-plan"
        const val SESSION = "review-session"
        const val QUEUE = "queue-1"
        const val PRESENTATION = "presentation-1"
        const val NOW = 10_000L
        val FINGERPRINT = "a".repeat(64)
    }
}

private fun activeSession(): StudentReviewSessionSnapshot =
    StudentReviewSessionSnapshot(
        sessionId = "review-session",
        planId = "review-plan",
        status = StudentReviewSessionStatus.ACTIVE,
        sessionVersion = 1L,
        currentItem =
            StudentReviewQueueItem(
                queueItemId = "queue-1",
                problemRevision =
                    StudentProblemRevisionRef(
                        problem =
                            StudentProblemRef(
                                learnerId = "local-learner",
                                subject = SubjectKind.MATH,
                                problemId = "problem-1",
                                practiceUnitId = "practice-1",
                            ),
                        revisionId = "revision-1",
                        revisionNumber = 1,
                        documentCanonicalFingerprint = "b".repeat(64),
                    ),
                scheduledOrder = 0,
                estimatedDurationSeconds = 180,
                reasonCodes = setOf("NEWLY_SAVED"),
                state = StudentReviewQueueState.PRESENTED,
            ),
        currentPresentationId = "presentation-1",
        startedAtEpochMillis = 9_000L,
        updatedAtEpochMillis = 10_000L,
        completedAtEpochMillis = null,
    )

private fun pacingCommand(
    signal: DailyReviewPacingSignal,
): DailyReviewPacingCommand =
    DailyReviewPacingCommand(
        signal = signal,
        transitionId = "transition-${signal.name.lowercase()}",
        sessionId = "review-session",
        queueItemId = "queue-1",
        expectedSessionVersion = 1L,
        presentationId = "presentation-1",
        nextAvailableAtEpochMillis = 20_000L,
        nextDueAtEpochMillis = 30_000L,
        schedulingPolicyVersion = "schedule-v1",
        elapsedDurationMillis = 5_000L,
        occurredAtEpochMillis = 10_000L,
    )
