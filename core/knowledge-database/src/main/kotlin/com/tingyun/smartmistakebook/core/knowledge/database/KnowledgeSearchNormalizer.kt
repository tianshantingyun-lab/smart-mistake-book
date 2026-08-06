package com.tingyun.smartmistakebook.core.knowledge.database

import java.text.Normalizer
import java.util.Locale

internal object KnowledgeSearchNormalizer {
    const val MAX_FEATURE_CHARS = 128

    private const val MAX_QUERY_FEATURES = 96
    private const val MAX_INDEX_FEATURES_PER_TEXT = 128
    private const val MIN_NGRAM_SIZE = 2
    private const val MAX_NGRAM_SIZE = 4
    private const val QUERY_REGION_CHARS = 512
    private const val SUBQUESTION_REGION_CHARS = 320
    private const val MAX_SUBQUESTION_REGIONS = 6

    /**
     * Natural-language words are locale-independently case-folded, while case-sensitive scientific
     * symbols keep their spelling. Superscript charges are protected before NFKC so a chemical
     * charge never becomes indistinguishable from an arithmetic operator.
     */
    fun normalizeFeature(value: String): String {
        val compatibilityNormalized =
            Normalizer.normalize(
                value
                    .replace('\u207A', PROTECTED_SUPERSCRIPT_PLUS)
                    .replace('\u207B', PROTECTED_SUPERSCRIPT_MINUS),
                Normalizer.Form.NFKC,
            )
        val tokens = ArrayList<String>()
        val word = StringBuilder()

        fun flushWord() {
            if (word.isNotEmpty()) {
                tokens += word.toString().normalizedWord()
                word.setLength(0)
            }
        }

        compatibilityNormalized.forEachIndexed { index, character ->
            when {
                character.isLetterOrDigit() || character == '_' -> word.append(character)
                character == COMBINING_VECTOR_ARROW -> {
                    if (word.isNotEmpty()) {
                        val vector = word.toString().normalizedWord()
                        word.setLength(0)
                        tokens += "vec_$vector"
                    } else if (tokens.isNotEmpty() && tokens.last().isPlainWord()) {
                        tokens[tokens.lastIndex] = "vec_${tokens.last()}"
                    }
                }
                character.isProtectedSuperscriptCharge() -> {
                    val charge = character.normalizedCharge()
                    if (word.isNotEmpty()) {
                        val chargedWord = word.toString().normalizedWord()
                        word.setLength(0)
                        tokens += "$chargedWord$charge"
                    } else if (tokens.lastOrNull()?.canCarryChemicalCharge() == true) {
                        tokens[tokens.lastIndex] = "${tokens.last()}$charge"
                    } else {
                        flushWord()
                        tokens += charge.toString()
                    }
                }
                character.isPlainPlusOrMinus() -> {
                    val pendingWord = word.toString().takeIf(String::isNotEmpty)
                    val previous = pendingWord?.normalizedWord() ?: tokens.lastOrNull()
                    val next = compatibilityNormalized.nextMeaningfulCharacter(index + 1)
                    if (previous?.isChemicalChargeCarrier(next) == true) {
                        val charge = character.normalizedCharge()
                        if (pendingWord != null) {
                            word.setLength(0)
                            tokens += "$previous$charge"
                        } else {
                            tokens[tokens.lastIndex] = "$previous$charge"
                        }
                    } else {
                        flushWord()
                        tokens += character.normalizedCharge().toString()
                    }
                }
                else -> {
                    flushWord()
                    character.scientificOperatorToken()?.let(tokens::add)
                }
            }
        }
        flushWord()
        return tokens.joinToString(" ")
    }

    fun queryFeatures(query: String): List<String> {
        require(query.isNotBlank()) { "Knowledge search query must not be blank" }
        require(query.length <= HighSchoolKnowledgeCatalog.MAX_QUERY_CHARS) {
            "Knowledge search query is too long"
        }
        return queryFeatures(listOf(query))
    }

    fun queryFeatures(query: KnowledgeRecallQuery): List<String> =
        queryFeatures(
            buildList {
                query.currentSubquestion?.let(::add)
                add(query.currentQuestion)
                query.surroundingContext?.let(::add)
            },
        )

    private fun queryFeatures(orderedTexts: List<String>): List<String> {
        val featuresByRegion =
            orderedTexts.flatMap { text ->
                queryRegions(text).map { region ->
                    extractRankedFeatures(region, MAX_INDEX_FEATURES_PER_TEXT)
                        .map(KnowledgeIndexedFeature::value)
                }
            }
        return roundRobinDistinct(featuresByRegion, MAX_QUERY_FEATURES).also { features ->
            require(features.isNotEmpty()) {
                "Knowledge search query must contain searchable content"
            }
        }
    }

