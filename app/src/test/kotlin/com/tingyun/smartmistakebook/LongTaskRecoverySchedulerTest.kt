package com.tingyun.smartmistakebook

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LongTaskRecoverySchedulerTest {
    @Test
    fun registeredUnitsRunInOrderAndFailuresAreIsolated() = runBlocking {
        val order = mutableListOf<String>()
        val scheduler = LongTaskRecoveryScheduler(CoroutineScope(Dispatchers.Unconfined))
        scheduler.register("first") {
            order += "first"
            throw IllegalStateException("first failed")
        }
        scheduler.register("second") {
            order += "second"
        }

        scheduler.runRecoveries()

        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun duplicateRegistrationFailsClosed() {
        val scheduler = LongTaskRecoveryScheduler(CoroutineScope(Dispatchers.Unconfined))
        scheduler.register("only") {}

        assertThrows(IllegalArgumentException::class.java) {
            scheduler.register("only") {}
        }
    }

    @Test
    fun startRunsEachUnitExactlyOnce() = runBlocking {
        val count = AtomicInteger(0)
        val scheduler = LongTaskRecoveryScheduler(CoroutineScope(Dispatchers.Unconfined))
        scheduler.register("counted") {
            count.incrementAndGet()
        }

        scheduler.start()
        scheduler.start()

        assertEquals(1, count.get())
    }
}
