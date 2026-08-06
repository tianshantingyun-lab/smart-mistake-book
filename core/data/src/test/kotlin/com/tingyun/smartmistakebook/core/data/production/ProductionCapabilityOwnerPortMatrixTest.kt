package com.tingyun.smartmistakebook.core.data.production

import com.tingyun.smartmistakebook.core.data.authority.CurrentGenerationProductionOwnerPorts
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionCapabilityOwnerPortMatrixTest {
    @Test
    fun matrixCoversEveryAtomicPublicationSlotExactlyOnce() {
        val entries = ProductionCapabilityOwnerPortMatrix.entries

        assertEquals(ProductionAdapter.entries.size, entries.size)
        assertEquals(
            ProductionAdapter.entries.toSet(),
            entries.map(ProductionOwnerPortMatrixEntry::adapter).toSet(),
        )
    }

    @Test
    fun manifestNamesEveryAuditedOwnerAssemblableNarrowPort() {
        val expected = ProductionAdapter.entries.toSet()

        assertEquals(expected, ProductionCapabilityOwnerPortMatrix.ownerSourceReadyAdapters)
        assertEquals(expected, ProductionCapabilityOwnerPortMatrix.manifest().availableAdapters)
        assertTrue(ProductionCapabilityOwnerPortMatrix.manifest().isComplete)
    }

    @Test
    fun everyInventoriedSlotHasOneReadyOwnerSource() {
        ProductionAdapter.entries.forEach { adapter ->
            val entry = ProductionCapabilityOwnerPortMatrix.entry(adapter)
            assertEquals(ProductionOwnerPortStatus.OWNER_SOURCE_READY, entry.status)
            assertTrue(entry.blockedBy.isEmpty())
            assertEquals(null, entry.missingRequirement)
        }
    }

    @Test
    fun architecturalEvidenceNamesRealFilesAndNoAvailableBusinessFallback() {
        val root = findProjectRoot()
        ProductionCapabilityOwnerPortMatrix.entries.forEach { entry ->
            entry.implementationFiles.forEach { relative ->
                assertTrue(
                    "Missing owner-port evidence file: $relative",
                    Files.isRegularFile(root.resolve(relative)),
                )
            }
        }

        val availableEvidence =
            ProductionCapabilityOwnerPortMatrix.entries
                .filter { entry ->
                    entry.status == ProductionOwnerPortStatus.OWNER_SOURCE_READY
                }.joinToString("\n") { entry ->
                    entry.narrowSource + "\n" + entry.implementationFiles.joinToString("\n")
                }.lowercase()
        listOf(
            "fallback",
            "roombackedstudy",
            "legacytutorinteraction",
            "legacystudyexperience",
        ).forEach { forbidden ->
            assertFalse(
                "Available production owner-port evidence names $forbidden",
                forbidden in availableEvidence,
            )
        }
    }

    @Test
    fun currentGenerationOwnerBundleExposesOnlyNarrowRepositories() {
        val type = CurrentGenerationProductionOwnerPorts::class.java

        assertTrue(type.declaredConstructors.all { constructor ->
            Modifier.isPrivate(constructor.modifiers)
        })
        val publicSurface =
            type.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
                .map { method -> method.toGenericString() }
                .joinToString("\n")
                .lowercase()
        listOf(
            "databasesessionowner",
            "databasecapability",
            "locallearningauthorityruntime",
            "sessionscope",
            "learnerid",
        ).forEach { forbidden ->
            assertFalse(
                "Current-generation owner bundle exposes $forbidden",
                forbidden in publicSurface,
            )
        }
        assertTrue(
            "Current owner must expose the narrow model queue",
            "modeltaskrepository" in publicSurface,
        )
        assertTrue(
            "Current owner must expose student-authoritative organization",
            "mistakeorganizationrepository" in publicSurface,
        )
        assertTrue(
            "Current owner must expose the student mistake catalog",
            "studentmistakelibrarycatalogrepository" in publicSurface,
        )
        assertTrue(
            "Current owner must expose WorkManager coordination",
            "productionworkmanagercoordination" in publicSurface,
        )
    }

    @Test
    fun currentGenerationAssemblyUsesRealMistakeOwnersAndProcessor() {
        val root = findProjectRoot()
        val source =
            Files.readString(
                root.resolve(
                    "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/" +
                        "authority/LocalLearningAuthorityRuntime.kt",
                ),
            )

        listOf(
            "ProductionStudentProblemOrganizationOwnerFactory.create(",
            "StudentAuthoritativeMistakeOrganizationRepositoryFactory.createProduction(",
            "StudentMistakeLibraryCatalogRepositoryFactory.createProjected(",
            "ProblemOrganizationWorkProcessor(",
            "triggerAuthorityRelay()",
            "mistakeCapabilities.workManagerCoordination",
        ).forEach { required ->
            assertTrue("Current-generation mistake assembly omitted $required", required in source)
        }
        assertFalse(
            "Production WorkManager coordination is a null stub",
            "issue { null }" in source,
        )
        val catalogAssembly =
            source
                .substringAfter("val catalogJob = SupervisorJob()")
                .substringBefore("return try")
        listOf(
            "learnerBoundMasteryDisplay.observeRevision().stateIn(",
            "highSchoolKnowledge.observeSnapshotRevision()",
            "masteryDisplayRevisions = masteryDisplayRevisions",
            "knowledgeRevisions = knowledgeRevisions",
            "knowledgeDisplayResolver = { expectedRevision, refs ->",
            "expectedRevision = expectedRevision",
            "LearnerBoundStudentMistakeMasteryRead { request ->",
            "learnerBoundMasteryDisplay.readKnowledgePage(request)",
        ).forEach { required ->
            assertTrue("Student catalog mastery assembly omitted $required", required in catalogAssembly)
        }
        assertFalse(
            "Student catalog assembly must not block while waiting for mastery revision",
            "runBlocking" in catalogAssembly,
        )
        assertFalse(
            "Production catalog assembly must not use the full-list compatibility adapter",
            "createDeferredWithMastery" in catalogAssembly ||
                "readCompleteStudentLibrary" in catalogAssembly,
        )
        assertFalse(
            "Student catalog projection must not receive a mastery write capability",
            "observationSink" in catalogAssembly || "recordObservation" in catalogAssembly,
        )
        val resourceCloseOrder =
            source
                .substringAfter("private class CurrentGenerationAuthorityResources")
                .substringAfter("listOf<AutoCloseable>(")
                .substringBefore(").forEach")
        assertTrue(
            "Mistake capability resources must close before the session database owner",
            resourceCloseOrder.indexOf("mistakeCapabilities") in
                0 until resourceCloseOrder.indexOf("sessionOwner"),
        )

        val workerSource =
            Files.readString(
                root.resolve(
                    "app/src/main/kotlin/com/tingyun/smartmistakebook/" +
                        "ProblemOrganizationWorker.kt",
                ),
            )
        val coordinatorSource =
            Files.readString(
                root.resolve(
                    "app/src/main/kotlin/com/tingyun/smartmistakebook/" +
                        "ProblemOrganizationWorkSchedulingCoordinator.kt",
                ),
            )
        listOf(
            "ProductionProblemOrganizationExecutionResolver",
            "ProductionProblemOrganizationExecutionLease(",
            "ProductionProblemOrganizationExecutionRequest(",
            "ExistingWorkPolicy.KEEP",
            ".await()",
        ).forEach { required ->
            assertTrue("WorkManager handoff omitted $required", required in workerSource)
        }
        listOf(
            "ProblemOrganizationWorkProcessor",
            "ProblemOrganizationWorkAuthorizationResult",
            "authorizeStoredGrant",
            "ProductionCapabilitySnapshot",
        ).forEach { forbidden ->
            assertFalse("WorkManager app boundary escaped $forbidden", forbidden in workerSource)
        }
        listOf(
            "recoverPublishedRunningProblemOrganizationWorks(",
            "collectSchedulableAfterRecovery()",
            "enqueueUnacknowledgedProblemOrganizationWorks(",
        ).forEach { required ->
            assertTrue("WorkManager recovery coordinator omitted $required", required in coordinatorSource)
        }
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