    fun indexFeatures(value: String): List<String> =
        indexFeatureCandidates(value).map(KnowledgeIndexedFeature::value)

    internal fun indexFeatureCandidates(value: String): List<KnowledgeIndexedFeature> =
        extractRankedFeatures(value, MAX_INDEX_FEATURES_PER_TEXT)

    private fun extractRankedFeatures(
        value: String,
        limit: Int,
    ): List<KnowledgeIndexedFeature> {
        val normalized = normalizeFeature(value)
        if (normalized.isEmpty()) return emptyList()

        val features = LinkedHashMap<String, KnowledgeSearchFeatureSpecificity>(limit)
        fun add(
            candidate: String,
            specificity: KnowledgeSearchFeatureSpecificity,
        ) {
            if (
                features.size < limit &&
                    candidate.isNotEmpty() &&
                    candidate.length <= MAX_FEATURE_CHARS
            ) {
                val current = features[candidate]
                if (current == null || specificity.rank > current.rank) {
                    features[candidate] = specificity
                }
            }
        }

        add(normalized, KnowledgeSearchFeatureSpecificity.EXACT)
        normalized
            .split(' ')
            .takeIf { tokens -> tokens.size > 1 && tokens.all(String::isNaturalLanguageToken) }
            ?.joinToString("")
            .takeIf { compact -> compact != normalized }
            ?.let { compact -> add(compact, KnowledgeSearchFeatureSpecificity.EXACT) }
        normalized.split(' ').filter(String::isNotEmpty).forEach { token ->
            add(token, KnowledgeSearchFeatureSpecificity.TOKEN)
        }
        normalized.split(' ').filter(String::isNaturalLanguageToken).forEach { token ->
            val maxSize = minOf(MAX_NGRAM_SIZE, token.length)
            val positionsBySize =
                (MIN_NGRAM_SIZE..maxSize).associateWith { size ->
                    coveragePositions(token.length - size).iterator()
                }
            while (
                features.size < limit &&
                positionsBySize.values.any(Iterator<Int>::hasNext)
            ) {
                for (size in MIN_NGRAM_SIZE..maxSize) {
                    val positions = positionsBySize.getValue(size)
                    if (!positions.hasNext()) continue
                    val start = positions.next()
                    add(
                        token.substring(start, start + size),
                        KnowledgeSearchFeatureSpecificity.NGRAM,
                    )
                    if (features.size == limit) break
                }
            }
        }
        return features.map { (feature, specificity) ->
            KnowledgeIndexedFeature(feature, specificity)
        }
    }

    private fun queryRegions(query: String): List<String> {
        val regions = ArrayList<String>()
        if (query.length <= QUERY_REGION_CHARS) {
            regions += query
        } else {
            regions += query.take(QUERY_REGION_CHARS)
            val middleStart = (query.length - QUERY_REGION_CHARS) / 2
            regions += query.substring(middleStart, middleStart + QUERY_REGION_CHARS)
            regions += query.takeLast(QUERY_REGION_CHARS)
        }

        val subquestionMarkers = SUBQUESTION_MARKER.findAll(query).toList()
        if (subquestionMarkers.isNotEmpty()) {
            coveragePositions(subquestionMarkers.lastIndex)
                .take(MAX_SUBQUESTION_REGIONS)
                .map(subquestionMarkers::get)
                .forEach { match ->
                    val start =
                        (match.range.first - SUBQUESTION_REGION_CHARS / 4).coerceAtLeast(0)
                    val end = (start + SUBQUESTION_REGION_CHARS).coerceAtMost(query.length)
                    regions += query.substring(start, end)
                }
        }
        return regions.distinctBy(::normalizeFeature)
    }

    private fun roundRobinDistinct(
        regions: List<List<String>>,
        limit: Int,
    ): List<String> {
        val selected = LinkedHashSet<String>(limit)
        val largestRegion = regions.maxOfOrNull(List<String>::size).orEmpty()
        for (featureIndex in 0 until largestRegion) {
            for (region in regions) {
                region.getOrNull(featureIndex)?.let(selected::add)
                if (selected.size == limit) return selected.toList()
            }
        }
        return selected.toList()
    }

    private fun coveragePositions(lastStart: Int): Sequence<Int> = sequence {
        if (lastStart <= 0) {
            yield(0)
            return@sequence
        }
        val positions = LinkedHashSet<Int>(lastStart + 1)
        val middle = lastStart / 2
        var offset = 0
        while (positions.size <= lastStart) {
            val candidates =
                intArrayOf(
                    offset.coerceAtMost(lastStart),
                    (middle + offset).coerceAtMost(lastStart),
                    (middle - offset).coerceAtLeast(0),
                    (lastStart - offset).coerceAtLeast(0),
                )
            for (position in candidates) {
                if (positions.add(position)) yield(position)
            }
            offset += 1
        }
    }

