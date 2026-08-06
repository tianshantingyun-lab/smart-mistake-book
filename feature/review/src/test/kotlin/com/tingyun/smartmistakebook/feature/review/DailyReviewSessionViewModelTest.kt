package com.tingyun.smartmistakebook.feature.review

import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionPortFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionResult
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceKind
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceResult
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommand
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommandFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingResult
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingSignal
import com.tingyun.smartmistakebook.core.data.review.DailyReviewRawAnswerSubmission
import com.tingyun.smartmistakebook.core.data.review.DailyReviewUserResponse
import com.tingyun.smartmistakebook.core.data.review.ReviewHomePlanSummary
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeProblemPreview
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeSession
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeSessionStatus
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeState
import com.tingyun.smartmistakebook.core.data.review.ReviewKnowledgePoint
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DailyReviewSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun doneUsesOnlyThePacingPortAndNeverTheMasteryWritingPort() = runTest(dispatcher) {
        val pacing = FakePacingActions()
        val answers = FakeAnswerSubmissions()
        val viewModel =
            DailyReviewSessionViewModel(
                home = activeReviewHome(),
                pacingActions = pacing,
                answerSubmissionPorts = answerPortFactory(answers),
                pacingCommandFactory = commandFactory(),
            )

        viewModel.recordPacing(DailyReviewPacingSignal.DONE)
        advanceUntilIdle()

        assertEquals(DailyReviewPacingSignal.DONE, pacing.commands.single().signal)
        assertEquals(0, answers.submissions.size)
        assertTrue(
            viewModel.state.value.pendingCompletion is
                DailyReviewSessionCompletion.PacingRecorded,
        )
    }

    @Test
    fun stuckIsAOneTapPacingFactAndCanAdvanceWithoutTypedWork() = runTest(dispatcher) {
        val pacing = FakePacingActions()
        val viewModel =
            DailyReviewSessionViewModel(
                home = activeReviewHome(),
                pacingActions = pacing,
                answerSubmissionPorts = answerPortFactory(FakeAnswerSubmissions()),
                pacingCommandFactory = commandFactory(),
            )

        viewModel.recordPacing(DailyReviewPacingSignal.STUCK)
        advanceUntilIdle()

        assertEquals(DailyReviewPacingSignal.STUCK, pacing.commands.single().signal)
        assertEquals(DailyReviewSessionOperation.SAVED, viewModel.state.value.operation)
    }

    @Test
    fun nextPresentationUsesANewViewModelIdentity() {
        val first = activeReviewHome()
        val next =
            first.copy(
                session =
                    requireNotNull(first.session).copy(
                        version = requireNotNull(first.session).version + 1L,
                    ),
            )

        assertNotEquals(
            dailyReviewSessionViewModelKey(first),
            dailyReviewSessionViewModelKey(next),
        )
    }

    @Test
    fun answerEntryPassesOnlyTheRawLearnerResponseToTheHostVerifier() = runTest(dispatcher) {
        val answers = FakeAnswerSubmissions()
        val viewModel =
            DailyReviewSessionViewModel(
                home = activeReviewHome(),
                pacingActions = FakePacingActions(),
                answerSubmissionPorts = answerPortFactory(answers),
                pacingCommandFactory = commandFactory(),
            )
        val submission =
            DailyReviewRawAnswerSubmission(
                response = DailyReviewUserResponse.Choice("B"),
            )

        viewModel.submitAnswer(submission)
        advanceUntilIdle()

        assertEquals(listOf(submission), answers.submissions)
        assertTrue(
            viewModel.state.value.pendingCompletion is
                DailyReviewSessionCompletion.VerifiedAnswerRecorded,
        )
    }

    @Test
    fun missingVerifierFailsWithoutPublishingARecordedAnswer() = runTest(dispatcher) {
        val viewModel =
            DailyReviewSessionViewModel(
                home = activeReviewHome(),
                pacingActions = FakePacingActions(),
                answerSubmissionPorts = DailyReviewAnswerSubmissionPortFactory { null },
                pacingCommandFactory = commandFactory(),
            )

        viewModel.submitAnswer(
            DailyReviewRawAnswerSubmission(
                response = DailyReviewUserResponse.Choice("B"),
            ),
        )
        advanceUntilIdle()

        assertEquals(DailyReviewSessionOperation.FAILED, viewModel.state.value.operation)
        assertEquals(null, viewModel.state.value.pendingCompletion)
    }

    @Test
    fun explanationIsPublishedOnlyAfterAssistanceIsPersisted() = runTest(dispatcher) {
        val assistance = FakeAssistanceActions(DailyReviewAssistanceResult.Recorded(false))
        val home = activeReviewHome()
        val viewModel =
            DailyReviewSessionViewModel(
                home = home,
                pacingActions = FakePacingActions(),
                answerSubmissionPorts = answerPortFactory(FakeAnswerSubmissions()),
                pacingCommandFactory = commandFactory(),
                assistanceActions = assistance,
            )

        viewModel.openExplanation()
        assertEquals(null, viewModel.state.value.pendingCompletion)
        advanceUntilIdle()

        assertEquals(
            listOf(DailyReviewAssistanceKind.ANSWER_REVEAL),
            assistance.kinds,
        )
        assertTrue(
            viewModel.state.value.pendingCompletion is
                DailyReviewSessionCompletion.ExplanationReady,
        )
        assertTrue(viewModel.state.value.answerEvidenceAvailable)
        assertEquals(
            assistanceEventId(home, DailyReviewAssistanceKind.ANSWER_REVEAL),
            assistance.eventIds.single(),
        )
    }

    @Test
    fun failedAssistanceStillPublishesExplanationButBlocksAnswerEvidence() = runTest(dispatcher) {
        val answers = FakeAnswerSubmissions()
        val viewModel =
            DailyReviewSessionViewModel(
                home = activeReviewHome(),
                pacingActions = FakePacingActions(),
                answerSubmissionPorts = answerPortFactory(answers),
                pacingCommandFactory = commandFactory(),
                assistanceActions =
                    FakeAssistanceActions(
                        DailyReviewAssistanceResult.PresentationDisqualified,
                    ),
            )

        viewModel.openExplanation()
        advanceUntilIdle()

        assertTrue(
            viewModel.state.value.pendingCompletion is
                DailyReviewSessionCompletion.ExplanationReady,
        )
        assertTrue(!viewModel.state.value.answerEvidenceAvailable)
        viewModel.submitAnswer(
            DailyReviewRawAnswerSubmission(DailyReviewUserResponse.Choice("B")),
        )
        advanceUntilIdle()
        assertTrue(answers.submissions.isEmpty())
    }

    private class FakePacingActions : DailyReviewPacingActionPort {
        val commands = mutableListOf<DailyReviewPacingCommand>()

        override suspend fun record(
            command: DailyReviewPacingCommand,
        ): DailyReviewPacingResult {
            commands += command
            return DailyReviewPacingResult.Recorded(
                session =
                    ReviewHomeSession(
                        sessionId = "session-1",
                        planId = "plan-1",
                        status = ReviewHomeSessionStatus.COMPLETED,
                        version = 2L,
                        currentQueueItemId = null,
                        currentPresentationId = null,
                        currentPresentationStartedAtEpochMillis = null,
                    ),
                duplicate = false,
            )
        }
    }

    private class FakeAnswerSubmissions : DailyReviewAnswerSubmissionPort {
        val submissions = mutableListOf<DailyReviewRawAnswerSubmission>()

        override suspend fun submit(
            submission: DailyReviewRawAnswerSubmission,
        ): DailyReviewAnswerSubmissionResult {
            submissions += submission
            return DailyReviewAnswerSubmissionResult.Recorded(
                duplicate = false,
            )
        }
    }

    private class FakeAssistanceActions(
        private val result: DailyReviewAssistanceResult,
    ) : DailyReviewAssistanceActionPort {
        val eventIds = mutableListOf<String>()
        val kinds = mutableListOf<DailyReviewAssistanceKind>()

        override suspend fun record(
            home: ReviewHomeState.Ready,
            assistanceEventId: String,
            kind: DailyReviewAssistanceKind,
        ): DailyReviewAssistanceResult {
            eventIds += assistanceEventId
            kinds += kind
            return result
        }
    }
}

