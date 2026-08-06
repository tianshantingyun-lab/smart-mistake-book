package com.tingyun.smartmistakebook.core.data.production

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterManifest
import com.tingyun.smartmistakebook.core.data.authority.ThreeAuthorityProductionStartupState
import com.tingyun.smartmistakebook.core.data.authority.ThreeAuthorityStartupBlockReason
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommandFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewProductionCapability
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionPortFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewSessionActionPort
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureProductionCapabilitiesTest {
    @Test
    fun routeFacingBundlesHaveNoPublicConstructor() {
        val tokenTypes =
            listOf(
                ReviewFeatureProductionCapability::class.java,
                CaptureFeatureProductionCapability::class.java,
                LibraryFeatureProductionCapability::class.java,
                TutorFeatureProductionCapability::class.java,
                ProfileFeatureProductionCapability::class.java,
                FeatureProductionCapabilities::class.java,
                FeatureProductionCapabilityAvailability.Available::class.java,
            )

        tokenTypes.forEach { type ->
            assertTrue("${type.simpleName} must be an interface", type.isInterface)
            assertTrue(
                "${type.simpleName} exposes a constructor",
                type.declaredConstructors.isEmpty(),
            )
        }
    }

    @Test
    fun bundlesExposeOnlyThePortsRequiredByTheirFeatureProviders() {
        assertGetterTypes(
            ReviewFeatureProductionCapability::class.java,
            mapOf(
                "getPlanning" to DailyReviewProductionCapability::class.java,
                "getSessionActions" to DailyReviewSessionActionPort::class.java,
                "getPacingActions" to DailyReviewPacingActionPort::class.java,
                "getAnswerSubmissionPorts" to DailyReviewAnswerSubmissionPortFactory::class.java,
                "getPacingCommandFactory" to DailyReviewPacingCommandFactory::class.java,
            ),
        )
        assertGetterTypes(
            CaptureFeatureProductionCapability::class.java,
            mapOf(
                "getCaptureWorkflow" to CaptureWorkflowRepository::class.java,
                "getBatchImports" to BatchImportRepository::class.java,
                "getModelTasks" to ScopedModelTaskPort::class.java,
            ),
        )
        assertGetterTypes(
            LibraryFeatureProductionCapability::class.java,
            mapOf(
                "getCatalog" to
                    com.tingyun.smartmistakebook.core.data.mistake
                        .StudentMistakeLibraryCatalogRepository::class.java,
                "getMistakeDetails" to MistakeDetailRepository::class.java,
                "getOrganization" to MistakeOrganizationRepository::class.java,
                "getBatchImports" to BatchImportRepository::class.java,
                "getModelTasks" to ScopedModelTaskPort::class.java,
            ),
        )
        assertGetterTypes(
            TutorFeatureProductionCapability::class.java,
            mapOf(
                "getTutorSessions" to CaptureWorkflowRepository::class.java,
                "getInteractions" to TutorInteractionRepository::class.java,
                "getLearningMemory" to TutorLearningMemoryRepository::class.java,
                "getMasteryContext" to TutorMasteryContextRepository::class.java,
                "getTeachingReferences" to TutorTeachingReferenceRepository::class.java,
                "getModelTasks" to ScopedModelTaskPort::class.java,
            ),
        )
        assertGetterTypes(
            ProfileFeatureProductionCapability::class.java,
            mapOf(
                "getLearningMasteryDisplay" to LearningMasteryDisplayRepository::class.java,
            ),
        )
    }

    @Test
    fun publicBundleApiCarriesNoAuthorityOrPersistenceIdentifiers() {
        val forbiddenFragments =
            listOf(
                "database",
                "dao",
                "sql",
                "owner",
                "runtime",
                "learnerid",
                "generation",
                "activation",
                "manifest",
                "witness",
                "proof",
                "fingerprint",
                "fallback",
            )
        val publicTypes =
            listOf(
                ReviewFeatureProductionCapability::class.java,
                CaptureFeatureProductionCapability::class.java,
                LibraryFeatureProductionCapability::class.java,
                TutorFeatureProductionCapability::class.java,
                ProfileFeatureProductionCapability::class.java,
                FeatureProductionCapabilities::class.java,
                FeatureProductionCapabilityAvailability::class.java,
                FeatureProductionCapabilityAvailability.Available::class.java,
                FeatureProductionCapabilityAvailability.Unavailable::class.java,
            )

        publicTypes.forEach { type ->
            type.methods
                .filter { method ->
                    method.declaringClass == type &&
                        Modifier.isPublic(method.modifiers) &&
                        !method.isSynthetic
                }.forEach { method ->
                    val signature =
                        buildString {
                            append(method.name)
                            append(':')
                            append(method.returnType.name)
                            method.parameterTypes.forEach { append(':').append(it.name) }
                        }.lowercase()
                    val violation = forbiddenFragments.firstOrNull(signature::contains)
                    assertTrue(
                        "${type.simpleName}.${method.name} leaks '$violation'",
                        violation == null,
                    )
                }
        }
    }

    @Test
    fun featureTokenCloseIsIdempotent() {
        val releases = AtomicInteger(0)
        val token =
            FeatureProductionCapabilityOwner.issueProfile(
                learningMasteryDisplay = proxy(),
                onClose = { releases.incrementAndGet() },
            )

        token.close()
        token.close()

        assertEquals(1, releases.get())
    }

    @Test
    fun ownerIsJvmSyntheticAndIssuedImplementationsAreNotPublic() {
        val ownerMethods =
            FeatureProductionCapabilityOwner::class.java.declaredMethods.filter {
                Modifier.isPublic(it.modifiers)
            }
        assertTrue(ownerMethods.isNotEmpty())
        assertTrue(
            "Every JVM-public owner method must be synthetic: $ownerMethods",
            ownerMethods.all { it.isSynthetic },
        )

        val token =
            FeatureProductionCapabilityOwner.issueProfile(
                learningMasteryDisplay = proxy(),
                onClose = {},
            )
        assertFalse(
            "Issued implementation must remain package-private",
            Modifier.isPublic(token.javaClass.modifiers),
        )
        token.close()
    }

    @Test
    fun publicationCloseAttemptsEveryAvailableTokenAndSuppressesLaterFailures() {
        val profileReleases = AtomicInteger(0)
        val captureReleases = AtomicInteger(0)
        val profile =
            FeatureProductionCapabilityOwner.issueProfile(
                learningMasteryDisplay = proxy(),
                onClose = {
                    profileReleases.incrementAndGet()
                    error("profile close")
                },
            )
        val capture =
            FeatureProductionCapabilityOwner.issueCapture(
                captureWorkflow = proxy(),
                batchImports = proxy(),
                modelTasks = proxy(),
                onClose = {
                    captureReleases.incrementAndGet()
                    error("capture close")
                },
            )
        val unavailable =
            FeatureProductionCapabilityAvailability.Unavailable(
                FeatureProductionCapabilityUnavailableReason.REQUIRED_PORT_UNAVAILABLE,
            )
        val publication =
            FeatureProductionCapabilityOwner.issue(
                review = unavailable,
                capture = FeatureProductionCapabilityOwner.available(capture),
                library = unavailable,
                tutor = unavailable,
                profile = FeatureProductionCapabilityOwner.available(profile),
            )

        val failure =
            try {
                publication.close()
                throw AssertionError("Expected publication close to rethrow the first failure")
            } catch (expected: IllegalStateException) {
                expected
            }
        publication.close()

        assertEquals("profile close", failure.message)
        assertEquals(listOf("capture close"), failure.suppressed.map { it.message })
        assertEquals(1, profileReleases.get())
        assertEquals(1, captureReleases.get())
    }

    @Test
    fun blockedTerminalStatePublishesNoFeatureToken() {
        val publication =
            FeatureProductionCapabilitiesFactory.fromTerminalRuntime(
                terminalState =
                    ThreeAuthorityProductionStartupState.Blocked(
                        ThreeAuthorityStartupBlockReason.TERMINAL_PROOF_INCOMPLETE_OR_INVALID,
                    ),
                runtime = null,
                manifest = ProductionAdapterManifest.fromAvailable(ProductionAdapter.entries.toSet()),
            )

        publication.assertAllUnavailable(
            FeatureProductionCapabilityUnavailableReason.TERMINAL_GATE_NOT_READY,
        )
    }

    @Test
    fun productionSourceUsesNoReflectionRawDatabaseOwnerOrLegacyFallback() {
        val source = Files.readString(findProductionSource())
        val importLines = source.lineSequence().filter { it.startsWith("import ") }.toList()

        assertFalse(source.contains("java.lang.reflect"))
        assertFalse(source.contains("Class.forName"))
        assertFalse(source.contains("::class.java.name"))
        assertTrue(importLines.none { it.contains(".core.database.") })
        assertFalse(source.contains("LegacySessionDatabaseOwner"))
        assertFalse(source.contains("transitionalHistoricalFallback"))
        assertFalse(source.contains("createLegacyDuringAuthorityMigration"))
    }

    @Test
    fun noOtherProductionSourceCanBypassTheCapabilityOwner() {
        val ownerReference = "FeatureProductionCapabilityOwner"
        val productionSource = findProductionSource().toAbsolutePath().normalize()
        val sourceRoot =
            findProjectRoot()
                .resolve(Paths.get("core", "data", "src", "main", "kotlin"))

        val violations =
            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .filter { it.toAbsolutePath().normalize() != productionSource }
                    .filter { Files.readString(it).contains(ownerReference) }
                    .toList()
            }

        assertEquals(emptyList<Path>(), violations)
    }

    private fun FeatureProductionCapabilities.assertAllUnavailable(
        reason: FeatureProductionCapabilityUnavailableReason,
    ) {
        listOf(review, capture, library, tutor, profile).forEach { availability ->
            val unavailable =
                availability as?
                    FeatureProductionCapabilityAvailability.Unavailable
                    ?: throw AssertionError(
                        "Expected unavailable publication but got $availability",
                    )
            assertEquals(reason, unavailable.reason)
        }
    }

    private fun assertGetterTypes(
        type: Class<*>,
        expected: Map<String, Class<*>>,
    ) {
        val actual =
            type.declaredMethods
                .filter {
                    Modifier.isPublic(it.modifiers) &&
                        !it.isSynthetic &&
                        it.parameterCount == 0 &&
                        it.name.startsWith("get")
                }.associate { it.name to it.returnType }
        assertEquals(expected, actual)
    }

    private inline fun <reified Port : Any> proxy(): Port {
        val type = Port::class.java
        require(type.isInterface) { "${type.name} must remain a narrow interface" }
        return type.cast(
            Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
            ) { _, method, _ ->
                when (method.name) {
                    "toString" -> "test-${type.simpleName}"
                    "hashCode" -> System.identityHashCode(type)
                    "equals" -> false
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

    private fun findProductionSource(): Path {
        return findProjectRoot().resolve(
            Paths.get(
                "core",
                "data",
                "src",
                "main",
                "kotlin",
                "com",
                "tingyun",
                "smartmistakebook",
                "core",
                "data",
                "production",
                "FeatureProductionCapabilities.kt",
            ),
        )
    }

    private fun findProjectRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Cannot locate project root")
    }
}
