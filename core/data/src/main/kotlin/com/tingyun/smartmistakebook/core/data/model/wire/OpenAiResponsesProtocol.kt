package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.data.model.ApprovedImage
import com.tingyun.smartmistakebook.core.data.model.InvalidModelResponseException
import com.tingyun.smartmistakebook.core.data.model.JSON_MEDIA_TYPE
import com.tingyun.smartmistakebook.core.data.model.OpenAiModelProtocol
import com.tingyun.smartmistakebook.core.data.model.OpenAiModelTaskAdapters
import com.tingyun.smartmistakebook.core.data.model.OpenAiSse
import com.tingyun.smartmistakebook.core.data.model.SSE_ACCEPT
import com.tingyun.smartmistakebook.core.data.model.objectValue
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
 * OpenAI Responses 协议（spec 2026-09-08-multi-protocol §3.3）。官方 README 明示 Responses
 * 是当前主接口、Chat Completions 为"上一代标准"（`openai/openai-python` README，2026-09-09）。
 *
 * 形状核对来源（同一 SDK）：
 * - 请求：`responses.create(model, instructions, input)`；输入项 `input_text{text}` /
 *   `input_image{image_url, detail}`（`types/responses/response_input_text_param.py`、
 *   `response_input_image_param.py`）。
 * - 响应：`output[].content[]` 的文本块 `output_text{text}`（`response_output_text.py`）。
 * - 流式：`response.output_text.delta` 事件的 `delta`（`response_text_delta_event.py`）。
 *
 * 工具环按用户裁定走 Route B（`supportsNativeTools = false`）；结构化输出靠 prompt 约束。
 */
internal object OpenAiResponsesProtocol : ModelWireProtocol {
    override val protocol = ModelProviderProtocol.OPENAI_RESPONSES
    override val supportsNativeTools = false

    override fun endpoint(baseUrl: HttpUrl, modelId: String, stream: Boolean): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegment("responses")
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
    ): String = buildJsonObject {
        put("model", modelId)
        put("instructions", OpenAiModelProtocol.SYSTEM_PROMPT)
        if (stream) put("stream", true)
        put(
            "input",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", OpenAiModelTaskAdapters.prompt(input))
                                    },
                                )
                                images.forEach { image ->
                                    add(
                                        buildJsonObject {
                                            put("type", "input_image")
                                            put(
                                                "image_url",
                                                "data:${image.mimeType};base64,${image.base64()}",
                                            )
                                            put("detail", "low")
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )
    }.toString()

    override fun parseCompletion(
        body: String,
        input: ModelTaskInput,
        modelVersion: String,
    ): ModelTaskOutput {
        val text = textBlocksOf(parseObject(body))
        if (text.isBlank()) {
            throw InvalidModelResponseException("Responses reply carried no output_text block")
        }
        return parseTaskOutputFromText(text, input, modelVersion)
    }

    override fun streamDelta(payload: String): String? = runCatching {
        val event = parseObject(payload)
        if (event["type"]?.jsonPrimitive?.contentOrNull != OUTPUT_TEXT_DELTA_EVENT) {
            return@runCatching null
        }
        event["delta"]?.jsonPrimitive?.contentOrNull
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
                "output",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "message")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "output_text")
                                            put("text", text)
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
        ModelProbeKind.TOOLS -> error("OpenAI Responses protocol does not use native tools")
    }

    override fun probeResponseText(body: String): String? = runCatching {
        textBlocksOf(parseObject(body)).ifBlank { null }
    }.getOrNull()

    /** 把 `output[].content[]` 里的 `output_text` 块按序拼接（忽略其它输出项）。 */
    private fun textBlocksOf(envelope: JsonObject): String = envelope["output"]?.jsonArray
        .orEmpty()
        .mapNotNull { item ->
            val itemObject = item as? JsonObject ?: return@mapNotNull null
            if (itemObject["type"]?.jsonPrimitive?.contentOrNull != "message") {
                return@mapNotNull null
            }
            itemObject["content"]?.jsonArray
        }
        .flatten()
        .mapNotNull { content ->
            val contentObject = content as? JsonObject ?: return@mapNotNull null
            if (contentObject["type"]?.jsonPrimitive?.contentOrNull != "output_text") {
                return@mapNotNull null
            }
            contentObject["text"]?.jsonPrimitive?.contentOrNull
        }
        .joinToString("")

    private fun structuredProbeBody(modelId: String): String = buildJsonObject {
        put("model", modelId)
        put("instructions", ModelProbeSpec.STRUCTURED_INSTRUCTION)
        put(
            "input",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", ModelProbeSpec.STRUCTURED_USER)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()

    private fun imageProbeBody(modelId: String): String = buildJsonObject {
        put("model", modelId)
        put(
            "input",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "input_image")
                                        put(
                                            "image_url",
                                            "data:image/png;base64," +
                                                ModelProbeSpec.SYNTHETIC_IMAGE_BASE64,
                                        )
                                        put("detail", "low")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", ModelProbeSpec.IMAGE_USER)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()

    private const val OUTPUT_TEXT_DELTA_EVENT = "response.output_text.delta"
}
