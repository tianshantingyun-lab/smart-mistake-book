package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionPort
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationReadCapability
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredModelExecutionLeaseFactoryTest {
    @Test
    fun unconfiguredPortRemainsReusableUntilItsOwnerClosesTheLease() = runBlocking {
        val lease = ConfiguredModelExecutionLeaseFactory.create(UnconfiguredModelConfiguration)
        val port = lease.claimForModelTaskOwner()

        repeat(2) {
            assertEquals(
                ModelExecutionLocation.UNAVAILABLE,
                port.capabilities().executionLocation,
            )
        }
        assertTrue(runCatching { lease.claimForModelTaskOwner() }.isFailure)

        lease.close()

        assertTrue(runCatching { port.capabilities() }.isFailure)
    }

    @Test
    fun concurrentAndCrossGenerationClaimsCannotReplayOneLease() {
        val lease = ConfiguredModelExecutionLeaseFactory.create(UnconfiguredModelConfiguration)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Result<ConfiguredModelExecutionPort>>())
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) {
                executor.submit {
                    start.await()
                    results += runCatching { lease.claimForModelTaskOwner() }
                }
            }
            start.countDown()
            futures.forEach { future -> future.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { result -> result.isSuccess })
        assertEquals(7, results.count { result -> result.isFailure })
        assertNotNull(results.single { result -> result.isSuccess }.getOrNull())
        lease.close()
    }

    @Test
    fun closeBeforeClaimRejectsLateOwnerHandoff() {
        val lease = ConfiguredModelExecutionLeaseFactory.create(UnconfiguredModelConfiguration)

        lease.close()
        lease.close()

        assertTrue(runCatching { lease.claimForModelTaskOwner() }.isFailure)
    }
}

private object UnconfiguredModelConfiguration : ModelConfigurationReadCapability {
    override val configuration: Flow<ModelConfigurationSnapshot> =
        flowOf(ModelConfigurationSnapshot())

    override suspend fun readCredential(): ModelCredentialReadResult =
        ModelCredentialReadResult.Missing
}
