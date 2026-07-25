package com.tingyun.smartmistakebook.feature.profile

import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileLearningStatusTest {
    @Test
    fun completedReviewIsDescribedAsAnAutomaticRecord() {
        assertEquals(
            "今天的复习已完成 · 已自动记录",
            profileLearningStatus(
                overview = StudyProfileOverview(
                    hasLearningEvidence = true,
                    recordedAttemptCount = 5,
                ),
                review = StudyReviewOverview(
                    completedToday = true,
                    completionStreakDays = 3,
                ),
            ),
        )
    }
}
