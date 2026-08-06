package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRequest
import com.tingyun.smartmistakebook.core.domain.TutorMasteryEvidenceQuality
import com.tingyun.smartmistakebook.core.domain.TutorMasteryStatus
import com.tingyun.smartmistakebook.core.domain.TutorMasteryTrend
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNode
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayBatch
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayLookup
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayMetadata
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogSnapshotMetadata
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogRelation
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogSearchHit
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogTeachingMaterial
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgePackManifest
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeRelationDirection
import com.tingyun.smartmistakebook.core.knowledge.database.VerifiedKnowledgeNodeHandle
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryHistoryAvailability
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContext
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextItem
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextReader
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextRequest
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextSelection
import com.tingyun.smartmistakebook.core.mastery.database.MasteryEvidenceQuality
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTutorMasteryContextRepositoryTest {
    @Test
    fun `reads only requested nodes and joins qualitative context in memory`() = runBlocking {
        val current = node("quadratic-discriminant")
        val fallback = node("quadratic-roots")
        var receivedRequest: LocalMasteryContextRequest? = null
        val mastery =
            object : LocalMasteryContextReader {
                override suspend fun queryContext(
                    request: LocalMasteryContextRequest,
                ): LocalMasteryContext {
                    receivedRequest = request
                    return LocalMasteryContext(
                        subject = SubjectKind.MATH,
                        items =
                            listOf(
                                item(
                                    node = current,
                                    historical = KnowledgeMasteryState.STEADY,
                                    current = KnowledgeMasteryState.FAMILIARIZING,
                                    trend = KnowledgeMasteryTrend.WAVERING,
                                    quality = MasteryEvidenceQuality.HIGH,
                                ),
                                item(
                                    node = fallback,
                                    selection = LocalMasteryContextSelection.SUBJECT_FOCUS,
                                    historical = KnowledgeMasteryState.FAMILIARIZING,
                                    current = KnowledgeMasteryState.FAMILIARIZING,
                                    trend = KnowledgeMasteryTrend.IMPROVING,
                                    quality = MasteryEvidenceQuality.MEDIUM,
                                ),
                            ),
                    )
                }
            }
        val knowledge =
            FakeKnowledgeCatalog(
                mapOf(
                    current.canonicalFingerprint to catalogNode(current, "判别式"),
                    fallback.canonicalFingerprint to catalogNode(fallback, "一元二次方程的根"),
                ),
            )

        val result =
            LocalTutorMasteryContextRepository(mastery, knowledge).read(
                TutorMasteryContextRequest(
                    subject = SubjectKind.MATH,
                    questionKnowledgeNodes = listOf(current),
                    fallbackKnowledgeNodes = listOf(fallback),
                ),
            )

        assertEquals(1, receivedRequest?.fallbackLimit)
        assertEquals(1, receivedRequest?.exactStableNodeFingerprints?.size)
        assertEquals(listOf(current, fallback), result.summaries.map { it.knowledgeNode })
        assertEquals("判别式", result.summaries.first().displayName)
        assertEquals(TutorMasteryStatus.NEEDS_REFRESH, result.summaries.first().status)
        assertEquals(TutorMasteryTrend.DECLINING, result.summaries.first().trend)
        assertEquals(
            TutorMasteryEvidenceQuality.STRONG,
            result.summaries.first().evidenceQuality,
        )
        assertTrue(result.projectionIsCurrent)
    }

    @Test
    fun `keeps mastery across a knowledge pack upgrade and displays the active ref`() =
        runBlocking {
            val stored = node(id = "quadratic-discriminant", pack = "pack-v1")
            val active = node(id = "quadratic-discriminant", pack = "pack-v2")
            val result =
                LocalTutorMasteryContextRepository(
                    mastery = reader(items = listOf(item(node = stored))),
                    knowledge =
                        FakeKnowledgeCatalog(
                            mapOf(active.canonicalFingerprint to catalogNode(active, "判别式（新版）")),
                        ),
                ).read(
                    TutorMasteryContextRequest(
                        subject = SubjectKind.MATH,
                        questionKnowledgeNodes = listOf(active),
                    ),
                )

            assertEquals(listOf(active), result.summaries.map { it.knowledgeNode })
            assertEquals("判别式（新版）", result.summaries.single().displayName)
        }

    @Test
    fun `never joins the same node id across taxonomy or subject boundaries`() =
        runBlocking {
            val requested = node(id = "vector-components")
            val wrongTaxonomy =
                node(
                    id = requested.knowledgeNodeId,
                    taxonomy = "taxonomy-v0",
                )
            val wrongSubject =
                node(
                    id = requested.knowledgeNodeId,
                    subject = SubjectKind.PHYSICS,
                )

            suspend fun readStored(
                stored: KnowledgeNodeRef,
                storedSubject: SubjectKind,
            ) = LocalTutorMasteryContextRepository(
                mastery =
                    reader(
                        subject = storedSubject,
                        items =
                            listOf(
                                item(
                                    node = stored,
                                    stableIdentityFingerprint = stableFingerprint(requested),
                                ),
                            ),
                    ),
                knowledge =
                    FakeKnowledgeCatalog(
                        mapOf(
                            requested.canonicalFingerprint to
                                catalogNode(requested, "向量分解"),
                        ),
                    ),
            ).read(
                TutorMasteryContextRequest(
                    subject = SubjectKind.MATH,
                    questionKnowledgeNodes = listOf(requested),
                ),
            )

            assertTrue(readStored(wrongTaxonomy, SubjectKind.MATH).summaries.isEmpty())
            assertTrue(readStored(wrongSubject, SubjectKind.PHYSICS).summaries.isEmpty())
        }

    @Test
    fun `deduplicates identical catalog positions and fails closed on conflicting metadata`() =
        runBlocking {
            val requested = node("quadratic-discriminant")
            val metadata = catalogMetadata(requested, "判别式")
            val duplicateLookup = KnowledgeCatalogNodeDisplayLookup(requested, metadata)
            val nodes =
                mapOf(
                    requested.canonicalFingerprint to catalogNode(requested, "判别式"),
                )
            val mastery = reader(items = listOf(item(requested)))

            val deduplicated =
                LocalTutorMasteryContextRepository(
                    mastery = mastery,
                    knowledge =
                        FakeKnowledgeCatalog(nodes) {
                            listOf(duplicateLookup, duplicateLookup)
                        },
                ).read(
                    TutorMasteryContextRequest(
                        subject = SubjectKind.MATH,
                        questionKnowledgeNodes = listOf(requested),
                    ),
                )
            val conflicting =
                LocalTutorMasteryContextRepository(
                    mastery = mastery,
                    knowledge =
                        FakeKnowledgeCatalog(nodes) {
                            listOf(
                                duplicateLookup,
                                KnowledgeCatalogNodeDisplayLookup(
                                    requested,
                                    catalogMetadata(requested, "冲突名称"),
                                ),
                            )
                        },
                ).read(
                    TutorMasteryContextRequest(
                        subject = SubjectKind.MATH,
                        questionKnowledgeNodes = listOf(requested),
                    ),
                )

            assertEquals(listOf("判别式"), deduplicated.summaries.map { it.displayName })
            assertTrue(conflicting.summaries.isEmpty())
        }

    @Test
    fun `keeps sixteen exact plus four fallback within one bounded mastery read`() =
        runBlocking {
            val exact = (1..16).map { index -> node("exact-$index") }
            val fallback = (1..4).map { index -> node("fallback-$index") }
            val all = exact + fallback
            var receivedRequest: LocalMasteryContextRequest? = null
            var masteryReadCount = 0
            val mastery =
                object : LocalMasteryContextReader {
                    override suspend fun queryContext(
                        request: LocalMasteryContextRequest,
                    ): LocalMasteryContext {
                        masteryReadCount += 1
                        receivedRequest = request
                        return LocalMasteryContext(
                            subject = request.subject,
                            items =
                                exact.map { item(it) } +
                                    fallback.map {
                                        item(
                                            node = it,
                                            selection =
                                                LocalMasteryContextSelection.SUBJECT_FOCUS,
                                        )
                                    },
                        )
                    }
                }
            val knowledge =
                FakeKnowledgeCatalog(
                    all.associate { ref ->
                        ref.canonicalFingerprint to catalogNode(ref, ref.knowledgeNodeId)
                    },
                )

            val result =
                LocalTutorMasteryContextRepository(mastery, knowledge).read(
                    TutorMasteryContextRequest(
                        subject = SubjectKind.MATH,
                        questionKnowledgeNodes = exact,
                        fallbackKnowledgeNodes = fallback,
                    ),
                )

            assertEquals(1, masteryReadCount)
            assertEquals(16, receivedRequest?.exactStableNodeFingerprints?.size)
            assertEquals(4, receivedRequest?.fallbackLimit)
            assertEquals(listOf(all), knowledge.requestedBatches)
            assertEquals(all, result.summaries.map { it.knowledgeNode })
            assertEquals(20, result.summaries.size)
        }

    @Test
    fun `expands same-subject related knowledge and keeps cross-subject out`() = runBlocking {
        val current = node("quadratic-discriminant")
        val related = node("quadratic-roots")
        val crossSubject = node("vector-components", subject = SubjectKind.PHYSICS)
        val requests = mutableListOf<LocalMasteryContextRequest>()
        val mastery =
            object : LocalMasteryContextReader {
                override suspend fun queryContext(
                    request: LocalMasteryContextRequest,
                ): LocalMasteryContext {
                    requests += request
                    val items =
                        if (stableFingerprint(current) in request.exactStableNodeFingerprints) {
                            listOf(
                                item(
                                    node = current,
                                    current = KnowledgeMasteryState.NEEDS_REINFORCEMENT,
                                ),
                            )
                        } else {
                            listOf(
                                item(
                                    node = related,
                                    current = KnowledgeMasteryState.STEADY,
                                ),
                            )
                        }
                    return LocalMasteryContext(
                        subject = SubjectKind.MATH,
                        items = items,
                    )
                }
            }
        val knowledge =
            FakeKnowledgeCatalog(
                nodesByFingerprint =
                    mapOf(
                        current.canonicalFingerprint to catalogNode(current, "判别式"),
                        related.canonicalFingerprint to catalogNode(related, "一元二次方程的根"),
                    ),
                relationsByOrigin =
                    mapOf(
                        current to
                            listOf(
                                relation(current, related),
                                relation(current, crossSubject),
                            ),
                    ),
            )

        val result =
            LocalTutorMasteryContextRepository(mastery, knowledge).read(
                TutorMasteryContextRequest(
                    subject = SubjectKind.MATH,
                    questionKnowledgeNodes = listOf(current),
                ),
            )

        assertEquals(2, requests.size)
        assertEquals(1, requests.last().exactStableNodeFingerprints.size)
        assertEquals(listOf(current), result.summaries.map { it.knowledgeNode })
        assertEquals(listOf(related), result.relatedSummaries.map { it.knowledgeNode })
        assertEquals(TutorMasteryStatus.NEEDS_PRACTICE, result.summaries.single().status)
        assertEquals(TutorMasteryStatus.SOLID, result.relatedSummaries.single().status)
    }

    @Test
    fun `does not expose an internal node id when the active knowledge catalog cannot resolve it`() =
        runBlocking {
            val requested = node("missing-node")
            val mastery =
                object : LocalMasteryContextReader {
                    override suspend fun queryContext(
                        request: LocalMasteryContextRequest,
                    ): LocalMasteryContext =
                        LocalMasteryContext(
                            subject = SubjectKind.MATH,
                            items = listOf(item(node = requested)),
                        )
                }

            val result =
                LocalTutorMasteryContextRepository(
                    mastery = mastery,
                    knowledge = FakeKnowledgeCatalog(emptyMap()),
                ).read(
                    TutorMasteryContextRequest(
                        subject = SubjectKind.MATH,
                        questionKnowledgeNodes = listOf(requested),
                    ),
                )

            assertTrue(result.summaries.isEmpty())
        }

    @Test
    fun `insufficient learner history never claims the mastery projection is current`() =
        runBlocking {
            val requested = node("newly-observed-node")
            val mastery =
                object : LocalMasteryContextReader {
                    override suspend fun queryContext(
                        request: LocalMasteryContextRequest,
                    ): LocalMasteryContext =
                        LocalMasteryContext(
                            subject = request.subject,
                            items = emptyList(),
                            historyAvailability =
                                LearnerMasteryHistoryAvailability.INSUFFICIENT_HISTORY,
                        )
                }

            val result =
                LocalTutorMasteryContextRepository(
                    mastery = mastery,
                    knowledge =
                        FakeKnowledgeCatalog(
                            mapOf(
                                requested.canonicalFingerprint to
                                    catalogNode(requested, "新知识点"),
                            ),
                        ),
                ).read(
                    TutorMasteryContextRequest(
                        subject = SubjectKind.MATH,
                        questionKnowledgeNodes = listOf(requested),
                    ),
                )

            assertTrue(result.summaries.isEmpty())
            assertFalse(result.projectionIsCurrent)
        }

    private fun node(
        id: String,
        subject: SubjectKind = SubjectKind.MATH,
        taxonomy: String = "taxonomy-v1",
        pack: String = "pack-v1",
    ): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = subject,
            knowledgeNodeId = id,
            taxonomyVersion = taxonomy,
            knowledgePackVersion = pack,
        )

    private fun item(
        node: KnowledgeNodeRef,
        selection: LocalMasteryContextSelection = LocalMasteryContextSelection.EXACT,
        stableIdentityFingerprint: String = stableFingerprint(node),
        historical: KnowledgeMasteryState = KnowledgeMasteryState.FAMILIARIZING,
        current: KnowledgeMasteryState = KnowledgeMasteryState.FAMILIARIZING,
        trend: KnowledgeMasteryTrend = KnowledgeMasteryTrend.STABLE,
        quality: MasteryEvidenceQuality = MasteryEvidenceQuality.LOW,
    ): LocalMasteryContextItem =
        LocalMasteryContextItem(
            selection = selection,
            stableNodeIdentityFingerprint = stableIdentityFingerprint,
            knowledgeNode = node,
            historicalState = historical,
            currentRecallState = current,
            trend = trend,
            evidenceQuality = quality,
        )

    private fun stableFingerprint(node: KnowledgeNodeRef): String =
        LocalMasteryContextRequest.fromKnowledgeNodes(
            subject = node.subject,
            exactKnowledgeNodes = listOf(node),
            fallbackLimit = 0,
        ).exactStableNodeFingerprints.single()

    private fun reader(
        subject: SubjectKind = SubjectKind.MATH,
        items: List<LocalMasteryContextItem>,
    ): LocalMasteryContextReader =
        object : LocalMasteryContextReader {
            override suspend fun queryContext(
                request: LocalMasteryContextRequest,
            ): LocalMasteryContext =
                LocalMasteryContext(
                    subject = subject,
                    items = items,
                )
        }

    private fun catalogNode(
        ref: KnowledgeNodeRef,
        displayName: String,
    ): KnowledgeCatalogNode =
        KnowledgeCatalogNode(
            ref = ref,
            stableCode = ref.knowledgeNodeId,
            subject = ref.subject,
            displayName = displayName,
            canonicalName = displayName,
            kind = KnowledgeNodeKind.CONCEPT,
            granularity = KnowledgeNodeGranularity.ATOMIC,
            aliases = emptyList(),
            boundaryMarkdown = null,
            verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
            parentRef = null,
        )

    private fun catalogMetadata(
        ref: KnowledgeNodeRef,
        displayName: String,
    ): KnowledgeCatalogNodeDisplayMetadata =
        KnowledgeCatalogNodeDisplayMetadata(
            ref = ref,
            displayName = displayName,
            kind = KnowledgeNodeKind.CONCEPT,
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentRef = null,
        )

    private fun relation(
        from: KnowledgeNodeRef,
        to: KnowledgeNodeRef,
    ): KnowledgeCatalogRelation =
        KnowledgeCatalogRelation(
            relationId = "relation-${from.knowledgeNodeId}-${to.knowledgeNodeId}",
            subject = from.subject,
            from = from,
            to = to,
            relationType = "PREREQUISITE",
            sourceId = "source",
            sourceLocator = "locator",
            reviewedAtEpochMillis = 1,
        )
}

