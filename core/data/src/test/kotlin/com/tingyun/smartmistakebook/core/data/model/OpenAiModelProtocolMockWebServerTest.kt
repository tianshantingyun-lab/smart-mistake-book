package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiModelProtocolMockWebServerTest {
    @Test
    fun mockWebServerReceivesAuthorizedImageInsideChatCompletionsBody() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(envelope(assessmentPayload())),
            )
            server.start()

            val imageBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val requestBody = OpenAiModelProtocol.requestBody(
                modelId = "test-model",
                input = CaptureAssessmentInput(
                    draftId = "draft-1",
                    sourceAssetId = "asset-1",
                    origin = CaptureAssessmentOrigin.LIBRARY,
                    imageWidth = 100,
                    imageHeight = 200,
                ),
                images = listOf(ApprovedImage("image/jpeg", imageBytes)),
            )
            val request = Request.Builder()
                .url(server.url("/chat/completions"))
                .header("Authorization", "Bearer test-key")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            OkHttpClient().newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }
            val recorded = server.takeRequest()
            val sentBody = recorded.body.readUtf8()

            assertTrue(sentBody.contains("\"model\":\"test-model\""))
            assertTrue(sentBody.contains("\"type\":\"image_url\""))
            assertTrue(sentBody.contains("data:image/jpeg;base64,${imageBytes.toByteString().base64()}"))
            assertFalseContainsDraftOrAssetId(sentBody)
        }
    }

    @Test
    fun malformedMockWebServerResponseIsRejectedBeforeUiCanSeeSuccess() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )
            server.start()
            val request = Request.Builder()
                .url(server.url("/chat/completions"))
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val body = OkHttpClient().newCall(request).execute().use { it.body.string() }

            assertThrows(InvalidModelResponseException::class.java) {
                OpenAiModelProtocol.parseResponse(
                    responseBody = body,
                    input = CaptureAssessmentInput(
                        draftId = "draft-1",
                        sourceAssetId = "asset-1",
                        origin = CaptureAssessmentOrigin.LIBRARY,
                        imageWidth = 100,
                        imageHeight = 200,
                    ),
                    modelVersion = "test-model",
                )
            }
        }
    }

    @Test
    fun parsedAssessmentKeepsExactDecisionFromMockWebServerBody() {
        val output = OpenAiModelProtocol.parseResponse(
            responseBody = envelope(assessmentPayload()),
            input = CaptureAssessmentInput(
                draftId = "draft-1",
                sourceAssetId = "asset-1",
                origin = CaptureAssessmentOrigin.LIBRARY,
                imageWidth = 100,
                imageHeight = 200,
            ),
            modelVersion = "test-model",
        ) as CaptureAssessmentOutput

        assertEquals(CaptureAssessmentDecision.PASS, output.assessment.decision)
    }

    private fun assertFalseContainsDraftOrAssetId(body: String) {
        assertTrue("draft-1" !in body)
        assertTrue("asset-1" !in body)
    }

    private fun envelope(content: String): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
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

    private fun assessmentPayload(): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("decision", "PASS")
            put("issues", buildJsonArray {})
            put("suggestedActions", buildJsonArray {})
        },
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
