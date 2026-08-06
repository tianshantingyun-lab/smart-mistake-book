package com.tingyun.smartmistakebook.core.data.model

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkloadAdmissionGateTest {
    @Test
    fun defaultFactoryAdmitsOneJobPerCategory() = runBlocking {
        val gate = WorkloadAdmissionGateFactory.createDefault()
        val jobs =
            WorkloadCategory.entries.map { category ->
                async {
                    gate.withPermit(category) { }
                }
            }

        jobs.forEach { it.await() }
    }

    @Test
    fun defaultFactorySharedTotalBindsAllCategoriesTogether() = runBlocking {
        val gate = WorkloadAdmissionGateFactory.createDefault()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val categories =
            listOf(
                WorkloadCategory.MODEL,
                WorkloadCategory.MODEL,
                WorkloadCategory.MODEL,
                WorkloadCategory.MODEL,
                WorkloadCategory.BATCH_IMPORT,
                WorkloadCategory.BATCH_IMPORT,
                WorkloadCategory.PDF,
                WorkloadCategory.PROJECTION,
            )
        val jobs =
            categories.map { category ->
                async {
                    gate.withPermit(category) {
                        active.incrementAndGet()
                        maxActive.updateAndGet { max -> max.coerceAtLeast(active.get()) }
                        delay(20)
                        active.decrementAndGet()
                    }
                }
            }

        jobs.forEach { it.await() }

        assertEquals(8, maxActive.get())
        assertEquals(0, active.get())
    }

    @Test
    fun sharedProjectionGateSharesTotalWithOtherCategories() = runBlocking {
        val gate = SharedWorkloadAdmissionGate.gate
        val releaseAll = CompletableDeferred<Unit>()
        val categories =
            listOf(
                WorkloadCategory.MODEL,
                WorkloadCategory.MODEL,
                WorkloadCategory.MODEL,
                WorkloadCategory.MODEL,
                WorkloadCategory.BATCH_IMPORT,
                WorkloadCategory.BATCH_IMPORT,
                WorkloadCategory.PDF,
                WorkloadCategory.PROJECTION,
            )
        val holders =
            categories.map { category ->
                async {
                    gate.withPermit(category) {
                        releaseAll.await()
                    }
                }
            }
        val projectionStarted = CompletableDeferred<Unit>()
        val projection =
            async {
                SharedProjectionWorkloadGate.withPermit {
                    projectionStarted.complete(Unit)
                }
            }

        delay(50)
        assertFalse(projectionStarted.isCompleted)

        releaseAll.complete(Unit)
        holders.forEach { it.await() }
        projection.await()
        assertTrue(projectionStarted.isCompleted)
    }

    @Test
    fun categoriesAreIsolatedUpToTheSharedTotal() = runBlocking {
        val gate =
            WorkloadAdmissionGate(
                categoryPermits =
                    mapOf(
                        WorkloadCategory.MODEL to 2,
                        WorkloadCategory.BATCH_IMPORT to 2,
                    ),
                totalPermits = 4,
            )
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val jobs =
            List(2) { index ->
                async {
                    gate.withPermit(WorkloadCategory.MODEL) {
                        active.incrementAndGet()
                        maxActive.updateAndGet { max -> max.coerceAtLeast(active.get()) }
                        delay(20)
                        active.decrementAndGet()
                    }
                }
            } + List(2) { index ->
                async {
                    gate.withPermit(WorkloadCategory.BATCH_IMPORT) {
                        active.incrementAndGet()
                        maxActive.updateAndGet { max -> max.coerceAtLeast(active.get()) }
                        delay(20)
                        active.decrementAndGet()
                    }
                }
            }
        jobs.forEach { it.await() }

        assertEquals(4, maxActive.get())
        assertEquals(0, active.get())
    }

    @Test
    fun totalPermitBoundsAllCategories() = runBlocking {
        val gate =
            WorkloadAdmissionGate(
                categoryPermits =
                    mapOf(
                        WorkloadCategory.MODEL to 2,
                        WorkloadCategory.PDF to 2,
                    ),
                totalPermits = 3,
            )
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val jobs =
            List(6) { index ->
                async {
                    gate.withPermit(
                        if (index % 2 == 0) WorkloadCategory.MODEL else WorkloadCategory.PDF,
                    ) {
                        active.incrementAndGet()
                        maxActive.updateAndGet { max -> max.coerceAtLeast(active.get()) }
                        delay(20)
                        active.decrementAndGet()
                    }
                }
            }
        jobs.forEach { it.await() }

        assertEquals(3, maxActive.get())
    }

    @Test
    fun unregisteredCategoryFailsClosed() = runBlocking {
        val gate =
            WorkloadAdmissionGate(
                categoryPermits = mapOf(WorkloadCategory.MODEL to 1),
                totalPermits = 2,
            )

        try {
            gate.withPermit(WorkloadCategory.PROJECTION) { }
            fail("Expected unregistered category failure")
        } catch (expected: IllegalStateException) {
            // Expected.
        }
        gate.withPermit(WorkloadCategory.MODEL) { }
    }

    @Test
    fun releasesPermitsWhenBlockFails() = runBlocking {
        val gate =
            WorkloadAdmissionGate(
                categoryPermits = mapOf(WorkloadCategory.MODEL to 1),
                totalPermits = 2,
            )

        try {
            gate.withPermit(WorkloadCategory.MODEL) { error("boom") }
            fail("Expected gate block failure")
        } catch (expected: IllegalStateException) {
            // Expected.
        }
        gate.withPermit(WorkloadCategory.MODEL) { }
    }

    @Test
    fun rejectsInvalidPermits() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkloadAdmissionGate(
                categoryPermits = mapOf(WorkloadCategory.MODEL to 0),
                totalPermits = 2,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkloadAdmissionGate(
                categoryPermits = mapOf(WorkloadCategory.MODEL to 3),
                totalPermits = 2,
            )
        }
    }
}
