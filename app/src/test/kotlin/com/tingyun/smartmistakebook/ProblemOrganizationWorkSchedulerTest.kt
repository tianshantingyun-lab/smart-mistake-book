package com.tingyun.smartmistakebook

import androidx.work.ListenableWorker
import androidx.work.NetworkType
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationWorkRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
