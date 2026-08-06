package com.tingyun.smartmistakebook.feature.review

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.data.review.ReviewHomePlanSummary
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeProblemPreview
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeState
import com.tingyun.smartmistakebook.core.data.review.ReviewKnowledgePoint
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.SmartDarkColors
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewHomeScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun contentStateShowsRealLandingSummaryAndPrimaryAction() {
        var primaryClicks = 0
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewHomeScreen(
                    state = contentState(),
                    onPrimaryAction = { primaryClicks += 1 },
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("review_scheduled_count")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("今日题量，5 道")
        composeRule.onNodeWithTag("review_estimated_minutes")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("预计时间，15 分钟")
        composeRule.onNodeWithTag("review_progress")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("今日进度，2 / 5")
        composeRule.onNodeWithTag("review_start_button")
            .assertIsDisplayed()
            .performClick()
        check(primaryClicks == 1)
        saveAuditScreenshot("review-home.png")
    }

    @Test
    fun unavailableStateOffersRetryWithoutPrimaryAction() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewHomeScreen(
                    state = ReviewLandingState.Unavailable,
                    onPrimaryAction = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("review_unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("review_retry").assertIsDisplayed()
        composeRule.onAllNodesWithTag("review_start_button").assertCountEquals(0)
        saveAuditScreenshot("review-home-unavailable.png")
    }

    @Test
    fun twoHundredPercentFontKeepsReviewHomeUsable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                SmartMistakeBookTheme {
                    ReviewHomeScreen(
                        state = contentState(),
                        onPrimaryAction = {},
                        onRetry = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("review_scheduled_count")
            .performScrollTo()
            .assertIsDisplayed()
            .assertContentDescriptionEquals("今日题量，5 道")
        composeRule.onNodeWithTag("review_progress")
            .performScrollTo()
            .assertIsDisplayed()
            .assertContentDescriptionEquals("今日进度，2 / 5")
        composeRule.onNodeWithTag("review_start_button")
            .performScrollTo()
            .assertIsDisplayed()
        saveAuditScreenshot("review-home-font-200.png")
    }

    @Test
    fun readyHomePlanMapsToLandingAndRenders() {
        val home = readyHome()
        val landing = reviewLandingState(home)

        composeRule.setContent {
            SmartMistakeBookTheme {
                ReviewHomeScreen(
                    state = landing,
                    onPrimaryAction = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("review_scheduled_count")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("今日题量，5 道")
        composeRule.onNodeWithTag("review_estimated_minutes")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("预计时间，15 分钟")
        composeRule.onNodeWithTag("review_progress")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("今日进度，2 / 5")
        composeRule.onNodeWithTag("review_start_button")
            .assertIsDisplayed()
        saveAuditScreenshot("review-home-ready.png")
    }

    @Test
    fun darkThemeUsesDarkPaletteAndRendersReviewHome() {
        var darkBackground = Color.Unspecified
        var darkPaper = Color.Unspecified

        composeRule.setContent {
            SmartMistakeBookTheme(darkTheme = true) {
                darkBackground = MaterialTheme.colorScheme.background
                darkPaper = Paper
                ReviewHomeScreen(
                    state = contentState(),
                    onPrimaryAction = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("review_scheduled_count").assertIsDisplayed()
        composeRule.onNodeWithTag("review_start_button").assertIsDisplayed()
        assertEquals(SmartDarkColors.Paper, darkBackground)
        assertEquals(SmartDarkColors.Paper, darkPaper)
        saveAuditScreenshot("review-home-dark.png")
    }

    @Test
    fun systemDarkConfigurationIsHonoredByDefaultTheme() {
        var darkBackground = Color.Unspecified

        composeRule.setContent {
            val resourcesConfiguration =
                InstrumentationRegistry.getInstrumentation().targetContext
                    .resources.configuration
            val darkConfiguration =
                Configuration(resourcesConfiguration).apply {
                    uiMode =
                        (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            Configuration.UI_MODE_NIGHT_YES
                }
            CompositionLocalProvider(LocalConfiguration provides darkConfiguration) {
                SmartMistakeBookTheme {
                    darkBackground = MaterialTheme.colorScheme.background
                    ReviewHomeScreen(
                        state = contentState(),
                        onPrimaryAction = {},
                        onRetry = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("review_scheduled_count").assertIsDisplayed()
        assertEquals(SmartDarkColors.Paper, darkBackground)
    }

    private fun saveAuditScreenshot(fileName: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$fileName")
            .close()
    }

    private fun readyHome(): ReviewHomeState.Ready {
        val node =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = "quadratic-function",
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v1",
            )
        val problemRevision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = "local-learner",
                        subject = SubjectKind.MATH,
                        problemId = "problem-1",
                        practiceUnitId = "practice-1",
                    ),
                revisionId = "revision-1",
                revisionNumber = 1,
                documentCanonicalFingerprint = "b".repeat(64),
            )
        return ReviewHomeState.Ready(
            plan =
                ReviewHomePlanSummary(
                    planId = "plan-1",
                    canonicalFingerprint = "a".repeat(64),
                    localDayEpochDay = 20_000L,
                    timeZoneId = "Asia/Shanghai",
                    timeBudgetSeconds = 900,
                    scheduledItemCount = 5,
                    completedItemCount = 2,
                    skippedItemCount = 0,
                    remainingItemCount = 3,
                    remainingEstimatedSeconds = 900,
                ),
            session = null,
            nextProblem =
                ReviewHomeProblemPreview(
                    queueItemId = "queue-1",
                    problemRevision = problemRevision,
                    title = "二次函数",
                    problemMarkdown = "求函数的最值。",
                    estimatedDurationSeconds = 180,
                    knowledgePoints =
                        listOf(
                            ReviewKnowledgePoint(
                                ref = node,
                                displayName = "二次函数",
                                parentRef = null,
                                parentDisplayName = null,
                                mastery = null,
                            ),
                        ),
                ),
        )
    }

    private fun contentState(): ReviewLandingState.Content =
        ReviewLandingState.Content(
            scheduledCount = 5,
            estimatedMinutes = 15,
            completedCount = 2,
            actionLabel = "开始复习",
            actionEnabled = true,
        )
}
