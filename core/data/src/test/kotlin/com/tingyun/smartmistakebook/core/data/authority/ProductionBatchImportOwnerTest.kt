package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.domain.BatchImportJob
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationApproval
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationOffer
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CreateBatchImportRequest
import com.tingyun.smartmistakebook.core.domain.CreatePdfImportRequest
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionBatchImportOwnerTest {
    @Test
    fun claimAndRepositoryEachTransferExactlyOnce() {
        val fixture = batchOwnerFixture()
        val owner = ProductionBatchImportOwnerFactory.open(fixture.claim)
        val repository = owner.claimRepository()

        runBlocking { repository.pauseBatchImport("job") }
        assertEquals(1, fixture.repository.pauseCalls.get())
        assertThrows(IllegalStateException::class.java) {
            ProductionBatchImportOwnerFactory.open(fixture.claim)
        }
        assertThrows(IllegalStateException::class.java) {
            owner.claimRepository()
        }

        owner.close()
    }

    @Test
    fun closeCancelsOwnedWorkAndObservationWithoutClosingSharedOwners() = runBlocking {
        val fixture = batchOwnerFixture()
        val childStarted = CompletableDeferred<Unit>()
        val childStopped = CompletableDeferred<Unit>()
        val childCleanupEntered = CompletableDeferred<Unit>()
        val releaseChildCleanup = CompletableDeferred<Unit>()
        CoroutineScope(fixture.processingJob + Dispatchers.Default).launch {
            childStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                childCleanupEntered.complete(Unit)
                withContext(NonCancellable) { releaseChildCleanup.await() }
                childStopped.complete(Unit)
            }
        }
        withTimeout(2_000) { childStarted.await() }

        val owner = ProductionBatchImportOwnerFactory.open(fixture.claim)
        val repository = owner.claimRepository()
        val observation = launch { repository.observeBatchImports().collect { } }
        withTimeout(2_000) { fixture.repository.observationStarted.await() }
        val operation =
            launch {
                runCatching {
                    repository.organizeBatch(
                        BatchImportOrganizationApproval(
                            jobId = "job",
                            providerId = "provider",
                            modelId = "model",
                            providerConfigurationVersion = "v1",
                            approvedAtEpochMillis = 1L,
                        ),
                    )
                }
            }
        withTimeout(2_000) { fixture.repository.operationStarted.await() }

        owner.close()
        owner.close()

        try {
            withTimeout(2_000) { childCleanupEntered.await() }
            withTimeout(2_000) { fixture.repository.observationStopped.await() }
            withTimeout(2_000) { fixture.repository.operationStopped.await() }
            withTimeout(2_000) { observation.join() }
            withTimeout(2_000) { operation.join() }
            assertFalse(
                "A stuck processing child must not delay owner-close cancellation signals",
                childStopped.isCompleted,
            )
        } finally {
            releaseChildCleanup.complete(Unit)
        }
        withTimeout(2_000) { childStopped.await() }
        assertTrue(fixture.processingJob.isCancelled)
        assertEquals(0, fixture.sessionOwner.closeCalls.get())
        assertEquals(0, fixture.modelOwner.closeCalls.get())
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.pauseBatchImport("job")
            }
        }
        Unit
    }

    @Test
    fun closingClaimRevokesItWithoutTouchingSharedOwners() {
        val fixture = batchOwnerFixture()

        fixture.claim.close()
        fixture.claim.close()

        assertThrows(IllegalStateException::class.java) {
            ProductionBatchImportOwnerFactory.open(fixture.claim)
        }
        assertTrue(fixture.processingJob.isCancelled)
        assertEquals(0, fixture.sessionOwner.closeCalls.get())
        assertEquals(0, fixture.modelOwner.closeCalls.get())
    }

    @Test
    fun observationCollectedAfterOwnerCloseFailsWithoutStartingUpstream() {
        val fixture = batchOwnerFixture()
        val owner = ProductionBatchImportOwnerFactory.open(fixture.claim)
        val repository = owner.claimRepository()
        owner.close()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withTimeout(2_000) {
                    repository.observeBatchImports().collect { }
                }
            }
        }
        assertFalse(fixture.repository.observationStarted.isCompleted)
    }

    @Test
    fun failingCloseCallbackCannotSkipProcessingCancellation() {
        val fixture = batchOwnerFixture()
        val owner = ProductionBatchImportOwnerFactory.open(fixture.claim)
        val repository = owner.claimRepository()
        val leaseField =
            repository.javaClass.getDeclaredField("repositoryLease").apply {
                isAccessible = true
            }
        val lease = leaseField.get(repository) as ProductionBatchImportRepositoryLease
        ProductionBatchImportOwnerRegistry.activeAccess(lease).invokeOnClose {
            error("close callback failure")
        }

        assertThrows(RuntimeException::class.java) { owner.close() }

        assertTrue(fixture.processingJob.isCancelled)
        owner.close()
    }

    @Test
    fun concurrentClaimTransferHasOneWinner() {
        val fixture = batchOwnerFixture()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts =
                (0 until 8).map {
                    pool.submit<ProductionBatchImportOwner?> {
                        start.await()
                        runCatching { ProductionBatchImportOwnerFactory.open(fixture.claim) }
                            .getOrNull()
                    }
                }
            start.countDown()

            val winners = attempts.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()

            assertEquals(1, winners.size)
            winners.single().close()
            assertTrue(fixture.processingJob.isCancelled)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun closingClaimDuringConstructionRevokesThePendingOwner() {
        val repository = RecordingBatchImportRepository()
        val processingJob = SupervisorJob()
        val buildStarted = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val identity = Any()
        val claim =
            TestOnlyProductionBatchImportAuthority.issueBlocking(
                repository,
                processingJob,
                identity,
                identity,
                identity,
                identity,
                identity,
                buildStarted,
                releaseBuild,
            )
        val pool = Executors.newSingleThreadExecutor()
        try {
            val transfer = pool.submit<ProductionBatchImportOwner> {
                ProductionBatchImportOwnerFactory.open(claim)
            }
            assertTrue(buildStarted.await(2, TimeUnit.SECONDS))

            claim.close()
            releaseBuild.countDown()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    transfer.get(2, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is IllegalStateException)
            assertTrue(processingJob.isCancelled)
        } finally {
            releaseBuild.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun mismatchedLearnerBindingCannotProduceAnOwner() {
        val repository = RecordingBatchImportRepository()
        val processingJob = SupervisorJob()
        val claim =
            TestOnlyProductionBatchImportAuthority.issueWithBuiltLearner(
                repository,
                processingJob,
                Any(),
                Any(),
                Any(),
                Any(),
                Any(),
                Any(),
            )

        assertThrows(IllegalStateException::class.java) {
            ProductionBatchImportOwnerFactory.open(claim)
        }
        assertTrue(processingJob.isCancelled)
    }

    @Test
    fun reflectivelyConstructedShellsHaveNoAuthority() {
        val forgedClaim =
            Class.forName(CurrentGenerationBatchImportClaim::class.java.name + "\$Issued")
                .declaredConstructors.single()
                .apply { isAccessible = true }
                .newInstance() as CurrentGenerationBatchImportClaim
        val forgedOwner =
            Class.forName(ProductionBatchImportOwner::class.java.name + "\$Issued")
                .declaredConstructors.single()
                .apply { isAccessible = true }
                .newInstance() as ProductionBatchImportOwner
        val wrapperType =
            Class.forName(
                "com.tingyun.smartmistakebook.core.data.authority." +
                    "OwnerBoundBatchImportRepository",
            )
        val forgedRepository =
            wrapperType.declaredConstructors.single()
                .apply { isAccessible = true }
                .newInstance(ProductionBatchImportRepositoryLease.newRegistryShell()) as
                BatchImportRepository

        assertThrows(IllegalStateException::class.java) {
            ProductionBatchImportOwnerFactory.open(forgedClaim)
        }
        assertThrows(IllegalStateException::class.java) {
            forgedOwner.claimRepository()
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { forgedRepository.pauseBatchImport("job") }
        }
        forgedClaim.close()
        forgedOwner.close()
    }

    @Test
    fun publicAbiExposesOnlyOpaqueOneShotTransfer() {
        assertTrue(
            CurrentGenerationBatchImportClaim::class.java.declaredConstructors.all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            },
        )
        assertTrue(
            ProductionBatchImportOwner::class.java.declaredConstructors.all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            },
        )
        assertFalse(Modifier.isPublic(ProductionBatchImportRepositoryLease::class.java.modifiers))
        assertTrue(
            ProductionBatchImportRepositoryLease::class.java.declaredConstructors.all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            },
        )
        val wrapperType =
            Class.forName(
                "com.tingyun.smartmistakebook.core.data.authority." +
                    "OwnerBoundBatchImportRepository",
            )
        assertFalse(Modifier.isPublic(wrapperType.modifiers))
        assertTrue(
            wrapperType.declaredConstructors.all { constructor ->
                constructor.parameterTypes.contentEquals(
                    arrayOf(ProductionBatchImportRepositoryLease::class.java),
                )
            },
        )
        assertEquals(
            setOf("close", "claimRepository"),
            ProductionBatchImportOwner::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
                .mapTo(mutableSetOf()) { method -> method.name },
        )
        val factoryMethods =
            ProductionBatchImportOwnerFactory::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
        assertEquals(1, factoryMethods.size)
        assertEquals("open", factoryMethods.single().name)
        assertEquals(
            listOf(CurrentGenerationBatchImportClaim::class.java),
            factoryMethods.single().parameterTypes.toList(),
        )

        val registry = ProductionBatchImportOwnerRegistry::class.java
        val registration = registry.declaredMethods.single { it.name == "registerBuilt" }
        assertFalse(Modifier.isPublic(registration.modifiers))
        assertFalse(Modifier.isProtected(registration.modifiers))
        val forbiddenInputs =
            setOf(
                Context::class.java,
                String::class.java,
                ModelTaskRepository::class.java,
            )
        listOf(
            CurrentGenerationBatchImportClaim::class.java,
            ProductionBatchImportOwner::class.java,
            ProductionBatchImportOwnerFactory::class.java,
        ).forEach { type ->
            assertFalse(
                "$type accepts caller-controlled production inputs",
                type.declaredMethods
                    .filter { method -> Modifier.isPublic(method.modifiers) }
                    .flatMap { method -> method.parameterTypes.toList() }
                    .any(forbiddenInputs::contains),
            )
        }

        val currentGenerationSurface =
            CurrentGenerationProductionOwnerPorts::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
        assertEquals(
            1,
            currentGenerationSurface.count { method ->
                method.returnType == BatchImportRepository::class.java
            },
        )
        assertFalse(
            currentGenerationSurface.any { method ->
                method.returnType in
                    setOf(
                        ProductionBatchImportOwner::class.java,
                        CurrentGenerationBatchImportClaim::class.java,
                        CurrentGenerationBatchImportConstructionClaim::class.java,
                        CurrentGenerationBatchImportConstructionResources::class.java,
                    )
            },
        )
    }

    private fun batchOwnerFixture(): BatchOwnerFixture {
        val repository = RecordingBatchImportRepository()
        val processingJob = SupervisorJob()
        val sessionOwner = CloseMarker()
        val modelOwner = CloseMarker()
        val claim =
            TestOnlyProductionBatchImportAuthority.issue(
                repository,
                processingJob,
                Any(),
                Any(),
                Any(),
                sessionOwner,
                modelOwner,
            )
        return BatchOwnerFixture(
            claim = claim,
            repository = repository,
            processingJob = processingJob,
            sessionOwner = sessionOwner,
            modelOwner = modelOwner,
        )
    }
}

