package com.tingyun.smartmistakebook.core.data.model

import java.io.IOException
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingModelHttpTransportTest {
    @Test
    fun eventStreamResponseEmitsDecodedData() = runBlocking {
        val call = FakeCall(
            response = response(
                code = 200,
                contentType = "text/event-stream",
                body = "data: one\n\ndata: two\n\ndata: [DONE]\n\n",
            ),
        )

        val events = call.streamBoundedResponse(beforeEnqueue = {}, maxBytes = 1_024).toList()

        assertEquals(
            listOf(
                ModelHttpStreamEvent.Data("one"),
                ModelHttpStreamEvent.Data("two"),
            ),
            events,
        )
    }

    @Test
    fun nonEventStreamResponseUsesFallbackContract() = runBlocking {
        val call = FakeCall(
            response = response(
                code = 200,
                contentType = "application/json",
                body = """{"choices":[]}""",
            ),
        )

        val events = call.streamBoundedResponse(beforeEnqueue = {}, maxBytes = 1_024).toList()

        assertEquals(
            listOf(ModelHttpStreamEvent.Fallback(ModelHttpResponse(200, """{"choices":[]}"""))),
            events,
        )
    }

    @Test
    fun httpErrorUsesFallbackContractWithoutDecodingSse() = runBlocking {
        val call = FakeCall(
            response = response(
                code = 429,
                contentType = "text/event-stream",
                body = "rate limited",
            ),
        )

        val events = call.streamBoundedResponse(beforeEnqueue = {}, maxBytes = 1_024).toList()

        assertEquals(
            listOf(ModelHttpStreamEvent.Fallback(ModelHttpResponse(429, "rate limited"))),
            events,
        )
    }

    @Test
    fun collectorCancellationCancelsCall() = runBlocking {
        val call = FakeCall(response = null)
        val collection = launch {
            call.streamBoundedResponse(beforeEnqueue = {}, maxBytes = 1_024).toList()
        }
        call.enqueued.await()

        collection.cancelAndJoin()

        assertTrue(call.cancelled)
    }

    @Test
    fun slowCollectorReceivesMoreThanChannelCapacity() = runBlocking {
        val expected = (1..128).map(Int::toString)
        val body = buildString {
            expected.forEach { value -> append("data: $value\n\n") }
            append("data: [DONE]\n\n")
        }
        val call = FakeCall(
            response = response(
                code = 200,
                contentType = "text/event-stream",
                body = body,
            ),
        )

        val actual = call.streamBoundedResponse(beforeEnqueue = {}, maxBytes = 8_192)
            .onEach { delay(1) }
            .toList()
            .map { event -> (event as ModelHttpStreamEvent.Data).value }

        assertEquals(expected, actual)
    }

    private fun response(
        code: Int,
        contentType: String,
        body: String,
    ): Response = Response.Builder()
        .request(REQUEST)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody(contentType.toMediaType()))
        .build()

    private class FakeCall(
        private val response: Response?,
    ) : Call {
        val enqueued = CompletableDeferred<Unit>()
        var cancelled = false
            private set
        private var executed = false

        override fun request(): Request = REQUEST

        override fun execute(): Response = error("Synchronous execution is not used")

        override fun enqueue(responseCallback: Callback) {
            executed = true
            enqueued.complete(Unit)
            response?.let { responseCallback.onResponse(this, it) }
        }

        override fun cancel() {
            cancelled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = cancelled

        override fun timeout(): Timeout = Timeout.NONE

        override fun addEventListener(eventListener: EventListener) = Unit

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(
            type: KClass<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()

        override fun <T : Any> tag(
            type: Class<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()

        override fun clone(): Call = FakeCall(response)
    }

    private companion object {
        val REQUEST = Request.Builder()
            .url("https://api.example.com/v1/chat/completions")
            .build()
    }
}
