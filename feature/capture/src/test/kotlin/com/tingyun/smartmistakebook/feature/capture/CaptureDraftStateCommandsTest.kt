package com.tingyun.smartmistakebook.feature.capture

import androidx.lifecycle.SavedStateHandle
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionSummary
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSplitResult
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft
import com.tingyun.smartmistakebook.core.model.CaptureFinalConfirmationRequestIdentity
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Pins the "再录一道" seam: the screen reset must leave the ViewModel workflow
 * out of SAVED as well, otherwise the next import is rejected by the state
 * machine and the stale savedEntryId trips its init require.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureDraftStateCommandsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `resetForNextCapture leaves the workflow out of SAVED and accepts a new import`() =
        runTest(dispatcher.scheduler) {
            val viewModel = CaptureViewModel(
                savedStateHandle = SavedStateHandle(),
                repository = FakeResetCaptureRepository(),
                modelTasks = FakeResetModelTasks(),
                ioDispatcher = dispatcher,
            )
            viewModel.acceptSource(
                uri = "file:///first.png",
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                requestId = "import-first",
                occurredAtEpochMillis = 10,
            )
            advanceUntilIdle()
            viewModel.confirm(
                workspaceIdentity = CaptureDraftWorkspaceIdentity(
                    draftId = "draft-1",
                    basisRevisionNumber = 1,
                    workspaceVersion = 1,
                    workspaceFingerprint = "a".repeat(64),
                    finalConfirmationRequest =
                        CaptureFinalConfirmationRequestIdentity("confirm-1", 20),
                ),
                origin = CaptureEntryOrigin.LIBRARY,
            )
            advanceUntilIdle()
            assertEquals(CaptureWorkflowPhase.SAVED, viewModel.uiState.value.workflow.phase)

            val state = CaptureScreenState()
            state.committedEntryId = "entry-1"
            state.captureError = "stale error"
            state.replacementCandidateUri = "file:///stale.png"
            val commands = CaptureDraftStateCommands(state)

            commands.resetForNextCapture(workflowReset = viewModel::reset)
            advanceUntilIdle()

            assertEquals(CaptureWorkflowPhase.IDLE, viewModel.uiState.value.workflow.phase)
            assertNull(viewModel.uiState.value.workflow.savedEntryId)
            assertNull(state.committedEntryId)
            assertNull(state.captureError)
            assertNull(state.replacementCandidateUri)

            viewModel.acceptSource(
                uri = "file:///second.png",
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                requestId = "import-second",
                occurredAtEpochMillis = 20,
            )
            advanceUntilIdle()

            assertEquals(CaptureWorkflowPhase.ASSESSING, viewModel.uiState.value.workflow.phase)
        }
}

private class FakeResetCaptureRepository : CaptureWorkflowRepository {
    override fun observePendingCaptures(): Flow<List<com.tingyun.smartmistakebook.core.domain.PendingCaptureItem>> =
        flowOf(emptyList())

    override suspend fun readPendingCapture(draftId: String): ResumableCaptureDraft? = null

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
    ): CaptureDraftSummary = draftSummary()

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
    ): com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveResult =
        error("not used in this test")

    override suspend fun confirmAndCommit(
        request: com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest,
    ): CapturedProblemCommitSummary = commitSummary()

    override suspend fun attachCleanRedrawImage(
        problemRevisionId: String,
        cleanImageBytes: ByteArray,
        cleanImageMimeType: String,
    ): Boolean = false

    private fun draftSummary(): CaptureDraftSummary {
        val page = CaptureSourcePage(
            pageIndex = 0,
            imageUri = "file:///draft-1.png",
            sourceAssetId = "asset-draft-1",
            sourceAssetSha256 = "a".repeat(64),
            width = 10,
            height = 10,
            byteSize = 100,
            createdAtEpochMillis = 1,
        )
        return CaptureDraftSummary(
            draftId = "draft-1",
            sourceAssetId = page.sourceAssetId,
            sourceAssetSha256 = page.sourceAssetSha256,
            revisionNumber = 1,
            width = page.width,
            height = page.height,
            byteSize = page.byteSize,
            status = "DRAFT",
            recognition = CaptureRecognitionSummary(),
            sourcePages = listOf(page),
        )
    }

    private fun commitSummary() = CapturedProblemCommitSummary(
        draftId = "draft-1",
        problemId = "problem-1",
        problemRevisionId = "revision-1",
        practiceUnitId = "unit-1",
        errorBookEntryId = "entry-1",
        created = true,
    )
}

private class FakeResetModelTasks : ModelTaskRepository {
    override suspend fun capabilities(): ProviderCapabilitySnapshot =
        error("not used in this test")

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
        flow { error("not used in this test") }
}
