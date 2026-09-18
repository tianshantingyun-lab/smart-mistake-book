package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.data.model.wire.OpenAiChatCompletionsProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the real SSE transport path (Call.awaitBoundedSseResponse) with a hand-built
 * `text/event-stream` response. This covers the one gap the gateway unit tests cannot reach:
 * the transport layer must split the streamed deltas into [ModelHttpResponse.streamChunks] and
 * reconstruct a chat-completion body, without lowering the transport's HTTPS/loopback hardening.
 */
class OpenAiSseTransportTest {

    @Test
    fun streamedSseResponseIsSplitIntoChunksAndReconstructed() = runBlocking {
        val sseBody = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"ok\\\":\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"true}\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val call = SseDeliveringCall(
            code = 200,
            contentType = "text/event-stream",
            body = sseBody,
        )

        val response = call.awaitBoundedSseResponse(OpenAiChatCompletionsProtocol) {}

        assertEquals(200, response.statusCode)
        // The deltas must be surfaced as ordered chunks, not one coalesced blob.
        assertEquals(listOf("{\"ok\":", "true}"), response.streamChunks)
        // The reconstructed body must be a valid chat-completion envelope whose content is the
        // concatenated deltas (the payload is JSON-escaped inside the string value).
        val envelope = kotlinx.serialization.json.Json.parseToJsonElement(response.body)
            .jsonObject
        val content = envelope["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!
            .jsonPrimitive.content
        assertEquals("{\"ok\":true}", content)
    }

    @Test
    fun nonStreamingErrorResponseKeepsChunksNull() = runBlocking {
        val call = SseDeliveringCall(
            code = 500,
            contentType = "text/event-stream",
            body = "service unavailable",
        )

        val response = call.awaitBoundedSseResponse(OpenAiChatCompletionsProtocol) {}

        assertEquals(500, response.statusCode)
        assertNull(response.streamChunks)
        assertEquals("service unavailable", response.body)
    }

    @Test
    fun blankStreamedReplyIsRejectedNotSilentlyAccepted() = runBlocking {
        val call = SseDeliveringCall(
            code = 200,
            contentType = "text/event-stream",
            body = "data: [DONE]\n\n",
        )
        var thrown: Throwable? = null
        try {
            call.awaitBoundedSseResponse(OpenAiChatCompletionsProtocol) {}
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull("A stream with no content must fail closed", thrown)
        assertTrue(thrown is InvalidModelResponseException)
    }

    @Test
    fun reasoningHeavyStreamsStillSurfaceTheirAnswer() = runBlocking {
        // Reasoning models stream chain-of-thought as their own deltas, far more than the app
        // consumes: a measured call to deepseek/deepseek-v4.1-flash (image + JSON mode, no
        // max_tokens) produced a 3.2MB stream for a 395-char answer, 98.8% of it reasoning.
        // The transport reads the stream to its [DONE] terminator with no local byte budget, so
        // the size of the deltas must never decide whether the answer arrives.
        val reasoningFrame =
            "data: {\"choices\":[{\"delta\":{\"reasoning\":\"" + "思".repeat(2_000) + "\"}}]}\n\n"
        val sseBody = buildString {
            repeat(560) { append(reasoningFrame) }
            append("data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"ok\\\":true}\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        assertTrue(
            "The fixture must be far larger than the old 2MB transport cap",
            sseBody.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024,
        )
        val call = SseDeliveringCall(
            code = 200,
            contentType = "text/event-stream",
            body = sseBody,
        )

        val response = call.awaitBoundedSseResponse(OpenAiChatCompletionsProtocol) {}

        assertEquals(200, response.statusCode)
        assertEquals(listOf("{\"ok\":true}"), response.streamChunks)
    }

    @Test
    fun streamedReasoningDeltasReachTheLiveCallbackInArrivalOrder() = runBlocking {
        // Reasoning models stream their chain-of-thought as its own delta; the tutor shows it while
        // the answer is still forming, so the transport must hand every frame over as it arrives.
        val sseBody = buildString {
            append("data: {\"choices\":[{\"delta\":{\"reasoning\":\"先看\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"reasoning\":\"定义域\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"ok\\\":true}\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val seen = mutableListOf<String>()

        val response = SseDeliveringCall(code = 200, contentType = "text/event-stream", body = sseBody)
            .awaitBoundedSseResponse(
                protocol = OpenAiChatCompletionsProtocol,
                onReasoningDelta = { delta -> seen.add(delta) },
            ) {}

        assertEquals(200, response.statusCode)
        assertEquals(listOf("先看", "定义域"), seen)
        // The answer still travels the normal content path.
        assertEquals(listOf("{\"ok\":true}"), response.streamChunks)
    }

    private class SseDeliveringCall(
        private val code: Int,
        private val contentType: String,
        private val body: String,
    ) : Call {
        private val request = Request.Builder()
            .url("https://api.example.com/v1/chat/completions")
            .build()

        override fun request(): Request = request

        override fun execute(): Response = error("execute is unused")

        override fun enqueue(responseCallback: Callback) {
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_2)
                .code(code)
                .message("ok")
                .header("Content-Type", contentType)
                .body(body.toResponseBody())
                .build()
            responseCallback.onResponse(this, response)
        }

        override fun cancel() = Unit
        override fun isExecuted(): Boolean = true
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = this

        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }
}
