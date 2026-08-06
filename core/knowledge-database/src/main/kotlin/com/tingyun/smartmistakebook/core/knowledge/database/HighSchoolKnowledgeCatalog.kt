package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

const val HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME = "high-school-knowledge.db"

data class KnowledgePackManifest(
    val packId: String,
    val schemaVersion: Int,
    /** Version copied into every stable [KnowledgeNodeRef]. */
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val searchIndexVersion: String,
    val contentFingerprint: String,
    val builtAtEpochMillis: Long,
    val nodeCount: Int,
    val sourceCount: Int,
    val relationCount: Int,
    val materialCount: Int,
    val searchFeatureCount: Int,
) {
    init {
        packId.requireCatalogId("Knowledge-pack id")
        require(schemaVersion > 0) { "Knowledge-pack schema version must be positive" }
        knowledgePackVersion.requireCatalogId("Knowledge-pack content version")
        taxonomyVersion.requireCatalogId("Knowledge-pack taxonomy version")
        searchIndexVersion.requireCatalogId("Knowledge-pack search-index version")
        require(SHA_256.matches(contentFingerprint)) {
            "Knowledge-pack content fingerprint must be lowercase SHA-256"
        }
        require(builtAtEpochMillis >= 0) { "Knowledge-pack build time must not be negative" }
        require(nodeCount > 0) { "Knowledge pack must contain at least one node" }
        require(sourceCount >= 0) { "Knowledge-pack source count must not be negative" }
        require(relationCount >= 0) { "Knowledge-pack relation count must not be negative" }
        require(materialCount >= 0) { "Knowledge-pack material count must not be negative" }
        require(searchFeatureCount > 0) {
            "Knowledge pack must contain searchable features"
        }
    }
}

data class KnowledgeCatalogNode(
    val ref: KnowledgeNodeRef,
    val stableCode: String,
    val subject: SubjectKind,
    val displayName: String,
    val canonicalName: String,
    val kind: KnowledgeNodeKind,
    val granularity: KnowledgeNodeGranularity,
    val aliases: List<String>,
    val boundaryMarkdown: String?,
    val verificationStatus: KnowledgeNodeVerificationStatus,
    val parentRef: KnowledgeNodeRef?,
) {
    init {
        stableCode.requireCatalogId("Knowledge-node stable code")
        require(subject != SubjectKind.GENERAL) {
            "High-school knowledge nodes require a specific subject"
        }
        displayName.requireCatalogText("Knowledge-node display name")
        canonicalName.requireCatalogText("Knowledge-node canonical name")
        require((kind == KnowledgeNodeKind.TOPIC) == (granularity == KnowledgeNodeGranularity.TOPIC)) {
            "Only topic nodes may use topic granularity"
        }
        require(aliases.size <= MAX_ALIASES && aliases.all(String::isNotBlank)) {
            "Knowledge-node aliases are invalid"
        }
        require(aliases.distinct().size == aliases.size) {
            "Knowledge-node aliases must be unique"
        }
        require(canonicalName !in aliases) {
            "Knowledge-node aliases must not repeat the canonical name"
        }
        require(boundaryMarkdown == null || boundaryMarkdown.isNotBlank()) {
            "Knowledge-node boundary must not be blank"
        }
        require(parentRef != ref) { "A knowledge node cannot be its own parent" }
    }

    companion object {
        const val MAX_ALIASES = 12
    }
}

data class KnowledgeCatalogSearchHit(
    val node: KnowledgeCatalogNode,
    val matchedFeatureCount: Int,
    val bestRankWeight: Int,
) {
    init {
        require(matchedFeatureCount > 0) {
            "A knowledge search hit must match at least one feature"
        }
        require(bestRankWeight > 0) { "Knowledge search rank weight must be positive" }
    }
}

/**
 * One bounded, immutable catalog neighborhood assembled against one manifest snapshot.
 *
 * The production implementation performs one manifest read, one recall, one batched relation
 * lookup, one neighbor lookup, and one parent lookup. Callers therefore cannot accidentally turn
 * relation expansion into a query-per-node loop.
 */