private class FakeKnowledgeCatalog(
    private val nodesByFingerprint: Map<String, KnowledgeCatalogNode>,
    private val relationsByOrigin:
        Map<KnowledgeNodeRef, List<KnowledgeCatalogRelation>> = emptyMap(),
    private val lookupOverride:
        ((List<KnowledgeNodeRef>) -> List<KnowledgeCatalogNodeDisplayLookup>)? = null,
) : HighSchoolKnowledgeCatalog {
    val requestedBatches = mutableListOf<List<KnowledgeNodeRef>>()

    override suspend fun readManifest(): KnowledgePackManifest =
        error("Not used by this test")

    override suspend fun findNode(ref: KnowledgeNodeRef): KnowledgeCatalogNode? =
        error("Tutor context must use the bounded batch lookup")

    override suspend fun findNodes(refs: List<KnowledgeNodeRef>): KnowledgeCatalogNodeDisplayBatch {
        requestedBatches += refs.toList()
        val first = refs.first()
        return KnowledgeCatalogNodeDisplayBatch(
            snapshot =
                KnowledgeCatalogSnapshotMetadata(
                    knowledgePackVersion = first.knowledgePackVersion,
                    taxonomyVersion = first.taxonomyVersion,
                    manifestFingerprint = "a".repeat(64),
                    activationGeneration = 1L,
                ),
            lookups = lookupOverride?.invoke(refs) ?: refs.map(::defaultLookup),
        )
    }

    private fun defaultLookup(ref: KnowledgeNodeRef): KnowledgeCatalogNodeDisplayLookup {
        val node = nodesByFingerprint[ref.canonicalFingerprint]
        return KnowledgeCatalogNodeDisplayLookup(
            requestedRef = ref,
            metadata =
                node?.let {
                    KnowledgeCatalogNodeDisplayMetadata(
                        ref = it.ref,
                        displayName = it.displayName,
                        kind = it.kind,
                        granularity = it.granularity,
                        parentRef = it.parentRef,
                    )
                },
        )
    }

    override suspend fun resolveNode(ref: KnowledgeNodeRef): VerifiedKnowledgeNodeHandle? = null

    override suspend fun verifyReference(
        ref: KnowledgeNodeRef,
    ): VerifiedKnowledgeReferenceProof? = null

    override suspend fun recall(
        subject: SubjectKind,
        query: String,
        limit: Int,
    ): List<KnowledgeCatalogSearchHit> = emptyList()

    override suspend fun expandRelations(
        origin: KnowledgeNodeRef,
        direction: KnowledgeRelationDirection,
        relationTypes: Set<String>,
        limit: Int,
    ): List<KnowledgeCatalogRelation> =
        relationsByOrigin[origin].orEmpty().take(limit)

    override suspend fun readTeachingMaterials(
        node: VerifiedKnowledgeNodeHandle,
        limit: Int,
    ): List<KnowledgeCatalogTeachingMaterial> = emptyList()

    override fun close() = Unit
}
