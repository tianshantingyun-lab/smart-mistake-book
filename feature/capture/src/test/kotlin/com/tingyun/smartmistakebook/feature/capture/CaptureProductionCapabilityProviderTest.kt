package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterManifest
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProductionCapabilityProviderTest {
    @Test
    fun requiredAdaptersCoverCaptureBatchQueueAndApprovedAssetDocuments() {
        assertEquals(
            setOf(
                ProductionAdapter.CAPTURE_WORKFLOW,
                ProductionAdapter.BATCH_IMPORT,
                ProductionAdapter.MODEL_TASK_QUEUE,
                ProductionAdapter.MODEL_ASSET_DOCUMENTS,
            ),
            CaptureProductionCapabilityProvider.REQUIRED_CAPTURE_ADAPTERS,
        )
    }

    @Test
    fun everyRequiredCaptureAdapterBlocksBeforeCapabilityPublication() {
        CaptureProductionCapabilityProvider.REQUIRED_CAPTURE_ADAPTERS.forEach { missing ->
            var sourceReads = 0
            val provider =
                provider(
                    available = ProductionAdapter.entries.toSet() - missing,
                    source = {
                        sourceReads += 1
                        error("must not inspect a partially published graph")
                    },
                )

            assertEquals(
                CaptureProductionCapabilityDecision.Blocked(
                    reason =
                        CaptureProductionCapabilityBlockReason
                            .REQUIRED_ADAPTERS_UNAVAILABLE,
                    missingRequiredAdapters = setOf(missing),
                ),
                provider.resolve(),
            )
            assertEquals(0, sourceReads)
        }
    }

    @Test
    fun globallyIncompleteManifestBlocksBeforeCapabilityPublication() {
        var sourceReads = 0
        val provider =
            provider(
                available =
                    CaptureProductionCapabilityProvider.REQUIRED_CAPTURE_ADAPTERS,
                source = {
                    sourceReads += 1
                    error("must not inspect capabilities before terminal publication")
                },
            )

        assertEquals(
            CaptureProductionCapabilityDecision.Blocked(
                CaptureProductionCapabilityBlockReason.PRODUCTION_MANIFEST_INCOMPLETE,
            ),
            provider.resolve(),
        )
        assertEquals(0, sourceReads)
    }

    @Test
    fun manifestAndPublicationFailuresStayBlocked() {
        val manifestFailure =
            CaptureProductionCapabilityProvider(
                adapterAvailability =
                    ProductionAdapterAvailabilityPort {
                        error("manifest unavailable")
                    },
                capabilitySource =
                    CaptureProductionCapabilitySource {
                        error("must not be reached")
                    },
            )
        assertEquals(
            CaptureProductionCapabilityDecision.Blocked(
                CaptureProductionCapabilityBlockReason.MANIFEST_UNAVAILABLE,
            ),
            manifestFailure.resolve(),
        )

        val publicationFailure =
            provider(
                available = ProductionAdapter.entries.toSet(),
                source = { error("publisher unavailable") },
            )
        assertEquals(
            CaptureProductionCapabilityDecision.Blocked(
                CaptureProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
            ),
            publicationFailure.resolve(),
        )

        val unpublished =
            provider(
                available = ProductionAdapter.entries.toSet(),
                source = { null },
            )
        assertEquals(
            CaptureProductionCapabilityDecision.Blocked(
                CaptureProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
            ),
            unpublished.resolve(),
        )
    }

    @Test
    fun completeManifestPublishesExactRouteFacingRepositoriesWithoutCallingThem() {
        val calls = mutableListOf<String>()
        val capture = recordingProxy<CaptureWorkflowRepository>("capture", calls)
        val batch = recordingProxy<BatchImportRepository>("batch", calls)
        val modelTasks = recordingProxy<ScopedModelTaskPort>("model", calls)
        val capability =
            CaptureProductionCapability(
                captureWorkflow = capture,
                batchImports = batch,
                modelTasks = modelTasks,
            )
        val provider =
            provider(
                available = ProductionAdapter.entries.toSet(),
                source = { capability },
            )

        val available =
            provider.resolve() as CaptureProductionCapabilityDecision.Available

        assertSame(capability, available.capability)
        assertSame(capture, available.capability.captureWorkflow)
        assertSame(batch, available.capability.batchImports)
        assertSame(modelTasks, available.capability.modelTasks)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun capabilityConstructorContainsNoDatabaseOrMasteryAuthority() {
        val parameterTypes =
            CaptureProductionCapability::class.java.constructors
                .single()
                .parameterTypes
                .toSet()

        assertEquals(
            setOf(
                CaptureWorkflowRepository::class.java,
                BatchImportRepository::class.java,
                ScopedModelTaskPort::class.java,
            ),
            parameterTypes,
        )
        assertTrue(
            parameterTypes.none { type ->
                type.name.contains("Database") ||
                    type.name.contains("Mastery") ||
                    type.name.contains("Knowledge")
            },
        )
    }

    private fun provider(
        available: Set<ProductionAdapter>,
        source: () -> CaptureProductionCapability?,
    ) = CaptureProductionCapabilityProvider(
        adapterAvailability =
            ProductionAdapterAvailabilityPort {
                ProductionAdapterManifest.fromAvailable(available)
            },
        capabilitySource = CaptureProductionCapabilitySource(source),
    )
}

private inline fun <reified T : Any> recordingProxy(
    name: String,
    calls: MutableList<String>,
): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "equals" -> proxy === arguments?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "RecordingProxy($name)"
            else -> {
                calls += "$name:${method.name}"
                null
            }
        }
    } as T
