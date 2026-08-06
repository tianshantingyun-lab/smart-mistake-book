package com.tingyun.smartmistakebook.core.database

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySessionDatabaseOwnerRegistryTest {
    @Test
    fun concurrentAcquireCreatesOneResourceButReturnsIndependentLeases() {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val registry =
            ReferenceCountedLegacySessionOwnerRegistry<String, Any> {
                closes.incrementAndGet()
            }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        try {
            val futures =
                List(32) {
                    executor.submit<ReferenceCountedLegacySessionOwnerLease<Any>> {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        registry.acquire("legacy.db") {
                            opens.incrementAndGet()
                            Any()
                        }
                    }
                }
            start.countDown()
            val leases = futures.map { future -> future.get(5, TimeUnit.SECONDS) }
            val resources = leases.map { lease -> lease.useResource { it } }

            assertEquals(1, opens.get())
            leases.forEachIndexed { index, lease ->
                leases.drop(index + 1).forEach { other -> assertNotSame(lease, other) }
            }
            resources.drop(1).forEach { resource -> assertSame(resources.first(), resource) }

            leases
                .map { lease -> executor.submit { lease.close() } }
                .forEach { future -> future.get(5, TimeUnit.SECONDS) }
            assertEquals(1, closes.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun oneCloseKeepsOtherLeaseAliveAndLastClosePermitsAFullReopen() {
        val closes = AtomicInteger()
        val registry =
            ReferenceCountedLegacySessionOwnerRegistry<String, Any> {
                closes.incrementAndGet()
            }
        val firstLease = registry.acquire("legacy.db", ::Any)
        val secondLease = registry.acquire("legacy.db", ::Any)
        val firstResource = firstLease.useResource { it }

        firstLease.close()
        firstLease.close()

        assertEquals(0, closes.get())
        assertSame(firstResource, secondLease.useResource { it })

        secondLease.close()
        assertEquals(1, closes.get())

        val reopened = registry.acquire("legacy.db", ::Any)
        assertNotSame(firstResource, reopened.useResource { it })
        reopened.close()
        assertEquals(2, closes.get())
    }

    @Test
    fun failedCreateIsNeverRegisteredAndRetryCreatesNormally() {
        val creates = AtomicInteger()
        val closes = AtomicInteger()
        val registry =
            ReferenceCountedLegacySessionOwnerRegistry<String, Any> {
                closes.incrementAndGet()
            }

        assertThrows(IllegalStateException::class.java) {
            registry.acquire("legacy.db") {
                creates.incrementAndGet()
                error("open failed")
            }
        }

        val lease =
            registry.acquire("legacy.db") {
                creates.incrementAndGet()
                Any()
            }
        assertEquals(2, creates.get())
        assertEquals(0, closes.get())

        lease.close()
        assertEquals(1, closes.get())
    }
}
