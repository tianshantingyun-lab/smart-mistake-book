package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorActiveStreamInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeExchangeDelaysPlaceholderAndKeepsPreviewWithOneRetry() {
        var active by mutableStateOf(
            message(
                phase = TutorActiveStreamPhase.PREPARING,
                showPlaceholder = false,
            ),
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                TutorActiveChatExchange(
                    message = active,
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("我卡在第二步").assertIsDisplayed()
        composeRule.onNodeWithTag("tutor_stream_activity").assertIsDisplayed()
        composeRule.onNodeWithTag("tutor_stream_placeholder").assertDoesNotExist()

        composeRule.runOnIdle {
            active = active.copy(showPlaceholder = true)
        }
        composeRule.onNodeWithTag("tutor_stream_placeholder").assertIsDisplayed()

        composeRule.runOnIdle {
            active = active.copy(
                snapshot = TutorMarkdownSnapshot("先保留这段讲解。", ""),
                phase = TutorActiveStreamPhase.FAILED,
                showPlaceholder = false,
                retryable = true,
            )
        }
        composeRule.onNodeWithText("先保留这段讲解。").assertIsDisplayed()
        composeRule.onNodeWithTag("tutor_stream_activity").assertDoesNotExist()
        composeRule.onAllNodesWithText("重试").assertCountEquals(1)
    }

    private fun message(
        phase: TutorActiveStreamPhase,
        showPlaceholder: Boolean,
    ) = TutorActiveStreamMessage(
        studentMessage = "我卡在第二步",
        ownerVersion = 1,
        turnVersion = 1,
        modeVersion = 0,
        phase = phase,
        showPlaceholder = showPlaceholder,
    )
}
