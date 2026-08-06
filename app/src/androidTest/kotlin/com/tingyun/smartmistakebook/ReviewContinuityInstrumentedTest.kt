package com.tingyun.smartmistakebook

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.NetworkMode
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.SmartDarkColors
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.feature.profile.ProfileRoute
import com.tingyun.smartmistakebook.feature.review.ReviewRoute
import androidx.test.platform.app.InstrumentationRegistry
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

        composeRule.onNodeWithContentDescription("今日题量，5 道").assertExists()
        composeRule.onNodeWithContentDescription("预计时间，10 分钟").assertExists()
        composeRule.onNodeWithContentDescription("今日进度，5 / 5").assertExists()
        composeRule.onAllNodesWithText("5", substring = false).assertCountEquals(0)
        composeRule.onNodeWithTag("review_start_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("review_capture_button").assertDoesNotExist()
        composeRule.onNodeWithTag("review_capture_shortcut").assertDoesNotExist()
        assertNoC4StudentCopy()
    }

    @Test
    fun reviewHeadlineMetricsStayEqualAndInsideTheViewportAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                SmartMistakeBookTheme {
                    ReviewRoute(
                        overview = StudyReviewOverview(
                            scheduledCount = 88,
                            estimatedSeconds = 59 * 60,
                        ),
                        profile = StudyProfileOverview(),
                        onStartReview = {},
                    )
                }
            }
        }

        val root = composeRule.onNodeWithTag("review_root").fetchSemanticsNode().boundsInRoot
        val summary = composeRule.onNodeWithTag("review_summary").fetchSemanticsNode().boundsInRoot
        val scheduled = composeRule.onNodeWithTag("review_scheduled_count")
            .fetchSemanticsNode().boundsInRoot
        val estimated = composeRule.onNodeWithTag("review_estimated_minutes")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(summary.left >= root.left)
        assertTrue(summary.right <= root.right)
        assertEquals(scheduled.width, estimated.width, 1f)
        assertTrue(scheduled.right <= estimated.left)
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
        assertNoC4StudentCopy()
    }

    @Test
    fun emptyProfileShowsOneLearningEmptyStateAndKeepsSettingsReachable() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                ProfileRoute(
                    overview = StudyProfileOverview(),
                    review = StudyReviewOverview(),
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

        composeRule.onAllNodesWithText("还没有学习记录", useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithTag("profile_recent_changes", useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule.onAllNodesWithText("最近变化", useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onNodeWithTag("profile_settings").performScrollTo().assertIsDisplayed()
        listOf(
            "profile_capability_setting",
            "profile_privacy_setting",
            "profile_reminder_setting",
            "profile_storage_setting",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        assertNoC4StudentCopy()
    }

    @Test
    fun darkThemeRendersProfileRootAndCapturesScreenshot() {
        var darkBackground = Color.Unspecified
        var darkPaper = Color.Unspecified
        composeRule.setContent {
            SmartMistakeBookTheme(darkTheme = true) {
                darkBackground = MaterialTheme.colorScheme.background
                darkPaper = Paper
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
                    onOpenCapability = {},
                    onOpenLearningMastery = {},
                    onOpenDataPrivacy = {},
                    onOpenReminder = {},
                    onOpenStorage = {},
                    nowEpochMillis = 3_000L,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("profile_subject_mastery").assertIsDisplayed()
        assertEquals(SmartDarkColors.Paper, darkBackground)
        assertEquals(SmartDarkColors.Paper, darkPaper)
        saveAuditScreenshot("profile-home-dark.png")
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

    private fun saveAuditScreenshot(fileName: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$fileName")
            .close()
    }

    private fun assertNoC4StudentCopy() {
        (C4_FORBIDDEN_TERMS + "%").forEach { term ->
            composeRule.onAllNodesWithText(
                term,
                substring = true,
                useUnmergedTree = true,
            ).assertCountEquals(0)
            composeRule.onAllNodes(
                hasContentDescription(term, substring = true),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        }
    }

    private companion object {
        val C4_FORBIDDEN_TERMS = listOf(
            "知识本体",
            "学习投影",
            "grounding",
            "taxonomy",
            "embedding",
            "置信度",
            "分类依据",
            "资料完整度",
            "待补齐",
            "检索召回",
            "证据不足",
            "根据多次独立作答估计",
        )
    }
}
