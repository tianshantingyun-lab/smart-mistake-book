package com.tingyun.smartmistakebook.core.data.production

import com.tingyun.smartmistakebook.core.data.authority.CurrentGenerationMistakeCapabilityOwner
import com.tingyun.smartmistakebook.core.data.authority.CurrentGenerationProductionOwnerPorts
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepository
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogState
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionPortFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommandFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewProductionCapability
import com.tingyun.smartmistakebook.core.data.review.DailyReviewRepository
import com.tingyun.smartmistakebook.core.data.review.DailyReviewSessionActionPort
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionPort
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPrivacyRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionCapabilityAssemblyTest {
    @Test
    fun currentGenerationMistakeOwnerRevokesCatalogAndExecutionResolverTogether() {
        val generationIsCurrent = AtomicBoolean(true)
        val catalogCloseCount = AtomicInteger(0)
        val catalog =
            object : StudentMistakeLibraryCatalogRepository {
                override val state =
                    MutableStateFlow<StudentMistakeLibraryCatalogState>(
                        StudentMistakeLibraryCatalogState.WaitingForParity,
                    )

                override fun close() {
                    catalogCloseCount.incrementAndGet()
                }
            }
        val organization = proxy<MistakeOrganizationRepository>()
        val scope = SessionScope("learner-current-generation")
        val sessions = proxy<ProblemOrganizationWorkSessionPort>()
        val modelTasks = proxy<ModelTaskRepository>()
        val catalogJob = SupervisorJob()
        val owner =
            CurrentGenerationMistakeCapabilityOwner(
                mistakeOrganization = organization,
                studentMistakeCatalog = catalog,
                workScope = scope,
                workSessions = sessions,
                modelTasks = modelTasks,
                catalogJob = catalogJob,
                productionGenerationIsCurrent = generationIsCurrent::get,
            )

        val executionResolver =
            owner.workManagerCoordination.currentProblemOrganizationExecutionResolver()
        assertSame(
            executionResolver,
            owner.workManagerCoordination.currentProblemOrganizationExecutionResolver(),
        )
        val scheduling = owner.workManagerCoordination.currentProblemOrganizationScheduling()
        generationIsCurrent.set(false)
        assertThrows(IllegalStateException::class.java) {
            owner.workManagerCoordination.currentProblemOrganizationExecutionResolver()
        }
        assertThrows(IllegalStateException::class.java) {
            owner.workManagerCoordination.currentProblemOrganizationScheduling()
        }
        generationIsCurrent.set(true)

        owner.close()
        owner.close()

        assertThrows(IllegalStateException::class.java) {
            scheduling.observeSchedulable()
        }
        assertEquals(
            null,
            executionResolver.resolve(
                ProductionProblemOrganizationExecutionLease(
                    workId = "work-after-close",
                    stateVersion = 1L,
                    token = "0".repeat(64),
                ),
            ),
        )

        assertEquals(1, catalogCloseCount.get())
        assertTrue(catalogJob.isCancelled)
        assertThrows(IllegalStateException::class.java) {
            owner.workManagerCoordination.currentProblemOrganizationExecutionResolver()
        }
        assertThrows(IllegalStateException::class.java) {
            owner.workManagerCoordination.currentProblemOrganizationScheduling()
        }
    }

    @Test
    fun registryBackedClaimTransfersOnceAndReleasesOneSharedOwner() {
        val fixture = testProductionPublicationFixture()
        val assembly = fixture.assembly()

        val snapshot = assembly.claimForPublication()

        assertSame(fixture.ports.captureWorkflow, snapshot.captureWorkflow)
        assertSame(fixture.ports.batchImport, snapshot.batchImport)
        assertSame(fixture.ports.mistakeDetail, snapshot.mistakeDetail)
        assertSame(fixture.ports.mistakeOrganization, snapshot.mistakeOrganization)
        assertSame(fixture.ports.studentMistakeCatalog, snapshot.studentMistakeCatalog)
        assertSame(
            fixture.ports.tutorCurrentSessionHost,
            snapshot.tutorSession.currentSessionHost,
        )
        assertSame(fixture.ports.tutorConversationLobby, snapshot.tutorConversationLobby)
        assertSame(
            fixture.ports.tutorMasteryContext,
            snapshot.tutorMasteryAndProfile.tutorMasteryContext,
        )
        assertSame(
            fixture.ports.learningMasteryDisplay,
            snapshot.tutorMasteryAndProfile.learningMasteryDisplay,
        )
        assertSame(fixture.ports.tutorTeachingReference, snapshot.tutorTeachingReference)
        assertSame(fixture.ports.modelTaskQueue, snapshot.modelTaskQueue)
        assertSame(fixture.ports.modelAssetDocuments, snapshot.modelAssetDocuments)
        assertSame(fixture.ports.reviewPlanning, snapshot.reviewPlanning.planning)
        assertSame(fixture.ports.reviewSessionActions, snapshot.reviewPlanning.sessionActions)
        assertSame(fixture.ports.reviewPacingActions, snapshot.reviewPlanning.pacingActions)
        assertSame(
            fixture.ports.reviewAnswerSubmissionPorts,
            snapshot.reviewPlanning.answerSubmissionPorts,
        )
        assertSame(
            fixture.ports.reviewAssistanceActions,
            snapshot.reviewPlanning.assistanceActions,
        )
        assertSame(
            fixture.ports.reviewPacingCommandFactory,
            snapshot.reviewPlanning.pacingCommandFactory,
        )
        assertEquals(
            null,
            snapshot.workManagerCoordination.currentProblemOrganizationExecutionResolver(),
        )
        assertEquals(0, fixture.releases.get())

        assertThrows(IllegalStateException::class.java) {
            assembly.claimForPublication()
        }
        assembly.close()
        assertEquals(0, fixture.releases.get())

        snapshot.close()
        snapshot.close()
        assertEquals(1, fixture.releases.get())
        ProductionCapabilitySnapshot::class.java.declaredMethods
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    method.name.startsWith("get")
            }.forEach { getter ->
                val failure =
                    assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                        getter.invoke(snapshot)
                    }
                assertTrue(
                    "${getter.name} remained active after snapshot close",
                    failure.cause is IllegalStateException,
                )
            }
    }

    @Test
    fun tutorSessionCapabilityPublishesOnlyTheCurrentSessionHost() {
        val declaredPublicGetters =
            TutorSessionProductionCapability::class.java.declaredMethods
                .filter { method ->
                    Modifier.isPublic(method.modifiers) &&
                        method.parameterCount == 0 &&
                        method.name.startsWith("get")
                }

        assertEquals(
            listOf(TutorCurrentSessionHostPort::class.java),
            declaredPublicGetters.map { method -> method.returnType },
        )
    }

    @Test
    fun closingAtEveryTransferStageRevokesTheIdentity() {
        val claimFixture = testProductionPublicationFixture()
        val claim = claimFixture.claim()
        claim.close()
        claim.close()
        assertEquals(1, claimFixture.releases.get())
        assertThrows(IllegalStateException::class.java) {
            CompleteProductionCapabilityAssembly.fromCurrentGenerationClaim(claim)
        }

        val assemblyFixture = testProductionPublicationFixture()
        val assembly = assemblyFixture.assembly()
        assembly.close()
        assembly.close()
        assertEquals(1, assemblyFixture.releases.get())
        assertThrows(IllegalStateException::class.java) {
            assembly.claimForPublication()
        }
    }

    @Test
    fun currentGenerationHandoffIsSingleUseAndOwnsRejectedLeases() {
        val fixture = testProductionPublicationFixture()
        val handoff =
            CurrentGenerationProductionPublicationHandoff(
                fixture.leases,
                Any(),
            )
        val claim = ProductionCapabilityPublicationRegistry.issueCurrentGeneration(handoff)

        assertThrows(IllegalStateException::class.java) {
            ProductionCapabilityPublicationRegistry.issueCurrentGeneration(handoff)
        }
        assertEquals(0, fixture.releases.get())

        claim.close()
        assertEquals(1, fixture.releases.get())
    }

    @Test
    fun concurrentClaimHasExactlyOneWinner() {
        val fixture = testProductionPublicationFixture()
        val claim = fixture.claim()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts =
                (0 until 8).map {
                    pool.submit<CompleteProductionCapabilityAssembly?> {
                        start.await()
                        runCatching {
                            CompleteProductionCapabilityAssembly.fromCurrentGenerationClaim(claim)
                        }.getOrNull()
                    }
                }
            start.countDown()
            val winners = attempts.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(1, winners.size)
            winners.single().close()
            assertEquals(1, fixture.releases.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun concurrentAssemblyTransferHasExactlyOneWinner() {
        val fixture = testProductionPublicationFixture()
        val assembly = fixture.assembly()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts =
                (0 until 8).map {
                    pool.submit<ProductionCapabilitySnapshot?> {
                        start.await()
                        runCatching { assembly.claimForPublication() }.getOrNull()
                    }
                }
            start.countDown()
            val winners = attempts.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(1, winners.size)
            winners.single().close()
            assertEquals(1, fixture.releases.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun mixedGenerationBindingCannotBecomeACompleteClaim() {
        val fixture = testProductionPublicationFixture()
        val generation = Any()
        val generations = Array<Any>(PRODUCTION_CAPABILITY_SLOT_COUNT) { generation }
        generations[generations.lastIndex] = Any()
        val sameContext = Any()
        val sameLearner = Any()
        val sameKnowledge = Any()
        val sameTerminalFence = Any()

        assertThrows(IllegalArgumentException::class.java) {
            TestOnlyProductionPublicationAuthority.issueWithBindings(
                fixture.leases,
                generations,
                Array(PRODUCTION_CAPABILITY_SLOT_COUNT) { sameContext },
                Array(PRODUCTION_CAPABILITY_SLOT_COUNT) { sameLearner },
                Array(PRODUCTION_CAPABILITY_SLOT_COUNT) { sameKnowledge },
                Array(PRODUCTION_CAPABILITY_SLOT_COUNT) { sameTerminalFence },
            )
        }
        fixture.leases.closeReverse()
        assertEquals(1, fixture.releases.get())
    }

    @Test
    fun reflectivelyConstructedShellsHaveNoRegistryAuthority() {
        val forgedClaim =
            instantiatePrivateNested(
                CompleteProductionOwnerPortClaim::class.java,
                "Issued",
            ) as CompleteProductionOwnerPortClaim
        assertThrows(IllegalStateException::class.java) {
            CompleteProductionCapabilityAssembly.fromCurrentGenerationClaim(forgedClaim)
        }

        val forgedAssembly =
            instantiatePrivateNested(
                CompleteProductionCapabilityAssembly::class.java,
                "Issued",
            ) as CompleteProductionCapabilityAssembly
        assertThrows(IllegalStateException::class.java) {
            forgedAssembly.claimForPublication()
        }

        val forgedSnapshot =
            instantiatePrivateNested(
                ProductionCapabilitySnapshot::class.java,
                "Issued",
            ) as ProductionCapabilitySnapshot
        assertThrows(IllegalStateException::class.java) {
            forgedSnapshot.terminalCutoverGate
        }
    }

    @Test
    fun releaseSurfaceHasNoCallerSuppliedPortIssuer() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName(
                "com.tingyun.smartmistakebook.core.data.production." +
                    "CompleteProductionCapabilityPorts",
            )
        }
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName(
                "com.tingyun.smartmistakebook.core.data.production." +
                    "ProductionCapabilityAssemblyOwner",
            )
        }
        val releaseMethods =
            CompleteProductionCapabilityAssembly::class.java.declaredMethods.toList() +
                CompleteProductionOwnerPortClaim::class.java.declaredMethods.toList()
        assertFalse(releaseMethods.any { it.name.contains("issue", ignoreCase = true) })
        assertFalse(
            releaseMethods.any { method ->
                method.parameterTypes.any { type ->
                    type.name.contains("CompleteProductionCapabilityPorts") ||
                        type == IssuedProductionCapabilityLeases::class.java
                }
            },
        )
        val releaseIssuer =
            ProductionCapabilityPublicationRegistry::class.java.declaredMethods
                .single { method -> method.name == "issueCurrentGeneration" }
        assertFalse(Modifier.isPublic(releaseIssuer.modifiers))
        assertEquals(1, releaseIssuer.parameterCount)
        assertEquals(
            CurrentGenerationProductionPublicationHandoff::class.java,
            releaseIssuer.parameterTypes.single(),
        )
        assertFalse(
            CurrentGenerationProductionOwnerPorts::class.java.declaredMethods.any { method ->
                method.returnType == CompleteProductionOwnerPortClaim::class.java ||
                    method.returnType == CompleteProductionCapabilityAssembly::class.java
            },
        )
    }

    @Test
    fun publicPublicationTypesCannotBeConstructedOrSubclassedByAppCode() {
        val publicationTypes =
            listOf(
                CompleteProductionOwnerPortClaim::class.java,
                CompleteProductionCapabilityAssembly::class.java,
                ProductionCapabilitySnapshot::class.java,
                TutorMasteryAndProfileProductionCapability::class.java,
                TutorSessionProductionCapability::class.java,
                ReviewPlanningProductionCapability::class.java,
                ProductionWorkManagerCoordination::class.java,
                ProductionProblemOrganizationExecutionResolver::class.java,
                ProductionProblemOrganizationExecution::class.java,
                ProductionProblemOrganizationScheduling::class.java,
                ProductionTerminalCutoverCapability::class.java,
            )

        publicationTypes.forEach { type ->
            assertTrue("${type.simpleName} must remain abstract", Modifier.isAbstract(type.modifiers))
            assertTrue(
                "${type.simpleName} exposes a public constructor",
                type.constructors.isEmpty(),
            )
            assertFalse(
                "${type.simpleName} exposes a non-private declared constructor",
                type.declaredConstructors.any { constructor ->
                    !Modifier.isPrivate(constructor.modifiers) &&
                        !constructor.isSynthetic
                },
            )
        }
    }

    @Test
    fun publicSnapshotApiCarriesOnlyTheInventoriedNarrowSlots() {
        val getters =
            ProductionCapabilitySnapshot::class.java.declaredMethods
                .filter { method ->
                    Modifier.isPublic(method.modifiers) &&
                        !method.isSynthetic &&
                        method.parameterCount == 0 &&
                        method.name.startsWith("get")
                }
        assertEquals(PRODUCTION_CAPABILITY_SLOT_COUNT, getters.size)

        val forbidden =
            listOf(
                "database",
                "dao",
                "sql",
                "learnerid",
                "generation",
                "fingerprint",
                "manifest",
                "witness",
                "proof",
                "runtime",
                "owner",
                "fallback",
                "tutorinteractionrepository",
                "tutorlearningmemoryrepository",
            )
        getters.forEach { method ->
            val signature = "${method.name}:${method.returnType.name}".lowercase()
            assertTrue(
                "${method.name} leaks an authority identifier",
                forbidden.none(signature::contains),
            )
        }
    }
}

