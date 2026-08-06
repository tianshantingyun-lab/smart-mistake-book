package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftSessionPort
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionPort
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.domain.BatchImportJob
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationApproval
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationOffer
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CreateBatchImportRequest
import com.tingyun.smartmistakebook.core.domain.CreatePdfImportRequest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionBatchImportAuthorityIsolationTest {
    @Test
    fun constructionClaimRawPermissionAndFinalClaimAreIndependentOneShotHandles() = runBlocking {
        val fixture = TestOnlyBatchImportConstructionAuthority.issue("learner-one-shot")
        val resources = fixture.claim().claimForConstruction()
        assertThrows(IllegalStateException::class.java) { fixture.claim().claimForConstruction() }

        resources.claimForRawConstruction()
        assertThrows(IllegalStateException::class.java) { resources.claimForRawConstruction() }

        val repository = TaggedBatchImportRepository()
        val processingJob = SupervisorJob()
        val finalClaim = resources.registerBuilt(repository, processingJob, fixture.identities())
        val rejectedReplayJob = SupervisorJob()
        assertThrows(IllegalStateException::class.java) {
            resources.registerBuilt(repository, rejectedReplayJob, fixture.identities())
        }
        assertTrue(rejectedReplayJob.isCancelled)

        val owner = ProductionBatchImportOwnerFactory.open(finalClaim)
        assertThrows(IllegalStateException::class.java) {
            ProductionBatchImportOwnerFactory.open(finalClaim)
        }
        val lease = owner.claimRepository()
        assertThrows(IllegalStateException::class.java) { owner.claimRepository() }
        lease.pauseBatchImport("one-shot-job")
        assertEquals(listOf("one-shot-job"), repository.pausedJobIds.toList())

        owner.close()
        resources.close()
        fixture.claim().close()
        assertTrue(processingJob.isCancelled)
    }

    @Test
    fun forgedOrUnclaimedConstructionResourcesCannotRegisterARepository() {
        val forgedResources =
            Class.forName(CurrentGenerationBatchImportConstructionResources::class.java.name + "\$Issued")
                .declaredConstructors.single()
                .apply { isAccessible = true }
                .newInstance() as CurrentGenerationBatchImportConstructionResources
        val forgedJob = SupervisorJob()
        assertThrows(IllegalStateException::class.java) { forgedResources.claimForRawConstruction() }
        assertThrows(IllegalStateException::class.java) {
            forgedResources.registerBuilt(
                TaggedBatchImportRepository(),
                forgedJob,
                Any(),
                Any(),
                Any(),
                Any(),
                Any(),
            )
        }
        assertTrue(forgedJob.isCancelled)

        val fixture = TestOnlyBatchImportConstructionAuthority.issue("learner-no-bypass")
        val resources = fixture.claim().claimForConstruction()
        val unclaimedRawPermissionJob = SupervisorJob()
        assertThrows(IllegalStateException::class.java) {
            resources.registerBuilt(
                TaggedBatchImportRepository(),
                unclaimedRawPermissionJob,
                fixture.identities(),
            )
        }
        assertTrue(unclaimedRawPermissionJob.isCancelled)
        assertThrows(IllegalStateException::class.java) { resources.claimForRawConstruction() }
        resources.close()
        fixture.claim().close()
    }

    @Test
    fun constructionRejectsCrossLearnerAndForeignCapabilityObjects() {
        assertThrows(IllegalArgumentException::class.java) {
            TestOnlyBatchImportConstructionAuthority.issueWithCaptureLearner(
                "session-learner",
                "other-learner",
            )
        }

        repeat(5) { foreignArgumentIndex ->
            val expected = TestOnlyBatchImportConstructionAuthority.issue("learner-capability")
            val foreign = TestOnlyBatchImportConstructionAuthority.issue("learner-capability")
            val expectedResources = expected.claim().claimForConstruction()
            val foreignResources = foreign.claim().claimForConstruction()
            val processingJob = SupervisorJob()
            val processingScope = CoroutineScope(processingJob + Dispatchers.Unconfined)
            try {
                val arguments =
                    arrayOf(
                        expectedResources,
                        if (foreignArgumentIndex == 0) {
                            foreignResources.canonicalContext
                        } else {
                            expectedResources.canonicalContext
                        },
                        if (foreignArgumentIndex == 1) {
                            foreignResources.sessionScope
                        } else {
                            expectedResources.sessionScope
                        },
                        if (foreignArgumentIndex == 2) {
                            foreignResources.batchSessions
                        } else {
                            expectedResources.batchSessions
                        },
                        if (foreignArgumentIndex == 3) {
                            foreignResources.captureDrafts
                        } else {
                            expectedResources.captureDrafts
                        },
                        processingScope,
                        if (foreignArgumentIndex == 4) {
                            foreignResources.modelTaskQueue
                        } else {
                            expectedResources.modelTaskQueue
                        },
                    )
                val failure =
                    assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                        rawRepositoryConstructor().newInstance(*arguments)
                    }
                assertTrue(
                    "Foreign capability $foreignArgumentIndex must fail before repository use",
                    failure.targetException is IllegalStateException,
                )
            } finally {
                processingScope.cancel()
                expectedResources.close()
                foreignResources.close()
                expected.claim().close()
                foreign.claim().close()
            }
        }
    }

    @Test
    fun everyAuthorityIdentityMismatchFailsClosed() {
        val mismatches =
            listOf<(TestOnlyBatchImportConstructionAuthority.Fixture) -> BatchImportIdentities>(
                { it.identities().copy(generation = Any()) },
                { it.identities().copy(context = Any()) },
                { it.identities().copy(learner = Any()) },
                { it.identities().copy(sessionOwner = Any()) },
                { it.identities().copy(modelOwner = Any()) },
            )

        mismatches.forEachIndexed { index, mismatch ->
            val fixture =
                TestOnlyBatchImportConstructionAuthority.issue("learner-mismatch-$index")
            val resources = fixture.claim().claimForConstruction()
            resources.claimForRawConstruction()
            val processingJob = SupervisorJob()
            assertThrows(IllegalStateException::class.java) {
                resources.registerBuilt(
                    TaggedBatchImportRepository(),
                    processingJob,
                    mismatch(fixture),
                )
            }
            assertTrue("Mismatch $index must cancel owned work", processingJob.isCancelled)
            resources.close()
            fixture.claim().close()
        }
    }

    @Test
    fun concurrentClaimsHaveOneWinnerPerOwnerAndNeverCrossRepositories() {
        val repositoryA = TaggedBatchImportRepository()
        val repositoryB = TaggedBatchImportRepository()
        val jobA = SupervisorJob()
        val jobB = SupervisorJob()
        val claimA =
            TestOnlyProductionBatchImportAuthority.issue(
                repositoryA,
                jobA,
                Any(),
                Any(),
                Any(),
                Any(),
                Any(),
            )
        val claimB =
            TestOnlyProductionBatchImportAuthority.issue(
                repositoryB,
                jobB,
                Any(),
                Any(),
                Any(),
                Any(),
                Any(),
            )
        val start = CountDownLatch(1)
        val successesA = AtomicInteger(0)
        val successesB = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val tasks =
                (0 until 64).map { index ->
                    pool.submit {
                        start.await()
                        val isA = index % 2 == 0
                        val claim = if (isA) claimA else claimB
                        try {
                            ProductionBatchImportOwnerFactory.open(claim).use { owner ->
                                val leasedRepository = owner.claimRepository()
                                runBlocking {
                                    leasedRepository.pauseBatchImport(
                                        if (isA) "owner-a-job" else "owner-b-job",
                                    )
                                }
                                if (isA) successesA.incrementAndGet()
                                else successesB.incrementAndGet()
                            }
                        } catch (_: IllegalStateException) {
                            Unit
                        }
                    }
                }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, successesA.get())
            assertEquals(1, successesB.get())
            assertEquals(listOf("owner-a-job"), repositoryA.pausedJobIds.toList())
            assertEquals(listOf("owner-b-job"), repositoryB.pausedJobIds.toList())
            assertTrue(jobA.isCancelled)
            assertTrue(jobB.isCancelled)
        } finally {
            start.countDown()
            claimA.close()
            claimB.close()
            pool.shutdownNow()
        }
    }
}

