package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.data.production.CompleteProductionCapabilityAssembly
import com.tingyun.smartmistakebook.core.data.production.ProductionCapabilitySnapshot
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionCapabilityPublicationGraphTest {
    @Test
    fun graphStartsEmptyAndCloseBeforePublicationIsPermanentAndIdempotent() {
        val graph = ProductionCapabilityPublicationGraph()

        assertSame(ProductionCapabilityPublicationState.Unpublished, graph.state)
        assertNull(graph.publishedCapabilities)

        graph.close()
        graph.close()

        assertSame(ProductionCapabilityPublicationState.Closed, graph.state)
        assertNull(graph.publishedCapabilities)
    }

    @Test
    fun publishedStateCanHoldOnlyOneOwnerIssuedSnapshot() {
        val fields =
            ProductionCapabilityPublicationState.Published::class.java.declaredFields
                .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }

        assertEquals(listOf("capabilities"), fields.map { it.name })
        assertEquals(ProductionCapabilitySnapshot::class.java, fields.single().type)
        assertTrue(Modifier.isFinal(fields.single().modifiers))
    }

    @Test
    fun appCannotConstructEitherTheAssemblyOrSnapshot() {
        listOf(
            CompleteProductionCapabilityAssembly::class.java,
            ProductionCapabilitySnapshot::class.java,
        ).forEach { type ->
            assertTrue("${type.simpleName} must remain abstract", Modifier.isAbstract(type.modifiers))
            assertTrue("${type.simpleName} exposes a public constructor", type.constructors.isEmpty())
            assertFalse(
                "${type.simpleName} exposes a non-private declared constructor",
                type.declaredConstructors.any { constructor ->
                    !Modifier.isPrivate(constructor.modifiers) && !constructor.isSynthetic
                },
            )
        }
    }

    @Test
    fun graphSourceClaimsOnlyTheOpaqueCompleteAssembly() {
        val source = Files.readString(findGraphSource())
        val imports = source.lineSequence().filter { it.startsWith("import ") }.toList()

        assertTrue(source.contains("assembly.claimForPublication()"))
        assertTrue(source.contains("ProductionCapabilityPublicationState.Published(snapshot)"))
        assertTrue(
            imports.contains(
                "import com.tingyun.smartmistakebook.core.data.production." +
                    "CompleteProductionCapabilityAssembly",
            ),
        )
        assertTrue(
            imports.contains(
                "import com.tingyun.smartmistakebook.core.data.production." +
                    "ProductionCapabilitySnapshot",
            ),
        )
        listOf(
            "ProductionCapabilityLease",
            "ProductionCapabilityAssemblyOwner",
            "CompleteProductionCapabilityPorts",
            "ProductionAdapter",
            "StudyExperienceRepository",
            "CaptureWorkflowRepository",
            "DailyReviewRepository",
            "TutorLearningMemoryRepository",
        ).forEach { forbidden ->
            assertFalse("App publication graph can fabricate $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun applicationAndRootCannotPublishOrAssembleIndependentBusinessPorts() {
        val application = Files.readString(findAppSource("SmartMistakeBookApplication.kt"))
        val root = Files.readString(findAppSource("SmartMistakeBookRoot.kt"))

        assertTrue(application.contains("ProductionAuthorityBootstrapFactory.createProduction("))
        assertTrue(application.contains("productionCapabilityPublicationGraph.publish(assembly)"))
        assertFalse(application.contains("ProductionAuthorityBootstrapFactory.failClosed("))
        assertFalse(application.contains("lateinit var studyRepository"))
        assertFalse(application.contains("lateinit var captureRepository"))
        assertFalse(application.contains("lateinit var modelTaskRepository"))
        listOf(
            "TutorInteractionRepository",
            "TutorLearningMemoryRepository",
            "tutorInteractionRepository",
            "tutorLearningMemoryRepository",
        ).forEach { forbidden ->
            assertFalse(
                "Application exposes a raw tutor repository through $forbidden",
                application.contains(forbidden),
            )
        }

        assertTrue(root.contains("application.publishedProductionCapabilities"))
        val lobbyRoute = root
            .substringAfter("composable(Routes.Tutor)")
            .substringBefore("composable(Routes.Library)")
        assertTrue(
            lobbyRoute.contains(
                "conversationLobby = productionCapabilities.tutorConversationLobby",
            ),
        )
        listOf(
            "tutorLearningMemory",
            "tutorSession",
            "learnerScopeId",
        ).forEach { forbidden ->
            assertFalse(
                "Root lobby bypasses its narrow port through $forbidden",
                lobbyRoute.contains(forbidden),
            )
        }
        listOf(
            "application.studyRepository",
            "application.captureRepository",
            "application.batchImportRepository",
            "application.mistakeDetailRepository",
            "application.mistakeOrganizationRepository",
            "application.modelTaskRepository",
            "application.tutorInteractionRepository",
            "application.tutorLearningMemoryRepository",
            "application.tutorMasteryContextRepository",
            "application.tutorTeachingReferenceRepository",
        ).forEach { forbidden ->
            assertFalse("Root bypasses the published snapshot through $forbidden", root.contains(forbidden))
        }
    }

    @Test
    fun rootReviewUsesOnlyThePublishedProductionReviewCapability() {
        val root = Files.readString(findAppSource("SmartMistakeBookRoot.kt"))

        assertTrue(root.contains("ProductionDailyReviewRoute("))
        assertTrue(root.contains("ProductionDailyReviewSessionRoute("))
        assertTrue(root.contains("productionCapabilities.reviewPlanning"))
        assertFalse(Regex("(?m)^\\s*ReviewRoute\\(").containsMatchIn(root))
        listOf(
            "ReviewSessionScreen(",
            "CapturedReviewSessionScreen(",
            "repository.startOrResumeReviewSession(",
            "repository.submitReviewChoice(",
            "repository.submitReviewSelfReport(",
            "repository::revealAnswer",
        ).forEach { forbidden ->
            assertFalse("Root review still uses legacy path: $forbidden", root.contains(forbidden))
        }
    }

    @Test
    fun workManagerFactoryConsumesOnlyTheDedicatedExecutionResolver() {
        val source = Files.readString(findAppSource("ProblemOrganizationWorker.kt"))
        val factory =
            source.substringAfter("internal class ProblemOrganizationWorkerFactory")
                .substringBefore("internal class ProblemOrganizationWorkScheduler")

        assertTrue(factory.contains("() -> ProductionProblemOrganizationExecutionResolver?"))
        assertTrue(factory.contains("productionWorkerExecution(executionResolverProvider)"))
        assertFalse(factory.contains("ProductionCapabilitySnapshot"))
        assertFalse(factory.contains("workManagerCoordination"))
        assertFalse(factory.contains("?: return null"))
        assertFalse(factory.contains("ProblemOrganizationWorkerDependencies"))
        assertFalse(factory.contains("ProblemOrganizationWorkProcessor("))
        assertFalse(factory.contains("StudyDatabase"))
        assertFalse(factory.contains("ModelTaskRepository"))
        assertFalse(factory.contains("MistakeOrganizationRepository"))
        listOf(
            "ProblemOrganizationWorkProcessor",
            "ProblemOrganizationWorkAuthorizationResult",
            "authorizeStoredGrant",
            ".process(",
        ).forEach { forbidden ->
            assertFalse("App Worker escaped raw execution detail: $forbidden", source.contains(forbidden))
        }
        assertTrue("WorkManager enqueue is not durably acknowledged", source.contains(".await()"))
        assertTrue(source.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(source.contains("ProductionProblemOrganizationExecutionLease("))
    }

    @Test
    fun applicationStartsOnlyTheMinimalPersistedWorkSchedulingFeedAfterPublication() {
        val application = Files.readString(findAppSource("SmartMistakeBookApplication.kt"))
        val coordinator =
            Files.readString(findAppSource("ProblemOrganizationWorkSchedulingCoordinator.kt"))

        assertTrue(
            application.contains(
                "workManagerCoordination.currentProblemOrganizationScheduling()",
            ),
        )
        assertTrue(application.contains("startProblemOrganizationWorkScheduling(capabilities)"))
        assertTrue(application.contains("problemOrganizationWorkSchedulingCoordinator.start()"))
        assertTrue(coordinator.contains("ProductionProblemOrganizationWorkSchedule"))
        listOf(
            "ProblemOrganizationWorkSessionPort",
            "ProblemOrganizationWorkSessionSnapshot",
            "SessionOpaquePayload",
            "requestPayload",
            "authorizationPayload",
            "learnerId",
        ).forEach { forbidden ->
            assertFalse("App scheduling escaped $forbidden", coordinator.contains(forbidden))
        }
    }

    private fun findGraphSource(): Path =
        findAppSource("ProductionCapabilityPublicationGraph.kt")

    private fun findAppSource(fileName: String): Path =
        findProjectRoot().resolve(
            Paths.get(
                "app",
                "src",
                "main",
                "kotlin",
                "com",
                "tingyun",
                "smartmistakebook",
                fileName,
            ),
        )

    private fun findProjectRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Cannot locate project root")
    }
}
