package com.tingyun.smartmistakebook.core.data.knowledge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionKnowledgeArchitectureContractTest {
    @Test
    fun productionKnowledgePackageContainsOnlyCatalogBackedReadersAndBootstrap() {
        val root = projectRoot()
        val productionDirectory = root.resolve(PRODUCTION_KNOWLEDGE_PATH)
        val sources =
            productionDirectory
                .listFiles()
                .orEmpty()
                .filter { file -> file.isFile && file.extension == "kt" }
                .associateBy(File::getName)

        assertEquals(EXPECTED_PRODUCTION_FILES, sources.keys)
        val forbiddenReferences =
            sources.values
                .flatMap { source ->
                    val content = source.readText()
                    FORBIDDEN_PRODUCTION_REFERENCES
                        .filter(content::contains)
                        .map { reference -> "${source.name}:$reference" }
                }
                .sorted()
        assertTrue(
            "Legacy knowledge authority escaped into production: $forbiddenReferences",
            forbiddenReferences.isEmpty(),
        )

        val bootstrap = sources.getValue("ProductionHighSchoolKnowledgeCatalogBootstrap.kt")
        assertTrue(
            bootstrap.readText().contains("HighSchoolKnowledgeProductionRuntime"),
        )
        val contextReader = sources.getValue("ReviewedProblemKnowledgeContextRepository.kt")
        assertTrue(contextReader.readText().contains("HighSchoolKnowledgeCatalog"))
        assertTrue(contextReader.readText().contains("ReviewedKnowledgeContextReader"))
        val teachingReader = sources.getValue("CatalogTutorTeachingReferenceRepository.kt")
        assertTrue(teachingReader.readText().contains("HighSchoolKnowledgeCatalog"))
        assertTrue(teachingReader.readText().contains("CatalogTutorTeachingReferenceRepository"))
    }

    @Test
    fun legacyBuildersAndCodecsExistOnlyAsExplicitTestProvisioningTools() {
        val root = projectRoot()
        val mainSources = root.resolve("core/data/src/main").walkTopDown().filter(File::isFile)
        val escapedFiles =
            mainSources
                .filter { file -> file.name in TEST_PROVISIONING_HELPERS }
                .map { file -> file.relativeTo(root).invariantSeparatorsPath }
                .toList()
        assertTrue(
            "Knowledge provisioning helpers must not enter an APK source set: $escapedFiles",
            escapedFiles.isEmpty(),
        )

        val toolingDirectory = root.resolve(TEST_PROVISIONING_PATH)
        val toolingFiles =
            toolingDirectory
                .listFiles()
                .orEmpty()
                .filter(File::isFile)
                .associateBy(File::getName)
        assertTrue(
            "Explicit test provisioning tooling is incomplete: ${toolingFiles.keys}",
            toolingFiles.keys.containsAll(TEST_PROVISIONING_HELPERS),
        )
        TEST_PROVISIONING_HELPERS.forEach { helper ->
            assertFalse(
                "$helper must not depend on the legacy StudyDatabasePort",
                toolingFiles.getValue(helper).readText().contains("StudyDatabasePort"),
            )
        }
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private companion object {
        const val PRODUCTION_KNOWLEDGE_PATH =
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/knowledge"
        const val TEST_PROVISIONING_PATH =
            "core/data/src/test/kotlin/com/tingyun/smartmistakebook/core/data/knowledge/provisioning"

        val EXPECTED_PRODUCTION_FILES =
            setOf(
                "CatalogTutorTeachingReferenceRepository.kt",
                "ProductionHighSchoolKnowledgeCatalogBootstrap.kt",
                "ReviewedProblemKnowledgeContextRepository.kt",
            )

        val TEST_PROVISIONING_HELPERS =
            setOf(
                "BundledKnowledgePackResources.kt",
                "KnowledgePackCoverage.kt",
                "ReviewedTeachingMaterialSidecarJsonCodec.kt",
            )

        val FORBIDDEN_PRODUCTION_REFERENCES =
            setOf(
                "com.tingyun.smartmistakebook.core.database",
                "StudyDatabasePort",
                "BundledKnowledgeBaseInstaller",
                "BundledKnowledgePackResources",
                "HistoricalSampleKnowledgeCatalogBootstrap",
                "HistoricalSampleKnowledgePackBootstrap",
                "KnowledgeContextRetriever",
                "RoomKnowledgeResearchReviewQueue",
                "RoomTutorTeachingReferenceRepository",
            )
    }
}
