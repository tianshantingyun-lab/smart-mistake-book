package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import org.junit.Rule
import org.junit.Test

class StorageScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun storageScreenShowsExportUnavailableWithoutLearningMemoryCapability() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                StorageScreen(
                    learningMasteryDisplay = null,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("storage_export_learning_memory").assertIsDisplayed()
        composeRule.onNodeWithTag("storage_export_learning_memory").performClick()
        composeRule.onNodeWithText("暂时无法导出学习记录").assertIsDisplayed()
        listOf("证据质量", "置信度", "投影", "learner").forEach { forbidden ->
            composeRule.onAllNodesWithText(forbidden, substring = true, useUnmergedTree = true)
                .assertCountEquals(0)
        }
    }
}
