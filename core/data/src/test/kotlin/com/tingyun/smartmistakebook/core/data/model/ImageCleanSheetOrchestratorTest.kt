package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.ImageCleanSheet
import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyInput
import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyOutput
import com.tingyun.smartmistakebook.core.model.ImagePipelineProblemKind
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCleanSheetOrchestratorTest {

    @Test
    fun `routes a text-only classify to structured text`() = runBlocking {
        val orchestrator = ImageCleanSheetOrchestrator(redrawChannel = null)

        val sheet = orchestrator.route(
            snapshot = terminalSnapshot(
                ImagePipelineClassifyOutput(
                    problemKind = ImagePipelineProblemKind.TEXT_ONLY,
                    textMarkdown = "求函数 f(x)=x^2 的单调区间。",
                    formulas = listOf("f(x)=x^2"),
                    modelVersion = "test-model",
                ),
            ),
            photoBytes = byteArrayOf(1),
            photoMimeType = "image/jpeg",
        )

        assertEquals(ImagePipelineProblemKind.TEXT_ONLY, sheet!!.problemKind)
        assertEquals("求函数 f(x)=x^2 的单调区间。", sheet.textMarkdown)
        assertEquals(listOf("f(x)=x^2"), sheet.formulas)
        assertNull(sheet.cleanImageBytes)
    }

    @Test
    fun `routes a with-figure classify through the redraw channel`() = runBlocking {
        val photo = byteArrayOf(1, 2, 3, 4)
        val redrawn = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        var requestedInstruction: String? = null
        val orchestrator = ImageCleanSheetOrchestrator(
            redrawChannel = object : ImageGenerationChannel {
                override suspend fun redrawClean(request: ImageRedrawRequest): ImageRedrawResult {
                    requestedInstruction = request.instruction
                    return ImageRedrawResult(
                        imageBytes = redrawn,
                        mimeType = "image/png",
                        modelVersion = "gpt-image-2",
                    )
                }

                override suspend fun generate(request: ImageGenerationRequest): ImageRedrawResult =
                    throw UnsupportedOperationException("generate not exercised by this fake")
            },
        )

        val sheet = orchestrator.route(
            snapshot = terminalSnapshot(
                ImagePipelineClassifyOutput(
                    problemKind = ImagePipelineProblemKind.WITH_FIGURE,
                    modelVersion = "test-model",
                ),
            ),
            photoBytes = photo,
            photoMimeType = "image/jpeg",
        )

        assertEquals(ImagePipelineProblemKind.WITH_FIGURE, sheet!!.problemKind)
        assertArrayEquals(redrawn, sheet.cleanImageBytes)
        assertEquals("image/png", sheet.cleanImageMimeType)
        assertTrue(requestedInstruction.orEmpty().contains("去除手写笔迹"))
    }

    @Test
    fun `with-figure classify without a configured channel fails closed`() = runBlocking {
        val orchestrator = ImageCleanSheetOrchestrator(redrawChannel = null)

        val sheet = orchestrator.route(
            snapshot = terminalSnapshot(
                ImagePipelineClassifyOutput(
                    problemKind = ImagePipelineProblemKind.WITH_FIGURE,
                    modelVersion = "test-model",
                ),
            ),
            photoBytes = byteArrayOf(1),
            photoMimeType = "image/jpeg",
        )

        assertNull("No redraw channel means the figure branch must not fabricate a sheet", sheet)
    }

    @Test
    fun `a redraw channel failure degrades to no sheet instead of throwing`() = runBlocking {
        val orchestrator = ImageCleanSheetOrchestrator(
            redrawChannel = object : ImageGenerationChannel {
                override suspend fun redrawClean(request: ImageRedrawRequest): ImageRedrawResult =
                    throw ImageGenerationException("network down")

                override suspend fun generate(request: ImageGenerationRequest): ImageRedrawResult =
                    throw UnsupportedOperationException("generate not exercised by this fake")
            },
        )

        val sheet = orchestrator.route(
            snapshot = terminalSnapshot(
                ImagePipelineClassifyOutput(
                    problemKind = ImagePipelineProblemKind.WITH_FIGURE,
                    modelVersion = "test-model",
                ),
            ),
            photoBytes = byteArrayOf(1),
            photoMimeType = "image/jpeg",
        )

        assertNull(sheet)
    }

    @Test
    fun `a non-terminal or non-classify snapshot is ignored`() = runBlocking {
        val orchestrator = ImageCleanSheetOrchestrator(redrawChannel = null)

        val running = orchestrator.route(
            snapshot = runningSnapshot(),
            photoBytes = byteArrayOf(1),
            photoMimeType = "image/jpeg",
        )

        assertNull(running)
    }

    private fun terminalSnapshot(output: ImagePipelineClassifyOutput): ModelTaskSnapshot {
        val request = ModelTaskRequest(
            requestId = "classify-request-1",
            input = ImagePipelineClassifyInput(
                sourceAssetId = "source-asset-1",
                imageWidth = 100,
                imageHeight = 200,
            ),
            occurredAtEpochMillis = 1_000L,
        )
        return snapshot(request, ModelTaskStatus.SUCCEEDED, output)
    }

    private fun runningSnapshot(): ModelTaskSnapshot {
        val request = ModelTaskRequest(
            requestId = "classify-request-2",
            input = ImagePipelineClassifyInput(
                sourceAssetId = "source-asset-1",
                imageWidth = 100,
                imageHeight = 200,
            ),
            occurredAtEpochMillis = 1_000L,
        )
        return snapshot(request, ModelTaskStatus.RUNNING, null)
    }

    private fun snapshot(
        request: ModelTaskRequest,
        status: ModelTaskStatus,
        output: com.tingyun.smartmistakebook.core.model.ModelTaskOutput?,
    ): ModelTaskSnapshot = ModelTaskSnapshot(
        taskId = "task-${request.requestId}",
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = status,
        stateVersion = 1,
        stage = ModelTaskStage.COMPLETE,
        userMessage = "",
        attemptCount = 1,
        output = output,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
    )
}
