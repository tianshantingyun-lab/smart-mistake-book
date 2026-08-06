package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNode
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogRelation
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgePackManifest
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeRecallQuery
import com.tingyun.smartmistakebook.core.knowledge.database.ReviewedKnowledgeContextReader
import com.tingyun.smartmistakebook.core.knowledge.database.VerifiedKnowledgeNodeHandle
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseCatalogProvenance
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.util.Locale

/**
 * Exact catalog candidates that may become durable organization references.
 *
 * [preferredKnowledgeNodeIds] is non-empty when the reviewed model result selected atomic nodes
 * from the supplied context. Otherwise each requested display name must resolve unambiguously to
 * either a supplied catalog node or its reviewed parent. Nothing outside this bounded context may
 * become an accepted mistake binding.
 */
data class ReviewedProblemKnowledgeConfirmationRequest(
    val subject: SubjectKind,
    val knowledgeBaseNodes: List<KnowledgeBaseNodeContext>,
    val knowledgeDisplayNames: List<String>,
    val preferredKnowledgeNodeIds: Set<String> = emptySet(),
) {
    init {
        require(subject != SubjectKind.GENERAL) {
            "Problem knowledge confirmation requires one high-school subject"
        }
        require(
            knowledgeBaseNodes.isNotEmpty() &&
                knowledgeBaseNodes.size <= ProblemOrganizationInput.MAX_KNOWLEDGE_BASE_NODES &&
                knowledgeBaseNodes.all { node -> node.subject == subject },
        ) {
            "Problem knowledge confirmation context is invalid"
        }
        require(
            knowledgeBaseNodes
                .map { context -> context.contextIdentity() }
                .distinct()
                .size == knowledgeBaseNodes.size,
        ) {
            "Problem knowledge confirmation context contains duplicate identities"
        }
        require(
            knowledgeBaseNodes.map(KnowledgeBaseNodeContext::knowledgeNodeId).distinct().size ==
                knowledgeBaseNodes.size,
        ) {
            "Problem knowledge confirmation context contains ambiguous node ids"
        }
        require(knowledgeBaseNodes.hasOneCatalogProvenance()) {
            "Problem knowledge confirmation context crosses catalog snapshots"
        }
        require(knowledgeDisplayNames.isNotEmpty() && knowledgeDisplayNames.size <= 8) {
            "Problem knowledge confirmation needs a bounded knowledge selection"
        }
        require(
            knowledgeDisplayNames.all { name ->
                name == name.trim() && name.isNotEmpty() && name.length <= 96
            } &&
                knowledgeDisplayNames
                    .map { name -> name.normalizedKnowledgeName() }
                    .distinct()
                    .size == knowledgeDisplayNames.size,
        ) {
            "Problem knowledge confirmation names are invalid"
        }
        val contextIds = knowledgeBaseNodes.mapTo(hashSetOf()) { it.knowledgeNodeId }
        require(
            preferredKnowledgeNodeIds.size <= 8 &&
                contextIds.containsAll(preferredKnowledgeNodeIds),
        ) {
            "Preferred knowledge references must come from the supplied context"
        }
    }
}

/**
 * Exact, bounded reference set selected from one model-disclosed knowledge context.
 *
 * Unlike display-name confirmation, this request never performs an open-ended lookup. Every
 * requested id must already be present in [knowledgeBaseNodes], remain reviewed in the active
 * catalog, and resolve from one activation snapshot.
 */
