package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationRecoveryCursor
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationWorkSchedule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProblemOrganizationWorkSchedulingCoordinatorTest {
    @Test
    fun repeatedFlowEmissionDoesNotEnqueueTheSamePersistedVersionTwice() = runBlocking {
        val deduplicator = ProblemOrganizationScheduleDeduplicator()
        val first = schedulable("work-1", version = 1)
        val second = schedulable("work-2", version = 1)
        val enqueued = mutableListOf<ProductionProblemOrganizationWorkSchedule>()

        enqueueUnacknowledgedProblemOrganizationWorks(
            listOf(first, second),
            deduplicator,
            { schedule -> enqueued += schedule },
        )
        enqueueUnacknowledgedProblemOrganizationWorks(
            listOf(first, second),
            deduplicator,
            { schedule -> enqueued += schedule },
        )

        val advanced = first.copy(stateVersion = 2)
        enqueueUnacknowledgedProblemOrganizationWorks(
            listOf(advanced, second),
            deduplicator,
            { schedule -> enqueued += schedule },
        )

        assertEquals(listOf(first, second, advanced), enqueued)
    }

    @Test
    fun workThatLeavesAndLaterReentersTheSchedulableSetIsEnqueuedAgain() = runBlocking {
        val deduplicator = ProblemOrganizationScheduleDeduplicator()
        val snapshot = schedulable("work-1", version = 1)
        val enqueued = mutableListOf<ProductionProblemOrganizationWorkSchedule>()

        enqueueUnacknowledgedProblemOrganizationWorks(
            listOf(snapshot),
            deduplicator,
            { schedule -> enqueued += schedule },
        )
        enqueueUnacknowledgedProblemOrganizationWorks(
            emptyList(),
            deduplicator,
            { schedule -> enqueued += schedule },
        )
        enqueueUnacknowledgedProblemOrganizationWorks(
            listOf(snapshot),
            deduplicator,
            { schedule -> enqueued += schedule },
        )

        assertEquals(listOf(snapshot, snapshot), enqueued)
    }

    @Test
    fun duplicateWorkIdentityIsFailClosed() {
        val deduplicator = ProblemOrganizationScheduleDeduplicator()
        val first = schedulable("work-1", version = 1)

        assertThrows(IllegalStateException::class.java) {
            deduplicator.selectUnacknowledgedVersions(
                listOf(first, first.copy(stateVersion = 2)),
            )
        }
    }

    @Test
    fun asynchronouslyFailedEnqueueRemainsEligibleForRetry() = runBlocking {
        val deduplicator = ProblemOrganizationScheduleDeduplicator()
        val snapshot = schedulable("work-1", version = 1)
        val attempts = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                enqueueUnacknowledgedProblemOrganizationWorks(
                    listOf(snapshot),
                    deduplicator,
                ) { schedule ->
                    attempts += schedule.workId
                    yield()
                    error("asynchronous enqueue failed")
                }
            }
        }
        enqueueUnacknowledgedProblemOrganizationWorks(
            listOf(snapshot),
            deduplicator,
        ) { schedule -> attempts += schedule.workId }

        assertEquals(listOf("work-1", "work-1"), attempts)
    }

    @Test
    fun partialSuccessAcknowledgesOnlySuccessfullyPersistedItems() = runBlocking {
        val deduplicator = ProblemOrganizationScheduleDeduplicator()
        val first = schedulable("work-1", version = 1)
        val second = schedulable("work-2", version = 1)
        val successful = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                enqueueUnacknowledgedProblemOrganizationWorks(
                    listOf(first, second),
                    deduplicator,
                ) { schedule ->
                    if (schedule == second) error("second enqueue failed")
                    successful += schedule.workId
                }
            }
        }
        enqueueUnacknowledgedProblemOrganizationWorks(
            listOf(first, second),
            deduplicator,
        ) { schedule -> successful += schedule.workId }

        assertEquals(listOf("work-1", "work-2"), successful)
    }

    @Test
    fun schedulableFeedStartsOnlyAfterRunningRecoveryCompletes() = runBlocking {
        val recoveryEntered = CompletableDeferred<Unit>()
        val finishRecovery = CompletableDeferred<Unit>()
        val feedObserved = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val coordinator =
            ProblemOrganizationWorkSchedulingCoordinator(
                parentScope = this,
                observeSchedulable = {
                    flow {
                        events += "feed"
                        feedObserved.complete(Unit)
                        awaitCancellation()
                    }
                },
                readRunningRecoveryPage = {
                    events += "recovery"
                    recoveryEntered.complete(Unit)
                    finishRecovery.await()
                    emptyList()
                },
                enqueueSchedulable = {},
                enqueueRunningRecovery = {},
            )

        coordinator.start()
        recoveryEntered.await()
        yield()
        assertFalse(feedObserved.isCompleted)
        finishRecovery.complete(Unit)
        withTimeout(1_000L) { feedObserved.await() }
        coordinator.close()

        assertEquals(listOf("recovery", "feed"), events)
    }

    @Test
    fun runningRecoveryReadsEveryStableKeysetPageBeforeEnqueue() = runBlocking {
        val rows =
            (0 until 250)
                .map { index ->
                    schedulable(
                        workId = "work-${index.toString().padStart(4, '0')}",
                        version = index.toLong() + 1,
                    ).copy(
                        eligibleAtEpochMillis = 20_000L + index / 100,
                        updatedAtEpochMillis = 15_000L + index % 100,
                    )
                }
                .sortedWith { left, right ->
                    comparePublishedRecoveryCursors(
                        left.toRecoveryCursor(),
                        right.toRecoveryCursor(),
                    )
                }
        val scheduled = mutableListOf<String>()
        val cursors = mutableListOf<ProductionProblemOrganizationRecoveryCursor?>()

        recoverPublishedRunningProblemOrganizationWorks(
            pageSize = 100,
            readPage = { query ->
                cursors += query.after
                rows.asSequence()
                    .filter { row ->
                        query.after?.let { cursor ->
                            comparePublishedRecoveryCursors(row.toRecoveryCursor(), cursor) > 0
                        } != false
                    }
                    .take(query.limit)
                    .toList()
            },
            enqueue = { schedule -> scheduled += schedule.workId },
        )

        assertEquals(rows.map { it.workId }, scheduled)
        assertEquals(3, cursors.size)
    }

    @Test
    fun runningFactsLeftByCancellationIoAndTerminalFailureRecoverWithoutAProcessRestart() =
        runBlocking {
            val initialRecoveryRead = CompletableDeferred<Unit>()
            val continuousScanWaiting = CompletableDeferred<Unit>()
            val allowContinuousScan = Channel<Unit>(capacity = 1)
            val recoveredAll =
                CompletableDeferred<List<ProductionProblemOrganizationWorkSchedule>>()
            val recovered = mutableListOf<ProductionProblemOrganizationWorkSchedule>()
            var running = emptyList<ProductionProblemOrganizationWorkSchedule>()
            val coordinator =
                ProblemOrganizationWorkSchedulingCoordinator(
                    parentScope = this,
                    observeSchedulable = {
                        flow { awaitCancellation() }
                    },
                    readRunningRecoveryPage = { query ->
                        initialRecoveryRead.complete(Unit)
                        if (query.after == null) running else emptyList()
                    },
                    enqueueSchedulable = {},
                    enqueueRunningRecovery = { schedule ->
                        recovered += schedule
                        if (recovered.size == 3) recoveredAll.complete(recovered.toList())
                    },
                    awaitNextRunningRecoveryScan = {
                        continuousScanWaiting.complete(Unit)
                        allowContinuousScan.receive()
                    },
                )

            coordinator.start()
            initialRecoveryRead.await()
            continuousScanWaiting.await()
            running =
                listOf("cancelled", "io-failed", "terminal-failed").mapIndexed { index, cause ->
                    schedulable("work-$cause", version = 8L + index).copy(
                        eligibleAtEpochMillis = 42_000L + index,
                    )
                }
            allowContinuousScan.send(Unit)

            assertEquals(running, withTimeout(1_000L) { recoveredAll.await() })
            coordinator.close()
        }

    private fun schedulable(
        workId: String,
        version: Long,
    ) = ProductionProblemOrganizationWorkSchedule(
        workId = workId,
        stateVersion = version,
        eligibleAtEpochMillis = 10_000,
        updatedAtEpochMillis = 9_500,
        executionLeaseToken = version.toString(16).padStart(64, '0'),
    )
}
