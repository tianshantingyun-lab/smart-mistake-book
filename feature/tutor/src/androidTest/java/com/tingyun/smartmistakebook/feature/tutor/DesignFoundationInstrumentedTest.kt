package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootBottomBarFrame
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SmartDimens
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesignFoundationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sharedControlsAndActiveComposersConsumeFoundationDimensions() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                Column(Modifier.width(360.dp)) {
                    TutorTopBar(onOpenCapabilitySettings = {})
                    PrimaryActionButton(
                        text = "开始",
                        onClick = {},
                        modifier = Modifier.testTag("foundation_primary_action"),
                    )
                    OutlineActionChip(
                        text = "选择",
                        onClick = {},
                        modifier = Modifier.testTag("foundation_action_chip"),
                    )
                    TutorComposer(
                        value = "",
                        onValueChange = {},
                        onCapture = {},
                        onSend = {},
                    )
                    TutorChatComposer(
                        value = "",
                        enabled = true,
                        sending = false,
                        onValueChange = {},
                        onSend = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("foundation_primary_action")
            .assertHeightIsEqualTo(SmartDimens.PrimaryControlHeight)
        composeRule.onNodeWithTag("foundation_action_chip")
            .assertHeightIsAtLeast(SmartDimens.MinimumTouchTarget)
        composeRule.onNodeWithTag("tutor_draft_input")
            .assertHeightIsEqualTo(SmartDimens.ComposerHeight)
        composeRule.onNodeWithTag("tutor_capture_button")
            .assertHeightIsAtLeast(SmartDimens.MinimumTouchTarget)
        composeRule.onNodeWithTag("tutor_send_button")
            .assertHeightIsAtLeast(SmartDimens.MinimumTouchTarget)
        composeRule.onNodeWithTag("tutor_chat_composer")
            .assertHeightIsEqualTo(SmartDimens.ComposerHeight)
        composeRule.onNodeWithTag("tutor_chat_send")
            .assertHeightIsAtLeast(SmartDimens.MinimumTouchTarget)
        composeRule.onAllNodesWithText("讲题").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("讲题").assertExists()
    }

    @Test
    fun rootContainersRenderPhoneAndLargeScreenMargins() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Column {
                    RootPageColumn(
                        modifier = Modifier
                            .width(375.dp)
                            .height(80.dp)
                            .testTag("foundation_phone_root"),
                    ) {
                        Box(
                            Modifier
                                .size(1.dp)
                                .testTag("foundation_phone_content"),
                        )
                    }
                    RootPageColumn(
                        modifier = Modifier
                            .width(600.dp)
                            .height(80.dp)
                            .testTag("foundation_large_root"),
                    ) {
                        Box(
                            Modifier
                                .size(1.dp)
                                .testTag("foundation_large_content"),
                        )
                    }
                }
            }
        }

        assertLeftInset("foundation_phone_root", "foundation_phone_content", expectedPixels = 16f)
        assertLeftInset("foundation_large_root", "foundation_large_content", expectedPixels = 24f)
    }

    @Test
    fun bottomBarAddsNavigationInsetOutsideItsContentHeight() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                RootBottomBarFrame(
                    navigationBarInsets = WindowInsets(bottom = 20.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("foundation_bottom_bar_slot"),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("root_bottom_bar_content")
            .assertHeightIsEqualTo(SmartDimens.BottomBarHeight)
        composeRule.onNodeWithTag("foundation_bottom_bar_slot")
            .assertHeightIsEqualTo(SmartDimens.BottomBarHeight)
        composeRule.onNodeWithTag("root_bottom_bar")
            .assertHeightIsEqualTo(SmartDimens.BottomBarHeight + 20.dp)
    }

    private fun assertLeftInset(rootTag: String, contentTag: String, expectedPixels: Float) {
        val rootBounds = composeRule.onNodeWithTag(rootTag).fetchSemanticsNode().boundsInRoot
        val contentBounds = composeRule.onNodeWithTag(contentTag).fetchSemanticsNode().boundsInRoot
        assertEquals(expectedPixels, contentBounds.left - rootBounds.left, 0.5f)
    }
}
