package com.tingyun.smartmistakebook.core.knowledge.database

import java.text.Normalizer
import java.util.Locale

internal object KnowledgeSearchNormalizer {
    const val MAX_FEATURE_CHARS = 128

    private const val MAX_QUERY_FEATURES = 64
    private const val MAX_INDEX_FEATURES_PER_TEXT = 128
    private const val MIN_NGRAM_SIZE = 2
    private const val MAX_NGRAM_SIZE = 4

    /**
     * NFKC, Locale.ROOT case folding, and punctuation folding make pack construction and runtime
     * recall independent of device locale and iteration order.
     */
    fun normalizeFeature(value: String): String {
        val compatibilityNormalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val folded = compatibilityNormalized.lowercase(Locale.ROOT)
        val output = StringBuilder(folded.length)
        var previousWasSeparator = true
        folded.forEach { character ->
            if (character.isLetterOrDigit()) {
                output.append(character)
                previousWasSeparator = false
            } else if (!previousWasSeparator) {
                output.append(' ')
                previousWasSeparator = true
            }
        }
        return output.toString().trim()
    }

    fun queryFeatures(query: String): List<String> {
        require(query.isNotBlank()) { "Knowledge search query must not be blank" }
        require(query.length <= HighSchoolKnowledgeCatalog.MAX_QUERY_CHARS) {
            "Knowledge search query is too long"
        }
        return extractFeatures(query, MAX_QUERY_FEATURES).also { features ->
            require(features.isNotEmpty()) {
                "Knowledge search query must contain letters or numbers"
            }
        }
    }

    fun indexFeatures(value: String): List<String> =
        extractFeatures(value, MAX_INDEX_FEATURES_PER_TEXT)

    private fun extractFeatures(
        value: String,
        limit: Int,
    ): List<String> {
        val normalized = normalizeFeature(value)
        if (normalized.isEmpty()) return emptyList()

        val features = LinkedHashSet<String>(limit)
        fun add(candidate: String) {
            if (
                features.size < limit &&
                    candidate.isNotEmpty() &&
                    candidate.length <= MAX_FEATURE_CHARS
            ) {
                features += candidate
            }
        }

        add(normalized)
        add(normalized.replace(" ", ""))
        normalized.split(' ').filter(String::isNotEmpty).forEach { token ->
            add(token)
            val maxSize = minOf(MAX_NGRAM_SIZE, token.length)
            for (size in MIN_NGRAM_SIZE..maxSize) {
                for (start in 0..token.length - size) {
                    add(token.substring(start, start + size))
                    if (features.size == limit) return@forEach
                }
            }
        }
        return features.toList()
    }
}

internal object KnowledgeSearchIndexBuilder {
    private data class Candidate(
        val subject: String,
        val feature: String,
        val nodeId: String,
        val kind: String,
        val weight: Int,
    )

    fun build(nodes: List<KnowledgeNodeEntity>): List<KnowledgeSearchFeatureEntity> {
        val bestByKey = LinkedHashMap<String, Candidate>()
        nodes
            .sortedWith(compareBy(KnowledgeNodeEntity::subject, KnowledgeNodeEntity::knowledgeNodeId))
            .forEach { node ->
                sequence {
                    yield(Triple("stable_code", node.stableCode, 100))
                    yield(Triple("canonical_name", node.canonicalName, 90))
                    yield(Triple("display_name", node.displayName, 85))
                    decodeAliases(node.aliasesText)
                        .sorted()
                        .forEach { alias -> yield(Triple("alias", alias, 75)) }
                    node.boundaryMarkdown?.let { boundary ->
                        yield(Triple("boundary", boundary, 25))
                    }
                }.forEach { (kind, value, weight) ->
                    KnowledgeSearchNormalizer.indexFeatures(value).forEach { feature ->
                        val key = "${node.subject}\u0000$feature\u0000${node.knowledgeNodeId}"
                        val candidate =
                            Candidate(
                                subject = node.subject,
                                feature = feature,
                                nodeId = node.knowledgeNodeId,
                                kind = kind,
                                weight = weight,
                            )
                        val current = bestByKey[key]
                        if (
                            current == null ||
                                candidate.weight > current.weight ||
                                (
                                    candidate.weight == current.weight &&
                                        candidate.kind < current.kind
                                )
                        ) {
                            bestByKey[key] = candidate
                        }
                    }
                }
            }

        return bestByKey.values
            .sortedWith(
                compareBy(
                    Candidate::subject,
                    Candidate::feature,
                    Candidate::nodeId,
                ),
            )
            .map { candidate ->
                KnowledgeSearchFeatureEntity(
                    subject = candidate.subject,
                    searchFeature = candidate.feature,
                    knowledgeNodeId = candidate.nodeId,
                    featureKind = candidate.kind,
                    rankWeight = candidate.weight,
                )
            }
    }
}

internal fun encodeAliases(aliases: Iterable<String>): String {
    val ordered = aliases.toList()
    require(ordered.distinct().size == ordered.size) { "Knowledge-node aliases must be unique" }
    require(ordered.all { it.isNotBlank() && ALIAS_SEPARATOR !in it }) {
        "Knowledge-node aliases are invalid"
    }
    return ordered.sorted().joinToString(ALIAS_SEPARATOR)
}

internal fun decodeAliases(aliasesText: String): List<String> =
    if (aliasesText.isEmpty()) {
        emptyList()
    } else {
        aliasesText.split(ALIAS_SEPARATOR)
    }
