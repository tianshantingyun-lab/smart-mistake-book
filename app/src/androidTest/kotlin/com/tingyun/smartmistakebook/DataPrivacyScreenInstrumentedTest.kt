package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tingyun.smartmistakebook.core.domain.LearningMasteryEraseOutcome
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPrivacyRepository
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.NetworkMode
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DataPrivacyScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun confirmedEraseCallsTheLearningMemoryPrivacyRepository() {
        val repository = FakeLearningMasteryPrivacyRepository()
        composeRule.setContent {
            SmartMistakeBookTheme {
                DataPrivacyScreen(
                    capabilities =
                        AppCapabilitySnapshot(
                            networkMode = NetworkMode.LOCAL_FIRST,
                            cameraCaptureAvailable = true,
                            trustedOcrAvailable = false,
                            tutorTeachingEnabled = true,
                            remoteModelConfigured = false,
                        ),
                    learningMasteryPrivacy = repository,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("privacy_erase_learning_memory").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_erase_learning_memory").performClick()
        composeRule.onNodeWithText("清除学习记录？").assertIsDisplayed()
        composeRule.onNodeWithText("清除").performClick()
        composeRule.waitUntil(5_000) { repository.calls == 1 }
        composeRule.onNodeWithText("学习记录已清除").assertIsDisplayed()
        assertEquals(1, repository.calls)
    }
}

private class FakeLearningMasteryPrivacyRepository : LearningMasteryPrivacyRepository {
    var calls = 0

    override suspend fun eraseAllLearningData(): LearningMasteryEraseOutcome {
        calls += 1
        return LearningMasteryEraseOutcome(erasedAtEpochMillis = 0L)
    }
}
