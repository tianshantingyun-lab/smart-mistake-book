package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.domain.TutorMasteryContext
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRequest
import com.tingyun.smartmistakebook.core.domain.TutorMasteryEvidenceQuality
import com.tingyun.smartmistakebook.core.domain.TutorMasteryStatus
import com.tingyun.smartmistakebook.core.domain.TutorMasterySummary
import com.tingyun.smartmistakebook.core.domain.TutorMasteryTrend
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayMetadata
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogRelation
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeRelationDirection
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContext
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryHistoryAvailability
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextItem
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextReader
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextRequest
import com.tingyun.smartmistakebook.core.mastery.database.MasteryEvidenceQuality
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef

/**
 * Joins bounded results in memory without sharing a connection, DAO, or SQL statement between the
 * learner-mastery and high-school knowledge stores.
 */
internal class LocalTutorMasteryContextRepository(
    private val mastery: LocalMasteryContextReader,
    private val knowledge: HighSchoolKnowledgeCatalog,
) : TutorMasteryContextRepository {
    override suspend fun read(request: TutorMasteryContextRequest): TutorMasteryContext {
        val requestedNodes = request.allowedKnowledgeNodes
        val relatedRefs =
            (
                request.relatedKnowledgeNodes +
                    knowledge.relatedTutorContextNodes(request)
            )
                .distinctBy(KnowledgeNodeRef::canonicalFingerprint)
                .take(TutorMasteryContextRequest.MAX_RELATED_KNOWLEDGE_NODES)
        val allLookupRefs =
            (requestedNodes + relatedRefs).distinctBy(KnowledgeNodeRef::canonicalFingerprint)
        val localRequest =
            LocalMasteryContextRequest.fromKnowledgeNodes(
                subject = request.subject,
                exactKnowledgeNodes = request.questionKnowledgeNodes,
                fallbackLimit = request.fallbackKnowledgeNodes.size,
            )
        val fallbackIdentityRequest =
            LocalMasteryContextRequest.fromKnowledgeNodes(
                subject = request.subject,
                exactKnowledgeNodes = request.fallbackKnowledgeNodes,
                fallbackLimit = 0,
            )
        val requestedBindings =
            request.questionKnowledgeNodes.zip(localRequest.exactStableNodeFingerprints) +
                request.fallbackKnowledgeNodes.zip(
                    fallbackIdentityRequest.exactStableNodeFingerprints,
                )
        val relatedBindings =
            relatedRefs.map { node ->
                node to
                    LocalMasteryContextRequest.fromKnowledgeNodes(
                        subject = node.subject,
                        exactKnowledgeNodes = listOf(node),
                        fallbackLimit = 0,
                    ).exactStableNodeFingerprints.single()
            }
        val localContext =
            mastery.queryContext(localRequest)
        val relatedContext =
            if (relatedRefs.isNotEmpty()) {
                mastery.queryContext(
                    LocalMasteryContextRequest.fromKnowledgeNodes(
                        subject = request.subject,
                        exactKnowledgeNodes = relatedRefs,
                        fallbackLimit = 0,
                    ),
                )
            } else {
                LocalMasteryContext(subject = request.subject, items = emptyList())
            }
        val masteryByNode =
            (localContext.items + relatedContext.items)
                .groupBy(LocalMasteryContextItem::stableNodeIdentityFingerprint)
                .mapNotNull { (stableIdentity, items) ->
                    items.singleOrNull()?.let { stableIdentity to it }
                }.toMap()

        val displayBatch = knowledge.findNodes(allLookupRefs)
        val metadataByRequestedRef =
            displayBatch.lookups
                .groupBy { lookup -> lookup.requestedRef.canonicalFingerprint }
                .mapNotNull { (requestedFingerprint, lookups) ->
                    lookups
                        .map { lookup -> lookup.metadata }
                        .distinct()
                        .singleOrNull()
                        ?.let { metadata -> requestedFingerprint to metadata }
                }.toMap()
        val summaries =
            buildTutorSummaries(
                bindings = requestedBindings,
                masteryByNode = masteryByNode,
                metadataByRequestedRef = metadataByRequestedRef,
            )
        val relatedSummaries =
            buildTutorSummaries(
                bindings = relatedBindings,
                masteryByNode = masteryByNode,
                metadataByRequestedRef = metadataByRequestedRef,
            )
        return TutorMasteryContext(
            summaries = summaries,
            relatedSummaries = relatedSummaries,
            relatedKnowledgeNodes = relatedRefs,
            projectionIsCurrent =
                localContext.historyAvailability ==
                    LearnerMasteryHistoryAvailability.AVAILABLE &&
                    relatedContext.historyAvailability ==
                    LearnerMasteryHistoryAvailability.AVAILABLE,
        ).boundedTo(request)
    }
}

