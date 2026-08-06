package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogDebugFixtures
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogDisplayOrderPage
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNeighborhood
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNode
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayBatch
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogRelation
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogSearchHit
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogTeachingMaterial
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgePackManifest
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeRecallQuery
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeRelationDirection
import com.tingyun.smartmistakebook.core.knowledge.database.VerifiedKnowledgeNodeHandle
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseCatalogProvenance
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedProblemKnowledgeContextRepositoryTest {
    @Test
    fun aliasRecallExpandsReviewedPrerequisiteFromIndependentCatalog() = runBlocking {
        val topic = node(
            id = "math-topic-extrema",
            canonicalName = "函数最值",
            kind = KnowledgeNodeKind.TOPIC,
            granularity = KnowledgeNodeGranularity.TOPIC,
        )
        val dependent = node(
            id = "math-atomic-extrema-candidates",
            canonicalName = "确定函数最值的候选位置",
            aliases = listOf("极值候选点"),
            parentRef = topic.ref,
        )
        val prerequisite = node(
            id = "math-atomic-derivative-zero",
            canonicalName = "求导数为零的位置",
            aliases = listOf("驻点"),
            parentRef = topic.ref,
        )
        val relation = relation(
            from = prerequisite.ref,
            to = dependent.ref,
            type = "PREREQUISITE_OF",
        )
        val catalog =
            FakeRetrievalCatalog(
                nodes = listOf(topic, dependent, prerequisite),
                directHit = dependent,
                relations = listOf(relation),
            )
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)

        val context = repository.read(SubjectKind.MATH, "极值候选点")

        assertEquals(
            listOf(dependent.ref.knowledgeNodeId, prerequisite.ref.knowledgeNodeId),
            context.map { it.knowledgeNodeId },
        )
        val dependentContext = context.first()
        assertEquals(listOf("极值候选点"), dependentContext.aliases)
        assertEquals("函数最值", dependentContext.parentCanonicalName)
        assertEquals(
            listOf(prerequisite.ref.knowledgeNodeId),
            dependentContext.prerequisiteKnowledgeNodeIds,
        )
        assertEquals(
            KnowledgeBaseCatalogProvenance(
                packId = "pack",
                knowledgePackVersion = PACK_VERSION,
                taxonomyVersion = TAXONOMY_VERSION,
                manifestFingerprint = "a".repeat(64),
                activationGeneration = 7L,
            ),
            dependentContext.catalogProvenance,
        )
        assertEquals(1, context.map { node -> node.catalogProvenance }.distinct().size)
        assertEquals(SubjectKind.MATH, catalog.lastRecallSubject)
        assertEquals("极值候选点", catalog.lastRecallQuery)
        assertEquals(listOf(dependent.ref), catalog.expandedOrigins)
        assertEquals(1, catalog.neighborhoodReadCount)
        assertEquals(0, catalog.findNodeCount)
    }

    @Test
    fun longProblemQueryKeepsHeadMiddleTailAndSubquestionCoverage() = runBlocking {
        val node =
            node(
                id = "math-topic-long-query",
                canonicalName = "尾部关键知识",
                aliases = listOf("中段关键知识"),
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val catalog = FakeRetrievalCatalog(nodes = listOf(node), directHit = node)
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)
        val question =
            buildString {
                append("头部关键知识 ")
                append("甲".repeat(10_000))
                append(" 中段关键知识 ")
                append("乙".repeat(10_000))
                append("（3）尾部关键知识")
            }

        repository.read(SubjectKind.MATH, question)

        val query = checkNotNull(catalog.lastRecallQuery)
        assertTrue(query.length <= HighSchoolKnowledgeCatalog.MAX_QUERY_CHARS)
        assertTrue("头部关键知识" in query)
        assertTrue("中段关键知识" in query)
        assertTrue("尾部关键知识" in query)
    }

    @Test
    fun explicitCurrentSubquestionTakesPriorityOverLongSurroundingText() = runBlocking {
        val focused =
            node(
                id = "math-focused-subquestion",
                canonicalName = "判断晶胞配位数",
                aliases = listOf("当前只求配位数"),
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val catalog = FakeRetrievalCatalog(nodes = listOf(focused), directHit = focused)
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)

        val result =
            repository.read(
                subject = SubjectKind.MATH,
                query =
                    KnowledgeRecallQuery(
                        currentQuestion = "伪前缀 ".repeat(8_000) + "题尾条件",
                        currentSubquestion = "当前只求配位数",
                        surroundingContext = "上一页背景 ".repeat(2_000),
                    ),
            )

        val flattenedQuery = checkNotNull(catalog.lastRecallQuery)
        assertTrue(flattenedQuery, flattenedQuery.startsWith("当前只求配位数"))
        assertEquals(
            flattenedQuery,
            listOf(focused.ref.knowledgeNodeId),
            result.map { it.knowledgeNodeId },
        )
    }

    @Test
    fun modelCandidateContextHasOneTotalCharacterBudget() = runBlocking {
        val directNodes =
            (0 until 6).map { index ->
                node(
                    id = "math-topic-budget-$index",
                    canonicalName = "预算知识$index",
                    aliases =
                        (0 until 8).map { alias ->
                            "预算别名$index-$alias-" + "甲".repeat(72)
                        },
                    kind = KnowledgeNodeKind.TOPIC,
                    granularity = KnowledgeNodeGranularity.TOPIC,
                ).copy(boundaryMarkdown = "边".repeat(600))
            }
        val relatedNodes =
            (0 until 12).map { index ->
                node(
                    id = "math-topic-budget-related-$index",
                    canonicalName = "预算关联知识$index",
                    aliases =
                        (0 until 8).map { alias ->
                            "预算关联别名$index-$alias-" + "乙".repeat(68)
                        },
                    kind = KnowledgeNodeKind.TOPIC,
                    granularity = KnowledgeNodeGranularity.TOPIC,
                ).copy(boundaryMarkdown = "界".repeat(600))
            }
        val nodes = directNodes + relatedNodes
        val relations =
            relatedNodes.map { related ->
                relation(
                    from = directNodes.first().ref,
                    to = related.ref,
                    type = "RELATED_TO",
                )
            }
        val repository =
            ReviewedProblemKnowledgeContextRepositoryFactory.create(
                FakeRetrievalCatalog(
                    nodes = nodes,
                    directHits = directNodes,
                    relations = relations,
                ),
            )

        val context = repository.read(SubjectKind.MATH, "预算别名")

        assertTrue(context.isNotEmpty())
        assertTrue(context.size < nodes.size)
        assertTrue(
            context.sumOf { node ->
                node.knowledgeNodeId.length +
                    node.canonicalName.length +
                    node.aliases.sumOf(String::length) +
                    node.parentCanonicalName.orEmpty().length +
                    node.boundaryMarkdown.orEmpty().length +
                    node.prerequisiteKnowledgeNodeIds.sumOf(String::length)
            } <= 12_000,
        )
    }

    @Test
    fun staleOrWrongSubjectHitsFailClosedWithoutInventingIds() = runBlocking {
        val stale =
            node(
                id = "stale-node",
                canonicalName = "旧版知识",
                packVersion = "pack-v0",
                taxonomyVersion = "taxonomy-v0",
            )
        val wrongSubject =
            node(
                id = "physics-node",
                canonicalName = "物理知识",
                subject = SubjectKind.PHYSICS,
            )
        val catalog =
            FakeRetrievalCatalog(
                nodes = listOf(stale, wrongSubject),
                directHits = listOf(stale, wrongSubject),
            )
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)

        assertTrue(repository.read(SubjectKind.MATH, "测试查询").isEmpty())
        assertTrue(repository.read(SubjectKind.GENERAL, "测试查询").isEmpty())
        assertTrue(repository.read(SubjectKind.MATH, "   ").isEmpty())
        assertEquals(1, catalog.manifestReadCount)
        assertEquals(1, catalog.recallCount)
    }

    @Test
    fun confirmationResolvesOnlyReviewedContextOrParentAndReturnsCatalogProof() = runBlocking {
        val topic =
            node(
                id = "math-topic-extrema",
                canonicalName = "函数最值",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val atomic =
            node(
                id = "math-atomic-extrema-candidates",
                canonicalName = "确定函数最值的候选位置",
                aliases = listOf("极值候选点"),
                parentRef = topic.ref,
            )
        val catalog =
            FakeRetrievalCatalog(
                nodes = listOf(topic, atomic),
                directHit = atomic,
            )
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)
        val context = repository.read(SubjectKind.MATH, "极值候选点")

        val atomicProofs =
            repository.verifyConfirmationReferences(
                ReviewedProblemKnowledgeConfirmationRequest(
                    subject = SubjectKind.MATH,
                    knowledgeBaseNodes = context,
                    knowledgeDisplayNames = listOf("函数最值"),
                    preferredKnowledgeNodeIds = setOf(atomic.ref.knowledgeNodeId),
                ),
            )
        val topicProofs =
            repository.verifyConfirmationReferences(
                ReviewedProblemKnowledgeConfirmationRequest(
                    subject = SubjectKind.MATH,
                    knowledgeBaseNodes = context,
                    knowledgeDisplayNames = listOf("函数最值"),
                ),
            )

        assertEquals(listOf(atomic.ref), atomicProofs.map { proof -> proof.ref })
        assertEquals(listOf(topic.ref), topicProofs.map { proof -> proof.ref })
        assertEquals("a".repeat(64), atomicProofs.single().manifestFingerprint)
        assertEquals(7L, atomicProofs.single().activationGeneration)
        assertEquals(5, catalog.batchResolveCount)
        assertEquals(2, catalog.batchVerifyCount)
        assertEquals(0, catalog.findNodeCount)
        assertEquals(0, catalog.verifyReferenceCount)
    }

    @Test
    fun confirmationRequestRejectsDuplicateExactContextBeforeCatalogAccess() = runBlocking {
        val topic =
            node(
                id = "math-topic-duplicate-context",
                canonicalName = "函数最值",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val catalog = FakeRetrievalCatalog(nodes = listOf(topic), directHit = topic)
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)
        val context = repository.read(SubjectKind.MATH, "函数最值").single()
        val countsBeforeRequest = catalog.authorityAccessCounts()

        val rejected =
            runCatching {
                ReviewedProblemKnowledgeConfirmationRequest(
                    subject = SubjectKind.MATH,
                    knowledgeBaseNodes = listOf(context, context),
                    knowledgeDisplayNames = listOf("函数最值"),
                )
            }.isFailure

        assertTrue(rejected)
        assertEquals(countsBeforeRequest, catalog.authorityAccessCounts())
    }

    @Test
    fun exactReferenceBatchUsesOnlyBoundedBatchCatalogOperations() = runBlocking {
        val topic =
            node(
                id = "math-topic-exact-batch",
                canonicalName = "函数最值",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val atomic =
            node(
                id = "math-atomic-exact-batch",
                canonicalName = "确定函数最值的候选位置",
                parentRef = topic.ref,
            )
        val catalog = FakeRetrievalCatalog(nodes = listOf(topic, atomic), directHit = atomic)
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)
        val context = repository.read(SubjectKind.MATH, "确定函数最值的候选位置")

        val proofs =
            repository.verifyExactReferences(
                ReviewedProblemKnowledgeReferenceBatchRequest(
                    subject = SubjectKind.MATH,
                    knowledgeBaseNodes = context,
                    exactKnowledgeNodeIds = listOf(atomic.ref.knowledgeNodeId),
                ),
            )

        assertEquals(listOf(atomic.ref), proofs.map { proof -> proof.ref })
        assertEquals(3, catalog.batchResolveCount)
        assertEquals(1, catalog.batchVerifyCount)
        assertEquals(0, catalog.findNodeCount)
        assertEquals(0, catalog.verifyReferenceCount)
    }

    @Test
    fun staleTaxonomyWithIdenticalContentFailsBeforeResolutionOrProof() = runBlocking {
        val topic =
            node(
                id = "math-topic-stale-taxonomy",
                canonicalName = "函数最值",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val catalog = FakeRetrievalCatalog(nodes = listOf(topic), directHit = topic)
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)
        val current = repository.read(SubjectKind.MATH, "函数最值").single()
        val stale =
            current.copy(
                taxonomyVersion = "taxonomy-v0",
                catalogProvenance =
                    current.catalogProvenance.copy(taxonomyVersion = "taxonomy-v0"),
            )
        val resolvesBefore = catalog.batchResolveCount
        val proofsBefore = catalog.batchVerifyCount

        val rejected =
            runCatching {
                repository.verifyExactReferences(
                    ReviewedProblemKnowledgeReferenceBatchRequest(
                        subject = SubjectKind.MATH,
                        knowledgeBaseNodes = listOf(stale),
                        exactKnowledgeNodeIds = listOf(stale.knowledgeNodeId),
                    ),
                )
            }.isFailure

        assertTrue(rejected)
        assertEquals(resolvesBefore, catalog.batchResolveCount)
        assertEquals(proofsBefore, catalog.batchVerifyCount)
    }

    @Test
    fun sameNodeIdFromDifferentTaxonomiesIsRejectedWithoutCatalogAccess() = runBlocking {
        val topic =
            node(
                id = "math-topic-cross-taxonomy",
                canonicalName = "函数最值",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val catalog = FakeRetrievalCatalog(nodes = listOf(topic), directHit = topic)
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)
        val current = repository.read(SubjectKind.MATH, "函数最值").single()
        val stale =
            current.copy(
                taxonomyVersion = "taxonomy-v0",
                catalogProvenance =
                    current.catalogProvenance.copy(taxonomyVersion = "taxonomy-v0"),
            )
        val countsBefore = catalog.authorityAccessCounts()

        val rejected =
            runCatching {
                ReviewedProblemKnowledgeReferenceBatchRequest(
                    subject = SubjectKind.MATH,
                    knowledgeBaseNodes = listOf(current, stale),
                    exactKnowledgeNodeIds = listOf(current.knowledgeNodeId),
                )
            }.isFailure

        assertTrue(rejected)
        assertEquals(countsBefore, catalog.authorityAccessCounts())
    }

    @Test
    fun mixedManifestAndGenerationContextIsRejectedBeforeCatalogAccess() = runBlocking {
        val first =
            node(
                id = "math-topic-mixed-snapshot-1",
                canonicalName = "混合快照知识一",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val second =
            node(
                id = "math-topic-mixed-snapshot-2",
                canonicalName = "混合快照知识二",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val catalog =
            FakeRetrievalCatalog(
                nodes = listOf(first, second),
                directHits = listOf(first, second),
            )
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)
        val context = repository.read(SubjectKind.MATH, "混合快照知识")
        val mixed =
            context.last().copy(
                catalogProvenance =
                    context.last().catalogProvenance.copy(
                        manifestFingerprint = "b".repeat(64),
                        activationGeneration = 8L,
                    ),
            )
        val countsBefore = catalog.authorityAccessCounts()

        val rejected =
            runCatching {
                ReviewedProblemKnowledgeConfirmationRequest(
                    subject = SubjectKind.MATH,
                    knowledgeBaseNodes = listOf(context.first(), mixed),
                    knowledgeDisplayNames = listOf(context.first().canonicalName),
                )
            }.isFailure

        assertTrue(rejected)
        assertEquals(countsBefore, catalog.authorityAccessCounts())
    }

    @Test
    fun staleActivationGenerationFailsBeforeConfirmationProof() = runBlocking {
        val topic =
            node(
                id = "math-topic-stale-generation",
                canonicalName = "函数最值",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val catalog = FakeRetrievalCatalog(nodes = listOf(topic), directHit = topic)
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)
        val current = repository.read(SubjectKind.MATH, "函数最值").single()
        val stale =
            current.copy(
                catalogProvenance =
                    current.catalogProvenance.copy(activationGeneration = 8L),
            )
        val proofsBefore = catalog.batchVerifyCount

        val rejected =
            runCatching {
                repository.verifyConfirmationReferences(
                    ReviewedProblemKnowledgeConfirmationRequest(
                        subject = SubjectKind.MATH,
                        knowledgeBaseNodes = listOf(stale),
                        knowledgeDisplayNames = listOf(stale.canonicalName),
                    ),
                )
            }.isFailure

        assertTrue(rejected)
        assertEquals(proofsBefore, catalog.batchVerifyCount)
    }

    @Test
    fun exactReferenceVerificationPreservesRequestedOrder() = runBlocking {
        val first =
            node(
                id = "math-topic-order-1",
                canonicalName = "顺序知识一",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val second =
            node(
                id = "math-topic-order-2",
                canonicalName = "顺序知识二",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val catalog =
            FakeRetrievalCatalog(
                nodes = listOf(first, second),
                directHits = listOf(first, second),
            )
        val repository = ReviewedProblemKnowledgeContextRepositoryFactory.create(catalog)
        val context = repository.read(SubjectKind.MATH, "顺序知识")
        val requestedIds = listOf(second.ref.knowledgeNodeId, first.ref.knowledgeNodeId)

        val proofs =
            repository.verifyExactReferences(
                ReviewedProblemKnowledgeReferenceBatchRequest(
                    subject = SubjectKind.MATH,
                    knowledgeBaseNodes = context,
                    exactKnowledgeNodeIds = requestedIds,
                ),
            )

        assertEquals(requestedIds, proofs.map { proof -> proof.ref.knowledgeNodeId })
    }

    @Test
    fun confirmationRejectsContextThatNoLongerMatchesTheCatalog() = runBlocking {
        val topic =
            node(
                id = "math-topic-extrema",
                canonicalName = "函数最值",
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
            )
        val atomic =
            node(
                id = "math-atomic-extrema-candidates",
                canonicalName = "确定函数最值的候选位置",
                parentRef = topic.ref,
            )
        val repository =
            ReviewedProblemKnowledgeContextRepositoryFactory.create(
                FakeRetrievalCatalog(
                    nodes = listOf(topic, atomic),
                    directHit = atomic,
                ),
            )
        val changedContext =
            repository.read(SubjectKind.MATH, "确定函数最值的候选位置")
                .map { context -> context.copy(canonicalName = "已被篡改的知识名称") }

        val rejected =
            runCatching {
                repository.verifyConfirmationReferences(
                    ReviewedProblemKnowledgeConfirmationRequest(
                        subject = SubjectKind.MATH,
                        knowledgeBaseNodes = changedContext,
                        knowledgeDisplayNames = listOf("函数最值"),
                        preferredKnowledgeNodeIds = setOf(atomic.ref.knowledgeNodeId),
                    ),
                )
            }.isFailure

        assertTrue(rejected)
    }

    private fun node(
        id: String,
        canonicalName: String,
        aliases: List<String> = emptyList(),
        kind: KnowledgeNodeKind = KnowledgeNodeKind.PROCEDURE,
        granularity: KnowledgeNodeGranularity = KnowledgeNodeGranularity.ATOMIC,
        parentRef: KnowledgeNodeRef? = null,
        subject: SubjectKind = SubjectKind.MATH,
        packVersion: String = PACK_VERSION,
        taxonomyVersion: String = TAXONOMY_VERSION,
    ) = KnowledgeCatalogNode(
        ref =
            KnowledgeNodeRef(
                subject = subject,
                knowledgeNodeId = id,
                taxonomyVersion = taxonomyVersion,
                knowledgePackVersion = packVersion,
            ),
        stableCode = "$subject:$id",
        subject = subject,
        displayName = canonicalName,
        canonicalName = canonicalName,
        kind = kind,
        granularity = granularity,
        aliases = aliases,
        boundaryMarkdown = null,
        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED,
        parentRef = parentRef,
    )

    private fun relation(
        from: KnowledgeNodeRef,
        to: KnowledgeNodeRef,
        type: String,
    ) = KnowledgeCatalogRelation(
        relationId = "relation:${from.knowledgeNodeId}:${to.knowledgeNodeId}",
        subject = from.subject,
        from = from,
        to = to,
        relationType = type,
        sourceId = "source-1",
        sourceLocator = "reviewed-test-fixture",
        reviewedAtEpochMillis = 1,
    )

    private companion object {
        const val PACK_VERSION = "pack-v1"
        const val TAXONOMY_VERSION = "taxonomy-v1"
    }
}

private class FakeRetrievalCatalog(
    nodes: List<KnowledgeCatalogNode>,
    directHit: KnowledgeCatalogNode? = null,
    private val directHits: List<KnowledgeCatalogNode> = listOfNotNull(directHit),
    private val relations: List<KnowledgeCatalogRelation> = emptyList(),
) : HighSchoolKnowledgeCatalog {
    private val nodesByRef = nodes.associateBy(KnowledgeCatalogNode::ref)
    private val proofIssuer = KnowledgeReferenceProofAuthority.create().issuer

    var manifestReadCount = 0
    var recallCount = 0
    var neighborhoodReadCount = 0
    var findNodeCount = 0
    var verifyReferenceCount = 0
    var batchResolveCount = 0
    var batchVerifyCount = 0
    var lastRecallSubject: SubjectKind? = null
    var lastRecallQuery: String? = null
    val expandedOrigins = mutableListOf<KnowledgeNodeRef>()

    override suspend fun readManifest(): KnowledgePackManifest {
        manifestReadCount += 1
        return KnowledgePackManifest(
            packId = "pack",
            schemaVersion = 1,
            knowledgePackVersion = "pack-v1",
            taxonomyVersion = "taxonomy-v1",
            searchIndexVersion = "search-v1",
            contentFingerprint = "a".repeat(64),
            builtAtEpochMillis = 1,
            nodeCount = nodesByRef.size,
            sourceCount = 1,
            relationCount = relations.size,
            materialCount = 0,
            searchFeatureCount = 1,
        )
    }

    override suspend fun findNode(ref: KnowledgeNodeRef): KnowledgeCatalogNode? {
        findNodeCount += 1
        return nodesByRef[ref]
    }

    override suspend fun findNodes(
        refs: List<KnowledgeNodeRef>,
    ): KnowledgeCatalogNodeDisplayBatch = error("Problem retrieval never uses display batches")

    override suspend fun readDisplayOrderPage(
        subject: SubjectKind,
        afterOrderToken: String?,
        limit: Int,
    ): KnowledgeCatalogDisplayOrderPage = error("Problem retrieval never reads display pages")

    override suspend fun verifyReference(
        ref: KnowledgeNodeRef,
    ): VerifiedKnowledgeReferenceProof? {
        verifyReferenceCount += 1
        return nodesByRef[ref]?.let {
            proofIssuer.issue(
                ref,
                "a".repeat(64),
                7,
            )
        }
    }

    override suspend fun verifyReferences(
        refs: List<KnowledgeNodeRef>,
    ): List<VerifiedKnowledgeReferenceProof> {
        batchVerifyCount += 1
        return refs.mapNotNull { ref ->
            nodesByRef[ref]?.let {
                proofIssuer.issue(
                    ref,
                    "a".repeat(64),
                    7,
                )
            }
        }
    }

    override suspend fun resolveNode(
        ref: KnowledgeNodeRef,
    ): VerifiedKnowledgeNodeHandle? = error("Problem retrieval never retains catalog handles")

    override suspend fun resolveNodes(
        subject: SubjectKind,
        knowledgeNodeIds: List<String>,
    ): List<VerifiedKnowledgeNodeHandle> {
        batchResolveCount += 1
        return knowledgeNodeIds.mapNotNull { knowledgeNodeId ->
            nodesByRef.values
                .singleOrNull { node ->
                    node.subject == subject &&
                        node.ref.knowledgeNodeId == knowledgeNodeId &&
                        node.ref.knowledgePackVersion == "pack-v1" &&
                        node.ref.taxonomyVersion == "taxonomy-v1"
                }?.let { node ->
                    KnowledgeCatalogDebugFixtures.verifiedNodeHandle(
                        node = node,
                        manifestFingerprint = "a".repeat(64),
                        activationGeneration = 7,
                    )
                }
        }
    }

    fun authorityAccessCounts(): List<Int> =
        listOf(
            manifestReadCount,
            recallCount,
            neighborhoodReadCount,
            findNodeCount,
            verifyReferenceCount,
            batchResolveCount,
            batchVerifyCount,
        )

    override suspend fun recall(
        subject: SubjectKind,
        query: String,
        limit: Int,
    ): List<KnowledgeCatalogSearchHit> {
        recallCount += 1
        lastRecallSubject = subject
        lastRecallQuery = query
        return directHits
            .filter { node ->
                node.aliases.any { alias -> alias in query || query in alias } ||
                    query in node.canonicalName ||
                    node.canonicalName in query
            }
            .take(limit)
            .map { node ->
                KnowledgeCatalogSearchHit(
                    node = node,
                    matchedFeatureCount = 1,
                    bestRankWeight = 75,
                )
            }
    }

    override suspend fun readNeighborhood(
        subject: SubjectKind,
        query: String,
        directLimit: Int,
        relatedLimit: Int,
        relationLimit: Int,
    ): KnowledgeCatalogNeighborhood {
        neighborhoodReadCount += 1
        val manifest = readManifest()
        val hits = recall(subject, query, directLimit)
        val directRefs = hits.mapTo(linkedSetOf()) { hit -> hit.node.ref }
        expandedOrigins += directRefs
        val selectedRelations =
            relations
                .filter { relation ->
                    relation.subject == subject &&
                        (relation.from in directRefs || relation.to in directRefs)
                }
                .take(relationLimit)
        val relatedNodes =
            selectedRelations
                .asSequence()
                .flatMap { relation -> sequenceOf(relation.from, relation.to) }
                .filterNot(directRefs::contains)
                .distinct()
                .mapNotNull(nodesByRef::get)
                .take(relatedLimit)
                .toList()
        val parents =
            (hits.map { hit -> hit.node } + relatedNodes)
                .mapNotNull(KnowledgeCatalogNode::parentRef)
                .distinct()
                .mapNotNull(nodesByRef::get)
        return KnowledgeCatalogNeighborhood(
            manifest = manifest,
            directHits = hits,
            relations = selectedRelations,
            relatedNodes = relatedNodes,
            parentNodes = parents,
        )
    }

    override suspend fun expandRelations(
        origin: KnowledgeNodeRef,
        direction: KnowledgeRelationDirection,
        relationTypes: Set<String>,
        limit: Int,
    ): List<KnowledgeCatalogRelation> {
        expandedOrigins += origin
        return relations
            .filter { relation -> relation.from == origin || relation.to == origin }
            .take(limit)
    }

    override suspend fun readTeachingMaterials(
        node: VerifiedKnowledgeNodeHandle,
        limit: Int,
    ): List<KnowledgeCatalogTeachingMaterial> =
        error("Problem organization does not read teaching materials")

    override fun close() = Unit
}
