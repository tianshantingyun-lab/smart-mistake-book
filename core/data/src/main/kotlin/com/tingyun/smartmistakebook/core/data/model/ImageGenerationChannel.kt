package com.tingyun.smartmistakebook.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Image-to-image channel: redraws a photographed problem into a clean version
 * (handwriting removed, printed problem kept). This is the only figure path —
 * the multimodal model decides when to use it, and the MCP figure server
 * wraps this channel.
 */
interface ImageGenerationChannel {
    suspend fun redrawClean(request: ImageRedrawRequest): ImageRedrawResult
}

/** A request to redraw a photographed problem into a clean figure. */
@Serializable
data class ImageRedrawRequest(
    val photoBytes: ByteArray,
    val photoMimeType: String,
    val instruction: String,
    val outputFormat: String = "png",
    val maxDimension: Int = 1536,
) {
    init {
        require(photoMimeType == "image/jpeg" || photoMimeType == "image/png") {
            "Only JPEG/PNG photographs are supported"
        }
        require(instruction.isNotBlank() && instruction.length <= MAX_INSTRUCTION_CHARS) {
            "Image redraw instruction must be non-blank and bounded"
        }
        require(outputFormat == "png" || outputFormat == "jpeg" || outputFormat == "webp") {
            "Unsupported output format: $outputFormat"
        }
    }

    companion object {
        const val MAX_INSTRUCTION_CHARS = 32_000
    }
}

/** The clean redrawn figure produced by the channel. */
@Serializable
data class ImageRedrawResult(
    val imageBytes: ByteArray,
    val mimeType: String,
    val modelVersion: String = "",
) {
    companion object {
        const val MAX_OUTPUT_BYTES = 24L * 1_024L * 1_024L
    }
}
