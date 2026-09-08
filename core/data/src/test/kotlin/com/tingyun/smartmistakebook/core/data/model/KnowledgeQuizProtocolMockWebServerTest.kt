package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.KnowledgeQuizInput
import com.tingyun.smartmistakebook.core.model.KnowledgeQuizOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KNOWLEDGE_QUIZ 的线上往返（spec dual-review-entry §3.3/§4）：请求侧必须把讲解材料与
 * `boundaryMarkdown` 防臆造锚真正送进 chat-completions 请求体；响应侧必须经真实 HTTP
 * 解析成 [KnowledgeQuizOutput]。与只测响应解析的 `KnowledgeQuizWireTest` 互补——
 * 这里覆盖"请求构造 → MockWebServer 真实往返 → 解析"整条链。
 */
class KnowledgeQuizProtocolMockWebServerTest {

    private val input = KnowledgeQuizInput(
        knowledgeNodeId = "kc-derivative-monotonicity",
        subjectId = "MATH",
        materialTitle = "导数符号与单调性",
        materialContentMarkdown = "若 f'(x) > 0 在区间上恒成立，则 f 单调递增。",
        materialBoundaryMarkdown = "仅覆盖导数符号与单调性的关系，不含凹凸性。",
        lastMasteryScore = 0.42,
        lastEvidenceAtEpochMillis = 1_700_000_000_000L,
    )

    @Test
    fun requestBodyCarriesTheMaterialAndItsBoundaryAnchor() {
        val body = OpenAiModelProtocol.requestBody(
            modelId = "quiz-model",
            input = input,
            images = emptyList(),
        )

        assertTrue(body.contains("\"model\":\"quiz-model\""))
        assertTrue(body.contains("kc-derivative-monotonicity"))
        assertTrue(body.contains("导数符号与单调性"))
        assertTrue(body.contains("若 f'(x) > 0 在区间上恒成立，则 f 单调递增。"))
        // 防臆造锚：边界说明必须真的上 wire，模型才可能不越界出题（§4）。
        assertTrue(body.contains("仅覆盖导数符号与单调性的关系，不含凹凸性。"))
        // 输出契约：三个 wire 键 + json_object 信封。
        assertTrue(body.contains("questionMarkdown"))
        assertTrue(body.contains("correctChoiceId"))
        assertTrue(body.contains("json_object"))
        // 掌握度仅供模型调难度，仍需随请求送出。
        assertTrue(body.contains("0.42"))
        // 纯文本任务：不得夹带图片。
        assertFalse(body.contains("image_url"))
    }

    @Test
    fun realHttpRoundTripParsesTheQuizReplyIntoKnowledgeQuizOutput() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(envelope(quizPayload())),
            )
            server.start()

            val requestBody = OpenAiModelProtocol.requestBody(
                modelId = "quiz-model",
                input = input,
                images = emptyList(),
            )
            val request = Request.Builder()
                .url(server.url("/chat/completions"))
                .header("Authorization", "Bearer test-key")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val responseBody = OkHttpClient().newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                response.body.string()
            }
            // 真实往返的请求体里同样带着边界锚。
            assertTrue(
                server.takeRequest().body.readUtf8()
                    .contains("仅覆盖导数符号与单调性的关系，不含凹凸性。"),
            )

            val output = OpenAiModelProtocol.parseResponse(
                responseBody = responseBody,
                input = input,
                modelVersion = "quiz-model",
            ) as KnowledgeQuizOutput

            assertEquals("若 f'(x)>0 恒成立，则 f 在该区间上单调递增。", output.questionMarkdown)
            assertEquals(listOf("A", "B"), output.choices.map { it.choiceId })
            assertEquals("单调递增", output.choices.first().markdown)
            assertEquals("A", output.correctChoiceId)
            assertEquals("quiz-model", output.modelVersion)
        }
    }

    @Test
    fun quizReplyWhoseCorrectIdIsAbsentIsRejectedBeforeTheUiCanSeeSuccess() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiModelProtocol.parseResponse(
                responseBody = envelope(quizPayload(correctChoiceId = "D")),
                input = input,
                modelVersion = "quiz-model",
            )
        }
    }

    private fun envelope(content: String): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "message",
                                buildJsonObject {
                                    put("content", content)
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    private fun quizPayload(correctChoiceId: String = "A"): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("questionMarkdown", "若 f'(x)>0 恒成立，则 f 在该区间上单调递增。")
            put(
                "choices",
                buildJsonArray {
                    add(buildJsonObject { put("choiceId", "A"); put("markdown", "单调递增") })
                    add(buildJsonObject { put("choiceId", "B"); put("markdown", "单调递减") })
                },
            )
            put("correctChoiceId", correctChoiceId)
        },
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
