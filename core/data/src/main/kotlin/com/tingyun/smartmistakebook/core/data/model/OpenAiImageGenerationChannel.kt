package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.MODEL_EGRESS_MAX_ASSET_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
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
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun redrawClean(request: ImageRedrawRequest): ImageRedrawResult =
        withContext(Dispatchers.IO) {
            requireSourceWithinEgressBound(request.photoBytes)
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
                .apply { authorization?.let { header("Authorization", it) } }
                .build()
            execute(http)
        }

    override suspend fun generate(request: ImageGenerationRequest): ImageRedrawResult =
        withContext(Dispatchers.IO) {
            if (request.sourceImageBytes != null) {
                // Seeded: image-to-image via /v1/images/edits (same contract as redrawClean).
                generateViaEdits(request)
            } else {
                // Unseeded: text-to-image via /v1/images/generations.
                generateViaGenerations(request)
            }
        }

    private fun generateViaEdits(request: ImageGenerationRequest): ImageRedrawResult {
        val sourceBytes = requireNotNull(request.sourceImageBytes) {
            "Image-to-image generation requires source image bytes"
        }
        val mimeType = requireNotNull(request.sourceImageMimeType) {
            "Image-to-image generation requires a source mime type"
        }
        requireSourceWithinEgressBound(sourceBytes)
        val mediaType = mimeType.toMediaType()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", modelId)
            .addFormDataPart("images[]", "source.$imageExt", sourceBytes.toRequestBody(mediaType))
            .addFormDataPart("prompt", request.prompt)
            .addFormDataPart("size", sizeFor(request.maxDimension))
            .addFormDataPart("quality", "high")
            .addFormDataPart("output_format", request.outputFormat)
            .build()
        val http = Request.Builder()
            .url("$baseUrl/images/edits")
            .post(body)
            .apply { authorization?.let { header("Authorization", it) } }
            .build()
        return execute(http)
    }

    private fun generateViaGenerations(request: ImageGenerationRequest): ImageRedrawResult {
        val body = ImageGenerationRequestBody(
            model = modelId,
            prompt = request.prompt,
            size = sizeFor(request.maxDimension),
            quality = "high",
            outputFormat = request.outputFormat,
            n = 1,
        )
        val jsonBody = json.encodeToString(ImageGenerationRequestBody.serializer(), body)
            .toRequestBody(JSON_MEDIA_TYPE)
        val http = Request.Builder()
            .url("$baseUrl/images/generations")
            .header("Accept", "application/json")
            .apply { authorization?.let { header("Authorization", it) } }
            .post(jsonBody)
            .build()
        return execute(http)
    }

    private fun execute(http: Request): ImageRedrawResult = client.newCall(http).execute().use { response ->
        if (!response.isSuccessful) {
            throw ImageGenerationException(
                "image generation failed: HTTP ${response.code} ${response.message}",
            )
        }
        parseEditResponse(response)
    }

    private fun parseEditResponse(response: Response): ImageRedrawResult {
        val text = response.body?.string() ?: throw ImageGenerationException("empty response")
        val parsed = json.decodeFromString<EditResponse>(text)
        val b64 = parsed.data.firstOrNull()?.b64Json
            ?: throw ImageGenerationException("no image in generation response")
        // okio rather than java.util.Base64: that class is API 26 while minSdk
        // is 23, and this decode runs on the image path every device can reach.
        val bytes = b64.decodeBase64()
            ?.toByteArray()
            ?: throw ImageGenerationException("image payload is not valid base64")
        if (bytes.isEmpty() || bytes.size > ImageRedrawResult.MAX_OUTPUT_BYTES) {
            throw ImageGenerationException("invalid image size in generation response")
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

    /**
     * 发送前上界核对（2026-09-14）。这两条链发的是 **multipart 原始字节**（不是 base64，没有
     * 4/3 膨胀），所以请求体 ≈ 源图字节 + 少量字段。源图来自规范资产（vault 导入上限 20 MiB），
     * 因此今天必然低于传输上限 36 MiB——**但这两个数字分处不同文件，此前没有任何地方把耦合写明**：
     * 来源上限一旦放宽，请求会在把整块字节读进内存、组完 multipart 之后才发现超限，白付一次内存
     * 与一次注定失败的出网。这里按模型层既有的"单资产出网上限"钉住（24 MiB，与 36 MiB 之间留
     * 字段与边界余量），失败即抛，调用方按"没有图"处理。
     */
    private fun requireSourceWithinEgressBound(sourceBytes: ByteArray) {
        require(sourceBytes.size.toLong() <= IMAGE_EDITS_MAX_SOURCE_BYTES) {
            "Image-to-image source exceeds the per-asset egress bound"
        }
    }

    companion object {
        const val DEFAULT_MODEL_ID = "gpt-image-2"

        /** 单张输入图上限＝单资产出网上限；见 [requireSourceWithinEgressBound] 的算术说明。 */
        internal const val IMAGE_EDITS_MAX_SOURCE_BYTES = MODEL_EGRESS_MAX_ASSET_BYTES
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
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

/** JSON request body for `POST /v1/images/generations` (text-to-image). */
@Serializable
private data class ImageGenerationRequestBody(
    val model: String,
    val prompt: String,
    val size: String,
    val quality: String,
    @SerialName("output_format") val outputFormat: String,
    val n: Int = 1,
)
