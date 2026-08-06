package com.tingyun.smartmistakebook.feature.review

import com.tingyun.smartmistakebook.core.data.review.DailyReviewRepository
import com.tingyun.smartmistakebook.core.data.review.DailyReviewSessionActionPort
import com.tingyun.smartmistakebook.core.data.review.ReviewHomePlanSummary
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeProblemPreview
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeRequest
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeSession
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeSessionStatus
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeState
import com.tingyun.smartmistakebook.core.data.review.StartDailyReviewCommand
import com.tingyun.smartmistakebook.core.data.review.StartDailyReviewResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DailyReviewViewModelTest {
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
    fun loadsOnlyTheNarrowHomeStateAndStartsItsPersistedPlan() = runTest(dispatcher) {
        val home = readyHome()
        val repository = FakeDailyReviewRepository(home)
        val sessions = FakeDailyReviewSessionActions()
        val viewModel =
            DailyReviewViewModel(
                repository = repository,
                sessionActions = sessions,
                requestProvider = ReviewHomeRequestProvider(::request),
                sessionIdFactory = DailyReviewSessionIdFactory { "new-session" },
            )

        advanceUntilIdle()
        val loaded = viewModel.state.value
        val landing = loaded.landing as ReviewLandingState.Content
        assertEquals(3, landing.scheduledCount)
        assertEquals(10, landing.estimatedMinutes)
        assertEquals(1, landing.completedCount)
        assertEquals("开始复习", landing.actionLabel)
        assertEquals(listOf(request()), repository.requests)

        viewModel.startOrContinue()
        advanceUntilIdle()

        val command = sessions.commands.single()
        assertEquals(home.plan.planId, command.planId)
        assertEquals(home.plan.canonicalFingerprint, command.expectedPlanCanonicalFingerprint)
        assertEquals("new-session", command.sessionId)
        assertNotNull(viewModel.state.value.pendingLaunch)
    }

    @Test
    fun activeSessionContinuesWithoutCreatingAnotherSession() = runTest(dispatcher) {
        val home = readyHome(session = activeSession())
        val repository = FakeDailyReviewRepository(home)
        val sessions = FakeDailyReviewSessionActions()
        val viewModel =
            DailyReviewViewModel(
                repository = repository,
                sessionActions = sessions,
                requestProvider = ReviewHomeRequestProvider(::request),
                sessionIdFactory = DailyReviewSessionIdFactory { "must-not-be-used" },
            )
        advanceUntilIdle()

        viewModel.startOrContinue()

        assertEquals(0, sessions.commands.size)
        assertEquals(home, viewModel.state.value.pendingLaunch?.home)
        val launchId = requireNotNull(viewModel.state.value.pendingLaunch).id
        viewModel.acknowledgeLaunch(launchId)
        assertNull(viewModel.state.value.pendingLaunch)
    }

    @Test
    fun repositoryFailureExposesOneRetryStateWithoutInternalReasonText() =
        runTest(dispatcher) {
            val repository =
                object : DailyReviewRepository {
                    override suspend fun readHome(
                        request: ReviewHomeRequest,
                    ): ReviewHomeState = error("database detail must stay internal")
                }
            val viewModel =
                DailyReviewViewModel(
                    repository = repository,
                    sessionActions = FakeDailyReviewSessionActions(),
                    requestProvider = ReviewHomeRequestProvider(::request),
                    sessionIdFactory = DailyReviewSessionIdFactory { "session" },
                )

            advanceUntilIdle()

            assertEquals(ReviewLandingState.Unavailable, viewModel.state.value.landing)
            assertNull(viewModel.state.value.readyHome)
            assertFalse(viewModel.state.value.pendingLaunch != null)
        }

    private class FakeDailyReviewRepository(
        private val home: ReviewHomeState,
    ) : DailyReviewRepository {
        val requests = mutableListOf<ReviewHomeRequest>()

        override suspend fun readHome(
            request: ReviewHomeRequest,
        ): ReviewHomeState {
            requests += request
            return home
        }
    }

    private class FakeDailyReviewSessionActions : DailyReviewSessionActionPort {
        val commands = mutableListOf<StartDailyReviewCommand>()

        override suspend fun startOrResume(
            command: StartDailyReviewCommand,
        ): StartDailyReviewResult {
            commands += command
            return StartDailyReviewResult.Ready(activeSession(command.sessionId))
        }
    }
}

private fun request(): ReviewHomeRequest =
    ReviewHomeRequest(
        localDayEpochDay = 20_000L,
        timeZoneId = "Asia/Shanghai",
        requestedAtEpochMillis = 10_000L,
    )

private fun readyHome(
    session: ReviewHomeSession? = null,
): ReviewHomeState.Ready =
    ReviewHomeState.Ready(
        plan =
            ReviewHomePlanSummary(
                planId = "plan-1",
                canonicalFingerprint = "a".repeat(64),
                localDayEpochDay = 20_000L,
                timeZoneId = "Asia/Shanghai",
                timeBudgetSeconds = 900,
                scheduledItemCount = 3,
                completedItemCount = 1,
                skippedItemCount = 0,
                remainingItemCount = 2,
                remainingEstimatedSeconds = 600,
            ),
        session = session,
        nextProblem = reviewProblem(),
    )

private fun activeSession(
    sessionId: String = "active-session",
): ReviewHomeSession =
    ReviewHomeSession(
        sessionId = sessionId,
        planId = "plan-1",
        status = ReviewHomeSessionStatus.ACTIVE,
        version = 1L,
        currentQueueItemId = "queue-1",
        currentPresentationId = "presentation-1",
        currentPresentationStartedAtEpochMillis = 9_000L,
    )

private fun reviewProblem(): ReviewHomeProblemPreview {
    val node =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = "quadratic-function",
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
        )
    return ReviewHomeProblemPreview(
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
    )
}