data class ReviewedProblemKnowledgeReferenceBatchRequest(
    val subject: SubjectKind,
    val knowledgeBaseNodes: List<KnowledgeBaseNodeContext>,
    val exactKnowledgeNodeIds: List<String>,
) {
    init {
        require(subject != SubjectKind.GENERAL) {
            "Problem knowledge reference verification requires one high-school subject"
        }
        require(
            knowledgeBaseNodes.isNotEmpty() &&
                knowledgeBaseNodes.size <= ProblemOrganizationInput.MAX_KNOWLEDGE_BASE_NODES &&
                knowledgeBaseNodes.all { node -> node.subject == subject },
        ) {
            "Problem knowledge reference context is invalid"
        }
        require(
            knowledgeBaseNodes
                .map { context -> context.contextIdentity() }
                .distinct()
                .size == knowledgeBaseNodes.size,
        ) {
            "Problem knowledge reference context contains duplicate identities"
        }
        require(
            knowledgeBaseNodes.map(KnowledgeBaseNodeContext::knowledgeNodeId).distinct().size ==
                knowledgeBaseNodes.size,
        ) {
            "Problem knowledge reference context contains ambiguous node ids"
        }
        require(knowledgeBaseNodes.hasOneCatalogProvenance()) {
            "Problem knowledge reference context crosses catalog snapshots"
        }
        require(
            exactKnowledgeNodeIds.isNotEmpty() &&
                exactKnowledgeNodeIds.size <= ProblemOrganizationInput.MAX_KNOWLEDGE_BASE_NODES &&
                exactKnowledgeNodeIds.distinct().size == exactKnowledgeNodeIds.size &&
                knowledgeBaseNodes
                    .mapTo(hashSetOf(), KnowledgeBaseNodeContext::knowledgeNodeId)
                    .containsAll(exactKnowledgeNodeIds),
        ) {
            "Exact knowledge references must be unique members of the disclosed context"
        }
    }
}

/**
 * Reads a bounded, reviewed knowledge shortlist for one supplied problem.
 *
 * The implementation owns no learner, mistake, mastery, or review data. It composes immutable
 * catalog results in memory and exposes neither a database handle nor a write capability.
 */
fun interface ReviewedProblemKnowledgeContextRepository {
    suspend fun read(
        subject: SubjectKind,
        questionText: String,
    ): List<KnowledgeBaseNodeContext>

    /**
     * Preferred structured entry point. The focused subquestion cannot be displaced by a long
     * whole-question prefix; surrounding text only supplements recall.
     */
    suspend fun read(
        subject: SubjectKind,
        query: KnowledgeRecallQuery,
    ): List<KnowledgeBaseNodeContext> =
        read(
            subject = subject,
            questionText =
                sequenceOf(
                    query.currentSubquestion,
                    query.currentQuestion,
                    query.surroundingContext,
                ).filterNotNull()
                    .joinToString("\n")
                    .take(HighSchoolKnowledgeCatalog.MAX_QUERY_CHARS),
        )

    /**
     * Issues catalog-owned proof for the exact references accepted at confirmation time.
     *
     * The default deliberately fails closed so a read-only test double or transitional adapter can
     * never turn display text into a durable knowledge binding.
     */
    suspend fun verifyConfirmationReferences(
        request: ReviewedProblemKnowledgeConfirmationRequest,
    ): List<VerifiedKnowledgeReferenceProof> =
        throw UnsupportedOperationException(
            "Problem knowledge confirmation requires the independent catalog authority",
        )

    /**
     * Batch-verifies an exact id set without allowing the caller or model to expand catalog scope.
     */
    suspend fun verifyExactReferences(
        request: ReviewedProblemKnowledgeReferenceBatchRequest,
    ): List<VerifiedKnowledgeReferenceProof> =
        throw UnsupportedOperationException(
            "Exact problem knowledge references require the independent catalog authority",
        )
}

object ReviewedProblemKnowledgeContextRepositoryFactory {
    fun create(
        catalog: HighSchoolKnowledgeCatalog,
    ): ReviewedProblemKnowledgeContextRepository =
        CatalogBackedReviewedProblemKnowledgeContextRepository(catalog)
}

