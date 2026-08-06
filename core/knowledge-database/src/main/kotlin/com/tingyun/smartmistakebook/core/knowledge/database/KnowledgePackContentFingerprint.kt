package com.tingyun.smartmistakebook.core.knowledge.database

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Canonical digest of every persisted knowledge-pack field except the digest itself.
 *
 * Length-prefixing keeps the encoding unambiguous even when reviewed text contains separators.
 * Stable sorting makes the result independent of collection and database iteration order.
 */
internal object KnowledgePackContentFingerprint {
    fun compute(bundle: KnowledgePackInstallBundle): String =
        KnowledgePackContentFingerprintStream(bundle.manifest)
            .apply {
                beginNodes(bundle.nodes.size)
                bundle.nodes.sortedWith(NODE_FINGERPRINT_COMPARATOR).forEach(::addNode)

                beginSources(bundle.sources.size)
                bundle.sources.sortedWith(SOURCE_FINGERPRINT_COMPARATOR).forEach(::addSource)

                beginNodeSourceBindings(bundle.nodeSourceBindings.size)
                bundle.nodeSourceBindings
                    .sortedWith(NODE_SOURCE_BINDING_COMPARATOR)
                    .forEach(::addNodeSourceBinding)

                beginRelations(bundle.relations.size)
                bundle.relations
                    .sortedWith(RELATION_FINGERPRINT_COMPARATOR)
                    .forEach(::addRelation)

                beginSearchFeatures(bundle.searchFeatures.size)
                bundle.searchFeatures
                    .sortedWith(SEARCH_FEATURE_FINGERPRINT_COMPARATOR)
                    .forEach(::addSearchFeature)

                beginMaterials(bundle.materials.size)
                bundle.materials
                    .sortedWith(MATERIAL_FINGERPRINT_COMPARATOR)
                    .forEach(::addMaterial)

                beginMaterialBindings(bundle.materialBindings.size)
                bundle.materialBindings
                    .sortedWith(MATERIAL_BINDING_COMPARATOR)
                    .forEach(::addMaterialBinding)
            }.finish()
}

/**
 * Incremental form of [KnowledgePackContentFingerprint]. Activation uses this writer with ordered
 * SQLite cursors so a million-row search index never becomes a million-object in-memory list.
 * Section order and declared row counts are enforced here to keep the streamed and offline forms
 * byte-for-byte identical.
 */
internal class KnowledgePackContentFingerprintStream(
    manifest: KnowledgePackManifestEntity,
) {
    private val digest = CanonicalPackDigest(FINGERPRINT_DOMAIN)
    private var nextSectionIndex = 0
    private var activeSection: String? = null
    private var remainingRows = 0

    init {
        digest.section("manifest", 1)
        digest.writeManifest(manifest)
    }

    fun beginNodes(count: Int) = beginSection("knowledge_node", count)

    fun addNode(row: KnowledgeNodeEntity) = addRow("knowledge_node") { digest.writeNode(row) }

    fun beginSources(count: Int) = beginSection("knowledge_source", count)

    fun addSource(row: KnowledgeSourceEntity) =
        addRow("knowledge_source") { digest.writeSource(row) }

    fun beginNodeSourceBindings(count: Int) =
        beginSection("knowledge_node_source_binding", count)

    fun addNodeSourceBinding(row: KnowledgeNodeSourceBindingEntity) =
        addRow("knowledge_node_source_binding") { digest.writeNodeSourceBinding(row) }

    fun beginRelations(count: Int) = beginSection("knowledge_node_relation", count)

    fun addRelation(row: KnowledgeNodeRelationEntity) =
        addRow("knowledge_node_relation") { digest.writeRelation(row) }

    fun beginSearchFeatures(count: Int) = beginSection("knowledge_search_feature", count)

    fun addSearchFeature(row: KnowledgeSearchFeatureEntity) =
        addRow("knowledge_search_feature") { digest.writeSearchFeature(row) }

    fun beginMaterials(count: Int) = beginSection("knowledge_teaching_material", count)

    fun addMaterial(row: KnowledgeTeachingMaterialEntity) =
        addRow("knowledge_teaching_material") { digest.writeMaterial(row) }

    fun beginMaterialBindings(count: Int) =
        beginSection("knowledge_teaching_material_node_binding", count)

    fun addMaterialBinding(row: KnowledgeTeachingMaterialNodeBindingEntity) =
        addRow("knowledge_teaching_material_node_binding") { digest.writeMaterialBinding(row) }

    fun finish(): String {
        check(remainingRows == 0) {
            "Canonical knowledge-pack section '$activeSection' ended before its declared row count"
        }
        check(nextSectionIndex == FINGERPRINT_SECTIONS.size) {
            "Canonical knowledge-pack fingerprint is missing persisted sections"
        }
        return digest.finish()
    }

    private fun beginSection(
        name: String,
        count: Int,
    ) {
        require(count >= 0) { "Canonical knowledge-pack row count cannot be negative" }
        check(remainingRows == 0) {
            "Canonical knowledge-pack section '$activeSection' ended before its declared row count"
        }
        check(nextSectionIndex < FINGERPRINT_SECTIONS.size && FINGERPRINT_SECTIONS[nextSectionIndex] == name) {
            "Canonical knowledge-pack sections are out of order"
        }
        activeSection = name
        remainingRows = count
        nextSectionIndex += 1
        digest.section(name, count)
    }

    private inline fun addRow(
        section: String,
        write: () -> Unit,
    ) {
        check(activeSection == section && remainingRows > 0) {
            "Canonical knowledge-pack section '$section' exceeds its declared row count"
        }
        write()
        remainingRows -= 1
    }
}