data class KnowledgeCatalogNeighborhood(
    val manifest: KnowledgePackManifest,
    val directHits: List<KnowledgeCatalogSearchHit>,
    val relations: List<KnowledgeCatalogRelation>,
    val relatedNodes: List<KnowledgeCatalogNode>,
    val parentNodes: List<KnowledgeCatalogNode>,
) {
    init {
        require(directHits.size <= HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_DIRECT_NODES)
        require(relations.size <= HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_RELATIONS)
        require(relatedNodes.size <= HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_RELATED_NODES)
        require(parentNodes.size <= HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_PARENT_NODES)
    }
}

/**
 * Immutable identity of the activated catalog snapshot used for one display lookup.
 *
 * This is provenance metadata, not an authority capability. It contains no learner, question,
 * conversation, or mastery-store identity.
 */
data class KnowledgeCatalogSnapshotMetadata(
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val manifestFingerprint: String,
    val activationGeneration: Long,
) {
    init {
        knowledgePackVersion.requireCatalogId("Knowledge-pack content version")
        taxonomyVersion.requireCatalogId("Knowledge-pack taxonomy version")
        require(SHA_256.matches(manifestFingerprint)) {
            "Knowledge-catalog snapshot fingerprint must be lowercase SHA-256"
        }
        require(activationGeneration > 0L) {
            "Knowledge-catalog activation generation must be positive"
        }
    }
}

/**
 * Minimal reviewed metadata needed to label a knowledge point in learner-facing UI.
 *
 * Internal aliases, source details, retrieval features, and review state deliberately stay out of
 * this DTO.
 */
data class KnowledgeCatalogNodeDisplayMetadata(
    val ref: KnowledgeNodeRef,
    val displayName: String,
    val kind: KnowledgeNodeKind,
    val granularity: KnowledgeNodeGranularity,
    val parentRef: KnowledgeNodeRef?,
) {
    init {
        displayName.requireCatalogText("Knowledge-node display name")
        require(parentRef != ref) { "A knowledge node cannot be its own parent" }
        require(parentRef == null || parentRef.subject == ref.subject) {
            "Knowledge-node display parent must share its subject"
        }
    }
}

/** One position in a batch lookup. A missing active node is represented by null metadata. */
data class KnowledgeCatalogNodeDisplayLookup(
    val requestedRef: KnowledgeNodeRef,
    val metadata: KnowledgeCatalogNodeDisplayMetadata?,
) {
    init {
        require(metadata == null || metadata.ref == requestedRef) {
            "Knowledge-node display metadata must match its requested reference"
        }
    }
}

/**
 * Bounded, snapshot-bound result of [HighSchoolKnowledgeCatalog.findNodes].
 *
 * [lookups] is one-to-one with the request: size, order, and duplicate positions are preserved.
 * Callers therefore never need an additional database query to recover missing or duplicate refs.
 */
data class KnowledgeCatalogNodeDisplayBatch(
    val snapshot: KnowledgeCatalogSnapshotMetadata,
    val lookups: List<KnowledgeCatalogNodeDisplayLookup>,
) {
    init {
        require(lookups.size <= HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS) {
            "Knowledge-node display batch exceeds its result budget"
        }
        require(
            lookups.all { lookup ->
                lookup.requestedRef.knowledgePackVersion == snapshot.knowledgePackVersion &&
                    lookup.requestedRef.taxonomyVersion == snapshot.taxonomyVersion
            },
        ) {
            "Knowledge-node display batch must belong to its catalog snapshot"
        }
    }
}

/**
 * One non-content position in the deterministic learner-display order.
 *
 * The token is derived from the immutable stable-code parent path. It is meaningful only inside
 * the exact catalog snapshot and ordering policy carried by its page.
 */
data class KnowledgeCatalogDisplayOrderEntry(
    val ref: KnowledgeNodeRef,
    val orderToken: String,
) {
    init {
        require(orderToken.isCatalogDisplayOrderToken()) {
            "Knowledge display-order token is invalid"
        }
    }
}

