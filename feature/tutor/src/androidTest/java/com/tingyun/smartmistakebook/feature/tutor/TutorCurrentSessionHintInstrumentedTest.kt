package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoice
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHint
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintStatus
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionInteraction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPresentation
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionText
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionTextKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TutorCurrentSessionHintInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun availableHintRevealsInsideReplyAndRetriesWithoutCreatingAnotherSlot() {
        val retries = AtomicInteger(0)
        val hint = hint(TutorCurrentSessionHintStatus.AVAILABLE)
        compose.setContent {
            var locallyVisibleToken by remember { mutableStateOf<String?>(null) }
            var failedToken by remember { mutableStateOf<String?>(null) }
            MaterialTheme {
                hostPresentation(
                    presentation = presentation(hint),
                    locallyVisibleHintToken = locallyVisibleToken,
                    hintCommitFailedToken = failedToken,
                    onShowHint = { shown ->
                        locallyVisibleToken = shown.slotToken
                        failedToken = shown.slotToken
                    },
                    onRetryHint = { retried ->
                        assertEquals(hint.slotToken, retried.slotToken)
                        retries.incrementAndGet()
                    },
                )
            }
        }

        compose.onNodeWithTag("tutor_host_hint_action").assertExists().performClick()
        compose.onNodeWithTag("tutor_host_hint_action").assertDoesNotExist()
        compose.onNodeWithTag("tutor_host_hint_content").assertExists()
        compose.onNodeWithTag("tutor_host_hint_retry").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, retries.get()) }
    }

    @Test
    fun restoredShownHintHasContentAndNoAction() {
        compose.setContent {
            MaterialTheme {
                hostPresentation(
                    presentation = presentation(hint(TutorCurrentSessionHintStatus.SHOWN)),
                )
            }
        }

        compose.onNodeWithTag("tutor_host_hint_content").assertExists()
        compose.onNodeWithTag("tutor_host_hint_action").assertDoesNotExist()
        compose.onNodeWithTag("tutor_host_hint_retry").assertDoesNotExist()
    }

    @Test
    fun choiceAnswerIsBlockedWhileHintCommitIsPending() {
        var choiceInvoked = false
        val hint = hint(TutorCurrentSessionHintStatus.AVAILABLE)
        compose.setContent {
            MaterialTheme {
                hostPresentation(
                    presentation = presentationWithChoices(hint),
                    locallyVisibleHintToken = hint.slotToken,
                    hintCommitBusyToken = hint.slotToken,
                    onChoice = { choiceInvoked = true },
                )
            }
        }

        compose.onNodeWithTag("tutor_host_choice_choice-a").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(choiceInvoked) }
    }

    @Test
    fun blockedChoiceWithoutTrustedAnswerShowsDirectExplanationInsteadOfEmptyShell() {
        var directExplanationInvoked = false
        val hint = hint(TutorCurrentSessionHintStatus.AVAILABLE)
        compose.setContent {
            MaterialTheme {
                hostPresentation(
                    presentation = presentationWithChoices(hint),
                    interactionBlocked = true,
                    onDirectExplanation = { directExplanationInvoked = true },
                )
            }
        }

        compose.onNodeWithTag("tutor_host_choice_choice-a").assertDoesNotExist()
        compose.onNodeWithTag("tutor_host_direct_explanation").assertExists().performClick()
        compose.runOnIdle { assertTrue(directExplanationInvoked) }
    }

    @Test
    fun blockedVisualTargetShowsDirectExplanationInsteadOfEmptyShell() {
        var directExplanationInvoked = false
        val hint = hint(TutorCurrentSessionHintStatus.AVAILABLE)
        compose.setContent {
            MaterialTheme {
                hostPresentation(
                    presentation = presentation(
                        hint = hint,
                        interaction = TutorCurrentSessionInteraction.VisualTarget(
                            promptMarkdown = "在图里点出变化方向。",
                        ),
                    ),
                    interactionBlocked = true,
                    onDirectExplanation = { directExplanationInvoked = true },
                )
            }
        }

        compose.onNodeWithTag("tutor_host_direct_explanation").assertExists().performClick()
        compose.runOnIdle { assertTrue(directExplanationInvoked) }
    }

    private fun presentation(hint: TutorCurrentSessionHint) =
        TutorCurrentSessionPresentation(
            sessionId = "session-hint-compose",
            presentationToken = "a".repeat(64),
            explanationMode = TutorExplanationMode.GUIDED,
            learningWritesAllowed = true,
            text = listOf(
                TutorCurrentSessionText(
                    TutorCurrentSessionTextKind.OPENING,
                    "先判断变化方向。",
                ),
            ),
            interaction = TutorCurrentSessionInteraction.FreeResponse(
                promptMarkdown = "下一步关系式是什么？",
                actionToken = "b".repeat(64),
            ),
            visualScene = null,
            hint = hint,
        )

    private fun presentation(
        hint: TutorCurrentSessionHint,
        interaction: TutorCurrentSessionInteraction,
    ) = TutorCurrentSessionPresentation(
        sessionId = "session-hint-compose",
        presentationToken = "a".repeat(64),
        explanationMode = TutorExplanationMode.GUIDED,
        learningWritesAllowed = true,
        text = listOf(
            TutorCurrentSessionText(
                TutorCurrentSessionTextKind.OPENING,
                "先判断变化方向。",
            ),
        ),
        interaction = interaction,
        visualScene = null,
        hint = hint,
    )

    private fun presentationWithChoices(hint: TutorCurrentSessionHint) = presentation(
        hint = hint,
        interaction = TutorCurrentSessionInteraction.Choices(
            promptMarkdown = "下一步应该先判断什么？",
            choices = listOf(
                TutorCurrentSessionChoice("choice-a", "磁通量变化"),
                TutorCurrentSessionChoice("choice-b", "感应电流方向"),
            ),
        ),
    )

    private fun hint(status: TutorCurrentSessionHintStatus) = TutorCurrentSessionHint(
        markdown = "先只比较两个时刻的磁通量。",
        slotToken = "c".repeat(64),
        status = status,
    )
}

@androidx.compose.runtime.Composable
private fun hostPresentation(
    presentation: TutorCurrentSessionPresentation,
    locallyVisibleHintToken: String? = null,
    hintCommitBusyToken: String? = null,
    hintCommitFailedToken: String? = null,
    hintSubmissionBlocked: Boolean = false,
    interactionBlocked: Boolean = false,
    onChoice: (String) -> Unit = {},
    onDirectExplanation: () -> Unit = {},
    onShowHint: (TutorCurrentSessionHint) -> Unit = {},
    onRetryHint: (TutorCurrentSessionHint) -> Unit = {},
) {
    HostPresentation(
        presentation = presentation,
        actionBusy = false,
        actionFeedback = null,
        freeResponseDraft = "",
        submittedFreeResponse = null,
        freeResponseSubmittedLocally = false,
        locallyVisibleHintToken = locallyVisibleHintToken,
        hintCommitBusyToken = hintCommitBusyToken,
        hintCommitFailedToken = hintCommitFailedToken,
        hintSubmissionBlocked = hintSubmissionBlocked,
        interactionBlocked = interactionBlocked,
        visualContent = {},
        onChoice = onChoice,
        onFreeResponseChange = {},
        onFreeResponse = {},
        onFreeResponseRetry = {},
        onVisualTarget = {},
        onDirectExplanation = onDirectExplanation,
        onShowHint = onShowHint,
        onRetryHint = onRetryHint,
        visualAlreadyRequested = false,
        onRequestVisual = {},
    )
}
