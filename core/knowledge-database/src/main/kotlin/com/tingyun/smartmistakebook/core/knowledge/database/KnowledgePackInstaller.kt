package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.util.PriorityQueue

/**
 * Offline-built pack input. This type and every write entry point remain module-internal so the
 * runtime catalog cannot mutate knowledge content.
 */
internal data class KnowledgePackInstallBundle(
    val manifest: KnowledgePackManifestEntity,
    val nodes: List<KnowledgeNodeEntity>,
    val sources: List<KnowledgeSourceEntity>,
    val nodeSourceBindings: List<KnowledgeNodeSourceBindingEntity>,
    val relations: List<KnowledgeNodeRelationEntity>,
    val searchFeatures: List<KnowledgeSearchFeatureEntity>,
    val materials: List<KnowledgeTeachingMaterialEntity>,
    val materialBindings: List<KnowledgeTeachingMaterialNodeBindingEntity>,
) {
    fun validateAndOrderNodes(): List<KnowledgeNodeEntity> {
        validateManifest()

        val nodesById = nodes.associateByUnique(KnowledgeNodeEntity::knowledgeNodeId, "node id")
        val sourcesById =
            sources.associateByUnique(KnowledgeSourceEntity::sourceId, "source id")
        val materialsById =
            materials.associateByUnique(
                KnowledgeTeachingMaterialEntity::materialId,
                "teaching-material id",
            )

        nodes.validateNodes(nodesById, manifest)
        sources.validateSources()
        nodeSourceBindings.validateNodeSourceBindings(nodesById, sourcesById)
        relations.validateRelations(nodesById, sourcesById)
        searchFeatures.validateSearchFeatures(nodesById)
        materials.validateMaterials(sourcesById)
        materialBindings.validateMaterialBindings(nodesById, materialsById)

        return nodes.parentFirst(nodesById)
    }

    private fun validateManifest() {
        require(manifest.manifestKey == ACTIVE_MANIFEST_KEY) {
            "A knowledge pack must install as the active manifest"
        }
        manifest.toCatalogManifest()
        require(manifest.schemaVersion == HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION) {
            "Knowledge-pack schema version does not match this database"
        }
        require(manifest.nodeCount == nodes.size) { "Knowledge-pack node count is inconsistent" }
        require(manifest.sourceCount == sources.size) {
            "Knowledge-pack source count is inconsistent"
        }
        require(manifest.relationCount == relations.size) {
            "Knowledge-pack relation count is inconsistent"
        }
        require(manifest.materialCount == materials.size) {
            "Knowledge-pack teaching-material count is inconsistent"
        }
        require(manifest.searchFeatureCount == searchFeatures.size) {
            "Knowledge-pack search-feature count is inconsistent"
        }
    }
}

internal class KnowledgePackInstaller(
    private val installDao: KnowledgeCatalogInstallDao,
) {
    suspend fun replacePack(bundle: KnowledgePackInstallBundle) {
        installDao.replacePack(bundle)
    }
}

private fun List<KnowledgeNodeEntity>.validateNodes(
    nodesById: Map<String, KnowledgeNodeEntity>,
    manifest: KnowledgePackManifestEntity,
) {
    requireDistinct({ "${it.stableCode}\u0000${it.taxonomyVersion}" }, "stable node reference")
    forEach { node ->
        val subject = enumValue<SubjectKind>(node.subject, "node subject")
        require(subject != SubjectKind.GENERAL) { "Knowledge nodes require a specific subject" }
        enumValue<KnowledgeNodeKind>(node.nodeKind, "node kind")
        enumValue<KnowledgeNodeGranularity>(node.granularity, "node granularity")
        enumValue<KnowledgeNodeVerificationStatus>(
            node.verificationStatus,
            "node verification status",
        )
        require(node.taxonomyVersion == manifest.taxonomyVersion) {
            "Knowledge-node taxonomy version must match the active manifest"
        }
        require(node.taxonomyVersion.isCatalogId()) { "Node taxonomy version is invalid" }
        require(node.knowledgeNodeId.isCatalogId()) { "Knowledge-node id is invalid" }
        require(node.stableCode.isCatalogId()) { "Knowledge-node stable code is invalid" }
        require(node.displayName.isCatalogText()) { "Knowledge-node display name is invalid" }
        require(node.canonicalName.isCatalogText()) { "Knowledge-node canonical name is invalid" }
        require(node.reviewedAtEpochMillis >= 0) { "Node review time must not be negative" }
        val aliases = decodeAliases(node.aliasesText)
        require(aliases == aliases.sorted()) {
            "Knowledge-node aliases must use deterministic lexical order"
        }
        node.toCatalogNode(manifest)

        node.parentKnowledgeNodeId?.let { parentId ->
            val parent = requireNotNull(nodesById[parentId]) {
                "Knowledge-node parent '$parentId' is missing"
            }
            require(parent.taxonomyVersion == node.taxonomyVersion) {
                "Knowledge-node parent must share its taxonomy version"
            }
            require(parent.subject == node.subject) {
                "Knowledge-node parent must share its subject"
            }
        }
    }
}

