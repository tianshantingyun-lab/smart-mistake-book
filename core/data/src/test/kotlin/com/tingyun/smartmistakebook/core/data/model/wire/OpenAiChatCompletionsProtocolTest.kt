package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import com.tingyun.smartmistakebook.core.data.model.OpenAiModelProtocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatCompletionsProtocolTest {
    private val protocol = OpenAiChatCompletionsProtocol

    @Test
    fun endpointMatchesTheCurrentChatCompletionsPath() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            protocol.endpoint("https://api.example.com/v1".toHttpUrl(), "any-model", stream = false).toString(),
        )
    }

    @Test
    fun headersMatchTheCurrentBearerAndAcceptContract() {
        val headers = protocol.headers("sk-test".toCharArray(), stream = false)
        assertEquals("Bearer sk-test", headers.single { it.first == "Authorization" }.second)
        assertEquals("application/json; charset=utf-8", headers.single { it.first == "Accept" }.second)
        assertEquals(
            "text/event-stream",
            protocol.headers("sk-test".toCharArray(), stream = true).single { it.first == "Accept" }.second,
        )
    }

    @Test
    fun requestBodyIsByteIdenticalToTheCurrentProtocol() {
        val input = CaptureAssessmentInput(
            draftId = "draft-1",
            sourceAssetId = "asset-1",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 100,
            imageHeight = 200,
        )
        assertEquals(
            OpenAiModelProtocol.requestBody("test-model", input, emptyList(), stream = false, enableNativeTools = false),
            protocol.requestBody("test-model", input, emptyList(), stream = false, enableNativeTools = false),
        )
    }

    @Test
    fun parseCompletionMatchesTheCurrentProtocol() {
        val input = CaptureAssessmentInput(
            draftId = "draft-1",
            sourceAssetId = "asset-1",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 100,
            imageHeight = 200,
        )
        val body = """{"choices":[{"message":{"content":"{\"decision\":\"PASS\",\"issues\":[],\"suggestedActions\":[]}"}}]}"""
        assertEquals(
            OpenAiModelProtocol.parseResponse(body, input, "test-model"),
            protocol.parseCompletion(body, input, "test-model"),
        )
    }

    @Test
    fun protocolIsTheDefaultAndKeepsNativeTools() {
        assertEquals(ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS, protocol.protocol)
        assertTrue(protocol.supportsNativeTools)
        assertTrue(protocol.supportsJsonObjectEnvelope)
    }
}
