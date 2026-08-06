package com.tingyun.smartmistakebook

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewReminderBroadcastHandoffTest {
    @Test
    fun `broadcasts wait durably for the production handler and drain once when ready`() {
        val store = InMemoryPendingBroadcastStore()
        val handled = mutableListOf<String>()
        var handler: ReviewReminderBroadcastHandler? = null
        var finishedCount = 0
        val handoff = handoff(store) { handler }

        repeat(2) {
            handoff.accept(ReviewReminderContract.ACTION_DAILY_REMINDER) {
                finishedCount += 1
            }
        }

        assertEquals(2, finishedCount)
        assertEquals(ReviewReminderContract.ACTION_DAILY_REMINDER, store.next())
        assertEquals(emptyList<String>(), handled)

        handler = ReviewReminderBroadcastHandler { handled += it }
        handoff.onHandlerReady()
        handoff.onHandlerReady()

        assertEquals(listOf(ReviewReminderContract.ACTION_DAILY_REMINDER), handled)
        assertNull(store.next())
    }

    @Test
    fun `a recreated handoff drains work persisted by the previous process`() {
        val store = InMemoryPendingBroadcastStore()
        var firstFinished = false
        handoff(store) { null }.accept(Intent.ACTION_TIMEZONE_CHANGED) {
            firstFinished = true
        }
        assertEquals(true, firstFinished)

        val handled = mutableListOf<String>()
        handoff(store) { ReviewReminderBroadcastHandler { handled += it } }
            .onHandlerReady()

        assertEquals(listOf(Intent.ACTION_TIMEZONE_CHANGED), handled)
        assertNull(store.next())
    }

    @Test
    fun `failed handling stays pending and can be retried without early acknowledgement`() {
        val store = InMemoryPendingBroadcastStore().apply {
            enqueue(Intent.ACTION_BOOT_COMPLETED)
        }
        var handler: ReviewReminderBroadcastHandler? =
            ReviewReminderBroadcastHandler { error("temporary failure") }
        val handoff = handoff(store) { handler }

        handoff.onHandlerReady()
        assertEquals(Intent.ACTION_BOOT_COMPLETED, store.next())

        val handled = mutableListOf<String>()
        handler = ReviewReminderBroadcastHandler { handled += it }
        handoff.onHandlerReady()

        assertEquals(listOf(Intent.ACTION_BOOT_COMPLETED), handled)
        assertNull(store.next())
    }

    private fun handoff(
        store: ReviewReminderPendingBroadcastStore,
        handlerProvider: () -> ReviewReminderBroadcastHandler?,
    ) = ReviewReminderBroadcastHandoff(
        store = store,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        handlerProvider = handlerProvider,
        workerDispatcher = Dispatchers.Unconfined,
    )

    private class InMemoryPendingBroadcastStore : ReviewReminderPendingBroadcastStore {
        private val pending = linkedSetOf<String>()

        override fun enqueue(action: String): Boolean {
            pending += action
            return true
        }

        override fun next(): String? = pending.firstOrNull()

        override fun acknowledge(action: String): Boolean {
            pending -= action
            return true
        }
    }
}
