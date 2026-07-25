package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** The only boundary allowed to promote reviewed research into the durable ontology. */
object KnowledgeBaseImportContract {
    private const val MAX_IMPORT_RECORDS = 4_096
    private val uppercaseSha256 = Regex("[A-F0-9]{64}")
    private val subjects = setOf(
        "CHINESE",
        "MATH",
        "ENGLISH",
        "PHYSICS",
        "CHEMISTRY",
        "BIOLOGY",
        "POLITICS",
        "HISTORY",
        "GEOGRAPHY",
    )
    private val nodeKinds = enumValues<KnowledgeNodeKind>().map { it.name }.toSet()
    private val granularities = enumValues<KnowledgeNodeGranularity>().map { it.name }.toSet()
    private val verificationStatuses =
        enumValues<KnowledgeNodeVerificationStatus>().map { it.name }.toSet()
    private val sourceTypes = enumValues<KnowledgeSourceType>().map { it.name }.toSet()
    private val licenseStatuses = enumValues<KnowledgeSourceLicenseStatus>().map { it.name }.toSet()
    private val contentUsePolicies =
        enumValues<KnowledgeSourceContentUsePolicy>().map { it.name }.toSet()

    fun validate(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
        existingSources: List<KnowledgeSourceSeedRecord> = emptyList(),
        existingParentNodes: List<KnowledgeNodeSeedRecord> = emptyList(),
    ) {
        requireImport(nodes.isNotEmpty()) { "A knowledge-base import needs at least one node" }
        requireImport(bindings.isNotEmpty()) {
            "A knowledge-base import needs reviewed provenance bindings"
        }
        requireImport(
            sources.size <= MAX_IMPORT_RECORDS &&
                nodes.size <= MAX_IMPORT_RECORDS &&
                bindings.size <= MAX_IMPORT_RECORDS,
        ) { "A knowledge-base import is too large" }

        validateSourcesOnly(sources)
        val newSourcesById = sources.associateBy(KnowledgeSourceSeedRecord::sourceId)
        val newNodesById = nodes.uniqueBy(KnowledgeNodeSeedRecord::knowledgeNodeId, "node ids")
        val sourcesById = existingSources.associateBy(KnowledgeSourceSeedRecord::sourceId) + newSourcesById
        val nodesById = existingParentNodes.associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId) + newNodesById
        requireImport(nodes.map(KnowledgeNodeSeedRecord::stableCode).distinct().size == nodes.size) {
            "Knowledge node stable codes must be unique"
        }

