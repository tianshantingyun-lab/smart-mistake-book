package com.tingyun.smartmistakebook.core.database

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorFreeResponseOutboxExpirySchedulerTest {
    @Test
    fun idlePendingCutoffIsWipedWithoutAnotherDatabaseOperation(): Unit =
        withScheduler(cutoffOffsetMillis = 120L) { _, task ->
            assertTrue(task.awaitFirstRead())
            assertTrue(task.awaitFirstWipe())
            assertEquals(listOf(task.initialCutoff), task.wipedCutoffs)
        }

    @Test
    fun newlyObservedEarlierCutoffReplacesTheExistingArm(): Unit =
        withScheduler(cutoffOffsetMillis = 5_000L) { scheduler, task ->
            assertTrue(task.awaitFirstRead())
            val earlier = System.currentTimeMillis() + 120L
            task.cutoff.set(earlier)
            scheduler.requestRefresh()

            assertTrue(task.awaitFirstWipe())
            assertEquals(listOf(earlier), task.wipedCutoffs)
        }

    @Test
    fun terminalRefreshCancelsTheArmWithoutCreatingAnotherWipe(): Unit =
        withScheduler(cutoffOffsetMillis = 250L) { scheduler, task ->
            assertTrue(task.awaitFirstRead())
            task.cutoff.set(NO_CUTOFF)
            scheduler.requestRefresh()

            assertFalse(task.wipeLatch.await(500L, TimeUnit.MILLISECONDS))
            assertTrue(task.maxConcurrentCalls.get() <= 1)
            assertTrue(task.readCount.get() <= 2)
        }

    @Test
    fun wallClockRollbackCannotExtendAnAlreadyCapturedCutoff(): Unit {
        val wallClock = AtomicLong(50_000L)
        val task = RecordingExpiryTask(initialCutoff = 50_180L)
        val scheduler = CoroutineTutorFreeResponseOutboxExpirySchedulerFactory.create(
            clock = wallClock::get,
            task = task,
        )
        try {
            scheduler.requestRefresh()
            assertTrue(task.awaitFirstRead())
            wallClock.set(1_000L)
            scheduler.requestRefresh()

            assertTrue(task.awaitFirstWipe())
            assertEquals(listOf(50_180L), task.wipedCutoffs)
        } finally {
            scheduler.closeAndJoin()
        }
    }

    @Test
    fun closeJoinsTheOnlyWorkerBeforeDatabaseTaskCanBeClosed(): Unit {
        val task = RecordingExpiryTask(System.currentTimeMillis() + 300L)
        val scheduler = CoroutineTutorFreeResponseOutboxExpirySchedulerFactory.create(
            clock = System::currentTimeMillis,
            task = task,
        )
        scheduler.requestRefresh()
        assertTrue(task.awaitFirstRead())

        scheduler.closeAndJoin()
        task.databaseClosed.set(true)
        Thread.sleep(400L)

        assertTrue(task.wipedCutoffs.isEmpty())
        assertFalse(task.accessedAfterDatabaseClose.get())
        assertTrue(task.maxConcurrentCalls.get() <= 1)
    }

    private fun withScheduler(
        cutoffOffsetMillis: Long,
        assertion: (TutorFreeResponseOutboxExpiryScheduler, RecordingExpiryTask) -> Unit,
    ) {
        val task = RecordingExpiryTask(System.currentTimeMillis() + cutoffOffsetMillis)
        val scheduler = CoroutineTutorFreeResponseOutboxExpirySchedulerFactory.create(
            clock = System::currentTimeMillis,
            task = task,
        )
        try {
            scheduler.requestRefresh()
            assertion(scheduler, task)
        } finally {
            scheduler.closeAndJoin()
        }
    }

    private class RecordingExpiryTask(
        val initialCutoff: Long,
    ) : TutorFreeResponseOutboxExpiryTask {
        val cutoff = AtomicLong(initialCutoff)
        val wipeLatch = CountDownLatch(1)
        val readCount = AtomicInteger(0)
        val maxConcurrentCalls = AtomicInteger(0)
        val databaseClosed = AtomicBoolean(false)
        val accessedAfterDatabaseClose = AtomicBoolean(false)
        val wipedCutoffs: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        private val firstReadLatch = CountDownLatch(1)
        private val concurrentCalls = AtomicInteger(0)

        override suspend fun nextPendingCutoffEpochMillis(): Long? = trackAccess {
            readCount.incrementAndGet()
            firstReadLatch.countDown()
            cutoff.get().takeUnless { it == NO_CUTOFF }
        }

        override suspend fun wipePendingOutboxesThrough(cutoffEpochMillis: Long) {
            trackAccess {
                wipedCutoffs += cutoffEpochMillis
                cutoff.set(NO_CUTOFF)
                wipeLatch.countDown()
            }
        }

        fun awaitFirstRead(): Boolean = firstReadLatch.await(2L, TimeUnit.SECONDS)

        fun awaitFirstWipe(): Boolean = wipeLatch.await(2L, TimeUnit.SECONDS)

        private inline fun <T> trackAccess(block: () -> T): T {
            if (databaseClosed.get()) accessedAfterDatabaseClose.set(true)
            val concurrent = concurrentCalls.incrementAndGet()
            maxConcurrentCalls.accumulateAndGet(concurrent, ::maxOf)
            return try {
                block()
            } finally {
                concurrentCalls.decrementAndGet()
            }
        }
    }

    private companion object {
        const val NO_CUTOFF = Long.MIN_VALUE
    }
}