data class KnowledgeCatalogDisplayOrderPage(
    val snapshot: KnowledgeCatalogSnapshotMetadata,
    val orderingPolicyVersion: String,
    val entries: List<KnowledgeCatalogDisplayOrderEntry>,
    val nextAfterOrderToken: String?,
) {
    init {
        orderingPolicyVersion.requireCatalogId("Knowledge display ordering-policy version")
        require(entries.size <= HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS) {
            "Knowledge display-order page exceeds its result budget"
        }
        require(entries.zipWithNext().all { (left, right) -> left.orderToken < right.orderToken }) {
            "Knowledge display-order page is not strictly ordered"
        }
        require(
            entries.all { entry ->
                entry.ref.knowledgePackVersion == snapshot.knowledgePackVersion &&
                    entry.ref.taxonomyVersion == snapshot.taxonomyVersion
            },
        ) {
            "Knowledge display-order page must belong to its catalog snapshot"
        }
        nextAfterOrderToken?.let { token ->
            require(token.isCatalogDisplayOrderToken()) {
                "Knowledge display-order next token is invalid"
            }
            require(entries.lastOrNull()?.orderToken == token) {
                "Knowledge display-order next token must identify the last returned entry"
            }
        }
    }
}

/**
 * Capability proving that [ref] existed in one physically activated catalog generation.
 *
 * The constructor is private and there is deliberately no `copy` method. This is a typed
 * application capability, not a sandbox against malicious in-process reflection: only trusted
 * local adapters may receive it. A formatted [KnowledgeNodeRef] remains only a reference; normal
 * callers that need teaching content must retain the handle returned by
 * [HighSchoolKnowledgeCatalog.resolveNode]. Other authorities receive only
 * [VerifiedKnowledgeReferenceProof].
 */
class VerifiedKnowledgeNodeHandle private constructor(
    val ref: KnowledgeNodeRef,
    val node: KnowledgeCatalogNode,
    val manifestFingerprint: String,
    val activationGeneration: Long,
) {
    init {
        require(node.ref == ref) { "Verified knowledge handle must bind its resolved node" }
        require(SHA_256.matches(manifestFingerprint)) {
            "Verified knowledge handle requires a manifest fingerprint"
        }
        require(activationGeneration > 0L) {
            "Verified knowledge handle requires an activated catalog generation"
        }
    }

    internal companion object {
        fun create(
            node: KnowledgeCatalogNode,
            manifestFingerprint: String,
            activationGeneration: Long,
        ): VerifiedKnowledgeNodeHandle =
            VerifiedKnowledgeNodeHandle(
                ref = node.ref,
                node = node,
                manifestFingerprint = manifestFingerprint,
                activationGeneration = activationGeneration,
            )
    }
}

enum class KnowledgeRelationDirection {
    OUTGOING,
    INCOMING,
    BOTH,
}

data class KnowledgeCatalogRelation(
    val relationId: String,
    val subject: SubjectKind,
    val from: KnowledgeNodeRef,
    val to: KnowledgeNodeRef,
    val relationType: String,
    val sourceId: String,
    val sourceLocator: String,
    val reviewedAtEpochMillis: Long,
) {
    init {
        relationId.requireCatalogId("Knowledge relation id")
        require(subject != SubjectKind.GENERAL) {
            "Knowledge relations require a specific subject"
        }
        require(from != to) { "A knowledge relation cannot point to itself" }
        relationType.requireCatalogId("Knowledge relation type")
        sourceId.requireCatalogId("Knowledge relation source id")
        sourceLocator.requireCatalogText("Knowledge relation source locator")
        require(reviewedAtEpochMillis >= 0) {
            "Knowledge relation review time must not be negative"
        }
    }
}

/**
 * Reviewed teaching support for one verified knowledge point.
 *
 * Worked examples and complete solutions in this DTO explain methods for a question the learner
 * already supplied. They have no assessment identity, answer-key role, review scheduling fields,
 * or learning-evidence semantics, so this surface cannot become a question bank.
 */
