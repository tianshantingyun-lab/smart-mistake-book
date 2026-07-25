package com.tingyun.smartmistakebook.feature.review

import androidx.lifecycle.SavedStateHandle
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealRequest
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealResult
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionProgress
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewSessionViewModelTest {
    private val assessmentItem = TutorAssessmentItem(
        id = "assessment:review",
        stemMarkdown = "测试题干",
        choices = listOf(
            TutorChoice(id = "A", markdown = "正确选项"),
            TutorChoice(id = "B", markdown = "干扰选项"),
        ),
        correctChoiceId = "A",
    )

    @Test
    fun selectionRemainsReplaceableUntilExplicitSubmit() {
        val handle = SavedStateHandle()
        val viewModel = ReviewSessionViewModel(handle)

        viewModel.select(assessmentItem, "B")
        viewModel.select(assessmentItem, "A")

        assertEquals("A", viewModel.selectedChoiceFor(assessmentItem))
        assertNull(viewModel.submittedChoiceFor(assessmentItem))
        assertEquals("A", ReviewSessionViewModel(handle).selectedChoiceFor(assessmentItem))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun explicitSubmitUsesCurrentDraftAndLocksOnlyAfterPersistence() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val viewModel = ReviewSessionViewModel(handle)
        val recorded = mutableListOf<String>()
        val presentationIds = mutableListOf<String>()

        try {
            viewModel.select(assessmentItem, "B")
            viewModel.select(assessmentItem, "A")
            viewModel.requestSubmit(
                assessmentItem,
                "practice:review",
                "presentation:review:stable",
            ) { request ->
                recorded += request.selectedChoiceId
                presentationIds += request.presentationId
                reviewResult(
                    attempt = StudyChoiceSubmissionResult(
                        attemptId = "attempt:review",
                        created = true,
                        isCorrect = true,
                        evidenceReason = LearningEvidenceReason.INDEPENDENT_CORRECT,
                    ),
                )
            }
            advanceUntilIdle()

            assertEquals(listOf("A"), recorded)
            assertEquals(listOf("presentation:review:stable"), presentationIds)
            assertEquals("A", viewModel.submittedChoiceFor(assessmentItem))
            viewModel.select(assessmentItem, "B")
            val restored = ReviewSessionViewModel(handle)
            assertEquals("A", restored.submittedChoiceFor(assessmentItem))
            assertEquals("practice:next", restored.reviewResultFor(assessmentItem)?.nextPracticeUnitId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun answerCanBeRevealedBeforeSubmission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val viewModel = ReviewSessionViewModel(handle)

        try {
            viewModel.requestReveal(
                assessmentItem,
                "practice:review",
                "presentation:review:stable",
            ) {
                StudyAnswerRevealResult(
                    outcomeId = "reveal:review",
                    created = true,
                    explanationMarkdown = "可信完整讲解",
                )
            }
            advanceUntilIdle()

            assertEquals("可信完整讲解", viewModel.revealedExplanation)
            assertNull(viewModel.submittedChoiceFor(assessmentItem))
            assertEquals(ReviewRevealStatus.REVEALED, ReviewSessionViewModel(handle).revealStatus)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedSubmitRetriesTheExactPersistedCommand() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val viewModel = ReviewSessionViewModel(handle)
        val requests = mutableListOf<StudyChoiceSubmission>()

        try {
            viewModel.select(assessmentItem, "B")
            viewModel.requestSubmit(
                assessmentItem,
                "practice:review",
                "presentation:review:stable",
            ) { request ->
                requests += request
                error("response lost after atomic commit")
            }
            advanceUntilIdle()
            waitUntilAfter(requests.single().occurredAtEpochMillis)

            val restored = ReviewSessionViewModel(handle)
            restored.select(assessmentItem, "A")
            restored.requestSubmit(
                assessmentItem,
                "practice:review",
                "presentation:review:stable",
            ) { request ->
                requests += request
                reviewResult(
                    StudyChoiceSubmissionResult(
                        attemptId = "attempt:replayed",
                        created = false,
                        isCorrect = false,
                        evidenceReason = LearningEvidenceReason.INDEPENDENT_INCORRECT,
                    ),
                )
            }
            advanceUntilIdle()

            assertEquals(requests.first(), requests.last())
            assertEquals("B", restored.submittedChoiceFor(assessmentItem))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedRevealRetriesTheExactPersistedCommand() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val requests = mutableListOf<StudyAnswerRevealRequest>()

        try {
            ReviewSessionViewModel(handle).requestReveal(
                assessmentItem,
                "practice:review",
                "presentation:review:stable",
            ) { request ->
                requests += request
                error("response lost after reveal commit")
            }
            advanceUntilIdle()
            waitUntilAfter(requests.single().occurredAtEpochMillis)

            val restored = ReviewSessionViewModel(handle)
            restored.requestReveal(
                assessmentItem,
                "practice:review",
                "presentation:review:stable",
            ) { request ->
                requests += request
                StudyAnswerRevealResult(
                    outcomeId = "reveal:replayed",
                    created = false,
                    explanationMarkdown = "可信完整讲解",
                )
            }
            advanceUntilIdle()

            assertEquals(requests.first(), requests.last())
            assertEquals(ReviewRevealStatus.REVEALED, restored.revealStatus)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun reviewResult(
        attempt: StudyChoiceSubmissionResult,
    ) = StudyReviewChoiceSubmissionResult(
        attempt = attempt,
        progress = StudyReviewSessionProgress(
            sessionId = "review:session",
            planId = "review:plan",
            currentOrdinal = 1,
            queueSize = 2,
            stateVersion = 1,
            status = StudyReviewSessionStatus.ACTIVE,
        ),
        nextPracticeUnitId = "practice:next",
    )

    private fun waitUntilAfter(epochMillis: Long) {
        while (System.currentTimeMillis() <= epochMillis) {
            Thread.yield()
        }
    }
}
