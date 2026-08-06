package com.tingyun.smartmistakebook

import androidx.work.ListenableWorker
import androidx.work.NetworkType
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationRecoveryCursor
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationWorkSchedule
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemOrganizationWorkSchedulerTest {
    @Test
    fun unknownWorkerClassIsNotHandledByTheCustomFactory() {
        assertFalse(ProblemOrganizationWorkerFactory.supports("unknown.Worker"))
    }

    @Test
    fun missingWorkIdReturnsFailure() {
        assertEquals(
            ListenableWorker.Result.failure(),
            ProblemOrganizationWorker.missingWorkIdResult(),
        )
    }

    @Test
    fun malformedLeaseFailsButUnknownOrRevokedLeaseIsTerminallyDiscarded() {
        assertEquals(
            ListenableWorker.Result.failure(),
            ProblemOrganizationWorker.invalidExecutionLeaseResult(),
        )
        assertEquals(
            ListenableWorker.Result.success(),
            ProblemOrganizationWorker.staleExecutionLeaseResult(),
        )
        assertEquals(
            ListenableWorker.Result.retry(),
            ProblemOrganizationWorker.executionFailureResult(IOException("temporary")),
        )
        assertEquals(
            ListenableWorker.Result.failure(),
            ProblemOrganizationWorker.executionFailureResult(IllegalStateException("bug")),
        )
    }

    @Test
    fun publishedAuthorityThatIsStillStartingLeavesReschedulingToThePersistedFeed() {
        assertEquals(
            ListenableWorker.Result.success(),
            ProblemOrganizationWorker.unavailableCapabilityResult(),
        )
    }

    @Test
    fun delayUsesPersistentNotBeforeInsteadOfRetryBackoff() {
        assertEquals(
            45_000L,
            ProblemOrganizationWorkScheduler.initialDelayMillis(
                notBeforeEpochMillis = 145_000L,
                nowEpochMillis = 100_000L,
            ),
        )
        assertEquals(
            0L,
            ProblemOrganizationWorkScheduler.initialDelayMillis(
                notBeforeEpochMillis = 99_999L,
                nowEpochMillis = 100_000L,
            ),
        )
    }

    @Test
    fun organizationWorkWaitsForNetworkWithoutConsumingModelDispatchBudget() {
        assertEquals(
            NetworkType.CONNECTED,
            ProblemOrganizationWorkScheduler.networkConstraints().requiredNetworkType,
        )
    }

    @Test
    fun workManagerIdentityIncludesExactOccurrenceAndOwnerLease() {
        val schedule = runningRecord(index = 1)
        assertEquals(
            "problem-organization:${schedule.workId}:${schedule.stateVersion}:" +
                schedule.executionLeaseToken,
            ProblemOrganizationWorkScheduler.uniqueWorkName(schedule),
        )
        assertEquals(
            "problem-organization-recovery:${schedule.workId}:${schedule.stateVersion}:" +
                schedule.executionLeaseToken,
            ProblemOrganizationWorkScheduler.recoveryWorkName(
                schedule.workId,
                schedule.stateVersion,
                schedule.executionLeaseToken,
            ),
        )
        assertFalse(
            ProblemOrganizationWorkScheduler.uniqueWorkName(schedule) ==
                ProblemOrganizationWorkScheduler.uniqueWorkName(
                    schedule.copy(executionLeaseToken = "f".repeat(64)),
                ),
        )
    }

    @Test
    fun runningRecoveryCursorUsesThePersistedLeaseExpiry() {
        assertEquals(
            145_000L,
            runningRecord(index = 1)
                .copy(eligibleAtEpochMillis = 145_000L)
                .toRecoveryCursor()
                .eligibleAtEpochMillis,
        )
    }

    @Test
    fun startupRecoveryPagesEveryRunningStateVersionAtItsOwnExpiry() = runBlocking {
        val running = (0 until 500)
            .map(::runningRecord)
            .sortedWith { left, right -> compareRecoverySnapshots(left, right) }
        val scheduled = mutableListOf<Pair<String, Long>>()
        val requestedCursors = mutableListOf<ProductionProblemOrganizationRecoveryCursor?>()

        recoverPublishedRunningProblemOrganizationWorks(
            pageSize = 100,
            readPage = { query ->
                val cursor = query.after
                requestedCursors += cursor
                running
                    .asSequence()
                    .filter { snapshot ->
                        cursor?.let {
                            comparePublishedRecoveryCursors(snapshot.toRecoveryCursor(), it) > 0
                        } != false
                    }
                    .take(query.limit)
                    .toList()
            },
            enqueue = { snapshot ->
                scheduled +=
                    ProblemOrganizationWorkScheduler.recoveryWorkName(
                        snapshot.workId,
                        snapshot.stateVersion,
                        snapshot.executionLeaseToken,
                    ) to snapshot.eligibleAtEpochMillis
            },
        )

        val expected = running.map { snapshot ->
            ProblemOrganizationWorkScheduler.recoveryWorkName(
                snapshot.workId,
                snapshot.stateVersion,
                snapshot.executionLeaseToken,
            ) to snapshot.eligibleAtEpochMillis
        }
        assertEquals(expected, scheduled)
        assertEquals(500, scheduled.distinct().size)
        assertEquals(6, requestedCursors.size)
        val pageCursors = requestedCursors.filterNotNull()
        assertEquals(
            running.filterIndexed { index, _ -> index % 100 == 99 }
                .map(ProductionProblemOrganizationWorkSchedule::toRecoveryCursor),
            pageCursors,
        )
        assertEquals(pageCursors[0].eligibleAtEpochMillis, pageCursors[1].eligibleAtEpochMillis)
        assertTrue(pageCursors[0].updatedAtEpochMillis < pageCursors[1].updatedAtEpochMillis)
        assertEquals(pageCursors[1].eligibleAtEpochMillis, pageCursors[2].eligibleAtEpochMillis)
        assertEquals(pageCursors[1].updatedAtEpochMillis, pageCursors[2].updatedAtEpochMillis)
        assertTrue(pageCursors[1].workId < pageCursors[2].workId)
        assertTrue(pageCursors[2].eligibleAtEpochMillis < pageCursors[3].eligibleAtEpochMillis)
        assertTrue(scheduled.map { it.second }.distinct().size > 1)
    }

    @Test
    fun startupRecoveryDoesNotScheduleTheSameStateTwiceIfItsCursorMoves() = runBlocking {
        val first = runningRecord(1)
        val moved = first.copy(updatedAtEpochMillis = first.updatedAtEpochMillis + 1)
        val rows = listOf(first, moved)
        val scheduled = mutableListOf<String>()

        recoverPublishedRunningProblemOrganizationWorks(
            pageSize = 1,
            readPage = { query ->
                val cursor = query.after
                rows.asSequence()
                    .filter {
                        cursor == null ||
                            comparePublishedRecoveryCursors(it.toRecoveryCursor(), cursor) > 0
                    }
                    .take(query.limit)
                    .toList()
            },
            enqueue = { scheduled += it.workId },
        )

        assertEquals(listOf(first.workId), scheduled)
    }

    @Test
    fun startupRecoveryFinishesItsKeysetSnapshotBeforeEnqueueCanMoveRows() = runBlocking {
        val rows = (0 until 250)
            .map(::runningRecord)
            .sortedWith { left, right -> compareRecoverySnapshots(left, right) }
            .toMutableList()
        val scheduled = mutableListOf<String>()
        var allPagesRead = false

        recoverPublishedRunningProblemOrganizationWorks(
            pageSize = 100,
            readPage = { query ->
                val cursor = query.after
                rows.asSequence()
                    .filter {
                        cursor == null ||
                            comparePublishedRecoveryCursors(it.toRecoveryCursor(), cursor) > 0
                    }
                    .take(query.limit)
                    .toList()
                    .also { page -> allPagesRead = page.size < query.limit }
            },
            enqueue = { snapshot ->
                assertTrue("Enqueue started before the paging snapshot completed", allPagesRead)
                scheduled += snapshot.workId
                val index = rows.indexOfFirst { it.workId == snapshot.workId }
                rows[index] = rows[index].copy(
                    stateVersion = snapshot.stateVersion + 1,
                    updatedAtEpochMillis = snapshot.updatedAtEpochMillis + 10_000,
                )
            },
        )

        assertEquals(rows.size, scheduled.size)
        assertEquals(rows.map { it.workId }.toSet(), scheduled.toSet())
    }

    private fun runningRecord(index: Int): ProductionProblemOrganizationWorkSchedule {
        val leaseGroup = if (index < 300) 0 else 1
        val updatedGroup = when {
            index < 100 -> 0
            index < 300 -> 1
            index < 400 -> 0
            else -> 1
        }
        return ProductionProblemOrganizationWorkSchedule(
            workId = "work-${index.toString().padStart(4, '0')}",
            stateVersion = index.toLong() + 1,
            eligibleAtEpochMillis = 100_000L + leaseGroup,
            updatedAtEpochMillis = 90_000L + updatedGroup,
            executionLeaseToken = index.toString(16).padStart(64, '0'),
        )
    }

    private fun compareRecoverySnapshots(
        left: ProductionProblemOrganizationWorkSchedule,
        right: ProductionProblemOrganizationWorkSchedule,
    ): Int =
        comparePublishedRecoveryCursors(left.toRecoveryCursor(), right.toRecoveryCursor())
}
