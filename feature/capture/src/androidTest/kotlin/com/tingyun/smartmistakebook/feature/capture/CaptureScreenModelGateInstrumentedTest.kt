package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

/**
 * Pins the capture model gate after the global "model agent" consent toggle was deleted:
 * the two surfaces are mutually exclusive and both are chosen by whether a model is
 * configured, so a mis-wired flag fails here instead of silently killing capture rounds
 * (or showing a settings CTA forever).
 */
class CaptureScreenModelGateInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun buildWithoutModelEgressShowsTheSetupCallToAction() {
        composeModelGate(egressAllowed = false)

        composeRule.onNodeWithTag("capture_model_setup_block").assertExists()
        composeRule.onAllNodesWithTag("capture_model_task_card").assertCountEquals(0)
    }

    @Test
    fun capableProviderShowsTheModelCard() {
        composeModelGate(egressAllowed = true)

        composeRule.onNodeWithTag("capture_model_task_card").assertExists()
        composeRule.onAllNodesWithTag("capture_model_setup_block").assertCountEquals(0)
    }

    private fun composeModelGate(egressAllowed: Boolean) {
        val draft = CaptureScreenTestFixtures.resumableDraft()
        composeRule.setContent {
            MaterialTheme {
                CaptureScreen(
                    entryOrigin = CaptureEntryOrigin.LIBRARY,
                    repository = RestorationFakeResumeRepository(draft),
                    modelTasks = CaptureCapableModelTasks(),
                    onOpenModelSettings = {},
                    onTutorSessionReady = {},
                    onLibraryEntryReady = {},
                    onSplitReady = {},
                    onBack = {},
                    resumeDraftId = draft.draftId,
                    modelEgressAllowed = egressAllowed,
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("capture_screen").fetchSemanticsNodes().isNotEmpty()
        }
    }
}

/** An external, image-capable provider, i.e. exactly what the capture round needs. */
private class CaptureCapableModelTasks : com.tingyun.smartmistakebook.core.domain.ModelTaskRepository {
    override suspend fun capabilities(): ProviderCapabilitySnapshot = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "模型",
        modelId = "model",
        supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "configuration-v1",
    )

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

    // The screen dispatches one assessment round once a model is configured; the gate test
    // only needs the dispatch to be admitted, so the round stays silent.
    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = emptyFlow()
}
