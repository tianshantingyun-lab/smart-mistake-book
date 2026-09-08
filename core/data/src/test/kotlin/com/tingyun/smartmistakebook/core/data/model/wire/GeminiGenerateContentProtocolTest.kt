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
 * Gemini generateContent 协议（spec 2026-09-08-multi-protocol §3.3）：端点把模型与方法放在
 * 同一路径段（`:generateContent` / `:streamGenerateContent?alt=sse`），认证走 `x-goog-api-key`，
 * 请求是 `systemInstruction` + `contents[].parts[]`（文本 / `inlineData{mimeType,data}`），
 * 响应取 `candidates[].content.parts[].text`。形状来源：官方 proto
 * `googleapis/googleapis` 的 `generative_service.proto` 与 `content.proto`。
 */
class GeminiGenerateContentProtocolTest {
    private val protocol = GeminiGenerateContentProtocol
    private val input = KnowledgeQuizInput(
        knowledgeNodeId = "kc-derivative-monotonicity",
        subjectId = "MATH",
        materialTitle = "导数符号与单调性",
        materialContentMarkdown = "若 f'(x) > 0 在区间上恒成立，则 f 单调递增。",
        materialBoundaryMarkdown = "仅覆盖导数符号与单调性的关系，不含凹凸性。",
    )

    private fun bodyOf(images: List<com.tingyun.smartmistakebook.core.data.model.ApprovedImage> = emptyList()) =
        Json.parseToJsonElement(
            protocol.requestBody("gemini-test", input, images, stream = false, enableNativeTools = false),
        ).jsonObject

    @Test
    fun endpointPutsModelAndMethodInOnePathSegment() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent",
            protocol.endpoint(
                "https://generativelanguage.googleapis.com".toHttpUrl(),
                "gemini-test",
                stream = false,
            ).toString(),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:streamGenerateContent?alt=sse",
            protocol.endpoint(
                "https://generativelanguage.googleapis.com".toHttpUrl(),
                "gemini-test",
                stream = true,
            ).toString(),
        )
    }

    @Test
    fun headersUseTheGoogleApiKeyHeader() {
        val headers = protocol.headers("goog-test".toCharArray(), stream = false)
        assertEquals("goog-test", headers.single { it.first == "x-goog-api-key" }.second)
        assertEquals("application/json; charset=utf-8", headers.single { it.first == "Accept" }.second)
        assertTrue(headers.none { it.first == "Authorization" })
    }

    @Test
    fun requestBodyUsesSystemInstructionAndContentsParts() {
        val body = bodyOf()

        val systemParts = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray
        assertTrue(systemParts[0].jsonObject["text"]!!.jsonPrimitive.content.isNotBlank())
        val contents = body["contents"]!!.jsonArray
        assertEquals("user", contents[0].jsonObject["role"]?.jsonPrimitive?.content)
        val parts = contents[0].jsonObject["parts"]!!.jsonArray
        assertTrue(parts[0].jsonObject["text"]!!.jsonPrimitive.content.contains("kc-derivative-monotonicity"))
        // 结构化输出用 Gemini 的 responseMimeType；Route B 不发 tools。
        assertEquals(
            "application/json",
            body["generationConfig"]!!.jsonObject["responseMimeType"]?.jsonPrimitive?.content,
        )
        assertNull(body["tools"])
    }

    @Test
    fun requestBodyAttachesImagesAsInlineData() {
        val image = com.tingyun.smartmistakebook.core.data.model.ApprovedImage(
            mimeType = "image/jpeg",
            bytes = byteArrayOf(0x05, 0x06),
        )
        try {
            val parts = bodyOf(listOf(image))["contents"]!!.jsonArray[0]
                .jsonObject["parts"]!!.jsonArray
            val inlineData = parts[1].jsonObject["inlineData"]!!.jsonObject
            assertEquals("image/jpeg", inlineData["mimeType"]?.jsonPrimitive?.content)
            assertEquals(image.base64(), inlineData["data"]?.jsonPrimitive?.content)
        } finally {
            image.close()
        }
    }

    @Test
    fun parseCompletionReadsCandidateTextParts() {
        val output = protocol.parseCompletion(
            body = envelopeOf("""{"questionMarkdown":"题干","choices":[{"choiceId":"A","markdown":"甲"},{"choiceId":"B","markdown":"乙"}],"correctChoiceId":"A"}"""),
            input = input,
            modelVersion = "gemini-test",
        ) as KnowledgeQuizOutput

        assertEquals("题干", output.questionMarkdown)
        assertEquals("A", output.correctChoiceId)
        assertEquals("gemini-test", output.modelVersion)
    }

    @Test
    fun parseCompletionRejectsAReplyWithoutTextParts() {
        assertThrows(com.tingyun.smartmistakebook.core.data.model.InvalidModelResponseException::class.java) {
            protocol.parseCompletion("""{"candidates":[{"content":{"parts":[{"functionCall":{}}]}}]}""", input, "gemini-test")
        }
    }

    @Test
    fun streamDeltaReadsTextPartsFromEveryFrame() {
        assertEquals("你好", protocol.streamDelta(envelopeOf("你好")))
        assertNull(protocol.streamDelta("""{"candidates":[{"content":{"parts":[{}]}}]}"""))
        assertNull(protocol.streamDelta("not-json"))
    }

    @Test
    fun reconstructedBodyRebuildsCandidatePartsFromTheSseStream() {
        val rawSse = buildString {
            append("data: ${envelopeOf("你好")}\n\n")
            append("data: ${envelopeOf("世界")}\n\n")
        }

        val rebuilt = Json.parseToJsonElement(protocol.reconstructedBody(rawSse)).jsonObject
        val text = rebuilt["candidates"]!!.jsonArray[0].jsonObject["content"]!!
            .jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
        assertEquals("你好世界", text)
    }

    @Test
    fun probeBodiesUseGeminiShapesAndTheSharedTokens() {
        val structured = protocol.probeRequestBody("gemini-test", ModelProbeKind.STRUCTURED)
        assertTrue(structured.contains(ModelProbeSpec.STRUCTURED_TOKEN))
        assertTrue(structured.contains("systemInstruction"))

        val imageProbe = protocol.probeRequestBody("gemini-test", ModelProbeKind.IMAGE)
        assertTrue(imageProbe.contains("inlineData"))
        assertTrue(imageProbe.contains(ModelProbeSpec.SYNTHETIC_IMAGE_BASE64))

        assertThrows(IllegalStateException::class.java) {
            protocol.probeRequestBody("gemini-test", ModelProbeKind.TOOLS)
        }
    }

    @Test
    fun probeResponseTextReadsCandidateParts() {
        assertEquals("Q7M2", protocol.probeResponseText(envelopeOf("Q7M2")))
        assertNull(protocol.probeResponseText("{}"))
    }

    private fun envelopeOf(text: String): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
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
        },
    )
}
