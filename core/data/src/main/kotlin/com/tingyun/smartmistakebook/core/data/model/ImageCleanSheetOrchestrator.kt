package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.ImageCleanSheet
import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyOutput
import com.tingyun.smartmistakebook.core.model.ImagePipelineProblemKind
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import kotlinx.coroutines.CancellationException

/**
 * Routes a photographed problem to the clean-sheet producer that matches its
 * classification, then assembles an [ImageCleanSheet].
 *
 * This is the image-pipeline spine (spec image-pipeline §1/§5): the multimodal
 * classify task (Direction B) decides WITH_FIGURE vs TEXT_ONLY, and the route
 * is a pure decision over that output —
 *   - WITH_FIGURE → the redrawn figure via [ImageGenerationChannel] (Direction A)
 *   - TEXT_ONLY   → the structured text the typesetter lays out (Direction C)
 *
 * The pipeline is deliberately not a fixed multi-step chain: the caller (a
 * capture coordinator or the tutor tool loop) supplies the classify snapshot
 * and the photo bytes it already holds under an authorized execution. This
 * class only routes and assembles.
 */
class ImageCleanSheetOrchestrator(
    private val redrawChannel: ImageGenerationChannel?,
) {
    /**
     * Routes one finished classify [snapshot] into a clean sheet.
     *
     * @param snapshot       the terminal IMAGE_PIPELINE_CLASSIFY task snapshot
     * @param photoBytes     the photographed problem bytes (JPEG/PNG)
     * @param photoMimeType  the photograph MIME type
     * @return a clean sheet for the figure or text branch; null when the model
     *         output cannot be used (e.g. no terminal result, or a redraw was
     *         needed but no channel is configured).
     */
    suspend fun route(
        snapshot: ModelTaskSnapshot,
        photoBytes: ByteArray,
        photoMimeType: String,
    ): ImageCleanSheet? {
        if (!snapshot.status.isTerminal) return null
        val classify = snapshot.output as? ImagePipelineClassifyOutput ?: return null
        return when (classify.problemKind) {
            ImagePipelineProblemKind.WITH_FIGURE -> routeWithFigure(
                classify = classify,
                photoBytes = photoBytes,
                photoMimeType = photoMimeType,
            )
            ImagePipelineProblemKind.TEXT_ONLY -> ImageCleanSheet(
                problemKind = ImagePipelineProblemKind.TEXT_ONLY,
                textMarkdown = classify.textMarkdown,
                formulas = classify.formulas,
                modelVersion = classify.modelVersion,
            )
        }
    }

    private suspend fun routeWithFigure(
        classify: ImagePipelineClassifyOutput,
        photoBytes: ByteArray,
        photoMimeType: String,
    ): ImageCleanSheet? {
        val channel = redrawChannel ?: return null
        val redrawn = try {
            channel.redrawClean(
                ImageRedrawRequest(
                    photoBytes = photoBytes,
                    photoMimeType = photoMimeType,
                    instruction = CLEAN_REDRAW_INSTRUCTION,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return ImageCleanSheet(
            problemKind = ImagePipelineProblemKind.WITH_FIGURE,
            cleanImageBytes = redrawn.imageBytes,
            cleanImageMimeType = redrawn.mimeType,
            modelVersion = classify.modelVersion,
        )
    }

    private companion object {
        const val CLEAN_REDRAW_INSTRUCTION =
            "去除手写笔迹，保留印刷题面文字和图形不变，重绘成干净的题面图。"
    }
}