private data class BatchOwnerFixture(
    val claim: CurrentGenerationBatchImportClaim,
    val repository: RecordingBatchImportRepository,
    val processingJob: Job,
    val sessionOwner: CloseMarker,
    val modelOwner: CloseMarker,
)

private class CloseMarker : AutoCloseable {
    val closeCalls = AtomicInteger(0)

    override fun close() {
        closeCalls.incrementAndGet()
    }
}

private class RecordingBatchImportRepository : BatchImportRepository {
    val observationStarted = CompletableDeferred<Unit>()
    val observationStopped = CompletableDeferred<Unit>()
    val operationStarted = CompletableDeferred<Unit>()
    val operationStopped = CompletableDeferred<Unit>()
    val pauseCalls = AtomicInteger(0)

    override fun observeBatchImports(): Flow<List<BatchImportJob>> =
        flow {
            observationStarted.complete(Unit)
            try {
                while (true) {
                    emit(emptyList())
                    yield()
                }
            } finally {
                observationStopped.complete(Unit)
            }
        }

    override suspend fun createBatchImport(request: CreateBatchImportRequest): BatchImportJob =
        error("Not used")

    override suspend fun createPdfImport(request: CreatePdfImportRequest): BatchImportJob =
        error("Not used")

    override suspend fun pauseBatchImport(jobId: String) {
        pauseCalls.incrementAndGet()
    }

    override suspend fun resumeBatchImport(jobId: String) = Unit

    override suspend fun retryBatchImportPage(
        jobId: String,
        pageIndex: Int,
    ) = Unit

    override suspend fun skipBatchImportPage(
        jobId: String,
        pageIndex: Int,
    ) = Unit

    override suspend fun prepareOrganization(jobId: String): BatchImportOrganizationOffer =
        error("Not used")

    override suspend fun organizeBatch(approval: BatchImportOrganizationApproval) {
        operationStarted.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            operationStopped.complete(Unit)
        }
    }
}
