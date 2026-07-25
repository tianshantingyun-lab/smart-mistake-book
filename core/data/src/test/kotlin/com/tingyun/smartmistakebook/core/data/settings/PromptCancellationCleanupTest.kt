package com.tingyun.smartmistakebook.core.data.settings

import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PromptCancellationCleanupTest {
    @Test
    fun `closeable created on dispatcher is closed when result delivery is cancelled`() = runBlocking {
        val dispatcher = ManualDispatcher()
        val key = ModelApiKey.from("cancelled-secret".toCharArray())
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val result = withContextClosingDiscarded<SecretVaultReadResult>(
                dispatcher = dispatcher,
                block = { SecretVaultReadResult.Available(key) },
                closeDiscarded = { discarded ->
                    if (discarded is SecretVaultReadResult.Available) discarded.apiKey.close()
                },
            )
            if (result is SecretVaultReadResult.Available) result.apiKey.close()
        }

        assertEquals(1, dispatcher.pendingCount)
        thread(name = "credential-cancellation-test") { dispatcher.runNext() }.join()
        job.cancelAndJoin()

        try {
            key.copyChars()
            fail("Prompt-cancelled key was not closed")
        } catch (_: IllegalStateException) {
            // Expected: the result was created but never delivered to its caller.
        }
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()

        val pendingCount: Int
            get() = queue.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun runNext() = queue.take().run()
    }
}
