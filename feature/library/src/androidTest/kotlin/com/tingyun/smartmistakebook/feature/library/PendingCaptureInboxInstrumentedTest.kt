package com.tingyun.smartmistakebook.feature.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.PendingCaptureItem
import com.tingyun.smartmistakebook.core.domain.PendingCaptureStage
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingCaptureInboxInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun studentSeesWhatIsSafeWhatIsWorkingAndWhatNeedsAttentionBeforeOpening() {
        var openedDraftId: String? = null
        composeRule.setContent {
            SmartMistakeBookTheme {
                PendingCaptureInboxContent(
                    state = PendingCaptureInboxState.Ready(
                        listOf(
                            pendingItem("draft-working", PendingCaptureStage.MODEL_WORKING),
                            pendingItem("draft-ready", PendingCaptureStage.READY_TO_REVIEW),
                            pendingItem("draft-retake", PendingCaptureStage.RECAPTURE_REQUIRED),
                        ),
                    ),
                    onOpenItem = { openedDraftId = it.draftId },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("3 道临时题记录保留在本机").assertExists()
        composeRule.onNodeWithText("正在整理 1 道 · 等你继续 1 道 · 需要处理 1 道")
            .assertExists()
        composeRule.onNodeWithText("正在整理题目，可稍后再来").assertExists()
        composeRule.captureLibraryQaScreenshot("pending-workbench-current.png")
        composeRule.onNodeWithText("题面已整理，可继续")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag("pending_capture_draft-ready")
            .performScrollTo()
            .performClick()

        assertEquals("draft-ready", openedDraftId)
    }

    private fun pendingItem(
        draftId: String,
        stage: PendingCaptureStage,
    ) = PendingCaptureItem(
        draftId = draftId,
        origin = CaptureEntryOrigin.LIBRARY,
        subject = "数学",
        title = when (stage) {
            PendingCaptureStage.MODEL_WORKING -> "函数单调性题"
            PendingCaptureStage.READY_TO_REVIEW -> "导数综合题"
            else -> "立体几何题"
        },
        currentRevisionNumber = 1,
        updatedAtEpochMillis = 1_752_988_800_000,
        stage = stage,
        tutorSessionId = null,
    )
}