private class CatalogBackedReviewedProblemKnowledgeContextRepository(
    private val catalog: HighSchoolKnowledgeCatalog,
) : ReviewedProblemKnowledgeContextRepository {
    override suspend fun read(
        subject: SubjectKind,
        questionText: String,
    ): List<KnowledgeBaseNodeContext> {
        if (questionText.isBlank()) return emptyList()
        return read(subject, KnowledgeRecallQuery(currentQuestion = questionText))
    }

    override suspend fun read(
        subject: SubjectKind,
        query: KnowledgeRecallQuery,
    ): List<KnowledgeBaseNodeContext> {
        if (subject == SubjectKind.GENERAL) return emptyList()

        val neighborhood =
            catalog.readNeighborhood(
                subject = subject,
                query = query,
                directLimit = ReviewedKnowledgeContextReader.MAX_DIRECT_NODES,
                relatedLimit = ReviewedKnowledgeContextReader.MAX_RELATED_NODES,
                relationLimit = ReviewedKnowledgeContextReader.MAX_RELATIONS,
            )
        val manifest = neighborhood.manifest
        val directNodes = LinkedHashMap<KnowledgeNodeRef, KnowledgeCatalogNode>()
        neighborhood.directHits.forEach { hit ->
            val node = hit.node
            if (node.belongsTo(manifest, subject) && node.isReviewed()) {
                directNodes.putIfAbsent(node.ref, node)
            }
        }
        if (directNodes.isEmpty()) return emptyList()

        val selectedNodes = LinkedHashMap(directNodes)
        val relations = LinkedHashMap<String, KnowledgeCatalogRelation>()
        var relatedNodeCount = 0
        val relatedByRef =
            neighborhood.relatedNodes
                .filter { node -> node.belongsTo(manifest, subject) && node.isReviewed() }
                .associateBy(KnowledgeCatalogNode::ref)
        neighborhood.relations.forEach relationLoop@{ relation ->
            if (
                relations.size >= ReviewedKnowledgeContextReader.MAX_RELATIONS ||
                !relation.belongsTo(manifest, subject)
            ) {
                return@relationLoop
            }
            val direct =
                directNodes.values.firstOrNull { node ->
                    node.ref == relation.from || node.ref == relation.to
                } ?: return@relationLoop
            val relatedRef = relation.otherEnd(direct.ref) ?: return@relationLoop
            val related =
                selectedNodes[relatedRef]
                    ?: relatedByRef[relatedRef]
                    ?: return@relationLoop
            if (related.ref != relatedRef) return@relationLoop
            if (related.ref !in selectedNodes) {
                if (
                    relatedNodeCount >= ReviewedKnowledgeContextReader.MAX_RELATED_NODES ||
                    selectedNodes.size >= ProblemOrganizationInput.MAX_KNOWLEDGE_BASE_NODES
                ) {
                    return@relationLoop
                }
                selectedNodes[related.ref] = related
                relatedNodeCount += 1
            }
            relations.putIfAbsent(relation.relationId, relation)
        }

        val parentByRef =
            neighborhood.parentNodes
                .filter { parent -> parent.belongsTo(manifest, subject) && parent.isReviewed() }
                .associateBy(KnowledgeCatalogNode::ref)
        val parentNames = HashMap<KnowledgeNodeRef, String?>()
        selectedNodes.values.forEach { node ->
            val parentRef = node.parentRef
            parentNames[node.ref] =
                if (parentRef == null) {
                    null
                } else {
                    parentByRef[parentRef]
                        ?.takeIf { parent ->
                            parent.ref == parentRef &&
                                parent.belongsTo(manifest, subject) &&
                                parent.isReviewed()
                        }
                        ?.canonicalName
                }
        }

        val selectedRefs = selectedNodes.keys.toList()
        val contextHandles =
            catalog.resolveNodes(
                subject = subject,
                knowledgeNodeIds = selectedRefs.map(KnowledgeNodeRef::knowledgeNodeId),
            )
        checkBatchResolution(
            expectedRefs = selectedRefs,
            handles = contextHandles,
            manifest = manifest,
            requestedSubject = subject,
        )
        check(contextHandles.size == selectedRefs.size) {
            "Reviewed knowledge context changed while it was being disclosed"
        }
        check(
            contextHandles.zip(selectedNodes.values).all { (handle, disclosedNode) ->
                handle.node == disclosedNode
            },
        ) {
            "Reviewed knowledge context changed while it was being disclosed"
        }
        check(catalog.readManifest() == manifest) {
            "Knowledge catalog changed while disclosing reviewed context"
        }
        val snapshot = contextHandles.map { handle -> handle.snapshotIdentity() }.distinct().single()
        val provenance =
            KnowledgeBaseCatalogProvenance(
                packId = manifest.packId,
                knowledgePackVersion = manifest.knowledgePackVersion,
                taxonomyVersion = manifest.taxonomyVersion,
                manifestFingerprint = snapshot.manifestFingerprint,
                activationGeneration = snapshot.activationGeneration,
            )
        val contexts =
            selectedNodes.values.zip(contextHandles).mapNotNull { (node, _) ->
                node.toModelContext(
                    parentCanonicalName = parentNames[node.ref],
                    prerequisiteKnowledgeNodeIds = emptyList(),
                    catalogProvenance = provenance,
                )
            }
        val selectedContextRefs = contexts.mapTo(hashSetOf()) { context -> context.catalogRef() }
        val prerequisitesByDependent =
            relations.values
                .asSequence()
                .filter { relation ->
                    relation.relationType == PREREQUISITE_RELATION_TYPE &&
                        relation.from in selectedContextRefs &&
                        relation.to in selectedContextRefs
                }
                .groupBy(
                    keySelector = KnowledgeCatalogRelation::to,
                    valueTransform = { relation -> relation.from.knowledgeNodeId },
                )

        var remainingCharacters = MAX_MODEL_CANDIDATE_CHARACTERS
        return contexts.mapNotNull { context ->
            val candidate =
                context.copy(
                    prerequisiteKnowledgeNodeIds =
                        prerequisitesByDependent[context.catalogRef()]
                            .orEmpty()
                            .distinct()
                            .sorted()
                            .take(MAX_PREREQUISITES_PER_NODE),
                )
            val candidateCharacters = candidate.modelCandidateCharacterCount()
            if (candidateCharacters > remainingCharacters) {
                null
            } else {
                remainingCharacters -= candidateCharacters
                candidate
            }
        }
    }

    override suspend fun verifyConfirmationReferences(
        request: ReviewedProblemKnowledgeConfirmationRequest,
    ): List<VerifiedKnowledgeReferenceProof> {
        val provenance = request.knowledgeBaseNodes.singleCatalogProvenance()
        val manifest = catalog.readManifest()
        check(manifest.matches(provenance)) {
            "Reviewed knowledge context no longer belongs to the active catalog manifest"
        }
        val contexts = request.knowledgeBaseNodes
        val contextRefs = contexts.map { context -> context.catalogRef() }
        val contextHandles =
            catalog.resolveNodes(
                request.subject,
                contextRefs.map(KnowledgeNodeRef::knowledgeNodeId),
            )
        checkBatchResolution(
            expectedRefs = contextRefs,
            handles = contextHandles,
            manifest = manifest,
            requestedSubject = request.subject,
            requiredProvenance = provenance,
        )
        check(contextHandles.size == contexts.size) {
            "A disclosed knowledge context is absent from the active catalog"
        }

        val resolvedContexts =
            contextHandles.zip(contexts).onEach { (handle, context) ->
                check(
                    handle.node.isReviewed() &&
                        handle.node.matches(context) &&
                        (context.parentCanonicalName == null) == (handle.node.parentRef == null),
                ) {
                    "Disclosed knowledge context no longer matches the reviewed catalog"
                }
            }
        val parentRefs =
            contextHandles
                .mapNotNull { handle -> handle.node.parentRef }
                .distinct()
        val parentHandles =
            if (parentRefs.isEmpty()) {
                emptyList()
            } else {
                catalog.resolveNodes(
                    request.subject,
                    parentRefs.map(KnowledgeNodeRef::knowledgeNodeId),
                )
            }
        checkBatchResolution(
            expectedRefs = parentRefs,
            handles = parentHandles,
            manifest = manifest,
            requestedSubject = request.subject,
            requiredProvenance = provenance,
        )
        check(parentHandles.size == parentRefs.size) {
            "A disclosed knowledge context has an unavailable parent"
        }
        val parentHandlesByRef = parentHandles.associateBy { handle -> handle.ref }
        val candidates =
            resolvedContexts.map { (nodeHandle, context) ->
                val parent =
                    nodeHandle.node.parentRef?.let { parentRef ->
                        checkNotNull(parentHandlesByRef[parentRef]) {
                            "Disclosed knowledge context has an unavailable parent"
                        }.also { parentHandle ->
                            check(
                                parentHandle.node.isReviewed() &&
                                    context.parentCanonicalName
                                        ?.normalizedKnowledgeName()
                                        ?.let { parentName ->
                                            parentHandle.node.matchesName(parentName)
                                        } == true,
                            ) {
                                "Disclosed knowledge parent no longer matches the reviewed catalog"
                            }
                        }
                    }
                ResolvedConfirmationCandidate(
                    context = context,
                    node = nodeHandle,
                    parent = parent,
                )
            }

        val selectedHandles =
            if (request.preferredKnowledgeNodeIds.isNotEmpty()) {
                val preferred =
                    request.preferredKnowledgeNodeIds.sorted().map { nodeId ->
                        candidates.singleOrNull { candidate ->
                            candidate.context.knowledgeNodeId == nodeId
                        } ?: error(
                            "Selected knowledge reference is no longer in the active catalog",
                        )
                    }
                val allowedNameNodes =
                    preferred
                        .flatMap { candidate -> listOfNotNull(candidate.node, candidate.parent) }
                        .map { handle -> handle.node }
                        .distinctBy { node -> node.ref }
                request.knowledgeDisplayNames.forEach { name ->
                    check(allowedNameNodes.count { node -> node.matchesName(name) } == 1) {
                        "Selected knowledge hierarchy is ambiguous or no longer reviewed"
                    }
                }
                preferred.map(ResolvedConfirmationCandidate::node)
            } else {
                val eligibleHandles =
                    candidates
                        .flatMap { candidate -> listOfNotNull(candidate.node, candidate.parent) }
                        .distinctBy { handle -> handle.ref }
                request.knowledgeDisplayNames.map { name ->
                    eligibleHandles.singleOrNull { handle -> handle.node.matchesName(name) }
                        ?: error(
                            "Selected knowledge name is ambiguous or absent from the active catalog",
                        )
                }
            }.distinctBy { handle -> handle.ref }

        val selectedRefs = selectedHandles.map { handle -> handle.ref }
        val proofs = catalog.verifyReferences(selectedRefs)
        checkProofBatch(
            proofs = proofs,
            selectedHandles = selectedHandles,
            requiredProvenance = provenance,
            message =
                "Selected knowledge references are not verified by the disclosed catalog snapshot",
        )
        return proofs
    }

    override suspend fun verifyExactReferences(
        request: ReviewedProblemKnowledgeReferenceBatchRequest,
    ): List<VerifiedKnowledgeReferenceProof> {
        val provenance = request.knowledgeBaseNodes.singleCatalogProvenance()
        val manifest = catalog.readManifest()
        check(manifest.matches(provenance)) {
            "Exact knowledge context no longer belongs to the active catalog manifest"
        }
        val selectedContexts =
            request.exactKnowledgeNodeIds.map { nodeId ->
                request.knowledgeBaseNodes.single { context ->
                    context.knowledgeNodeId == nodeId
                }
            }
        val selectedContextRefs = selectedContexts.map { context -> context.catalogRef() }
        val handles = catalog.resolveNodes(request.subject, request.exactKnowledgeNodeIds)
        checkBatchResolution(
            expectedRefs = selectedContextRefs,
            handles = handles,
            manifest = manifest,
            requestedSubject = request.subject,
            requiredProvenance = provenance,
        )
        check(handles.size == request.exactKnowledgeNodeIds.size) {
            "An exact knowledge reference is absent from the active catalog"
        }
        handles.zip(selectedContexts).forEach { (handle, context) ->
            check(
                handle.node.isReviewed() &&
                    handle.node.matches(context) &&
                    (handle.node.parentRef == null) == (context.parentCanonicalName == null),
            ) {
                "Exact knowledge reference no longer matches the reviewed context"
            }
        }

        val parentRefs =
            handles
                .mapNotNull { handle -> handle.node.parentRef }
                .distinct()
        val parentHandles =
            if (parentRefs.isEmpty()) {
                emptyList()
            } else {
                catalog.resolveNodes(
                    request.subject,
                    parentRefs.map(KnowledgeNodeRef::knowledgeNodeId),
                )
            }
        checkBatchResolution(
            expectedRefs = parentRefs,
            handles = parentHandles,
            manifest = manifest,
            requestedSubject = request.subject,
            requiredProvenance = provenance,
        )
        check(parentHandles.size == parentRefs.size) {
            "An exact knowledge reference has an unavailable parent"
        }
        val parentsByRef = parentHandles.associateBy(VerifiedKnowledgeNodeHandle::ref)
        handles.zip(selectedContexts).forEach { (handle, context) ->
            handle.node.parentRef?.let { parentRef ->
                val parent = checkNotNull(parentsByRef[parentRef]) {
                    "Exact knowledge reference has an unavailable parent"
                }
                check(
                    parent.node.isReviewed() &&
                        context.parentCanonicalName
                            ?.let { parentName -> parent.node.matchesName(parentName) } == true,
                ) {
                    "Exact knowledge parent no longer matches the reviewed context"
                }
            }
        }

        val selectedRefs = handles.map(VerifiedKnowledgeNodeHandle::ref)
        val proofs = catalog.verifyReferences(selectedRefs)
        checkProofBatch(
            proofs = proofs,
            selectedHandles = handles,
            requiredProvenance = provenance,
            message = "Exact knowledge references are not verified by the disclosed catalog snapshot",
        )
        return proofs
    }
}

