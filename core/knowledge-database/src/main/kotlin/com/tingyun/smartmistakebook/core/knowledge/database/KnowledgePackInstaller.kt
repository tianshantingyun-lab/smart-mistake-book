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
import java.util.Collections
import java.util.PriorityQueue

/**
 * Offline-built pack input. This type and every write entry point remain module-internal so the
 * runtime catalog cannot mutate knowledge content.
 */
internal class KnowledgePackInstallBundle(
    val manifest: KnowledgePackManifestEntity,
    nodes: List<KnowledgeNodeEntity>,
    sources: List<KnowledgeSourceEntity>,
    nodeSourceBindings: List<KnowledgeNodeSourceBindingEntity>,
    relations: List<KnowledgeNodeRelationEntity>,
    searchFeatures: List<KnowledgeSearchFeatureEntity>,
    materials: List<KnowledgeTeachingMaterialEntity>,
    materialBindings: List<KnowledgeTeachingMaterialNodeBindingEntity>,
) {
    val nodes: List<KnowledgeNodeEntity> =
        nodes.snapshotWithinBudget(KnowledgePackBudgets.MAX_NODES, "node")
    val sources: List<KnowledgeSourceEntity> =
        sources.snapshotWithinBudget(KnowledgePackBudgets.MAX_SOURCES, "source")
    val nodeSourceBindings: List<KnowledgeNodeSourceBindingEntity> =
        nodeSourceBindings.snapshotWithinBudget(
            KnowledgePackBudgets.MAX_NODE_SOURCE_BINDINGS,
            "node-source binding",
        )
    val relations: List<KnowledgeNodeRelationEntity> =
        relations.snapshotWithinBudget(KnowledgePackBudgets.MAX_RELATIONS, "relation")
    val searchFeatures: List<KnowledgeSearchFeatureEntity> =
        searchFeatures.snapshotWithinBudget(
            KnowledgePackBudgets.MAX_SEARCH_FEATURES,
            "search feature",
        ).canonicalized(SEARCH_FEATURE_CANONICAL_COMPARATOR)
    val materials: List<KnowledgeTeachingMaterialEntity> =
        materials.snapshotWithinBudget(KnowledgePackBudgets.MAX_MATERIALS, "teaching material")
    val materialBindings: List<KnowledgeTeachingMaterialNodeBindingEntity> =
        materialBindings.snapshotWithinBudget(
            KnowledgePackBudgets.MAX_MATERIAL_BINDINGS,
            "teaching-material binding",
        )

    fun withRecomputedContentFingerprint(): KnowledgePackInstallBundle =
        recreate(
            manifest =
                manifest.copy(
                    contentFingerprint = KnowledgePackContentFingerprint.compute(this),
                ),
        )

    fun validateAndOrderNodes(): List<KnowledgeNodeEntity> = validatedSnapshot().nodes

    fun validatedSnapshot(): ValidatedKnowledgePack {
        validateCollectionBudgets()
        validateNodeTextBudgets()
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
        val parentFirstNodes = nodes.parentFirst(nodesById)
        sources.validateSources()
        nodeSourceBindings.validateNodeSourceBindings(nodesById, sourcesById)
        relations.validateRelations(nodesById, sourcesById)
        relations.validatePrerequisiteGraph(nodesById)
        materials.validateMaterials(sourcesById)
        materialBindings.validateMaterialBindings(nodesById, materialsById)
        validateNineSubjectCoverage()
        validateCompleteSourceGrounding(nodesById, materialsById)
        val rebuiltSearchFeatures = validateAndRebuildSearchFeatures(nodesById)
        require(
            KnowledgePackContentFingerprint.compute(this) == manifest.contentFingerprint,
        ) {
            "Knowledge-pack content fingerprint does not match its complete canonical content"
        }

        return ValidatedKnowledgePack(
            manifest = manifest,
            nodes = parentFirstNodes,
            sources = sources.sortedBy(KnowledgeSourceEntity::sourceId),
            nodeSourceBindings =
                nodeSourceBindings.sortedWith(
                    compareBy(
                        KnowledgeNodeSourceBindingEntity::knowledgeNodeId,
                        KnowledgeNodeSourceBindingEntity::sourceId,
                        KnowledgeNodeSourceBindingEntity::sourceLocator,
                    ),
                ),
            relations = relations.sortedBy(KnowledgeNodeRelationEntity::relationId),
            searchFeatures = rebuiltSearchFeatures,
            materials = materials.sortedBy(KnowledgeTeachingMaterialEntity::materialId),
            materialBindings =
                materialBindings.sortedWith(
                    compareBy(
                        KnowledgeTeachingMaterialNodeBindingEntity::materialId,
                        KnowledgeTeachingMaterialNodeBindingEntity::knowledgeNodeId,
                    ),
                ),
        )
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

    private fun validateCollectionBudgets() {
        require(nodes.size <= KnowledgePackBudgets.MAX_NODES) {
            "Knowledge pack exceeds the node budget"
        }
        require(sources.size <= KnowledgePackBudgets.MAX_SOURCES) {
            "Knowledge pack exceeds the source budget"
        }
        require(nodeSourceBindings.size <= KnowledgePackBudgets.MAX_NODE_SOURCE_BINDINGS) {
            "Knowledge pack exceeds the node-source binding budget"
        }
        require(relations.size <= KnowledgePackBudgets.MAX_RELATIONS) {
            "Knowledge pack exceeds the relation budget"
        }
        require(searchFeatures.size <= KnowledgePackBudgets.MAX_SEARCH_FEATURES) {
            "Knowledge pack exceeds the search-feature budget"
        }
        require(materials.size <= KnowledgePackBudgets.MAX_MATERIALS) {
            "Knowledge pack exceeds the teaching-material budget"
        }
        require(materialBindings.size <= KnowledgePackBudgets.MAX_MATERIAL_BINDINGS) {
            "Knowledge pack exceeds the teaching-material binding budget"
        }
    }

    private fun validateNodeTextBudgets() {
        nodes.forEach { node ->
            node.aliasesText.requireAliasEncodingWithinBudget()
            require(
                node.boundaryMarkdown == null ||
                    node.boundaryMarkdown.length <= KnowledgePackBudgets.MAX_NODE_BOUNDARY_CHARS,
            ) {
                "Knowledge-node boundary exceeds its character budget"
            }
        }
    }

    private fun validateNineSubjectCoverage() {
        val nodeSubjects = nodes.mapTo(mutableSetOf()) { SubjectKind.valueOf(it.subject) }
        require(nodeSubjects == REQUIRED_HIGH_SCHOOL_SUBJECTS) {
            "A formal high-school knowledge pack must cover all nine subjects"
        }
    }

    private fun validateCompleteSourceGrounding(
        nodesById: Map<String, KnowledgeNodeEntity>,
        materialsById: Map<String, KnowledgeTeachingMaterialEntity>,
    ) {
        require(nodeSourceBindings.mapTo(mutableSetOf()) { it.knowledgeNodeId } == nodesById.keys) {
            "Every knowledge node must have a reviewed source binding"
        }
        require(materialBindings.mapTo(mutableSetOf()) { it.materialId } == materialsById.keys) {
            "Every teaching material must be bound to at least one knowledge node"
        }
    }

    private fun validateAndRebuildSearchFeatures(
        nodesById: Map<String, KnowledgeNodeEntity>,
    ): List<KnowledgeSearchFeatureEntity> {
        searchFeatures.validateSearchFeatures(nodesById)
        var rebuiltFeatureCount = 0
        nodes.forEach { node ->
            KnowledgeSearchIndexBuilder.buildForNode(node).forEach { expected ->
                rebuiltFeatureCount += 1
                val index =
                    searchFeatures.binarySearch(
                        element = expected,
                        comparator = SEARCH_FEATURE_CANONICAL_COMPARATOR,
                    )
                require(index >= 0 && searchFeatures[index] == expected) {
                    "Knowledge-search features do not match the locally rebuilt index"
                }
            }
        }
        require(rebuiltFeatureCount == searchFeatures.size) {
            "Knowledge-search features contain entries outside the locally rebuilt index"
        }
        return searchFeatures
    }

    private fun recreate(manifest: KnowledgePackManifestEntity): KnowledgePackInstallBundle =
        KnowledgePackInstallBundle(
            manifest = manifest,
            nodes = nodes,
            sources = sources,
            nodeSourceBindings = nodeSourceBindings,
            relations = relations,
            searchFeatures = searchFeatures,
            materials = materials,
            materialBindings = materialBindings,
        )
}

internal class KnowledgePackInstaller(
    private val installDao: KnowledgeCatalogInstallDao,
) {
    suspend fun replacePack(bundle: KnowledgePackInstallBundle) {
        installDao.replacePack(bundle.validatedSnapshot())
    }
}

internal data class ValidatedKnowledgePack(
    val manifest: KnowledgePackManifestEntity,
    val nodes: List<KnowledgeNodeEntity>,
    val sources: List<KnowledgeSourceEntity>,
    val nodeSourceBindings: List<KnowledgeNodeSourceBindingEntity>,
    val relations: List<KnowledgeNodeRelationEntity>,
    val searchFeatures: List<KnowledgeSearchFeatureEntity>,
    val materials: List<KnowledgeTeachingMaterialEntity>,
    val materialBindings: List<KnowledgeTeachingMaterialNodeBindingEntity>,
)

internal object KnowledgePackBudgets {
    const val MAX_NODES = 50_000
    const val MAX_SOURCES = 10_000
    const val MAX_NODE_SOURCE_BINDINGS = 250_000
    const val MAX_RELATIONS = 250_000
    const val MAX_SEARCH_FEATURES = 1_000_000
    const val MAX_MATERIALS = 100_000
    const val MAX_MATERIAL_BINDINGS = 500_000
    const val MAX_NODE_ALIASES = 12
    const val MAX_NODE_ALIAS_CHARS = 4_096
    const val MAX_NODE_ALIASES_TEXT_CHARS =
        MAX_NODE_ALIASES * MAX_NODE_ALIAS_CHARS + (MAX_NODE_ALIASES - 1)
    const val MAX_NODE_BOUNDARY_CHARS = 16_384
}

private val SEARCH_FEATURE_CANONICAL_COMPARATOR =
    compareBy(
        KnowledgeSearchFeatureEntity::subject,
        KnowledgeSearchFeatureEntity::searchFeature,
        KnowledgeSearchFeatureEntity::knowledgeNodeId,
    )

private fun <T> List<T>.snapshotWithinBudget(
    maximumSize: Int,
    label: String,
): List<T> {
    require(size <= maximumSize) { "Knowledge pack exceeds the $label budget" }
    return Collections.unmodifiableList(ArrayList(this))
}

private fun <T> List<T>.canonicalized(comparator: Comparator<in T>): List<T> {
    for (index in 1 until size) {
        if (comparator.compare(this[index - 1], this[index]) > 0) {
            return Collections.unmodifiableList(ArrayList(this).apply { sortWith(comparator) })
        }
    }
    return this
}

// Schema v1 defines one complete catalog, not independently activatable subject shards.
private val REQUIRED_HIGH_SCHOOL_SUBJECTS =
    enumValues<SubjectKind>().filterTo(mutableSetOf()) { it != SubjectKind.GENERAL }

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
        require(
            node.verificationStatus != KnowledgeNodeVerificationStatus.MODEL_CANDIDATE.name,
        ) {
            "Model-candidate knowledge nodes cannot enter a formal knowledge pack"
        }
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
        require(
            aliases.size <= KnowledgePackBudgets.MAX_NODE_ALIASES &&
                aliases.all {
                    it.length <= KnowledgePackBudgets.MAX_NODE_ALIAS_CHARS &&
                        it.isCatalogText()
                },
        ) {
            "Knowledge-node aliases are invalid"
        }
        require(aliases == aliases.sorted()) {
            "Knowledge-node aliases must use deterministic lexical order"
        }
        require(node.boundaryMarkdown == null || node.boundaryMarkdown.isCatalogText()) {
            "Knowledge-node boundary is invalid"
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
        val licenseStatus =
            enumValue<KnowledgeSourceLicenseStatus>(
                source.licenseStatus,
                "source license status",
            )
        val contentUsePolicy =
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
        require(
            licenseStatus != KnowledgeSourceLicenseStatus.REFERENCE_ONLY ||
                contentUsePolicy == KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY,
        ) {
            "Reference-only sources may only support reviewed synthesis"
        }
        if (
            licenseStatus == KnowledgeSourceLicenseStatus.LICENSED &&
            contentUsePolicy != KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY
        ) {
            require(!source.licenseExpression.isNullOrBlank()) {
                "Licensed excerpts and adaptations require a license expression"
            }
            require(!source.attributionText.isNullOrBlank()) {
                "Licensed excerpts and adaptations require attribution"
            }
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
    requireDistinctByComparator(
        compareBy(KnowledgeNodeRelationEntity::relationId),
        "relation id",
    )
    requireDistinctByComparator(
        compareBy(
            KnowledgeNodeRelationEntity::fromKnowledgeNodeId,
            KnowledgeNodeRelationEntity::toKnowledgeNodeId,
            KnowledgeNodeRelationEntity::relationType,
            KnowledgeNodeRelationEntity::taxonomyVersion,
        ),
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

private fun List<KnowledgeNodeRelationEntity>.validatePrerequisiteGraph(
    nodesById: Map<String, KnowledgeNodeEntity>,
) {
    val prerequisiteRelations = filter { it.relationType == PREREQUISITE_RELATION_TYPE }
    if (prerequisiteRelations.isEmpty()) return

    val incomingCount = nodesById.keys.associateWith { 0 }.toMutableMap()
    val dependentsByPrerequisite =
        prerequisiteRelations.groupBy(KnowledgeNodeRelationEntity::fromKnowledgeNodeId)
    prerequisiteRelations.forEach { relation ->
        incomingCount.compute(
            relation.toKnowledgeNodeId,
        ) { _, count -> requireNotNull(count) + 1 }
    }
    val ready = PriorityQueue<String>()
    incomingCount.filterValues { it == 0 }.keys.forEach(ready::add)
    var visited = 0
    while (ready.isNotEmpty()) {
        val prerequisite = ready.remove()
        visited += 1
        dependentsByPrerequisite[prerequisite].orEmpty().forEach { relation ->
            val dependent = relation.toKnowledgeNodeId
            val remaining = requireNotNull(incomingCount[dependent]) - 1
            incomingCount[dependent] = remaining
            if (remaining == 0) ready += dependent
        }
    }
    require(visited == nodesById.size) {
        "Knowledge prerequisite graph contains a cycle"
    }
}

private fun List<KnowledgeSearchFeatureEntity>.validateSearchFeatures(
    nodesById: Map<String, KnowledgeNodeEntity>,
) {
    var previous: KnowledgeSearchFeatureEntity? = null
    forEach { feature ->
        val prior = previous
        require(
            prior == null || SEARCH_FEATURE_CANONICAL_COMPARATOR.compare(prior, feature) < 0,
        ) {
            "Duplicate or non-canonical knowledge-search feature"
        }
        previous = feature
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
        require(feature.rankWeight in 1..MAX_SEARCH_RANK_WEIGHT) {
            "Search-feature rank weight is outside the local ranking contract"
        }
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
        val derivationKind =
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
        require(
            material.summaryMarkdown.length +
                material.applicabilityMarkdown.length +
                material.contentMarkdown.length +
                material.boundaryMarkdown.length <=
                HighSchoolKnowledgeCatalog.MAX_SINGLE_TEACHING_MATERIAL_MARKDOWN_CHARS,
        ) {
            "Teaching material exceeds the per-material Markdown budget"
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
        require(derivationKind.isAuthorizedBy(source)) {
            "Teaching-material derivation is not authorized by its reviewed source"
        }
    }
}

private fun KnowledgeMaterialDerivationKind.isAuthorizedBy(
    source: KnowledgeSourceEntity,
): Boolean {
    val licenseStatus =
        enumValue<KnowledgeSourceLicenseStatus>(
            source.licenseStatus,
            "source license status",
        )
    val contentUsePolicy =
        enumValue<KnowledgeSourceContentUsePolicy>(
            source.contentUsePolicy,
            "source content-use policy",
        )
    return when (this) {
        KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS -> true
        KnowledgeMaterialDerivationKind.PUBLIC_OFFICIAL_EXCERPT ->
            licenseStatus == KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL &&
                contentUsePolicy != KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY
        KnowledgeMaterialDerivationKind.LICENSED_EXCERPT ->
            licenseStatus == KnowledgeSourceLicenseStatus.LICENSED &&
                contentUsePolicy != KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY
        KnowledgeMaterialDerivationKind.LICENSED_ADAPTATION ->
            licenseStatus == KnowledgeSourceLicenseStatus.LICENSED &&
                contentUsePolicy == KnowledgeSourceContentUsePolicy.ADAPTATION_ALLOWED
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
    val keys = HashSet<K>(size)
    forEach { value ->
        require(keys.add(keySelector(value))) { "Duplicate $label" }
    }
}

private fun <T> List<T>.requireDistinctByComparator(
    comparator: Comparator<in T>,
    label: String,
) {
    val ordered = sortedWith(comparator)
    for (index in 1 until ordered.size) {
        require(comparator.compare(ordered[index - 1], ordered[index]) != 0) {
            "Duplicate $label"
        }
    }
}

private fun String.requireAliasEncodingWithinBudget() {
    require(length <= KnowledgePackBudgets.MAX_NODE_ALIASES_TEXT_CHARS) {
        "Knowledge-node aliases exceed their character budget"
    }
    if (isEmpty()) return

    var aliasCount = 1
    var aliasLength = 0
    forEach { character ->
        if (character == ALIAS_SEPARATOR.single()) {
            require(aliasLength > 0) { "Knowledge-node aliases are invalid" }
            aliasCount += 1
            require(aliasCount <= KnowledgePackBudgets.MAX_NODE_ALIASES) {
                "Knowledge-node aliases exceed their count budget"
            }
            aliasLength = 0
        } else {
            aliasLength += 1
            require(aliasLength <= KnowledgePackBudgets.MAX_NODE_ALIAS_CHARS) {
                "Knowledge-node alias exceeds its character budget"
            }
        }
    }
    require(aliasLength > 0) { "Knowledge-node aliases are invalid" }
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

private const val PREREQUISITE_RELATION_TYPE = "PREREQUISITE_OF"
private const val MAX_SEARCH_RANK_WEIGHT = 100