private fun CanonicalPackDigest.writeManifest(manifest: KnowledgePackManifestEntity) {
    field("manifestKey", manifest.manifestKey)
    field("packId", manifest.packId)
    field("schemaVersion", manifest.schemaVersion)
    field("knowledgePackVersion", manifest.knowledgePackVersion)
    field("taxonomyVersion", manifest.taxonomyVersion)
    field("searchIndexVersion", manifest.searchIndexVersion)
    field("builtAtEpochMillis", manifest.builtAtEpochMillis)
    field("nodeCount", manifest.nodeCount)
    field("sourceCount", manifest.sourceCount)
    field("relationCount", manifest.relationCount)
    field("materialCount", manifest.materialCount)
    field("searchFeatureCount", manifest.searchFeatureCount)
}

private fun CanonicalPackDigest.writeNode(row: KnowledgeNodeEntity) {
    field("knowledgeNodeId", row.knowledgeNodeId)
    field("stableCode", row.stableCode)
    field("subject", row.subject)
    field("displayName", row.displayName)
    field("canonicalName", row.canonicalName)
    field("nodeKind", row.nodeKind)
    field("granularity", row.granularity)
    field("aliasesText", row.aliasesText)
    nullableField("boundaryMarkdown", row.boundaryMarkdown)
    field("verificationStatus", row.verificationStatus)
    nullableField("parentKnowledgeNodeId", row.parentKnowledgeNodeId)
    field("taxonomyVersion", row.taxonomyVersion)
    field("reviewedAtEpochMillis", row.reviewedAtEpochMillis)
}

private fun CanonicalPackDigest.writeSource(row: KnowledgeSourceEntity) {
    field("sourceId", row.sourceId)
    field("subject", row.subject)
    field("sourceType", row.sourceType)
    field("title", row.title)
    nullableField("publisher", row.publisher)
    nullableField("edition", row.edition)
    nullableField("sourceUri", row.sourceUri)
    field("licenseStatus", row.licenseStatus)
    field("contentUsePolicy", row.contentUsePolicy)
    field("contentFingerprint", row.contentFingerprint)
    nullableField("licenseExpression", row.licenseExpression)
    nullableField("licenseUri", row.licenseUri)
    nullableField("attributionText", row.attributionText)
    field("reviewedAtEpochMillis", row.reviewedAtEpochMillis)
}

private fun CanonicalPackDigest.writeNodeSourceBinding(row: KnowledgeNodeSourceBindingEntity) {
    field("knowledgeNodeId", row.knowledgeNodeId)
    field("sourceId", row.sourceId)
    field("sourceLocator", row.sourceLocator)
    field("derivationNote", row.derivationNote)
    field("reviewedAtEpochMillis", row.reviewedAtEpochMillis)
}

private fun CanonicalPackDigest.writeRelation(row: KnowledgeNodeRelationEntity) {
    field("relationId", row.relationId)
    field("subject", row.subject)
    field("fromKnowledgeNodeId", row.fromKnowledgeNodeId)
    field("toKnowledgeNodeId", row.toKnowledgeNodeId)
    field("relationType", row.relationType)
    field("taxonomyVersion", row.taxonomyVersion)
    field("sourceId", row.sourceId)
    field("sourceLocator", row.sourceLocator)
    field("reviewedAtEpochMillis", row.reviewedAtEpochMillis)
}

private fun CanonicalPackDigest.writeSearchFeature(row: KnowledgeSearchFeatureEntity) {
    field("subject", row.subject)
    field("searchFeature", row.searchFeature)
    field("knowledgeNodeId", row.knowledgeNodeId)
    field("featureKind", row.featureKind)
    field("rankWeight", row.rankWeight)
}

private fun CanonicalPackDigest.writeMaterial(row: KnowledgeTeachingMaterialEntity) {
    field("materialId", row.materialId)
    field("stableCode", row.stableCode)
    field("subject", row.subject)
    field("materialType", row.materialType)
    field("title", row.title)
    field("summaryMarkdown", row.summaryMarkdown)
    field("applicabilityMarkdown", row.applicabilityMarkdown)
    field("contentMarkdown", row.contentMarkdown)
    field("boundaryMarkdown", row.boundaryMarkdown)
    field("derivationKind", row.derivationKind)
    field("sourceId", row.sourceId)
    field("sourceLocator", row.sourceLocator)
    field("contentFingerprint", row.contentFingerprint)
    field("reviewedAtEpochMillis", row.reviewedAtEpochMillis)
}

