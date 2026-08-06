package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentReviewSessionPort
import com.tingyun.smartmistakebook.core.student.mistake.database.ReportStudentReviewDoneCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.ReportStudentReviewStuckCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StartStudentReviewSessionCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StartStudentReviewSessionResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewScheduleUpdate
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewSessionSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewSessionStatus
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewTransitionResult

internal class StudentMistakeDailyReviewSessionActionPort(
    private val sessions: LearnerBoundStudentReviewSessionPort,
) : DailyReviewSessionActionPort {
    override suspend fun startOrResume(
        command: StartDailyReviewCommand,
    ): StartDailyReviewResult =
        when (
            val result =
                sessions.startOrResume(
                    StartStudentReviewSessionCommand(
                        sessionId = command.sessionId,
                        planId = command.planId,
                        expectedPlanCanonicalFingerprint =
                            command.expectedPlanCanonicalFingerprint,
                        startedAtEpochMillis = command.startedAtEpochMillis,
                    ),
                )
        ) {
            is StartStudentReviewSessionResult.Ready ->
                StartDailyReviewResult.Ready(result.session.toReviewHomeSession())
            is StartStudentReviewSessionResult.ActiveSessionConflict ->
                StartDailyReviewResult.ActiveSessionConflict(
                    result.activeSession.toReviewHomeSession(),
                )
            StartStudentReviewSessionResult.LegacyActivityConflict ->
                StartDailyReviewResult.LegacyActivityConflict
            StartStudentReviewSessionResult.ReloadRequired ->
                StartDailyReviewResult.ReloadRequired
        }
}

internal class StudentMistakeDailyReviewPacingActionPort(
    private val sessions: LearnerBoundStudentReviewSessionPort,
) : DailyReviewPacingActionPort {
    override suspend fun record(
        command: DailyReviewPacingCommand,
    ): DailyReviewPacingResult {
        val result =
            when (command.signal) {
                DailyReviewPacingSignal.DONE ->
                    sessions.reportDone(
                        ReportStudentReviewDoneCommand(
                            transitionId = command.transitionId,
                            sessionId = command.sessionId,
                            queueItemId = command.queueItemId,
                            expectedSessionVersion = command.expectedSessionVersion,
                            presentationId = command.presentationId,
                            elapsedDurationMillis = command.elapsedDurationMillis,
                            schedule = command.toStudentSchedule(),
                            reportedAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    )
                DailyReviewPacingSignal.STUCK ->
                    sessions.reportStuck(
                        ReportStudentReviewStuckCommand(
                            transitionId = command.transitionId,
                            sessionId = command.sessionId,
                            queueItemId = command.queueItemId,
                            expectedSessionVersion = command.expectedSessionVersion,
                            presentationId = command.presentationId,
                            elapsedDurationMillis = command.elapsedDurationMillis,
                            schedule = command.toStudentSchedule(),
                            reportedAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    )
            }
        return result.toDailyReviewPacingResult()
    }
}

internal data class DailyReviewActionPorts(
    val sessions: DailyReviewSessionActionPort,
    val pacing: DailyReviewPacingActionPort,
)

internal object DailyReviewActionPortsFactory {
    fun create(
        sessions: LearnerBoundStudentReviewSessionPort,
    ): DailyReviewActionPorts =
        DailyReviewActionPorts(
            sessions = StudentMistakeDailyReviewSessionActionPort(sessions),
            pacing = StudentMistakeDailyReviewPacingActionPort(sessions),
        )
}

private fun DailyReviewPacingCommand.toStudentSchedule(): StudentReviewScheduleUpdate =
    StudentReviewScheduleUpdate(
        nextAvailableAtEpochMillis = nextAvailableAtEpochMillis,
        nextDueAtEpochMillis = nextDueAtEpochMillis,
        schedulingPolicyVersion = schedulingPolicyVersion,
    )

internal fun StudentReviewSessionSnapshot.toReviewHomeSession(): ReviewHomeSession =
    ReviewHomeSession(
        sessionId = sessionId,
        planId = planId,
        status =
            when (status) {
                StudentReviewSessionStatus.ACTIVE -> ReviewHomeSessionStatus.ACTIVE
                StudentReviewSessionStatus.COMPLETED -> ReviewHomeSessionStatus.COMPLETED
            },
        version = sessionVersion,
        currentQueueItemId = currentItem?.queueItemId,
        currentPresentationId = currentPresentationId,
        currentPresentationStartedAtEpochMillis =
            if (status == StudentReviewSessionStatus.ACTIVE) {
                updatedAtEpochMillis
            } else {
                null
            },
    )

private fun StudentReviewTransitionResult.toDailyReviewPacingResult():
    DailyReviewPacingResult =
    when (this) {
        is StudentReviewTransitionResult.Applied ->
            DailyReviewPacingResult.Recorded(
                session = session.toReviewHomeSession(),
                duplicate = false,
            )
        is StudentReviewTransitionResult.Duplicate ->
            DailyReviewPacingResult.Recorded(
                session = session.toReviewHomeSession(),
                duplicate = true,
            )
        StudentReviewTransitionResult.ReloadRequired ->
            DailyReviewPacingResult.ReloadRequired
    }