private suspend fun HighSchoolKnowledgeCatalog.relatedTutorContextNodes(
    request: TutorMasteryContextRequest,
): List<KnowledgeNodeRef> {
    if (request.questionKnowledgeNodes.isEmpty()) return emptyList()
    val allowedFingerprints =
        request.allowedKnowledgeNodes.mapTo(hashSetOf()) { it.canonicalFingerprint }
    val related = mutableListOf<KnowledgeNodeRef>()
    for (origin in request.questionKnowledgeNodes.take(RELATED_EXPANSION_ORIGIN_LIMIT)) {
        val relations =
            runCatching {
                expandRelations(
                    origin = origin,
                    direction = KnowledgeRelationDirection.BOTH,
                    limit = TutorMasteryContextRequest.MAX_RELATED_KNOWLEDGE_NODES,
                )
            }.getOrDefault(emptyList())
        for (relation in relations) {
            relation.otherEnd(origin)?.let(related::add)
        }
    }
    return related
        .asSequence()
        .filter { node -> node.subject == request.subject }
        .filterNot { node -> node.canonicalFingerprint in allowedFingerprints }
        .distinctBy(KnowledgeNodeRef::canonicalFingerprint)
        .take(TutorMasteryContextRequest.MAX_RELATED_KNOWLEDGE_NODES)
        .toList()
}

private fun buildTutorSummaries(
    bindings: List<Pair<KnowledgeNodeRef, String>>,
    masteryByNode: Map<String, LocalMasteryContextItem>,
    metadataByRequestedRef: Map<String, KnowledgeCatalogNodeDisplayMetadata>,
): List<TutorMasterySummary> {
    val bindingsByStableIdentity = bindings.groupBy { binding -> binding.second }
    val emittedStableIdentities = hashSetOf<String>()
    return bindings.mapNotNull { (requestedNode, stableIdentity) ->
        val aliases = bindingsByStableIdentity.getValue(stableIdentity)
        val currentRefs = aliases.map { binding -> binding.first.canonicalFingerprint }.distinct()
        if (currentRefs.size != 1 || !emittedStableIdentities.add(stableIdentity)) {
            return@mapNotNull null
        }
        val item = masteryByNode[stableIdentity] ?: return@mapNotNull null
        if (!item.knowledgeNode.hasSameStableIdentityAs(requestedNode)) {
            return@mapNotNull null
        }
        val metadata =
            metadataByRequestedRef[requestedNode.canonicalFingerprint]
                ?: return@mapNotNull null
        if (metadata.ref.canonicalFingerprint != requestedNode.canonicalFingerprint) {
            return@mapNotNull null
        }
        item.toTutorSummary(
            currentKnowledgeNode = requestedNode,
            displayName = metadata.displayName,
        )
    }
}

private fun KnowledgeCatalogRelation.otherEnd(origin: KnowledgeNodeRef): KnowledgeNodeRef? =
    when (origin) {
        from -> to
        to -> from
        else -> null
    }

private fun KnowledgeNodeRef.hasSameStableIdentityAs(other: KnowledgeNodeRef): Boolean =
    subject == other.subject &&
        knowledgeNodeId == other.knowledgeNodeId &&
        taxonomyVersion == other.taxonomyVersion

private fun LocalMasteryContextItem.toTutorSummary(
    currentKnowledgeNode: KnowledgeNodeRef,
    displayName: String,
): TutorMasterySummary =
    TutorMasterySummary(
        knowledgeNode = currentKnowledgeNode,
        displayName = displayName,
        status = toTutorStatus(),
        trend = trend.toTutorTrend(),
        evidenceQuality = evidenceQuality.toTutorEvidenceQuality(),
    )

private fun LocalMasteryContextItem.toTutorStatus(): TutorMasteryStatus =
    when {
        historicalState == KnowledgeMasteryState.STEADY &&
            currentRecallState != KnowledgeMasteryState.STEADY ->
            TutorMasteryStatus.NEEDS_REFRESH

        currentRecallState == KnowledgeMasteryState.NEEDS_REINFORCEMENT ->
            TutorMasteryStatus.NEEDS_PRACTICE

        currentRecallState == KnowledgeMasteryState.FAMILIARIZING ->
            TutorMasteryStatus.LEARNING

        else -> TutorMasteryStatus.SOLID
    }

private fun KnowledgeMasteryTrend.toTutorTrend(): TutorMasteryTrend =
    when (this) {
        KnowledgeMasteryTrend.IMPROVING -> TutorMasteryTrend.IMPROVING
        KnowledgeMasteryTrend.STABLE -> TutorMasteryTrend.STEADY
        KnowledgeMasteryTrend.WAVERING -> TutorMasteryTrend.DECLINING
    }

private fun MasteryEvidenceQuality.toTutorEvidenceQuality(): TutorMasteryEvidenceQuality =
    when (this) {
        MasteryEvidenceQuality.HIGH -> TutorMasteryEvidenceQuality.STRONG
        MasteryEvidenceQuality.MEDIUM -> TutorMasteryEvidenceQuality.MODERATE
        MasteryEvidenceQuality.LOW -> TutorMasteryEvidenceQuality.LIMITED
        MasteryEvidenceQuality.UNVERIFIABLE -> TutorMasteryEvidenceQuality.UNKNOWN
    }

private const val RELATED_EXPANSION_ORIGIN_LIMIT = 4
