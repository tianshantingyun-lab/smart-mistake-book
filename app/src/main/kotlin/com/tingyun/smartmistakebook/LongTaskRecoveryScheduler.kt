package com.tingyun.smartmistakebook

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Application-level recovery scheduler.
 *
 * Each registered unit owns one durable long-task family (problem organization, review reminders,
 * batch import, model task recovery, ...). Units run in registration order, failures are isolated,
 * and startup may safely call [start] more than once.
 */
internal class LongTaskRecoveryScheduler(
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val recoveries = linkedMapOf<String, suspend () -> Unit>()

    fun register(
        name: String,
        recovery: suspend () -> Unit,
    ) {
        require(name.isNotBlank()) { "Recovery unit name must not be blank" }
        require(recoveries.putIfAbsent(name, recovery) == null) {
            "Recovery unit was registered more than once: $name"
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            runRecoveries()
        }
    }

    internal suspend fun runRecoveries() {
        recoveries.values.forEach { recovery ->
            try {
                recovery()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // One failed recovery must not prevent later long-task families from starting.
            }
        }
    }
}
