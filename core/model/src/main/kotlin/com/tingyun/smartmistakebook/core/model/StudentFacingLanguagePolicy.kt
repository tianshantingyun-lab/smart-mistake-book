package com.tingyun.smartmistakebook.core.model

import java.text.Normalizer
import java.util.Locale

/**
 * Rejects internal implementation vocabulary from model-authored text that can reach students.
 *
 * Internal prompts, persisted protocol fields, and diagnostic messages may still use engineering
 * terms. This boundary is intentionally applied only to content that can be rendered in the app.
 */
internal object StudentFacingLanguagePolicy {
    const val POLICY_VERSION = "student-facing-language-v8"

    private val exactForbiddenPhrases by lazy {
        forbiddenPhrases(
        "内部资料",
        "内部审校",
        "审校资料",
        "内部参考资料",
        "内部参考",
        "审校参考",
        "资料类型",
        "来源状态",
        "原子知识",
        "原子能力",
        "知识本体",
        "检索召回",
        "学习投影",
        "清单指纹",
        "激活代次",
        "激活世代",
        "绑定见证",
        "来源见证",
        "內部資料",
        "內部審校",
        "審校資料",
        "內部參考資料",
        "審校參考",
        "資料類型",
        "來源狀態",
        "原子知識",
        "原子能力",
        "知識本體",
        "檢索召回",
        "學習投影",
        "清單指紋",
        "啟用代次",
        "啟用世代",
        "綁定見證",
        "來源見證",
        "atomic knowledge",
        "atomic ability",
        "knowledge ontology",
        "knowledge grounding",
        "grounding request",
        "internal material",
        "internal reference",
        "reviewed teaching reference",
        "retrieval recall",
        "source status",
        "mastery projection",
        "source_grounded",
        "manifest fingerprint",
        "binding witness",
        "node witness",
        "catalog provenance",
        "knowledge pack version",
        "taxonomy version",
        "知识节点",
        "知識節點",
        "候选节点",
        "候選節點",
        "置信度",
        "證據權重",
        "证据权重",
        "证据等级",
        "證據等級",
        "正向证据",
        "正向證據",
        "负向证据",
        "負向證據",
        "错误归因",
        "錯誤歸因",
        "知识归因",
        "知識歸因",
        "归因流程",
        "歸因流程",
        "归因结果",
        "歸因結果",
        "knowledge node",
        "confidence score",
        "evidence weight",
        "error attribution",
        "knowledge attribution",
        "attribution process",
        "attribution result",
        )
    }

    private val internalOwnershipMarkers = canonicalFamily(
        "内部",
        "內部",
        "内置",
        "內置",
        "私有",
        "internal",
        "private",
        "system internal",
        "system-owned",
        "system-maintained",
    )

    private val teachingMarkers = canonicalFamily(
        "教学",
        "教學",
        "讲解",
        "講解",
        "教参",
        "教參",
        "teaching",
        "instructional",
        "explanation",
    )

    private val materialOrReferenceMarkers = canonicalFamily(
        "资料",
        "資料",
        "材料",
        "素材",
        "参考",
        "參考",
        "讲义",
        "講義",
        "教参",
        "教參",
        "material",
        "reference",
        "source",
        "corpus",
    )

    private val reviewMarkers = canonicalFamily(
        "审校",
        "審校",
        "审核",
        "審核",
        "复核",
        "複核",
        "reviewed",
        "curated",
        "vetted",
    )

    private val knowledgeStoreMarkers = canonicalFamily(
        "知识库",
        "知識庫",
        "knowledge base",
    )

    private val knowledgeDatabaseMarkers = canonicalFamily(
        "知识数据库",
        "知识资料库",
        "知識資料庫",
        "knowledge database",
    )

    private val provenanceMarkers = canonicalFamily(
        "来源状态",
        "來源狀態",
        "来源见证",
        "來源見證",
        "绑定见证",
        "綁定見證",
        "清单指纹",
        "清單指紋",
        "激活代次",
        "激活世代",
        "啟用代次",
        "啟用世代",
        "source status",
        "provenance",
        "manifest fingerprint",
        "activation generation",
        "binding witness",
        "node witness",
    )

    private val protocolMetadataMarkers = canonicalFamily(
        "manifest fingerprint",
        "catalog provenance",
        "taxonomy version",
        "binding witness",
        "node witness",
        "knowledge pack",
        "configuration version",
        "provider configuration",
        "runtime authority",
    )

    private val englishActivationMarkers = canonicalFamily("activation")
    private val englishGenerationMarkers = canonicalFamily("generation")

    private val retrievalMarkers = canonicalFamily(
        "检索",
        "檢索",
        "召回",
        "retrieval",
        "grounding",
    )

    private val sourceMarkers = canonicalFamily("来源", "來源", "source")
    private val statusMarkers = canonicalFamily("状态", "狀態", "status")
    private val contextualMasteryMarkers = canonicalFamily(
        "mastery",
        "proficiency",
        "knowledge state",
        "learner model",
        "learning memory",
        "mastery projection",
    )
    private val ambiguousEnglishMasteryMarkers = canonicalFamily(
        "candidate node",
        "evidence level",
        "positive evidence",
        "negative evidence",
    )

