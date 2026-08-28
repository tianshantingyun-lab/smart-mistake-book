package com.tingyun.smartmistakebook.feature.review

import androidx.lifecycle.SavedStateHandle
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReport
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionProgress
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CapturedReviewSessionViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun successfulSubmissionPersistsRecordedResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val viewModel = CapturedReviewSessionViewModel(handle)
        val expected = resultFor(StudyReviewSelfReport.RECALL_COMPLETED)

        try {
            viewModel.submit(
                report = StudyReviewSelfReport.RECALL_COMPLETED,
                practiceUnitId = PRACTICE_UNIT_ID,
                presentationId = PRESENTATION_ID,
            ) { expected }
            advanceUntilIdle()

            assertEquals(CapturedReviewSubmissionStatus.RECORDED, viewModel.status)
            assertEquals(expected, viewModel.recordedResult())
            assertFalse(viewModel.resultDispatched)

            val restored = CapturedReviewSessionViewModel(handle)
            assertEquals(CapturedReviewSubmissionStatus.RECORDED, restored.status)
            assertEquals(expected, restored.recordedResult())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedSubmissionRetriesTheExactPersistedCommandAfterRecreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val requests = mutableListOf<StudyReviewSelfReportSubmission>()

        try {
            CapturedReviewSessionViewModel(handle).submit(
                report = StudyReviewSelfReport.NEEDS_HELP,
                practiceUnitId = PRACTICE_UNIT_ID,
                presentationId = PRESENTATION_ID,
            ) { request ->
                requests += request
                error("response lost after atomic commit")
            }
            advanceUntilIdle()

            val restored = CapturedReviewSessionViewModel(handle)
            assertEquals(CapturedReviewSubmissionStatus.FAILED, restored.status)
            assertFalse(restored.canSubmit(StudyReviewSelfReport.RECALL_COMPLETED))

            val replayedResult = resultFor(
                report = StudyReviewSelfReport.NEEDS_HELP,
                created = false,
            )
            restored.submit(
                report = StudyReviewSelfReport.NEEDS_HELP,
                practiceUnitId = PRACTICE_UNIT_ID,
                presentationId = PRESENTATION_ID,
            ) { request ->
                requests += request
                replayedResult
            }
            advanceUntilIdle()

            assertEquals(2, requests.size)
            assertEquals(requests.first(), requests.last())
            assertEquals(CapturedReviewSubmissionStatus.RECORDED, restored.status)
            assertEquals(replayedResult, restored.recordedResult())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun doubleTapWhileRecordingSubmitsOnlyOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = CapturedReviewSessionViewModel(SavedStateHandle())
        val requests = mutableListOf<StudyReviewSelfReportSubmission>()
        val submit: suspend (StudyReviewSelfReportSubmission) -> StudyReviewSelfReportSubmissionResult =
            { request ->
                requests += request
                resultFor(request.report)
            }

        try {
            viewModel.submit(
                report = StudyReviewSelfReport.RECALL_COMPLETED,
                practiceUnitId = PRACTICE_UNIT_ID,
                presentationId = PRESENTATION_ID,
                submit = submit,
            )
            viewModel.submit(
                report = StudyReviewSelfReport.RECALL_COMPLETED,
                practiceUnitId = PRACTICE_UNIT_ID,
                presentationId = PRESENTATION_ID,
                submit = submit,
            )

            assertEquals(CapturedReviewSubmissionStatus.RECORDING, viewModel.status)
            advanceUntilIdle()

            assertEquals(1, requests.size)
            assertEquals(CapturedReviewSubmissionStatus.RECORDED, viewModel.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun resultFor(
        report: StudyReviewSelfReport,
        created: Boolean = true,
    ) = StudyReviewSelfReportSubmissionResult(
        attemptId = "attempt:self-report",
        created = created,
        report = report,
        evidenceReason = when (report) {
            StudyReviewSelfReport.RECALL_COMPLETED -> LearningEvidenceReason.SELF_REPORTED_RECALL
            StudyReviewSelfReport.RECALLED_WITH_EFFORT -> LearningEvidenceReason.CORRECT_ON_RETRY
            StudyReviewSelfReport.NEEDS_HELP -> LearningEvidenceReason.SELF_REPORTED_STUCK
        },
        progress = StudyReviewSessionProgress(
            sessionId = "review:session",
            planId = "review:plan",
            currentOrdinal = 1,
            queueSize = 2,
            stateVersion = 2,
            status = StudyReviewSessionStatus.ACTIVE,
        ),
        nextPracticeUnitId = "practice:next",
    )

    private companion object {
        const val PRACTICE_UNIT_ID = "practice:captured"
        const val PRESENTATION_ID = "presentation:captured:stable"
    }
}