internal data class TestProductionCapabilityPorts(
    val captureWorkflow: CaptureWorkflowRepository = proxy(),
    val batchImport: BatchImportRepository = proxy(),
    val mistakeDetail: MistakeDetailRepository = proxy(),
    val mistakeOrganization: MistakeOrganizationRepository = proxy(),
    val studentMistakeCatalog: StudentMistakeLibraryCatalogRepository = proxy(),
    val tutorCurrentSessionHost: TutorCurrentSessionHostPort = proxy(),
    val tutorConversationLobby: TutorConversationLobbyPort = proxy(),
    val tutorMasteryContext: TutorMasteryContextRepository = proxy(),
    val learningMasteryDisplay: LearningMasteryDisplayRepository = proxy(),
    val learningMasteryPrivacy: LearningMasteryPrivacyRepository = proxy(),
    val tutorTeachingReference: TutorTeachingReferenceRepository = proxy(),
    val modelTaskQueue: ModelTaskRepository = proxy(),
    val modelAssetDocuments: RestrictedModelAssetSource = proxy(),
    val reviewPlanning: DailyReviewProductionCapability =
        DailyReviewProductionCapability(proxy<DailyReviewRepository>()),
    val reviewSessionActions: DailyReviewSessionActionPort = proxy(),
    val reviewPacingActions: DailyReviewPacingActionPort = proxy(),
    val reviewAnswerSubmissionPorts: DailyReviewAnswerSubmissionPortFactory = proxy(),
    val reviewAssistanceActions: DailyReviewAssistanceActionPort = proxy(),
    val reviewPacingCommandFactory: DailyReviewPacingCommandFactory = proxy(),
)

