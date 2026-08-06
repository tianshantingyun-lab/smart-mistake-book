package com.tingyun.smartmistakebook.core.database

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Database-facing work used by the expiry scheduler. Only an absolute cutoff crosses this boundary;
 * answer bytes, action tokens, and outbox rows never enter the scheduler.
 */
internal interface TutorFreeResponseOutboxExpiryTask {
    suspend fun nextPendingCutoffEpochMillis(): Long?

    suspend fun wipePendingOutboxesThrough(cutoffEpochMillis: Long)
}

internal fun interface TutorFreeResponseOutboxExpirySchedulerFactory {
    fun create(
        clock: () -> Long,
        task: TutorFreeResponseOutboxExpiryTask,
    ): TutorFreeResponseOutboxExpiryScheduler
}

internal interface TutorFreeResponseOutboxExpiryScheduler {
    /** Conflated: callers never queue one worker per mutation. */
    fun requestRefresh()

    /** Returns only after the worker can no longer touch the database-facing task. */
    fun closeAndJoin()
}

internal object CoroutineTutorFreeResponseOutboxExpirySchedulerFactory :
    TutorFreeResponseOutboxExpirySchedulerFactory {
    override fun create(
        clock: () -> Long,
        task: TutorFreeResponseOutboxExpiryTask,
    ): TutorFreeResponseOutboxExpiryScheduler =
        CoroutineTutorFreeResponseOutboxExpiryScheduler(clock, task)
}

private class CoroutineTutorFreeResponseOutboxExpiryScheduler(
    private val clock: () -> Long,
    private val task: TutorFreeResponseOutboxExpiryTask,
) : TutorFreeResponseOutboxExpiryScheduler {
    private val closed = AtomicBoolean(false)
    private val refreshes = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = scope.launch { runScheduler() }

    override fun requestRefresh() {
        if (!closed.get()) refreshes.trySend(Unit)
    }

    override fun closeAndJoin() {
        if (!closed.compareAndSet(false, true)) return
        runBlocking {
            worker.cancelAndJoin()
        }
        refreshes.close()
        scope.cancel()
    }

    private suspend fun runScheduler() {
        var arm: ExpiryArm? = null
        while (currentCoroutineContext().isActive) {
            if (arm == null) {
                refreshes.receiveCatching().getOrNull() ?: return
                arm = loadArm()
                if (arm == null) continue
            }

            val waitStarted = TimeSource.Monotonic.markNow()
            val refreshed = if (arm.remainingDelayMillis == 0L) {
                false
            } else {
                withTimeoutOrNull(arm.remainingDelayMillis) {
                    refreshes.receiveCatching().getOrNull() != null
                } == true
            }

            if (refreshed) {
                val elapsedMillis = waitStarted.elapsedNow().inWholeMilliseconds
                val remainingDelay = max(0L, arm.remainingDelayMillis - elapsedMillis)
                val observation = loadCutoffObservation() ?: run {
                    arm = null
                    continue
                }
                arm = if (observation.cutoffEpochMillis == arm.cutoffEpochMillis) {
                    // A wall-clock rollback cannot extend an already captured deadline.
                    arm.copy(remainingDelayMillis = remainingDelay)
                } else {
                    newArm(observation)
                }
                continue
            }

            val firedCutoff = arm.cutoffEpochMillis
            if (!wipeWithBackoff(firedCutoff)) {
                arm = arm.copy(remainingDelayMillis = EXPIRY_FAILURE_RETRY_DELAY_MILLIS)
                continue
            }
            val observation = loadCutoffObservation()
            arm = when {
                observation == null -> null
                observation.cutoffEpochMillis <= firedCutoff ->
                    // A broken/failing fake or database task must not create a zero-delay loop.
                    ExpiryArm(
                        observation.cutoffEpochMillis,
                        EXPIRY_FAILURE_RETRY_DELAY_MILLIS,
                    )
                else -> newArm(observation)
            }
        }
    }

    private suspend fun loadArm(): ExpiryArm? = loadCutoffObservation()?.let(::newArm)

    private suspend fun loadCutoffObservation(): CutoffObservation? {
        // Capture wall time before the database suspension point so rollback during the query cannot
        // lengthen the resulting monotonic wait.
        val observedAtEpochMillis = clock().coerceAtLeast(0L)
        val cutoff = loadNextCutoffWithBackoff() ?: return null
        return CutoffObservation(cutoff, observedAtEpochMillis)
    }

    private suspend fun loadNextCutoffWithBackoff(): Long? {
        while (currentCoroutineContext().isActive) {
            try {
                return task.nextPendingCutoffEpochMillis()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                delay(EXPIRY_FAILURE_RETRY_DELAY_MILLIS)
            }
        }
        return null
    }

    private suspend fun wipeWithBackoff(cutoffEpochMillis: Long): Boolean = try {
        task.wipePendingOutboxesThrough(cutoffEpochMillis)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun newArm(observation: CutoffObservation): ExpiryArm {
        val cutoffEpochMillis = observation.cutoffEpochMillis
        val now = observation.observedAtEpochMillis
        val remaining = when {
            cutoffEpochMillis <= now -> 0L
            cutoffEpochMillis - now < 0L -> Long.MAX_VALUE
            else -> cutoffEpochMillis - now
        }
        return ExpiryArm(cutoffEpochMillis, remaining)
    }

    private data class ExpiryArm(
        val cutoffEpochMillis: Long,
        val remainingDelayMillis: Long,
    )

    private data class CutoffObservation(
        val cutoffEpochMillis: Long,
        val observedAtEpochMillis: Long,
    )
}

private const val EXPIRY_FAILURE_RETRY_DELAY_MILLIS = 1_000L
