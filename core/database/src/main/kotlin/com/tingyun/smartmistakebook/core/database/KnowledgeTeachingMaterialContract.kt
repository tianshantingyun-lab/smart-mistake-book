package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Import boundary between the reviewed knowledge corpus and durable teaching context.
 *
 * Worked examples, complete solutions, and derivations are valid teaching content. What this
 * boundary excludes is assessment behavior: materials have no practice-unit identity, answer key,
 * score, difficulty, or scheduling metadata.
 */
object KnowledgeTeachingMaterialContract {
    // 容量不设总量上限（用户 2026-09-19 决定）：材料数/绑定数/总字符数都不再设门。
    // 只保留每条材料自身的结构约束（绑定数 1..16、恰好一个 PRIMARY）。
    private const val MAX_BINDINGS_PER_MATERIAL = 16
    private val uppercaseSha256 = Regex("[A-F0-9]{64}")
    private val materialTypes = enumValues<KnowledgeTeachingMaterialType>().mapTo(hashSetOf()) {
        it.name
    }
    private val derivationKinds = enumValues<KnowledgeMaterialDerivationKind>().mapTo(hashSetOf()) {
        it.name
    }
    private val bindingRoles = enumValues<KnowledgeMaterialNodeRole>().mapTo(hashSetOf()) {
        it.name
    }

    fun validate(
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
    ) {
        if (materials.isEmpty() && bindings.isEmpty()) return
        requireValid(materials.isNotEmpty()) {
            "Knowledge teaching-material bindings require imported materials"
        }
        // 不设总量上限（用户 2026-09-19 决定：知识库容量不设限，越全越好）。
        // 只保留结构不变量：每条材料至少一个绑定，故 bindings 数不少于 materials 数。
        requireValid(bindings.size >= materials.size) {
            "Knowledge teaching-material binding count is invalid"
        }
        requireValid(materials.map(KnowledgeTeachingMaterialRecord::materialId).distinct().size == materials.size) {
            "Knowledge teaching-material ids must be unique"
        }
        requireValid(materials.map(KnowledgeTeachingMaterialRecord::stableCode).distinct().size == materials.size) {
            "Knowledge teaching-material stable codes must be unique"
        }
        requireValid(
            materials.map(KnowledgeTeachingMaterialRecord::contentFingerprint).distinct().size ==
                materials.size,
        ) { "Knowledge teaching-material content fingerprints must be unique" }
        requireValid(
            bindings.map {
                it.materialId to it.knowledgeNodeId
            }.distinct().size == bindings.size,
        ) { "A teaching material may bind to a knowledge point only once" }

        val materialsById = materials.associateBy(KnowledgeTeachingMaterialRecord::materialId)
        val nodesById = nodes.associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
        val sourcesById = sources.associateBy(KnowledgeSourceSeedRecord::sourceId)
        val bindingsByMaterial = bindings.groupBy(
            KnowledgeTeachingMaterialNodeBindingRecord::materialId,
        )

        materials.forEach { material ->
            id(material.materialId, "materialId")
            id(material.stableCode, "stableCode")
            known(material.materialType, "materialType", materialTypes)
            known(material.derivationKind, "derivationKind", derivationKinds)
            text(material.subject, "material.subject", 32)
            text(material.title, "material.title", 240)
            text(material.summaryMarkdown, "material.summaryMarkdown", 4_000)
            text(material.applicabilityMarkdown, "material.applicabilityMarkdown", 8_000)
            text(material.contentMarkdown, "material.contentMarkdown", 32_000)
            text(material.boundaryMarkdown, "material.boundaryMarkdown", 8_000)
            text(material.sourceLocator, "material.sourceLocator", 2_000)
            requireValid(uppercaseSha256.matches(material.contentFingerprint)) {
                "material.contentFingerprint must be an uppercase SHA-256 digest"
            }
            requireValid(
                material.contentFingerprint == KnowledgeTeachingMaterialFingerprint.expected(material),
            ) { "material.contentFingerprint does not match the reviewed material payload" }
            requireValid(material.reviewedAtEpochMillis > 0) {
                "material.reviewedAtEpochMillis must be positive"
            }
            val source = sourcesById[material.sourceId]
                ?: invalid("Every teaching material needs a reviewed source")
            requireValid(source.subject == material.subject) {
                "Teaching material and source must belong to the same subject"
            }
            requireValid(material.reviewedAtEpochMillis >= source.importedAtEpochMillis) {
                "Teaching-material review cannot predate its source import"
            }
            validateDerivationLicense(material, source)

            val materialBindings = bindingsByMaterial[material.materialId].orEmpty()
            requireValid(materialBindings.size in 1..MAX_BINDINGS_PER_MATERIAL) {
                "Every teaching material needs a bounded set of knowledge-point bindings"
            }
            requireValid(materialBindings.count { it.role == KnowledgeMaterialNodeRole.PRIMARY.name } == 1) {
                "Every teaching material needs exactly one primary knowledge point"
            }
        }
        // 总量字符预算同样取消（2026-09-19：容量不设限）。

        bindings.forEach { binding ->
            val material = materialsById[binding.materialId]
                ?: invalid("Teaching-material binding references an unknown material")
            val node = nodesById[binding.knowledgeNodeId]
                ?: invalid("Teaching-material binding references an unknown knowledge point")
            known(binding.role, "binding.role", bindingRoles)
            requireValid(
                node.subject == material.subject &&
                    node.granularity == KnowledgeNodeGranularity.ATOMIC.name &&
                    node.verificationStatus == KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
            ) {
                "Teaching materials may bind only to reviewed fine-grained knowledge in one subject"
            }
            requireValid(material.reviewedAtEpochMillis >= node.createdAtEpochMillis) {
                "Teaching-material review cannot predate its knowledge point"
            }
        }
    }

