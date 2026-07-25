package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tingyun.smartmistakebook.core.ui.LocalImageLoadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CaptureSourceImagePreviewInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unreadableSourceIsReportedToTheParentGate() {
        var observedState = LocalImageLoadState.LOADING

        composeRule.setContent {
            MaterialTheme {
                CaptureSourceImagePreview(
                    imageUri = "content://smart-mistake-book-test/missing-image",
                    onStateChange = { observedState = it },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            observedState == LocalImageLoadState.UNAVAILABLE
        }
        composeRule.onNodeWithText("原图预览暂时不可用", substring = true).assertExists()
        composeRule.runOnIdle {
            assertEquals(LocalImageLoadState.UNAVAILABLE, observedState)
        }
    }
}
