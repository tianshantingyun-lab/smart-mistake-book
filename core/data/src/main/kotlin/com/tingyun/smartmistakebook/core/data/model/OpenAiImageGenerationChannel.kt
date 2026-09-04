package com.tingyun.smartmistakebook.core.data.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Image-to-image channel that calls an OpenAI-compatible
 * `POST /v1/images/edits` endpoint (e.g. gpt-image-2) with the photographed
 * problem and an instruction, and returns the clean redrawn figure.
 *
 * The MCP figure server wraps this channel; the model decides when to use it.
 */
internal class OpenAiImageGenerationChannel(
    private val baseUrl: String = "https://api.openai.com/v1",
    private val modelId: String = DEFAULT_MODEL_ID,
    private val client: OkHttpClient = defaultClient(),
    private val authorization: String? = null,
) : ImageGenerationChannel {

    override suspend fun redrawClean(request: ImageRedrawRequest): ImageRedrawResult =
        withContext(Dispatchers.IO) {
            val mediaType = request.photoMimeType.toMediaType()
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", modelId)
                .addFormDataPart(
                    "images[]",
                    "source.$imageExt",
                    request.photoBytes.toRequestBody(mediaType),
                )
                .addFormDataPart("prompt", request.instruction)
                .addFormDataPart("size", sizeFor(request.maxDimension))
                .addFormDataPart("quality", "high")
                .addFormDataPart("output_format", request.outputFormat)
                .build()
            val http = Request.Builder()
                .url("$baseUrl/images/edits")
                .post(body)
                .apply {
                    authorization?.let { header("Authorization", it) }
                }
                .build()
            client.newCall(http).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ImageGenerationException(
                        "image edit failed: HTTP ${response.code} ${response.message}",
                    )
                }
                parseEditResponse(response)
            }
        }

    private fun parseEditResponse(response: Response): ImageRedrawResult {
        val text = response.body?.string() ?: throw ImageGenerationException("empty response")
        val parsed = Json.decodeFromString<EditResponse>(text)
        val b64 = parsed.data.firstOrNull()?.b64Json
            ?: throw ImageGenerationException("no image in edit response")
        val bytes = Base64.getDecoder().decode(b64)
        if (bytes.isEmpty() || bytes.size > ImageRedrawResult.MAX_OUTPUT_BYTES) {
            throw ImageGenerationException("invalid image size in edit response")
        }
        return ImageRedrawResult(
            imageBytes = bytes,
            mimeType = parsed.data.first().mimeType ?: "image/png",
            modelVersion = modelId,
        )
    }

    private fun sizeFor(maxDimension: Int): String = when {
        maxDimension <= 1024 -> "1024x1024"
        maxDimension <= 1536 -> "1536x1024"
        else -> "1024x1536"
    }

    private val imageExt: String
        get() = "png" // multipart part name extension is cosmetic; mimeType drives decode

    companion object {
        const val DEFAULT_MODEL_ID = "gpt-image-2"
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

internal class ImageGenerationException(message: String) : IllegalStateException(message)

@Serializable
private data class EditResponse(
    val created: Long = 0,
    val data: List<EditImage> = emptyList(),
)

@Serializable
private data class EditImage(
    @SerialName("b64_json") val b64Json: String? = null,
    @SerialName("revised_prompt") val revisedPrompt: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
)
