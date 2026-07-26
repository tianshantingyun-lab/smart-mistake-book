package com.tingyun.smartmistakebook.feature.review

import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRoutePolicyTest {
    @Test
    fun progressUsesTheClampedCompletedOrdinalWithoutAnOffByOne() {
        assertEquals(
            "0 / 5",
            reviewLandingState(StudyReviewOverview(scheduledCount = 5, currentOrdinal = -1))
                .progressText,
        )
        assertEquals(
            "2 / 5",
            reviewLandingState(StudyReviewOverview(scheduledCount = 5, currentOrdinal = 2))
                .progressText,
        )
        assertEquals(
            "5 / 5",
            reviewLandingState(StudyReviewOverview(scheduledCount = 5, currentOrdinal = 8))
                .progressText,
        )
    }

    @Test
    fun headlineMetricsExposeCompleteStudentFacingDescriptions() {
        val state = reviewLandingState(
            StudyReviewOverview(
                scheduledCount = 5,
                estimatedSeconds = 600,
                currentOrdinal = 2,
            ),
        )

        assertEquals("今日题量，5 道", state.scheduledCountDescription)
        assertEquals("预计时间，10 分钟", state.estimatedMinutesDescription)
        assertEquals("今日进度，2 / 5", state.progressDescription)
    }

    @Test
    fun completedAndEmptyPlansKeepTheProgressStructure() {
        assertEquals(
            "5 / 5",
            reviewLandingState(
                StudyReviewOverview(
                    scheduledCount = 5,
                    currentOrdinal = 2,
                    completedToday = true,
                ),
            ).progressText,
        )
        assertEquals(
            "0 / 0",
            reviewLandingState(StudyReviewOverview(currentOrdinal = 4)).progressText,
        )
    }

    @Test
    fun plannedReviewStartsAndActiveReviewResumes() {
        val planned = reviewLandingState(StudyReviewOverview(scheduledCount = 3))
        val active = reviewLandingState(
            StudyReviewOverview(
                scheduledCount = 3,
                activeSessionId = "session-1",
            ),
        )

        assertEquals("开始今日复习", planned.actionLabel)
        assertTrue(planned.actionEnabled)
        assertEquals("继续今日复习", active.actionLabel)
        assertTrue(active.actionEnabled)
    }

    @Test
    fun completedAndEmptyPlansExposeOneDisabledTerminalAction() {
        val completed = reviewLandingState(
            StudyReviewOverview(scheduledCount = 3, completedToday = true),
        )
        val empty = reviewLandingState(StudyReviewOverview())

        assertEquals("今日复习已完成", completed.actionLabel)
        assertFalse(completed.actionEnabled)
        assertEquals("今天没有待复习", empty.actionLabel)
        assertFalse(empty.actionEnabled)
    }
}
