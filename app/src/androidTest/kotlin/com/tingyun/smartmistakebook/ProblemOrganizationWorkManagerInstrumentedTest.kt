package com.tingyun.smartmistakebook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionLease
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionResult
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationWorkSchedule
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProblemOrganizationWorkManagerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        ProblemOrganizationWorkerInstrumentedTestControl.reset()
    }

    @After
    fun tearDown() {
        ProblemOrganizationWorkerInstrumentedTestControl.reset()
        workManager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun claimFailureIsRecoveredInProcessAndStaleVersionAndOwnerCannotExecute() = runBlocking {
        val scheduler = ProblemOrganizationWorkScheduler(workManager) { TEST_NOW }
        val claimed =
            schedule(workId = "wm-running-recovery", stateVersion = 1, tokenByte = 'a')
                .copy(eligibleAtEpochMillis = TEST_NOW)
        val recoverable =
            claimed.copy(
                stateVersion = 2,
                executionLeaseToken = "b".repeat(64),
            )
        val staleOwner = recoverable.copy(executionLeaseToken = claimed.executionLeaseToken)
        val persistedRunning = AtomicReference<ProductionProblemOrganizationWorkSchedule?>()
        val recoveredExecutionCount = AtomicInteger()
        val attemptedLeases = mutableListOf<ProductionProblemOrganizationExecutionLease>()
        val recoveryScanEntered = CompletableDeferred<Unit>()
        val requestRecoveryScan = Channel<Unit>(Channel.UNLIMITED)
        val recoveryEnqueued = CompletableDeferred<Unit>()
        val coordinator =
            ProblemOrganizationWorkSchedulingCoordinator(
                parentScope = this,
                observeSchedulable = { flow { awaitCancellation() } },
                readRunningRecoveryPage = { query ->
                    if (query.after == null) listOfNotNull(persistedRunning.get()) else emptyList()
                },
                enqueueSchedulable = scheduler::enqueue,
                enqueueRunningRecovery = { schedule ->
                    scheduler.enqueueRunningRecovery(schedule)
                    if (schedule == recoverable) recoveryEnqueued.complete(Unit)
                },
                awaitNextRunningRecoveryScan = {
                    recoveryScanEntered.complete(Unit)
                    requestRecoveryScan.receive()
                },
            )
        ProblemOrganizationWorkerInstrumentedTestControl.install(
            ProblemOrganizationWorkerExecution { lease, _ ->
                synchronized(attemptedLeases) {
                    attemptedLeases += lease
                }
                val active = persistedRunning.get()
                if (
                    active == null ||
                    active.workId != lease.workId ||
                    active.stateVersion != lease.stateVersion ||
                    active.executionLeaseToken != lease.token
                ) {
                    ProblemOrganizationWorkerExecutionOutcome.Stale
                } else if (lease.stateVersion == claimed.stateVersion) {
                    check(persistedRunning.compareAndSet(active, recoverable))
                    throw IOException("claim persisted before execution failed")
                } else {
                    check(persistedRunning.compareAndSet(active, null))
                    recoveredExecutionCount.incrementAndGet()
                    ProblemOrganizationWorkerExecutionOutcome.Executed(
                        ProductionProblemOrganizationExecutionResult.Finished,
                    )
                }
            },
        )

        try {
            coordinator.start()
            withTimeout(5_000) { recoveryScanEntered.await() }

            persistedRunning.set(claimed)
            scheduler.enqueue(claimed)
            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
            val originalWork =
                workInfos(ProblemOrganizationWorkScheduler.uniqueWorkName(claimed)).single()
            driver.setAllConstraintsMet(originalWork.id)

            assertEquals(
                WorkInfo.State.ENQUEUED,
                workInfos(ProblemOrganizationWorkScheduler.uniqueWorkName(claimed)).single().state,
            )
            assertEquals(recoverable, persistedRunning.get())

            requestRecoveryScan.send(Unit)
            withTimeout(5_000) { recoveryEnqueued.await() }
            assertEquals(
                1,
                workInfos(
                    ProblemOrganizationWorkScheduler.recoveryWorkName(
                        recoverable.workId,
                        recoverable.stateVersion,
                        recoverable.executionLeaseToken,
                    ),
                ).size,
            )

            scheduler.enqueueRunningRecovery(staleOwner)
            val staleOwnerWork =
                workInfos(
                    ProblemOrganizationWorkScheduler.recoveryWorkName(
                        staleOwner.workId,
                        staleOwner.stateVersion,
                        staleOwner.executionLeaseToken,
                    ),
                ).single()
            driver.setAllConstraintsMet(staleOwnerWork.id)
            assertEquals(
                WorkInfo.State.SUCCEEDED,
                workInfos(
                    ProblemOrganizationWorkScheduler.recoveryWorkName(
                        staleOwner.workId,
                        staleOwner.stateVersion,
                        staleOwner.executionLeaseToken,
                    ),
                ).single().state,
            )

            driver.setAllConstraintsMet(originalWork.id)
            assertEquals(
                WorkInfo.State.SUCCEEDED,
                workInfos(ProblemOrganizationWorkScheduler.uniqueWorkName(claimed)).single().state,
            )
            assertEquals(0, recoveredExecutionCount.get())
            assertEquals(recoverable, persistedRunning.get())

            val recoveredWork =
                workInfos(
                    ProblemOrganizationWorkScheduler.recoveryWorkName(
                        recoverable.workId,
                        recoverable.stateVersion,
                        recoverable.executionLeaseToken,
                    ),
                ).single()
            driver.setAllConstraintsMet(recoveredWork.id)

            assertEquals(
                WorkInfo.State.SUCCEEDED,
                workInfos(
                    ProblemOrganizationWorkScheduler.recoveryWorkName(
                        recoverable.workId,
                        recoverable.stateVersion,
                        recoverable.executionLeaseToken,
                    ),
                ).single().state,
            )
            assertEquals(1, recoveredExecutionCount.get())
            assertEquals(null, persistedRunning.get())
            assertTrue(
                synchronized(attemptedLeases) {
                    attemptedLeases.containsAll(
                        listOf(
                            ProductionProblemOrganizationExecutionLease(
                                claimed.workId,
                                claimed.stateVersion,
                                claimed.executionLeaseToken,
                            ),
                            ProductionProblemOrganizationExecutionLease(
                                staleOwner.workId,
                                staleOwner.stateVersion,
                                staleOwner.executionLeaseToken,
                            ),
                            ProductionProblemOrganizationExecutionLease(
                                recoverable.workId,
                                recoverable.stateVersion,
                                recoverable.executionLeaseToken,
                            ),
                        ),
                    )
                },
            )
        } finally {
            coordinator.close()
            requestRecoveryScan.close()
            ProblemOrganizationWorkerInstrumentedTestControl.reset()
        }
    }

    @Test
    fun keepDeduplicatesOneOccurrenceWithoutSwallowingANewVersionOrOwner() = runBlocking {
        val scheduler = ProblemOrganizationWorkScheduler(workManager) { TEST_NOW }
        val oldOwner = schedule(workId = "wm-restart", stateVersion = 7, tokenByte = 'a')
        val nextVersion = oldOwner.copy(stateVersion = 8, executionLeaseToken = "b".repeat(64))
        val nextOwner = oldOwner.copy(executionLeaseToken = "c".repeat(64))

        scheduler.enqueue(oldOwner)
        scheduler.enqueue(oldOwner)
        scheduler.enqueue(nextVersion)
        scheduler.enqueue(nextOwner)

        assertEquals(1, workInfos(ProblemOrganizationWorkScheduler.uniqueWorkName(oldOwner)).size)
        assertEquals(1, workInfos(ProblemOrganizationWorkScheduler.uniqueWorkName(nextVersion)).size)
        assertEquals(1, workInfos(ProblemOrganizationWorkScheduler.uniqueWorkName(nextOwner)).size)

        val persistedOldOwnerWork =
            workInfos(ProblemOrganizationWorkScheduler.uniqueWorkName(oldOwner)).single()
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        driver.setInitialDelayMet(persistedOldOwnerWork.id)
        driver.setAllConstraintsMet(persistedOldOwnerWork.id)

        assertEquals(
            WorkInfo.State.SUCCEEDED,
            workInfos(ProblemOrganizationWorkScheduler.uniqueWorkName(oldOwner)).single().state,
        )
    }

    @Test
    fun lostAcknowledgementAfterRealEnqueueIsIdempotentAndPartialSuccessResumes() = runBlocking {
        val scheduler = ProblemOrganizationWorkScheduler(workManager) { TEST_NOW }
        val first = schedule(workId = "wm-partial-first", stateVersion = 1, tokenByte = 'd')
        val second = schedule(workId = "wm-partial-second", stateVersion = 1, tokenByte = 'e')
        val deduplicator = ProblemOrganizationScheduleDeduplicator()
        var loseSecondAcknowledgement = true

        val firstAttempt =
            runCatching {
                enqueueUnacknowledgedProblemOrganizationWorks(
                    snapshots = listOf(first, second),
                    deduplicator = deduplicator,
                ) { schedule ->
                    scheduler.enqueue(schedule)
                    if (schedule == second && loseSecondAcknowledgement) {
                        throw IOException("operation acknowledgement was lost")
                    }
                }
            }
        assertTrue(firstAttempt.exceptionOrNull() is IOException)

        loseSecondAcknowledgement = false
        enqueueUnacknowledgedProblemOrganizationWorks(
            snapshots = listOf(first, second),
            deduplicator = deduplicator,
            enqueue = scheduler::enqueue,
        )

        assertEquals(1, workInfos(ProblemOrganizationWorkScheduler.uniqueWorkName(first)).size)
        assertEquals(1, workInfos(ProblemOrganizationWorkScheduler.uniqueWorkName(second)).size)
    }

    @Test
    fun productionWorkerFactoryOwnsOnlyTheResolverProvider() {
        val ownedFields =
            ProblemOrganizationWorkerFactory::class.java.declaredFields.filter { field ->
                !field.isSynthetic && !Modifier.isStatic(field.modifiers)
            }

        assertEquals(listOf("executionResolverProvider"), ownedFields.map { it.name })
    }

    private fun workInfos(uniqueWorkName: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(uniqueWorkName).get(5, TimeUnit.SECONDS)

    private fun schedule(
        workId: String,
        stateVersion: Long,
        tokenByte: Char,
    ) = ProductionProblemOrganizationWorkSchedule(
        workId = workId,
        stateVersion = stateVersion,
        eligibleAtEpochMillis = TEST_NOW + 60_000,
        updatedAtEpochMillis = TEST_NOW,
        executionLeaseToken = tokenByte.toString().repeat(64),
    )

    private companion object {
        const val TEST_NOW = 100_000L
    }
}
