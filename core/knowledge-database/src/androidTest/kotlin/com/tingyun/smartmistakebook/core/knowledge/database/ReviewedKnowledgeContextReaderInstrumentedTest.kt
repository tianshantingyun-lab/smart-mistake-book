package com.tingyun.smartmistakebook.core.knowledge.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReviewedKnowledgeContextReaderInstrumentedTest {
    @Test
    fun exactAliasOutranksBoundaryNgramsRegardlessOfInsertionOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstName = "knowledge-ranking-first.knowledge-test.db"
        val secondName = "knowledge-ranking-second.knowledge-test.db"
        listOf(firstName, secondName).forEach(context::deleteDatabase)
        try {
            val base = syntheticBundle()
            val exactId = nodeId(SubjectKind.MATH, 0)
            val boundaryId = nodeId(SubjectKind.MATH, 1)
            val nodes =
                base.nodes.map { node ->
                    when (node.knowledgeNodeId) {
                        exactId ->
                            node.copy(
                                aliasesText =
                                    encodeAliases(decodeAliases(node.aliasesText) + RANKING_ALIAS),
                            )
                        boundaryId ->
                            node.copy(
                                boundaryMarkdown =
                                    "A long reviewed boundary containing $RANKING_ALIAS among noise.",
                            )
                        else -> node
                    }
                }
            val features = KnowledgeSearchIndexBuilder.build(nodes)
            fun rankedBundle(
                orderedNodes: List<KnowledgeNodeEntity>,
                orderedFeatures: List<KnowledgeSearchFeatureEntity>,
            ) = KnowledgePackInstallBundle(
                manifest =
                    base.manifest.copy(
                        contentFingerprint = "0".repeat(64),
                        searchFeatureCount = orderedFeatures.size,
                    ),
                nodes = orderedNodes,
                sources = base.sources,
                nodeSourceBindings = base.nodeSourceBindings,
                relations = base.relations,
                searchFeatures = orderedFeatures,
                materials = base.materials,
                materialBindings = base.materialBindings,
            ).withRecomputedContentFingerprint()

            HighSchoolKnowledgePackBuilder.openForTest(context, firstName).use { builder ->
                builder.replacePack(rankedBundle(nodes, features))
            }
            HighSchoolKnowledgePackBuilder.openForTest(context, secondName).use { builder ->
                builder.replacePack(rankedBundle(nodes.reversed(), features.reversed()))
            }
            val first =
                HighSchoolKnowledgeCatalogFactory.openExistingForTest(context, firstName).use {
                    it.recall(SubjectKind.MATH, RANKING_ALIAS)
                        .map { hit -> hit.node.ref.knowledgeNodeId }
                }
            val second =
                HighSchoolKnowledgeCatalogFactory.openExistingForTest(context, secondName).use {
                    it.recall(SubjectKind.MATH, RANKING_ALIAS)
                        .map { hit -> hit.node.ref.knowledgeNodeId }
                }

            assertEquals(first, second)
            assertEquals(exactId, first.first())
            assertTrue(first.indexOf(boundaryId) > first.indexOf(exactId))
        } finally {
            listOf(firstName, secondName).forEach(context::deleteDatabase)
        }
    }

    @Test
    fun neighborhoodUsesConstantBatchQueryCount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "knowledge-neighborhood-count.knowledge-test.db"
        context.deleteDatabase(databaseName)
        try {
            HighSchoolKnowledgePackBuilder.openForTest(context, databaseName).use { builder ->
                builder.replacePack(syntheticBundle())
            }
            val statements = mutableListOf<String>()
            HighSchoolKnowledgeCatalogFactory.openExistingForTest(
                context = context,
                databaseName = databaseName,
                queryObserver = statements::add,
            ).use { catalog ->
                val neighborhood =
                    catalog.readNeighborhood(
                        subject = SubjectKind.MATH,
                        query = SHARED_ALIAS,
                    )
                assertTrue(neighborhood.directHits.isNotEmpty())
                assertTrue(neighborhood.relatedNodes.isNotEmpty())
            }

            val selectStatements =
                statements.filter { sql ->
                    sql.trimStart().startsWith("SELECT", ignoreCase = true) ||
                        sql.trimStart().startsWith("WITH", ignoreCase = true)
                }
            assertTrue(
                "Neighborhood query count must remain constant: $selectStatements",
                selectStatements.size in 5..7,
            )
            assertEquals(
                1,
                selectStatements.count { sql ->
                    "FROM knowledge_node_relation" in sql
                },
            )
            val recallIndex =
                selectStatements.indexOfFirst { sql ->
                    "FROM knowledge_search_feature AS feature" in sql &&
                        "node.taxonomy_version" in sql
                }
            val relationIndex =
                selectStatements.indexOfFirst { sql -> "FROM knowledge_node_relation" in sql }
            assertTrue(
                "Subject/version-scoped recall must precede relation expansion: $selectStatements",
                recallIndex >= 0 && relationIndex > recallIndex,
            )
            assertTrue(
                selectStatements.count { sql ->
                    "FROM knowledge_node AS node" in sql &&
                        "knowledge_node_id IN" in sql
                } <= 2,
            )
            val relationPlan = readNeighborhoodRelationPlan(context, databaseName)
            assertTrue(
                "Batch relation lookup must use both endpoint indexes: $relationPlan",
                relationPlan.count { detail ->
                    (
                        detail.contains("SEARCH outgoing", ignoreCase = true) ||
                            detail.contains("SEARCH incoming", ignoreCase = true)
                    ) &&
                        detail.contains("INDEX", ignoreCase = true)
                } >= 2,
            )
            assertTrue(
                "Batch relation lookup must not scan all relations: $relationPlan",
                relationPlan.none { detail ->
                    Regex(
                        "\\bSCAN\\s+(outgoing|incoming|knowledge_node_relation)\\b",
                        RegexOption.IGNORE_CASE,
                    ).containsMatchIn(detail)
                },
            )
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun neighborhoodRelationBudgetIsPartitionedFairlyAcrossDirectMatches() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "knowledge-neighborhood-fairness.knowledge-test.db"
        context.deleteDatabase(databaseName)
        try {
            val base = syntheticBundle()
            val directIds =
                (0 until ReviewedKnowledgeContextReader.MAX_DIRECT_NODES)
                    .map { index -> nodeId(SubjectKind.MATH, index) }
            val relations =
                directIds.flatMapIndexed { directIndex, directId ->
                    (0 until 8).map { offset ->
                        KnowledgeNodeRelationEntity(
                            relationId = "fair.$directIndex.$offset",
                            subject = SubjectKind.MATH.name,
                            fromKnowledgeNodeId = directId,
                            toKnowledgeNodeId =
                                nodeId(
                                    SubjectKind.MATH,
                                    RELATED_NODE_START + offset,
                                ),
                            relationType = "RELATED_TO",
                            taxonomyVersion = TAXONOMY_VERSION,
                            sourceId = sourceId(SubjectKind.MATH),
                            sourceLocator = "fair/$directIndex/$offset",
                            reviewedAtEpochMillis = REVIEWED_AT,
                        )
                    }
                }
            val bundle =
                KnowledgePackInstallBundle(
                    manifest =
                        base.manifest.copy(
                            relationCount = relations.size,
                            contentFingerprint = "0".repeat(64),
                        ),
                    nodes = base.nodes,
                    sources = base.sources,
                    nodeSourceBindings = base.nodeSourceBindings,
                    relations = relations,
                    searchFeatures = base.searchFeatures,
                    materials = base.materials,
                    materialBindings = base.materialBindings,
                ).withRecomputedContentFingerprint()
            HighSchoolKnowledgePackBuilder.openForTest(context, databaseName).use { builder ->
                builder.replacePack(bundle)
            }

            val neighborhood =
                HighSchoolKnowledgeCatalogFactory.openExistingForTest(
                    context,
                    databaseName,
                ).use { catalog ->
                    catalog.readNeighborhood(
                        subject = SubjectKind.MATH,
                        query = SHARED_ALIAS,
                    )
                }

            assertEquals(ReviewedKnowledgeContextReader.MAX_RELATIONS, neighborhood.relations.size)
            directIds.forEach { directId ->
                assertEquals(
                    ReviewedKnowledgeContextReader.MAX_RELATIONS_PER_DIRECT_NODE,
                    neighborhood.relations.count { relation ->
                        relation.from.knowledgeNodeId == directId ||
                            relation.to.knowledgeNodeId == directId
                    },
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun syntheticCatalogUsesSubjectFeatureIndexAndReturnsStableBoundedContext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionKnowledgePack(context)
        try {
            val bundle = syntheticBundle()
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            KnowledgePackActivationManager.activateNext(
                context = context,
                authorization =
                    KnowledgePackActivationAuthorization(
                        packId = bundle.manifest.packId,
                        knowledgePackVersion = bundle.manifest.knowledgePackVersion,
                        taxonomyVersion = bundle.manifest.taxonomyVersion,
                        generation = 1L,
                        expectedContentFingerprint = bundle.manifest.contentFingerprint,
                    ),
            )

            val request =
                ReviewedKnowledgeContextRequest(
                    subject = SubjectKind.MATH,
                    query = SHARED_ALIAS,
                )
            val first =
                ReviewedKnowledgeContextReaderFactory.open(context).use { reader ->
                    reader.read(request)
                }
            val second =
                ReviewedKnowledgeContextReaderFactory.open(context).use { reader ->
                    reader.read(request)
                }
            assertEquals(
                first.nodes.map(ReviewedKnowledgeContextNode::canonicalName),
                second.nodes.map(ReviewedKnowledgeContextNode::canonicalName),
            )
            assertEquals(
                first.relations.map { relation ->
                    Triple(relation.fromAlias, relation.toAlias, relation.relationship)
                },
                second.relations.map { relation ->
                    Triple(relation.fromAlias, relation.toAlias, relation.relationship)
                },
            )
            assertTrue(first.nodes.all { node -> node.subject == SubjectKind.MATH })
            assertTrue(
                first.nodes.size <=
                    ReviewedKnowledgeContextReader.MAX_DIRECT_NODES +
                    ReviewedKnowledgeContextReader.MAX_RELATED_NODES,
            )
            assertTrue(first.relations.size <= ReviewedKnowledgeContextReader.MAX_RELATIONS)
            assertEquals(listOf("material-1"), first.teachingMaterials.map { it.alias })
            assertTrue(
                first.teachingMaterials.sumOf { material -> material.markdownCharacterCount } <=
                    ReviewedKnowledgeContextReader.MAX_TEACHING_MATERIAL_MARKDOWN_CHARS,
            )

            val physics =
                ReviewedKnowledgeContextReaderFactory.open(context).use { reader ->
                    reader.read(
                        ReviewedKnowledgeContextRequest(
                            subject = SubjectKind.PHYSICS,
                            query = SHARED_ALIAS,
                        ),
                    )
                }
            assertTrue(physics.nodes.isNotEmpty())
            assertTrue(physics.nodes.all { node -> node.subject == SubjectKind.PHYSICS })

            val queryPlan = readRecallQueryPlan(context, SHARED_ALIAS)
            assertTrue(
                "Recall must search the subject-first feature index: $queryPlan",
                queryPlan.any { detail ->
                    (
                        detail.contains("SEARCH feature", ignoreCase = true) ||
                            detail.contains(
                                "SEARCH knowledge_search_feature",
                                ignoreCase = true,
                            )
                    ) &&
                        detail.contains("INDEX", ignoreCase = true) &&
                        (
                            detail.contains(
                                "sqlite_autoindex_knowledge_search_feature_1",
                                ignoreCase = true,
                            ) ||
                                (
                                    detail.contains("subject=?", ignoreCase = true) &&
                                        detail.contains("search_feature=?", ignoreCase = true)
                                )
                        )
                },
            )
            assertTrue(
                "Recall must not scan the complete feature table: $queryPlan",
                queryPlan.none { detail ->
                    Regex(
                        "\\bSCAN\\s+(feature|knowledge_search_feature)\\b",
                        RegexOption.IGNORE_CASE,
                    ).containsMatchIn(detail)
                },
            )
        } finally {
            clearProductionKnowledgePack(context)
        }
    }

    private fun readRecallQueryPlan(
        context: Context,
        query: String,
    ): List<String> {
        val feature = KnowledgeSearchNormalizer.queryFeatures(query).first()
        val databaseFile = context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        return SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery(
                """
                EXPLAIN QUERY PLAN
                SELECT node.knowledge_node_id
                FROM knowledge_search_feature AS feature
                INNER JOIN knowledge_node AS node
                  ON node.knowledge_node_id = feature.knowledge_node_id
                 AND node.subject = feature.subject
                WHERE feature.subject = ?
                  AND node.taxonomy_version = ?
                  AND feature.search_feature IN (?)
                GROUP BY node.knowledge_node_id
                ORDER BY node.stable_code ASC, node.knowledge_node_id ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    SubjectKind.MATH.name,
                    TAXONOMY_VERSION,
                    feature,
                    ReviewedKnowledgeContextReader.MAX_DIRECT_NODES.toString(),
                ),
            ).use { cursor ->
                buildList {
                    val detailIndex = cursor.getColumnIndexOrThrow("detail")
                    while (cursor.moveToNext()) add(cursor.getString(detailIndex))
                }
            }
        }
    }

    private fun readNeighborhoodRelationPlan(
        context: Context,
        databaseName: String,
    ): List<String> =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery(
                """
                EXPLAIN QUERY PLAN
                WITH relation_candidates AS (
                  SELECT outgoing.*,
                         outgoing.from_knowledge_node_id AS origin_knowledge_node_id
                  FROM knowledge_node_relation AS outgoing
                  WHERE outgoing.subject = ?
                    AND outgoing.taxonomy_version = ?
                    AND outgoing.from_knowledge_node_id IN (?, ?)
                  UNION ALL
                  SELECT incoming.*,
                         incoming.to_knowledge_node_id AS origin_knowledge_node_id
                  FROM knowledge_node_relation AS incoming
                  WHERE incoming.subject = ?
                    AND incoming.taxonomy_version = ?
                    AND incoming.to_knowledge_node_id IN (?, ?)
                ),
                ranked_relations AS (
                  SELECT relation_candidates.*,
                         ROW_NUMBER() OVER (
                           PARTITION BY origin_knowledge_node_id
                           ORDER BY relation_type ASC,
                                    from_knowledge_node_id ASC,
                                    to_knowledge_node_id ASC,
                                    relation_id ASC
                         ) AS origin_rank
                  FROM relation_candidates
                )
                SELECT *
                FROM ranked_relations
                WHERE origin_rank <= ?
                ORDER BY origin_knowledge_node_id ASC,
                         origin_rank ASC,
                         relation_id ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    SubjectKind.MATH.name,
                    TAXONOMY_VERSION,
                    nodeId(SubjectKind.MATH, 0),
                    nodeId(SubjectKind.MATH, 1),
                    SubjectKind.MATH.name,
                    TAXONOMY_VERSION,
                    nodeId(SubjectKind.MATH, 0),
                    nodeId(SubjectKind.MATH, 1),
                    ReviewedKnowledgeContextReader.MAX_RELATIONS_PER_DIRECT_NODE.toString(),
                    ReviewedKnowledgeContextReader.MAX_RELATIONS.toString(),
                ),
            ).use { cursor ->
                buildList {
                    val detailIndex = cursor.getColumnIndexOrThrow("detail")
                    while (cursor.moveToNext()) add(cursor.getString(detailIndex))
                }
            }
        }

    private fun syntheticBundle(): KnowledgePackInstallBundle {
        val subjects = enumValues<SubjectKind>().filter { subject -> subject != SubjectKind.GENERAL }
        val nodes =
            subjects.flatMap { subject ->
                (0 until NODES_PER_SUBJECT).map { index ->
                    KnowledgeNodeEntity(
                        knowledgeNodeId = nodeId(subject, index),
                        stableCode =
                            "synthetic.${subject.name.lowercase()}." +
                                index.toString().padStart(3, '0'),
                        subject = subject.name,
                        displayName = "${subject.name} synthetic knowledge $index",
                        canonicalName = "${subject.name} synthetic concept $index",
                        nodeKind = KnowledgeNodeKind.TOPIC.name,
                        granularity = KnowledgeNodeGranularity.TOPIC.name,
                        aliasesText =
                            encodeAliases(
                                listOf(
                                    SHARED_ALIAS,
                                    "${subject.name.lowercase()}-alias-$index",
                                ),
                            ),
                        boundaryMarkdown = "Synthetic high-school boundary $index.",
                        verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
                        parentKnowledgeNodeId = null,
                        taxonomyVersion = TAXONOMY_VERSION,
                        reviewedAtEpochMillis = REVIEWED_AT,
                    )
                }
            }
        val sources =
            subjects.map { subject ->
                KnowledgeSourceEntity(
                    sourceId = sourceId(subject),
                    subject = subject.name,
                    sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD.name,
                    title = "${subject.name} synthetic reviewed source",
                    publisher = "Synthetic publisher",
                    edition = "Synthetic edition",
                    sourceUri = null,
                    licenseStatus = KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL.name,
                    contentUsePolicy =
                        KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY.name,
                    contentFingerprint =
                        (subject.ordinal + 1).toString(16).padStart(64, '0'),
                    licenseExpression = null,
                    licenseUri = null,
                    attributionText = "Synthetic query-plan fixture",
                    reviewedAtEpochMillis = REVIEWED_AT,
                )
            }
        val relations =
            (RELATED_NODE_START until RELATED_NODE_START + RELATED_NODE_COUNT).map { target ->
                KnowledgeNodeRelationEntity(
                    relationId = "relation.synthetic.math.0.$target",
                    subject = SubjectKind.MATH.name,
                    fromKnowledgeNodeId = nodeId(SubjectKind.MATH, 0),
                    toKnowledgeNodeId = nodeId(SubjectKind.MATH, target),
                    relationType = "RELATED_TO",
                    taxonomyVersion = TAXONOMY_VERSION,
                    sourceId = sourceId(SubjectKind.MATH),
                    sourceLocator = "synthetic/relations/$target",
                    reviewedAtEpochMillis = REVIEWED_AT,
                )
            }
        val material =
            KnowledgeTeachingMaterialEntity(
                materialId = SYNTHETIC_MATERIAL_ID,
                stableCode = "material.synthetic.math.method",
                subject = SubjectKind.MATH.name,
                materialType = KnowledgeTeachingMaterialType.METHOD_MODEL.name,
                title = "Synthetic reviewed method",
                summaryMarkdown = "A bounded reviewed summary.",
                applicabilityMarkdown = "Use for synthetic retrieval verification.",
                contentMarkdown = "A reviewed method model, not an assessment item.",
                boundaryMarkdown = "Synthetic test boundary.",
                derivationKind = KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS.name,
                sourceId = sourceId(SubjectKind.MATH),
                sourceLocator = "synthetic/material",
                contentFingerprint = "e".repeat(64),
                reviewedAtEpochMillis = REVIEWED_AT,
            )
        val searchFeatures = KnowledgeSearchIndexBuilder.build(nodes)
        return KnowledgePackInstallBundle(
            manifest =
                KnowledgePackManifestEntity(
                    manifestKey = ACTIVE_MANIFEST_KEY,
                    packId = "synthetic-general-pack",
                    schemaVersion = HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION,
                    knowledgePackVersion = PACK_VERSION,
                    taxonomyVersion = TAXONOMY_VERSION,
                    searchIndexVersion = "synthetic-search-v1",
                    contentFingerprint = "0".repeat(64),
                    builtAtEpochMillis = REVIEWED_AT,
                    nodeCount = nodes.size,
                    sourceCount = sources.size,
                    relationCount = relations.size,
                    materialCount = 1,
                    searchFeatureCount = searchFeatures.size,
                ),
            nodes = nodes,
            sources = sources,
            nodeSourceBindings =
                nodes.map { node ->
                    KnowledgeNodeSourceBindingEntity(
                        knowledgeNodeId = node.knowledgeNodeId,
                        sourceId = sourceId(SubjectKind.valueOf(node.subject)),
                        sourceLocator = "synthetic/${node.stableCode}",
                        derivationNote = "Synthetic reviewed general-data binding.",
                        reviewedAtEpochMillis = REVIEWED_AT,
                    )
                },
            relations = relations,
            searchFeatures = searchFeatures,
            materials = listOf(material),
            materialBindings =
                listOf(
                    KnowledgeTeachingMaterialNodeBindingEntity(
                        materialId = SYNTHETIC_MATERIAL_ID,
                        knowledgeNodeId = nodeId(SubjectKind.MATH, 0),
                        role = KnowledgeMaterialNodeRole.PRIMARY.name,
                    ),
                ),
        ).withRecomputedContentFingerprint()
    }

    private fun clearProductionKnowledgePack(context: Context) {
        HighSchoolKnowledgePackBuilder.discardNext(context)
        context.deleteDatabase(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        val directory =
            requireNotNull(context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile)
        listOf(
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
        ).forEach { name ->
            val file = File(directory, name)
            if (file.exists()) assertTrue(file.delete())
        }
    }

    private fun nodeId(
        subject: SubjectKind,
        index: Int,
    ): String = "node.synthetic.${subject.name.lowercase()}.$index"

    private fun sourceId(subject: SubjectKind): String =
        "source.synthetic.${subject.name.lowercase()}"

    private companion object {
        const val NODES_PER_SUBJECT = 32
        const val RELATED_NODE_START = 20
        const val RELATED_NODE_COUNT = 4
        const val SHARED_ALIAS = "shared synthetic lookup"
        const val RANKING_ALIAS = "stationary point"
        const val TAXONOMY_VERSION = "synthetic-taxonomy-v1"
        const val PACK_VERSION = "synthetic-content-v1"
        const val SYNTHETIC_MATERIAL_ID = "material.synthetic.math"
        const val REVIEWED_AT = 1_700_000_000_000L
    }
}