internal data class TestProductionPublicationFixture(
    val ports: TestProductionCapabilityPorts,
    val leases: IssuedProductionCapabilityLeases,
    val releases: AtomicInteger,
) {
    fun claim(): CompleteProductionOwnerPortClaim =
        TestOnlyProductionPublicationAuthority.issue(
            leases,
            Any(),
            Any(),
            Any(),
            Any(),
            Any(),
        )

    fun assembly(): CompleteProductionCapabilityAssembly =
        CompleteProductionCapabilityAssembly.fromCurrentGenerationClaim(claim())
}

internal fun testProductionPublicationFixture(
    release: () -> Unit = {},
): TestProductionPublicationFixture {
    val ports = TestProductionCapabilityPorts()
    val releases = AtomicInteger(0)
    val sharedOwner =
        SharedProductionCapabilityOwner(PRODUCTION_CAPABILITY_SLOT_COUNT) {
            releases.incrementAndGet()
            release()
        }
    val leases =
        IssuedProductionCapabilityLeases(
            captureWorkflow = sharedOwner.lease(ports.captureWorkflow),
            batchImport = sharedOwner.lease(ports.batchImport),
            mistakeDetail = sharedOwner.lease(ports.mistakeDetail),
            mistakeOrganization = sharedOwner.lease(ports.mistakeOrganization),
            studentMistakeCatalog = sharedOwner.lease(ports.studentMistakeCatalog),
            tutorSession =
                sharedOwner.lease(
                    TutorSessionProductionCapability.issue(
                        ports.tutorCurrentSessionHost,
                    ),
                ),
            tutorConversationLobby = sharedOwner.lease(ports.tutorConversationLobby),
            tutorMasteryAndProfile =
                sharedOwner.lease(
                    TutorMasteryAndProfileProductionCapability.issue(
                        ports.tutorMasteryContext,
                        ports.learningMasteryDisplay,
                        ports.learningMasteryPrivacy,
                    ),
                ),
            tutorTeachingReference = sharedOwner.lease(ports.tutorTeachingReference),
            modelTaskQueue = sharedOwner.lease(ports.modelTaskQueue),
            modelAssetDocuments = sharedOwner.lease(ports.modelAssetDocuments),
            reviewPlanning =
                sharedOwner.lease(
                    ReviewPlanningProductionCapability.issue(
                        ports.reviewPlanning,
                        ports.reviewSessionActions,
                        ports.reviewPacingActions,
                        ports.reviewAnswerSubmissionPorts,
                        ports.reviewAssistanceActions,
                        ports.reviewPacingCommandFactory,
                    ),
                ),
            workManagerCoordination =
                sharedOwner.lease(
                    ProductionWorkManagerCoordination.issue(
                        { null },
                        { null },
                    ),
                ),
            terminalCutoverGate =
                sharedOwner.lease(ProductionTerminalCutoverCapability.issue()),
        )
    return TestProductionPublicationFixture(ports, leases, releases)
}

internal fun testCompleteProductionCapabilityAssembly(
    release: () -> Unit = {},
): CompleteProductionCapabilityAssembly =
    testProductionPublicationFixture(release).assembly()

private fun instantiatePrivateNested(
    enclosing: Class<*>,
    simpleName: String,
): Any {
    val nested = enclosing.declaredClasses.single { it.simpleName == simpleName }
    return nested.getDeclaredConstructor().run {
        isAccessible = true
        newInstance()
    }
}

private inline fun <reified Port : Any> proxy(): Port {
    val type = Port::class.java
    require(type.isInterface) { "${type.name} must remain a narrow interface" }
    return type.cast(
        Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "test-${type.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        },
    )
}

private fun defaultValue(type: Class<*>): Any? =
    when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
