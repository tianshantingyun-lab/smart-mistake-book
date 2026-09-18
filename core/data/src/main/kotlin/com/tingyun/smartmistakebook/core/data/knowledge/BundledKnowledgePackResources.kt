package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationContract
import com.tingyun.smartmistakebook.core.database.KnowledgeBaseImportContract
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSourceBindingSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSourceSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialContract
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class KnowledgeBasePack(
    val packId: String,
    val coverage: KnowledgePackCoverage,
    val sources: List<KnowledgeSourceSeedRecord>,
    val nodes: List<KnowledgeNodeSeedRecord>,
    val bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    val relations: List<KnowledgeNodeRelationRecord>,
    val teachingSources: List<KnowledgeSourceSeedRecord> = emptyList(),
    val teachingMaterials: List<KnowledgeTeachingMaterialRecord> = emptyList(),
    val teachingMaterialBindings: List<KnowledgeTeachingMaterialNodeBindingRecord> = emptyList(),
)

internal object BundledKnowledgePackResources {
    private const val TEACHING_SIDECAR_INDEX = "knowledge/moe-2025-teaching-support-v2-index.json"
    private const val INDEXED_PACK_ID = "moe-2025-four-subjects-v1"
    private val resourceNames = listOf(
        "knowledge/moe-2020-foundation-v1.json",
        "knowledge/moe-2025-four-subjects-v1.json",
    )
    private val teachingSidecarsByPack = mapOf(
        "moe-2020-foundation-v1" to listOf(
            "knowledge/moe-2020-teaching-support-v1.json",
        ),
    )
    private val indexJson = Json { ignoreUnknownKeys = false }
    // 四科包的教学材料卷数不再硬编码：开新卷只加文件 + 改索引数据，不动代码。
    private val indexedSidecarNames: List<String> by lazy {
        val root = indexJson.parseToJsonElement(readClasspathResource(TEACHING_SIDECAR_INDEX)).jsonObject
        require(root["packId"]?.jsonPrimitive?.contentOrNull == INDEXED_PACK_ID) {
            "Teaching sidecar index points at pack ${root["packId"]}, expected $INDEXED_PACK_ID"
        }
        root["sidecars"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: error("Teaching sidecar index has no sidecars list")
    }
    private val bundledPacks by lazy {
        resourceNames.map(::loadResource)
    }

    fun load(): List<KnowledgeBasePack> = bundledPacks

    private fun loadResource(resourceName: String): KnowledgeBasePack {
        // 容量不设上限（2026-09-19 用户决定）：资源大小不再设门，装不下由侧车滚动机制解决。
        val json = readClasspathResource(resourceName)
        val base = ReviewedKnowledgePackJsonCodec.decode(json)
        val sidecarNames = teachingSidecarsByPack[base.packId]
            ?: if (base.packId == INDEXED_PACK_ID) indexedSidecarNames else emptyList()
        val sidecars = sidecarNames.map { sidecarName ->
            val sidecarJson = readClasspathResource(sidecarName)
            ReviewedTeachingMaterialSidecarJsonCodec.decode(sidecarJson, base)
        }
        val allMaterials = sidecars.flatMap(ReviewedTeachingMaterialSidecar::materials)
        val boundMaterialIds = sidecars.flatMap(ReviewedTeachingMaterialSidecar::bindings)
            .mapTo(hashSetOf(), KnowledgeTeachingMaterialNodeBindingRecord::materialId)
        // DB 契约要求每个教学材料至少绑定一个原子知识点；sidecar 里未绑定的条目是提取残渣，
        // 无法归因，不能导入。在聚合层统一剔除，保证 pack、幂等比对与契约一致。
        val importableMaterials = allMaterials.filter { it.materialId in boundMaterialIds }
        return base.copy(
            teachingSources = sidecars.flatMap(ReviewedTeachingMaterialSidecar::sources)
                .distinctBy(KnowledgeSourceSeedRecord::sourceId),
            teachingMaterials = importableMaterials,
            teachingMaterialBindings = sidecars.flatMap(ReviewedTeachingMaterialSidecar::bindings),
        ).also(KnowledgeBasePack::validate)
    }

    private fun readClasspathResource(resourceName: String): String {
        val stream = BundledKnowledgePackResources::class.java.classLoader
            ?.getResourceAsStream(resourceName)
            ?: error("Missing bundled knowledge pack $resourceName")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

internal object ReviewedKnowledgePackJsonCodec {
    private const val LEGACY_SCHEMA_VERSION = 1L
    private const val CURRENT_SCHEMA_VERSION = 2L
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun decode(rawJson: String): KnowledgeBasePack {
        val root = json.parseToJsonElement(rawJson).requireObject("knowledge pack")
        return when (val schemaVersion = root.requiredLong("schemaVersion")) {
            LEGACY_SCHEMA_VERSION -> decodeLegacy(root)
            CURRENT_SCHEMA_VERSION -> decodeCurrent(root)
            else -> error("Unsupported knowledge-pack schema $schemaVersion")
        }
    }

    private fun decodeLegacy(root: JsonObject): KnowledgeBasePack {
        root.requireOnlyKeys(
            "schemaVersion",
            "packId",
            "taxonomyVersion",
            "sourceNamespace",
            "reviewedAtEpochMillis",
            "sourceUri",
            "subjects",
        )
        return decodeShared(
            root = root,
            coverage = KnowledgePackCoverage.historical2020Sample(),
            subjectDecoder = JsonElement::toLegacySubjectBlueprint,
        )
    }

    private fun decodeCurrent(root: JsonObject): KnowledgeBasePack {
        root.requireOnlyKeys(
            "schemaVersion",
            "packId",
            "taxonomyVersion",
            "sourceNamespace",
            "reviewedAtEpochMillis",
            "sourceUri",
            "coverage",
            "subjects",
        )
        return decodeShared(
            root = root,
            coverage = root.requiredCoverage(),
            subjectDecoder = JsonElement::toCurrentSubjectBlueprint,
        )
    }

    private fun decodeShared(
        root: JsonObject,
        coverage: KnowledgePackCoverage,
        subjectDecoder: (JsonElement, Int) -> SubjectBlueprint,
    ): KnowledgeBasePack {
        val packId = root.requiredString("packId")
        val taxonomyVersion = root.requiredString("taxonomyVersion")
        require(packId == taxonomyVersion) {
            "Knowledge pack id and taxonomy version must match"
        }
        val sourceNamespace = root.requiredString("sourceNamespace")
        val reviewedAt = root.requiredLong("reviewedAtEpochMillis")
        require(reviewedAt > 0) { "Knowledge-pack review time must be positive" }
        val sourceUri = root.requiredString("sourceUri")
        val blueprints = root.requiredArray("subjects").mapIndexed { index, element ->
            subjectDecoder(element, index)
        }
        require(blueprints.isNotEmpty()) { "Knowledge pack needs at least one subject" }
        require(blueprints.map(SubjectBlueprint::subject).distinct().size == blueprints.size) {
            "Knowledge-pack subjects must be unique"
        }

        val sources = blueprints.map {
            it.toSource(sourceNamespace, reviewedAt, sourceUri, coverage)
        }
        val nodes = blueprints.flatMap {
            it.toNodes(taxonomyVersion, reviewedAt)
        }
        val bindings = blueprints.flatMap {
            it.toBindings(taxonomyVersion, sourceNamespace, reviewedAt)
        }
        val relations = blueprints.flatMap {
            it.toRelations(taxonomyVersion, sourceNamespace, reviewedAt)
        }
        return KnowledgeBasePack(
            packId = packId,
            coverage = coverage,
            sources = sources,
            nodes = nodes,
            bindings = bindings,
            relations = relations,
        ).also {
            it.validate()
        }
    }
}

private data class SubjectBlueprint(
    val subject: SubjectKind,
    val sourceFingerprint: String,
    val topics: List<TopicBlueprint>,
)

private data class TopicBlueprint(
    val topicSlug: String,
    val topicName: String,
    val topicLocator: String,
    val parentSlug: String? = null,
    val points: List<PointBlueprint>,
)

private data class PointBlueprint(
    val slug: String,
    val name: String,
    val aliases: Set<String>,
    val kind: KnowledgeNodeKind,
    val boundary: String,
    val sourceLocator: String,
    val prerequisiteSlugs: Set<String>,
)

private fun JsonElement.toLegacySubjectBlueprint(index: Int): SubjectBlueprint {
    val value = requireObject("subjects[$index]")
    value.requireOnlyKeys(
        "subject",
        "sourceFingerprint",
        "topicSlug",
        "topicName",
        "topicLocator",
        "knowledgePoints",
    )
    val subject = value.requiredEnum<SubjectKind>("subject")
    require(subject != SubjectKind.GENERAL) { "A curriculum pack cannot use GENERAL subject" }
    val points = value.requiredArray("knowledgePoints").mapIndexed { pointIndex, element ->
        element.toPointBlueprint("subjects[$index].knowledgePoints[$pointIndex]")
    }
    require(points.isNotEmpty()) { "Every subject needs at least one knowledge point" }
    require(points.map(PointBlueprint::slug).distinct().size == points.size) {
        "Knowledge-point slugs must be unique inside a subject"
    }
    return SubjectBlueprint(
        subject = subject,
        sourceFingerprint = value.requiredString("sourceFingerprint"),
        topics = listOf(
            TopicBlueprint(
                topicSlug = value.requiredString("topicSlug"),
                topicName = value.requiredString("topicName"),
                topicLocator = value.requiredString("topicLocator"),
                points = points,
            ),
        ),
    ).also(SubjectBlueprint::validate)
}

private fun JsonElement.toCurrentSubjectBlueprint(index: Int): SubjectBlueprint {
    val value = requireObject("subjects[$index]")
    value.requireOnlyKeys("subject", "sourceFingerprint", "topics")
    val subject = value.requiredEnum<SubjectKind>("subject")
    require(subject != SubjectKind.GENERAL) { "A curriculum pack cannot use GENERAL subject" }
    val topics = value.requiredArray("topics").mapIndexed { topicIndex, element ->
        val topic = element.requireObject("subjects[$index].topics[$topicIndex]")
        // parentSlug 是可选键，不能进 requireOnlyKeys（那里同时要求键必填）
        val unknownTopicKeys = topic.keys -
            setOf("slug", "name", "sourceLocator", "parentSlug", "knowledgePoints")
        require(unknownTopicKeys.isEmpty()) {
            "Knowledge pack contains unknown keys: ${unknownTopicKeys.sorted()}"
        }
        val parentSlug = topic["parentSlug"]?.let { p ->
            (p as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: error("subjects[$index].topics[$topicIndex].parentSlug must be a string")
        }
        val points = topic.requiredArray("knowledgePoints").mapIndexed { pointIndex, point ->
            point.toPointBlueprint("subjects[$index].topics[$topicIndex].knowledgePoints[$pointIndex]")
        }
        TopicBlueprint(
            topicSlug = topic.requiredString("slug"),
            topicName = topic.requiredString("name"),
            topicLocator = topic.requiredString("sourceLocator"),
            parentSlug = parentSlug,
            points = points,
        )
    }
    return SubjectBlueprint(
        subject = subject,
        sourceFingerprint = value.requiredString("sourceFingerprint"),
        topics = topics,
    ).also(SubjectBlueprint::validate)
}

private fun SubjectBlueprint.validate() {
    require(topics.isNotEmpty()) { "Every subject needs at least one topic" }
    require(topics.map(TopicBlueprint::topicSlug).distinct().size == topics.size) {
        "Topic slugs must be unique inside a subject"
    }
    val points = topics.flatMap(TopicBlueprint::points)
    require(points.isNotEmpty()) { "Every subject needs at least one knowledge point" }
    require(points.map(PointBlueprint::slug).distinct().size == points.size) {
        "Knowledge-point slugs must be unique inside a subject"
    }
    val slugs = points.mapTo(hashSetOf(), PointBlueprint::slug)
    require(points.all { point -> point.prerequisiteSlugs.all(slugs::contains) }) {
        "Knowledge-point prerequisites must reference the same subject block"
    }
    // 嵌套 topic 校验：parentSlug 必须引用同科存在的 topic，且无环
    val topicSlugs = topics.mapTo(hashSetOf(), TopicBlueprint::topicSlug)
    require(topics.all { it.parentSlug == null || it.parentSlug in topicSlugs }) {
        "Nested topic parentSlug must reference a topic in the same subject"
    }
    val parentBySlug = topics.associate { it.topicSlug to it.parentSlug }
    topics.forEach { topic ->
        var seen = mutableSetOf(topic.topicSlug)
        var cursor: String? = topic.parentSlug
        while (cursor != null) {
            require(cursor !in seen) { "Topic nesting must not contain cycles" }
            seen.add(cursor)
            cursor = parentBySlug[cursor]
        }
    }
}

private fun JsonElement.toPointBlueprint(label: String): PointBlueprint {
    val value = requireObject(label)
    value.requireOnlyKeys(
        "slug",
        "name",
        "aliases",
        "kind",
        "boundary",
        "sourceLocator",
        "prerequisiteSlugs",
    )
    return PointBlueprint(
        slug = value.requiredString("slug"),
        name = value.requiredString("name"),
        aliases = value.requiredStringSet("aliases"),
        kind = value.requiredEnum("kind"),
        boundary = value.requiredString("boundary"),
        sourceLocator = value.requiredString("sourceLocator"),
        prerequisiteSlugs = value.requiredStringSet("prerequisiteSlugs"),
    )
}

private fun SubjectBlueprint.toSource(
    sourceNamespace: String,
    reviewedAt: Long,
    sourceUri: String,
    coverage: KnowledgePackCoverage,
) = KnowledgeSourceSeedRecord(
    sourceId = sourceId(sourceNamespace),
    subject = subject.name,
    sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD.name,
    title = "普通高中${subject.standardName()}课程标准（${coverage.standardEdition()}）",
    publisher = "中华人民共和国教育部",
    edition = coverage.standardEdition(),
    sourceUri = sourceUri,
    licenseStatus = KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL.name,
    contentFingerprint = sourceFingerprint,
    importedAtEpochMillis = reviewedAt,
)

private fun SubjectBlueprint.toNodes(
    taxonomyVersion: String,
    reviewedAt: Long,
): List<KnowledgeNodeSeedRecord> = topics.flatMap { topic ->
    val topicNodeId = topicId(taxonomyVersion, topic.topicSlug)
    listOf(
        KnowledgeNodeSeedRecord(
            knowledgeNodeId = topicNodeId,
            stableCode = "$taxonomyVersion:${subjectKey()}:topic:${topic.topicSlug}",
            subject = subject.name,
            displayName = topic.topicName,
            parentKnowledgeNodeId = topic.parentSlug?.let { parentSlug ->
                topicId(taxonomyVersion, parentSlug)
            },
            taxonomyVersion = taxonomyVersion,
            createdAtEpochMillis = reviewedAt,
            canonicalName = topic.topicName,
            nodeKind = KnowledgeNodeKind.TOPIC.name,
            granularity = KnowledgeNodeGranularity.TOPIC.name,
            verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
        ),
    ) + topic.points.map { point ->
        KnowledgeNodeSeedRecord(
            knowledgeNodeId = pointId(taxonomyVersion, point.slug),
            stableCode = "$taxonomyVersion:${subjectKey()}:atomic:${point.slug}",
            subject = subject.name,
            displayName = point.name,
            parentKnowledgeNodeId = topicNodeId,
            taxonomyVersion = taxonomyVersion,
            createdAtEpochMillis = reviewedAt,
            canonicalName = point.name,
            nodeKind = point.kind.name,
            granularity = KnowledgeNodeGranularity.ATOMIC.name,
            aliases = point.aliases,
            boundaryMarkdown = point.boundary,
            verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
        )
    }
}

private fun SubjectBlueprint.toBindings(
    taxonomyVersion: String,
    sourceNamespace: String,
    reviewedAt: Long,
): List<KnowledgeNodeSourceBindingSeedRecord> = topics.flatMap { topic ->
    listOf(
        KnowledgeNodeSourceBindingSeedRecord(
            knowledgeNodeId = topicId(taxonomyVersion, topic.topicSlug),
            sourceId = sourceId(sourceNamespace),
            sourceLocator = topic.topicLocator,
            derivationNote = "课程标准中的内容主题，仅作为可见分类层级，不作为学生能力证据。",
            reviewedAtEpochMillis = reviewedAt,
        ),
    ) + topic.points.map { point ->
        KnowledgeNodeSourceBindingSeedRecord(
            knowledgeNodeId = pointId(taxonomyVersion, point.slug),
            sourceId = sourceId(sourceNamespace),
            sourceLocator = point.sourceLocator,
            derivationNote = "从课程标准的内容要求或学业质量描述拆成可观察、可跨题复用的最小能力；不含题目正文。",
            reviewedAtEpochMillis = reviewedAt,
        )
    }
}

private fun SubjectBlueprint.toRelations(
    taxonomyVersion: String,
    sourceNamespace: String,
    reviewedAt: Long,
): List<KnowledgeNodeRelationRecord> = topics.flatMap(TopicBlueprint::points).flatMap { point ->
    point.prerequisiteSlugs.map { prerequisiteSlug ->
        val draft = KnowledgeNodeRelationRecord(
            relationId = "pending",
            subject = subject.name,
            prerequisiteKnowledgeNodeId = pointId(taxonomyVersion, prerequisiteSlug),
            dependentKnowledgeNodeId = pointId(taxonomyVersion, point.slug),
            relationType = StudyDbValue.KnowledgeRelationType.PREREQUISITE_OF,
            sourceId = sourceId(sourceNamespace),
            sourceLocator = point.sourceLocator,
            reviewedAtEpochMillis = reviewedAt,
        )
        draft.copy(relationId = KnowledgeNodeRelationContract.expectedId(draft))
    }
}

internal fun KnowledgeBasePack.validate() {
    val allSources = sources + teachingSources
    val sourcesById = allSources.associateBy(KnowledgeSourceSeedRecord::sourceId)
    val nodesById = nodes.associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
    require(sourcesById.size == allSources.size) { "Knowledge sources must have unique ids" }
    require(allSources.map(KnowledgeSourceSeedRecord::contentFingerprint).distinct().size == allSources.size) {
        "Knowledge sources must have unique content fingerprints"
    }
    require(nodesById.size == nodes.size) { "Knowledge nodes must have unique ids" }
    require(allSources.all { it.contentFingerprint.matches(Regex("[0-9A-F]{64}")) }) {
        "Every bundled source needs an uppercase SHA-256 fingerprint"
    }
    require(bindings.map {
        Triple(it.knowledgeNodeId, it.sourceId, it.sourceLocator)
    }.distinct().size == bindings.size) {
        "Knowledge provenance bindings must be unique"
    }
    require(bindings.all { binding ->
        val node = nodesById[binding.knowledgeNodeId]
        val source = sourcesById[binding.sourceId]
        node != null && source != null && node.subject == source.subject
    }) { "Every provenance binding must remain inside one subject" }
    require(nodes.all { node ->
        if (node.granularity != KnowledgeNodeGranularity.ATOMIC.name) return@all true
        val parent = node.parentKnowledgeNodeId?.let(nodesById::get)
        parent?.subject == node.subject &&
            parent.granularity == KnowledgeNodeGranularity.TOPIC.name &&
            node.verificationStatus == KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name
    }) { "Every fine-grained ability needs a grounded same-subject topic parent" }
    KnowledgeNodeRelationContract.validate(
        incoming = relations,
        nodes = nodes,
        sources = sources,
        existing = emptyList(),
    )
    // Each teaching sidecar is validated on decode (per sidecar <= 2048 materials and binding
    // count range hold there). The aggregate validate therefore only checks cross-sidecar
    // uniqueness and that every binding points at an existing node/source of the same subject.
    require(
        teachingMaterials.all {
            it.contentFingerprint != "PENDING"
        },
    ) { "Every bundled teaching material needs a content fingerprint" }
    require(
        teachingMaterials.map(KnowledgeTeachingMaterialRecord::stableCode).distinct().size ==
            teachingMaterials.size,
    ) { "Teaching-material stable codes must be unique across sidecars" }
    val materialMaterialById = teachingMaterials.associateBy(KnowledgeTeachingMaterialRecord::materialId)
    require(materialMaterialById.size == teachingMaterials.size) {
        "Teaching-material ids must be unique across sidecars"
    }
    require(teachingMaterialBindings.all { binding ->
        val material = materialMaterialById[binding.materialId]
        val node = nodesById[binding.knowledgeNodeId]
        material != null && node != null && material.subject == node.subject
    }) { "Every teaching-material binding must reference an existing same-subject node" }
    KnowledgeBaseImportContract.validateSourcesOnly(teachingSources)
    KnowledgeCoverageContract.validate(
        coverage = coverage,
        nodes = nodes,
        sources = sources,
        teachingMaterials = teachingMaterials,
        teachingMaterialBindings = teachingMaterialBindings,
    )
}

private fun SubjectBlueprint.subjectKey() = subject.name.lowercase(Locale.ROOT)
private fun SubjectBlueprint.sourceId(sourceNamespace: String) =
    "source:$sourceNamespace:${subjectKey()}"

private fun SubjectBlueprint.topicId(taxonomyVersion: String, topicSlug: String) =
    "kb:$taxonomyVersion:${subjectKey()}:topic:$topicSlug"

private fun SubjectBlueprint.pointId(taxonomyVersion: String, slug: String) =
    "kb:$taxonomyVersion:${subjectKey()}:atomic:$slug"

private fun KnowledgePackCoverage.standardEdition(): String = when (baselineId) {
    KnowledgeCoverageContract.HISTORICAL_2020_BASELINE_ID -> "2017年版2020年修订"
    KnowledgeCoverageContract.CURRENT_BASELINE_ID -> "日常修订版（2017年版2025年修订）"
    else -> error("Unsupported knowledge coverage baseline $baselineId")
}

private fun SubjectKind.standardName(): String = when (this) {
    SubjectKind.CHINESE -> "语文"
    SubjectKind.MATH -> "数学"
    SubjectKind.ENGLISH -> "英语"
    SubjectKind.PHYSICS -> "物理"
    SubjectKind.CHEMISTRY -> "化学"
    SubjectKind.BIOLOGY -> "生物学"
    SubjectKind.POLITICS -> "思想政治"
    SubjectKind.HISTORY -> "历史"
    SubjectKind.GEOGRAPHY -> "地理"
    SubjectKind.GENERAL -> error("A curriculum pack cannot use a general subject")
}

private fun JsonElement.requireObject(label: String): JsonObject =
    runCatching { jsonObject }.getOrElse { error("$label must be a JSON object") }

private fun JsonObject.requireOnlyKeys(vararg allowed: String) {
    val allowedKeys = allowed.toSet()
    require(keys.all(allowedKeys::contains)) {
        "Knowledge pack contains unknown keys: ${(keys - allowedKeys).sorted()}"
    }
    require(allowedKeys.all(::containsKey)) {
        "Knowledge pack is missing keys: ${(allowedKeys - keys).sorted()}"
    }
}

private fun JsonObject.requiredCoverage(): KnowledgePackCoverage {
    val value = get("coverage")?.requireObject("coverage")
        ?: error("Knowledge-pack field coverage must be an object")
    value.requireOnlyKeys(
        "baselineId",
        "catalogLevel",
        "teachingSupportLevel",
    )
    val catalogLevel = value.requiredEnum<KnowledgeCoverageLevel>("catalogLevel")
    val teachingSupportLevel = value.requiredEnum<KnowledgeCoverageLevel>("teachingSupportLevel")
    require(
        catalogLevel != KnowledgeCoverageLevel.HISTORICAL_SAMPLE &&
            teachingSupportLevel != KnowledgeCoverageLevel.HISTORICAL_SAMPLE,
    ) {
        "Current-schema packs cannot declare historical sample coverage"
    }
    return KnowledgePackCoverage(
        baselineId = value.requiredString("baselineId"),
        catalogLevel = catalogLevel,
        teachingSupportLevel = teachingSupportLevel,
    )
}

private fun JsonObject.requiredString(name: String): String {
    val primitive = get(name) as? JsonPrimitive
        ?: error("Knowledge-pack field $name must be a string")
    require(primitive.isString) { "Knowledge-pack field $name must be a string" }
    return primitive.content.also {
        require(it.isNotBlank() && it == it.trim()) {
            "Knowledge-pack field $name must be a trimmed non-blank string"
        }
    }
}

private fun JsonObject.requiredLong(name: String): Long {
    val primitive = get(name) as? JsonPrimitive
        ?: error("Knowledge-pack field $name must be an integer")
    require(!primitive.isString && primitive.booleanOrNull == null) {
        "Knowledge-pack field $name must be an integer"
    }
    return primitive.longOrNull ?: error("Knowledge-pack field $name must be an integer")
}

private fun JsonObject.requiredArray(name: String): JsonArray =
    get(name) as? JsonArray ?: error("Knowledge-pack field $name must be an array")

private fun JsonObject.requiredStringSet(name: String): Set<String> {
    val element = get(name) ?: error("Knowledge-pack field $name must be an array")
    val array = element as? JsonArray ?: error("Knowledge-pack field $name must be an array")
    val values = array.map { item ->
        val primitive = item as? JsonPrimitive
            ?: error("Knowledge-pack field $name must contain strings")
        primitive.contentOrNull?.takeIf { primitive.isString }
            ?: error("Knowledge-pack field $name must contain strings")
    }
    require(values.distinct().size == values.size) {
        "Knowledge-pack field $name must not contain duplicates"
    }
    return values.toSet()
}

private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T {
    val value = requiredString(name)
    return enumValues<T>().singleOrNull { it.name == value }
        ?: error("Knowledge-pack field $name has unknown value '$value'")
}