private fun List<KnowledgeSourceEntity>.validateSources() {
    requireDistinct(KnowledgeSourceEntity::sourceId, "source id")
    requireDistinct(KnowledgeSourceEntity::contentFingerprint, "source fingerprint")
    forEach { source ->
        require(source.sourceId.isCatalogId()) { "Knowledge-source id is invalid" }
        require(enumValue<SubjectKind>(source.subject, "source subject") != SubjectKind.GENERAL) {
            "Knowledge sources require a specific subject"
        }
        enumValue<KnowledgeSourceType>(source.sourceType, "source type")
        enumValue<KnowledgeSourceLicenseStatus>(source.licenseStatus, "source license status")
        enumValue<KnowledgeSourceContentUsePolicy>(
            source.contentUsePolicy,
            "source content-use policy",
        )
        require(source.title.isCatalogText()) { "Knowledge-source title is invalid" }
        require(source.publisher.isNullOrCatalogText()) { "Knowledge-source publisher is invalid" }
        require(source.edition.isNullOrCatalogText()) { "Knowledge-source edition is invalid" }
        require(source.sourceUri.isNullOrCatalogText()) { "Knowledge-source URI is invalid" }
        require(source.contentFingerprint.isSha256()) {
            "Knowledge-source fingerprint must be lowercase SHA-256"
        }
        require(source.licenseExpression.isNullOrCatalogText()) {
            "Knowledge-source license expression is invalid"
        }
        require(source.licenseUri.isNullOrCatalogText()) {
            "Knowledge-source license URI is invalid"
        }
        require(source.attributionText.isNullOrCatalogText()) {
            "Knowledge-source attribution is invalid"
        }
        require(source.reviewedAtEpochMillis >= 0) {
            "Knowledge-source review time must not be negative"
        }
    }
}

private fun List<KnowledgeNodeSourceBindingEntity>.validateNodeSourceBindings(
    nodesById: Map<String, KnowledgeNodeEntity>,
    sourcesById: Map<String, KnowledgeSourceEntity>,
) {
    requireDistinct(
        { "${it.knowledgeNodeId}\u0000${it.sourceId}\u0000${it.sourceLocator}" },
        "node-source binding",
    )
    forEach { binding ->
        val node = requireNotNull(nodesById[binding.knowledgeNodeId]) {
            "Node-source binding references a missing node"
        }
        val source = requireNotNull(sourcesById[binding.sourceId]) {
            "Node-source binding references a missing source"
        }
        require(node.subject == source.subject) {
            "Node-source binding must remain within one subject"
        }
        require(binding.sourceLocator.isCatalogText()) {
            "Node-source binding locator is invalid"
        }
        require(binding.derivationNote.isCatalogText()) {
            "Node-source binding derivation note is invalid"
        }
        require(binding.reviewedAtEpochMillis >= 0) {
            "Node-source binding review time must not be negative"
        }
    }
}

