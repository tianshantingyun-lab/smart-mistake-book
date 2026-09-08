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

    /**
     * Generates a figure from a prose prompt (text-to-image), optionally seeded
     * by a source image (image-to-image). This is the "tutor figure" path —
     * e.g. a worked-solution / process diagram that the model describes in
     * [ImageGenerationRequest.prompt]. The MCP figure server may also expose it.
     */
    suspend fun generate(request: ImageGenerationRequest): ImageRedrawResult
}

/** A request to generate a figure from prose, optionally seeded by a source image. */
@Serializable
data class ImageGenerationRequest(
    val prompt: String,
    val sourceImageBytes: ByteArray? = null,
    val sourceImageMimeType: String? = null,
    val outputFormat: String = "png",
    val maxDimension: Int = 1536,
) {
    init {
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS) {
            "Image generation prompt must be non-blank and bounded"
        }
        require(
            (sourceImageBytes == null) == (sourceImageMimeType == null),
        ) { "Source image bytes and mime type must be provided together" }
        sourceImageMimeType?.let {
            require(it == "image/jpeg" || it == "image/png") {
                "Only JPEG/PNG source images are supported"
            }
        }
        require(outputFormat == "png" || outputFormat == "jpeg" || outputFormat == "webp") {
            "Unsupported output format: $outputFormat"
        }
    }

    companion object {
        const val MAX_PROMPT_CHARS = 32_000
    }
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
