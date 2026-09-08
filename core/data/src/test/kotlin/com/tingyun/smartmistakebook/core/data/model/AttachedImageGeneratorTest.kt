package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.AttachedImageKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachedImageGeneratorTest {

    private val processImage = AttachedImage(
        imageId = "process-1",
        kind = AttachedImageKind.GENERATE_PROCESS,
        description = "数轴标注导数符号区间",
    )
    private val redrawImage = AttachedImage(
        imageId = "redraw-1",
        kind = AttachedImageKind.REDRAW_PROBLEM,
        description = "重绘题面",
    )

    @Test
    fun `resolves both kinds to local uris`() = runBlocking {
        val requests = mutableListOf<ImageGenerationRequest>()
        val generator = AttachedImageGenerator(
            generate = { request ->
                requests += request
                ImageRedrawResult(imageBytes = byteArrayOf(1), mimeType = "image/png")
            },
            persist = { _, _, sourceType, _ ->
                CanonicalSourceAssetRecord(
                    sourceAssetId = "asset-1",
                    contentSha256 = "a".repeat(64),
                    relativePath = "assets/1.png",
                    mimeType = "image/png",
                    byteSize = 4,
                    width = 10,
                    height = 10,
                    sourceType = sourceType,
                    createdAtEpochMillis = 1,
                )
            },
            resolveCurrentSheetBytes = { byteArrayOf(2) },
            uriFor = { "file://${it.relativePath}" },
        )

        val uris = generator.resolve(listOf(processImage, redrawImage), createdAtEpochMillis = 1)

        assertEquals(2, uris.size)
        assertEquals("file://assets/1.png", uris["process-1"])
        assertEquals("file://assets/1.png", uris["redraw-1"])
        // GENERATE_PROCESS 无源图；REDRAW_PROBLEM 带源图。
        assertEquals(null, requests[0].sourceImageBytes)
        assertTrue(requests[1].sourceImageBytes != null)
    }

    @Test
    fun `skips a redraw problem when the current sheet is unavailable`() = runBlocking {
        val generator = AttachedImageGenerator(
            generate = { ImageRedrawResult(byteArrayOf(1), "image/png") },
            persist = { _, _, _, _ -> asset() },
            resolveCurrentSheetBytes = { null },
            uriFor = { "file://x" },
        )

        val uris = generator.resolve(listOf(redrawImage), createdAtEpochMillis = 1)

        assertTrue(uris.isEmpty())
    }

    @Test
    fun `a generation failure degrades to that image's absence`() = runBlocking {
        val generator = AttachedImageGenerator(
            generate = { _ -> throw ImageGenerationException("network down") },
            persist = { _, _, _, _ -> asset() },
            resolveCurrentSheetBytes = { byteArrayOf(1) },
            uriFor = { "file://x" },
        )

        val uris = generator.resolve(listOf(processImage), createdAtEpochMillis = 1)

        assertTrue(uris.isEmpty())
    }

    @Test
    fun `cancellation propagates instead of degrading`() = runBlocking {
        val generator = AttachedImageGenerator(
            generate = { _ -> throw CancellationException() },
            persist = { _, _, _, _ -> asset() },
            resolveCurrentSheetBytes = { byteArrayOf(1) },
            uriFor = { "file://x" },
        )

        val error = runCatching { generator.resolve(listOf(processImage), createdAtEpochMillis = 1) }
            .exceptionOrNull()

        assertTrue(error is CancellationException)
    }

    @Test
    fun `generation prompts are shaped for the image kind`() = runBlocking {
        val prompts = mutableListOf<String>()
        AttachedImageGenerator(
            generate = { request ->
                prompts += request.prompt
                ImageRedrawResult(byteArrayOf(1), "image/png")
            },
            persist = { _, _, _, _ -> asset() },
            resolveCurrentSheetBytes = { byteArrayOf(1) },
            uriFor = { "file://x" },
        ).resolve(listOf(processImage, redrawImage), createdAtEpochMillis = 1)

        assertTrue(prompts[0].contains("解析过程图"))
        assertTrue(prompts[0].contains("数轴标注"))
        assertTrue(prompts[1].contains("去除手写笔迹"))
    }

    private fun asset() = CanonicalSourceAssetRecord(
        sourceAssetId = "asset-1",
        contentSha256 = "a".repeat(64),
        relativePath = "assets/1.png",
        mimeType = "image/png",
        byteSize = 4,
        width = 10,
        height = 10,
        sourceType = "TUTOR_ATTACHED",
        createdAtEpochMillis = 1,
    )
}
