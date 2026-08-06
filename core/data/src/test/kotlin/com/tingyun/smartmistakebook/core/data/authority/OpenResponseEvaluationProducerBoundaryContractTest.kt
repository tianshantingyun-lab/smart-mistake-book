package com.tingyun.smartmistakebook.core.data.authority

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenResponseEvaluationProducerBoundaryContractTest {
    @Test
    fun onlyTheAuditedCoreDataSplitPackageFileCanReachTheModelOwnerBridge() {
        val root = projectRoot()
        val bridgeReferences =
            productionSourceFiles(root)
                .filter { source ->
                    "CoreDataOpenResponseEvaluationOwnerBridge" in source.readText()
                }
                .mapTo(sortedSetOf()) { source ->
                    source.relativeTo(root).invariantSeparatorsPath
                }

        assertEquals(
            sortedSetOf(
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/model/" +
                    "OpenResponseEvaluationOwnerAccess.kt",
                "core/model/src/main/java/com/tingyun/smartmistakebook/core/model/" +
                    "CoreDataOpenResponseEvaluationOwnerBridge.java",
            ),
            bridgeReferences,
        )
    }

    @Test
    fun appFeatureProviderAndDatabaseCodeCannotMintOpenResponseAuthority() {
        val root = projectRoot()
        val forbiddenRoots =
            listOf(
                File(root, "app"),
                File(root, "feature"),
                File(root, "core/model-provider"),
                File(root, "core/database"),
                File(root, "core/learner-mastery-database"),
                File(root, "core/student-mistake-database"),
                File(root, "core/knowledge-database"),
            )
        val forbiddenAuthorityNames =
            listOf(
                "CoreDataOpenResponseEvaluationOwnerBridge",
                "OpenResponseEvaluationOwnerKey",
                "OpenResponseEvaluationHostAuthority",
                "openCoreDataOpenResponseEvaluationTaskProducer",
            )
        val violations =
            forbiddenRoots
                .flatMap(::productionSourceFiles)
                .filter { source ->
                    val text = source.readText()
                    forbiddenAuthorityNames.any(text::contains)
                }
                .map { source -> source.relativeTo(root).invariantSeparatorsPath }

        assertTrue(
            "Production code outside core:model and the audited core:data owner can mint " +
                "open-response authority: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun productionProducerDependsOnNoDatabaseOrRawMasterySurface() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ProductionOpenResponseEvaluationTaskProducer.kt",
            ).readText()
        val forbidden =
            listOf(
                "StudyDatabase",
                "RoomDatabase",
                "Dao",
                "LearnerMastery",
                "MasteryProjection",
                "rawMastery",
                "sql",
            )

        forbidden.forEach { name ->
            assertFalse("Producer owner leaked $name", source.contains(name, ignoreCase = true))
        }
        assertTrue("KnowledgeReferenceProofVerifier" in source)
        assertTrue("CurrentOpenResponseEvaluationScopeAuthorization" in source)
        assertTrue("OpenResponseEvaluationTaskProducer" in source)
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate project root")

    private fun productionSourceFiles(root: File): List<File> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .onEnter { directory ->
                directory.name !in setOf("build", ".gradle", ".git")
            }
            .filter(File::isFile)
            .filter { file ->
                file.invariantSeparatorsPath.contains("/src/main/") &&
                    file.extension in setOf("kt", "java")
            }
            .toList()
    }
}
