package com.tingyun.smartmistakebook.feature.review

import androidx.lifecycle.SavedStateHandle
import com.tingyun.smartmistakebook.core.domain.KnowledgeQuizFeedbackResult
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewQueueEntry
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewSessionPlan
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识点复习会话单元（spec dual-review-entry §3.3/§3.4）：单知识点展示单元——进入后向模型
 * 要题（loading）→ 得选择题 → 学生选 → 判答（本地 evaluateChoice）→ 回写掌握度 →
 * 下一知识点/完成。ViewModel 只持状态 + 命令持久化，dispatch 经回调注入，纯 JVM 可测。
 */
class KnowledgeReviewSessionViewModelTest {

    private val plan = KnowledgeReviewSessionPlan(
        queue = listOf(
            entry("kc-confl", "冲突的知识点"),
            entry("kc-fresh", "无证据知识点"),
        ),
    )

    private fun entry(knowledgeNodeId: String, displayName: String) = KnowledgeReviewQueueEntry(
        knowledgeNodeId = knowledgeNodeId,
        subject = "MATH",
        displayName = displayName,
        masteryScore = 0.3,
        lastEvidenceAtEpochMillis = 1_000,
    )

    private fun quizItem(nodeId: String) = TutorAssessmentItem(
        id = "knowledge-quiz:$nodeId:1",
        stemMarkdown = "考察 $nodeId 的选择题",
        choices = listOf(
            TutorChoice(id = "A", markdown = "正确项"),
            TutorChoice(id = "B", markdown = "干扰项"),
        ),
        correctChoiceId = "A",
        knowledgeNodeIds = setOf(nodeId),
    )

    @Test
    fun startsAtFirstNodeIdleWithoutItem() {
        val viewModel = KnowledgeReviewSessionViewModel(
            savedStateHandle = SavedStateHandle(),
            plan = plan,
        )
        assertEquals(KnowledgeQuizLoadStatus.IDLE, viewModel.loadStatus)
        assertEquals(2, viewModel.queueSize)
        assertEquals("kc-confl", viewModel.currentEntry?.knowledgeNodeId)
        assertNull(viewModel.currentItem)
        assertFalse(viewModel.completed)
    }