    private val splitProtocolFamilies = listOf(
        canonicalFamily("知识", "知識", "knowledge") to
            canonicalFamily("本体", "本體", "ontology"),
        canonicalFamily("检索", "檢索") to canonicalFamily("召回"),
        canonicalFamily("清单", "清單", "manifest") to
            canonicalFamily("指纹", "指紋", "fingerprint"),
        canonicalFamily("激活", "啟用") to canonicalFamily("代次", "世代"),
        canonicalFamily("绑定", "綁定", "节点", "節點", "binding", "node") to
            canonicalFamily("见证", "見證", "witness"),
        canonicalFamily("catalog") to canonicalFamily("provenance"),
        canonicalFamily("knowledge pack", "taxonomy") to canonicalFamily("version"),
        canonicalFamily("knowledge") to canonicalFamily("grounding"),
        canonicalFamily("grounding") to canonicalFamily("request"),
    )

    fun requirePlainLanguage(value: String, label: String) {
        val normalizedDocument = normalizeCompatibility(value)
        val policyNormalizedDocument = normalizedDocument.foldProtectedLatinConfusables()
        val foldedDocument = canonicalizeNormalized(policyNormalizedDocument)
        val clauses = canonicalClauses(policyNormalizedDocument)
        require(
            exactForbiddenPhrases.none { forbidden ->
                forbidden.appearsIn(normalizedDocument, foldedDocument)
            } && clauses.none { clause ->
                clause.containsSplitProtocolFamily() ||
                    clause.containsInternalTeachingReferenceFamily() ||
                    clause.containsInternalKnowledgeStoreFamily() ||
                    clause.containsContextualMasteryVocabulary()
            },
        ) {
            "$label contains internal implementation vocabulary"
        }
    }

    private fun String.containsInternalTeachingReferenceFamily(): Boolean {
        val mentionsInternalOwnership = containsAny(internalOwnershipMarkers)
        val mentionsTeaching = containsAny(teachingMarkers)
        val mentionsMaterialOrReference = containsAny(materialOrReferenceMarkers)
        val mentionsReview = containsAny(reviewMarkers)
        val internalTeachingReference =
            mentionsInternalOwnership && mentionsMaterialOrReference &&
                (mentionsTeaching || mentionsReview)
        return internalTeachingReference
    }

    private fun String.containsInternalKnowledgeStoreFamily(): Boolean {
        val technicalKnowledgeStore = containsAny(knowledgeStoreMarkers) &&
            (
                containsAny(internalOwnershipMarkers) ||
                    containsAny(provenanceMarkers) ||
                    containsAny(retrievalMarkers)
                )
        return containsAny(knowledgeDatabaseMarkers) || technicalKnowledgeStore
    }

    private fun String.containsContextualMasteryVocabulary(): Boolean =
        containsAny(ambiguousEnglishMasteryMarkers) &&
            containsAny(contextualMasteryMarkers)

    private fun String.containsSplitProtocolFamily(): Boolean {
        val technicalSourceStatus = containsOrderedNearby(sourceMarkers, statusMarkers) &&
            (
                containsAny(knowledgeStoreMarkers) ||
                    containsAny(internalOwnershipMarkers)
                )
        val technicalActivationGeneration =
            containsOrderedNearby(englishActivationMarkers, englishGenerationMarkers) &&
                (
                    containsAny(internalOwnershipMarkers) ||
                        containsAny(knowledgeStoreMarkers) ||
                        containsAny(protocolMetadataMarkers)
                    )
        return technicalSourceStatus || technicalActivationGeneration ||
            splitProtocolFamilies.any { (first, second) ->
            containsOrderedNearby(first, second)
        }
    }

    private fun String.containsOrderedNearby(
        firstMarkers: Set<String>,
        secondMarkers: Set<String>,
    ): Boolean = firstMarkers.any firstMarker@ { first ->
        var firstIndex = indexOf(first)
        while (firstIndex >= 0) {
            val firstEnd = firstIndex + first.length
            val nearbySecond = secondMarkers.any { second ->
                val secondIndex = indexOf(second, startIndex = firstEnd)
                secondIndex >= firstEnd && secondIndex - firstEnd <= MAX_SPLIT_PROTOCOL_GAP_CHARS
            }
            if (nearbySecond) return@firstMarker true
            firstIndex = indexOf(first, startIndex = firstIndex + 1)
        }
        false
    }

    private fun String.containsAny(markers: Set<String>): Boolean = markers.any(::contains)

    private data class ForbiddenPhrase(
        val folded: String,
        val latinPattern: Regex?,
    ) {
        fun appearsIn(original: String, foldedDocument: String): Boolean =
            latinPattern?.containsMatchIn(original.foldProtectedLatinConfusables())
                ?: foldedDocument.contains(folded)
    }

