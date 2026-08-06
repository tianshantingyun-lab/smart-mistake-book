package com.tingyun.smartmistakebook.core.data.mastery

import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayError
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgeItem
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgeKey
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgePage
import com.tingyun.smartmistakebook.core.domain.LearningMasteryLoadState
import com.tingyun.smartmistakebook.core.domain.LearningMasteryOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageCursor
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryProjectionRevision
import com.tingyun.smartmistakebook.core.domain.LearningMasteryStatus
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubjectOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimeline
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineActivity
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineEntry
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineSignal
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTrend
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogDisplayOrderPage
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayBatch
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayMetadata
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayKnowledgeItem
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayOverviewResult
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
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal data class LearningMasteryKnowledgeActivation(
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val manifestFingerprint: String,
    val generation: Long,
) {
    init {
        require(knowledgePackVersion.isValidVersion()) {
            "Mastery display knowledge-pack version is invalid"
        }
        require(taxonomyVersion.isValidVersion()) {
            "Mastery display taxonomy version is invalid"
        }
        require(SHA_256.matches(manifestFingerprint)) {
            "Mastery display knowledge manifest fingerprint is invalid"
        }
        require(generation > 0L) {
            "Mastery display knowledge activation generation must be positive"
        }
    }
}

/**
 * Joins two independent read authorities in memory.
 *
 * Mastery owns state, trend and revision. The knowledge catalog owns learner-facing names,
 * hierarchy and deterministic hierarchy order. Neither authority receives the other's query object
 * or persistence handle.
 */
