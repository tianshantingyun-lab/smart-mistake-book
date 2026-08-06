package com.tingyun.smartmistakebook.core.data.mastery

import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayError
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgePage
import com.tingyun.smartmistakebook.core.domain.LearningMasteryLoadState
import com.tingyun.smartmistakebook.core.domain.LearningMasteryOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageCursor
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryStatus
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimeline
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineActivity
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineRange
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineSignal
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTrend
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogDisplayOrderEntry
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogDisplayOrderPage
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNode
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayBatch
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayLookup
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayMetadata
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogRelation
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogSearchHit
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogSnapshotMetadata
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogTeachingMaterial
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgePackManifest
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeRelationDirection
import com.tingyun.smartmistakebook.core.knowledge.database.VerifiedKnowledgeNodeHandle
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayKnowledgeItem
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayOverviewResult
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayOverviewSnapshot
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayPageRequest
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayPageResult
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayReader
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayRevision
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplaySubjectOverview
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayTimelineRequest
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayTimelineResult
import com.tingyun.smartmistakebook.core.mastery.database.SubjectMasteryTimelineActivity
import com.tingyun.smartmistakebook.core.mastery.database.SubjectMasteryTimelineEntry
import com.tingyun.smartmistakebook.core.mastery.database.SubjectMasteryTimelineSignal
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLearningMasteryDisplayRepositoryTest {
    @Test
    fun overviewAlwaysReturnsNineSubjectsAndUsesOrdinaryNoDataState() = runBlocking {
        val mastery =
            FakeDisplayReader(
                overview =
                    overview(
                        revision = 7L,
                        mathematicsState = KnowledgeMasteryState.STEADY,
                        mathematicsTrend = KnowledgeMasteryTrend.IMPROVING,
                    ),
            )
        val repository = repository(mastery)

        val result = repository.observeSubjectOverview().take(2).toList().last()
        val content = result as LearningMasteryLoadState.Content<LearningMasteryOverview>

        assertEquals(LearningMasterySubject.entries, content.value.subjects.map { it.subject })
        assertEquals(
            LearningMasteryStatus.FAIRLY_STEADY,
            content.value.subjects.single {
                it.subject == LearningMasterySubject.MATHEMATICS
            }.status,
        )
        assertEquals(
            LearningMasteryTrend.IMPROVING,
            content.value.subjects.single {
                it.subject == LearningMasterySubject.MATHEMATICS
            }.trend,
        )
        assertTrue(
            content.value.subjects
                .filterNot { it.subject == LearningMasterySubject.MATHEMATICS }
                .all {
                    it.status == LearningMasteryStatus.NOT_YET_LEARNED &&
                        it.trend == LearningMasteryTrend.NO_CLEAR_CHANGE
                },
        )
    }

    @Test
    fun publicRevisionBindsMasteryAndKnowledgeActivation() = runBlocking {
        val mastery = FakeDisplayReader(overview = overview(revision = 9L))

        val first =
            repository(mastery, activation = activation(generation = 3L))
                .observeSubjectOverview().take(2).toList().last()
                .content<LearningMasteryOverview>().revision
        val changedMastery =
            repository(
                FakeDisplayReader(overview = overview(revision = 10L)),
                activation = activation(generation = 3L),
            ).observeSubjectOverview().take(2).toList().last()
                .content<LearningMasteryOverview>().revision
        val changedKnowledge =
            repository(mastery, activation = activation(generation = 4L))
                .observeSubjectOverview().take(2).toList().last()
                .content<LearningMasteryOverview>().revision
        val changedAsOf =
            repository(
                FakeDisplayReader(overview = overview(revision = 9L, asOfEpochMillis = AS_OF + 1L)),
                activation = activation(generation = 3L),
            ).observeSubjectOverview().take(2).toList().last()
                .content<LearningMasteryOverview>().revision

        assertNotEquals(first, changedMastery)
        assertNotEquals(first, changedKnowledge)
        assertNotEquals(first, changedAsOf)
    }

    @Test
    fun pageUsesCurrentRecallStateBuildsHierarchyAndCarriesOpaqueKeysetCursor() = runBlocking {
        val root = node("math-root")
        val chapter = node("functions")
        val first = node("quadratic-discriminant", knowledgePackVersion = "evidence-pack")
        val second = node("quadratic-roots", knowledgePackVersion = "evidence-pack")
        val mastery =
            FakeDisplayReader(
                overview =
                    overview(
                        revision = 11L,
                        mathematicsState = KnowledgeMasteryState.FAMILIARIZING,
                        mathematicsTrend = KnowledgeMasteryTrend.STABLE,
                    ),
                items =
                    listOf(
                        displayItem(
                            "1".repeat(64),
                            first,
                            KnowledgeMasteryState.NEEDS_REINFORCEMENT,
                        ),
                        displayItem(
                            "2".repeat(64),
                            second,
                            KnowledgeMasteryState.STEADY,
                        ),
                    ),
            )
        val catalog =
            FakeDisplayCatalog(
                metadata =
                    listOf(
                        metadata(root, "数学"),
                        metadata(chapter, "函数", root),
                        metadata(first.copy(knowledgePackVersion = PACK), "判别式", chapter),
                        metadata(second.copy(knowledgePackVersion = PACK), "方程的根", chapter),
                    ).associateBy { it.ref },
                orderedRefs =
                    listOf(
                        root,
                        chapter,
                        first.copy(knowledgePackVersion = PACK),
                        second.copy(knowledgePackVersion = PACK),
                    ),
            )
        val repository = repository(mastery, catalog)
        val revision =
            repository.observeSubjectOverview().take(2).toList().last()
                .content<LearningMasteryOverview>().revision

        val page =
            repository.observeKnowledgePage(
                LearningMasteryPageRequest(
                    subject = LearningMasterySubject.MATHEMATICS,
                    revision = revision,
                    limit = 1,
                ),
            ).take(2).toList().last().content<LearningMasteryKnowledgePage>()

        assertEquals(listOf("判别式"), page.items.map { it.displayName })
        assertEquals(listOf("数学", "函数"), page.items.first().displayPath)
        assertEquals(LearningMasteryStatus.NEEDS_REINFORCEMENT, page.items.first().status)
        assertFalse(page.items.first().key.opaqueValue.contains("quadratic"))
        assertFalse(page.nextCursor!!.opaqueValue.contains("quadratic"))
        val secondPage =
            repository.observeKnowledgePage(
                LearningMasteryPageRequest(
                    subject = LearningMasterySubject.MATHEMATICS,
                    revision = revision,
                    cursor = page.nextCursor,
                    limit = 1,
                ),
            ).take(2).toList().last().content<LearningMasteryKnowledgePage>()
        assertEquals("方程的根", secondPage.items.single().displayName)
        assertEquals(LearningMasteryStatus.FAIRLY_STEADY, secondPage.items.single().status)
        assertTrue(mastery.pageRequests.all { it.expectedRevision.asOfEpochMillis == AS_OF })
    }

    @Test
    fun twentyThousandKnowledgePointsAreReadThroughBoundedPages() = runBlocking {
        val count = 20_000
        val refs = List(count) { index -> node("scale-node-$index") }
        val items =
            refs.mapIndexed { index, ref ->
                displayItem(
                    fingerprint = index.toString(16).padStart(64, '0'),
                    node = ref,
                    state =
                        if (index % 7 == 0) {
                            KnowledgeMasteryState.NEEDS_REINFORCEMENT
                        } else {
                            KnowledgeMasteryState.FAMILIARIZING
                        },
                )
            }
        val metadata = refs.associateWith { ref -> metadata(ref, "知识点 ${ref.knowledgeNodeId}") }
        val mastery =
            FakeDisplayReader(
                overview =
                    overview(
                        revision = 30L,
                        mathematicsState = KnowledgeMasteryState.FAMILIARIZING,
                        mathematicsTrend = KnowledgeMasteryTrend.STABLE,
                    ),
                items = items,
            )
        val catalog =
            FakeDisplayCatalog(
                metadata = metadata,
                orderedRefs = refs,
            )
        val repository = repository(mastery, catalog)
        val revision =
            repository.observeSubjectOverview().take(2).toList().last()
                .content<LearningMasteryOverview>().revision

        var cursor: LearningMasteryPageCursor? = null
        val seen = linkedSetOf<String>()
        var pages = 0
        while (true) {
            val page =
                repository.observeKnowledgePage(
                    LearningMasteryPageRequest(
                        subject = LearningMasterySubject.MATHEMATICS,
                        revision = revision,
                        cursor = cursor,
                        limit = LearningMasteryPageRequest.MAX_LIMIT,
                    ),
                ).take(2).toList().last().content<LearningMasteryKnowledgePage>()
            assertTrue(page.items.size <= LearningMasteryPageRequest.MAX_LIMIT)
            assertTrue(page.items.all { seen.add(it.key.opaqueValue) })
            pages += 1
            cursor = page.nextCursor ?: break
            assertTrue(pages <= count / LearningMasteryPageRequest.MAX_LIMIT + 2)
        }

        assertEquals(count, seen.size)
        assertTrue(mastery.pageRequests.all { it.orderedKnowledgeNodes.size <= LearningMasteryPageRequest.MAX_LIMIT })
    }

    @Test
    fun stalePublicRevisionFailsBeforeReadingAPage() = runBlocking {
        val mastery = FakeDisplayReader(overview = overview(revision = 12L))
        val repository = repository(mastery)
        val oldRevision =
            repository.observeSubjectOverview().take(2).toList().last()
                .content<LearningMasteryOverview>().revision
        mastery.overview = overview(revision = 13L)
        mastery.revision.value = LearnerMasteryDisplayRevision(13L, AS_OF)

        val result =
            repository.observeKnowledgePage(
                LearningMasteryPageRequest(
                    subject = LearningMasterySubject.MATHEMATICS,
                    revision = oldRevision,
                ),
            ).take(2).toList().last()

        assertEquals(
            LearningMasteryDisplayError.PROJECTION_CHANGED,
            (result as LearningMasteryLoadState.Error).reason,
        )
        assertEquals(0, mastery.pageReadCount)
    }

    @Test
    fun taxonomyMismatchAndMissingKnowledgeFailClosedWithoutPartialContent() = runBlocking {
        val wrongTaxonomyMastery =
            FakeDisplayReader(
                overview =
                    overview(
                        revision = 14L,
                        mathematicsState = KnowledgeMasteryState.FAMILIARIZING,
                        mathematicsTrend = KnowledgeMasteryTrend.STABLE,
                        taxonomyVersions = setOf("taxonomy-v0"),
                    ),
            )
        val overviewResult =
            repository(wrongTaxonomyMastery)
                .observeSubjectOverview().take(2).toList().last()
        assertEquals(
            LearningMasteryDisplayError.TEMPORARILY_UNAVAILABLE,
            (overviewResult as LearningMasteryLoadState.Error).reason,
        )

        val missing = node("missing", knowledgePackVersion = "evidence-pack")
        val mastery =
            FakeDisplayReader(
                overview =
                    overview(
                        revision = 15L,
                        mathematicsState = KnowledgeMasteryState.FAMILIARIZING,
                        mathematicsTrend = KnowledgeMasteryTrend.STABLE,
                    ),
                items = listOf(displayItem("3".repeat(64), missing)),
            )
        val missingRepository =
            repository(
                mastery,
                FakeDisplayCatalog(
                    metadata = emptyMap(),
                    orderedRefs = listOf(missing.copy(knowledgePackVersion = PACK)),
                ),
            )
        val revision =
            missingRepository.observeSubjectOverview().take(2).toList().last()
                .content<LearningMasteryOverview>().revision
        val pageResult =
            missingRepository.observeKnowledgePage(
                LearningMasteryPageRequest(
                    subject = LearningMasterySubject.MATHEMATICS,
                    revision = revision,
                ),
            ).take(2).toList().last()

        assertEquals(
            LearningMasteryDisplayError.TEMPORARILY_UNAVAILABLE,
            (pageResult as LearningMasteryLoadState.Error).reason,
        )
    }

    @Test
    fun changedKnowledgeSnapshotFailsClosed() = runBlocking {
        val item = node("limits", knowledgePackVersion = "evidence-pack")
        val mastery =
            FakeDisplayReader(
                overview =
                    overview(
                        revision = 16L,
                        mathematicsState = KnowledgeMasteryState.FAMILIARIZING,
                        mathematicsTrend = KnowledgeMasteryTrend.STABLE,
                    ),
                items = listOf(displayItem("4".repeat(64), item)),
            )
        val catalog =
            FakeDisplayCatalog(
                metadata =
                    mapOf(
                        item.copy(knowledgePackVersion = PACK) to
                            metadata(item.copy(knowledgePackVersion = PACK), "极限"),
                    ),
                orderedRefs = listOf(item.copy(knowledgePackVersion = PACK)),
                snapshotGeneration = 99L,
            )
        val repository = repository(mastery, catalog)
        val revision =
            repository.observeSubjectOverview().take(2).toList().last()
                .content<LearningMasteryOverview>().revision

        val result =
            repository.observeKnowledgePage(
                LearningMasteryPageRequest(
                    subject = LearningMasterySubject.MATHEMATICS,
                    revision = revision,
                ),
            ).take(2).toList().last()

        assertEquals(
            LearningMasteryDisplayError.TEMPORARILY_UNAVAILABLE,
            (result as LearningMasteryLoadState.Error).reason,
        )
    }

    @Test
    fun timelineFillsMissingDaysAndMapsSignalsToStudentFacingValues() = runBlocking {
        val asOfEpochMillis = 7 * DAY_MILLIS
        val mastery =
            FakeDisplayReader(
                overview = overview(revision = 20L, asOfEpochMillis = asOfEpochMillis),
                timelineEntries =
                    listOf(
                        SubjectMasteryTimelineEntry(
                            utcEpochDay = 7L,
                            signal = SubjectMasteryTimelineSignal.PROGRESS,
                            activity = SubjectMasteryTimelineActivity.REGULAR,
                            observationCount = 3,
                            affectedKnowledgeCount = 2,
                        ),
                    ),
            )
        val repository = repository(mastery)
        val revision =
            repository.observeSubjectOverview().take(2).toList().last()
                .content<LearningMasteryOverview>().revision

        val timeline =
            repository.observeSubjectTimeline(
                LearningMasteryTimelineRequest(
                    subject = LearningMasterySubject.MATHEMATICS,
                    revision = revision,
                    range = LearningMasteryTimelineRange.LAST_7_DAYS,
                ),
            ).take(2).toList().last().content<LearningMasteryTimeline>()

        assertEquals(7, timeline.entries.size)
        assertEquals(LearningMasteryTimelineSignal.PROGRESS, timeline.entries.last().signal)
        assertEquals(LearningMasteryTimelineActivity.REGULAR, timeline.entries.last().activity)
        assertEquals(3, timeline.entries.last().attempts)
        assertEquals(2, timeline.entries.last().knowledgePoints)
        assertTrue(
            timeline.entries.dropLast(1).all {
                it.signal == LearningMasteryTimelineSignal.NO_ACTIVITY
            },
        )
        assertEquals(7, mastery.timelineRequests.single().dayLimit)
        assertEquals(1L * DAY_MILLIS, mastery.timelineRequests.single().sinceEpochMillis)
    }

    private fun repository(
        mastery: FakeDisplayReader,
        catalog: HighSchoolKnowledgeCatalog = FakeDisplayCatalog(emptyMap()),
        activation: LearningMasteryKnowledgeActivation = activation(),
    ): LocalLearningMasteryDisplayRepository =
        LocalLearningMasteryDisplayRepository(
            mastery = mastery,
            knowledge = catalog,
            activation = activation,
        )

    private fun activation(generation: Long = 3L): LearningMasteryKnowledgeActivation =
        LearningMasteryKnowledgeActivation(
            knowledgePackVersion = PACK,
            taxonomyVersion = TAXONOMY,
            manifestFingerprint = MANIFEST,
            generation = generation,
        )

    private fun overview(
        revision: Long,
        asOfEpochMillis: Long = AS_OF,
        mathematicsState: KnowledgeMasteryState? = null,
        mathematicsTrend: KnowledgeMasteryTrend? = null,
        taxonomyVersions: Set<String>? = null,
    ): LearnerMasteryDisplayOverviewSnapshot =
        LearnerMasteryDisplayOverviewSnapshot(
            revision = LearnerMasteryDisplayRevision(revision, asOfEpochMillis),
            subjects =
                SUBJECTS.map { subject ->
                    LearnerMasteryDisplaySubjectOverview(
                        subject = subject,
                        currentState =
                            mathematicsState.takeIf { subject == SubjectKind.MATH },
                        trend =
                            mathematicsTrend.takeIf { subject == SubjectKind.MATH },
                    )
                },
            taxonomyVersions =
                taxonomyVersions
                    ?: if (mathematicsState == null) emptySet() else setOf(TAXONOMY),
        )

    private fun node(
        id: String,
        knowledgePackVersion: String = PACK,
    ): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = id,
            taxonomyVersion = TAXONOMY,
            knowledgePackVersion = knowledgePackVersion,
        )

    private fun displayItem(
        fingerprint: String,
        node: KnowledgeNodeRef,
        state: KnowledgeMasteryState = KnowledgeMasteryState.FAMILIARIZING,
    ): LearnerMasteryDisplayKnowledgeItem =
        LearnerMasteryDisplayKnowledgeItem(
            stableNodeIdentityFingerprint = fingerprint,
            knowledgeNode = node,
            currentRecallState = state,
            trend = KnowledgeMasteryTrend.STABLE,
        )

    private fun metadata(
        ref: KnowledgeNodeRef,
        name: String,
        parent: KnowledgeNodeRef? = null,
    ): KnowledgeCatalogNodeDisplayMetadata =
        KnowledgeCatalogNodeDisplayMetadata(
            ref = ref,
            displayName = name,
            kind = KnowledgeNodeKind.CONCEPT,
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentRef = parent,
        )

    private fun <T> LearningMasteryLoadState<T>.content(): T =
        (this as LearningMasteryLoadState.Content<T>).value

    private companion object {
        const val PACK = "pack-v1"
        const val TAXONOMY = "taxonomy-v1"
        const val AS_OF = 5_000L
        const val DAY_MILLIS = 86_400_000L
        val MANIFEST = "a".repeat(64)
        val SUBJECTS =
            listOf(
                SubjectKind.CHINESE,
                SubjectKind.MATH,
                SubjectKind.ENGLISH,
                SubjectKind.PHYSICS,
                SubjectKind.CHEMISTRY,
                SubjectKind.BIOLOGY,
                SubjectKind.HISTORY,
                SubjectKind.GEOGRAPHY,
                SubjectKind.POLITICS,
            )
    }
}

