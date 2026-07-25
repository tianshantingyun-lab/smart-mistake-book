package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.NetworkMode
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.feature.profile.ProfileRoute
import com.tingyun.smartmistakebook.feature.review.ReviewRoute
import org.junit.Rule
import org.junit.Test

class ReviewContinuityInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun completedReviewShowsAnAutomaticPlainLanguageContinuityRecord() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewRoute(
                    overview = StudyReviewOverview(
                        scheduledCount = 5,
                        estimatedSeconds = 600,
                        completedToday = true,
                        completionStreakDays = 3,
                    ),
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    onStartReview = {},
                )
            }
        }

        composeRule.onNodeWithTag("review_continuity")
            .assertTextEquals("今天已完成 · 连续复习 3 天")
        composeRule.onNodeWithTag("review_start_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("review_capture_button").assertDoesNotExist()
        composeRule.onNodeWithTag("review_capture_shortcut").assertDoesNotExist()
    }

    @Test
    fun learningDataShowsTheSameContinuityWithoutAnotherCheckInControl() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                ProfileRoute(
                    overview = StudyProfileOverview(
                        hasLearningEvidence = true,
                        recordedAttemptCount = 8,
                    ),
                    review = StudyReviewOverview(
                        completedToday = true,
                        completionStreakDays = 3,
                    ),
                    capabilities = AppCapabilitySnapshot(
                        networkMode = NetworkMode.LOCAL_FIRST,
                        cameraCaptureAvailable = true,
                        trustedOcrAvailable = false,
                        tutorTeachingEnabled = true,
                        remoteModelConfigured = false,
                    ),
                    onOpenCapability = {},
                    onOpenLearningMastery = {},
                    onOpenDataPrivacy = {},
                    onOpenReminder = {},
                    onOpenStorage = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("连续复习，3天").assertExists()
    }
}