private fun rawRepositoryConstructor() =
    Class.forName(
        "com.tingyun.smartmistakebook.core.data.capture.ProductionBatchImportRepository",
    ).getDeclaredConstructor(
        CurrentGenerationBatchImportConstructionResources::class.java,
        android.content.Context::class.java,
        SessionScope::class.java,
        BatchImportSessionPort::class.java,
        CaptureDraftSessionPort::class.java,
        CoroutineScope::class.java,
        com.tingyun.smartmistakebook.core.domain.ModelTaskRepository::class.java,
    ).apply { isAccessible = true }

private data class BatchImportIdentities(
    val generation: Any,
    val context: Any,
    val learner: Any,
    val sessionOwner: Any,
    val modelOwner: Any,
)

private fun TestOnlyBatchImportConstructionAuthority.Fixture.identities() =
    BatchImportIdentities(
        generation = generationIdentity(),
        context = contextIdentity(),
        learner = learnerIdentity(),
        sessionOwner = sessionOwnerIdentity(),
        modelOwner = modelOwnerIdentity(),
    )

private fun CurrentGenerationBatchImportConstructionResources.registerBuilt(
    repository: BatchImportRepository,
    processingJob: Job,
    identities: BatchImportIdentities,
): CurrentGenerationBatchImportClaim =
    registerBuilt(
        repository,
        processingJob,
        identities.generation,
        identities.context,
        identities.learner,
        identities.sessionOwner,
        identities.modelOwner,
    )

private class TaggedBatchImportRepository : BatchImportRepository {
    val pausedJobIds = ConcurrentLinkedQueue<String>()

    override fun observeBatchImports(): Flow<List<BatchImportJob>> = flowOf(emptyList())

    override suspend fun createBatchImport(request: CreateBatchImportRequest): BatchImportJob =
        error("Not used")

    override suspend fun createPdfImport(request: CreatePdfImportRequest): BatchImportJob =
        error("Not used")

    override suspend fun pauseBatchImport(jobId: String) {
        pausedJobIds += jobId
    }

    override suspend fun resumeBatchImport(jobId: String) = Unit

    override suspend fun retryBatchImportPage(jobId: String, pageIndex: Int) = Unit

    override suspend fun skipBatchImportPage(jobId: String, pageIndex: Int) = Unit

    override suspend fun prepareOrganization(jobId: String): BatchImportOrganizationOffer =
        error("Not used")

    override suspend fun organizeBatch(approval: BatchImportOrganizationApproval) = Unit
}
