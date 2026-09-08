package com.tingyun.mcp.figure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Thin client for `POST /v1/images/edits` (gpt-image-2). Same multipart contract
 * that was validated in the app's OpenAiImageGenerationChannel: an input image +
 * an instruction in, a base64 clean image out.
 */
internal class OpenAiEditsClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-image-2",
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun redrawClean(sourceImage: ByteArray, mimeType: String): RedrawResult =
        withContext(Dispatchers.IO) {
            val ext = when (mimeType) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("images[]", "source.$ext", sourceImage.toRequestBody(mimeType.toMediaType()))
                .addFormDataPart("prompt", REDRAW_PROMPT)
                .addFormDataPart("size", "1536x1024")
                .addFormDataPart("quality", "high")
                .addFormDataPart("output_format", "png")
                .build()
            val http = Request.Builder()
                .url("$baseUrl/images/edits".toHttpUrl())
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            execute(http)
        }

    /**
     * Generates a figure from a prose instruction. With a [sourceImage] it is
     * image-to-image (`/v1/images/edits`); without, text-to-image
     * (`/v1/images/generations`). Same gpt-image-2 model and base64 contract.
     */
    suspend fun generate(
        instruction: String,
        sourceImage: ByteArray? = null,
        sourceMimeType: String? = null,
    ): RedrawResult = withContext(Dispatchers.IO) {
        require(instruction.isNotBlank() && instruction.length <= MAX_INSTRUCTION_CHARS) {
            "Instruction must be non-blank and bounded"
        }
        require((sourceImage == null) == (sourceMimeType == null)) {
            "Source image and mime type must be provided together"
        }
        val http = if (sourceImage != null) {
            val ext = when (sourceMimeType) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("images[]", "source.$ext", sourceImage.toRequestBody(sourceMimeType!!.toMediaType()))
                .addFormDataPart("prompt", instruction)
                .addFormDataPart("size", "1536x1024")
                .addFormDataPart("quality", "high")
                .addFormDataPart("output_format", "png")
                .build()
            Request.Builder()
                .url("$baseUrl/images/edits".toHttpUrl())
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
        } else {
            val jsonBody = json.encodeToString(
                ImageGenerationRequestBody.serializer(),
                ImageGenerationRequestBody(
                    model = model,
                    prompt = instruction,
                    size = "1536x1024",
                    quality = "high",
                    outputFormat = "png",
                    n = 1,
                ),
            ).toRequestBody(JSON_MEDIA_TYPE)
            Request.Builder()
                .url("$baseUrl/images/generations".toHttpUrl())
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .post(jsonBody)
                .build()
        }
        execute(http)
    }

    private fun execute(http: Request): RedrawResult = client.newCall(http).execute().use { response ->
        if (!response.isSuccessful) {
            throw EditsApiException("HTTP ${response.code} ${response.message}")
        }
        val text = response.body?.string() ?: throw EditsApiException("empty response")
        val parsed = json.decodeFromString<EditResponse>(text)
        val b64 = parsed.data.firstOrNull()?.b64Json
            ?: throw EditsApiException("no image in response")
        val bytes = Base64.getDecoder().decode(b64)
        if (bytes.isEmpty() || bytes.size > MAX_OUTPUT_BYTES) {
            throw EditsApiException("invalid output size")
        }
        RedrawResult(
            image = bytes,
            mimeType = parsed.data.first().mimeType ?: "image/png",
            model = model,
        )
    }

    private companion object {
        const val REDRAW_PROMPT =
            "这是一道被学生拍摄的题目照片。请保留印刷题面的所有文字与图形内容不变，" +
                "仅去除照片中的手写笔迹、涂改、无关阴影与折痕，重绘成一张干净的题面图。" +
                "不要改写、增删或重新排版任何印刷内容。"
        const val MAX_OUTPUT_BYTES = 24L * 1024L * 1024L
        const val MAX_INSTRUCTION_CHARS = 32_000
        val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

/** JSON request body for `POST /v1/images/generations` (gpt-image-2 text-to-image). */
@Serializable
private data class ImageGenerationRequestBody(
    val model: String,
    val prompt: String,
    val size: String,
    val quality: String,
    @SerialName("output_format") val outputFormat: String,
    val n: Int = 1,
)

internal data class RedrawResult(
    val image: ByteArray,
    val mimeType: String,
    val model: String,
)

internal class EditsApiException(message: String) : IllegalStateException(message)

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