data class KnowledgeCatalogTeachingMaterial(
    val materialId: String,
    val stableCode: String,
    val knowledgeNodeRef: KnowledgeNodeRef,
    val subject: SubjectKind,
    val materialType: KnowledgeTeachingMaterialType,
    val nodeRole: KnowledgeMaterialNodeRole,
    val title: String,
    val summaryMarkdown: String,
    val applicabilityMarkdown: String,
    val contentMarkdown: String,
    val boundaryMarkdown: String,
    val derivationKind: KnowledgeMaterialDerivationKind,
    val sourceId: String,
    val sourceLocator: String,
    val contentFingerprint: String,
    val reviewedAtEpochMillis: Long,
) {
    init {
        materialId.requireCatalogId("Teaching-material id")
        stableCode.requireCatalogId("Teaching-material stable code")
        require(subject != SubjectKind.GENERAL) {
            "Teaching materials require a specific subject"
        }
        require(knowledgeNodeRef.subject == subject) {
            "Teaching material and verified knowledge node must share one subject"
        }
        title.requireCatalogMaterialText("Teaching-material title")
        summaryMarkdown.requireCatalogMaterialText("Teaching-material summary")
        applicabilityMarkdown.requireCatalogMaterialText("Teaching-material applicability")
        contentMarkdown.requireCatalogMaterialText("Teaching-material content")
        boundaryMarkdown.requireCatalogMaterialText("Teaching-material boundary")
        sourceId.requireCatalogId("Teaching-material source id")
        sourceLocator.requireCatalogMaterialText("Teaching-material source locator")
        require(SHA_256.matches(contentFingerprint)) {
            "Teaching-material content fingerprint must be lowercase SHA-256"
        }
        require(reviewedAtEpochMillis >= 0L) {
            "Teaching-material review time must not be negative"
        }
        require(
            markdownCharacterCount <=
                HighSchoolKnowledgeCatalog.MAX_SINGLE_TEACHING_MATERIAL_MARKDOWN_CHARS,
        ) {
            "Teaching material exceeds the per-material Markdown budget"
        }
    }

    internal val markdownCharacterCount: Int
        get() =
            summaryMarkdown.length +
                applicabilityMarkdown.length +
                contentMarkdown.length +
                boundaryMarkdown.length
}

/**
 * Structured text for one knowledge lookup.
 *
 * The current subquestion is kept separate from the whole question so a long preface cannot crowd
 * the learner's actual focus out of the feature budget. Surrounding text is only supplemental.
 */
data class KnowledgeRecallQuery(
    val currentQuestion: String,
    val currentSubquestion: String? = null,
    val surroundingContext: String? = null,
) {
    init {
        currentQuestion.requireRecallText("Current question")
        currentSubquestion?.requireRecallText("Current subquestion")
        surroundingContext?.requireRecallText("Surrounding question context")
    }

    internal fun flattenedForLegacyCatalog(): String =
        sequenceOf(currentSubquestion, currentQuestion, surroundingContext)
            .filterNotNull()
            .map { text -> text.stratifiedPrefixMiddleSuffix(LEGACY_QUERY_PART_CHARS) }
            .joinToString("\n")
            .stratifiedPrefixMiddleSuffix(HighSchoolKnowledgeCatalog.MAX_QUERY_CHARS)
}

/**
 * Runtime access to the independent high-school catalog.
 *
 * The interface deliberately exposes no installation, mutation, raw SQL, Room database, or file
 * handle. Pack construction and installation remain module-internal.
 */
interface HighSchoolKnowledgeCatalog : Closeable {
    suspend fun readManifest(): KnowledgePackManifest

    /**
     * Exact activated revision owned by this runtime catalog lease.
     *
     * A production lease prevents pack activation until this catalog closes, so this StateFlow is
     * intentionally stable for the lease lifetime. Reopening after activation yields a new flow
     * carrying the new generation. Test-built, unactivated catalogs may reject this operation.
     */
    fun observeSnapshotRevision(): StateFlow<KnowledgeCatalogSnapshotMetadata> =
        throw UnsupportedOperationException("Activated knowledge revision is unavailable")

    /**
     * Content lookup for display and retrieval. Its return value alone is not proof that a learner
     * event referenced an activated catalog generation; use [verifyReference] for that boundary.
     */
    suspend fun findNode(ref: KnowledgeNodeRef): KnowledgeCatalogNode?

    /**
     * Resolves minimal display metadata for at most [MAX_BATCH_NODE_REQUESTS] refs in one bounded
     * database query.
     *
     * All refs must belong to the exact currently activated pack. Refs from multiple subjects are
     * resolved together because node ids are globally unique in the catalog. The returned positions
     * match the request positions, including duplicates and missing nodes. Unlike [findNode], this
     * verified batch surface fails closed for test-built, unactivated, or stale catalogs.
     */
    suspend fun findNodes(refs: List<KnowledgeNodeRef>): KnowledgeCatalogNodeDisplayBatch

