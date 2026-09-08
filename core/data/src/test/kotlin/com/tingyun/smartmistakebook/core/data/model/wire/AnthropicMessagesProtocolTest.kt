package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.model.KnowledgeQuizInput
import com.tingyun.smartmistakebook.core.model.KnowledgeQuizOutput
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic Messages 协议（spec 2026-09-08-multi-protocol §3.3）：请求用顶层 `system` +
 * user 内容块（无 system role）、`x-api-key` + `anthropic-version` 认证、`max_tokens` 必填；
 * 响应取 `content[].text`；流式取 `content_block_delta` 的 `text_delta.text`。
 * 形状来源：官方 SDK `anthropics/anthropic-sdk-python`（见实现类注释）。
 */
class AnthropicMessagesProtocolTest {
    private val protocol = AnthropicMessagesProtocol
    private val input = KnowledgeQuizInput(
        knowledgeNodeId = "kc-derivative-monotonicity",
        subjectId = "MATH",
        materialTitle = "导数符号与单调性",
        materialContentMarkdown = "若 f'(x) > 0 在区间上恒成立，则 f 单调递增。",
        materialBoundaryMarkdown = "仅覆盖导数符号与单调性的关系，不含凹凸性。",
        lastMasteryScore = 0.42,
        lastEvidenceAtEpochMillis = 1_700_000_000_000L,
    )

    private fun bodyOf(images: List<com.tingyun.smartmistakebook.core.data.model.ApprovedImage> = emptyList()) =
        Json.parseToJsonElement(
            protocol.requestBody("claude-test", input, images, stream = false, enableNativeTools = false),
        ).jsonObject