internal class LocalLearningMasteryDisplayRepository(
    private val mastery: LearnerMasteryDisplayReader,
    private val knowledge: HighSchoolKnowledgeCatalog,
    private val activation: LearningMasteryKnowledgeActivation,
) : LearningMasteryDisplayRepository {
    override fun observeSubjectOverview():
        Flow<LearningMasteryLoadState<LearningMasteryOverview>> =
        mastery.observeRevision()
            .distinctUntilChanged()
            .map { revision ->
                uiSafeRead { readOverview(revision) }
            }
            .onStart { emit(LearningMasteryLoadState.Loading) }

    override fun observeSubjectTimeline(
        request: LearningMasteryTimelineRequest,
    ): Flow<LearningMasteryLoadState<LearningMasteryTimeline>> =
        mastery.observeRevision()
            .distinctUntilChanged()
            .map { currentMasteryRevision ->
                val currentRevision = currentMasteryRevision.toPublicRevision()
                if (request.revision != currentRevision) {
                    projectionChanged(currentRevision)
                } else {
                    uiSafeRead {
                        readTimeline(
                            request = request,
                            currentMasteryRevision = currentMasteryRevision,
                        )
                    }
                }
            }
            .onStart { emit(LearningMasteryLoadState.Loading) }

    override fun observeKnowledgePage(
        request: LearningMasteryPageRequest,
    ): Flow<LearningMasteryLoadState<LearningMasteryKnowledgePage>> =
        mastery.observeRevision()
            .distinctUntilChanged()
            .map { currentMasteryRevision ->
                val currentRevision = currentMasteryRevision.toPublicRevision()
                if (request.revision != currentRevision) {
                    projectionChanged(currentRevision)
                } else {
                    uiSafeRead {
                        readPage(
                            request = request,
                            currentMasteryRevision = currentMasteryRevision,
                            currentRevision = currentRevision,
                        )
                    }
                }
            }.onStart { emit(LearningMasteryLoadState.Loading) }

    private suspend fun readTimeline(
        request: LearningMasteryTimelineRequest,
        currentMasteryRevision: LearnerMasteryDisplayRevision,
    ): LearningMasteryLoadState<LearningMasteryTimeline> {
        val asOfDay = currentMasteryRevision.asOfEpochMillis / DAY_MILLIS
        val startDay = maxOf(0L, asOfDay - (request.range.days - 1L))
        val dayCount = (asOfDay - startDay + 1L).toInt()
        val result =
            mastery.readSubjectTimeline(
                LearnerMasteryDisplayTimelineRequest(
                    subject = request.subject.toSubjectKind(),
                    expectedRevision = currentMasteryRevision,
                    sinceEpochMillis = startDay * DAY_MILLIS,
                    dayLimit = dayCount,
                ),
            )
        return when (result) {
            is LearnerMasteryDisplayTimelineResult.RevisionChanged ->
                projectionChanged(result.currentRevision.toPublicRevision())

            is LearnerMasteryDisplayTimelineResult.Current -> {
                require(result.revision == currentMasteryRevision) {
                    "Mastery display timeline returned an unexpected revision"
                }
                val entriesByDay =
                    result.entries.associateBy(SubjectMasteryTimelineEntry::utcEpochDay)
                val entries =
                    (0 until dayCount).map { offset ->
                        val day = startDay + offset
                        entriesByDay[day]?.toPublicEntry()
                            ?: LearningMasteryTimelineEntry(
                                day = day,
                                signal = LearningMasteryTimelineSignal.NO_ACTIVITY,
                                activity = LearningMasteryTimelineActivity.NONE,
                                attempts = 0,
                                knowledgePoints = 0,
                            )
                    }
                LearningMasteryLoadState.Content(
                    LearningMasteryTimeline(
                        revision = currentMasteryRevision.toPublicRevision(),
                        subject = request.subject,
                        entries = entries,
                    ),
                )
            }
        }
    }

    private suspend fun readOverview(
        expectedRevision: LearnerMasteryDisplayRevision,
    ):
        LearningMasteryLoadState<LearningMasteryOverview> {
        val snapshot =
            when (val result = mastery.readOverview(expectedRevision)) {
                is LearnerMasteryDisplayOverviewResult.RevisionChanged ->
                    return projectionChanged(result.currentRevision.toPublicRevision())

                is LearnerMasteryDisplayOverviewResult.Current -> result.snapshot
            }
        require(snapshot.revision == expectedRevision) {
            "Mastery display overview returned an unexpected revision"
        }
        requireCurrentTaxonomy(snapshot.taxonomyVersions)
        val subjects =
            snapshot.subjects.map { subject ->
                subject.toPublicOverview()
            }
        return LearningMasteryLoadState.Content(
            LearningMasteryOverview(
                revision = snapshot.revision.toPublicRevision(),
                subjects = subjects,
            ),
        )
    }

    private suspend fun readPage(
        request: LearningMasteryPageRequest,
        currentMasteryRevision: LearnerMasteryDisplayRevision,
        currentRevision: LearningMasteryProjectionRevision,
    ): LearningMasteryLoadState<LearningMasteryKnowledgePage> {
        val requestedSubject = request.subject.toSubjectKind()
        val initialOrderToken =
            request.cursor?.let { cursor ->
                decodeCursor(
                    cursor = cursor,
                    subject = request.subject,
                    revision = request.revision,
                )
            }
        var nextCatalogCursor = initialOrderToken
        var catalogExhausted = false
        val orderedItems = mutableListOf<OrderedActiveDisplayItem>()
        while (orderedItems.size <= request.limit && !catalogExhausted) {
            val orderPage =
                knowledge.readDisplayOrderPage(
                    subject = requestedSubject,
                    afterOrderToken = nextCatalogCursor,
                    limit = HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS,
                )
            validateOrderPage(
                page = orderPage,
                subject = requestedSubject,
                afterOrderToken = nextCatalogCursor,
            )
            if (orderPage.entries.isEmpty()) {
                check(orderPage.nextAfterOrderToken == null) {
                    "An empty knowledge display-order page cannot continue"
                }
                catalogExhausted = true
                continue
            }
            when (
                val result =
                mastery.readKnowledgePage(
                    LearnerMasteryDisplayPageRequest(
                        subject = requestedSubject,
                        expectedRevision = currentMasteryRevision,
                        orderedKnowledgeNodes = orderPage.entries.map { it.ref },
                    ),
                )
            ) {
                is LearnerMasteryDisplayPageResult.RevisionChanged ->
                    return projectionChanged(result.currentRevision.toPublicRevision())

                is LearnerMasteryDisplayPageResult.Current -> {
                    require(result.revision == currentMasteryRevision) {
                        "Mastery display page returned an unexpected revision"
                    }
                    requireCurrentTaxonomy(result.taxonomyVersions)
                    val masteryByActiveRef =
                        result.items.associateBy { item ->
                            item.knowledgeNode.toActiveCatalogRef()
                        }
                    require(masteryByActiveRef.size == result.items.size) {
                        "Mastery display lookup repeated a knowledge node"
                    }
                    orderPage.entries.forEach { entry ->
                        masteryByActiveRef[entry.ref]?.let { item ->
                            orderedItems +=
                                OrderedActiveDisplayItem(
                                    orderToken = entry.orderToken,
                                    item =
                                        ActiveDisplayItem(
                                            mastery = item,
                                            activeRef = entry.ref,
                                        ),
                                )
                        }
                    }
                }
            }
            val catalogCursor = orderPage.nextAfterOrderToken
            if (catalogCursor == null) {
                catalogExhausted = true
            } else {
                check(catalogCursor > (nextCatalogCursor ?: "")) {
                    "Knowledge display-order cursor did not advance"
                }
                nextCatalogCursor = catalogCursor
            }
        }
        if (orderedItems.isEmpty()) {
            return LearningMasteryLoadState.Empty(currentRevision)
        }
        val pageItems = orderedItems.take(request.limit)
        val metadataByRef =
            resolveDisplayMetadata(pageItems.map { it.item.activeRef })
        val latestRevision = mastery.observeRevision().first()
        if (latestRevision != currentMasteryRevision) {
            return projectionChanged(latestRevision.toPublicRevision())
        }
        return LearningMasteryLoadState.Content(
            LearningMasteryKnowledgePage(
                subject = request.subject,
                revision = currentRevision,
                items =
                    pageItems.map { ordered ->
                        ordered.item.toPublicItem(metadataByRef)
                    },
                nextCursor =
                    pageItems.lastOrNull()
                        ?.orderToken
                        ?.takeIf { orderedItems.size > request.limit }
                        ?.let { orderToken ->
                            LearningMasteryPageCursor.fromOpaque(
                                opaqueValue =
                                    encodeCursor(
                                        orderToken = orderToken,
                                        subject = request.subject,
                                        revision = currentRevision,
                                    ),
                                subject = request.subject,
                                revision = currentRevision,
                            )
                        },
            ),
        )
    }

    private suspend fun resolveDisplayMetadata(
        initialRefs: List<KnowledgeNodeRef>,
    ): Map<KnowledgeNodeRef, KnowledgeCatalogNodeDisplayMetadata> {
        val metadataByRef = linkedMapOf<KnowledgeNodeRef, KnowledgeCatalogNodeDisplayMetadata>()
        var pending = initialRefs.distinct()
        repeat(MAX_DISPLAY_TREE_LEVELS + 1) { level ->
            if (pending.isEmpty()) {
                return Collections.unmodifiableMap(metadataByRef)
            }
            val batch = knowledge.findNodes(pending)
            validateBatch(batch, pending)
            val resolved =
                batch.lookups.map { lookup ->
                    requireNotNull(lookup.metadata) {
                        "The active knowledge catalog cannot label a mastery node"
                    }
                }
            resolved.forEach { metadata ->
                metadata.requireActiveRef()
                val prior = metadataByRef.put(metadata.ref, metadata)
                require(prior == null || prior == metadata) {
                    "The active knowledge catalog returned conflicting display metadata"
                }
            }
            val parents =
                resolved.mapNotNull(KnowledgeCatalogNodeDisplayMetadata::parentRef)
                    .distinct()
                    .filterNot(metadataByRef::containsKey)
            if (level == MAX_DISPLAY_TREE_LEVELS && parents.isNotEmpty()) {
                error("The knowledge display hierarchy exceeds its depth budget")
            }
            pending = parents
        }
        error("The knowledge display hierarchy did not terminate")
    }

    private fun validateBatch(
        batch: KnowledgeCatalogNodeDisplayBatch,
        requested: List<KnowledgeNodeRef>,
    ) {
        val snapshot = batch.snapshot
        require(
            snapshot.knowledgePackVersion == activation.knowledgePackVersion &&
                snapshot.taxonomyVersion == activation.taxonomyVersion &&
                snapshot.manifestFingerprint == activation.manifestFingerprint &&
                snapshot.activationGeneration == activation.generation,
        ) {
            "The knowledge catalog activation changed during mastery display"
        }
        require(batch.lookups.size == requested.size) {
            "The knowledge catalog returned an incomplete display batch"
        }
        batch.lookups.zip(requested).forEach { (lookup, expected) ->
            require(lookup.requestedRef == expected) {
                "The knowledge catalog changed display lookup order"
            }
        }
    }

    private fun validateOrderPage(
        page: KnowledgeCatalogDisplayOrderPage,
        subject: SubjectKind,
        afterOrderToken: String?,
    ) {
        val snapshot = page.snapshot
        require(
            snapshot.knowledgePackVersion == activation.knowledgePackVersion &&
                snapshot.taxonomyVersion == activation.taxonomyVersion &&
                snapshot.manifestFingerprint == activation.manifestFingerprint &&
                snapshot.activationGeneration == activation.generation,
        ) {
            "The knowledge catalog activation changed during mastery ordering"
        }
        require(
            page.orderingPolicyVersion ==
                HighSchoolKnowledgeCatalog.DISPLAY_ORDERING_POLICY_VERSION,
        ) {
            "The knowledge display ordering policy changed"
        }
        require(page.entries.all { entry -> entry.ref.subject == subject }) {
            "The knowledge display-order page crossed a subject boundary"
        }
        require(page.entries.map { entry -> entry.ref }.distinct().size == page.entries.size) {
            "The knowledge display-order page repeated a node"
        }
        afterOrderToken?.let { prior ->
            require(page.entries.firstOrNull()?.orderToken?.let { it > prior } != false) {
                "The knowledge display-order page did not advance"
            }
        }
    }

    private fun ActiveDisplayItem.toPublicItem(
        metadataByRef: Map<KnowledgeNodeRef, KnowledgeCatalogNodeDisplayMetadata>,
    ): LearningMasteryKnowledgeItem {
        val metadata =
            requireNotNull(metadataByRef[activeRef]) {
                "The active knowledge catalog omitted a mastery node"
            }
        val path = mutableListOf<String>()
        val visited = linkedSetOf(activeRef)
        var parent = metadata.parentRef
        while (parent != null) {
            require(visited.add(parent)) {
                "The knowledge display hierarchy contains a cycle"
            }
            val parentMetadata =
                requireNotNull(metadataByRef[parent]) {
                    "The knowledge display hierarchy contains a missing parent"
                }
            path += parentMetadata.displayName
            parent = parentMetadata.parentRef
        }
        path.reverse()
        return LearningMasteryKnowledgeItem(
            key =
                LearningMasteryKnowledgeKey.fromOpaque(
                    CanonicalSha256("learning-mastery-display-key-v1")
                        .field("knowledgeNode", activeRef.canonicalFingerprint)
                        .finish(),
                ),
            displayName = metadata.displayName,
            displayPath = path,
            status = mastery.currentRecallState.toPublicStatus(),
            trend = mastery.trend.toPublicTrend(),
        )
    }

    private fun KnowledgeNodeRef.toActiveCatalogRef(): KnowledgeNodeRef {
        require(taxonomyVersion == activation.taxonomyVersion) {
            "Mastery projection belongs to a different knowledge taxonomy"
        }
        return copy(knowledgePackVersion = activation.knowledgePackVersion)
    }

    private fun KnowledgeCatalogNodeDisplayMetadata.requireActiveRef() {
        require(
            ref.taxonomyVersion == activation.taxonomyVersion &&
                ref.knowledgePackVersion == activation.knowledgePackVersion,
        ) {
            "Knowledge display metadata belongs to an inactive pack"
        }
        parentRef?.let { parent ->
            require(
                parent.taxonomyVersion == activation.taxonomyVersion &&
                    parent.knowledgePackVersion == activation.knowledgePackVersion,
            ) {
                "Knowledge display parent belongs to an inactive pack"
            }
        }
    }

    private fun requireCurrentTaxonomy(taxonomyVersions: Set<String>) {
        require(taxonomyVersions.all { it == activation.taxonomyVersion }) {
            "Mastery projection taxonomy has no reviewed mapping to the active catalog"
        }
    }

    private fun LearnerMasteryDisplayRevision.toPublicRevision():
        LearningMasteryProjectionRevision =
        LearningMasteryProjectionRevision.fromOpaque(
            CanonicalSha256("learning-mastery-display-revision-v1")
                .field("masteryLedgerSequence", ledgerSequence)
                .field("asOfEpochMillis", asOfEpochMillis)
                .field("knowledgeManifestFingerprint", activation.manifestFingerprint)
                .field("knowledgeActivationGeneration", activation.generation)
                .field(
                    "knowledgeDisplayOrderingPolicy",
                    HighSchoolKnowledgeCatalog.DISPLAY_ORDERING_POLICY_VERSION,
                )
                .finish(),
        )

    private fun encodeCursor(
        orderToken: String,
        subject: LearningMasterySubject,
        revision: LearningMasteryProjectionRevision,
    ): String {
        require(orderToken.isDisplayOrderToken()) {
            "Mastery display cursor order token is invalid"
        }
        return listOf(
            CURSOR_VERSION,
            orderToken,
            cursorChecksum(orderToken, subject, revision),
        ).joinToString(CURSOR_SEPARATOR)
    }

    private fun decodeCursor(
        cursor: LearningMasteryPageCursor,
        subject: LearningMasterySubject,
        revision: LearningMasteryProjectionRevision,
    ): String {
        val parts = cursor.opaqueValue.split(CURSOR_SEPARATOR)
        require(parts.size == 3 && parts[0] == CURSOR_VERSION) {
            "Mastery display cursor version is unsupported"
        }
        val orderToken = parts[1]
        require(
            orderToken.isDisplayOrderToken() &&
                parts[2] == cursorChecksum(orderToken, subject, revision),
        ) {
            "Mastery display cursor is invalid"
        }
        return orderToken
    }

    private fun cursorChecksum(
        orderToken: String,
        subject: LearningMasterySubject,
        revision: LearningMasteryProjectionRevision,
    ): String =
        CanonicalSha256("learning-mastery-display-cursor-v2")
            .field("orderToken", orderToken)
            .field(
                "orderingPolicy",
                HighSchoolKnowledgeCatalog.DISPLAY_ORDERING_POLICY_VERSION,
            )
            .field("subject", subject.name)
            .field("revision", revision.opaqueValue)
            .finish()

    private suspend fun <T> uiSafeRead(
        block: suspend () -> LearningMasteryLoadState<T>,
    ): LearningMasteryLoadState<T> =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LearningMasteryLoadState.Error(
                LearningMasteryDisplayError.TEMPORARILY_UNAVAILABLE,
            )
        }

    private fun <T> projectionChanged(
        currentRevision: LearningMasteryProjectionRevision,
    ): LearningMasteryLoadState<T> =
        LearningMasteryLoadState.Error(
            reason = LearningMasteryDisplayError.PROJECTION_CHANGED,
            currentRevision = currentRevision,
        )
}