private class FakeDisplayReader(
    overview: LearnerMasteryDisplayOverviewSnapshot,
    items: List<LearnerMasteryDisplayKnowledgeItem> = emptyList(),
    timelineEntries: List<SubjectMasteryTimelineEntry> = emptyList(),
) : LearnerMasteryDisplayReader {
    val revision = MutableStateFlow(overview.revision)
    var overview = overview
    var items = items
    var timelineEntries = timelineEntries
    var lastPageRequest: LearnerMasteryDisplayPageRequest? = null
    val pageRequests = mutableListOf<LearnerMasteryDisplayPageRequest>()
    val timelineRequests = mutableListOf<LearnerMasteryDisplayTimelineRequest>()
    var pageReadCount: Int = 0

    override fun observeRevision(): Flow<LearnerMasteryDisplayRevision> = revision

    override suspend fun readOverview(
        expectedRevision: LearnerMasteryDisplayRevision,
    ): LearnerMasteryDisplayOverviewResult =
        if (expectedRevision == revision.value) {
            LearnerMasteryDisplayOverviewResult.Current(overview)
        } else {
            LearnerMasteryDisplayOverviewResult.RevisionChanged(revision.value)
        }

    override suspend fun readKnowledgePage(
        request: LearnerMasteryDisplayPageRequest,
    ): LearnerMasteryDisplayPageResult {
        pageReadCount += 1
        lastPageRequest = request
        pageRequests += request
        if (request.expectedRevision != revision.value) {
            return LearnerMasteryDisplayPageResult.RevisionChanged(revision.value)
        }
        val requestedIds = request.orderedKnowledgeNodes.map { it.knowledgeNodeId }.toSet()
        return LearnerMasteryDisplayPageResult.Current(
            revision = request.expectedRevision,
            items = items.filter { it.knowledgeNode.knowledgeNodeId in requestedIds },
            taxonomyVersions = overview.taxonomyVersions,
        )
    }

    override suspend fun readSubjectTimeline(
        request: LearnerMasteryDisplayTimelineRequest,
    ): LearnerMasteryDisplayTimelineResult {
        timelineRequests += request
        if (request.expectedRevision != revision.value) {
            return LearnerMasteryDisplayTimelineResult.RevisionChanged(revision.value)
        }
        return LearnerMasteryDisplayTimelineResult.Current(
            revision = request.expectedRevision,
            entries = timelineEntries,
        )
    }
}