    @Test
    fun endpointIsTheMessagesPath() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            protocol.endpoint("https://api.anthropic.com".toHttpUrl(), "claude-test", stream = false).toString(),
        )
    }

    @Test
    fun headersUseApiKeyAndAnthropicVersionInsteadOfBearer() {
        val headers = protocol.headers("sk-ant-test".toCharArray(), stream = false)
        assertEquals("sk-ant-test", headers.single { it.first == "x-api-key" }.second)
        assertEquals("2023-06-01", headers.single { it.first == "anthropic-version" }.second)
        assertEquals("application/json; charset=utf-8", headers.single { it.first == "Accept" }.second)
        assertTrue(headers.none { it.first == "Authorization" })
        assertEquals(
            "text/event-stream",
            protocol.headers("k".toCharArray(), stream = true).single { it.first == "Accept" }.second,
        )
    }

    @Test
    fun requestBodyUsesTopLevelSystemAndUserContentBlocks() {
        val body = bodyOf()

        assertEquals("claude-test", body["model"]?.jsonPrimitive?.content)
        assertTrue(body["max_tokens"]!!.jsonPrimitive.content.toInt() > 0)
        // 系统提示在顶层；messages 里不得出现 system role。
        assertTrue(body["system"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
        val blocks = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", blocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(blocks[0].jsonObject["text"]!!.jsonPrimitive.content.contains("kc-derivative-monotonicity"))
        // Route B：不发 tools，也没有 json_object 信封。
        assertNull(body["tools"])
        assertNull(body["response_format"])
        assertNull(body["stream"])
    }

    @Test
    fun requestBodyAttachesImagesAsBase64SourceBlocks() {
        val image = com.tingyun.smartmistakebook.core.data.model.ApprovedImage(
            mimeType = "image/jpeg",
            bytes = byteArrayOf(0x01, 0x02, 0x03),
        )
        try {
            val blocks = bodyOf(listOf(image))["messages"]!!.jsonArray[0]
                .jsonObject["content"]!!.jsonArray
            assertEquals("image", blocks[1].jsonObject["type"]?.jsonPrimitive?.content)
            val source = blocks[1].jsonObject["source"]!!.jsonObject
            assertEquals("base64", source["type"]?.jsonPrimitive?.content)
            assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.content)
            assertEquals(image.base64(), source["data"]?.jsonPrimitive?.content)
        } finally {
            image.close()
        }
    }

    @Test
    fun streamingFlagIsOnlySentWhenStreaming() {
        val streamed = Json.parseToJsonElement(
            protocol.requestBody("claude-test", input, emptyList(), stream = true, enableNativeTools = false),
        ).jsonObject
        assertEquals(true, streamed["stream"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun parseCompletionReadsTheTextBlocksAndParsesTheTaskPayload() {
        val output = protocol.parseCompletion(
            body = envelopeOf("""{"questionMarkdown":"若 f'(x)>0 恒成立，则 f 单调递增。","choices":[{"choiceId":"A","markdown":"递增"},{"choiceId":"B","markdown":"递减"}],"correctChoiceId":"A"}"""),
            input = input,
            modelVersion = "claude-test",
        ) as KnowledgeQuizOutput

        assertEquals("若 f'(x)>0 恒成立，则 f 单调递增。", output.questionMarkdown)
        assertEquals("A", output.correctChoiceId)
        assertEquals("claude-test", output.modelVersion)
    }

    @Test
    fun parseCompletionRejectsAReplyWithoutTextBlocks() {
        assertThrows(com.tingyun.smartmistakebook.core.data.model.InvalidModelResponseException::class.java) {
            protocol.parseCompletion(
                body = """{"content":[{"type":"tool_use","id":"x"}]}""",
                input = input,
                modelVersion = "claude-test",
            )
        }
    }

    @Test
    fun streamDeltaReadsOnlyTextDeltas() {
        assertEquals(
            "你好",
            protocol.streamDelta("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}"""),
        )
        assertNull(protocol.streamDelta("""{"type":"message_start","message":{}}"""))
        assertNull(
            protocol.streamDelta(
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            ),
        )
        assertNull(protocol.streamDelta("not-json"))
    }

    @Test
    fun reconstructedBodyRebuildsATextBlockFromTheSseStream() {
        val rawSse = buildString {
            append("event: message_start\ndata: {\"type\":\"message_start\"}\n\n")
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n\n")
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"世界\"}}\n\n")
            append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }

        val reconstructed = Json.parseToJsonElement(protocol.reconstructedBody(rawSse)).jsonObject
        assertEquals("你好世界", reconstructed["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun probeBodiesUseAnthropicShapesAndTheSharedTokens() {
        val structured = Json.parseToJsonElement(
            protocol.probeRequestBody("claude-test", ModelProbeKind.STRUCTURED),
        ).jsonObject
        assertEquals(ModelProbeSpec.STRUCTURED_INSTRUCTION, structured["system"]?.jsonPrimitive?.content)
        assertNull(structured["response_format"])
        assertTrue(structured.toString().contains(ModelProbeSpec.STRUCTURED_TOKEN))

        val imageProbe = Json.parseToJsonElement(
            protocol.probeRequestBody("claude-test", ModelProbeKind.IMAGE),
        ).jsonObject
        val blocks = imageProbe["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("image", blocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(imageProbe.toString().contains(ModelProbeSpec.SYNTHETIC_IMAGE_BASE64))

        assertThrows(IllegalStateException::class.java) {
            protocol.probeRequestBody("claude-test", ModelProbeKind.TOOLS)
        }
    }

    @Test
    fun probeResponseTextReadsContentBlocks() {
        assertEquals("Q7M2", protocol.probeResponseText(envelopeOf("Q7M2")))
        assertNull(protocol.probeResponseText("{}"))
    }

    @Test
    fun realHttpRoundTripParsesTheReply() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        envelopeOf(
                            """{"questionMarkdown":"题干","choices":[{"choiceId":"A","markdown":"甲"},{"choiceId":"B","markdown":"乙"}],"correctChoiceId":"B"}""",
                        ),
                    ),
            )
            server.start()
            val requestBody = protocol.requestBody("claude-test", input, emptyList(), stream = false, enableNativeTools = false)
            val request = Request.Builder()
                .url(server.url("/v1/messages"))
                .header("x-api-key", "sk-ant-test")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            val responseBody = OkHttpClient().newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                response.body.string()
            }
            val recorded = server.takeRequest()
            assertTrue(recorded.body.readUtf8().contains("\"system\""))

            val output = protocol.parseCompletion(responseBody, input, "claude-test") as KnowledgeQuizOutput
            assertEquals("B", output.correctChoiceId)
        }
    }

    private fun envelopeOf(text: String): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                },
            )
        },
    )
}