private data class ActiveDisplayItem(
    val mastery: LearnerMasteryDisplayKnowledgeItem,
    val activeRef: KnowledgeNodeRef,
)

private data class OrderedActiveDisplayItem(
    val orderToken: String,
    val item: ActiveDisplayItem,
)

private fun LearnerMasteryDisplaySubjectOverview.toPublicOverview():
    LearningMasterySubjectOverview =
    LearningMasterySubjectOverview(
        subject = subject.toPublicSubject(),
        status = currentState?.toPublicStatus() ?: LearningMasteryStatus.NOT_YET_LEARNED,
        trend = trend?.toPublicTrend() ?: LearningMasteryTrend.NO_CLEAR_CHANGE,
    )

private fun KnowledgeMasteryState.toPublicStatus(): LearningMasteryStatus =
    when (this) {
        KnowledgeMasteryState.NEEDS_REINFORCEMENT ->
            LearningMasteryStatus.NEEDS_REINFORCEMENT

        KnowledgeMasteryState.FAMILIARIZING ->
            LearningMasteryStatus.GETTING_FAMILIAR

        KnowledgeMasteryState.STEADY ->
            LearningMasteryStatus.FAIRLY_STEADY
    }

private fun KnowledgeMasteryTrend.toPublicTrend(): LearningMasteryTrend =
    when (this) {
        KnowledgeMasteryTrend.IMPROVING -> LearningMasteryTrend.IMPROVING
        KnowledgeMasteryTrend.STABLE -> LearningMasteryTrend.STEADY
        KnowledgeMasteryTrend.WAVERING -> LearningMasteryTrend.RECENTLY_FLUCTUATING
    }

