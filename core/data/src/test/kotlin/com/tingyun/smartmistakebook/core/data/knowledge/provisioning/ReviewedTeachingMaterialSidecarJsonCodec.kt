package com.tingyun.smartmistakebook.core.data.knowledge.provisioning

import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialFingerprint
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSourceSeedRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal data class ReviewedTeachingMaterialSidecar(
    val packId: String,
    val sources: List<KnowledgeSourceSeedRecord>,
    val materials: List<KnowledgeTeachingMaterialRecord>,
    val bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
)

/**
 * Strict teaching-support sidecar for a reviewed taxonomy pack.
 *
 * The schema intentionally represents explanations, method models, worked examples, complete
 * solutions, and derivations. A teaching record may contain a problem statement and its answer;
 * it still has no assessment, scheduling, scoring, or learning-evidence authority. Unknown
 * question-bank fields fail closed instead of silently changing the role of the knowledge corpus.
 */
internal object ReviewedTeachingMaterialSidecarJsonCodec {
    private const val LEGACY_SCHEMA_VERSION = 1L
    private const val CURRENT_SCHEMA_VERSION = 2L
    private val safeSlug = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun decode(rawJson: String, pack: KnowledgeBasePack): ReviewedTeachingMaterialSidecar {
        val root = json.parseToJsonElement(rawJson).requireObject("teaching-material sidecar")
        root.requireOnlyKeys("schemaVersion", "packId", "sources", "materials")
        val schemaVersion = root.requiredLong("schemaVersion")
        require(schemaVersion in setOf(LEGACY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)) {
            "Unsupported teaching-material sidecar schema $schemaVersion"
        }
        val packId = root.requiredString("packId")
        require(packId == pack.packId) {
            "Teaching-material sidecar does not target its bundled taxonomy pack"
        }
        val sources = root.requiredArray("sources").mapIndexed { index, element ->
            element.toSource(index, schemaVersion)
        }
        val availableSources = pack.sources + sources
        val materialsWithBindings = root.requiredArray("materials").mapIndexed { index, element ->
            element.toMaterial(pack, availableSources, index)
        }
        require(materialsWithBindings.isNotEmpty()) {
            "Teaching-material sidecar needs at least one material"
        }
        require(materialsWithBindings.map { it.first.stableCode }.distinct().size == materialsWithBindings.size) {
            "Teaching-material sidecar stable codes must be unique"
        }
        return ReviewedTeachingMaterialSidecar(
            packId = packId,
            sources = sources,
            materials = materialsWithBindings.map { it.first },
            bindings = materialsWithBindings.flatMap { it.second },
        ).also { sidecar ->
            pack.copy(
                teachingSources = sidecar.sources,
                teachingMaterials = sidecar.materials,
                teachingMaterialBindings = sidecar.bindings,
            ).validate()
        }
    }

    private fun JsonElement.toMaterial(
        pack: KnowledgeBasePack,
        availableSources: List<KnowledgeSourceSeedRecord>,
        index: Int,
    ): Pair<KnowledgeTeachingMaterialRecord, List<KnowledgeTeachingMaterialNodeBindingRecord>> {
        val value = requireObject("materials[$index]")
        value.requireOnlyKeys(
            "slug",
            "subject",
            "type",
            "title",
            "summaryMarkdown",
            "applicabilityMarkdown",
            "contentMarkdown",
            "boundaryMarkdown",
            "derivationKind",
            "sourceId",
            "sourceLocator",
            "reviewedAtEpochMillis",
            "bindings",
        )
        val slug = value.requiredString("slug")
        require(safeSlug.matches(slug)) { "Teaching-material slug is invalid" }
        val subject = value.requiredString("subject")
        val sourceId = value.requiredString("sourceId")
        require(availableSources.any { it.sourceId == sourceId && it.subject == subject }) {
            "Teaching-material source must belong to the targeted taxonomy pack and subject"
        }
        val materialType = value.requiredEnum<KnowledgeTeachingMaterialType>("type").name
        val derivationKind = value.requiredEnum<KnowledgeMaterialDerivationKind>("derivationKind").name
        val stableCode = "${pack.packId}:${subject.lowercase(Locale.ROOT)}:teaching:$slug"
        val materialId = "kb-material:${sha256(stableCode).take(40)}"
        val draft = KnowledgeTeachingMaterialRecord(
            materialId = materialId,
            stableCode = stableCode,
            subject = subject,
            materialType = materialType,
            title = value.requiredString("title"),
            summaryMarkdown = value.requiredString("summaryMarkdown"),
            applicabilityMarkdown = value.requiredString("applicabilityMarkdown"),
            contentMarkdown = value.requiredString("contentMarkdown"),
            boundaryMarkdown = value.requiredString("boundaryMarkdown"),
            derivationKind = derivationKind,
            sourceId = sourceId,
            sourceLocator = value.requiredString("sourceLocator"),
            contentFingerprint = "PENDING",
            reviewedAtEpochMillis = value.requiredLong("reviewedAtEpochMillis"),
        )
        val material = draft.copy(
            contentFingerprint = KnowledgeTeachingMaterialFingerprint.expected(draft),
        )
        val bindings = value.requiredArray("bindings").mapIndexed { bindingIndex, element ->
            val binding = element.requireObject("materials[$index].bindings[$bindingIndex]")
            binding.requireOnlyKeys("knowledgeNodeId", "role")
            KnowledgeTeachingMaterialNodeBindingRecord(
                materialId = materialId,
                knowledgeNodeId = binding.requiredString("knowledgeNodeId"),
                role = binding.requiredEnum<KnowledgeMaterialNodeRole>("role").name,
            )
        }
        return material to bindings
    }

