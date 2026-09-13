package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pins the no-model CTA after the global "model agent" consent toggle was deleted: the copy
 * must point at configuring a model (the toggle it used to name no longer exists), and the
 * only affordance it offers must still reach the model settings surface.
 */
class CaptureModelSetupBlockInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setupBlockNamesConfigurationAndOpensModelSettings() {
        var openedSettings = 0
        composeRule.setContent {
            MaterialTheme {
                CaptureModelSetupBlock(onOpenSettings = { openedSettings += 1 })
            }
        }

        composeRule.onNodeWithText("配置好模型后，拍照题图才会交给模型整理").assertExists()
        composeRule.onNodeWithTag("capture_model_setup_settings").performClick()
        assertEquals(1, openedSettings)
    }
}
