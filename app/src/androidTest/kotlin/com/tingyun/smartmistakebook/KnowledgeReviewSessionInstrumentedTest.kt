package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tingyun.smartmistakebook.core.domain.KnowledgeQuizFeedbackResult
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewQueueEntry
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewSessionPlan
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.feature.review.KnowledgeReviewSessionScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 知识点复习会话屏（spec dual-review-entry §3.3/§3.4）：进屏自动取题 → 显示选择题 →
 * 作答 → 判答回写 → 下一知识点/完成。取题与回写都是注入回调，本测试验证整条 UI 会话
 * 状态机在真实 Compose 环境里走通（含 ViewModel 的 LaunchedEffect 驱动）。
 */
class KnowledgeReviewSessionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val node = KnowledgeReviewQueueEntry(
        knowledgeNodeId = "kc-monotonicity",
        subject = "MATH",
        displayName = "函数单调性",
        masteryScore = null,
        lastEvidenceAtEpochMillis = null,
    )

    private val quizItem = TutorAssessmentItem(
        id = "knowledge-quiz:kc-monotonicity:1",
        stemMarkdown = "函数单调性的判定？",
        choices = listOf(
            TutorChoice(id = "A", markdown = "看导数符号"),
            TutorChoice(id = "B", markdown = "看函数值大小"),
        ),
        correctChoiceId = "A",
        knowledgeNodeIds = setOf("kc-monotonicity"),
    )

    @Test
    fun singleNodeSessionLoadsQuizzesAnswersAndFinishes() {
        var finished = false
        var submitted: Pair<String, String>? = null
        composeRule.setContent {
            SmartMistakeBookTheme {
                KnowledgeReviewSessionScreen(
                    plan = KnowledgeReviewSessionPlan(queue = listOf(node)),
                    onBack = {},
                    loadQuiz = { quizItem },
                    submitAnswer = { requestId, knowledgeNodeId, correctChoiceId, selectedChoiceId, _, _ ->
                        submitted = knowledgeNodeId to selectedChoiceId
                        KnowledgeQuizFeedbackResult(
                            isCorrect = selectedChoiceId == correctChoiceId,
                            evidenceRecorded = true,
                        )
                    },
                    onFinished = { finished = true },
                )
            }
        }

        // 自动取题：选择题题干出现。
        composeRule.waitForIdle()
        composeRule.onNodeWithText("函数单调性的判定？").assertIsDisplayed()

        // 选正确答案并提交。
        composeRule.onNodeWithTag("knowledge_review_choice_A").performClick()
        composeRule.onNodeWithTag("knowledge_review_submit").assertIsEnabled()
        composeRule.onNodeWithTag("knowledge_review_submit").performClick()
        composeRule.waitForIdle()

        assertEquals("kc-monotonicity" to "A", submitted)
        composeRule.onNodeWithText("回答正确").assertIsDisplayed()

        // 单节点会话已完成：点完成回到首页。
        composeRule.onNodeWithTag("knowledge_review_finish").assertIsDisplayed()
        composeRule.onNodeWithTag("knowledge_review_finish").performClick()
        assertTrue(finished)
    }

    @Test
    fun loadFailureShowsRetryThatCanRecover() {
        var attempts = 0
        composeRule.setContent {
            SmartMistakeBookTheme {
                KnowledgeReviewSessionScreen(
                    plan = KnowledgeReviewSessionPlan(queue = listOf(node)),
                    onBack = {},
                    loadQuiz = {
                        attempts += 1
                        if (attempts == 1) null else quizItem
                    },
                    submitAnswer = { _, _, _, _, _, _ -> KnowledgeQuizFeedbackResult(true, true) },
                    onFinished = {},
                )
            }
        }

        composeRule.waitForIdle()
        // 首次取题失败 → 重试按钮；重试后题目出现。
        composeRule.onNodeWithTag("knowledge_review_load_failed").assertIsDisplayed()
        composeRule.onNodeWithTag("knowledge_review_retry").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("函数单调性的判定？").assertIsDisplayed()
        assertTrue(attempts >= 2)
    }
}
