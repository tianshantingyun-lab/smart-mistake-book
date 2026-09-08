package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.data.model.ApprovedImage
import com.tingyun.smartmistakebook.core.data.model.InvalidModelResponseException
import com.tingyun.smartmistakebook.core.data.model.JSON_MEDIA_TYPE
import com.tingyun.smartmistakebook.core.data.model.OpenAiModelProtocol
import com.tingyun.smartmistakebook.core.data.model.OpenAiModelTaskAdapters
import com.tingyun.smartmistakebook.core.data.model.OpenAiSse
import com.tingyun.smartmistakebook.core.data.model.SSE_ACCEPT
import com.tingyun.smartmistakebook.core.data.model.parseObject
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import com.tingyun.smartmistakebook.core.model.ModelTaskInput
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/**
 * Google Gemini `generateContent` 协议（spec 2026-09-08-multi-protocol §3.3）。
 *
 * 形状核对来源（官方 proto `googleapis/googleapis` 的
 * `google/ai/generativelanguage/v1beta/{generative_service,content}.proto`，2026-09-09）：
 * - 端点 `POST /v1beta/models/{model}:generateContent`（流式 `:streamGenerateContent`），
 *   模型与方法同属一个路径段。
 * - 请求 `GenerateContentRequest{model, system_instruction: Content, contents: [Content],
 *   generation_config: GenerationConfig}`；`Content{parts: [Part], role}`、
 *   `Part{text | inline_data: Blob}`、`Blob{mime_type, data}`（JSON 映射为
 *   `inlineData{mimeType,data}`）。
 * - 响应 `GenerateContentResponse{candidates: [Candidate{content: Content}]}`——文本在
 *   `candidates[].content.parts[].text`；流式的每个 SSE 帧也是一个完整响应。
 * - 结构化输出用 `generationConfig.responseMimeType = "application/json"`（proto 已声明）。
 *
 * 工具环按用户裁定走 Route B（`supportsNativeTools = false`，不发 tools）。
 */
internal object GeminiGenerateContentProtocol : ModelWireProtocol {
    override val protocol = ModelProviderProtocol.GEMINI_GENERATE_CONTENT
    override val supportsNativeTools = false

    override fun endpoint(baseUrl: HttpUrl, modelId: String, stream: Boolean): HttpUrl {
        val builder = baseUrl.newBuilder()
            .addPathSegment("v1beta")
            .addPathSegment("models")
            .addPathSegment(
                modelId + if (stream) ":streamGenerateContent" else ":generateContent",
            )
        // 流式端点默认返回 JSON 数组；alt=sse 才给 SSE 帧。
        if (stream) builder.addQueryParameter("alt", "sse")
        return builder.build()
    }

    override fun headers(apiKey: CharArray, stream: Boolean): List<Pair<String, String>> = listOf(
        "x-goog-api-key" to String(apiKey),
        "Accept" to if (stream) SSE_ACCEPT else JSON_MEDIA_TYPE.toString(),
    )

    override fun requestBody(
        modelId: String,
        input: ModelTaskInput,
        images: List<ApprovedImage>,
        stream: Boolean,
        enableNativeTools: Boolean,
    ): String = buildJsonObject {
        put(
            "systemInstruction",
            buildJsonObject {
                put(
                    "parts",
                    buildJsonArray {
                        add(buildJsonObject { put("text", OpenAiModelProtocol.SYSTEM_PROMPT) })
                    },
                )
            },
        )
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("text", OpenAiModelTaskAdapters.prompt(input))
                                    },
                                )
                                images.forEach { image ->
                                    add(
                                        buildJsonObject {
                                            put(
                                                "inlineData",
                                                buildJsonObject {
                                                    put("mimeType", image.mimeType)
                                                    put("data", image.base64())
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )
        put(
            "generationConfig",
            buildJsonObject {
                put("temperature", GEMINI_TEMPERATURE)
                put("responseMimeType", JSON_RESPONSE_MIME_TYPE)
            },
        )
    }.toString()

    override fun parseCompletion(
        body: String,
        input: ModelTaskInput,
        modelVersion: String,
    ): ModelTaskOutput {
        val text = textPartsOf(parseObject(body))
        if (text.isBlank()) {
            throw InvalidModelResponseException("Gemini reply carried no text part")
        }
        return parseTaskOutputFromText(text, input, modelVersion)
    }

    override fun streamDelta(payload: String): String? = runCatching {
        val event = parseObject(payload)
        textPartsOf(event).ifBlank { null }
    }.getOrNull()

    override fun reconstructedBody(rawSse: String): String {
        val text = buildString {
            for (block in OpenAiSse.eventDataBlocksTerminated(rawSse)) {
                if (block == OpenAiSse.DONE_BLOCK) break
                append(streamDelta(block) ?: continue)
            }
        }
        return buildJsonObject {
            put(
                "candidates",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "content",
                                buildJsonObject {
                                    put(
                                        "parts",
                                        buildJsonArray {
                                            add(buildJsonObject { put("text", text) })
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }.toString()
    }

    override fun probeRequestBody(modelId: String, probe: ModelProbeKind): String = when (probe) {
        ModelProbeKind.STRUCTURED -> structuredProbeBody(modelId)
        ModelProbeKind.IMAGE -> imageProbeBody(modelId)
        // Route B：本协议不声明原生工具，探测不会请求它。
        ModelProbeKind.TOOLS -> error("Gemini generateContent protocol does not use native tools")
    }

    override fun probeResponseText(body: String): String? = runCatching {
        textPartsOf(parseObject(body)).ifBlank { null }
    }.getOrNull()

    /** 把 `candidates[].content.parts[]` 里的文本部分按序拼接（忽略非文本 part）。 */
    private fun textPartsOf(envelope: JsonObject): String = envelope["candidates"]?.jsonArray
        .orEmpty()
        .mapNotNull { candidate -> (candidate as? JsonObject)?.get("content") as? JsonObject }
        .flatMap { content -> content["parts"]?.jsonArray.orEmpty() }
        .mapNotNull { part -> (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        .joinToString("")

    private fun structuredProbeBody(modelId: String): String = buildJsonObject {
        put(
            "systemInstruction",
            buildJsonObject {
                put(
                    "parts",
                    buildJsonArray {
                        add(buildJsonObject { put("text", ModelProbeSpec.STRUCTURED_INSTRUCTION) })
                    },
                )
            },
        )
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject { put("text", ModelProbeSpec.STRUCTURED_USER) })
                            },
                        )
                    },
                )
            },
        )
        put(
            "generationConfig",
            buildJsonObject { put("responseMimeType", JSON_RESPONSE_MIME_TYPE) },
        )
    }.toString()

    private fun imageProbeBody(modelId: String): String = buildJsonObject {
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put(
                                            "inlineData",
                                            buildJsonObject {
                                                put("mimeType", "image/png")
                                                put("data", ModelProbeSpec.SYNTHETIC_IMAGE_BASE64)
                                            },
                                        )
                                    },
                                )
                                add(buildJsonObject { put("text", ModelProbeSpec.IMAGE_USER) })
                            },
                        )
                    },
                )
            },
        )
    }.toString()

    private const val JSON_RESPONSE_MIME_TYPE = "application/json"
    private const val GEMINI_TEMPERATURE = 0.1
}
