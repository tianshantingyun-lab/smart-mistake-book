package com.tingyun.smartmistakebook.core.student.mistake.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerBoundStudentReviewSessionPortContractTest {
    @Test
    fun portCapturesLearnerScopeAndExposesOnlyReviewActions() {
        assertEquals(
            setOf(
                "readActiveSession",
                "readHomeSnapshot",
                "removeUnreadyQueueItem",
                "reportDone",
                "reportStuck",
                "revealAnswer",
                "startOrResume",
                "submitResponse",
            ),
            LearnerBoundStudentReviewSessionPort::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        LearnerBoundStudentReviewSessionPort::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .forEach { method ->
                assertTrue(
                    "${method.name} must not accept a learner identity",
                    method.parameterTypes.none { it == String::class.java },
                )
                assertFalse(
                    "${method.name} must not expose SQL or another authority",
                    method.toGenericString().contains("learnerId", ignoreCase = true) ||
                        method.toGenericString().contains("room", ignoreCase = true) ||
                        method.toGenericString().contains("dao", ignoreCase = true) ||
                        method.toGenericString().contains("mastery", ignoreCase = true) ||
                        method.toGenericString().contains("knowledgeBody", ignoreCase = true),
                )
            }
    }

    @Test
    fun responseCommandCarriesFactsButNoMasteryDecisionOrRawAnswer() {
        val fieldNames =
            SubmitStudentReviewResponseCommand::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map(java.lang.reflect.Field::getName)
                .toSet()
        assertTrue(
            fieldNames.containsAll(
                setOf(
                    "observationId",
                    "submissionId",
                    "responseForm",
                    "responseCanonicalFingerprint",
                    "verificationOutcome",
                    "attemptOrdinal",
                    "hintCount",
                    "answerWasRevealed",
                ),
            ),
        )
        setOf(
            "rawAnswer",
            "knowledge",
            "weight",
            "confidence",
            "mastery",
            "score",
            "family",
        ).forEach { forbidden ->
            assertTrue(
                "Response command leaks $forbidden",
                fieldNames.none { it.contains(forbidden, ignoreCase = true) },
            )
        }
    }

    @Test
    fun staleVersionAndUnboundedElapsedTimeAreRejectedLocally() {
        val schedule =
            StudentReviewScheduleUpdate(
                nextAvailableAtEpochMillis = 2,
                nextDueAtEpochMillis = null,
                schedulingPolicyVersion = "schedule-v1",
            )
        assertTrue(
            runCatching {
                responseCommand(
                    expectedSessionVersion = Long.MAX_VALUE,
                    elapsedDurationMillis = null,
                    schedule = schedule,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                responseCommand(
                    expectedSessionVersion = 1,
                    elapsedDurationMillis = 24L * 60L * 60L * 1_000L + 1,
                    schedule = schedule,
                )
            }.isFailure,
        )
    }

    private fun responseCommand(
        expectedSessionVersion: Long,
        elapsedDurationMillis: Long?,
        schedule: StudentReviewScheduleUpdate,
    ): SubmitStudentReviewResponseCommand =
        SubmitStudentReviewResponseCommand(
            transitionId = "transition-1",
            sessionId = "session-1",
            queueItemId = "queue-1",
            expectedSessionVersion = expectedSessionVersion,
            presentationId = "presentation-1",
            observationId = "observation-1",
            submissionId = "submission-1",
            responseForm = StudentReviewResponseForm.CHOICE,
            responseCanonicalFingerprint = "a".repeat(64),
            verificationOutcome = StudentReviewVerificationOutcome.CORRECT,
            attemptOrdinal = 1,
            hintCount = 0,
            answerWasRevealed = false,
            verificationPolicyVersion = "verification-v1",
            elapsedDurationMillis = elapsedDurationMillis,
            schedule = schedule,
            capturedAtEpochMillis = 1,
        )
}