    private val SUBQUESTION_MARKER =
        Regex("""(?:[（(]\s*\d{1,2}\s*[）)]|[①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳])""")

    private const val COMBINING_VECTOR_ARROW = '\u20D7'
}

internal data class KnowledgeIndexedFeature(
    val value: String,
    val specificity: KnowledgeSearchFeatureSpecificity,
)

internal enum class KnowledgeSearchFeatureSpecificity(
    val rank: Int,
) {
    NGRAM(1),
    TOKEN(2),
    EXACT(3),
}

internal object KnowledgeSearchIndexBuilder {
    private data class Candidate(
        val subject: String,
        val feature: String,
        val nodeId: String,
        val kind: String,
        val weight: Int,
    )

    internal fun buildForNode(node: KnowledgeNodeEntity): List<KnowledgeSearchFeatureEntity> {
        val bestByFeature = LinkedHashMap<String, Candidate>()
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
            KnowledgeSearchNormalizer.indexFeatureCandidates(value).forEach { indexed ->
                val feature = indexed.value
                val candidate =
                    Candidate(
                        subject = node.subject,
                        feature = feature,
                        nodeId = node.knowledgeNodeId,
                        kind = canonicalFeatureKind(kind, indexed.specificity),
                        weight =
                            searchFeatureWeight(
                                baseWeight = weight,
                                specificity = indexed.specificity,
                            ),
                    )
                val current = bestByFeature[feature]
                if (
                    current == null ||
                        candidate.weight > current.weight ||
                        (candidate.weight == current.weight && candidate.kind < current.kind)
                ) {
                    bestByFeature[feature] = candidate
                }
            }
        }
        return bestByFeature.values.map { candidate ->
            KnowledgeSearchFeatureEntity(
                subject = candidate.subject,
                searchFeature = candidate.feature,
                knowledgeNodeId = candidate.nodeId,
                featureKind = candidate.kind,
                rankWeight = candidate.weight,
            )
        }
    }

    fun build(nodes: List<KnowledgeNodeEntity>): List<KnowledgeSearchFeatureEntity> {
        val features = ArrayList<KnowledgeSearchFeatureEntity>()
        nodes
            .sortedWith(compareBy(KnowledgeNodeEntity::subject, KnowledgeNodeEntity::knowledgeNodeId))
            .forEach { node ->
                // The node id is part of the database primary key, so candidates from different
                // nodes can never replace one another. Keeping one global map retained a million
                // composite key strings for a full catalog. Deduplicate only the current node and
                // append its winners before advancing, which bounds temporary memory by one node.
                val nodeFeatures = buildForNode(node)
                require(
                    features.size + nodeFeatures.size <=
                        KnowledgePackBudgets.MAX_SEARCH_FEATURES,
                ) {
                    "Locally rebuilt knowledge-search index exceeds its budget"
                }
                features.addAll(nodeFeatures)
            }

        features.sortWith(
            compareBy(
                KnowledgeSearchFeatureEntity::subject,
                KnowledgeSearchFeatureEntity::searchFeature,
                KnowledgeSearchFeatureEntity::knowledgeNodeId,
            ),
        )
        return features
    }
}

private fun canonicalFeatureKind(
    sourceKind: String,
    specificity: KnowledgeSearchFeatureSpecificity,
): String =
    when (sourceKind) {
        "stable_code" ->
            when (specificity) {
                KnowledgeSearchFeatureSpecificity.EXACT -> "stable_code:exact"
                KnowledgeSearchFeatureSpecificity.TOKEN -> "stable_code:token"
                KnowledgeSearchFeatureSpecificity.NGRAM -> "stable_code:ngram"
            }
        "canonical_name" ->
            when (specificity) {
                KnowledgeSearchFeatureSpecificity.EXACT -> "canonical_name:exact"
                KnowledgeSearchFeatureSpecificity.TOKEN -> "canonical_name:token"
                KnowledgeSearchFeatureSpecificity.NGRAM -> "canonical_name:ngram"
            }
        "display_name" ->
            when (specificity) {
                KnowledgeSearchFeatureSpecificity.EXACT -> "display_name:exact"
                KnowledgeSearchFeatureSpecificity.TOKEN -> "display_name:token"
                KnowledgeSearchFeatureSpecificity.NGRAM -> "display_name:ngram"
            }
        "alias" ->
            when (specificity) {
                KnowledgeSearchFeatureSpecificity.EXACT -> "alias:exact"
                KnowledgeSearchFeatureSpecificity.TOKEN -> "alias:token"
                KnowledgeSearchFeatureSpecificity.NGRAM -> "alias:ngram"
            }
        "boundary" ->
            when (specificity) {
                KnowledgeSearchFeatureSpecificity.EXACT -> "boundary:exact"
                KnowledgeSearchFeatureSpecificity.TOKEN -> "boundary:token"
                KnowledgeSearchFeatureSpecificity.NGRAM -> "boundary:ngram"
            }
        else -> error("Unsupported knowledge-search feature source kind")
    }