    private fun JsonElement.toSource(
        index: Int,
        schemaVersion: Long,
    ): KnowledgeSourceSeedRecord {
        val value = requireObject("sources[$index]")
        val sharedKeys = arrayOf(
            "sourceId",
            "subject",
            "sourceType",
            "title",
            "publisher",
            "edition",
            "sourceUri",
            "licenseStatus",
            "contentFingerprint",
            "importedAtEpochMillis",
        )
        if (schemaVersion == LEGACY_SCHEMA_VERSION) {
            value.requireOnlyKeys(*sharedKeys)
        } else {
            value.requireOnlyKeys(
                *sharedKeys,
                "contentUsePolicy",
                "licenseExpression",
                "licenseUri",
                "attributionText",
            )
        }
        return KnowledgeSourceSeedRecord(
            sourceId = value.requiredString("sourceId"),
            subject = value.requiredString("subject"),
            sourceType = value.requiredEnum<KnowledgeSourceType>("sourceType").name,
            title = value.requiredString("title"),
            publisher = value.requiredNullableString("publisher"),
            edition = value.requiredNullableString("edition"),
            sourceUri = value.requiredNullableString("sourceUri"),
            licenseStatus = value.requiredEnum<KnowledgeSourceLicenseStatus>("licenseStatus").name,
            contentFingerprint = value.requiredString("contentFingerprint"),
            importedAtEpochMillis = value.requiredLong("importedAtEpochMillis"),
            contentUsePolicy = if (schemaVersion == LEGACY_SCHEMA_VERSION) {
                KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY.name
            } else {
                value.requiredEnum<KnowledgeSourceContentUsePolicy>("contentUsePolicy").name
            },
            licenseExpression = value.schemaNullableString(
                "licenseExpression",
                schemaVersion,
            ),
            licenseUri = value.schemaNullableString("licenseUri", schemaVersion),
            attributionText = value.schemaNullableString(
                "attributionText",
                schemaVersion,
            ),
        )
    }

    private fun JsonObject.schemaNullableString(
        name: String,
        schemaVersion: Long,
    ): String? = if (schemaVersion == LEGACY_SCHEMA_VERSION) {
        null
    } else {
        requiredNullableString(name)
    }

    private fun JsonElement.requireObject(label: String): JsonObject =
        runCatching { jsonObject }.getOrElse { error("$label must be a JSON object") }

    private fun JsonObject.requireOnlyKeys(vararg allowed: String) {
        val allowedKeys = allowed.toSet()
        require(keys.all(allowedKeys::contains)) {
            "Teaching-material sidecar contains unknown keys: ${(keys - allowedKeys).sorted()}"
        }
        require(allowedKeys.all(::containsKey)) {
            "Teaching-material sidecar is missing keys: ${(allowedKeys - keys).sorted()}"
        }
    }

    private fun JsonObject.requiredString(name: String): String {
        val primitive = get(name) as? JsonPrimitive
            ?: error("Teaching-material field $name must be a string")
        require(primitive.isString) { "Teaching-material field $name must be a string" }
        return primitive.content.also {
            require(it.isNotBlank() && it == it.trim()) {
                "Teaching-material field $name must be a trimmed non-blank string"
            }
        }
    }

    private fun JsonObject.requiredNullableString(name: String): String? {
        val value = get(name)
            ?: error("Teaching-material field $name is required")
        if (value is JsonNull) return null
        return requiredString(name)
    }

    private fun JsonObject.requiredLong(name: String): Long {
        val primitive = get(name) as? JsonPrimitive
            ?: error("Teaching-material field $name must be an integer")
        require(!primitive.isString && primitive.booleanOrNull == null) {
            "Teaching-material field $name must be an integer"
        }
        return primitive.longOrNull
            ?: error("Teaching-material field $name must be an integer")
    }

    private fun JsonObject.requiredArray(name: String): JsonArray =
        get(name) as? JsonArray ?: error("Teaching-material field $name must be an array")

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T {
        val value = requiredString(name)
        return enumValues<T>().singleOrNull { it.name == value }
            ?: error("Teaching-material field $name has unknown value '$value'")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