private data class KnowledgeBaseContextIdentity(
    val subject: SubjectKind,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
    val catalogProvenance: KnowledgeBaseCatalogProvenance,
)

private data class ResolvedConfirmationCandidate(
    val context: KnowledgeBaseNodeContext,
    val node: VerifiedKnowledgeNodeHandle,
    val parent: VerifiedKnowledgeNodeHandle?,
)

private data class KnowledgeCatalogSnapshotIdentity(
    val manifestFingerprint: String,
    val activationGeneration: Long,
)

private fun VerifiedKnowledgeNodeHandle.snapshotIdentity(): KnowledgeCatalogSnapshotIdentity =
    KnowledgeCatalogSnapshotIdentity(
        manifestFingerprint = manifestFingerprint,
        activationGeneration = activationGeneration,
    )

private fun VerifiedKnowledgeReferenceProof.snapshotIdentity(): KnowledgeCatalogSnapshotIdentity =
    KnowledgeCatalogSnapshotIdentity(
        manifestFingerprint = manifestFingerprint,
        activationGeneration = activationGeneration,
    )

private fun KnowledgeBaseCatalogProvenance.snapshotIdentity(): KnowledgeCatalogSnapshotIdentity =
    KnowledgeCatalogSnapshotIdentity(
        manifestFingerprint = manifestFingerprint,
        activationGeneration = activationGeneration,
    )

