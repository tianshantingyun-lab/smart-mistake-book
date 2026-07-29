package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HighSchoolKnowledgeCatalogContractTest {
    @Test
    fun productionDatabaseNameIsFixed() {
        assertEquals("high-school-knowledge.db", HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
    }

    @Test
    fun runtimeCatalogDeclaresOnlyReadOperations() {
        assertEquals(
            setOf("expandRelations", "findNode", "readManifest", "recall"),
            HighSchoolKnowledgeCatalog::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        val forbiddenFragments =
            listOf("insert", "update", "delete", "write", "install", "raw", "database", "attach")
        HighSchoolKnowledgeCatalog::class.java.methods.forEach { method ->
            forbiddenFragments.forEach { fragment ->
                assertTrue(
                    "Runtime catalog unexpectedly exposes ${method.name}",
                    !method.name.contains(fragment, ignoreCase = true),
                )
            }
        }
        assertEquals(
            KnowledgeNodeRef::class.java,
            HighSchoolKnowledgeCatalog::class.java
                .getDeclaredMethod("findNode", KnowledgeNodeRef::class.java, kotlin.coroutines.Continuation::class.java)
                .parameterTypes
                .first(),
        )
    }

    @Test
    fun searchNormalizationIsLocaleIndependentAndDeterministic() {
        val first = KnowledgeSearchNormalizer.queryFeatures("  二次函数：图像  ")
        val second = KnowledgeSearchNormalizer.queryFeatures("二次函数：图像")

        assertEquals(first, second)
        assertTrue("二次函数" in first)
        assertTrue("二次函数图像" in first)
        assertTrue("图像" in first)
        assertEquals(
            KnowledgeSearchNormalizer.queryFeatures("ＡＢＣ"),
            KnowledgeSearchNormalizer.queryFeatures("abc"),
        )
    }

    @Test
    fun searchIndexDoesNotDependOnNodeOrAliasIterationOrder() {
        val first =
            listOf(
                node(
                    id = "node.quadratic",
                    stableCode = "math.quadratic",
                    aliases = listOf("抛物线", "二次多项式"),
                ),
                node(
                    id = "node.function",
                    stableCode = "math.function",
                    aliases = listOf("映射"),
                ),
            )
        val second =
            listOf(
                node(
                    id = "node.function",
                    stableCode = "math.function",
                    aliases = listOf("映射"),
                ),
                node(
                    id = "node.quadratic",
                    stableCode = "math.quadratic",
                    aliases = listOf("二次多项式", "抛物线"),
                ),
            )

        assertEquals(
            KnowledgeSearchIndexBuilder.build(first),
            KnowledgeSearchIndexBuilder.build(second),
        )
    }

    @Test
    fun installerRejectsParentCyclesBeforeWriting() {
        val nodes =
            listOf(
                node(
                    id = "node.a",
                    stableCode = "math.a",
                    aliases = emptyList(),
                    parentId = "node.b",
                ),
                node(
                    id = "node.b",
                    stableCode = "math.b",
                    aliases = emptyList(),
                    parentId = "node.a",
                ),
            )
        val features = KnowledgeSearchIndexBuilder.build(nodes)
        val bundle =
            KnowledgePackInstallBundle(
                manifest = manifest(nodes.size, features.size),
                nodes = nodes,
                sources = emptyList(),
                nodeSourceBindings = emptyList(),
                relations = emptyList(),
                searchFeatures = features,
                materials = emptyList(),
                materialBindings = emptyList(),
            )

        assertThrows(IllegalArgumentException::class.java) {
            bundle.validateAndOrderNodes()
        }
    }

    private fun node(
        id: String,
        stableCode: String,
        aliases: List<String>,
        parentId: String? = null,
    ): KnowledgeNodeEntity =
        KnowledgeNodeEntity(
            knowledgeNodeId = id,
            stableCode = stableCode,
            subject = SubjectKind.MATH.name,
            displayName = stableCode,
            canonicalName = stableCode,
            nodeKind = KnowledgeNodeKind.CONCEPT.name,
            granularity = KnowledgeNodeGranularity.ATOMIC.name,
            aliasesText = encodeAliases(aliases),
            boundaryMarkdown = "High-school mathematics boundary.",
            verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
            parentKnowledgeNodeId = parentId,
            taxonomyVersion = TAXONOMY_VERSION,
            reviewedAtEpochMillis = 1L,
        )

    private fun manifest(
        nodeCount: Int,
        featureCount: Int,
    ): KnowledgePackManifestEntity =
        KnowledgePackManifestEntity(
            manifestKey = ACTIVE_MANIFEST_KEY,
            packId = "test-pack",
            schemaVersion = HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION,
            knowledgePackVersion = "test-content-v1",
            taxonomyVersion = TAXONOMY_VERSION,
            searchIndexVersion = "test-search-v1",
            contentFingerprint = "a".repeat(64),
            builtAtEpochMillis = 1L,
            nodeCount = nodeCount,
            sourceCount = 0,
            relationCount = 0,
            materialCount = 0,
            searchFeatureCount = featureCount,
        )

    private companion object {
        const val TAXONOMY_VERSION = "test-taxonomy-v1"
    }
}
