package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.model.KnowledgeQuizInput
import com.tingyun.smartmistakebook.core.model.KnowledgeQuizOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI Responses 协议（spec 2026-09-08-multi-protocol §3.3）：请求用 `instructions` +
 * `input` 项（`input_text` / `input_image`），响应取 `output[].content[].output_text.text`，
 * 流式取 `response.output_text.delta` 的 `delta`。形状来源：官方 SDK `openai/openai-python`。
 */
class OpenAiResponsesProtocolTest {
    private val protocol = OpenAiResponsesProtocol
    private val input = KnowledgeQuizInput(
        knowledgeNodeId = "kc-derivative-monotonicity",
        subjectId = "MATH",
        materialTitle = "导数符号与单调性",
        materialContentMarkdown = "若 f'(x) > 0 在区间上恒成立，则 f 单调递增。",
        materialBoundaryMarkdown = "仅覆盖导数符号与单调性的关系，不含凹凸性。",
    )

    private fun bodyOf(images: List<com.tingyun.smartmistakebook.core.data.model.ApprovedImage> = emptyList()) =
        Json.parseToJsonElement(
            protocol.requestBody("gpt-test", input, images, stream = false, enableNativeTools = false),
        ).jsonObject

    @Test
    fun endpointIsTheResponsesPath() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            protocol.endpoint("https://api.openai.com/v1".toHttpUrl(), "gpt-test", stream = false).toString(),
        )
    }

    @Test
    fun headersUseBearerAuthentication() {
        val headers = protocol.headers("sk-test".toCharArray(), stream = false)
        assertEquals("Bearer sk-test", headers.single { it.first == "Authorization" }.second)
        assertEquals("application/json; charset=utf-8", headers.single { it.first == "Accept" }.second)
    }

    @Test
    fun requestBodyUsesInstructionsAndInputItems() {
        val body = bodyOf()

        assertEquals("gpt-test", body["model"]?.jsonPrimitive?.content)
        assertTrue(body["instructions"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
        val items = body["input"]!!.jsonArray
        assertEquals("user", items[0].jsonObject["role"]?.jsonPrimitive?.content)
        val blocks = items[0].jsonObject["content"]!!.jsonArray
        assertEquals("input_text", blocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(blocks[0].jsonObject["text"]!!.jsonPrimitive.content.contains("kc-derivative-monotonicity"))
        // Route B：不发 tools，也没有 response_format 信封，非流式不带 stream。
        assertNull(body["tools"])
        assertNull(body["response_format"])
        assertNull(body["stream"])
    }

    @Test
    fun requestBodyAttachesImagesAsInputImageItems() {
        val image = com.tingyun.smartmistakebook.core.data.model.ApprovedImage(
            mimeType = "image/jpeg",
            bytes = byteArrayOf(0x09, 0x08),
        )
        try {
            val blocks = bodyOf(listOf(image))["input"]!!.jsonArray[0]
                .jsonObject["content"]!!.jsonArray
            assertEquals("input_image", blocks[1].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals(
                "data:image/jpeg;base64,${image.base64()}",
                blocks[1].jsonObject["image_url"]?.jsonPrimitive?.content,
            )
            assertEquals("low", blocks[1].jsonObject["detail"]?.jsonPrimitive?.content)
        } finally {
            image.close()
        }
    }

    @Test
    fun parseCompletionReadsOutputTextBlocks() {
        val output = protocol.parseCompletion(
            body = envelopeOf("""{"questionMarkdown":"题干","choices":[{"choiceId":"A","markdown":"甲"},{"choiceId":"B","markdown":"乙"}],"correctChoiceId":"A"}"""),
            input = input,
            modelVersion = "gpt-test",
        ) as KnowledgeQuizOutput

        assertEquals("题干", output.questionMarkdown)
        assertEquals("A", output.correctChoiceId)
        assertEquals("gpt-test", output.modelVersion)
    }

    @Test
    fun parseCompletionIgnoresNonTextOutputItems() {
        val body = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "output",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "reasoning")
                                put("summary", buildJsonArray { })
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "message")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "output_text")
                                                put("text", "{\"questionMarkdown\":\"题干\",\"choices\":[{\"choiceId\":\"A\",\"markdown\":\"甲\"},{\"choiceId\":\"B\",\"markdown\":\"乙\"}],\"correctChoiceId\":\"B\"}")
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

        val output = protocol.parseCompletion(body, input, "gpt-test") as KnowledgeQuizOutput
        assertEquals("B", output.correctChoiceId)
    }

    @Test
    fun parseCompletionRejectsAReplyWithoutOutputText() {
        assertThrows(com.tingyun.smartmistakebook.core.data.model.InvalidModelResponseException::class.java) {
            protocol.parseCompletion("""{"output":[{"type":"reasoning"}]}""", input, "gpt-test")
        }
    }

    @Test
    fun streamDeltaReadsOnlyOutputTextDeltas() {
        assertEquals(
            "你好",
            protocol.streamDelta("""{"type":"response.output_text.delta","delta":"你好"}"""),
        )
        assertNull(protocol.streamDelta("""{"type":"response.created"}"""))
        assertNull(protocol.streamDelta("not-json"))
    }

    @Test
    fun reconstructedBodyRebuildsAnOutputTextBlockFromTheSseStream() {
        val rawSse = buildString {
            append("data: {\"type\":\"response.created\"}\n\n")
            append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}\n\n")
            append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"世界\"}\n\n")
            append("data: {\"type\":\"response.completed\"}\n\n")
        }

        val rebuilt = Json.parseToJsonElement(protocol.reconstructedBody(rawSse)).jsonObject
        val text = rebuilt["output"]!!.jsonArray[0].jsonObject["content"]!!
            .jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
        assertEquals("你好世界", text)
    }

    @Test
    fun probeBodiesUseResponsesShapesAndTheSharedTokens() {
        val structured = protocol.probeRequestBody("gpt-test", ModelProbeKind.STRUCTURED)
        assertTrue(structured.contains(ModelProbeSpec.STRUCTURED_TOKEN))
        assertTrue(structured.contains("input_text"))

        val imageProbe = protocol.probeRequestBody("gpt-test", ModelProbeKind.IMAGE)
        assertTrue(imageProbe.contains("input_image"))
        assertTrue(imageProbe.contains(ModelProbeSpec.SYNTHETIC_IMAGE_BASE64))

        assertThrows(IllegalStateException::class.java) {
            protocol.probeRequestBody("gpt-test", ModelProbeKind.TOOLS)
        }
    }

    @Test
    fun probeResponseTextReadsOutputText() {
        assertEquals("Q7M2", protocol.probeResponseText(envelopeOf("Q7M2")))
        assertNull(protocol.probeResponseText("{}"))
    }

    private fun envelopeOf(text: String): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
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
        },
    )
}
