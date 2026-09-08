package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.data.model.ApprovedImage
import com.tingyun.smartmistakebook.core.data.model.OpenAiModelProtocol
import com.tingyun.smartmistakebook.core.data.model.OpenAiSse
import com.tingyun.smartmistakebook.core.data.model.SSE_ACCEPT
import com.tingyun.smartmistakebook.core.data.model.JSON_MEDIA_TYPE
import com.tingyun.smartmistakebook.core.data.model.objectValue
import com.tingyun.smartmistakebook.core.data.model.parseObject
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import com.tingyun.smartmistakebook.core.model.ModelTaskInput
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/**
 * OpenAI Chat Completions 协议（spec §3.1）：现有行为的原样搬迁——本类只做委托，
 * 逻辑仍在 OpenAiModelProtocol/OpenAiSse，保证 P1 阶段行为逐字节不变。
 */
internal object OpenAiChatCompletionsProtocol : ModelWireProtocol {
    override val protocol = ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS
    override val supportsNativeTools = true

    override fun endpoint(baseUrl: HttpUrl, modelId: String, stream: Boolean): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegment("chat")
            .addPathSegment("completions")
            .build()

    override fun headers(apiKey: CharArray, stream: Boolean): List<Pair<String, String>> = listOf(
        "Authorization" to "Bearer ${String(apiKey)}",
        "Accept" to if (stream) SSE_ACCEPT else JSON_MEDIA_TYPE.toString(),
    )

    override fun requestBody(
        modelId: String,
        input: ModelTaskInput,
        images: List<ApprovedImage>,
        stream: Boolean,
        enableNativeTools: Boolean,
    ): String = OpenAiModelProtocol.requestBody(modelId, input, images, stream, enableNativeTools)

    override fun parseCompletion(body: String, input: ModelTaskInput, modelVersion: String): ModelTaskOutput =
        OpenAiModelProtocol.parseResponse(body, input, modelVersion)

    override fun streamDelta(payload: String): String? = OpenAiSse.deltaContent(payload)

    override fun reconstructedBody(rawSse: String): String = OpenAiSse.reconstructedChatCompletion(rawSse)

    override fun probeRequestBody(modelId: String, probe: ModelProbeKind): String = when (probe) {
        ModelProbeKind.STRUCTURED -> structuredProbeBody(modelId)
        ModelProbeKind.IMAGE -> imageProbeBody(modelId)
        ModelProbeKind.TOOLS -> toolsProbeBody(modelId)
    }

    override fun probeResponseText(body: String): String? = runCatching {
        parseObject(body)["choices"]?.jsonArray
            ?.firstOrNull()
            ?.objectValue()
            ?.get("message")
            ?.objectValue()
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()

    private fun structuredProbeBody(modelId: String): String = buildJsonObject {
        put("model", modelId)
        put("temperature", 0)
        put("max_tokens", ModelProbeSpec.PROBE_MAX_TOKENS)
        put("response_format", buildJsonObject { put("type", "json_object") })
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", ModelProbeSpec.STRUCTURED_INSTRUCTION)
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", ModelProbeSpec.STRUCTURED_USER)
                    },
                )
            },
        )
    }.toString()

    private fun imageProbeBody(modelId: String): String = buildJsonObject {
        put("model", modelId)
        put("temperature", 0)
        put("max_tokens", ModelProbeSpec.PROBE_MAX_TOKENS)
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", ModelProbeSpec.IMAGE_USER)
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        put(
                                            "image_url",
                                            buildJsonObject {
                                                put(
                                                    "url",
                                                    "data:image/png;base64," +
                                                        ModelProbeSpec.SYNTHETIC_IMAGE_BASE64,
                                                )
                                                put("detail", "low")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()

    private fun toolsProbeBody(modelId: String): String = buildJsonObject {
        put("model", modelId)
        put("temperature", 0)
        put("max_tokens", ModelProbeSpec.PROBE_MAX_TOKENS)
        put(
            "tools",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", "synthetic_compat_check")
                                put("description", "Synthetic compatibility check")
                                put(
                                    "parameters",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put(
                                                    "token",
                                                    buildJsonObject {
                                                        put("type", "string")
                                                        put(
                                                            "description",
                                                            "Echo the code you were asked to return",
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                        put("required", buildJsonArray { add(JsonPrimitive("token")) })
                                        put("additionalProperties", JsonPrimitive(false))
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", ModelProbeSpec.TOOLS_USER)
                    },
                )
            },
        )
    }.toString()
}
