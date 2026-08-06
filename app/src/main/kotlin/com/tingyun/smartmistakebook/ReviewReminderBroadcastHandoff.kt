package com.tingyun.smartmistakebook

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface ReviewReminderBroadcastHandler {
    suspend fun handle(action: String)
}

internal interface ReviewReminderPendingBroadcastStore {
    fun enqueue(action: String): Boolean
    fun next(): String?
    fun acknowledge(action: String): Boolean
}

internal class SharedPreferencesReviewReminderPendingBroadcastStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) : ReviewReminderPendingBroadcastStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            preferencesName,
            Context.MODE_PRIVATE,
        )

    @Synchronized
    override fun enqueue(action: String): Boolean {
        require(ReviewReminderContract.acceptsBroadcastAction(action))
        val pending = readPendingActions().toMutableSet()
        if (!pending.add(action)) return true
        return writePendingActions(pending)
    }

    @Synchronized
    override fun next(): String? =
        ReviewReminderContract.broadcastActionOrder
            .firstOrNull(readPendingActions()::contains)

    @Synchronized
    override fun acknowledge(action: String): Boolean {
        val pending = readPendingActions().toMutableSet()
        if (!pending.remove(action)) return true
        return writePendingActions(pending)
    }

    private fun readPendingActions(): Set<String> =
        preferences.getStringSet(PENDING_ACTIONS_KEY, emptySet())
            .orEmpty()
            .filterTo(mutableSetOf()) { action ->
                ReviewReminderContract.acceptsBroadcastAction(action)
            }

    private fun writePendingActions(actions: Set<String>): Boolean =
        preferences.edit()
            .putStringSet(PENDING_ACTIONS_KEY, actions.toSet())
            .commit()

    private companion object {
        const val PREFERENCES_NAME = "review_reminder_broadcast_handoff"
        const val PENDING_ACTIONS_KEY = "pending_actions"
    }
}

internal class ReviewReminderBroadcastHandoff(
    private val store: ReviewReminderPendingBroadcastStore,
    private val scope: CoroutineScope,
    private val handlerProvider: () -> ReviewReminderBroadcastHandler?,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val drainMutex = Mutex()

    fun accept(action: String, onFinished: () -> Unit) {
        require(ReviewReminderContract.acceptsBroadcastAction(action))
        scope.launch(workerDispatcher) {
            val persisted =
                try {
                    store.enqueue(action)
                } catch (_: Exception) {
                    false
                }
            try {
                onFinished()
            } catch (_: Exception) {
                // The system callback is terminal; reminder work must not invoke it twice.
            }
            if (persisted) requestDrain()
        }
    }

    fun onHandlerReady() {
        requestDrain()
    }

    private fun requestDrain() {
        scope.launch(workerDispatcher) {
            drainAvailable()
        }
    }

    private suspend fun drainAvailable() {
        drainMutex.withLock {
            while (true) {
                val handler = handlerProvider() ?: return
                val action = store.next() ?: return
                try {
                    handler.handle(action)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return
                }
                if (!store.acknowledge(action)) return
            }
        }
    }
}
