package com.tingyun.smartmistakebook.core.data.knowledge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 取代映射台账：哪个知识点退役了、被谁取代。
 *
 * 由生成器侧 `tools/kb_build/update_manifest.py` 产出（`merge_points` / `delete_points`
 * 在执行的那一次就写），随包发布。**它消灭的失败**：改前内容侧的合并与删除做得很完整，
 * 但映射只活在人工 CSV 里、成品包一个字都不记——运行时看到的是"节点凭空消失了"，
 * 于是学生错题绑定/掌握度/复习队列无法解析到取代它的新节点，而安装器又因为
 * 逐行比对不等而整包拒绝。
 *
 * 形状校验与 Python 侧 `_require_shape` 一一对应：MERGE 必须有取代目标、DELETE 必须没有。
 * 写反了运行时会去解析一个不存在或不该存在的重定向。
 */
internal data class KnowledgeContentRetirement(
    val nodeId: String,
    /** 取代它的节点 id；null 表示**没有唯一目标**（删除，或拆分——源节点退役但有两个后继）。 */
    val supersededBy: String?,
    val kind: String,
    val reason: String,
)

internal data class KnowledgeUpdateManifest(
    val packId: String,
    val contentVersion: String,
    val retired: List<KnowledgeContentRetirement>,
) {
    /** nodeId -> supersededBy。调和用它给退役节点写重定向。 */
    val retirementByNodeId: Map<String, String?> = retired.associate { it.nodeId to it.supersededBy }
}

internal object ReviewedKnowledgeUpdateManifestJsonCodec {
    private const val SCHEMA_VERSION = 1L
    internal const val KIND_MERGE = "MERGE"
    internal const val KIND_DELETE = "DELETE"

    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun decode(rawJson: String): KnowledgeUpdateManifest {
        val root = json.parseToJsonElement(rawJson).jsonObjectOrNull()
            ?: error("Knowledge update manifest must be a JSON object")
        root.requireOnlyKeys("schemaVersion", "packId", "contentVersion", "retired")
        val schemaVersion = root.requiredLong("schemaVersion")
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported knowledge update manifest schema $schemaVersion"
        }
        val retired = root["retired"]?.jsonArray
            ?: error("Knowledge update manifest has no retired list")

        val entries = retired.map { element ->
            val entry = element.jsonObjectOrNull()
                ?: error("Knowledge update manifest retirement must be an object")
            entry.requireOnlyKeys("nodeId", "supersededBy", "kind", "reason")
            val nodeId = entry.requiredString("nodeId")
            val kind = entry.requiredString("kind")
            val supersededBy = entry["supersededBy"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.jsonPrimitive?.content
            val reason = entry["reason"]?.jsonPrimitive?.content.orEmpty()
            require(kind == KIND_MERGE || kind == KIND_DELETE) {
                "Unknown knowledge retirement kind '$kind' for $nodeId"
            }
            // 与生成器侧 _require_shape 同构：写反了运行时会去解析一个不存在或不该存在的重定向。
            require(kind != KIND_MERGE || !supersededBy.isNullOrBlank()) {
                "A merged knowledge point must name its successor: $nodeId"
            }
            require(kind != KIND_DELETE || supersededBy.isNullOrBlank()) {
                "A deleted knowledge point must not name a successor: $nodeId"
            }
            KnowledgeContentRetirement(
                nodeId = nodeId,
                supersededBy = supersededBy,
                kind = kind,
                reason = reason,
            )
        }
        require(entries.map(KnowledgeContentRetirement::nodeId).distinct().size == entries.size) {
            "Knowledge update manifest retires a node more than once"
        }

        return KnowledgeUpdateManifest(
            packId = root.requiredString("packId"),
            contentVersion = root.requiredString("contentVersion"),
            retired = entries,
        )
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    private fun JsonObject.requireOnlyKeys(vararg allowed: String) {
        val unexpected = keys - allowed.toSet()
        require(unexpected.isEmpty()) { "Unexpected knowledge manifest keys $unexpected" }
        val missing = allowed.toSet() - keys
        require(missing.isEmpty()) { "Missing knowledge manifest keys $missing" }
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.content ?: error("Knowledge update manifest has no '$key'")

    private fun JsonObject.requiredLong(key: String): Long =
        requiredString(key).toLongOrNull()
            ?: error("Knowledge update manifest '$key' is not a number")
}
