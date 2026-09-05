package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureModelTaskCoordinatorTest {
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
    fun localRequestExecutesOnceAndRejectsDuplicateDispatch() =
        runTest(dispatcher.scheduler) {
            val request = request()
            val repository = CoordinatorFakeModelTasks(
                flowOf(runningSnapshot(request)),
            )
            val scope = CoroutineScope(dispatcher)
            val coordinator = CaptureModelTaskCoordinator(
                modelTasks = repository,
                scope = scope,
            )
            val snapshots = mutableListOf<ModelTaskSnapshot>()

            coordinator.executeAssessment(
                request = request,
                onSnapshot = { snapshots += it },
            )
            coordinator.executeAssessment(
                request = request,
                onSnapshot = { snapshots += it },
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, repository.executeCount)
            assertEquals(1, snapshots.size)
        }

    @Test
    fun completedFlowAllowsTheSameRequestToBeRetried() =
        runTest(dispatcher.scheduler) {
            val request = request()
            val repository = CoordinatorFakeModelTasks(
                flowOf(runningSnapshot(request)),
            )
            val scope = CoroutineScope(dispatcher)
            val coordinator = CaptureModelTaskCoordinator(
                modelTasks = repository,
                scope = scope,
            )

            coordinator.executeAssessment(
                request = request,
                onSnapshot = {},
            )
            dispatcher.scheduler.advanceUntilIdle()
            coordinator.executeAssessment(
                request = request,
                onSnapshot = {},
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, repository.executeCount)
        }

    @Test
    fun parseRequestExecutesThroughTheSameCoordinatorGuard() =
        runTest(dispatcher.scheduler) {
            val request = ModelTaskRequest(
                requestId = "parse-1",
                input = CaptureParseInput(
                    draftId = "draft-1",
                    origin = CaptureAssessmentOrigin.LIBRARY,
                    basisRevisionNumber = 1,
                    sourceAssets = listOf(
                        CaptureSourceAssetRef(
                            assetId = "asset-1",
                            sha256 = "a".repeat(64),
                            width = 100,
                            height = 200,
                            pageIndex = 0,
                        ),
                    ),
                    assessmentRequestId = "assess-1",
                ),
                occurredAtEpochMillis = 1,
                egressManifest = null,
            )
            val repository = CoordinatorFakeModelTasks(
                flowOf(runningSnapshot(request)),
            )
            val scope = CoroutineScope(dispatcher)
            val coordinator = CaptureModelTaskCoordinator(
                modelTasks = repository,
                scope = scope,
            )

            coordinator.executeParse(
                request = request,
                onSnapshot = {},
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, repository.executeCount)
        }

    private fun request() = ModelTaskRequest(
        requestId = "assess-1",
        input = CaptureAssessmentInput(
            draftId = "draft-1",
            sourceAssetId = "asset-1",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 100,
            imageHeight = 200,
        ),
        occurredAtEpochMillis = 1,
        egressManifest = null,
    )

    private fun runningSnapshot(request: ModelTaskRequest) = ModelTaskSnapshot(
        taskId = request.requestId,
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = ModelTaskStatus.RUNNING,
        stateVersion = 1,
        stage = ModelTaskStage.READING_IMAGE,
        userMessage = "读取题图中",
        attemptCount = 0,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun localProvider() = ProviderCapabilitySnapshot(
        providerId = "local",
        providerDisplayName = "本地",
        modelId = "local-model",
        supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
    )

    private fun externalProvider() = ProviderCapabilitySnapshot(
        providerId = "external",
        providerDisplayName = "外部",
        modelId = "external-model",
        supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "v1",
    )
}

private class CoordinatorFakeModelTasks(
    private val snapshotFlow: Flow<ModelTaskSnapshot>,
) : ModelTaskRepository {
    var executeCount = 0

    override suspend fun capabilities(): ProviderCapabilitySnapshot =
        error("not used")

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> {
        executeCount += 1
        return snapshotFlow
    }
}
