package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.io.Closeable

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
 * Runtime access to the independent high-school catalog.
 *
 * The interface deliberately exposes no installation, mutation, raw SQL, Room database, or file
 * handle. Pack construction and installation remain module-internal.
 */
interface HighSchoolKnowledgeCatalog : Closeable {
    suspend fun readManifest(): KnowledgePackManifest

    suspend fun findNode(ref: KnowledgeNodeRef): KnowledgeCatalogNode?

    suspend fun recall(
        subject: SubjectKind,
        query: String,
        limit: Int = DEFAULT_RECALL_LIMIT,
    ): List<KnowledgeCatalogSearchHit>

    suspend fun expandRelations(
        origin: KnowledgeNodeRef,
        direction: KnowledgeRelationDirection = KnowledgeRelationDirection.BOTH,
        relationTypes: Set<String> = emptySet(),
        limit: Int = DEFAULT_RELATION_LIMIT,
    ): List<KnowledgeCatalogRelation>

    companion object {
        const val DEFAULT_RECALL_LIMIT = 20
        const val DEFAULT_RELATION_LIMIT = 40
        const val MAX_QUERY_CHARS = 256
        const val MAX_RESULT_LIMIT = 100
    }
}

private val SHA_256 = Regex("[0-9a-f]{64}")

private fun String.requireCatalogId(label: String) {
    require(isNotBlank() && length <= 160 && none { it.isISOControl() }) {
        "$label is invalid"
    }
}

private fun String.requireCatalogText(label: String) {
    require(isNotBlank() && length <= 4_096 && none { it.isISOControl() && it != '\n' && it != '\t' }) {
        "$label is invalid"
    }
}
