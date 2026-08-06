package com.tingyun.smartmistakebook.core.student.mistake.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPacingContractsTest {
    @Test
    fun doneAndStuckAreDistinctIdempotentFactsWithoutMasteryPayload() {
        val schedule =
            StudentReviewScheduleUpdate(
                nextAvailableAtEpochMillis = 20_000L,
                nextDueAtEpochMillis = 30_000L,
                schedulingPolicyVersion = "whole-problem-v1",
            )
        val done =
            ReportStudentReviewDoneCommand(
                transitionId = "transition-done",
                sessionId = "session-1",
                queueItemId = "queue-1",
                expectedSessionVersion = 1L,
                presentationId = "presentation-1",
                elapsedDurationMillis = 5_000L,
                schedule = schedule,
                reportedAtEpochMillis = 10_000L,
            )
        val stuck =
            ReportStudentReviewStuckCommand(
                transitionId = "transition-stuck",
                sessionId = "session-1",
                queueItemId = "queue-1",
                expectedSessionVersion = 1L,
                presentationId = "presentation-1",
                elapsedDurationMillis = 5_000L,
                schedule = schedule,
                reportedAtEpochMillis = 10_000L,
            )

        assertEquals(done.canonicalFingerprint(LEARNER), done.canonicalFingerprint(LEARNER))
        assertEquals(stuck.canonicalFingerprint(LEARNER), stuck.canonicalFingerprint(LEARNER))
        assertNotEquals(
            done.canonicalFingerprint(LEARNER),
            stuck.canonicalFingerprint(LEARNER),
        )
        listOf(
            ReportStudentReviewDoneCommand::class.java,
            ReportStudentReviewStuckCommand::class.java,
        ).forEach { commandType ->
            assertTrue(
                commandType.declaredFields.none { field ->
                    field.name.contains("mastery", ignoreCase = true) ||
                        field.name.contains("knowledge", ignoreCase = true) ||
                        field.name.contains("answer", ignoreCase = true) ||
                        field.name.contains("observation", ignoreCase = true)
                },
            )
        }
    }

    @Test
    fun elapsedReviewTimeUpdatesOnlyABoundedRollingDurationEstimate() {
        assertEquals(
            165,
            rollingReviewDurationSeconds(
                baselineSeconds = 180,
                observedDurationMillis = 120_000L,
            ),
        )
        assertEquals(
            315,
            rollingReviewDurationSeconds(
                baselineSeconds = 180,
                observedDurationMillis = 24L * 60L * 60L * 1_000L,
            ),
        )
        assertEquals(
            180,
            rollingReviewDurationSeconds(
                baselineSeconds = 180,
                observedDurationMillis = null,
            ),
        )
    }

    @Test
    fun pacingRejectsUnboundedElapsedTimeBeforeReachingStorage() {
        assertThrows(IllegalArgumentException::class.java) {
            ReportStudentReviewDoneCommand(
                transitionId = "transition-done",
                sessionId = "session-1",
                queueItemId = "queue-1",
                expectedSessionVersion = 1L,
                presentationId = "presentation-1",
                elapsedDurationMillis = 24L * 60L * 60L * 1_000L + 1L,
                schedule =
                    StudentReviewScheduleUpdate(
                        nextAvailableAtEpochMillis = 20_000L,
                        nextDueAtEpochMillis = 30_000L,
                        schedulingPolicyVersion = "whole-problem-v1",
                    ),
                reportedAtEpochMillis = 10_000L,
            )
        }
    }

    private companion object {
        const val LEARNER = "learner-local"
    }
}