private fun KnowledgeBaseNodeContext.contextIdentity(): KnowledgeBaseContextIdentity =
    KnowledgeBaseContextIdentity(
        subject = subject,
        knowledgeNodeId = knowledgeNodeId,
        taxonomyVersion = taxonomyVersion,
        catalogProvenance = catalogProvenance,
    )

private fun KnowledgeBaseNodeContext.catalogRef(): KnowledgeNodeRef =
    KnowledgeNodeRef(
        subject = subject,
        knowledgeNodeId = knowledgeNodeId,
        taxonomyVersion = taxonomyVersion,
        knowledgePackVersion = catalogProvenance.knowledgePackVersion,
    )

private fun List<KnowledgeBaseNodeContext>.hasOneCatalogProvenance(): Boolean =
    isNotEmpty() && map(KnowledgeBaseNodeContext::catalogProvenance).distinct().size == 1

private fun List<KnowledgeBaseNodeContext>.singleCatalogProvenance(): KnowledgeBaseCatalogProvenance =
    map(KnowledgeBaseNodeContext::catalogProvenance).distinct().single()

private fun KnowledgePackManifest.matches(provenance: KnowledgeBaseCatalogProvenance): Boolean =
    packId == provenance.packId &&
        knowledgePackVersion == provenance.knowledgePackVersion &&
        taxonomyVersion == provenance.taxonomyVersion &&
        contentFingerprint == provenance.manifestFingerprint

