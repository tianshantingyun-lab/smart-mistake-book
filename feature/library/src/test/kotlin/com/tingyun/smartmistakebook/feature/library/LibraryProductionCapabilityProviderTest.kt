package com.tingyun.smartmistakebook.feature.library

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterManifest
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepository
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryProductionCapabilityProviderTest {
    @Test
    fun `required adapters cover every library route dependency`() {
        assertEquals(
            setOf(
                ProductionAdapter.MISTAKE_DETAIL,
                ProductionAdapter.MISTAKE_ORGANIZATION,
                ProductionAdapter.STUDENT_MISTAKE_CATALOG,
                ProductionAdapter.BATCH_IMPORT,
                ProductionAdapter.MODEL_TASK_QUEUE,
                ProductionAdapter.MODEL_ASSET_DOCUMENTS,
            ),
            LibraryProductionCapabilityProvider.REQUIRED_LIBRARY_ADAPTERS,
        )
    }

    @Test
    fun `missing route adapter blocks before capability publication`() {
        var sourceReads = 0
        val missing = ProductionAdapter.MISTAKE_ORGANIZATION
        val provider =
            provider(
                available = ProductionAdapter.entries.toSet() - missing,
                source = {
                    sourceReads += 1
                    capability()
                },
            )

        val decision = provider.resolve()

        assertTrue(decision is LibraryProductionCapabilityDecision.Blocked)
        decision as LibraryProductionCapabilityDecision.Blocked
        assertEquals(
            LibraryProductionCapabilityBlockReason.REQUIRED_ADAPTERS_UNAVAILABLE,
            decision.reason,
        )
        assertEquals(setOf(missing), decision.missingRequiredAdapters)
        assertEquals(0, sourceReads)
    }

    @Test
    fun `incomplete global manifest blocks before capability publication`() {
        var sourceReads = 0
        val nonLibraryAdapter = ProductionAdapter.TUTOR_SESSION
        check(nonLibraryAdapter !in LibraryProductionCapabilityProvider.REQUIRED_LIBRARY_ADAPTERS)
        val provider =
            provider(
                available = ProductionAdapter.entries.toSet() - nonLibraryAdapter,
                source = {
                    sourceReads += 1
                    capability()
                },
            )

        val decision = provider.resolve()

        assertEquals(
            LibraryProductionCapabilityDecision.Blocked(
                LibraryProductionCapabilityBlockReason.PRODUCTION_MANIFEST_INCOMPLETE,
            ),
            decision,
        )
        assertEquals(0, sourceReads)
    }

    @Test
    fun `manifest failure blocks without consulting publication`() {
        var sourceReads = 0
        val provider =
            LibraryProductionCapabilityProvider(
                adapterAvailability =
                    ProductionAdapterAvailabilityPort {
                        error("manifest unavailable")
                    },
                capabilitySource =
                    LibraryProductionCapabilitySource {
                        sourceReads += 1
                        capability()
                    },
            )

        assertEquals(
            LibraryProductionCapabilityDecision.Blocked(
                LibraryProductionCapabilityBlockReason.MANIFEST_UNAVAILABLE,
            ),
            provider.resolve(),
        )
        assertEquals(0, sourceReads)
    }

    @Test
    fun `unpublished or exceptional capability blocks without fallback`() {
        val complete = ProductionAdapter.entries.toSet()
        listOf<LibraryProductionCapabilitySource>(
            LibraryProductionCapabilitySource { null },
            LibraryProductionCapabilitySource { error("publication failed") },
        ).forEach { source ->
            val decision =
                provider(
                    available = complete,
                    source = source::currentCapability,
                ).resolve()

            assertEquals(
                LibraryProductionCapabilityDecision.Blocked(
                    LibraryProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
                ),
                decision,
            )
        }
    }

    @Test
    fun `complete manifest returns the exact published capability`() {
        val capability = capability()
        val decision =
            provider(
                available = ProductionAdapter.entries.toSet(),
                source = { capability },
            ).resolve()

        assertTrue(decision is LibraryProductionCapabilityDecision.Available)
        assertSame(
            capability,
            (decision as LibraryProductionCapabilityDecision.Available).capability,
        )
    }

    private fun provider(
        available: Set<ProductionAdapter>,
        source: () -> LibraryProductionCapability?,
    ): LibraryProductionCapabilityProvider =
        LibraryProductionCapabilityProvider(
            adapterAvailability =
                ProductionAdapterAvailabilityPort {
                    ProductionAdapterManifest.fromAvailable(available)
                },
            capabilitySource = LibraryProductionCapabilitySource(source),
        )

    private fun capability(): LibraryProductionCapability =
        LibraryProductionCapability(
            catalog = interfaceStub(StudentMistakeLibraryCatalogRepository::class.java),
            mistakeDetails = interfaceStub(MistakeDetailRepository::class.java),
            organization = interfaceStub(MistakeOrganizationRepository::class.java),
            batchImports = interfaceStub(BatchImportRepository::class.java),
            modelTasks = interfaceStub(ModelTaskRepository::class.java),
        )

    private fun <T> interfaceStub(type: Class<T>): T {
        val instance =
            Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
            ) { _, method, _ ->
                when (method.name) {
                    "toString" -> "unused-${type.simpleName}"
                    "hashCode" -> System.identityHashCode(type)
                    "equals" -> false
                    else -> error("${type.simpleName}.${method.name} must not be accessed")
                }
            }
        return type.cast(instance)
    }
}