private fun searchFeatureWeight(
    baseWeight: Int,
    specificity: KnowledgeSearchFeatureSpecificity,
): Int =
    when (specificity) {
        KnowledgeSearchFeatureSpecificity.EXACT -> baseWeight
        KnowledgeSearchFeatureSpecificity.TOKEN -> (baseWeight - 20).coerceAtLeast(10)
        KnowledgeSearchFeatureSpecificity.NGRAM -> (baseWeight / 4).coerceAtLeast(1)
    }

private fun Char.isProtectedSuperscriptCharge(): Boolean =
    this == PROTECTED_SUPERSCRIPT_PLUS || this == PROTECTED_SUPERSCRIPT_MINUS

private fun Char.isPlainPlusOrMinus(): Boolean =
    this == '+' || this == '-' || this == '\u2212'

private fun Char.normalizedCharge(): Char =
    when (this) {
        '\u2212', PROTECTED_SUPERSCRIPT_MINUS -> '-'
        PROTECTED_SUPERSCRIPT_PLUS -> '+'
        else -> this
    }

private fun Char.scientificOperatorToken(): String? =
    when (this) {
        '=' -> "="
        '≠' -> "≠"
        '<' -> "<"
        '>' -> ">"
        '≤' -> "≤"
        '≥' -> "≥"
        '→', '⟶', '⟹', '⇒' -> "→"
        '←', '⟵', '⇐' -> "←"
        '↔', '⟷', '⇔' -> "↔"
        '⇌', '⇋' -> "⇌"
        '∂' -> "∂"
        '∇' -> "∇"
        '/', '⁄' -> "/"
        '\'', '′' -> "′"
        '×' -> "×"
        '·', '⋅' -> "·"
        else -> null
    }

private fun String.nextMeaningfulCharacter(startIndex: Int): Char? {
    for (index in startIndex until length) {
        if (!this[index].isWhitespace()) return this[index]
    }
    return null
}

private fun String.isChemicalChargeCarrier(next: Char?): Boolean {
    if (!CHEMICAL_FORMULA.matches(this)) return false
    if (next == null || !next.isLetterOrDigit()) return true
    return length > 1 &&
        (
            next.isUpperCase() ||
                (!next.isAsciiLetter() && !next.isDigit())
        )
}

private fun String.canCarryChemicalCharge(): Boolean =
    removeSuffix("+").removeSuffix("-").let(CHEMICAL_FORMULA::matches)

private fun String.normalizedWord(): String =
    if (isCaseSensitiveScientificWord()) this else lowercase(Locale.ROOT)

private fun String.isCaseSensitiveScientificWord(): Boolean {
    if (any(Char::isGreekLetter)) return true
    if (length == 1 && single().isAsciiLetter()) return true
    if (CHEMICAL_FORMULA.matches(this)) return true
    val asciiLetters = filter(Char::isAsciiLetter)
    if (asciiLetters.isEmpty()) return false
    val titleCaseNaturalWord =
        length > 2 &&
            first().isUpperCase() &&
            drop(1).all { character ->
                !character.isAsciiLetter() || character.isLowerCase()
            }
    return !titleCaseNaturalWord && asciiLetters.any(Char::isUpperCase)
}

private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

private fun Char.isGreekLetter(): Boolean =
    this in '\u0370'..'\u03FF' || this in '\u1F00'..'\u1FFF'

private fun String.isPlainWord(): Boolean = all { character -> character.isLetterOrDigit() || character == '_' }

private fun String.isNaturalLanguageToken(): Boolean =
    length >= 2 &&
        all { character -> character.isLetterOrDigit() || character == '_' } &&
        none { character -> character == ':' } &&
        !isCaseSensitiveScientificWord()

private fun Int?.orEmpty(): Int = this ?: 0

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

private const val PROTECTED_SUPERSCRIPT_PLUS = '\uE000'
private const val PROTECTED_SUPERSCRIPT_MINUS = '\uE001'
private val CHEMICAL_FORMULA = Regex("""(?:[A-Z][a-z]?\d*)+""")
