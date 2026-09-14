package com.tingyun.smartmistakebook.feature.capture

import androidx.lifecycle.SavedStateHandle
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureFailureCode
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionSummary
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
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

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
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
    fun `new source moves workflow to assessing and keeps exact draft identity`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeCaptureRepository()
            val viewModel = CaptureViewModel(
                savedStateHandle = SavedStateHandle(),
                repository = repository,
                modelTasks = FakeModelTasks(),
                ioDispatcher = dispatcher,
            )

            viewModel.acceptSource(
                uri = "file:///new.png",
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                requestId = "import-1",
                occurredAtEpochMillis = 10,
            )
            advanceUntilIdle()

            val workflow = viewModel.uiState.value.workflow
            assertEquals(CaptureWorkflowPhase.ASSESSING, workflow.phase)
            assertEquals("draft-1", workflow.draftId)
            assertEquals(1, workflow.basisRevisionNumber)
            assertEquals("import-1", workflow.latestRequestId)
            assertEquals("draft-1", viewModel.uiState.value.importedDraft?.summary?.draftId)
            assertNull(viewModel.uiState.value.userError)

            viewModel.consumeDraftImported("import-1")
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.importedDraft)
        }

    @Test
    fun `failed import is retryable and retry replays the same source command`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeCaptureRepository(
                importResults = ArrayDeque(
                    listOf(
                        Result.failure(IOException("disk full")),
                        Result.success(draftSummary()),
                    ),
                ),
            )
            val viewModel = CaptureViewModel(
                savedStateHandle = SavedStateHandle(),
                repository = repository,
                modelTasks = FakeModelTasks(),
                ioDispatcher = dispatcher,
            )

            viewModel.acceptSource(
                uri = "file:///retry.png",
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.TUTOR,
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                requestId = "import-retry",
                occurredAtEpochMillis = 20,
            )
            advanceUntilIdle()

            assertEquals(CaptureWorkflowPhase.FAILED, viewModel.uiState.value.workflow.phase)
            assertEquals(
                CaptureFailureCode.IMPORT_REJECTED,
                viewModel.uiState.value.workflow.failureCode,
            )
            assertEquals("file:///retry.png", repository.importedUris.last())

            viewModel.retryFailedWorkflow()
            advanceUntilIdle()

            assertEquals(CaptureWorkflowPhase.ASSESSING, viewModel.uiState.value.workflow.phase)
            assertEquals(2, repository.importedUris.size)
            assertNull(viewModel.uiState.value.userError)
        }

    @Test
    fun `missing resume draft reports a permanent missing state`() =
        runTest(dispatcher.scheduler) {
            val viewModel = CaptureViewModel(
                savedStateHandle = SavedStateHandle(mapOf("resumeDraftId" to "missing-draft")),
                repository = FakeCaptureRepository(),
                modelTasks = FakeModelTasks(),
                ioDispatcher = dispatcher,
            )

            advanceUntilIdle()

            assertEquals(CaptureResumeLoadState.MISSING, viewModel.uiState.value.resumeState)
            assertEquals(CaptureWorkflowPhase.FAILED, viewModel.uiState.value.workflow.phase)
            assertEquals(false, viewModel.uiState.value.workflow.canRetry)
        }

    @Test
    fun `append and replace replay through the same durable workflow`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeCaptureRepository(
                importResults = ArrayDeque(
                    listOf(
                        Result.success(draftSummary("draft-1", 1)),
                        Result.success(draftSummary("draft-1", 2)),
                        Result.success(draftSummary("draft-1", 3)),
                    ),
                ),
            )
            val viewModel = CaptureViewModel(
                savedStateHandle = SavedStateHandle(),
                repository = repository,
                modelTasks = FakeModelTasks(),
                ioDispatcher = dispatcher,
            )

            viewModel.acceptSource(
                uri = "file:///new.png",
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                requestId = "import-1",
                occurredAtEpochMillis = 10,
            )
            advanceUntilIdle()

            viewModel.acceptSource(
                uri = "file:///append.png",
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                purpose = CaptureAcquisitionPurpose.APPEND_DRAFT,
                requestId = "append-1",
                occurredAtEpochMillis = 20,
                expectedPageCount = 1,
            )
            advanceUntilIdle()

            assertEquals("append-1", viewModel.uiState.value.importedDraft?.requestId)
            assertEquals(
                CaptureAcquisitionPurpose.APPEND_DRAFT,
                viewModel.uiState.value.importedDraft?.purpose,
            )
            assertEquals(2, viewModel.uiState.value.workflow.basisRevisionNumber)
            viewModel.consumeDraftImported("append-1")
            advanceUntilIdle()

            viewModel.acceptSource(
                uri = "file:///replace.png",
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                purpose = CaptureAcquisitionPurpose.REPLACE_DRAFT,
                requestId = "replace-1",
                occurredAtEpochMillis = 30,
            )
            advanceUntilIdle()

            assertEquals("replace-1", viewModel.uiState.value.importedDraft?.requestId)
            assertEquals(
                CaptureAcquisitionPurpose.REPLACE_DRAFT,
                viewModel.uiState.value.importedDraft?.purpose,
            )
            assertEquals(3, viewModel.uiState.value.workflow.basisRevisionNumber)
            assertEquals("file:///replace.png", viewModel.uiState.value.importedDraft?.sourceUri)
        }

    @Test
    fun `library confirmation publishes one saved entry id`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeCaptureRepository()
            val viewModel = CaptureViewModel(
                savedStateHandle = SavedStateHandle(),
                repository = repository,
                modelTasks = FakeModelTasks(),
                ioDispatcher = dispatcher,
            )
            viewModel.acceptSource(
                uri = "file:///commit.png",
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                requestId = "import-commit",
                occurredAtEpochMillis = 30,
            )
            advanceUntilIdle()

            viewModel.confirm(
                workspaceIdentity = workspaceIdentity(),
                origin = CaptureEntryOrigin.LIBRARY,
            )
            advanceUntilIdle()

            assertEquals(CaptureWorkflowPhase.SAVED, viewModel.uiState.value.workflow.phase)
            assertEquals("entry-1", viewModel.uiState.value.workflow.savedEntryId)
            assertEquals("draft-1", repository.committedDraftIds.single())
        }

    @Test
    fun `capturing another question after a saved commit accepts a new import`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeCaptureRepository(
                importResults = ArrayDeque(
                    listOf(
                        Result.success(draftSummary("draft-1", 1)),
                        Result.success(draftSummary("draft-2", 1)),
                    ),
                ),
            )
            val viewModel = CaptureViewModel(
                savedStateHandle = SavedStateHandle(),
                repository = repository,
                modelTasks = FakeModelTasks(),
                ioDispatcher = dispatcher,
            )
            viewModel.acceptSource(
                uri = "file:///first.png",
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                requestId = "import-first",
                occurredAtEpochMillis = 30,
            )
            advanceUntilIdle()
            viewModel.confirm(workspaceIdentity(), CaptureEntryOrigin.LIBRARY)
            advanceUntilIdle()
            assertEquals(CaptureWorkflowPhase.SAVED, viewModel.uiState.value.workflow.phase)

            viewModel.reset()

            viewModel.acceptSource(
                uri = "file:///second.png",
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                requestId = "import-second",
                occurredAtEpochMillis = 60,
            )
            advanceUntilIdle()

            assertEquals(CaptureWorkflowPhase.ASSESSING, viewModel.uiState.value.workflow.phase)
            assertEquals("draft-2", viewModel.uiState.value.workflow.draftId)
            assertNull(viewModel.uiState.value.workflow.savedEntryId)
            assertEquals("import-second", viewModel.uiState.value.workflow.latestRequestId)
        }

    @Test
    fun `commit failure retries the exact persisted confirmation without a new draft`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeCaptureRepository(
                commitResults = ArrayDeque(
                    listOf(
                        Result.failure(IOException("db full")),
                        Result.success(commitSummary("entry-1")),
                    ),
                ),
            )
            val viewModel = CaptureViewModel(
                savedStateHandle = SavedStateHandle(),
                repository = repository,
                modelTasks = FakeModelTasks(),
                ioDispatcher = dispatcher,
            )
            viewModel.acceptSource(
                uri = "file:///commit-fail.png",
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                requestId = "import-commit-fail",
                occurredAtEpochMillis = 50,
            )
            advanceUntilIdle()

            viewModel.confirm(workspaceIdentity(), CaptureEntryOrigin.LIBRARY)
            advanceUntilIdle()

            assertEquals(CaptureWorkflowPhase.FAILED, viewModel.uiState.value.workflow.phase)
            assertEquals(
                CaptureFailureCode.COMMIT_REJECTED,
                viewModel.uiState.value.workflow.failureCode,
            )

            viewModel.retryFailedWorkflow()
            advanceUntilIdle()

            assertEquals(CaptureWorkflowPhase.SAVED, viewModel.uiState.value.workflow.phase)
            assertEquals("entry-1", viewModel.uiState.value.workflow.savedEntryId)
            assertEquals(listOf("draft-1", "draft-1"), repository.confirmAttempts)
            assertEquals(listOf("draft-1"), repository.committedDraftIds)
        }

    private fun workspaceIdentity() = CaptureDraftWorkspaceIdentity(
        draftId = "draft-1",
        basisRevisionNumber = 1,
        workspaceVersion = 1,
        workspaceFingerprint = "a".repeat(64),
        finalConfirmationRequest = CaptureFinalConfirmationRequestIdentity("confirm-1", 40),
    )

    private fun draftSummary(
        draftId: String = "draft-1",
        revisionNumber: Int = 1,
    ): CaptureDraftSummary {
        val page = CaptureSourcePage(
            pageIndex = 0,
            imageUri = "file:///$draftId.png",
            sourceAssetId = "asset-$draftId",
            sourceAssetSha256 = "a".repeat(64),
            width = 10,
            height = 10,
            byteSize = 100,
            createdAtEpochMillis = 1,
        )
        return CaptureDraftSummary(
            draftId = draftId,
            sourceAssetId = page.sourceAssetId,
            sourceAssetSha256 = page.sourceAssetSha256,
            revisionNumber = revisionNumber,
            width = page.width,
            height = page.height,
            byteSize = page.byteSize,
            status = "DRAFT",
            recognition = CaptureRecognitionSummary(),
            sourcePages = listOf(page),
        )
    }

    private fun commitSummary(entryId: String) = CapturedProblemCommitSummary(
        draftId = "draft-1",
        problemId = "problem-1",
        problemRevisionId = "revision-1",
        practiceUnitId = "unit-1",
        errorBookEntryId = entryId,
        created = true,
    )
}

