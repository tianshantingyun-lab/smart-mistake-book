package com.tingyun.smartmistakebook.feature.profile

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterManifest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileProductionCapabilityProviderTest {
    @Test
    fun profileRequiresOnlyTheExistingMasteryContextAdapter() {
        assertEquals(
            setOf(ProductionAdapter.TUTOR_MASTERY_CONTEXT),
            ProfileProductionCapabilityProvider.REQUIRED_PROFILE_ADAPTERS,
        )
        assertFalse(
            ProductionAdapter.TUTOR_TEACHING_REFERENCE in
                ProfileProductionCapabilityProvider.REQUIRED_PROFILE_ADAPTERS,
        )
    }

    @Test
    fun missingMasteryAdapterBlocksBeforePublishedCapabilityIsRead() {
        var sourceReads = 0
        val provider =
            provider(
                available =
                    ProductionAdapter.entries.toSet() -
                        ProductionAdapter.TUTOR_MASTERY_CONTEXT,
                source = {
                    sourceReads += 1
                    error("must not inspect a partial publication")
                },
            )

        assertEquals(
            ProfileProductionCapabilityDecision.Blocked(
                reason =
                    ProfileProductionCapabilityBlockReason
                        .REQUIRED_ADAPTERS_UNAVAILABLE,
                missingRequiredAdapters =
                    setOf(ProductionAdapter.TUTOR_MASTERY_CONTEXT),
            ),
            provider.resolve(),
        )
        assertEquals(0, sourceReads)
    }

    @Test
    fun globallyIncompleteManifestBlocksBeforePublishedCapabilityIsRead() {
        var sourceReads = 0
        val provider =
            provider(
                available =
                    ProfileProductionCapabilityProvider.REQUIRED_PROFILE_ADAPTERS,
                source = {
                    sourceReads += 1
                    error("must not inspect capabilities before global publication")
                },
            )

        assertEquals(
            ProfileProductionCapabilityDecision.Blocked(
                ProfileProductionCapabilityBlockReason.PRODUCTION_MANIFEST_INCOMPLETE,
            ),
            provider.resolve(),
        )
        assertEquals(0, sourceReads)
    }

    @Test
    fun manifestAndPublicationFailuresStayBlocked() {
        val manifestFailure =
            ProfileProductionCapabilityProvider(
                adapterAvailability =
                    ProductionAdapterAvailabilityPort {
                        error("manifest unavailable")
                    },
                capabilitySource =
                    ProfileProductionCapabilitySource {
                        error("must not be reached")
                    },
            )
        assertEquals(
            ProfileProductionCapabilityDecision.Blocked(
                ProfileProductionCapabilityBlockReason.MANIFEST_UNAVAILABLE,
            ),
            manifestFailure.resolve(),
        )

        val sourceFailure =
            provider(
                available = ProductionAdapter.entries.toSet(),
                source = { error("publication failed") },
            )
        assertEquals(
            ProfileProductionCapabilityDecision.Blocked(
                ProfileProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
            ),
            sourceFailure.resolve(),
        )

        val unpublished =
            provider(
                available = ProductionAdapter.entries.toSet(),
                source = { null },
            )
        assertEquals(
            ProfileProductionCapabilityDecision.Blocked(
                ProfileProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
            ),
            unpublished.resolve(),
        )
    }

    @Test
    fun completeManifestPublishesOnlyTheDisplayRepositoryWithoutReadingIt() {
        val repositoryCalls = mutableListOf<String>()
        val repository =
            recordingProxy<LearningMasteryDisplayRepository>(
                "mastery-display",
                repositoryCalls,
            )
        val capability = ProfileProductionCapability(repository)
        val provider =
            provider(
                available = ProductionAdapter.entries.toSet(),
                source = { capability },
            )

        val available =
            provider.resolve() as ProfileProductionCapabilityDecision.Available

        assertSame(capability, available.capability)
        assertSame(repository, available.capability.learningMasteryDisplay)
        assertTrue(repositoryCalls.isEmpty())
    }

    @Test
    fun publicCapabilityAcceptsOnlyTheOrdinaryLanguageDisplayRepository() {
        val parameterTypes =
            ProfileProductionCapability::class.java.constructors
                .single()
                .parameterTypes
                .toSet()

        assertEquals(
            setOf(LearningMasteryDisplayRepository::class.java),
            parameterTypes,
        )
        assertTrue(
            parameterTypes.none { type ->
                FORBIDDEN_BOUNDARY_FRAGMENTS.any { fragment ->
                    type.name.contains(fragment, ignoreCase = true)
                }
            },
        )
    }

    @Test
    fun productionRouteHasNoLegacyMasteryOrSettingsStorageInput() {
        val routeMethods =
            Class.forName(
                "com.tingyun.smartmistakebook.feature.profile.ProductionProfileRouteKt",
            ).declaredMethods
                .filter { method -> method.name == "ProductionProfileRoute" }

        assertTrue(routeMethods.isNotEmpty())
        assertTrue(
            routeMethods
                .flatMap { method -> method.parameterTypes.toList() }
                .none { type ->
                    FORBIDDEN_ROUTE_FRAGMENTS.any { fragment ->
                        type.name.contains(fragment, ignoreCase = true)
                    }
                },
        )
    }

    private fun provider(
        available: Set<ProductionAdapter>,
        source: () -> ProfileProductionCapability?,
    ) = ProfileProductionCapabilityProvider(
        adapterAvailability =
            ProductionAdapterAvailabilityPort {
                ProductionAdapterManifest.fromAvailable(available)
            },
        capabilitySource = ProfileProductionCapabilitySource(source),
    )

    private companion object {
        val FORBIDDEN_BOUNDARY_FRAGMENTS =
            listOf(
                "probability",
                "event",
                "identity",
                "database",
                "dao",
                "sql",
                "knowledgecatalog",
                "knowledgenode",
            )

        val FORBIDDEN_ROUTE_FRAGMENTS =
            FORBIDDEN_BOUNDARY_FRAGMENTS +
                listOf(
                    "StudyProfileOverview",
                    "StudyReviewOverview",
                    "AppCapabilitySnapshot",
                    "DataStore",
                    "TutorSettingsRepository",
                    "TutorTeachingReferenceRepository",
                )
    }
}

private inline fun <reified Capability : Any> recordingProxy(
    name: String,
    calls: MutableList<String>,
): Capability =
    Proxy.newProxyInstance(
        Capability::class.java.classLoader,
        arrayOf(Capability::class.java),
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
    } as Capability
