package com.tingyun.smartmistakebook.feature.review

import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRoutePolicyTest {
    @Test
    fun savedQuestionsShowTodaysPlanBeforeAnyLearningEvidenceExists() {
        assertTrue(
            shouldShowReviewSummary(
                scheduledCount = 1,
                hasLearningEvidence = false,
            ),
        )
    }

    @Test
    fun aTrulyEmptyNewProfileKeepsTheNoLearningRecordState() {
        assertFalse(
            shouldShowReviewSummary(
                scheduledCount = 0,
                hasLearningEvidence = false,
            ),
        )
    }

    @Test
    fun completingThePlanIsTheOnlyActionNeededToRecordContinuity() {
        assertEquals(
            "今天已完成 · 连续复习 3 天",
            reviewContinuityText(
                StudyReviewOverview(
                    completedToday = true,
                    completionStreakDays = 3,
                ),
            ),
        )
        assertEquals(
            "连续复习 3 天 · 完成今天的安排后自动记录",
            reviewContinuityText(StudyReviewOverview(completionStreakDays = 3)),
        )
        assertNull(reviewContinuityText(StudyReviewOverview()))
    }

    @Test
    fun knowledgeEntryIsDrivenByTheComputedCountNotTheMistakePlan() {
        // 入口显隐由 app 层实算的 knowledgeReviewCount 决定（见 ReviewRoute 的渲染条件）：
        // 有今日错题计划但知识点全无材料可出题（count=null/0）时不显示入口；
        // 即使错题复习已完成，只要还有可复习知识点，入口仍在（两者独立、自由选）。
        assertTrue(knowledgeReviewEntryVisible(count = 3, hasStartCallback = true))
        assertFalse(knowledgeReviewEntryVisible(count = 0, hasStartCallback = true))
        assertFalse(knowledgeReviewEntryVisible(count = null, hasStartCallback = true))
        assertFalse(knowledgeReviewEntryVisible(count = 3, hasStartCallback = false))
    }
}
