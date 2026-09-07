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
    fun knowledgeReviewEntryAppearsOnlyOnAnActiveReviewDay() {
        assertTrue(
            isKnowledgeReviewDay(
                StudyReviewOverview(scheduledCount = 3, completedToday = false),
            ),
        )
        // 已完成今天 → 不再建议知识点复习。
        assertFalse(
            isKnowledgeReviewDay(
                StudyReviewOverview(scheduledCount = 3, completedToday = true),
            ),
        )
        // 无今日计划 → 无知识点可复习。
        assertFalse(
            isKnowledgeReviewDay(StudyReviewOverview(scheduledCount = 0)),
        )
    }
}