    /**
     * Reads a stable hierarchy-grouped keyset page without teaching content or learner state.
     *
     * Implementations order by the deterministic stable-code parent path. The ordering policy is
     * versioned because a cursor must never be reused after that policy changes.
     */
    suspend fun readDisplayOrderPage(
        subject: SubjectKind,
        afterOrderToken: String? = null,
        limit: Int = MAX_BATCH_NODE_REQUESTS,
    ): KnowledgeCatalogDisplayOrderPage =
        throw UnsupportedOperationException("Knowledge display ordering is unavailable")

    /**
     * Produces the narrow proof accepted by student and mastery authorities.
     *
     * The proof contains identity and activated-snapshot provenance only. Callers that also need
     * reviewed teaching content must use [resolveNode] and keep the full handle inside that content
     * reading path.
     */
    suspend fun verifyReference(ref: KnowledgeNodeRef): VerifiedKnowledgeReferenceProof?

    /**
     * Verifies a bounded set of references against one activated catalog snapshot.
     *
     * Implementations must use a bounded batch query and preserve request order for references
     * that still exist. Missing references are omitted. The default fails closed so a new catalog
     * implementation cannot accidentally turn this operation into one query per reference.
     */
    suspend fun verifyReferences(
        refs: List<KnowledgeNodeRef>,
    ): List<VerifiedKnowledgeReferenceProof> =
        throw UnsupportedOperationException("Batch knowledge-reference verification is unavailable")

    suspend fun resolveNode(ref: KnowledgeNodeRef): VerifiedKnowledgeNodeHandle?

    /**
     * Resolves a bounded set of handles against one active manifest snapshot.
     *
     * The Room implementation performs one manifest read and one node query. Missing ids are
     * omitted while request order is preserved.
     */
    suspend fun resolveNodes(
        subject: SubjectKind,
        knowledgeNodeIds: List<String>,
    ): List<VerifiedKnowledgeNodeHandle> =
        throw UnsupportedOperationException("Batch knowledge-handle resolution is unavailable")

    suspend fun recall(
        subject: SubjectKind,
        query: String,
        limit: Int = DEFAULT_RECALL_LIMIT,
    ): List<KnowledgeCatalogSearchHit>

    suspend fun recall(
        subject: SubjectKind,
        query: KnowledgeRecallQuery,
        limit: Int = DEFAULT_RECALL_LIMIT,
    ): List<KnowledgeCatalogSearchHit> =
        recall(subject, query.flattenedForLegacyCatalog(), limit)

    /**
     * Reads a subject-isolated problem neighborhood with a constant number of database queries.
     *
     * Implementations must keep all returned nodes and relations inside the exact manifest carried
     * by the result. The default fails closed so a new implementation cannot silently expand this
     * bounded operation into per-node catalog calls. The Room runtime overrides it with batched DAO
     * operations.
     */
    suspend fun readNeighborhood(
        subject: SubjectKind,
        query: String,
        directLimit: Int = MAX_NEIGHBORHOOD_DIRECT_NODES,
        relatedLimit: Int = MAX_NEIGHBORHOOD_RELATED_NODES,
        relationLimit: Int = MAX_NEIGHBORHOOD_RELATIONS,
    ): KnowledgeCatalogNeighborhood =
        throw UnsupportedOperationException("Batched knowledge-neighborhood lookup is unavailable")

    suspend fun readNeighborhood(
        subject: SubjectKind,
        query: KnowledgeRecallQuery,
        directLimit: Int = MAX_NEIGHBORHOOD_DIRECT_NODES,
        relatedLimit: Int = MAX_NEIGHBORHOOD_RELATED_NODES,
        relationLimit: Int = MAX_NEIGHBORHOOD_RELATIONS,
    ): KnowledgeCatalogNeighborhood =
        readNeighborhood(
            subject = subject,
            query = query.flattenedForLegacyCatalog(),
            directLimit = directLimit,
            relatedLimit = relatedLimit,
            relationLimit = relationLimit,
        )

    suspend fun expandRelations(
        origin: KnowledgeNodeRef,
        direction: KnowledgeRelationDirection = KnowledgeRelationDirection.BOTH,
        relationTypes: Set<String> = emptySet(),
        limit: Int = DEFAULT_RELATION_LIMIT,
    ): List<KnowledgeCatalogRelation>

