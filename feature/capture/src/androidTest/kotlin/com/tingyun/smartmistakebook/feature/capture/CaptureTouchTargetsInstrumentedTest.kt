package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CaptureTouchTargetsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryAndOutlineCaptureActionsMeetFortyEightDpTarget() {
        composeRule.setContent {
            MaterialTheme {
                CaptureActions(
                    provider = null,
                    onTakePicture = {},
                    onPickPhoto = {},
                )
            }
        }
        val density = composeRule.density

        val takePictureHeight = composeRule
            .onNodeWithTag("capture_take_picture_button")
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        val pickPhotoHeight = composeRule
            .onNodeWithTag("capture_pick_photo_button")
            .fetchSemanticsNode()
            .boundsInRoot
            .height

        composeRule.runOnIdle {
            assertTrue(
                "Primary capture action height was ${takePictureHeight}px",
                takePictureHeight >= with(density) { 48.dp.toPx() },
            )
            assertTrue(
                "Outline capture action height was ${pickPhotoHeight}px",
                pickPhotoHeight >= with(density) { 48.dp.toPx() },
            )
        }
    }

    @Test
    fun topBarBackButtonMeetsFortyEightDpTarget() {
        composeRule.setContent {
            MaterialTheme {
                CaptureTopBar(title = "拍题讲解", onBack = {})
            }
        }
        val density = composeRule.density
        val backHeight = composeRule
            .onNodeWithTag("capture_back_button")
            .fetchSemanticsNode()
            .boundsInRoot
            .height

        composeRule.runOnIdle {
            assertTrue(
                "Capture back button height was ${backHeight}px",
                backHeight >= with(density) { 48.dp.toPx() },
            )
        }
    }
}
