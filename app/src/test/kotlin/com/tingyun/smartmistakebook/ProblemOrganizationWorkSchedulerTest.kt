package com.tingyun.smartmistakebook

import androidx.work.ListenableWorker
import androidx.work.NetworkType
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationWorkRecord
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
    fun runningRecoveryIsDeduplicatedByWorkAndClaimedStateVersion() {
        assertEquals(
            "problem-organization-recovery:work-1:7",
            ProblemOrganizationWorkScheduler.recoveryWorkName("work-1", 7),
        )
        assertFalse(
            ProblemOrganizationWorkScheduler.recoveryWorkName("work-1", 7) ==
                ProblemOrganizationWorkScheduler.recoveryWorkName("work-1", 8),
        )
    }

    @Test
    fun runningRecoveryWaitsForThePersistedLeaseExpiry() {
        assertEquals(
            145_000L,
            ProblemOrganizationWorkScheduler.eligibleAtEpochMillis(
                ProblemOrganizationWorkRecord(
                    workId = "work-1",
                    commitReceiptCommandId = "receipt-1",
                    status = "RUNNING",
                    stateVersion = 7,
                    attemptCount = 1,
                    notBeforeEpochMillis = 90_000L,
                    requestId = "request-1",
                    requestSnapshot = "{}",
                    leaseOwner = "worker-1",
                    leaseExpiresAtEpochMillis = 145_000L,
                    failureCode = null,
                    failureMessage = null,
                    createdAtEpochMillis = 80_000L,
                    updatedAtEpochMillis = 100_000L,
                ),
            ),
        )
    }

    @Test
    fun startupRecoveryPagesEveryRunningStateVersionAtItsOwnExpiry() = runBlocking {
        val running = (0 until 500)
            .map(::runningRecord)
            .sortedBy { ProblemOrganizationWorkRecoveryCursor.from(it) }
        val scheduled = mutableListOf<Pair<String, Long>>()
        val requestedCursors = mutableListOf<ProblemOrganizationWorkRecoveryCursor?>()

        recoverRunningProblemOrganizationWorks(
            pageSize = 100,
            readPage = { limit, afterLease, afterUpdated, afterWorkId ->
                val cursor = when {
                    afterLease == null &&
                        afterUpdated == null &&
                        afterWorkId == null -> null
                    afterLease != null &&
                        afterUpdated != null &&
                        afterWorkId != null -> ProblemOrganizationWorkRecoveryCursor(
                        leaseExpiresAtEpochMillis = afterLease,
                        updatedAtEpochMillis = afterUpdated,
                        workId = afterWorkId,
                    )
                    else -> error("Cursor must be entirely absent or present")
                }
                requestedCursors += cursor
                running
                    .asSequence()
                    .filter { record ->
                        cursor?.let {
                            ProblemOrganizationWorkRecoveryCursor.from(record) > it
                        } != false
                    }
                    .take(limit)
                    .toList()
            },
            enqueue = { record ->
                scheduled +=
                    ProblemOrganizationWorkScheduler.recoveryWorkName(
                        record.workId,
                        record.stateVersion,
                    ) to ProblemOrganizationWorkScheduler.eligibleAtEpochMillis(record)
            },
        )

        val expected = running.map { record ->
            ProblemOrganizationWorkScheduler.recoveryWorkName(
                record.workId,
                record.stateVersion,
            ) to requireNotNull(record.leaseExpiresAtEpochMillis)
        }
        assertEquals(expected, scheduled)
        assertEquals(500, scheduled.distinct().size)
        assertEquals(6, requestedCursors.size)
        val pageCursors = requestedCursors.filterNotNull()
        assertEquals(
            running.filterIndexed { index, _ -> index % 100 == 99 }
                .map { ProblemOrganizationWorkRecoveryCursor.from(it) },
            pageCursors,
        )
        assertEquals(pageCursors[0].leaseExpiresAtEpochMillis, pageCursors[1].leaseExpiresAtEpochMillis)
        assertTrue(pageCursors[0].updatedAtEpochMillis < pageCursors[1].updatedAtEpochMillis)
        assertEquals(pageCursors[1].leaseExpiresAtEpochMillis, pageCursors[2].leaseExpiresAtEpochMillis)
        assertEquals(pageCursors[1].updatedAtEpochMillis, pageCursors[2].updatedAtEpochMillis)
        assertTrue(pageCursors[1].workId < pageCursors[2].workId)
        assertTrue(pageCursors[2].leaseExpiresAtEpochMillis < pageCursors[3].leaseExpiresAtEpochMillis)
        assertTrue(scheduled.map { it.second }.distinct().size > 1)
    }

    @Test
    fun startupRecoveryDoesNotScheduleTheSameStateTwiceIfItsCursorMoves() = runBlocking {
        val first = runningRecord(1)
        val moved = first.copy(updatedAtEpochMillis = first.updatedAtEpochMillis + 1)
        val rows = listOf(first, moved)
        val scheduled = mutableListOf<String>()

        recoverRunningProblemOrganizationWorks(
            pageSize = 1,
            readPage = { limit, afterLease, afterUpdated, afterWorkId ->
                val cursor = afterLease?.let {
                    ProblemOrganizationWorkRecoveryCursor(
                        leaseExpiresAtEpochMillis = it,
                        updatedAtEpochMillis = requireNotNull(afterUpdated),
                        workId = requireNotNull(afterWorkId),
                    )
                }
                rows.asSequence()
                    .filter { cursor == null || ProblemOrganizationWorkRecoveryCursor.from(it) > cursor }
                    .take(limit)
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
            .sortedBy { ProblemOrganizationWorkRecoveryCursor.from(it) }
            .toMutableList()
        val scheduled = mutableListOf<String>()
        var allPagesRead = false

        recoverRunningProblemOrganizationWorks(
            pageSize = 100,
            readPage = { limit, afterLease, afterUpdated, afterWorkId ->
                val cursor = afterLease?.let {
                    ProblemOrganizationWorkRecoveryCursor(
                        leaseExpiresAtEpochMillis = it,
                        updatedAtEpochMillis = requireNotNull(afterUpdated),
                        workId = requireNotNull(afterWorkId),
                    )
                }
                rows.asSequence()
                    .filter { cursor == null || ProblemOrganizationWorkRecoveryCursor.from(it) > cursor }
                    .take(limit)
                    .toList()
                    .also { page -> allPagesRead = page.size < limit }
            },
            enqueue = { record ->
                assertTrue("Enqueue started before the paging snapshot completed", allPagesRead)
                scheduled += record.workId
                val index = rows.indexOfFirst { it.workId == record.workId }
                rows[index] = rows[index].copy(
                    stateVersion = record.stateVersion + 1,
                    updatedAtEpochMillis = record.updatedAtEpochMillis + 10_000,
                )
            },
        )

        assertEquals(rows.size, scheduled.size)
        assertEquals(rows.map { it.workId }.toSet(), scheduled.toSet())
    }

    private fun runningRecord(index: Int): ProblemOrganizationWorkRecord {
        val leaseGroup = if (index < 300) 0 else 1
        val updatedGroup = when {
            index < 100 -> 0
            index < 300 -> 1
            index < 400 -> 0
            else -> 1
        }
        return ProblemOrganizationWorkRecord(
            workId = "work-${index.toString().padStart(4, '0')}",
            commitReceiptCommandId = "receipt-$index",
            status = "RUNNING",
            stateVersion = index.toLong() + 1,
            attemptCount = 1,
            notBeforeEpochMillis = 50_000L,
            requestId = "request-$index",
            requestSnapshot = "{}",
            leaseOwner = "worker-$index",
            leaseExpiresAtEpochMillis = 100_000L + leaseGroup,
            failureCode = null,
            failureMessage = null,
            createdAtEpochMillis = 40_000L + index,
            updatedAtEpochMillis = 90_000L + updatedGroup,
        )
    }
}
