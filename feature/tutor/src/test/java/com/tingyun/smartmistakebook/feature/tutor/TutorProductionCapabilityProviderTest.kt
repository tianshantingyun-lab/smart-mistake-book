package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterManifest
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorProductionCapabilityProviderTest {
    @Test
    fun `required adapters match the tutor session and visual chain`() {
        assertEquals(
            setOf(
                ProductionAdapter.TUTOR_SESSION,
                ProductionAdapter.TUTOR_LEARNING_MEMORY,
                ProductionAdapter.TUTOR_MASTERY_CONTEXT,
                ProductionAdapter.TUTOR_TEACHING_REFERENCE,
                ProductionAdapter.MODEL_TASK_QUEUE,
                ProductionAdapter.MODEL_ASSET_DOCUMENTS,
            ),
            TutorProductionCapabilityProvider.REQUIRED_TUTOR_ADAPTERS,
        )
    }

    @Test
    fun `missing tutor adapter blocks before publication is read`() {
        var sourceReads = 0
        val missing = ProductionAdapter.TUTOR_SESSION
        val provider =
            provider(
                available = ProductionAdapter.entries.toSet() - missing,
                source = {
                    sourceReads += 1
                    capability()
                },
            )

        val decision = provider.resolve()

        assertTrue(decision is TutorProductionCapabilityDecision.Blocked)
        decision as TutorProductionCapabilityDecision.Blocked
        assertEquals(
            TutorProductionCapabilityBlockReason.REQUIRED_ADAPTERS_UNAVAILABLE,
            decision.reason,
        )
        assertEquals(setOf(missing), decision.missingRequiredAdapters)
        assertEquals(0, sourceReads)
    }

    @Test
    fun `incomplete global manifest blocks before publication is read`() {
        var sourceReads = 0
        val nonTutorAdapter = ProductionAdapter.CAPTURE_WORKFLOW
        check(nonTutorAdapter !in TutorProductionCapabilityProvider.REQUIRED_TUTOR_ADAPTERS)
        val provider =
            provider(
                available = ProductionAdapter.entries.toSet() - nonTutorAdapter,
                source = {
                    sourceReads += 1
                    capability()
                },
            )

        assertEquals(
            TutorProductionCapabilityDecision.Blocked(
                TutorProductionCapabilityBlockReason.PRODUCTION_MANIFEST_INCOMPLETE,
            ),
            provider.resolve(),
        )
        assertEquals(0, sourceReads)
    }

    @Test
    fun `manifest failure blocks without consulting publication`() {
        var sourceReads = 0
        val provider =
            TutorProductionCapabilityProvider(
                adapterAvailability =
                    ProductionAdapterAvailabilityPort {
                        error("manifest unavailable")
                    },
                capabilitySource =
                    TutorProductionCapabilitySource {
                        sourceReads += 1
                        capability()
                    },
            )

        assertEquals(
            TutorProductionCapabilityDecision.Blocked(
                TutorProductionCapabilityBlockReason.MANIFEST_UNAVAILABLE,
            ),
            provider.resolve(),
        )
        assertEquals(0, sourceReads)
    }

    @Test
    fun `unpublished or exceptional capability blocks without fallback`() {
        listOf<TutorProductionCapabilitySource>(
            TutorProductionCapabilitySource { null },
            TutorProductionCapabilitySource { error("publication failed") },
        ).forEach { source ->
            val decision =
                provider(
                    available = ProductionAdapter.entries.toSet(),
                    source = source::currentCapability,
                ).resolve()

            assertEquals(
                TutorProductionCapabilityDecision.Blocked(
                    TutorProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
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

        assertTrue(decision is TutorProductionCapabilityDecision.Available)
        assertSame(
            capability,
            (decision as TutorProductionCapabilityDecision.Available).capability,
        )
    }

    @Test
    fun `published capability keeps the exact current session host`() {
        val host = interfaceStub(TutorCurrentSessionHostPort::class.java)

        assertSame(host, capability(currentSessionHost = host).currentSessionHost)
    }

    @Test
    fun `published capability keeps the exact narrow lobby port`() {
        val lobby = interfaceStub(TutorConversationLobbyPort::class.java)

        assertSame(lobby, capability(conversationLobby = lobby).conversationLobby)
    }

    private fun provider(
        available: Set<ProductionAdapter>,
        source: () -> TutorProductionCapability?,
    ): TutorProductionCapabilityProvider =
        TutorProductionCapabilityProvider(
            adapterAvailability =
                ProductionAdapterAvailabilityPort {
                    ProductionAdapterManifest.fromAvailable(available)
                },
            capabilitySource = TutorProductionCapabilitySource(source),
        )

    private fun capability(
        currentSessionHost: TutorCurrentSessionHostPort =
            interfaceStub(TutorCurrentSessionHostPort::class.java),
        conversationLobby: TutorConversationLobbyPort =
            interfaceStub(TutorConversationLobbyPort::class.java),
    ): TutorProductionCapability =
        TutorProductionCapability(
            tutorSessions = interfaceStub(CaptureWorkflowRepository::class.java),
            currentSessionHost = currentSessionHost,
            conversationLobby = conversationLobby,
            masteryContext = interfaceStub(TutorMasteryContextRepository::class.java),
            teachingReferences = interfaceStub(TutorTeachingReferenceRepository::class.java),
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