private fun List<KnowledgeNodeRelationEntity>.validateRelations(
    nodesById: Map<String, KnowledgeNodeEntity>,
    sourcesById: Map<String, KnowledgeSourceEntity>,
) {
    requireDistinct(KnowledgeNodeRelationEntity::relationId, "relation id")
    requireDistinct(
        {
            "${it.fromKnowledgeNodeId}\u0000${it.toKnowledgeNodeId}" +
                "\u0000${it.relationType}\u0000${it.taxonomyVersion}"
        },
        "knowledge relation",
    )
    forEach { relation ->
        require(relation.relationId.isCatalogId()) { "Knowledge-relation id is invalid" }
        require(relation.relationType.isCatalogId()) { "Knowledge-relation type is invalid" }
        val subject = enumValue<SubjectKind>(relation.subject, "relation subject")
        require(subject != SubjectKind.GENERAL) { "Knowledge relations require a specific subject" }
        val from = requireNotNull(nodesById[relation.fromKnowledgeNodeId]) {
            "Knowledge relation references a missing source node"
        }
        val to = requireNotNull(nodesById[relation.toKnowledgeNodeId]) {
            "Knowledge relation references a missing target node"
        }
        require(from.knowledgeNodeId != to.knowledgeNodeId) {
            "Knowledge relation cannot point to itself"
        }
        require(
            from.subject == relation.subject &&
                to.subject == relation.subject &&
                from.taxonomyVersion == relation.taxonomyVersion &&
                to.taxonomyVersion == relation.taxonomyVersion,
        ) {
            "Knowledge relation subject and taxonomy must match both nodes"
        }
        val source = requireNotNull(sourcesById[relation.sourceId]) {
            "Knowledge relation references a missing source"
        }
        require(source.subject == relation.subject) {
            "Knowledge relation source must share its subject"
        }
        require(relation.sourceLocator.isCatalogText()) {
            "Knowledge-relation source locator is invalid"
        }
        require(relation.reviewedAtEpochMillis >= 0) {
            "Knowledge-relation review time must not be negative"
        }
    }
}

private fun List<KnowledgeSearchFeatureEntity>.validateSearchFeatures(
    nodesById: Map<String, KnowledgeNodeEntity>,
) {
    requireDistinct(
        { "${it.subject}\u0000${it.searchFeature}\u0000${it.knowledgeNodeId}" },
        "knowledge-search feature",
    )
    val indexedNodeIds = HashSet<String>()
    forEach { feature ->
        val subject = enumValue<SubjectKind>(feature.subject, "search-feature subject")
        require(subject != SubjectKind.GENERAL) {
            "Knowledge-search features require a specific subject"
        }
        val node = requireNotNull(nodesById[feature.knowledgeNodeId]) {
            "Knowledge-search feature references a missing node"
        }
        require(node.subject == feature.subject) {
            "Knowledge-search feature must share its node subject"
        }
        require(
            feature.searchFeature ==
                KnowledgeSearchNormalizer.normalizeFeature(feature.searchFeature),
        ) {
            "Knowledge-search features must be normalized"
        }
        require(feature.searchFeature.isNotBlank()) {
            "Knowledge-search feature must not be blank"
        }
        require(feature.searchFeature.length <= KnowledgeSearchNormalizer.MAX_FEATURE_CHARS) {
            "Knowledge-search feature is too long"
        }
        require(feature.featureKind.isCatalogId()) { "Search-feature kind is invalid" }
        require(feature.rankWeight > 0) { "Search-feature rank weight must be positive" }
        indexedNodeIds += node.knowledgeNodeId
    }
    require(indexedNodeIds.size == nodesById.size) {
        "Every knowledge node must have at least one search feature"
    }
}

