package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSplitResult
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionSummary
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

/**
 * Screen-level composition seam for [CaptureScreen]. Component tests cover the
 * leaves; this test pins that the giant screen composable still assembles its
 * fresh-entry UI (top bar, entry actions) over empty fakes, so the planned
 * state-web refactor fails here instead of silently changing the capture flow.
 */
class CaptureScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun freshTutorCaptureScreenComposesEntryActions() {
        composeRule.setContent {
            MaterialTheme {
                CaptureScreen(
                    entryOrigin = CaptureEntryOrigin.TUTOR,
                    repository = FakeCaptureRepository(),
                    modelTasks = FakeModelTasks(),
                    onOpenModelSettings = {},
                    onTutorSessionReady = { _, _ -> },
                    onLibraryEntryReady = {},
                    onSplitReady = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("capture_screen").assertExists()
        composeRule.onNodeWithText("拍题讲解").assertExists()
        composeRule.onNodeWithTag("capture_take_picture_button").assertExists()
        composeRule.onNodeWithTag("capture_pick_photo_button").assertExists()
    }

    @Test
    fun freshLibraryCaptureScreenComposesEntryActions() {
        composeRule.setContent {
            MaterialTheme {
                CaptureScreen(
                    entryOrigin = CaptureEntryOrigin.LIBRARY,
                    repository = FakeCaptureRepository(),
                    modelTasks = FakeModelTasks(),
                    onOpenModelSettings = {},
                    onTutorSessionReady = { _, _ -> },
                    onLibraryEntryReady = {},
                    onSplitReady = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("capture_screen").assertExists()
        composeRule.onNodeWithText("录入错题").assertExists()
    }
}

private class FakeCaptureRepository : CaptureWorkflowRepository {
    override fun observePendingCaptures(): Flow<List<com.tingyun.smartmistakebook.core.domain.PendingCaptureItem>> =
        flowOf(emptyList())

    override suspend fun readPendingCapture(draftId: String): com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft? = null

    override suspend fun readDraftWorkspace(
        draftId: String,
    ): com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot? = null

    override suspend fun saveDraftWorkspace(
        request: com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest,
    ): com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot =
        error("not used in this test")

    override suspend fun consumeDraftWorkspace(
        request: com.tingyun.smartmistakebook.core.domain.ConsumeCaptureDraftWorkspaceRequest,
    ): Boolean = true

    override suspend fun importDraft(
        request: com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest,
    ): CaptureDraftSummary = error("not used in this test")

    override suspend fun appendDraftPage(
        request: com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest,
    ): CaptureDraftSummary = error("not used in this test")

    override suspend fun replaceDraft(
        request: com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest,
    ): CaptureDraftSummary = error("not used in this test")

    override suspend fun splitDraft(
        request: com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest,
    ): CaptureDraftSplitResult = error("not used in this test")

    override suspend fun confirmForTutoring(
        request: com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession = error("not used in this test")

    override suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession? = null

    override suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope> = emptyList()

    override suspend fun saveTutorSession(
        request: com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest,
    ): CapturedProblemCommitSummary = error("not used in this test")

    override suspend fun endTutorSessionWithoutSaving(
        request: com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest,
    ): com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveResult = error("not used in this test")

    override suspend fun confirmAndCommit(
        request: com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest,
    ): CapturedProblemCommitSummary = error("not used in this test")

    override suspend fun attachCleanRedrawImage(
        problemRevisionId: String,
        cleanImageBytes: ByteArray,
        cleanImageMimeType: String,
    ): Boolean = false
}

private class FakeModelTasks : ModelTaskRepository {
    // The screen wraps capabilities() in runCatching; a missing provider keeps
    // the fresh-entry UI free of any external-provider gating.
    override suspend fun capabilities(): ProviderCapabilitySnapshot =
        error("no provider configured in this test")

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
        flow { error("not used in this test") }
}
