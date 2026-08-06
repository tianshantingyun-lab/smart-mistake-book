package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.ui.SmartDarkColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewReminderSystemDarkModeInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun reminderScreenFollowsRealSystemDarkMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("cmd uimode night yes").close()
        try {
            ActivityScenario.launch(ReviewReminderActivityRecreationHarness::class.java)
                .use { scenario ->
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    composeRule.waitForIdle()
                    composeRule.onNodeWithTag("reminder_time_value").assertIsDisplayed()
                    composeRule.onNodeWithTag("reminder_pacing_summary").assertIsDisplayed()
                    assertEquals(
                        SmartDarkColors.Paper,
                        ReviewReminderRotationFixture.lastBackgroundColor,
                    )
                    saveAuditScreenshot("review-reminder-system-dark.png")
                }
        } finally {
            instrumentation.uiAutomation.executeShellCommand("cmd uimode night no").close()
        }
    }

    private fun saveAuditScreenshot(fileName: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$fileName")
            .close()
    }
}
