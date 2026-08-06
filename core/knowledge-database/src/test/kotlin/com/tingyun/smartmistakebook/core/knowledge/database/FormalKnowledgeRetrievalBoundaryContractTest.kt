package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.Continuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormalKnowledgeRetrievalBoundaryContractTest {
    @Test
    fun recallDaoRequiresSubjectAndTaxonomyScope() {
        val method =
            KnowledgeCatalogDao::class.java.declaredMethods.single { candidate ->
                candidate.name == "recall" && !candidate.isSynthetic
            }

        assertEquals(String::class.java, method.parameterTypes[0])
        assertEquals(String::class.java, method.parameterTypes[1])
        assertEquals(List::class.java, method.parameterTypes[2])
        assertEquals(Int::class.javaPrimitiveType, method.parameterTypes[3])
        assertEquals(Continuation::class.java, method.parameterTypes[4])
    }

    @Test
    fun identicalAliasesRemainSeparatedBySubjectInTheLocalIndex() {
        val indexed =
            KnowledgeSearchIndexBuilder.build(
                listOf(
                    node(
                        id = "node.math.shared-alias",
                        stableCode = "math.shared-alias",
                        subject = SubjectKind.MATH,
                    ),
                    node(
                        id = "node.physics.shared-alias",
                        stableCode = "physics.shared-alias",
                        subject = SubjectKind.PHYSICS,
                    ),
                ),
            )

        val aliasRows = indexed.filter { row -> row.searchFeature == SHARED_ALIAS }
        assertTrue(aliasRows.all { row -> row.featureKind == "alias:exact" })
        assertEquals(
            setOf(SubjectKind.MATH.name, SubjectKind.PHYSICS.name),
            aliasRows.mapTo(mutableSetOf(), KnowledgeSearchFeatureEntity::subject),
        )
        assertEquals(
            setOf("node.math.shared-alias"),
            aliasRows
                .filter { row -> row.subject == SubjectKind.MATH.name }
                .mapTo(mutableSetOf(), KnowledgeSearchFeatureEntity::knowledgeNodeId),
        )
        assertEquals(
            setOf("node.physics.shared-alias"),
            aliasRows
                .filter { row -> row.subject == SubjectKind.PHYSICS.name }
                .mapTo(mutableSetOf(), KnowledgeSearchFeatureEntity::knowledgeNodeId),
        )
    }

    @Test
    fun formalKnowledgeModuleCannotDependOnStudentOrMasteryStores() {
        val moduleRoot = locateModuleRoot()
        val buildScript = Files.readString(moduleRoot.resolve("build.gradle.kts"))
        val projectDependencies =
            PROJECT_DEPENDENCY.findAll(buildScript).map { match -> match.groupValues[1] }.toSet()
        assertEquals(setOf(":core:model"), projectDependencies)

        val forbiddenImports =
            listOf(
                "com.tingyun.smartmistakebook.core.data.",
                "com.tingyun.smartmistakebook.core.database.",
                "com.tingyun.smartmistakebook.core.mastery.database.",
                "com.tingyun.smartmistakebook.core.student.mistake.database.",
            )
        Files.walk(moduleRoot.resolve("src/main")).use { paths ->
            paths
                .filter { path ->
                    Files.isRegularFile(path) &&
                        (path.toString().endsWith(".kt") || path.toString().endsWith(".java"))
                }
                .forEach { path ->
                    val imports =
                        Files.readAllLines(path).filter { line ->
                            line.trimStart().startsWith("import ")
                        }
                    forbiddenImports.forEach { forbidden ->
                        assertTrue(
                            "$path must not import student, mistake, or mastery storage authority",
                            imports.none { line -> forbidden in line },
                        )
                    }
                }
        }
    }

    private fun node(
        id: String,
        stableCode: String,
        subject: SubjectKind,
    ): KnowledgeNodeEntity =
        KnowledgeNodeEntity(
            knowledgeNodeId = id,
            stableCode = stableCode,
            subject = subject.name,
            displayName = "$SHARED_ALIAS-${subject.name}",
            canonicalName = "$SHARED_ALIAS-${subject.name}",
            nodeKind = "KNOWLEDGE_POINT",
            granularity = "ATOMIC",
            aliasesText = encodeAliases(listOf(SHARED_ALIAS)),
            boundaryMarkdown = null,
            verificationStatus = "HUMAN_REVIEWED",
            parentKnowledgeNodeId = null,
            taxonomyVersion = "taxonomy-v1",
            reviewedAtEpochMillis = 1L,
        )

    private fun locateModuleRoot(): Path {
        var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            val direct = cursor.takeIf(::isKnowledgeModuleRoot)
            if (direct != null) return direct
            val nested = cursor.resolve("core/knowledge-database")
            if (isKnowledgeModuleRoot(nested)) return nested
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate core/knowledge-database from ${System.getProperty("user.dir")}")
    }

    private fun isKnowledgeModuleRoot(path: Path): Boolean =
        Files.isDirectory(path.resolve("src/main")) &&
            Files.isRegularFile(path.resolve("build.gradle.kts"))

    private companion object {
        const val SHARED_ALIAS = "共享别名"
        val PROJECT_DEPENDENCY = Regex("""project\(\"([^\"]+)\"\)""")
    }
}