private fun SubjectMasteryTimelineEntry.toPublicEntry(): LearningMasteryTimelineEntry =
    LearningMasteryTimelineEntry(
        day = utcEpochDay,
        signal = signal.toPublicSignal(),
        activity = activity.toPublicActivity(),
        attempts = observationCount,
        knowledgePoints = affectedKnowledgeCount,
    )

private fun SubjectMasteryTimelineSignal.toPublicSignal(): LearningMasteryTimelineSignal =
    when (this) {
        SubjectMasteryTimelineSignal.PROGRESS -> LearningMasteryTimelineSignal.PROGRESS
        SubjectMasteryTimelineSignal.MIXED -> LearningMasteryTimelineSignal.MIXED
        SubjectMasteryTimelineSignal.NEEDS_ATTENTION ->
            LearningMasteryTimelineSignal.NEEDS_ATTENTION
    }

private fun SubjectMasteryTimelineActivity.toPublicActivity(): LearningMasteryTimelineActivity =
    when (this) {
        SubjectMasteryTimelineActivity.LIGHT -> LearningMasteryTimelineActivity.LIGHT
        SubjectMasteryTimelineActivity.REGULAR -> LearningMasteryTimelineActivity.REGULAR
        SubjectMasteryTimelineActivity.INTENSIVE -> LearningMasteryTimelineActivity.INTENSIVE
    }

