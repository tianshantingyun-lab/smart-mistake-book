package com.tingyun.smartmistakebook.core.data.model

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class ModelExecutionAdmissionGateTest {
    @Test
    fun limitsConcurrentExecutionsToPermitCount() = runBlocking {
        val gate = ModelExecutionAdmissionGate(permits = 2)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val jobs =
            List(5) {
                async {
                    gate.withPermit {
                        val now = active.incrementAndGet()
                        maxActive.updateAndGet { max -> max.coerceAtLeast(now) }
                        delay(20)
                        active.decrementAndGet()
                    }
                }
            }
        jobs.forEach { it.await() }

        assertEquals(2, maxActive.get())
        assertEquals(0, active.get())
    }

    @Test
    fun releasesPermitWhenBlockFails() = runBlocking {
        val gate = ModelExecutionAdmissionGate(permits = 1)

        try {
            gate.withPermit { error("boom") }
            fail("Expected gate block failure")
        } catch (expected: IllegalStateException) {
            // Expected.
        }
        gate.withPermit { }
    }

    @Test
    fun rejectsZeroPermits() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelExecutionAdmissionGate(permits = 0)
        }
    }
}