private fun checkProofBatch(
    proofs: List<VerifiedKnowledgeReferenceProof>,
    selectedHandles: List<VerifiedKnowledgeNodeHandle>,
    requiredProvenance: KnowledgeBaseCatalogProvenance,
    message: String,
) {
    val selectedRefs = selectedHandles.map(VerifiedKnowledgeNodeHandle::ref)
    val requiredSnapshot = requiredProvenance.snapshotIdentity()
    check(
        selectedHandles.isNotEmpty() &&
            proofs.size == selectedRefs.size &&
            proofs.zip(selectedRefs).all { (proof, ref) -> proof.ref == ref } &&
            selectedHandles.all { handle -> handle.snapshotIdentity() == requiredSnapshot } &&
            proofs.all { proof -> proof.snapshotIdentity() == requiredSnapshot },
    ) {
        message
    }
}

private fun checkBatchResolution(
    expectedRefs: List<KnowledgeNodeRef>,
    handles: List<VerifiedKnowledgeNodeHandle>,
    manifest: KnowledgePackManifest,
    requestedSubject: SubjectKind,
    requiredProvenance: KnowledgeBaseCatalogProvenance? = null,
) {
    check(expectedRefs.distinct().size == expectedRefs.size)
    check(expectedRefs.all { ref -> ref.belongsTo(manifest, requestedSubject) }) {
        "Batch knowledge resolution was requested with a stale catalog identity"
    }
    val expectedRefSet = expectedRefs.toSet()
    val requestedOrdinalByRef = expectedRefs.withIndex().associate { (index, ref) -> ref to index }
    check(handles.size <= expectedRefs.size)
    check(handles.map(VerifiedKnowledgeNodeHandle::ref).distinct().size == handles.size)
    check(handles.all { handle -> handle.ref in expectedRefSet }) {
        "Batch knowledge resolution escaped its requested identities"
    }
    check(
        handles.map { handle -> requestedOrdinalByRef.getValue(handle.ref) }
            .zipWithNext()
            .all { (left, right) -> left < right },
    ) {
        "Batch knowledge resolution did not preserve request order"
    }
    check(
        handles.all { handle ->
            handle.ref == handle.node.ref &&
                handle.node.belongsTo(manifest, requestedSubject) &&
                handle.manifestFingerprint == manifest.contentFingerprint
        },
    ) {
        "Batch knowledge resolution escaped its requested catalog snapshot"
    }
    requiredProvenance?.let { provenance ->
        val requiredSnapshot = provenance.snapshotIdentity()
        check(
            manifest.matches(provenance) &&
                handles.all { handle -> handle.snapshotIdentity() == requiredSnapshot },
        ) {
            "Batch knowledge resolution no longer matches the disclosed catalog provenance"
        }
    }
}