    /**
     * **逐条**校验一条材料：通过返回 null，不通过返回原因。
     *
     * 调和循环用它实现"坏对象只跳过自己"。整批校验是全有或全无的——KD-15 就是
     * 80 条材料的 `reviewedAt` 早于来源 `importedAt` 几毫秒，把整个材料集挡在门外、
     * 每次启动弹横幅。
     *
     * 复用 [validate] 本身（只传这一条材料）而不是另写规则：两套规则迟早漂移。
     */
    fun problemWith(
        material: KnowledgeTeachingMaterialRecord,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
    ): String? = runCatching {
        validate(listOf(material), bindings, nodes, sources)
    }.exceptionOrNull()?.message

    private fun validateDerivationLicense(
        material: KnowledgeTeachingMaterialRecord,
        source: KnowledgeSourceSeedRecord,
    ) {
        val allowed = mutableSetOf(KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS.name)
        when (source.contentUsePolicy) {
            KnowledgeSourceContentUsePolicy.EXCERPT_ALLOWED.name -> when (source.licenseStatus) {
                KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL.name ->
                    allowed += KnowledgeMaterialDerivationKind.PUBLIC_OFFICIAL_EXCERPT.name
                KnowledgeSourceLicenseStatus.LICENSED.name ->
                    allowed += KnowledgeMaterialDerivationKind.LICENSED_EXCERPT.name
            }
            KnowledgeSourceContentUsePolicy.ADAPTATION_ALLOWED.name -> {
                if (source.licenseStatus == KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL.name) {
                    allowed += KnowledgeMaterialDerivationKind.PUBLIC_OFFICIAL_EXCERPT.name
                }
                if (source.licenseStatus == KnowledgeSourceLicenseStatus.LICENSED.name) {
                    allowed += KnowledgeMaterialDerivationKind.LICENSED_EXCERPT.name
                    allowed += KnowledgeMaterialDerivationKind.LICENSED_ADAPTATION.name
                }
            }
        }
        requireValid(material.derivationKind in allowed) {
            "Teaching-material derivation is incompatible with its source content-use policy"
        }
    }

    private fun id(value: String, name: String) {
        requireValid(value.isNotBlank() && value == value.trim() && value.length <= 256) {
            "$name must be a trimmed non-blank string of at most 256 characters"
        }
        requireValid(value.none(Char::isISOControl)) { "$name contains control characters" }
    }

    private fun text(value: String, name: String, maxLength: Int) {
        requireValid(value.isNotBlank() && value == value.trim() && value.length <= maxLength) {
            "$name must be trimmed, non-blank, and at most $maxLength characters"
        }
        requireValid(
            value.none { character ->
                (character.isISOControl() && character !in "\n\r\t") ||
                    character == '\u061C' ||
                    character == '\u200E' ||
                    character == '\u200F' ||
                    character in '\u202A'..'\u202E' ||
                    character in '\u2066'..'\u2069'
            },
        ) { "$name contains unsupported control characters" }
    }

    private fun known(value: String, name: String, allowed: Set<String>) {
        requireValid(value in allowed) { "$name has unknown value '$value'" }
    }

    private inline fun requireValid(condition: Boolean, message: () -> String) {
        if (!condition) throw DatabaseContractViolationException(message())
    }

    private fun invalid(message: String): Nothing =
        throw DatabaseContractViolationException(message)
}

object KnowledgeTeachingMaterialFingerprint {
    fun expected(material: KnowledgeTeachingMaterialRecord): String {
        val payload = listOf(
            material.subject,
            material.materialType,
            material.title,
            material.summaryMarkdown,
            material.applicabilityMarkdown,
            material.contentMarkdown,
            material.boundaryMarkdown,
            material.derivationKind,
            material.sourceId,
            material.sourceLocator,
            material.reviewedAtEpochMillis.toString(),
        ).joinToString("\u001F")
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }
    }
}
