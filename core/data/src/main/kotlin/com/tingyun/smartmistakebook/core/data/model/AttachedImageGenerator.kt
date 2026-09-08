package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.AttachedImageKind
import kotlinx.coroutines.CancellationException

/**
 * Resolves a model-authored [AttachedImage] intent into a renderable local image
 * URI: it redraws/generates the figure, persists it as a canonical asset, and
 * maps each intent to its local `file://`/`content://` URI.
 *
 * Injectable seams keep this pure and testable without DNS/network:
 * - [generate] redraws/generates a figure from a [ImageGenerationRequest].
 * - [persist] stores decoded bytes as a canonical asset (asset vault).
 * - [resolveCurrentSheetBytes] supplies the current question's problem-sheet
 *   bytes for a REDRAW_PROBLEM (auto-resolved, never model-supplied).
 * - [uriFor] turns a persisted asset record into a renderable local URI.
 *
 * One failed figure (network/auth/decode) degrades to that image's absence — the
 * reply still shows its text; cancellation propagates so an app shutdown is not
 * swallowed.
 */
class AttachedImageGenerator(
    private val generate: suspend (ImageGenerationRequest) -> ImageRedrawResult,
    private val persist: (bytes: ByteArray, mimeType: String, sourceType: String, createdAtEpochMillis: Long) -> CanonicalSourceAssetRecord,
    private val resolveCurrentSheetBytes: suspend () -> ByteArray?,
    private val uriFor: (CanonicalSourceAssetRecord) -> String,
) {
    /**
     * Resolves [images] into local URIs keyed by [AttachedImage.imageId]. Images
     * that cannot be produced (no source sheet, generation failure) are omitted.
     */
    suspend fun resolve(
        images: List<AttachedImage>,
        createdAtEpochMillis: Long,
    ): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (image in images) {
            resolveOne(image, createdAtEpochMillis)?.let { out[image.imageId] = it }
        }
        return out
    }

    private suspend fun resolveOne(
        image: AttachedImage,
        createdAtEpochMillis: Long,
    ): String? = try {
        val request = requestFor(image) ?: return null
        val result = generate(request)
        persist(result.imageBytes, result.mimeType, StudyDbValue.SourceAssetType.TUTOR_ATTACHED, createdAtEpochMillis).let(uriFor)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** Builds the concrete generation request for an intent; null when the figure cannot be produced. */
    private suspend fun requestFor(image: AttachedImage): ImageGenerationRequest? = when (image.kind) {
        AttachedImageKind.GENERATE_PROCESS -> ImageGenerationRequest(
            prompt = processPrompt(image.description),
        )
        AttachedImageKind.REDRAW_PROBLEM -> {
            val source = resolveCurrentSheetBytes() ?: return null
            ImageGenerationRequest(
                prompt = redrawProblemInstruction(),
                sourceImageBytes = source,
                sourceImageMimeType = "image/png",
            )
        }
    }

    private fun processPrompt(description: String): String =
        "根据下面讲解生成题目解析过程图：$description。" +
            "只绘制与当前题目相关的图形、数轴、步骤标注，不添加题意之外的符号。"

    private fun redrawProblemInstruction(): String =
        "这是一道被拍摄的题目。请保留印刷题面的所有文字与图形内容不变，仅去除手写笔迹、涂改、阴影与折痕，重绘成一张干净的题面图。"
}
