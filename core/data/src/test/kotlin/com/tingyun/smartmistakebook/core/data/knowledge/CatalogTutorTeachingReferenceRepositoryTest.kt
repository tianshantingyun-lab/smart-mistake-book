package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.domain.ConfirmedKnowledgeNodeBinding
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogDebugFixtures
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogDisplayOrderPage
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNode
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayBatch
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogRelation
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogSearchHit
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogTeachingMaterial
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgePackManifest
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeRelationDirection
import com.tingyun.smartmistakebook.core.knowledge.database.VerifiedKnowledgeNodeHandle
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTutorTeachingReferenceRepositoryTest {
    @Test
    fun versionBoundLookupReturnsOnlyExactDirectReviewedMaterials() = runBlocking {
        val firstNode = node("knowledge:math:exact")
        val unrelatedNode = node("knowledge:math:related")
        val catalog = FakeTeachingCatalog(
            nodes = listOf(firstNode, unrelatedNode),
            materialsByNode = mapOf(
                firstNode.ref to listOf(
                    material(
                        id = "material:exact",
                        nodeRef = firstNode.ref,
                        type = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
                    ),
                    material(
                        id = "material:related",
                        nodeRef = unrelatedNode.ref,
                        type = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
                    ),
                ),
            ),
        )

        val references = CatalogTutorTeachingReferenceRepository(catalog).referencesForConfirmedNodes(
            subject = SubjectKind.MATH.name,
            directKnowledgeNodes = setOf(firstNode.binding()),
            limit = 4,
        )

        assertEquals(listOf("material:exact"), references.map { it.materialId })
        assertEquals(listOf(firstNode.ref), references.single().boundKnowledgeNodes)
        assertEquals(MANIFEST_FINGERPRINT, references.single().manifestFingerprint)
        assertEquals(7L, references.single().activationGeneration)
    }

    @Test
    fun versionBoundLookupFailsClosedForStaleManifestOrUnreviewedNode() = runBlocking {
        val reviewedNode = node("knowledge:math:reviewed")
        val candidateNode = node(
            id = "knowledge:math:candidate",
            verificationStatus = KnowledgeNodeVerificationStatus.MODEL_CANDIDATE,
        )
        val catalog = FakeTeachingCatalog(
            nodes = listOf(reviewedNode, candidateNode),
            materialsByNode = mapOf(
                reviewedNode.ref to listOf(
                    material(
                        id = "material:reviewed",
                        nodeRef = reviewedNode.ref,
                        type = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
                    ),
                ),
                candidateNode.ref to listOf(
                    material(
                        id = "material:candidate",
                        nodeRef = candidateNode.ref,
                        type = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
                    ),
                ),
            ),
        )
        val repository = CatalogTutorTeachingReferenceRepository(catalog)

        assertTrue(
            repository.referencesForConfirmedNodes(
                subject = SubjectKind.MATH.name,
                directKnowledgeNodes = setOf(
                    reviewedNode.binding(manifestFingerprint = "b".repeat(64)),
                ),
                limit = 4,
            ).isEmpty(),
        )
        assertTrue(
            repository.referencesForConfirmedNodes(
                subject = SubjectKind.MATH.name,
                directKnowledgeNodes = setOf(candidateNode.binding()),
                limit = 4,
            ).isEmpty(),
        )
        assertEquals(0, catalog.batchTeachingReadCount)
    }

    @Test
    fun readsOnlyVerifiedCatalogMaterialsAndCombinesTheirRequestedNodeBindings() = runBlocking {
        val firstNode = node("knowledge:math:first")
        val secondNode = node("knowledge:math:second")
        val sharedMethodId = "material:shared-method"
        val catalog =
            FakeTeachingCatalog(
                nodes = listOf(firstNode, secondNode),
                materialsByNode =
                    mapOf(
                        firstNode.ref to
                            listOf(
                                material(
                                    id = sharedMethodId,
                                    nodeRef = firstNode.ref,
                                    type = KnowledgeTeachingMaterialType.METHOD_MODEL,
                                ),
                                material(
                                    id = "material:first-example",
                                    nodeRef = firstNode.ref,
                                    type = KnowledgeTeachingMaterialType.WORKED_EXAMPLE,
                                ),
                            ),
                        secondNode.ref to
                            listOf(
                                material(
                                    id = sharedMethodId,
                                    nodeRef = secondNode.ref,
                                    type = KnowledgeTeachingMaterialType.METHOD_MODEL,
                                ),
                            ),
                    ),
            )
        val repository = CatalogTutorTeachingReferenceRepository(catalog)

        val references =
            repository.referencesFor(
                subject = SubjectKind.MATH.name,
                knowledgeNodeIds =
                    linkedSetOf(
                        secondNode.ref.knowledgeNodeId,
                        firstNode.ref.knowledgeNodeId,
                    ),
                limit = 4,
            )

        assertEquals(
            listOf(sharedMethodId, "material:first-example"),
            references.map { reference -> reference.materialId },
        )
        assertEquals(
            listOf(
                firstNode.ref.knowledgeNodeId,
                secondNode.ref.knowledgeNodeId,
            ),
            references.first().knowledgeNodeIds,
        )
        assertEquals(
            KnowledgeTeachingMaterialType.WORKED_EXAMPLE,
            references.last().materialType,
        )
        assertEquals(
            listOf(firstNode.ref, secondNode.ref),
            catalog.resolvedRefs,
        )
        assertEquals(1, catalog.batchResolveCount)
        assertEquals(1, catalog.batchTeachingReadCount)
    }

    @Test
    fun rejectsGeneralSubjectBeforeReadingCatalogContent() {
        val catalog = FakeTeachingCatalog()
        val repository = CatalogTutorTeachingReferenceRepository(catalog)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.referencesFor(
                    subject = SubjectKind.GENERAL.name,
                    knowledgeNodeIds = setOf("knowledge:general:invalid"),
                    limit = 1,
                )
            }
        }

        assertTrue(catalog.resolvedRefs.isEmpty())
        assertEquals(0, catalog.batchResolveCount)
        assertEquals(0, catalog.manifestReadCount)
    }

    @Test
    fun rejectsUnboundedKnowledgeIdsBeforeCallingCatalog() {
        val catalog = FakeTeachingCatalog()
        val repository = CatalogTutorTeachingReferenceRepository(catalog)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.referencesFor(
                    subject = SubjectKind.MATH.name,
                    knowledgeNodeIds =
                        (0..TutorTeachingReference.MAX_KNOWLEDGE_NODES)
                            .mapTo(linkedSetOf()) { index -> "knowledge:math:$index" },
                    limit = 1,
                )
            }
        }

        assertEquals(0, catalog.batchResolveCount)
        assertTrue(catalog.resolvedRefs.isEmpty())
    }

    private fun node(
        id: String,
        verificationStatus: KnowledgeNodeVerificationStatus =
            KnowledgeNodeVerificationStatus.SOURCE_GROUNDED,
    ): KnowledgeCatalogNode {
        val ref =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = id,
                taxonomyVersion = TAXONOMY_VERSION,
                knowledgePackVersion = PACK_VERSION,
            )
        return KnowledgeCatalogNode(
            ref = ref,
            stableCode = "stable:$id",
            subject = SubjectKind.MATH,
            displayName = id,
            canonicalName = id,
            kind = KnowledgeNodeKind.CONCEPT,
            granularity = KnowledgeNodeGranularity.ATOMIC,
            aliases = emptyList(),
            boundaryMarkdown = null,
            verificationStatus = verificationStatus,
            parentRef = null,
        )
    }

    private fun material(
        id: String,
        nodeRef: KnowledgeNodeRef,
        type: KnowledgeTeachingMaterialType,
    ): KnowledgeCatalogTeachingMaterial =
        KnowledgeCatalogTeachingMaterial(
            materialId = id,
            stableCode = "stable:$id",
            knowledgeNodeRef = nodeRef,
            subject = SubjectKind.MATH,
            materialType = type,
            nodeRole = KnowledgeMaterialNodeRole.PRIMARY,
            title = "Reviewed material $id",
            summaryMarkdown = "Reviewed summary.",
            applicabilityMarkdown = "Use for the requested knowledge point.",
            contentMarkdown = "Reviewed method explanation.",
            boundaryMarkdown = "No question-bank authority.",
            derivationKind = KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS,
            sourceId = "source:reviewed",
            sourceLocator = "reviewed fixture",
            contentFingerprint = "c".repeat(64),
            reviewedAtEpochMillis = 1,
        )

    private companion object {
        const val PACK_VERSION = "pack-v1"
        const val TAXONOMY_VERSION = "taxonomy-v1"
        val MANIFEST_FINGERPRINT = "a".repeat(64)
    }
}