        nodes.forEach { node -> validateNode(node, nodesById) }
        validateTopicHierarchyIsAcyclic(newNodesById.values, nodesById)
        validateBindings(bindings, newSourcesById, newNodesById, sourcesById)
    }

    fun validateSourcesOnly(sources: List<KnowledgeSourceSeedRecord>) {
        requireImport(sources.size <= MAX_IMPORT_RECORDS) {
            "A knowledge-source import is too large"
        }
        sources.uniqueBy(KnowledgeSourceSeedRecord::sourceId, "source ids")
        requireImport(
            sources.map(KnowledgeSourceSeedRecord::contentFingerprint).distinct().size ==
                sources.size,
        ) { "Knowledge source fingerprints must be unique" }
        sources.forEach(::validateSource)
    }

    private fun validateSource(source: KnowledgeSourceSeedRecord) {
        id(source.sourceId, "sourceId")
        known(source.subject, "source.subject", subjects)
        known(source.sourceType, "source.sourceType", sourceTypes)
        text(source.title, "source.title", 1_000)
        source.publisher?.let { text(it, "source.publisher", 500) }
        source.edition?.let { text(it, "source.edition", 500) }
        known(source.licenseStatus, "source.licenseStatus", licenseStatuses)
        known(source.contentUsePolicy, "source.contentUsePolicy", contentUsePolicies)
        validateSourceReuseRights(source)
        requireImport(uppercaseSha256.matches(source.contentFingerprint)) {
            "source.contentFingerprint must be an uppercase SHA-256 digest"
        }
        requireImport(source.importedAtEpochMillis > 0) {
            "source.importedAtEpochMillis must be positive"
        }
        validateSourceUri(source)
    }

    private fun validateSourceReuseRights(source: KnowledgeSourceSeedRecord) {
        val directReuse = source.contentUsePolicy !=
            KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY.name
        if (source.licenseStatus == KnowledgeSourceLicenseStatus.REFERENCE_ONLY.name) {
            requireImport(!directReuse) {
                "Reference-only knowledge sources permit reviewed synthesis only"
            }
        }
        if (directReuse) {
            text(
                source.licenseExpression.orEmpty(),
                "source.licenseExpression",
                240,
            )
            val licenseUri = source.licenseUri
                ?: throw DatabaseContractViolationException(
                    "Direct source reuse needs a license URI",
                )
            validateHttpsUri(licenseUri, "source.licenseUri")
            text(
                source.attributionText.orEmpty(),
                "source.attributionText",
                2_000,
            )
        } else {
            source.licenseExpression?.let {
                text(it, "source.licenseExpression", 240)
            }
            source.licenseUri?.let {
                validateHttpsUri(it, "source.licenseUri")
            }
            source.attributionText?.let {
                text(it, "source.attributionText", 2_000)
            }
        }
    }

    private fun validateNode(
        node: KnowledgeNodeSeedRecord,
        nodesById: Map<String, KnowledgeNodeSeedRecord>,
    ) {
        id(node.knowledgeNodeId, "knowledgeNodeId")
        id(node.stableCode, "stableCode")
        known(node.subject, "node.subject", subjects)
        text(node.displayName, "node.displayName", 500)
        text(node.canonicalName, "node.canonicalName", 500)
        node.parentKnowledgeNodeId?.let { id(it, "parentKnowledgeNodeId") }
        id(node.taxonomyVersion, "taxonomyVersion")
        requireImport(node.createdAtEpochMillis > 0) { "node.createdAtEpochMillis must be positive" }
        known(node.nodeKind, "node.nodeKind", nodeKinds)
        known(node.granularity, "node.granularity", granularities)
        known(node.verificationStatus, "node.verificationStatus", verificationStatuses)
        requireImport(node.verificationStatus != KnowledgeNodeVerificationStatus.MODEL_CANDIDATE.name) {
            "Model-candidate knowledge cannot enter the durable ontology"
        }
        requireImport(
            (node.nodeKind == KnowledgeNodeKind.TOPIC.name) ==
                (node.granularity == KnowledgeNodeGranularity.TOPIC.name),
        ) { "Only topic nodes may use topic granularity" }
        validateAliases(node.aliases)

        if (node.granularity == KnowledgeNodeGranularity.ATOMIC.name) {
            requireImport(node.verificationStatus == KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name) {
                "Atomic knowledge must be source-grounded before import"
            }
            text(node.boundaryMarkdown.orEmpty(), "node.boundaryMarkdown", 4_000)
            validateParent(node, nodesById, "Atomic knowledge needs a same-subject topic parent")
        } else {
            node.boundaryMarkdown?.let { text(it, "node.boundaryMarkdown", 4_000) }
            if (node.parentKnowledgeNodeId != null) {
                validateParent(node, nodesById, "Topic parents must remain inside one subject")
            }
        }
    }

    private fun validateParent(
        node: KnowledgeNodeSeedRecord,
        nodesById: Map<String, KnowledgeNodeSeedRecord>,
        errorPrefix: String,
    ) {
        val parent = node.parentKnowledgeNodeId?.let(nodesById::get)
        requireImport(
            parent != null &&
                parent.subject == node.subject &&
                parent.granularity == KnowledgeNodeGranularity.TOPIC.name &&
                parent.taxonomyVersion == node.taxonomyVersion,
        ) { "$errorPrefix in the same taxonomy" }
    }

    private fun validateAliases(aliases: Set<String>) {
        requireImport(aliases.size <= 32) { "A knowledge node has too many aliases" }
        val normalized = aliases.map { alias ->
            text(alias, "node.alias", 200)
            requireImport(alias == alias.trim()) { "Knowledge aliases must be trimmed" }
            alias.lowercase(Locale.ROOT)
        }
        requireImport(normalized.distinct().size == normalized.size) {
            "Knowledge aliases must be unique ignoring case"
        }
    }

    private fun validateBindings(
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
        newSourcesById: Map<String, KnowledgeSourceSeedRecord>,
        newNodesById: Map<String, KnowledgeNodeSeedRecord>,
        sourcesById: Map<String, KnowledgeSourceSeedRecord>,
    ) {
        val keys = bindings.map { Triple(it.knowledgeNodeId, it.sourceId, it.sourceLocator) }
        requireImport(keys.distinct().size == keys.size) { "Knowledge provenance bindings must be unique" }
        bindings.forEach { binding ->
            id(binding.knowledgeNodeId, "binding.knowledgeNodeId")
            id(binding.sourceId, "binding.sourceId")
            text(binding.sourceLocator, "binding.sourceLocator", 2_000)
            text(binding.derivationNote, "binding.derivationNote", 4_000)
            requireImport(binding.reviewedAtEpochMillis?.let { it > 0 } == true) {
                "Every provenance binding must record a positive review timestamp"
            }
            val node = newNodesById[binding.knowledgeNodeId]
            val source = sourcesById[binding.sourceId]
            requireImport(node != null && source != null && node.subject == source.subject) {
                "Every provenance binding must reference a new node and stay inside one subject"
            }
        }
        requireImport(bindings.mapTo(mutableSetOf()) { it.knowledgeNodeId } == newNodesById.keys) {
            "Every imported knowledge node must have reviewed provenance"
        }
        requireImport(newSourcesById.keys.all { sourceId -> bindings.any { it.sourceId == sourceId } }) {
            "Every imported knowledge source must support at least one node"
        }
    }

    private fun validateSourceUri(source: KnowledgeSourceSeedRecord) {
        val uriText = source.sourceUri
        val uriRequired = source.licenseStatus != KnowledgeSourceLicenseStatus.LICENSED.name
        requireImport(!uriRequired || uriText != null) {
            "Public and reference-only knowledge sources need a source URI"
        }
        if (uriText == null) return
        validateHttpsUri(uriText, "source.sourceUri")
    }

    private fun validateHttpsUri(uriText: String, fieldName: String) {
        text(uriText, fieldName, 2_000)
        val uri = try {
            URI(uriText)
        } catch (_: URISyntaxException) {
            throw DatabaseContractViolationException("$fieldName is not a valid URI")
        }
        requireImport(
            "https".equals(uri.scheme, ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null,
        ) { "$fieldName must be an absolute HTTPS URI without credentials or fragments" }
    }

    private fun validateTopicHierarchyIsAcyclic(
        newNodes: Collection<KnowledgeNodeSeedRecord>,
        nodesById: Map<String, KnowledgeNodeSeedRecord>,
    ) {
        newNodes.asSequence()
            .filter { it.granularity == KnowledgeNodeGranularity.TOPIC.name }
            .forEach { start ->
                val visited = mutableSetOf<String>()
                var current: KnowledgeNodeSeedRecord? = start
                while (current != null) {
                    requireImport(visited.add(current.knowledgeNodeId)) {
                        "Knowledge topic hierarchy cannot contain a cycle"
                    }
                    current = current.parentKnowledgeNodeId?.let(nodesById::get)
                }
            }
    }

    private fun <T> List<T>.uniqueBy(
        selector: (T) -> String,
        label: String,
    ): Map<String, T> = associateBy(selector).also { indexed ->
        requireImport(indexed.size == size) { "Knowledge $label must be unique" }
    }

    private fun id(value: String, name: String) {
        requireImport(value.isNotBlank() && value == value.trim() && value.length <= 256) {
            "$name must be a trimmed non-blank string of at most 256 characters"
        }
        requireImport(value.none(Char::isISOControl)) { "$name contains control characters" }
    }

    private fun text(value: String, name: String, maxLength: Int) {
        requireImport(value.isNotBlank() && value.length <= maxLength) {
            "$name must be non-blank and at most $maxLength characters"
        }
        requireImport(
            value.none { character ->
                (character.isISOControl() && character !in "\n\r\t") ||
                    character.isBidirectionalControl()
            },
        ) {
            "$name contains unsupported control characters"
        }
    }

    private fun Char.isBidirectionalControl(): Boolean =
        this == '\u061C' || this == '\u200E' || this == '\u200F' ||
            this in '\u202A'..'\u202E' || this in '\u2066'..'\u2069'

    private fun known(value: String, name: String, allowed: Set<String>) {
        requireImport(value in allowed) { "$name has unknown value '$value'" }
    }

    private inline fun requireImport(condition: Boolean, message: () -> String) {
        if (!condition) throw DatabaseContractViolationException(message())
    }
}