private fun SubjectKind.toPublicSubject(): LearningMasterySubject =
    when (this) {
        SubjectKind.CHINESE -> LearningMasterySubject.CHINESE
        SubjectKind.MATH -> LearningMasterySubject.MATHEMATICS
        SubjectKind.ENGLISH -> LearningMasterySubject.ENGLISH
        SubjectKind.PHYSICS -> LearningMasterySubject.PHYSICS
        SubjectKind.CHEMISTRY -> LearningMasterySubject.CHEMISTRY
        SubjectKind.BIOLOGY -> LearningMasterySubject.BIOLOGY
        SubjectKind.HISTORY -> LearningMasterySubject.HISTORY
        SubjectKind.GEOGRAPHY -> LearningMasterySubject.GEOGRAPHY
        SubjectKind.POLITICS -> LearningMasterySubject.IDEOLOGY_AND_POLITICS
        SubjectKind.GENERAL -> error("General subject cannot enter mastery display")
    }

private fun LearningMasterySubject.toSubjectKind(): SubjectKind =
    when (this) {
        LearningMasterySubject.CHINESE -> SubjectKind.CHINESE
        LearningMasterySubject.MATHEMATICS -> SubjectKind.MATH
        LearningMasterySubject.ENGLISH -> SubjectKind.ENGLISH
        LearningMasterySubject.PHYSICS -> SubjectKind.PHYSICS
        LearningMasterySubject.CHEMISTRY -> SubjectKind.CHEMISTRY
        LearningMasterySubject.BIOLOGY -> SubjectKind.BIOLOGY
        LearningMasterySubject.HISTORY -> SubjectKind.HISTORY
        LearningMasterySubject.GEOGRAPHY -> SubjectKind.GEOGRAPHY
        LearningMasterySubject.IDEOLOGY_AND_POLITICS -> SubjectKind.POLITICS
    }

private fun String.isValidVersion(): Boolean =
    isNotBlank() && length <= 160 && this == trim() && none(Char::isISOControl)

private fun String.isDisplayOrderToken(): Boolean =
    isNotBlank() &&
        length <= MAX_DISPLAY_ORDER_TOKEN_LENGTH &&
        length % 2 == 0 &&
        all { it in '0'..'9' || it in 'a'..'f' }

private val SHA_256 = Regex("[0-9a-f]{64}")
private const val MAX_DISPLAY_TREE_LEVELS = 6
private const val MAX_DISPLAY_ORDER_TOKEN_LENGTH = 16_384
private const val CURSOR_VERSION = "m2"
private const val CURSOR_SEPARATOR = "."
private const val DAY_MILLIS = 86_400_000L