private class FakeCaptureRepository(
    private val importResults: ArrayDeque<Result<CaptureDraftSummary>> = ArrayDeque(
        listOf(Result.success(draftSummaryFixture())),
    ),
    private val commitResults: ArrayDeque<Result<CapturedProblemCommitSummary>> = ArrayDeque(
        listOf(Result.success(commitSummaryFixture())),
    ),
) : CaptureWorkflowRepository {
    val importedUris = mutableListOf<String>()
    val committedDraftIds = mutableListOf<String>()
    val confirmAttempts = mutableListOf<String>()

    override fun observePendingCaptures(): Flow<List<com.tingyun.smartmistakebook.core.domain.PendingCaptureItem>> =
        flowOf(emptyList())

    override suspend fun readPendingCapture(draftId: String): ResumableCaptureDraft? = null

    override suspend fun readDraftWorkspace(
        draftId: String,
    ): com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot? = null

    override suspend fun saveDraftWorkspace(
        request: com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest,
    ): com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot =
        error("not used")

    override suspend fun consumeDraftWorkspace(
        request: com.tingyun.smartmistakebook.core.domain.ConsumeCaptureDraftWorkspaceRequest,
    ): Boolean = true

    override suspend fun importDraft(
        request: com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest,
    ): CaptureDraftSummary {
        importedUris += request.localUri
        return importResults.removeFirst().getOrThrow()
    }

    override suspend fun appendDraftPage(
        request: com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest,
    ): CaptureDraftSummary = importResults.removeFirst().getOrThrow()

    override suspend fun replaceDraft(
        request: com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest,
    ): CaptureDraftSummary = importResults.removeFirst().getOrThrow()

    override suspend fun splitDraft(
        request: com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest,
    ): com.tingyun.smartmistakebook.core.domain.CaptureDraftSplitResult = error("not used")

    override suspend fun confirmForTutoring(
        request: com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession = error("not used")

    override suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession? = null

    override suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope> = emptyList()

    override suspend fun saveTutorSession(
        request: com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest,
    ): com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary = error("not used")

    override suspend fun endTutorSessionWithoutSaving(
        request: com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest,
    ): com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveResult = error("not used")

    override suspend fun confirmAndCommit(
        request: com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest,
    ): CapturedProblemCommitSummary {
        confirmAttempts += request.draftId
        val result = commitResults.removeFirst()
        result.getOrThrow()
        committedDraftIds += request.draftId
        return result.getOrThrow()
    }

    override suspend fun attachCleanRedrawImage(
        problemRevisionId: String,
        cleanImageBytes: ByteArray,
        cleanImageMimeType: String,
    ): Boolean = false
}

private class FakeModelTasks : ModelTaskRepository {
    override suspend fun capabilities(): ProviderCapabilitySnapshot =
        error("not used in this test")

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
        flow { error("not used in this test") }
}

private fun draftSummaryFixture(): CaptureDraftSummary {
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

private fun commitSummaryFixture() = CapturedProblemCommitSummary(
    draftId = "draft-1",
    problemId = "problem-1",
    problemRevisionId = "revision-1",
    practiceUnitId = "unit-1",
    errorBookEntryId = "entry-1",
    created = true,
)
