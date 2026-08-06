package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewReminderActivityRecreationInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun reminderScreenSurvivesRealActivityRecreation() {
        ActivityScenario.launch(ReviewReminderActivityRecreationHarness::class.java).use { scenario ->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            composeRule.onNodeWithTag("reminder_time_value").assertIsDisplayed()
            saveAuditScreenshot("review-reminder-activity-before.png")

            scenario.recreate()

            composeRule.waitForIdle()
            composeRule.onNodeWithTag("reminder_time_value").assertIsDisplayed()
            composeRule.onNodeWithTag("reminder_pacing_summary").assertIsDisplayed()
            saveAuditScreenshot("review-reminder-activity-recreated.png")
        }
    }

    private fun saveAuditScreenshot(fileName: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$fileName")
            .close()
    }
}