private class FakeDisplayCatalog(
    private val metadata: Map<KnowledgeNodeRef, KnowledgeCatalogNodeDisplayMetadata>,
    orderedRefs: List<KnowledgeNodeRef> = metadata.keys.toList(),
    private val snapshotGeneration: Long = 3L,
) : HighSchoolKnowledgeCatalog {
    private val orderedEntries =
        orderedRefs.mapIndexed { index, ref ->
            KnowledgeCatalogDisplayOrderEntry(
                ref = ref,
                orderToken = (index + 1).toString(16).padStart(4, '0'),
            )
        }

    override suspend fun readManifest(): KnowledgePackManifest = error("Not used")

    override suspend fun findNode(ref: KnowledgeNodeRef): KnowledgeCatalogNode? =
        error("Mastery display must use bounded batch lookups")

    override suspend fun findNodes(
        refs: List<KnowledgeNodeRef>,
    ): KnowledgeCatalogNodeDisplayBatch =
        KnowledgeCatalogNodeDisplayBatch(
            snapshot =
                KnowledgeCatalogSnapshotMetadata(
                    knowledgePackVersion = "pack-v1",
                    taxonomyVersion = "taxonomy-v1",
                    manifestFingerprint = "a".repeat(64),
                    activationGeneration = snapshotGeneration,
                ),
            lookups =
                refs.map { ref ->
                    KnowledgeCatalogNodeDisplayLookup(
                        requestedRef = ref,
                        metadata = metadata[ref],
                    )
                },
        )

    override suspend fun readDisplayOrderPage(
        subject: SubjectKind,
        afterOrderToken: String?,
        limit: Int,
    ): KnowledgeCatalogDisplayOrderPage {
        val remaining =
            orderedEntries.filter { entry ->
                entry.ref.subject == subject &&
                    (afterOrderToken == null || entry.orderToken > afterOrderToken)
            }
        val entries = remaining.take(limit)
        return KnowledgeCatalogDisplayOrderPage(
            snapshot =
                KnowledgeCatalogSnapshotMetadata(
                    knowledgePackVersion = "pack-v1",
                    taxonomyVersion = "taxonomy-v1",
                    manifestFingerprint = "a".repeat(64),
                    activationGeneration = snapshotGeneration,
                ),
            orderingPolicyVersion =
                HighSchoolKnowledgeCatalog.DISPLAY_ORDERING_POLICY_VERSION,
            entries = entries,
            nextAfterOrderToken =
                entries.lastOrNull()?.orderToken?.takeIf { remaining.size > entries.size },
        )
    }

    override suspend fun verifyReference(
        ref: KnowledgeNodeRef,
    ): VerifiedKnowledgeReferenceProof? = null

    override suspend fun resolveNode(ref: KnowledgeNodeRef): VerifiedKnowledgeNodeHandle? = null

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
    ): List<KnowledgeCatalogRelation> = emptyList()

    override suspend fun readTeachingMaterials(
        node: VerifiedKnowledgeNodeHandle,
        limit: Int,
    ): List<KnowledgeCatalogTeachingMaterial> = emptyList()

    override fun close() = Unit
}