private fun answerPortFactory(
    port: DailyReviewAnswerSubmissionPort,
): DailyReviewAnswerSubmissionPortFactory =
    DailyReviewAnswerSubmissionPortFactory { port }

private fun commandFactory(): DailyReviewPacingCommandFactory =
    DailyReviewPacingCommandFactory { signal, home ->
        DailyReviewPacingCommand(
            signal = signal,
            transitionId = "transition-${signal.name.lowercase()}",
            sessionId = requireNotNull(home.session).sessionId,
            queueItemId = requireNotNull(home.nextProblem).queueItemId,
            expectedSessionVersion = requireNotNull(home.session).version,
            presentationId =
                requireNotNull(
                    requireNotNull(home.session).currentPresentationId,
                ),
            nextAvailableAtEpochMillis = 20_000L,
            nextDueAtEpochMillis = 30_000L,
            schedulingPolicyVersion = "pacing-v1",
            elapsedDurationMillis = 1_000L,
            occurredAtEpochMillis = 10_000L,
        )
    }

private fun activeReviewHome(): ReviewHomeState.Ready {
    val node =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = "quadratic-function",
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
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
                currentPresentationStartedAtEpochMillis = 9_000L,
            ),
        nextProblem =
            ReviewHomeProblemPreview(
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
                title = "二次函数",
                problemMarkdown = "求函数的最值。",
                estimatedDurationSeconds = 180,
                knowledgePoints =
                    listOf(
                        ReviewKnowledgePoint(
                            ref = node,
                            displayName = "二次函数",
                            parentRef = null,
                            parentDisplayName = null,
                            mastery = null,
                        ),
                    ),
            ),
    )
}
