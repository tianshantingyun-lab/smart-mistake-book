package com.tingyun.smartmistakebook.core.data.model

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiModelTransportCancellationTest {
    @Test
    fun cancellingTheCoroutineCancelsTheHttpCall() = runBlocking {
        val call = HangingCall()
        val job = async {
            call.awaitBoundedResponse { }
        }
        delay(20)
        job.cancelAndJoin()
        assertTrue(call.canceled)
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancellingTheCoroutineCancelsTheSseCall() = runBlocking {
        val call = HangingCall()
        val job = async {
            call.awaitBoundedSseResponse { }
        }
        delay(20)
        job.cancelAndJoin()
        assertTrue(call.canceled)
        assertTrue(job.isCancelled)
    }

    private class HangingCall : Call {
        @Volatile
        var canceled: Boolean = false
        private val request = Request.Builder().url("https://example.com/chat/completions").build()

        override fun request(): Request = request

        override fun execute(): Response = error("execute is unused")

        override fun enqueue(responseCallback: Callback) {
            // Stay in-flight until cancel() is observed by the caller.
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = HangingCall()

        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }
}