    @Test
    fun restoreFromProcessDeathKeepsNodeIndex() {
        val handle = SavedStateHandle().apply {
            set(KnowledgeReviewSessionViewModel.CURRENT_INDEX_KEY, 1)
        }
        val viewModel = KnowledgeReviewSessionViewModel(
            savedStateHandle = handle,
            plan = plan,
        )
        assertEquals("kc-fresh", viewModel.currentEntry?.knowledgeNodeId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadingCurrentQuizRunsTheInjectDispatcherAndShowsItem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = KnowledgeReviewSessionViewModel(
            savedStateHandle = SavedStateHandle(),
            plan = plan,
        )
        val loaded = mutableListOf<String>()

        try {
            viewModel.loadCurrentQuiz { entry ->
                loaded += entry.knowledgeNodeId
                quizItem(entry.knowledgeNodeId)
            }
            advanceUntilIdle()

            assertEquals(listOf("kc-confl"), loaded)
            assertEquals(KnowledgeQuizLoadStatus.LOADED, viewModel.loadStatus)
            assertEquals("kc-confl", viewModel.currentItem?.knowledgeNodeIds?.single())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aFailedLoadIsRetryableWithoutLosingTheNode() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = KnowledgeReviewSessionViewModel(
            savedStateHandle = SavedStateHandle(),
            plan = plan,
        )

        try {
            viewModel.loadCurrentQuiz { error("模型暂时不可用") }
            advanceUntilIdle()
            assertEquals(KnowledgeQuizLoadStatus.FAILED, viewModel.loadStatus)
            assertEquals("kc-confl", viewModel.currentEntry?.knowledgeNodeId)

            viewModel.loadCurrentQuiz { quizItem(it.knowledgeNodeId) }
            advanceUntilIdle()
            assertEquals(KnowledgeQuizLoadStatus.LOADED, viewModel.loadStatus)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun correctAnswerRecordsVerdictAndAllowsContinue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = KnowledgeReviewSessionViewModel(
            savedStateHandle = SavedStateHandle(),
            plan = plan,
        )
        val submitted = mutableListOf<Triple<String, String, String>>()

        try {
            viewModel.loadCurrentQuiz { quizItem(it.knowledgeNodeId) }
            advanceUntilIdle()
            viewModel.select("A")
            viewModel.submitAnswer(
                requestId = "request:kq:1",
                occurredAtEpochMillis = 1_000,
                submit = { requestId, nodeId, correctChoiceId, selectedChoiceId, _ ->
                    submitted += Triple(nodeId, correctChoiceId, selectedChoiceId)
                    KnowledgeQuizFeedbackResult(isCorrect = true, evidenceRecorded = true)
                },
            )
            advanceUntilIdle()

            assertEquals(listOf(Triple("kc-confl", "A", "A")), submitted)
            assertEquals(KnowledgeQuizSubmitStatus.RECORDED, viewModel.submitStatus)
            assertEquals("A", viewModel.submittedChoice)
            assertEquals(true, viewModel.feedbackResult?.isCorrect)
            assertTrue(viewModel.canContinue)
            assertFalse(viewModel.completed)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun wrongAnswerStillRecordsNegativeVerdictAndAllowsContinue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = KnowledgeReviewSessionViewModel(
            savedStateHandle = SavedStateHandle(),
            plan = plan,
        )

        try {
            viewModel.loadCurrentQuiz { quizItem(it.knowledgeNodeId) }
            advanceUntilIdle()
            viewModel.select("B")
            viewModel.submitAnswer(
                requestId = "request:kq:wrong",
                occurredAtEpochMillis = 1_000,
                submit = { _, _, _, selectedChoiceId, _ ->
                    KnowledgeQuizFeedbackResult(
                        isCorrect = selectedChoiceId == "A",
                        evidenceRecorded = true,
                    )
                },
            )
            advanceUntilIdle()

            assertEquals("B", viewModel.submittedChoice)
            assertEquals(false, viewModel.feedbackResult?.isCorrect)
            assertTrue(viewModel.canContinue)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun continueToNextAdvancesAndClearsVerdictBeforeLoadingNext() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = KnowledgeReviewSessionViewModel(
            savedStateHandle = SavedStateHandle(),
            plan = plan,
        )

        try {
            viewModel.loadCurrentQuiz { quizItem(it.knowledgeNodeId) }
            advanceUntilIdle()
            viewModel.select("A")
            viewModel.submitAnswer(
                requestId = "request:kq:1",
                occurredAtEpochMillis = 1_000,
                submit = { _, _, _, _, _ -> KnowledgeQuizFeedbackResult(true, true) },
            )
            advanceUntilIdle()

            viewModel.continueToNext()
            assertEquals("kc-fresh", viewModel.currentEntry?.knowledgeNodeId)
            assertNull(viewModel.submittedChoice)
            assertNull(viewModel.feedbackResult)
            assertEquals(KnowledgeQuizLoadStatus.IDLE, viewModel.loadStatus)
            assertFalse(viewModel.canContinue)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recordingTheLastAnswerCompletesTheSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle().apply {
            set(KnowledgeReviewSessionViewModel.CURRENT_INDEX_KEY, 1)
        }
        val viewModel = KnowledgeReviewSessionViewModel(
            savedStateHandle = handle,
            plan = plan,
        )

        try {
            viewModel.loadCurrentQuiz { quizItem(it.knowledgeNodeId) }
            advanceUntilIdle()
            viewModel.select("A")
            viewModel.submitAnswer(
                requestId = "request:kq:last",
                occurredAtEpochMillis = 1_000,
                submit = { _, _, _, _, _ -> KnowledgeQuizFeedbackResult(true, true) },
            )
            advanceUntilIdle()

            assertTrue(viewModel.canContinue)
            assertTrue(viewModel.completed)
            viewModel.continueToNext()
            assertNull(viewModel.currentEntry)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aFailedWritebackIsRetryableAndDoesNotLockTheNode() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = KnowledgeReviewSessionViewModel(
            savedStateHandle = SavedStateHandle(),
            plan = plan,
        )

        try {
            viewModel.loadCurrentQuiz { quizItem(it.knowledgeNodeId) }
            advanceUntilIdle()
            viewModel.select("A")
            viewModel.submitAnswer(
                requestId = "request:kq:fail",
                occurredAtEpochMillis = 1_000,
                submit = { _, _, _, _, _ -> error("写入超时") },
            )
            advanceUntilIdle()

            assertEquals(KnowledgeQuizSubmitStatus.FAILED, viewModel.submitStatus)
            assertFalse(viewModel.canContinue)
            viewModel.submitAnswer(
                requestId = "request:kq:fail",
                occurredAtEpochMillis = 1_000,
                submit = { _, _, _, _, _ -> KnowledgeQuizFeedbackResult(true, true) },
            )
            advanceUntilIdle()
            assertEquals(KnowledgeQuizSubmitStatus.RECORDED, viewModel.submitStatus)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