private fun KnowledgeCatalogNode.belongsTo(
    manifest: KnowledgePackManifest,
    requestedSubject: SubjectKind,
): Boolean =
    subject == requestedSubject &&
        ref.subject == requestedSubject &&
        ref.knowledgePackVersion == manifest.knowledgePackVersion &&
        ref.taxonomyVersion == manifest.taxonomyVersion

private fun KnowledgeCatalogNode.isReviewed(): Boolean =
    verificationStatus != KnowledgeNodeVerificationStatus.MODEL_CANDIDATE

private fun KnowledgeCatalogNode.matches(context: KnowledgeBaseNodeContext): Boolean =
    subject == context.subject &&
        ref.subject == context.subject &&
        ref.knowledgeNodeId == context.knowledgeNodeId &&
        ref.taxonomyVersion == context.taxonomyVersion &&
        ref.knowledgePackVersion == context.catalogProvenance.knowledgePackVersion &&
        canonicalName.normalizedKnowledgeName() == context.canonicalName.normalizedKnowledgeName() &&
        kind == context.kind &&
        granularity == context.granularity &&
        verificationStatus == context.verificationStatus &&
        boundaryMarkdown == context.boundaryMarkdown &&
        context.aliases.all { alias -> matchesName(alias) }

private fun KnowledgeCatalogNode.matchesName(name: String): Boolean {
    val normalized = name.normalizedKnowledgeName()
    return sequenceOf(displayName, canonicalName)
        .plus(aliases.asSequence())
        .any { candidate -> candidate.normalizedKnowledgeName() == normalized }
}

