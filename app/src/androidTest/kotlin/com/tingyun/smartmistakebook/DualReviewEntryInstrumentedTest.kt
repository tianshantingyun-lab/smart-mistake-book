package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.feature.review.ReviewRoute
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 双复习入口（spec dual-review-entry §3.1）：复习首页在有今日计划时展示知识点复习入口，
 * 点击回调可用；已完成今日或没有计划时不展示。
 */
class DualReviewEntryInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun computedKnowledgeCountShowsClickableEntry() {
        var knowledgeStarted = false
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewRoute(
                    overview = StudyReviewOverview(
                        scheduledCount = 5,
                        estimatedSeconds = 600,
                    ),
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    onStartReview = {},
                    onStartKnowledgeReview = { knowledgeStarted = true },
                    knowledgeReviewCount = 3,
                )
            }
        }

        composeRule.onNodeWithTag("review_start_knowledge_review").assertIsDisplayed()
        composeRule.onNodeWithTag("review_start_knowledge_review").performClick()
        assertTrue(knowledgeStarted)
    }

    @Test
    fun completedMistakeReviewKeepsTheKnowledgeEntryWhenKnowledgeRemains() {
        // 两个入口独立、自由选：错题复习完成不隐藏知识点入口（只要还有可复习知识点）。
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewRoute(
                    overview = StudyReviewOverview(
                        scheduledCount = 5,
                        estimatedSeconds = 600,
                        completedToday = true,
                    ),
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    onStartReview = {},
                    onStartKnowledgeReview = {},
                    knowledgeReviewCount = 2,
                )
            }
        }

        composeRule.onNodeWithTag("review_start_knowledge_review").assertIsDisplayed()
        composeRule.onNodeWithTag("review_start_button").assertIsNotEnabled()
    }

    @Test
    fun zeroOrUnloadedKnowledgeCountDoesNotShowTheEntry() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewRoute(
                    overview = StudyReviewOverview(scheduledCount = 5),
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    onStartReview = {},
                    onStartKnowledgeReview = {},
                    knowledgeReviewCount = 0,
                )
            }
        }

        composeRule.onNodeWithTag("review_start_knowledge_review").assertDoesNotExist()
    }
}
