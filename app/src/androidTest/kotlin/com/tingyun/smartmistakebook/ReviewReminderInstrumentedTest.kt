package com.tingyun.smartmistakebook

import android.Manifest
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.domain.ReviewPacingLevel
import com.tingyun.smartmistakebook.core.domain.ReviewReminderDelivery
import com.tingyun.smartmistakebook.core.domain.ReviewReminderPreferences
import com.tingyun.smartmistakebook.core.domain.ReviewReminderRepository
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.SmartDarkColors
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewReminderInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reminderPreferencesAndPacingChoiceRemainUsable() {
        grantNotificationPermission()
        val repository = FakeReviewReminderRepository()

        composeRule.setContent {
            SmartMistakeBookTheme {
                ReminderScreen(
                    repository = repository,
                    onRefreshSchedule = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("reminder_time_value").assertTextEquals("20:30")
        composeRule.onNodeWithTag("reminder_pacing_summary")
            .assertIsDisplayed()
            .assertTextEquals("当前：标准 · 约15分钟，最多5题")
        composeRule.onNodeWithTag("reminder_preset_1110").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            repository.state.value.minutesAfterMidnight == 1_110
        }
        composeRule.onNodeWithTag("reminder_time_value").assertTextEquals("18:30")

        composeRule.onNodeWithTag("reminder_pacing_STRONG").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            repository.state.value.pacingLevel == ReviewPacingLevel.STRONG
        }
        assertEquals(
            ReviewPacingLevel.STRONG,
            repository.state.value.pacingLevel,
        )
        composeRule.onNodeWithTag("reminder_pacing_summary")
            .assertIsDisplayed()
            .assertTextEquals("当前：加强 · 约25分钟，最多8题")

        composeRule.onNodeWithTag("reminder_exam_MATH")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("reminder_exam_days_14")
            .performScrollTo()
            .performClick()
        val expectedExamDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay() + 14
        composeRule.waitUntil(5_000) {
            repository.state.value.examSubject == SubjectKind.MATH &&
                repository.state.value.examEpochDay == expectedExamDay
        }
        assertEquals(SubjectKind.MATH, repository.state.value.examSubject)
        assertEquals(expectedExamDay, repository.state.value.examEpochDay)

        composeRule.onNodeWithTag("reminder_enabled_switch").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            repository.state.value.enabled
        }
        composeRule.onNodeWithTag("reminder_enabled_switch").assertIsOn()
        assertTrue(repository.state.value.enabled)
        saveAuditScreenshot("review-reminder-preferences.png")
    }

    @Test
    fun twoHundredPercentFontKeepsReminderSummaryUsable() {
        val repository = FakeReviewReminderRepository()

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                SmartMistakeBookTheme {
                    ReminderScreen(
                        repository = repository,
                        onRefreshSchedule = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("reminder_pacing_summary")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals("当前：标准 · 约15分钟，最多5题")
        composeRule.onNodeWithTag("reminder_time_value")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("review_reminder_screen").assertIsDisplayed()
        saveAuditScreenshot("review-reminder-font-200.png")
    }

    @Test
    fun savedStatusAndPreferencesSurviveComposeStateRestoration() {
        val repository = FakeReviewReminderRepository()
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            SmartMistakeBookTheme {
                ReminderScreen(
                    repository = repository,
                    onRefreshSchedule = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("reminder_preset_1110")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(5_000) {
            repository.state.value.minutesAfterMidnight == 1_110 &&
                composeRule.onAllNodesWithTag("reminder_status_message")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("reminder_status_message")
            .assertTextEquals("提醒时间已改为 18:30")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("reminder_time_value")
            .assertTextEquals("18:30")
        composeRule.onNodeWithTag("reminder_status_message")
            .assertTextEquals("提醒时间已改为 18:30")
    }

    @Test
    fun darkThemeRendersReminderScreenAndCapturesScreenshot() {
        val repository = FakeReviewReminderRepository()
        var darkBackground = Color.Unspecified
        var darkPaper = Color.Unspecified

        composeRule.setContent {
            SmartMistakeBookTheme(darkTheme = true) {
                darkBackground = MaterialTheme.colorScheme.background
                darkPaper = Paper
                ReminderScreen(
                    repository = repository,
                    onRefreshSchedule = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("reminder_time_value").assertIsDisplayed()
        composeRule.onNodeWithTag("reminder_pacing_summary").assertIsDisplayed()
        assertEquals(SmartDarkColors.Paper, darkBackground)
        assertEquals(SmartDarkColors.Paper, darkPaper)
        saveAuditScreenshot("review-reminder-dark.png")
    }

    private fun saveAuditScreenshot(fileName: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$fileName")
            .close()
    }

    private fun grantNotificationPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}",
        ).close()
        repeat(50) {
            if (instrumentation.targetContext.canPostReviewNotifications()) return
            SystemClock.sleep(50)
        }
        error("Notification permission was not granted within 2.5 seconds")
    }

    private class FakeReviewReminderRepository : ReviewReminderRepository {
        val state = MutableStateFlow(ReviewReminderPreferences())

        override val preferences: Flow<ReviewReminderPreferences> = state

        override suspend fun current(): ReviewReminderPreferences = state.value

        override suspend fun setEnabled(enabled: Boolean) {
            state.value = state.value.copy(enabled = enabled)
        }

        override suspend fun setReminderTime(minutesAfterMidnight: Int) {
            state.value = state.value.copy(minutesAfterMidnight = minutesAfterMidnight)
        }

        override suspend fun setPacingLevel(pacingLevel: ReviewPacingLevel) {
            state.value = state.value.copy(pacingLevel = pacingLevel)
        }

        override suspend fun setExamTarget(
            subject: SubjectKind?,
            examEpochDay: Long?,
        ) {
            state.value = state.value.copy(
                examSubject = subject,
                examEpochDay = examEpochDay,
            )
        }

        override suspend fun claimNotificationDelivery(
            delivery: ReviewReminderDelivery,
        ): Boolean = true
    }
}