private fun String.normalizedKnowledgeName(): String =
    trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun KnowledgeCatalogRelation.belongsTo(
    manifest: KnowledgePackManifest,
    requestedSubject: SubjectKind,
): Boolean =
    subject == requestedSubject &&
        from.belongsTo(manifest, requestedSubject) &&
        to.belongsTo(manifest, requestedSubject)

private fun KnowledgeNodeRef.belongsTo(
    manifest: KnowledgePackManifest,
    requestedSubject: SubjectKind,
): Boolean =
    subject == requestedSubject &&
        knowledgePackVersion == manifest.knowledgePackVersion &&
        taxonomyVersion == manifest.taxonomyVersion

private fun KnowledgeCatalogRelation.otherEnd(origin: KnowledgeNodeRef): KnowledgeNodeRef? =
    when (origin) {
        from -> to
        to -> from
        else -> null
    }

private fun KnowledgeCatalogNode.toModelContext(
    parentCanonicalName: String?,
    prerequisiteKnowledgeNodeIds: List<String>,
    catalogProvenance: KnowledgeBaseCatalogProvenance,
): KnowledgeBaseNodeContext? {
    if (granularity == KnowledgeNodeGranularity.ATOMIC && parentCanonicalName == null) return null
    return runCatching {
        KnowledgeBaseNodeContext(
            knowledgeNodeId = ref.knowledgeNodeId,
            subject = subject,
            canonicalName = canonicalName,
            aliases = aliases.distinct().sorted().take(KnowledgeBaseNodeContext.MAX_ALIASES),
            kind = kind,
            granularity = granularity,
            parentCanonicalName = parentCanonicalName,
            taxonomyVersion = ref.taxonomyVersion,
            catalogProvenance = catalogProvenance,
            verificationStatus = verificationStatus,
            boundaryMarkdown = boundaryMarkdown,
            prerequisiteKnowledgeNodeIds = prerequisiteKnowledgeNodeIds,
        )
    }.getOrNull()
}

private const val PREREQUISITE_RELATION_TYPE = "PREREQUISITE_OF"
private const val MAX_PREREQUISITES_PER_NODE = 8
private const val MAX_MODEL_CANDIDATE_CHARACTERS = 12_000

private fun KnowledgeBaseNodeContext.modelCandidateCharacterCount(): Int =
    knowledgeNodeId.length +
        canonicalName.length +
        aliases.sumOf(String::length) +
        parentCanonicalName.orEmpty().length +
        boundaryMarkdown.orEmpty().length +
        prerequisiteKnowledgeNodeIds.sumOf(String::length)
