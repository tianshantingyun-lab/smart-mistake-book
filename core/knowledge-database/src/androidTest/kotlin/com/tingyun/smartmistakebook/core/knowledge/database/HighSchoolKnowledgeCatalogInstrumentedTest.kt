package com.tingyun.smartmistakebook.core.knowledge.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighSchoolKnowledgeCatalogInstrumentedTest {
    @Test
    fun installedPackSupportsDeterministicReadOnlyCatalogQueries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        try {
            val missingDatabaseFailure =
                runCatching {
                    HighSchoolKnowledgeCatalogFactory.openExistingForTest(
                        context,
                        TEST_DATABASE_NAME,
                    )
                }.exceptionOrNull()
            assertNotNull(missingDatabaseFailure)
            assertTrue(!context.getDatabasePath(TEST_DATABASE_NAME).exists())

            val bundle = fixtureBundle()
            HighSchoolKnowledgePackBuilder.openForTest(context, TEST_DATABASE_NAME).use { builder ->
                builder.replacePack(bundle)
            }

            HighSchoolKnowledgeCatalogFactory.openExistingForTest(
                context,
                TEST_DATABASE_NAME,
            ).use { catalog ->
                assertEquals(bundle.manifest.toCatalogManifest(), catalog.readManifest())

                val quadraticRef = nodeRef(QUADRATIC_NODE_ID)
                assertEquals(
                    "二次函数",
                    catalog.findNode(quadraticRef)?.displayName,
                )

                val aliasResults = catalog.recall(SubjectKind.MATH, "抛物线")
                assertEquals(quadraticRef, aliasResults.first().node.ref)

                val fullTextResults = catalog.recall(SubjectKind.MATH, "二次函数图像性质")
                assertEquals(quadraticRef, fullTextResults.first().node.ref)
                assertEquals(
                    fullTextResults,
                    catalog.recall(SubjectKind.MATH, "二次函数图像性质"),
                )

                val outgoing =
                    catalog.expandRelations(
                        origin = quadraticRef,
                        direction = KnowledgeRelationDirection.OUTGOING,
                        relationTypes = setOf("PART_OF"),
                    )
                assertEquals(1, outgoing.size)
                assertEquals(FUNCTION_NODE_ID, outgoing.single().to.knowledgeNodeId)

                val incoming =
                    catalog.expandRelations(
                        origin = nodeRef(FUNCTION_NODE_ID),
                        direction = KnowledgeRelationDirection.INCOMING,
                    )
                assertEquals(outgoing, incoming)
                assertNotNull(catalog.findNode(incoming.single().from))
                assertTrue(catalog.recall(SubjectKind.PHYSICS, "二次函数").isEmpty())

                val wrongPackRef =
                    KnowledgeNodeRef(
                        subject = SubjectKind.MATH,
                        knowledgeNodeId = QUADRATIC_NODE_ID,
                        taxonomyVersion = TAXONOMY_VERSION,
                        knowledgePackVersion = "other-pack-v1",
                    )
                assertEquals(null, catalog.findNode(wrongPackRef))
                assertTrue(catalog.expandRelations(wrongPackRef).isEmpty())
            }

            val readOnlyWriteFailure =
                runCatching {
                    HighSchoolKnowledgeCatalogFactory
                        .attemptInstallThroughReadOnlyConnectionForTest(
                            context = context,
                            databaseName = TEST_DATABASE_NAME,
                            bundle = bundle,
                        )
                }.exceptionOrNull()
            assertNotNull("PRAGMA query_only must reject catalog writes", readOnlyWriteFailure)

            HighSchoolKnowledgeCatalogFactory.openExistingForTest(
                context,
                TEST_DATABASE_NAME,
            ).use { catalog ->
                assertEquals(
                    bundle.manifest.toCatalogManifest(),
                    catalog.readManifest(),
                )
            }
        } finally {
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }

    private fun nodeRef(knowledgeNodeId: String): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = knowledgeNodeId,
            taxonomyVersion = TAXONOMY_VERSION,
            knowledgePackVersion = KNOWLEDGE_PACK_VERSION,
        )

    private fun fixtureBundle(): KnowledgePackInstallBundle {
        val nodes =
            listOf(
                KnowledgeNodeEntity(
                    knowledgeNodeId = FUNCTION_NODE_ID,
                    stableCode = "math.function",
                    subject = SubjectKind.MATH.name,
                    displayName = "函数",
                    canonicalName = "函数",
                    nodeKind = KnowledgeNodeKind.TOPIC.name,
                    granularity = KnowledgeNodeGranularity.TOPIC.name,
                    aliasesText = encodeAliases(listOf("函数概念")),
                    boundaryMarkdown = "高中函数的定义、表示与基本性质。",
                    verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
                    parentKnowledgeNodeId = null,
                    taxonomyVersion = TAXONOMY_VERSION,
                    reviewedAtEpochMillis = REVIEWED_AT,
                ),
                KnowledgeNodeEntity(
                    knowledgeNodeId = QUADRATIC_NODE_ID,
                    stableCode = "math.function.quadratic",
                    subject = SubjectKind.MATH.name,
                    displayName = "二次函数",
                    canonicalName = "二次函数",
                    nodeKind = KnowledgeNodeKind.CONCEPT.name,
                    granularity = KnowledgeNodeGranularity.ATOMIC.name,
                    aliasesText = encodeAliases(listOf("抛物线", "二次多项式函数")),
                    boundaryMarkdown = "研究二次函数的图像、开口、对称轴与性质。",
                    verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
                    parentKnowledgeNodeId = FUNCTION_NODE_ID,
                    taxonomyVersion = TAXONOMY_VERSION,
                    reviewedAtEpochMillis = REVIEWED_AT,
                ),
            )
        val source =
            KnowledgeSourceEntity(
                sourceId = SOURCE_ID,
                subject = SubjectKind.MATH.name,
                sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD.name,
                title = "普通高中数学课程标准",
                publisher = "测试出版社",
                edition = "测试版",
                sourceUri = null,
                licenseStatus = KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL.name,
                contentUsePolicy =
                    KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY.name,
                contentFingerprint = "b".repeat(64),
                licenseExpression = null,
                licenseUri = null,
                attributionText = "测试来源",
                reviewedAtEpochMillis = REVIEWED_AT,
            )
        val relation =
            KnowledgeNodeRelationEntity(
                relationId = "relation.quadratic.part-of.function",
                subject = SubjectKind.MATH.name,
                fromKnowledgeNodeId = QUADRATIC_NODE_ID,
                toKnowledgeNodeId = FUNCTION_NODE_ID,
                relationType = "PART_OF",
                taxonomyVersion = TAXONOMY_VERSION,
                sourceId = SOURCE_ID,
                sourceLocator = "课程标准/函数",
                reviewedAtEpochMillis = REVIEWED_AT,
            )
        val material =
            KnowledgeTeachingMaterialEntity(
                materialId = MATERIAL_ID,
                stableCode = "material.math.quadratic.concept",
                subject = SubjectKind.MATH.name,
                materialType = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION.name,
                title = "二次函数概念说明",
                summaryMarkdown = "二次函数的简要说明。",
                applicabilityMarkdown = "适用于二次函数概念复习。",
                contentMarkdown = "二次函数可写成 \$y=ax^2+bx+c\$。",
                boundaryMarkdown = "不包含大学阶段的二次型。",
                derivationKind = KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS.name,
                sourceId = SOURCE_ID,
                sourceLocator = "课程标准/函数",
                contentFingerprint = "c".repeat(64),
                reviewedAtEpochMillis = REVIEWED_AT,
            )
        val searchFeatures = KnowledgeSearchIndexBuilder.build(nodes)

        return KnowledgePackInstallBundle(
            manifest =
                KnowledgePackManifestEntity(
                    manifestKey = ACTIVE_MANIFEST_KEY,
                    packId = "high-school-test-pack",
                    schemaVersion = HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION,
                    knowledgePackVersion = KNOWLEDGE_PACK_VERSION,
                    taxonomyVersion = TAXONOMY_VERSION,
                    searchIndexVersion = "test-search-v1",
                    contentFingerprint = "a".repeat(64),
                    builtAtEpochMillis = REVIEWED_AT,
                    nodeCount = nodes.size,
                    sourceCount = 1,
                    relationCount = 1,
                    materialCount = 1,
                    searchFeatureCount = searchFeatures.size,
                ),
            nodes = nodes,
            sources = listOf(source),
            nodeSourceBindings =
                listOf(
                    KnowledgeNodeSourceBindingEntity(
                        knowledgeNodeId = QUADRATIC_NODE_ID,
                        sourceId = SOURCE_ID,
                        sourceLocator = "课程标准/函数",
                        derivationNote = "依据课程标准人工复核。",
                        reviewedAtEpochMillis = REVIEWED_AT,
                    ),
                ),
            relations = listOf(relation),
            searchFeatures = searchFeatures,
            materials = listOf(material),
            materialBindings =
                listOf(
                    KnowledgeTeachingMaterialNodeBindingEntity(
                        materialId = MATERIAL_ID,
                        knowledgeNodeId = QUADRATIC_NODE_ID,
                        role = KnowledgeMaterialNodeRole.PRIMARY.name,
                    ),
                ),
        )
    }

    private companion object {
        const val TAXONOMY_VERSION = "high-school-test-taxonomy-v1"
        const val KNOWLEDGE_PACK_VERSION = "test-content-v1"
        const val FUNCTION_NODE_ID = "node.math.function"
        const val QUADRATIC_NODE_ID = "node.math.function.quadratic"
        const val SOURCE_ID = "source.math.curriculum"
        const val MATERIAL_ID = "material.math.quadratic"
        const val TEST_DATABASE_NAME = "high-school-catalog-slice.knowledge-test.db"
        const val REVIEWED_AT = 1_700_000_000_000L
    }
}
