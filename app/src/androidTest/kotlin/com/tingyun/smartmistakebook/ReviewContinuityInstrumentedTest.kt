package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.NetworkMode
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.feature.profile.ProfileRoute
import com.tingyun.smartmistakebook.feature.review.ReviewRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReviewContinuityInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun completedReviewShowsOnlyThePlanProgressAndDisabledAction() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewRoute(
                    overview = StudyReviewOverview(
                        scheduledCount = 5,
                        estimatedSeconds = 600,
                        currentOrdinal = 2,
                        completedToday = true,
                        completionStreakDays = 3,
                    ),
                    profile = StudyProfileOverview(hasLearningEvidence = true),
                    onStartReview = {},
                )
            }
        }

        composeRule.onNodeWithTag("review_scheduled_count").assertTextEquals("5")
        composeRule.onNodeWithTag("review_estimated_minutes").assertTextEquals("10")
        composeRule.onNodeWithTag("review_progress").assertTextEquals("5 / 5")
        composeRule.onNodeWithTag("review_start_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("review_capture_button").assertDoesNotExist()
        composeRule.onNodeWithTag("review_capture_shortcut").assertDoesNotExist()
        listOf("同学", "连续复习", "薄弱", "%").forEach { forbidden ->
            composeRule.onAllNodesWithText(forbidden, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun profileKeepsOrderedLearningSectionsAndAllSettingsActions() {
        var learningCalls = 0
        var capabilityCalls = 0
        var privacyCalls = 0
        var reminderCalls = 0
        var storageCalls = 0
        composeRule.setContent {
            SmartMistakeBookTheme {
                ProfileRoute(
                    overview = StudyProfileOverview(
                        hasLearningEvidence = true,
                        recordedAttemptCount = 8,
                        weaknesses = listOf(
                            summary("weak-1", "函数单调性", 3_000L),
                            summary("weak-2", "受力分析", 2_000L),
                            summary("weak-3", "化学平衡", 1_000L),
                        ),
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
                    onOpenCapability = { capabilityCalls += 1 },
                    onOpenLearningMastery = { learningCalls += 1 },
                    onOpenDataPrivacy = { privacyCalls += 1 },
                    onOpenReminder = { reminderCalls += 1 },
                    onOpenStorage = { storageCalls += 1 },
                    nowEpochMillis = 3_000L,
                )
            }
        }

        val subjectTop = composeRule.onNodeWithTag("profile_subject_mastery")
            .fetchSemanticsNode().boundsInRoot.top
        val recentTop = composeRule.onNodeWithTag("profile_recent_changes")
            .fetchSemanticsNode().boundsInRoot.top
        val weaknessTop = composeRule.onNodeWithTag("profile_weaknesses")
            .fetchSemanticsNode().boundsInRoot.top
        val settingsTop = composeRule.onNodeWithTag("profile_settings")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(subjectTop < recentTop)
        assertTrue(recentTop < weaknessTop)
        assertTrue(weaknessTop < settingsTop)
        composeRule.onNodeWithTag("profile_weakness_weak-3").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("连续复习，3天").assertExists()
        listOf(
            "profile_learning_mastery",
            "profile_capability_setting",
            "profile_privacy_setting",
            "profile_reminder_setting",
            "profile_storage_setting",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        }
        composeRule.runOnIdle {
            assertEquals(1, learningCalls)
            assertEquals(1, capabilityCalls)
            assertEquals(1, privacyCalls)
            assertEquals(1, reminderCalls)
            assertEquals(1, storageCalls)
        }
        listOf("%", "根据多次独立作答估计", "查看全部知识点").forEach { forbidden ->
            composeRule.onAllNodesWithText(forbidden, substring = true).assertCountEquals(0)
        }
    }

    private fun summary(
        id: String,
        name: String,
        lastEvidenceAtEpochMillis: Long,
    ) = StudyKnowledgeSummary(
        knowledgeNodeId = id,
        displayName = name,
        status = MasteryStatus.LEARNING,
        lowerBoundIndependentCorrect = 0.4,
        lastEvidenceAtEpochMillis = lastEvidenceAtEpochMillis,
        subject = SubjectKind.MATH,
    )
}