    private fun forbiddenPhrases(vararg values: String): List<ForbiddenPhrase> = values.map { value ->
        val normalizedValue = normalizeCompatibility(value)
        val latinTokens = LATIN_TOKEN.findAll(normalizedValue).map(MatchResult::value).toList()
        val latinOnly = latinTokens.isNotEmpty() && normalizedValue.none { character ->
            character.isLetterOrDigit() && character.code > ASCII_MAX_CODE_POINT
        }
        ForbiddenPhrase(
            folded = canonicalizeNormalized(normalizedValue),
            latinPattern = latinTokens.takeIf { latinOnly }?.let { tokens ->
                Regex(
                    pattern = tokens.joinToString(
                        separator = LATIN_SEPARATOR_PATTERN,
                        prefix = "(?i)(?<![\\p{IsLatin}0-9])",
                        postfix = "(?![\\p{IsLatin}0-9])",
                        transform = ::latinTokenPattern,
                    ),
                )
            },
        )
    }

    private fun latinTokenPattern(token: String): String = token
        .map { character -> Regex.escape(character.toString()) }
        .joinToString(LATIN_SEPARATOR_PATTERN)

    private fun canonicalFamily(vararg values: String): Set<String> =
        values.mapTo(linkedSetOf(), ::canonicalize)

    private fun canonicalClauses(value: String): List<String> = value
        .replace(Regex("\\.(?=\\s|$)"), "。")
        .split(CLAUSE_BOUNDARIES)
        .map(::canonicalize)
        .filter(String::isNotEmpty)

    /** Collapses case and separators so punctuation cannot disguise an internal protocol term. */
    private fun canonicalize(value: String): String =
        canonicalizeNormalized(normalizeCompatibility(value))

    private fun canonicalizeNormalized(value: String): String = buildString(value.length) {
        value.lowercase(Locale.ROOT).forEach { character ->
            if (character.isLetterOrDigit()) append(character)
        }
    }

    private fun normalizeCompatibility(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)

    /** Maps Latin-lookalikes only inside mixed Latin tokens, leaving real Greek/Cyrillic prose. */
    private fun String.foldProtectedLatinConfusables(): String = buildString(length) {
        var cursor = 0
        while (cursor < this@foldProtectedLatinConfusables.length) {
            val character = this@foldProtectedLatinConfusables[cursor]
            if (!character.isLetterOrDigit()) {
                append(character)
                cursor += 1
                continue
            }
            val tokenStart = cursor
            while (
                cursor < this@foldProtectedLatinConfusables.length &&
                this@foldProtectedLatinConfusables[cursor].isLetterOrDigit()
            ) {
                cursor += 1
            }
            val token = this@foldProtectedLatinConfusables.substring(tokenStart, cursor)
            val isMixedLatinLookalike = token.any(::isAsciiLatinLetter) &&
                token.any(PROTECTED_LATIN_CONFUSABLES::containsKey)
            token.forEach { tokenCharacter ->
                append(
                    if (isMixedLatinLookalike) {
                        PROTECTED_LATIN_CONFUSABLES[tokenCharacter] ?: tokenCharacter
                    } else {
                        tokenCharacter
                    },
                )
            }
        }
    }

    private fun isAsciiLatinLetter(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z'

    private val PROTECTED_LATIN_CONFUSABLES = mapOf(
        'А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'Н' to 'H', 'І' to 'I',
        'Ј' to 'J', 'К' to 'K', 'М' to 'M', 'О' to 'O', 'Р' to 'P', 'Ѕ' to 'S',
        'Т' to 'T', 'Х' to 'X', 'У' to 'Y', 'а' to 'a', 'с' to 'c', 'е' to 'e',
        'і' to 'i', 'ј' to 'j', 'о' to 'o', 'р' to 'p', 'ѕ' to 's', 'х' to 'x',
        'у' to 'y',
        'Α' to 'A', 'Β' to 'B', 'Ε' to 'E', 'Ζ' to 'Z', 'Η' to 'H', 'Ι' to 'I',
        'Κ' to 'K', 'Μ' to 'M', 'Ν' to 'N', 'Ο' to 'O', 'Ρ' to 'P', 'Τ' to 'T',
        'Υ' to 'Y', 'Χ' to 'X', 'α' to 'a', 'ε' to 'e', 'ι' to 'i', 'κ' to 'k',
        'ο' to 'o', 'ρ' to 'p', 'τ' to 't', 'υ' to 'y', 'χ' to 'x',
    )
    private val CLAUSE_BOUNDARIES = Regex("[。！？!?；;]+")
    private val LATIN_TOKEN = Regex("[a-zA-Z0-9]+")
    private const val LATIN_SEPARATOR_PATTERN = "[^\\p{L}\\p{N}]*+"
    private const val ASCII_MAX_CODE_POINT = 0x7f
    private const val MAX_SPLIT_PROTOCOL_GAP_CHARS = 24
}
