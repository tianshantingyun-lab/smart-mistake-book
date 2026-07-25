package com.tingyun.smartmistakebook.core.data.capture

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class LocalTextRecognition(
    val blocks: List<LocalRecognizedTextBlock>,
    val producerVersion: String,
)

internal data class LocalRecognizedTextBlock(
    val text: String,
    val sourceRegion: NormalizedSourceRegion?,
    val confidence: Double?,
)

internal fun interface LocalQuestionTextRecognizer {
    suspend fun recognize(
        canonicalFile: File,
        imageWidth: Int,
        imageHeight: Int,
    ): LocalTextRecognition
}

/** Bundled Chinese + Latin OCR. It never downloads a model or promotes output to trusted text. */
internal class MlKitChineseQuestionTextRecognizer(
    private val context: Context,
) : LocalQuestionTextRecognizer {
    override suspend fun recognize(
        canonicalFile: File,
        imageWidth: Int,
        imageHeight: Int,
    ): LocalTextRecognition {
        require(imageWidth > 0 && imageHeight > 0) { "OCR image dimensions must be positive" }
        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build(),
        )
        return try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(canonicalFile))
            val result = recognizer.process(image).awaitResult()
            LocalTextRecognition(
                blocks = result.textBlocks.mapNotNull { block ->
                    val text = block.text.trim()
                    if (text.isBlank()) return@mapNotNull null
                    LocalRecognizedTextBlock(
                        text = text,
                        sourceRegion = block.boundingBox?.let { bounds ->
                            bounds.toNormalizedRegion(imageWidth, imageHeight)
                        },
                        confidence = block.lines
                            .mapNotNull { line -> line.confidence.toBoundedConfidence() }
                            .takeIf(List<Double>::isNotEmpty)
                            ?.average(),
                    )
                },
                producerVersion = PRODUCER_VERSION,
            )
        } finally {
            recognizer.close()
        }
    }

    private fun android.graphics.Rect.toNormalizedRegion(
        imageWidth: Int,
        imageHeight: Int,
    ): NormalizedSourceRegion? {
        val safeLeft = left.coerceIn(0, imageWidth)
        val safeTop = top.coerceIn(0, imageHeight)
        val safeRight = right.coerceIn(0, imageWidth)
        val safeBottom = bottom.coerceIn(0, imageHeight)
        if (safeLeft >= safeRight || safeTop >= safeBottom) return null
        return NormalizedSourceRegion(
            left = safeLeft.toDouble() / imageWidth,
            top = safeTop.toDouble() / imageHeight,
            right = safeRight.toDouble() / imageWidth,
            bottom = safeBottom.toDouble() / imageHeight,
        )
    }

    private fun Float.toBoundedConfidence(): Double? = toDouble().takeIf {
        it.isFinite() && it in 0.0..1.0
    }

    private companion object {
        const val PRODUCER_VERSION = "mlkit-chinese-v2-16.0.1"
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener { continuation.cancel() }
}