private fun List<KnowledgeTeachingMaterialEntity>.validateMaterials(
    sourcesById: Map<String, KnowledgeSourceEntity>,
) {
    requireDistinct(KnowledgeTeachingMaterialEntity::materialId, "teaching-material id")
    requireDistinct(KnowledgeTeachingMaterialEntity::stableCode, "teaching-material stable code")
    requireDistinct(
        KnowledgeTeachingMaterialEntity::contentFingerprint,
        "teaching-material fingerprint",
    )
    forEach { material ->
        require(material.materialId.isCatalogId()) { "Teaching-material id is invalid" }
        require(material.stableCode.isCatalogId()) { "Teaching-material stable code is invalid" }
        val subject = enumValue<SubjectKind>(material.subject, "teaching-material subject")
        require(subject != SubjectKind.GENERAL) {
            "Teaching materials require a specific subject"
        }
        enumValue<KnowledgeTeachingMaterialType>(material.materialType, "teaching-material type")
        enumValue<KnowledgeMaterialDerivationKind>(
            material.derivationKind,
            "teaching-material derivation kind",
        )
        require(material.title.isCatalogText()) { "Teaching-material title is invalid" }
        require(material.summaryMarkdown.isCatalogText()) {
            "Teaching-material summary is invalid"
        }
        require(material.applicabilityMarkdown.isCatalogText()) {
            "Teaching-material applicability is invalid"
        }
        require(material.contentMarkdown.isCatalogText()) {
            "Teaching-material content is invalid"
        }
        require(material.boundaryMarkdown.isCatalogText()) {
            "Teaching-material boundary is invalid"
        }
        require(material.sourceLocator.isCatalogText()) {
            "Teaching-material source locator is invalid"
        }
        require(material.contentFingerprint.isSha256()) {
            "Teaching-material fingerprint must be lowercase SHA-256"
        }
        require(material.reviewedAtEpochMillis >= 0) {
            "Teaching-material review time must not be negative"
        }
        val source = requireNotNull(sourcesById[material.sourceId]) {
            "Teaching material references a missing source"
        }
        require(source.subject == material.subject) {
            "Teaching material source must share its subject"
        }
    }
}

private fun List<KnowledgeTeachingMaterialNodeBindingEntity>.validateMaterialBindings(
    nodesById: Map<String, KnowledgeNodeEntity>,
    materialsById: Map<String, KnowledgeTeachingMaterialEntity>,
) {
    requireDistinct(
        { "${it.materialId}\u0000${it.knowledgeNodeId}" },
        "teaching-material node binding",
    )
    forEach { binding ->
        val material = requireNotNull(materialsById[binding.materialId]) {
            "Teaching-material binding references a missing material"
        }
        val node = requireNotNull(nodesById[binding.knowledgeNodeId]) {
            "Teaching-material binding references a missing node"
        }
        require(material.subject == node.subject) {
            "Teaching-material binding must remain within one subject"
        }
        enumValue<KnowledgeMaterialNodeRole>(binding.role, "teaching-material node role")
    }
}

private fun List<KnowledgeNodeEntity>.parentFirst(
    nodesById: Map<String, KnowledgeNodeEntity>,
): List<KnowledgeNodeEntity> {
    val remainingParents =
        associate { node ->
            node.knowledgeNodeId to if (node.parentKnowledgeNodeId == null) 0 else 1
        }.toMutableMap()
    val childrenByParent =
        filter { it.parentKnowledgeNodeId != null }
            .groupBy { requireNotNull(it.parentKnowledgeNodeId) }
    val comparator =
        compareBy<KnowledgeNodeEntity>(
            { it.stableCode },
            { it.knowledgeNodeId },
        )
    val ready = PriorityQueue(comparator)
    filterTo(ready) { remainingParents.getValue(it.knowledgeNodeId) == 0 }

    val ordered = ArrayList<KnowledgeNodeEntity>(size)
    while (ready.isNotEmpty()) {
        val parent = ready.remove()
        ordered += parent
        childrenByParent[parent.knowledgeNodeId]
            .orEmpty()
            .sortedWith(comparator)
            .forEach { child ->
                remainingParents[child.knowledgeNodeId] = 0
                ready += child
            }
    }
    require(ordered.size == nodesById.size) {
        "Knowledge-node parent graph contains a cycle"
    }
    return ordered
}

private fun <T, K> List<T>.associateByUnique(
    keySelector: (T) -> K,
    label: String,
): Map<K, T> {
    requireDistinct(keySelector, label)
    return associateBy(keySelector)
}

private fun <T, K> List<T>.requireDistinct(
    keySelector: (T) -> K,
    label: String,
) {
    require(map(keySelector).distinct().size == size) { "Duplicate $label" }
}

private inline fun <reified T : Enum<T>> enumValue(
    value: String,
    label: String,
): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("Unknown $label '$value'")

private fun String.isCatalogId(): Boolean =
    isNotBlank() && length <= 160 && none { it.isISOControl() }

private fun String.isCatalogText(): Boolean =
    isNotBlank() &&
        length <= 16_384 &&
        none { it.isISOControl() && it != '\n' && it != '\t' }

private fun String?.isNullOrCatalogText(): Boolean = this == null || isCatalogText()

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