private fun KnowledgeCatalogNode.binding(
    manifestFingerprint: String = "a".repeat(64),
    activationGeneration: Long = 7,
): ConfirmedKnowledgeNodeBinding = ConfirmedKnowledgeNodeBinding(
    ref = ref,
    manifestFingerprint = manifestFingerprint,
    activationGeneration = activationGeneration,
)

private class FakeTeachingCatalog(
    nodes: List<KnowledgeCatalogNode> = emptyList(),
    private val materialsByNode:
        Map<KnowledgeNodeRef, List<KnowledgeCatalogTeachingMaterial>> = emptyMap(),
) : HighSchoolKnowledgeCatalog {
    private val nodesByRef = nodes.associateBy(KnowledgeCatalogNode::ref)

    var manifestReadCount: Int = 0
        private set
    var batchResolveCount: Int = 0
        private set
    var batchTeachingReadCount: Int = 0
        private set
    val resolvedRefs = mutableListOf<KnowledgeNodeRef>()

    override suspend fun readManifest(): KnowledgePackManifest {
        manifestReadCount += 1
        return KnowledgePackManifest(
            packId = "pack",
            schemaVersion = 1,
            knowledgePackVersion = "pack-v1",
            taxonomyVersion = "taxonomy-v1",
            searchIndexVersion = "search-v1",
            contentFingerprint = MANIFEST_FINGERPRINT,
            builtAtEpochMillis = 1,
            nodeCount = maxOf(1, nodesByRef.size),
            sourceCount = 1,
            relationCount = 0,
            materialCount = materialsByNode.values.sumOf { materials -> materials.size },
            searchFeatureCount = 1,
        )
    }

    override suspend fun findNode(ref: KnowledgeNodeRef): KnowledgeCatalogNode? =
        error("Tutor teaching-reference lookup never reads an unverified node")

    override suspend fun findNodes(
        refs: List<KnowledgeNodeRef>,
    ): KnowledgeCatalogNodeDisplayBatch =
        error("Tutor teaching-reference lookup never reads display batches")

    override suspend fun readDisplayOrderPage(
        subject: SubjectKind,
        afterOrderToken: String?,
        limit: Int,
    ): KnowledgeCatalogDisplayOrderPage =
        error("Tutor teaching-reference lookup never reads display pages")

    override suspend fun verifyReference(
        ref: KnowledgeNodeRef,
    ): VerifiedKnowledgeReferenceProof? =
        error("Tutor teaching-reference lookup retains a verified node handle")

    override suspend fun resolveNode(
        ref: KnowledgeNodeRef,
    ): VerifiedKnowledgeNodeHandle? =
        error("Tutor teaching-reference lookup must use bounded batch resolution")

    override suspend fun resolveNodes(
        subject: SubjectKind,
        knowledgeNodeIds: List<String>,
    ): List<VerifiedKnowledgeNodeHandle> {
        batchResolveCount += 1
        val refs =
            knowledgeNodeIds.map { nodeId ->
                KnowledgeNodeRef(
                    subject = subject,
                    knowledgeNodeId = nodeId,
                    taxonomyVersion = TAXONOMY_VERSION,
                    knowledgePackVersion = PACK_VERSION,
                )
            }
        resolvedRefs += refs
        return refs.mapNotNull(nodesByRef::get).map { node ->
            KnowledgeCatalogDebugFixtures.verifiedNodeHandle(
                node = node,
                manifestFingerprint = MANIFEST_FINGERPRINT,
                activationGeneration = 7,
            )
        }
    }

    override suspend fun recall(
        subject: SubjectKind,
        query: String,
        limit: Int,
    ): List<KnowledgeCatalogSearchHit> =
        error("Tutor teaching-reference lookup never performs text recall")

    override suspend fun expandRelations(
        origin: KnowledgeNodeRef,
        direction: KnowledgeRelationDirection,
        relationTypes: Set<String>,
        limit: Int,
    ): List<KnowledgeCatalogRelation> =
        error("Tutor teaching-reference lookup never expands relations")

    override suspend fun readTeachingMaterials(
        node: VerifiedKnowledgeNodeHandle,
        limit: Int,
    ): List<KnowledgeCatalogTeachingMaterial> =
        error("Tutor teaching-reference lookup must use bounded batch material retrieval")

    override suspend fun readTeachingMaterials(
        nodes: List<VerifiedKnowledgeNodeHandle>,
        limit: Int,
    ): List<KnowledgeCatalogTeachingMaterial> {
        batchTeachingReadCount += 1
        return nodes.flatMap { node -> materialsByNode[node.ref].orEmpty() }.take(limit)
    }

    override fun close() = Unit

    private companion object {
        val MANIFEST_FINGERPRINT = "a".repeat(64)
        const val PACK_VERSION = "pack-v1"
        const val TAXONOMY_VERSION = "taxonomy-v1"
    }
}
