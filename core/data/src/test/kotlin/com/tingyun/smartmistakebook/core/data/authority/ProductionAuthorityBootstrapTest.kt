package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.data.production.ProductionCapabilitySnapshot
import com.tingyun.smartmistakebook.core.data.production.testCompleteProductionCapabilityAssembly
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionAuthorityBootstrapTest {
    @Test
    fun missingAdapterBlocksBeforeGateOrPublication() = runBlocking {
        var gateCalled = false
        var publicationCalled = false
        val manifest =
            ProductionAdapterManifest.fromAvailable(
                ProductionAdapter.entries.toSet() - ProductionAdapter.TUTOR_SESSION,
            )
        val bootstrap =
            ProductionAuthorityBootstrap(
                adapterAvailability = ProductionAdapterAvailabilityPort { manifest },
                publicationPreparer =
                    ProductionAuthorityPublicationPreparer {
                        gateCalled = true
                        completePreparation()
                    },
                publishCapabilities = { publicationCalled = true },
            )

        bootstrap.start()

        assertEquals(
            ProductionAuthorityStartupState.Blocked(
                code = ProductionAuthorityStartupBlockCode.MISSING_PRODUCTION_ADAPTERS,
                missingAdapters = setOf(ProductionAdapter.TUTOR_SESSION),
            ),
            bootstrap.state.value,
        )
        assertFalse(gateCalled)
        assertFalse(publicationCalled)
    }

    @Test
    fun completeManifestPublishesOnlyAfterVerifiedGate() = runBlocking {
        val calls = mutableListOf<String>()
        var published: ProductionCapabilitySnapshot? = null
        val bootstrap =
            ProductionAuthorityBootstrap(
                adapterAvailability = completeAvailability(),
                publicationPreparer =
                    ProductionAuthorityPublicationPreparer {
                        calls += "gate"
                        completePreparation()
                    },
                publishCapabilities = { assembly ->
                    calls += "publish"
                    published = assembly.claimForPublication()
                },
            )

        bootstrap.start()

        assertEquals(listOf("gate", "publish"), calls)
        assertEquals(ProductionAuthorityStartupState.Ready, bootstrap.state.value)
        requireNotNull(published).close()
    }

    @Test
    fun blockedGateNeverPublishesCapabilities() = runBlocking {
        var publicationCalled = false
        val bootstrap =
            ProductionAuthorityBootstrap(
                adapterAvailability = completeAvailability(),
                publicationPreparer =
                    ProductionAuthorityPublicationPreparer {
                        ProductionAuthorityPublicationPreparation.Blocked(
                            ProductionAuthorityStartupBlockCode
                                .TERMINAL_PROOF_INCOMPLETE,
                        )
                    },
                publishCapabilities = { publicationCalled = true },
            )

        bootstrap.start()

        assertEquals(
            ProductionAuthorityStartupState.Blocked(
                ProductionAuthorityStartupBlockCode.TERMINAL_PROOF_INCOMPLETE,
            ),
            bootstrap.state.value,
        )
        assertFalse(publicationCalled)
    }

    @Test
    fun publicationFailureCannotExposeReady() = runBlocking {
        val releases = AtomicInteger(0)
        val bootstrap =
            ProductionAuthorityBootstrap(
                adapterAvailability = completeAvailability(),
                publicationPreparer =
                    ProductionAuthorityPublicationPreparer {
                        completePreparation {
                            releases.incrementAndGet()
                        }
                    },
                publishCapabilities = { error("adapter construction failed") },
            )

        bootstrap.start()

        assertEquals(
            ProductionAuthorityStartupState.Blocked(
                ProductionAuthorityStartupBlockCode.CAPABILITY_PUBLICATION_FAILED,
            ),
            bootstrap.state.value,
        )
        assertEquals(1, releases.get())
    }

    @Test
    fun gateFailureBecomesBlockedAndNeverPublishes() = runBlocking {
        var publicationCalled = false
        val bootstrap =
            ProductionAuthorityBootstrap(
                adapterAvailability = completeAvailability(),
                publicationPreparer =
                    ProductionAuthorityPublicationPreparer {
                        error("proof storage unavailable")
                    },
                publishCapabilities = { publicationCalled = true },
            )

        bootstrap.start()

        assertEquals(
            ProductionAuthorityStartupState.Blocked(
                ProductionAuthorityStartupBlockCode.TERMINAL_PROOF_INCOMPLETE,
            ),
            bootstrap.state.value,
        )
        assertFalse(publicationCalled)
    }

    @Test
    fun failClosedFactoryCannotBecomeReadyWithACompleteManifest() = runBlocking {
        var publicationCalled = false
        val bootstrap =
            ProductionAuthorityBootstrapFactory.failClosed(
                adapterAvailability = completeAvailability(),
                publishCapabilities = { publicationCalled = true },
            )

        bootstrap.start()

        assertEquals(
            ProductionAuthorityStartupState.Blocked(
                ProductionAuthorityStartupBlockCode.PRODUCTION_GATE_UNAVAILABLE,
            ),
            bootstrap.state.value,
        )
        assertFalse(publicationCalled)
    }

    @Test
    fun manifestMakesNewOrMissingAdaptersFailClosed() {
        val manifest =
            ProductionAdapterManifest.fromAvailable(
                setOf(
                    ProductionAdapter.MISTAKE_DETAIL,
                    ProductionAdapter.STUDENT_MISTAKE_CATALOG,
                ),
            )

        assertFalse(manifest.isComplete)
        assertTrue(ProductionAdapter.CAPTURE_WORKFLOW in manifest.missingAdapters)
        assertTrue(ProductionAdapter.TERMINAL_CUTOVER_GATE in manifest.missingAdapters)
    }

    private fun completeAvailability(): ProductionAdapterAvailabilityPort =
        ProductionAdapterAvailabilityPort {
            ProductionAdapterManifest.fromAvailable(ProductionAdapter.entries.toSet())
        }

    private fun completePreparation(
        release: () -> Unit = {},
    ): ProductionAuthorityPublicationPreparation.Complete =
        ProductionAuthorityPublicationPreparation.Complete(
            testCompleteProductionCapabilityAssembly(release),
        )
}