private fun CanonicalPackDigest.writeMaterialBinding(
    row: KnowledgeTeachingMaterialNodeBindingEntity,
) {
    field("materialId", row.materialId)
    field("knowledgeNodeId", row.knowledgeNodeId)
    field("role", row.role)
}

private class CanonicalPackDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val lengthPrefix = ByteArray(Int.SIZE_BYTES)

    init {
        append(domain)
    }

    fun section(
        name: String,
        count: Int,
    ) {
        append("section")
        append(name)
        append(count.toString())
    }

    fun field(
        name: String,
        value: String,
    ) {
        append(name)
        append("present")
        append(value)
    }

    fun field(
        name: String,
        value: Int,
    ) = field(name, value.toString())

    fun field(
        name: String,
        value: Long,
    ) = field(name, value.toString())

    fun nullableField(
        name: String,
        value: String?,
    ) {
        append(name)
        if (value == null) {
            append("null")
        } else {
            append("present")
            append(value)
        }
    }

    fun finish(): String =
        buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }

    private fun append(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val size = bytes.size
        lengthPrefix[0] = (size ushr 24).toByte()
        lengthPrefix[1] = (size ushr 16).toByte()
        lengthPrefix[2] = (size ushr 8).toByte()
        lengthPrefix[3] = size.toByte()
        digest.update(lengthPrefix)
        digest.update(bytes)
    }
}

private const val FINGERPRINT_DOMAIN = "high-school-knowledge-pack-content-v1"
private const val HEX_DIGITS = "0123456789abcdef"

private val FINGERPRINT_SECTIONS =
    listOf(
        "knowledge_node",
        "knowledge_source",
        "knowledge_node_source_binding",
        "knowledge_node_relation",
        "knowledge_search_feature",
        "knowledge_teaching_material",
        "knowledge_teaching_material_node_binding",
    )

private val NODE_SOURCE_BINDING_COMPARATOR =
    Comparator<KnowledgeNodeSourceBindingEntity> { left, right ->
        compareCanonicalText(left.knowledgeNodeId, right.knowledgeNodeId)
            .ifEqual { compareCanonicalText(left.sourceId, right.sourceId) }
            .ifEqual { compareCanonicalText(left.sourceLocator, right.sourceLocator) }
    }

private val SEARCH_FEATURE_FINGERPRINT_COMPARATOR =
    Comparator<KnowledgeSearchFeatureEntity> { left, right ->
        compareCanonicalText(left.subject, right.subject)
            .ifEqual { compareCanonicalText(left.searchFeature, right.searchFeature) }
            .ifEqual { compareCanonicalText(left.knowledgeNodeId, right.knowledgeNodeId) }
    }

private val MATERIAL_BINDING_COMPARATOR =
    Comparator<KnowledgeTeachingMaterialNodeBindingEntity> { left, right ->
        compareCanonicalText(left.materialId, right.materialId)
            .ifEqual { compareCanonicalText(left.knowledgeNodeId, right.knowledgeNodeId) }
    }

private val NODE_FINGERPRINT_COMPARATOR =
    Comparator<KnowledgeNodeEntity> { left, right ->
        compareCanonicalText(left.knowledgeNodeId, right.knowledgeNodeId)
    }

private val SOURCE_FINGERPRINT_COMPARATOR =
    Comparator<KnowledgeSourceEntity> { left, right ->
        compareCanonicalText(left.sourceId, right.sourceId)
    }

private val RELATION_FINGERPRINT_COMPARATOR =
    Comparator<KnowledgeNodeRelationEntity> { left, right ->
        compareCanonicalText(left.relationId, right.relationId)
    }

private val MATERIAL_FINGERPRINT_COMPARATOR =
    Comparator<KnowledgeTeachingMaterialEntity> { left, right ->
        compareCanonicalText(left.materialId, right.materialId)
    }

/** SQLite BINARY ordering is unsigned UTF-8, which is Unicode code-point order for valid text. */
private fun compareCanonicalText(
    left: String,
    right: String,
): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftCodePoint = Character.codePointAt(left, leftIndex)
        val rightCodePoint = Character.codePointAt(right, rightIndex)
        if (leftCodePoint != rightCodePoint) return leftCodePoint.compareTo(rightCodePoint)
        leftIndex += Character.charCount(leftCodePoint)
        rightIndex += Character.charCount(rightCodePoint)
    }
    return (left.length - leftIndex).compareTo(right.length - rightIndex)
}

private inline fun Int.ifEqual(next: () -> Int): Int = if (this == 0) next() else this
