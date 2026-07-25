package com.tingyun.smartmistakebook

import android.Manifest
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewReminderInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun reminderPreferenceIsUsableAndNotificationIntentReturnsToReview() {
        val application = composeRule.activity.application as SmartMistakeBookApplication
        runBlocking {
            application.reviewReminderRepository.setEnabled(false)
            application.reviewReminderRepository.setReminderTime(20 * 60 + 30)
        }
        grantNotificationPermission()

        composeRule.onNodeWithTag("nav_profile").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("profile_reminder_setting")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("profile_reminder_setting")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("reminder_time_value").assertTextEquals("20:30")

        composeRule.onNodeWithTag("reminder_preset_1110").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking {
                application.reviewReminderRepository.current().minutesAfterMidnight == 1_110
            }
        }
        composeRule.onNodeWithTag("reminder_time_value").assertTextEquals("18:30")

        composeRule.onNodeWithTag("reminder_enabled_switch").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { application.reviewReminderRepository.current().enabled }
        }
        composeRule.onNodeWithTag("reminder_enabled_switch").assertIsOn()
        val saved = runBlocking { application.reviewReminderRepository.current() }
        assertTrue(saved.enabled)
        assertEquals(1_110, saved.minutesAfterMidnight)

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.requestReviewOpen()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("root_review").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun grantNotificationPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}",
        ).close()
    }
}
