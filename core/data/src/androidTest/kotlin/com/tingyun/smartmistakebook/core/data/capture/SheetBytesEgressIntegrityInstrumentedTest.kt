package com.tingyun.smartmistakebook.core.data.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.data.model.AttachedImageGenerator
import com.tingyun.smartmistakebook.core.data.model.ImageRedrawResult
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureTranscriptionReview
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.AttachedImageKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 题面字节出网前的完整性核对（2026-09-14）。
 *
 * 消灭的失败：讲题会话的题面按 URI 直接读文件，是图片链上最后一处「按路径取字节」的地方——
 * 规范记录核对过之后又按路径重读一次，中间被替换/损坏的字节仍可能被 POST 出去。现在字节
 * 一律取自 `vault.resolve(record)`（逐位核对 sha256 与字节数）。核对不过时这条链的姿态是
 * **fail-closed：没有图，而不是崩**——由 `AttachedImageGenerator.resolveOne` 的 catch 兜住。
 */
@RunWith(AndroidJUnit4::class)
class SheetBytesEgressIntegrityInstrumentedTest : CaptureWorkflowTestBase() {

    @Test
    fun tamperedOrMissingSheetNeverReachesTheImageRequest() = runBlocking {
        val source = createJpeg(24, 24)
        val imported = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "sheet-egress-import",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.CAMERA,
                // 讲题会话只能由 TUTOR 入口的采集创建（confirmForTutoring 的既有前置）。
                origin = CaptureEntryOrigin.TUTOR,
                occurredAtEpochMillis = 1_000,
            ),
        )
        val session = repository.confirmForTutoring(
            ConfirmCapturedProblemRequest(
                requestId = "sheet-egress-confirm",
                draftId = imported.draftId,
                expectedRevisionNumber = imported.revisionNumber,
                subject = "MATH",
                title = "含图函数题",
                transcription = "求单调区间。",
                writingLayer = CaptureWritingLayer.PRINTED,
                transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                occurredAtEpochMillis = 2_000,
            ),
        )

        var generated = 0
        val generator = AttachedImageGenerator(
            generate = { request ->
                generated += 1
                // 只关心"有没有带着题面字节走到这里"，不需要真的出网。
                ImageRedrawResult(
                    imageBytes = request.sourceImageBytes ?: ByteArray(0),
                    mimeType = "image/png",
                )
            },
            persist = { bytes, mimeType, sourceType, createdAt ->
                CanonicalSourceAssetRecord(
                    sourceAssetId = "attached-egress-1",
                    contentSha256 = "0".repeat(64),
                    relativePath = "source-assets/attached-egress-1.png",
                    mimeType = mimeType,
                    byteSize = bytes.size.toLong(),
                    width = 1,
                    height = 1,
                    sourceType = sourceType,
                    createdAtEpochMillis = createdAt,
                )
            },
            resolveCurrentSheetBytes = { repository.readTutorSessionSheetBytes(session.sessionId) },
            uriFor = { record -> "file:///${record.relativePath}" },
        )
        val redraw = AttachedImage(
            imageId = "redraw-1",
            kind = AttachedImageKind.REDRAW_PROBLEM,
            description = "重绘题面",
            accessibilityText = "重绘后的题面图",
        )

        // 基线：完好的规范资产读得出来，并真的走到生成请求（否则下面的断言证明不了任何事）。
        assertNotNull(repository.readTutorSessionSheetBytes(session.sessionId))
        assertEquals(1, generator.resolve(listOf(redraw), 3_000).size)
        assertEquals(1, generated)

        // 同样的路径、同样的字节数、不同的内容：只有逐位核对能抓住它。
        val record = requireNotNull(database.readProblemDraft(imported.draftId)?.sourceAsset)
        val canonicalFile = File(context.filesDir, record.relativePath)
        canonicalFile.writeBytes(ByteArray(record.byteSize.toInt()) { 7 })

        assertTrue(
            "被替换的题图必须被核对拦下，而不是原样返回字节",
            runCatching { repository.readTutorSessionSheetBytes(session.sessionId) }.isFailure,
        )
        assertTrue(generator.resolve(listOf(redraw), 4_000).isEmpty())
        assertEquals("核对不过时不得再发起生成请求", 1, generated)

        // 文件没了同样拦下（不是退回"按路径尽力读"）。
        canonicalFile.delete()
        assertTrue(
            runCatching { repository.readTutorSessionSheetBytes(session.sessionId) }.isFailure,
        )
        assertTrue(generator.resolve(listOf(redraw), 5_000).isEmpty())
        assertEquals(1, generated)
    }
}
