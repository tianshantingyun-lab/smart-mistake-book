package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationRecoveryQuery
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationRecoveryCursor
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationWorkSchedule
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bridges persisted organization work to WorkManager after the production authority is published.
 *
 * This coordinator owns no database and exposes no mutation capability. The authority supplies a
 * learner-bound read feed; WorkManager remains only a durable execution mechanism.
 */
internal class ProblemOrganizationWorkSchedulingCoordinator(
    parentScope: CoroutineScope,
    private val observeSchedulable:
        () -> Flow<List<ProductionProblemOrganizationWorkSchedule>>,
    private val readRunningRecoveryPage:
        suspend (ProductionProblemOrganizationRecoveryQuery) ->
            List<ProductionProblemOrganizationWorkSchedule>,
    private val enqueueSchedulable: suspend (ProductionProblemOrganizationWorkSchedule) -> Unit,
    private val enqueueRunningRecovery:
        suspend (ProductionProblemOrganizationWorkSchedule) -> Unit,
    private val awaitNextRunningRecoveryScan: suspend () -> Unit = {
        delay(RUNNING_RECOVERY_SCAN_INTERVAL_MILLIS)
    },
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val childJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope =
        CoroutineScope(parentScope.coroutineContext.minusKey(Job) + childJob)
    private val deduplicator = ProblemOrganizationScheduleDeduplicator()

    fun start() {
        check(!closed.get()) { "Problem organization scheduling coordinator is closed" }
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            retryUntilSuccessful {
                recoverPublishedRunningProblemOrganizationWorks(
                    pageSize = RECOVERY_PAGE_SIZE,
                    readPage = readRunningRecoveryPage,
                    enqueue = enqueueRunningRecovery,
                )
            }
            launch { collectSchedulableAfterRecovery() }
            continuouslyRecoverRunningWork()
        }
    }

    private suspend fun continuouslyRecoverRunningWork() {
        while (currentCoroutineContext().isActive) {
            awaitNextRunningRecoveryScan()
            try {
                recoverPublishedRunningProblemOrganizationWorks(
                    pageSize = RECOVERY_PAGE_SIZE,
                    readPage = readRunningRecoveryPage,
                    enqueue = enqueueRunningRecovery,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The next reconciliation pass rereads the durable RUNNING facts.
            }
        }
    }

    private suspend fun collectSchedulableAfterRecovery() {
        while (currentCoroutineContext().isActive) {
            try {
                observeSchedulable().collect { snapshots ->
                    enqueueUnacknowledgedProblemOrganizationWorks(
                        snapshots = snapshots,
                        deduplicator = deduplicator,
                        enqueue = enqueueSchedulable,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The persisted feed remains authoritative; retry without fabricating state.
            }
            delay(RETRY_DELAY_MILLIS)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
    }

    private companion object {
        const val RECOVERY_PAGE_SIZE = 100
        const val RETRY_DELAY_MILLIS = 1_000L
        const val RUNNING_RECOVERY_SCAN_INTERVAL_MILLIS = 60_000L
    }

    private suspend fun retryUntilSuccessful(block: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            try {
                block()
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                delay(RETRY_DELAY_MILLIS)
            }
        }
    }
}

/** Suppresses only versions whose WorkManager enqueue operation was durably acknowledged. */
internal class ProblemOrganizationScheduleDeduplicator {
    private val acknowledgedVisibleVersions = LinkedHashMap<String, Long>()

    @Synchronized
    fun selectUnacknowledgedVersions(
        snapshots: List<ProductionProblemOrganizationWorkSchedule>,
    ): List<ProductionProblemOrganizationWorkSchedule> {
        val currentVersions = LinkedHashMap<String, Long>(snapshots.size)
        snapshots.forEach { snapshot ->
            check(currentVersions.put(snapshot.workId, snapshot.stateVersion) == null) {
                "Schedulable organization work contains a duplicate work id"
            }
        }
        acknowledgedVisibleVersions.keys.retainAll(currentVersions.keys)
        return snapshots.filter { snapshot ->
            acknowledgedVisibleVersions[snapshot.workId] != snapshot.stateVersion
        }
    }

    @Synchronized
    fun acknowledge(schedule: ProductionProblemOrganizationWorkSchedule) {
        acknowledgedVisibleVersions[schedule.workId] = schedule.stateVersion
    }
}

internal suspend fun enqueueUnacknowledgedProblemOrganizationWorks(
    snapshots: List<ProductionProblemOrganizationWorkSchedule>,
    deduplicator: ProblemOrganizationScheduleDeduplicator,
    enqueue: suspend (ProductionProblemOrganizationWorkSchedule) -> Unit,
) {
    for (schedule in deduplicator.selectUnacknowledgedVersions(snapshots)) {
        enqueue(schedule)
        deduplicator.acknowledge(schedule)
    }
}

internal suspend fun recoverPublishedRunningProblemOrganizationWorks(
    pageSize: Int,
    readPage: suspend (
        ProductionProblemOrganizationRecoveryQuery,
    ) -> List<ProductionProblemOrganizationWorkSchedule>,
    enqueue: suspend (ProductionProblemOrganizationWorkSchedule) -> Unit,
) {
    require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
    var cursor: ProductionProblemOrganizationRecoveryCursor? = null
    val recoveredStates =
        linkedMapOf<Pair<String, Long>, ProductionProblemOrganizationWorkSchedule>()
    do {
        val page =
            readPage(
                ProductionProblemOrganizationRecoveryQuery(
                    limit = pageSize,
                    after = cursor,
                ),
            )
        check(page.size <= pageSize) { "Running recovery page exceeds its requested limit" }
        page.forEach { schedule ->
            val nextCursor = schedule.toRecoveryCursor()
            check(
                cursor?.let { previous ->
                    comparePublishedRecoveryCursors(nextCursor, previous) > 0
                } != false,
            ) { "Running recovery pages must use stable keyset order" }
            recoveredStates.putIfAbsent(
                schedule.workId to schedule.stateVersion,
                schedule,
            )
            cursor = nextCursor
        }
    } while (page.size == pageSize)
    for (schedule in recoveredStates.values) {
        enqueue(schedule)
    }
}

internal fun ProductionProblemOrganizationWorkSchedule.toRecoveryCursor() =
    ProductionProblemOrganizationRecoveryCursor(
        eligibleAtEpochMillis = eligibleAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        workId = workId,
    )

internal fun comparePublishedRecoveryCursors(
    left: ProductionProblemOrganizationRecoveryCursor,
    right: ProductionProblemOrganizationRecoveryCursor,
): Int =
    compareValuesBy(
        left,
        right,
        ProductionProblemOrganizationRecoveryCursor::eligibleAtEpochMillis,
        ProductionProblemOrganizationRecoveryCursor::updatedAtEpochMillis,
        ProductionProblemOrganizationRecoveryCursor::workId,
    )
