package com.tingyun.smartmistakebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterManifest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgePage
import com.tingyun.smartmistakebook.core.domain.LearningMasteryLoadState
import com.tingyun.smartmistakebook.core.domain.LearningMasteryOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryProjectionRevision
import com.tingyun.smartmistakebook.core.domain.LearningMasteryStatus
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubjectOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTrend
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.feature.profile.ProfileProductionCapability
import com.tingyun.smartmistakebook.feature.profile.ProfileProductionCapabilityProvider
import com.tingyun.smartmistakebook.feature.profile.ProfileProductionCapabilitySource
import com.tingyun.smartmistakebook.feature.profile.ProductionProfileHomeRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProductionProfileHomeInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun productionProfileHomeKeepsLearningBeforeSettingsAndExposesActions() {
        val repository = FakeProfileMasteryDisplay()
        val provider =
            ProfileProductionCapabilityProvider(
                adapterAvailability =
                    ProductionAdapterAvailabilityPort {
                        ProductionAdapterManifest.fromAvailable(
                            ProductionAdapter.entries.toSet(),
                        )
                    },
                capabilitySource =
                    ProfileProductionCapabilitySource {
                        ProfileProductionCapability(repository)
                    },
            )
        var learningCalls = 0
        var capabilityCalls = 0
        var privacyCalls = 0
        var reminderCalls = 0
        var storageCalls = 0

        composeRule.setContent {
            SmartMistakeBookTheme {
                ProductionProfileHomeRoute(
                    capabilityProvider = provider,
                    onOpenCapability = { capabilityCalls += 1 },
                    onOpenLearningMastery = { learningCalls += 1 },
                    onOpenDataPrivacy = { privacyCalls += 1 },
                    onOpenReminder = { reminderCalls += 1 },
                    onOpenStorage = { storageCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("profile_screen").assertIsDisplayed()
        composeRule.onNodeWithText("学习掌握").assertIsDisplayed()
        composeRule.onNodeWithText("数学").assertIsDisplayed()
        composeRule.onNodeWithText("需要再巩固").assertIsDisplayed()
        composeRule.onNodeWithText("物理").assertIsDisplayed()
        composeRule.onNodeWithText("比较稳").assertIsDisplayed()

        composeRule.onNodeWithTag("profile_open_learning_mastery")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("profile_settings").performScrollTo().assertIsDisplayed()
        listOf(
            "profile_capability_setting",
            "profile_privacy_setting",
            "profile_reminder_setting",
            "profile_storage_setting",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        }

        composeRule.runOnIdle {
            assertEquals(1, learningCalls)
            assertEquals(1, capabilityCalls)
            assertEquals(1, privacyCalls)
            assertEquals(1, reminderCalls)
            assertEquals(1, storageCalls)
        }
    }
}

private class FakeProfileMasteryDisplay : LearningMasteryDisplayRepository {
    private val revision = LearningMasteryProjectionRevision.fromOpaque("profile-home-v1")

    override fun observeSubjectOverview(): Flow<LearningMasteryLoadState<LearningMasteryOverview>> =
        flowOf(
            LearningMasteryLoadState.Content(
                LearningMasteryOverview(
                    revision = revision,
                    subjects =
                        listOf(
                            LearningMasterySubjectOverview(
                                subject = LearningMasterySubject.MATHEMATICS,
                                status = LearningMasteryStatus.NEEDS_REINFORCEMENT,
                                trend = LearningMasteryTrend.RECENTLY_FLUCTUATING,
                            ),
                            LearningMasterySubjectOverview(
                                subject = LearningMasterySubject.PHYSICS,
                                status = LearningMasteryStatus.FAIRLY_STEADY,
                                trend = LearningMasteryTrend.STEADY,
                            ),
                        ),
                ),
            ),
        )

    override fun observeSubjectTimeline(
        request: LearningMasteryTimelineRequest,
    ): Flow<LearningMasteryLoadState<com.tingyun.smartmistakebook.core.domain.LearningMasteryTimeline>> =
        flowOf(LearningMasteryLoadState.Empty(request.revision))

    override fun observeKnowledgePage(
        request: LearningMasteryPageRequest,
    ): Flow<LearningMasteryLoadState<LearningMasteryKnowledgePage>> =
        flowOf(LearningMasteryLoadState.Empty(request.revision))
}
