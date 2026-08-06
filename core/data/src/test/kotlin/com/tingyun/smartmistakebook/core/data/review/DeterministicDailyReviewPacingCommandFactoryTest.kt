package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicDailyReviewPacingCommandFactoryTest {
    @Test
    fun fixedPresentationAndClockProduceStableWholeProblemCommands() {
        val factory = DeterministicDailyReviewPacingCommandFactory { NOW }
        val home = activeHome()

        val done = factory.create(DailyReviewPacingSignal.DONE, home)
        val doneReplay = factory.create(DailyReviewPacingSignal.DONE, home)
        val stuck = factory.create(DailyReviewPacingSignal.STUCK, home)

        assertEquals(done, doneReplay)
        assertEquals(NOW - PRESENTED_AT, done.elapsedDurationMillis)
        assertEquals(3L * 24L * 60L * 60L * 1_000L, done.nextAvailableAtEpochMillis - NOW)
        assertEquals(12L * 60L * 60L * 1_000L, stuck.nextAvailableAtEpochMillis - NOW)
        assertTrue(done.transitionId != stuck.transitionId)
        assertTrue(
            DailyReviewPacingCommand::class.java.declaredFields.none { field ->
                field.name.contains("mastery", ignoreCase = true) ||
                    field.name.contains("knowledge", ignoreCase = true) ||
                    field.name.contains("answer", ignoreCase = true) ||
                    field.name.contains("observation", ignoreCase = true)
            },
        )
    }

    @Test
    fun staleOrMismatchedPresentationCannotCreatePacingCommand() {
        val factory = DeterministicDailyReviewPacingCommandFactory { PRESENTED_AT - 1L }
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(DailyReviewPacingSignal.DONE, activeHome())
        }

        assertThrows(IllegalArgumentException::class.java) {
            val home = activeHome()
            home.copy(
                session =
                    requireNotNull(home.session).copy(
                        currentQueueItemId = "another-queue",
                    ),
            )
        }
    }

    @Test
    fun backgroundResidencyAndImmediateTapsDoNotDistortThePersonalEstimate() {
        val backgroundHome =
            activeHome().copy(
                session =
                    requireNotNull(activeHome().session).copy(
                        currentPresentationStartedAtEpochMillis = 0L,
                    ),
            )
        val immediateHome =
            activeHome().copy(
                session =
                    requireNotNull(activeHome().session).copy(
                        currentPresentationStartedAtEpochMillis = NOW,
                    ),
            )
        val factory = DeterministicDailyReviewPacingCommandFactory { NOW }

        assertEquals(
            180_000L,
            factory.create(DailyReviewPacingSignal.DONE, backgroundHome)
                .elapsedDurationMillis,
        )
        assertEquals(
            180_000L,
            factory.create(DailyReviewPacingSignal.STUCK, immediateHome)
                .elapsedDurationMillis,
        )
    }

    private companion object {
        const val NOW = 2_000_000L
        const val PRESENTED_AT = 1_880_000L
    }
}

private fun activeHome(): ReviewHomeState.Ready {
    val ref =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = "learner-local",
                    subject = SubjectKind.MATH,
                    problemId = "problem-1",
                    practiceUnitId = "practice-1",
                ),
            revisionId = "revision-1",
            revisionNumber = 1,
            documentCanonicalFingerprint = "b".repeat(64),
        )
    return ReviewHomeState.Ready(
        plan =
            ReviewHomePlanSummary(
                planId = "plan-1",
                canonicalFingerprint = "a".repeat(64),
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
                sessionId = "session-1",
                planId = "plan-1",
                status = ReviewHomeSessionStatus.ACTIVE,
                version = 1L,
                currentQueueItemId = "queue-1",
                currentPresentationId = "presentation-1",
                currentPresentationStartedAtEpochMillis = 1_880_000L,
            ),
        nextProblem =
            ReviewHomeProblemPreview(
                queueItemId = "queue-1",
                problemRevision = ref,
                title = null,
                problemMarkdown = "求函数的最值。",
                estimatedDurationSeconds = 180,
                knowledgePoints =
                    listOf(
                        ReviewKnowledgePoint(
                            ref =
                                KnowledgeNodeRef(
                                    subject = SubjectKind.MATH,
                                    knowledgeNodeId = "quadratic-function",
                                    taxonomyVersion = "taxonomy-v1",
                                    knowledgePackVersion = "pack-v1",
                                ),
                            displayName = "二次函数",
                            parentRef = null,
                            parentDisplayName = null,
                            mastery = null,
                        ),
                    ),
            ),
    )
}
