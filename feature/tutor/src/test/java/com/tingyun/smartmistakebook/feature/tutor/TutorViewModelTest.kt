package com.tingyun.smartmistakebook.feature.tutor

import androidx.lifecycle.SavedStateHandle
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealRequest
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealResult
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorViewModelTest {
    private val assessmentItem = TutorAssessmentItem(
        id = "assessment:test",
        stemMarkdown = "测试题干",
        choices = listOf(
            TutorChoice(id = "A", markdown = "正确选项"),
            TutorChoice(id = "B", markdown = "干扰选项"),
        ),
        correctChoiceId = "A",
    )

    @Test
    fun presentationKeyCannotCollideWhenIdsContainSeparators() {
        val first = tutorPresentationKey(
            practiceUnitId = "a|b",
            artifactId = "c",
            assessmentItemId = "d",
        )
        val second = tutorPresentationKey(
            practiceUnitId = "a",
            artifactId = "b|c",
            assessmentItemId = "d",
        )

        assertTrue(first != second)
    }

    @Test
    fun choiceIsReplaceableDraftUntilExplicitSubmission() {
        val handle = SavedStateHandle()
        val viewModel = TutorViewModel(handle)

        viewModel.selectChoice(assessmentItem, "B")
        viewModel.selectChoice(assessmentItem, "A")

        assertEquals("A", viewModel.selectedChoiceFor(assessmentItem))
        assertNull(viewModel.submittedChoiceFor(assessmentItem))
        assertEquals("A", TutorViewModel(handle).selectedChoiceFor(assessmentItem))
        assertNull(TutorViewModel(handle).submittedChoiceFor(assessmentItem))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun explicitSubmitRecordsOnlyTheCurrentDraftAndLocksIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val viewModel = TutorViewModel(handle)
        val submittedChoices = mutableListOf<String>()

        try {
            viewModel.selectChoice(assessmentItem, "B")
            viewModel.selectChoice(assessmentItem, "A")
            viewModel.requestSubmit(assessmentItem, "practice:test") { request ->
                submittedChoices += request.selectedChoiceId
                StudyChoiceSubmissionResult(
                    attemptId = "attempt:test",
                    created = true,
                    isCorrect = true,
                    evidenceReason = LearningEvidenceReason.INDEPENDENT_CORRECT,
                )
            }
            advanceUntilIdle()

            assertEquals(listOf("A"), submittedChoices)
            assertEquals("A", viewModel.submittedChoiceFor(assessmentItem))
            viewModel.selectChoice(assessmentItem, "B")
            viewModel.requestSubmit(assessmentItem, "practice:test") { error("must not resubmit") }
            advanceUntilIdle()
            assertEquals("A", TutorViewModel(handle).submittedChoiceFor(assessmentItem))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun directRevealWorksWithoutSubmittingAChoice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val viewModel = TutorViewModel(handle)
        var revealCalls = 0

        try {
            viewModel.requestReveal(
                assessmentItem = assessmentItem,
                practiceUnitId = "practice:test",
                reveal = {
                revealCalls += 1
                StudyAnswerRevealResult(
                    outcomeId = "reveal:test",
                    created = true,
                    explanationMarkdown = "可信完整讲解",
                )
                },
            )
            advanceUntilIdle()

            assertEquals(1, revealCalls)
            assertEquals("可信完整讲解", viewModel.revealedExplanation)
            assertNull(viewModel.submittedChoiceFor(assessmentItem))
            assertEquals(TutorRevealStatus.REVEALED, TutorViewModel(handle).revealStatus)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switchingGuidedOffCancelsAnAwaitedAnswerBeforeItBecomesEvidence() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = TutorViewModel(SavedStateHandle())
        val release = CompletableDeferred<Unit>()
        var completedWrites = 0
        var submittedRequestId: String? = null
        var cancelledRequestId: String? = null

        try {
            viewModel.selectChoice(assessmentItem, "A")
            viewModel.requestSubmit(assessmentItem, "practice:test") { submission ->
                submittedRequestId = submission.requestId
                release.await()
                completedWrites += 1
                StudyChoiceSubmissionResult(
                    attemptId = "attempt:cancelled",
                    created = true,
                    isCorrect = true,
                    evidenceReason = LearningEvidenceReason.INDEPENDENT_CORRECT,
                )
            }
            runCurrent()

            viewModel.useExplanationMode(TutorExplanationMode.DIRECT) { requestId ->
                cancelledRequestId = requestId
            }
            release.complete(Unit)
            advanceUntilIdle()

            assertEquals(0, completedWrites)
            assertEquals(submittedRequestId, cancelledRequestId)
            assertNull(viewModel.submittedChoiceFor(assessmentItem))
            assertEquals(TutorSubmissionStatus.IDLE, viewModel.submissionStatus)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun directExposurePreventsAChoiceFromBecomingFreshEvidenceAfterGuidedIsReenabled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = TutorViewModel(SavedStateHandle())
        var submissions = 0

        try {
            viewModel.synchronizePresentation("presentation:exposed", isSaved = false)
            viewModel.recordDirectExposure()
            viewModel.useExplanationMode(TutorExplanationMode.GUIDED)
            viewModel.selectChoice(assessmentItem, "A")
            viewModel.requestSubmit(assessmentItem, "practice:test") {
                submissions += 1
                error("an exposed answer must not be submitted")
            }
            advanceUntilIdle()

            assertEquals(0, submissions)
            assertNull(viewModel.submittedChoiceFor(assessmentItem))
            assertEquals(TutorSubmissionStatus.IDLE, viewModel.submissionStatus)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedSubmissionRetriesWithTheSameRequestIdentity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = TutorViewModel(SavedStateHandle())
        val requests = mutableListOf<StudyChoiceSubmission>()

        try {
            viewModel.selectChoice(assessmentItem, "B")
            viewModel.requestSubmit(assessmentItem, "practice:test") { request ->
                requests += request
                error("transient persistence failure")
            }
            advanceUntilIdle()
            assertEquals(TutorSubmissionStatus.FAILED, viewModel.submissionStatus)
            waitUntilAfter(requests.single().occurredAtEpochMillis)

            viewModel.requestSubmit(assessmentItem, "practice:test") { request ->
                requests += request
                StudyChoiceSubmissionResult(
                    attemptId = "attempt:retry",
                    created = true,
                    isCorrect = false,
                    evidenceReason = LearningEvidenceReason.INDEPENDENT_INCORRECT,
                )
            }
            advanceUntilIdle()

            assertEquals(2, requests.size)
            assertEquals(requests.first(), requests.last())
            assertEquals("B", viewModel.submittedChoiceFor(assessmentItem))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedRevealRetriesTheExactPersistedCommand() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = TutorViewModel(SavedStateHandle())
        val requests = mutableListOf<StudyAnswerRevealRequest>()

        try {
            viewModel.requestReveal(assessmentItem, "practice:test", reveal = { request ->
                requests += request
                error("response lost after persistence")
            })
            advanceUntilIdle()
            waitUntilAfter(requests.single().occurredAtEpochMillis)

            viewModel.requestReveal(assessmentItem, "practice:test", reveal = { request ->
                requests += request
                StudyAnswerRevealResult(
                    outcomeId = "reveal:retry",
                    created = false,
                    explanationMarkdown = "可信完整讲解",
                )
            })
            advanceUntilIdle()

            assertEquals(requests.first(), requests.last())
            assertEquals(TutorRevealStatus.REVEALED, viewModel.revealStatus)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switchingPracticeUnitClearsOldRevealAndIgnoresItsLateResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val viewModel = TutorViewModel(handle)
        val releaseOldReveal = CompletableDeferred<Unit>()
        val firstKey = "practice:A|artifact:A|assessment:test"
        val secondKey = "practice:B|artifact:B|assessment:test"

        try {
            viewModel.synchronizePresentation(firstKey, isSaved = false)
            viewModel.updateDraft("A 题草稿")
            viewModel.requestReveal(assessmentItem, "practice:A", reveal = {
                withContext(NonCancellable) { releaseOldReveal.await() }
                StudyAnswerRevealResult(
                    outcomeId = "reveal:A",
                    created = true,
                    explanationMarkdown = "A 题讲解",
                )
            })
            runCurrent()

            viewModel.synchronizePresentation(secondKey, isSaved = false)
            assertTrue(viewModel.isActivePresentation(secondKey))
            assertEquals(TutorRevealStatus.HIDDEN, viewModel.revealStatus)
            assertNull(viewModel.revealedExplanation)
            assertEquals("", viewModel.draft)

            releaseOldReveal.complete(Unit)
            advanceUntilIdle()

            assertEquals(TutorRevealStatus.HIDDEN, viewModel.revealStatus)
            assertNull(viewModel.revealedExplanation)
            assertTrue(TutorViewModel(handle).isActivePresentation(secondKey))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun revealInFlightBlocksSubmitUntilTheRevealIsDurable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = TutorViewModel(SavedStateHandle())
        var submitCalls = 0

        try {
            viewModel.selectChoice(assessmentItem, "A")
            viewModel.requestReveal(
                assessmentItem = assessmentItem,
                practiceUnitId = "practice:test",
                reveal = {
                    StudyAnswerRevealResult(
                        outcomeId = "reveal:test",
                        created = true,
                        explanationMarkdown = "可信完整讲解",
                    )
                },
            )
            assertEquals(TutorRevealStatus.RECORDING, viewModel.revealStatus)

            viewModel.requestSubmit(assessmentItem, "practice:test") {
                submitCalls += 1
                error("submit must wait for reveal")
            }
            assertEquals(0, submitCalls)
            advanceUntilIdle()

            viewModel.requestSubmit(assessmentItem, "practice:test") {
                submitCalls += 1
                StudyChoiceSubmissionResult(
                    attemptId = "attempt:after-reveal",
                    created = true,
                    isCorrect = true,
                    evidenceReason = LearningEvidenceReason.ANSWER_REVEALED,
                )
            }
            advanceUntilIdle()
            assertEquals(1, submitCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedSaveCanBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val viewModel = TutorViewModel(handle)

        try {
            viewModel.requestSave { error("disk full") }
            advanceUntilIdle()

            assertEquals(TutorSaveStatus.FAILED, viewModel.saveStatus)
            assertEquals(TutorSaveStatus.FAILED, TutorViewModel(handle).saveStatus)

            viewModel.requestSave { }
            advanceUntilIdle()

            assertEquals(TutorSaveStatus.SAVED, viewModel.saveStatus)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun arbitraryDraftShowsTrustNoticeWithoutDroppingDraft() {
        val handle = SavedStateHandle()
        val viewModel = TutorViewModel(handle)

        viewModel.updateDraft("请讲一道尚未校对的新题")
        viewModel.submitDraft()

        assertEquals("请讲一道尚未校对的新题", viewModel.draft)
        assertTrue(viewModel.unverifiedQuestionNoticeVisible)
    }

    private fun waitUntilAfter(epochMillis: Long) {
        while (System.currentTimeMillis() <= epochMillis) {
            Thread.yield()
        }
    }
}