    /**
     * Reads a small, deterministic set of reviewed explanations for [node].
     *
     * The verified handle must belong to this exact activated generation. Stale, cross-pack, and
     * merely formatted node references are rejected instead of silently falling back.
     */
    suspend fun readTeachingMaterials(
        node: VerifiedKnowledgeNodeHandle,
        limit: Int = DEFAULT_TEACHING_MATERIAL_LIMIT,
    ): List<KnowledgeCatalogTeachingMaterial>

    /**
     * Batch teaching lookup for handles from one subject and one activated snapshot. The Room
     * implementation first reads only title, summary, applicability, and boundary metadata,
     * applies the result budget, and then fetches bodies for the selected material ids.
     */
    suspend fun readTeachingMaterials(
        nodes: List<VerifiedKnowledgeNodeHandle>,
        limit: Int = DEFAULT_TEACHING_MATERIAL_LIMIT,
    ): List<KnowledgeCatalogTeachingMaterial> =
        throw UnsupportedOperationException("Batch teaching-material lookup is unavailable")

    companion object {
        const val DEFAULT_RECALL_LIMIT = 20
        const val DEFAULT_RELATION_LIMIT = 40
        const val DEFAULT_TEACHING_MATERIAL_LIMIT = 4
        const val MAX_QUERY_CHARS = 16_384
        const val MAX_STRUCTURED_QUERY_FIELD_CHARS = 65_536
        const val MAX_RESULT_LIMIT = 100
        const val MAX_TEACHING_MATERIAL_LIMIT = 8
        const val MAX_SINGLE_TEACHING_MATERIAL_MARKDOWN_CHARS = 20_000
        const val MAX_RETURNED_TEACHING_MATERIAL_MARKDOWN_CHARS = 40_000
        const val MAX_BATCH_NODE_REQUESTS = 64
        const val MAX_BATCH_NODE_ROWS = 64
        const val MAX_NEIGHBORHOOD_DIRECT_NODES = 6
        const val MAX_NEIGHBORHOOD_RELATED_NODES = 12
        const val MAX_NEIGHBORHOOD_RELATIONS = 24
        const val MAX_NEIGHBORHOOD_RELATIONS_PER_DIRECT_NODE = 4
        const val MAX_NEIGHBORHOOD_PARENT_NODES = 18
        const val DISPLAY_ORDERING_POLICY_VERSION = "hierarchy-stable-code-v1"
    }
}

private val SHA_256 = Regex("[0-9a-f]{64}")

private fun String.requireCatalogId(label: String) {
    require(isNotBlank() && length <= 160 && none { it.isISOControl() }) {
        "$label is invalid"
    }
}

internal fun String.isCatalogDisplayOrderToken(): Boolean =
    isNotBlank() &&
        length <= 16_384 &&
        length % 2 == 0 &&
        all { it in '0'..'9' || it in 'a'..'f' }

private fun String.requireCatalogText(label: String) {
    require(isNotBlank() && length <= 4_096 && none { it.isISOControl() && it != '\n' && it != '\t' }) {
        "$label is invalid"
    }
}

private fun String.requireCatalogMaterialText(label: String) {
    require(
        isNotBlank() &&
            length <= 16_384 &&
            none { it.isISOControl() && it != '\n' && it != '\t' },
    ) {
        "$label is invalid"
    }
}

private fun String.requireRecallText(label: String) {
    require(
        isNotBlank() &&
            length <= HighSchoolKnowledgeCatalog.MAX_STRUCTURED_QUERY_FIELD_CHARS &&
            none { it.isISOControl() && it != '\n' && it != '\t' },
    ) {
        "$label is invalid"
    }
}

private fun String.stratifiedPrefixMiddleSuffix(limit: Int): String {
    if (length <= limit) return this
    val headChars = limit / 3
    val tailChars = limit / 3
    val middleChars = limit - headChars - tailChars - 2
    val middleStart = (length - middleChars) / 2
    return buildString(limit) {
        append(this@stratifiedPrefixMiddleSuffix, 0, headChars)
        append('\n')
        append(
            this@stratifiedPrefixMiddleSuffix,
            middleStart,
            middleStart + middleChars,
        )
        append('\n')
        append(
            this@stratifiedPrefixMiddleSuffix,
            this@stratifiedPrefixMiddleSuffix.length - tailChars,
            this@stratifiedPrefixMiddleSuffix.length,
        )
    }
}

private const val LEGACY_QUERY_PART_CHARS = 5_400
