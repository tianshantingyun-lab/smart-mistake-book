package com.tingyun.smartmistakebook.core.database

import java.util.Locale

/** Builds the bounded local index used to find relevant reviewed knowledge points. */
object KnowledgeSearchFeatureExtractor {
    const val MAX_QUERY_FEATURES = 128
    private const val MAX_NODE_FEATURES = 192
    private const val MAX_WORD_LENGTH = 48
    private const val MAX_SEARCH_FRAGMENTS = 16

    fun fromQuestion(text: String): Set<String> = extract(
        texts = listOf(text),
        limit = MAX_QUERY_FEATURES,
    )

    fun fromNode(node: KnowledgeNodeSeedRecord): Set<String> {
        val texts = buildList {
            add(node.canonicalName)
            addAll(node.aliases.sorted())
            node.boundaryMarkdown?.let(::add)
        }
        return extract(texts, MAX_NODE_FEATURES).ifEmpty {
            setOf(node.canonicalName.lowercase(Locale.ROOT).take(MAX_WORD_LENGTH))
        }
    }

    private fun extract(texts: Iterable<String>, limit: Int): Set<String> {
        val result = linkedSetOf<String>()
        val fragments = texts
            .flatMap { raw ->
                raw.lowercase(Locale.ROOT)
                    .split(NON_SEARCH_CHARACTER)
                    .filter(String::isNotBlank)
            }
        val selectedFragments = if (fragments.size <= MAX_SEARCH_FRAGMENTS) {
            fragments
        } else {
            // Long captures usually put the actual ask last and the givens first.
            buildList {
                add(fragments.last())
                add(fragments.first())
                balancedStartIndexes(fragments.size).forEach { index ->
                    if (size < MAX_SEARCH_FRAGMENTS && fragments[index] !in this) {
                        add(fragments[index])
                    }
                }
            }
        }
        val streams = ArrayDeque<Iterator<String>>()
        selectedFragments.mapTo(streams) { fragmentFeatures(it).iterator() }
        while (result.size < limit && streams.isNotEmpty()) {
            val iterator = streams.removeFirst()
            if (iterator.hasNext()) result += iterator.next()
            if (iterator.hasNext()) streams.addLast(iterator)
        }
        return result
    }

    private fun fragmentFeatures(fragment: String): Sequence<String> = sequence {
        if (fragment.length in 2..MAX_WORD_LENGTH) yield(fragment)
        for (width in 2..minOf(3, fragment.length)) {
            balancedStartIndexes(fragment.length - width + 1).forEach { start ->
                yield(fragment.substring(start, start + width))
            }
        }
    }

    private fun balancedStartIndexes(count: Int): Sequence<Int> = sequence {
        if (count <= 0) return@sequence
        val pending = ArrayDeque<IntRange>()
        pending += 0 until count
        while (pending.isNotEmpty()) {
            val range = pending.removeFirst()
            val middle = (range.first + range.last) / 2
            yield(middle)
            if (range.first < middle) pending += range.first until middle
            if (middle < range.last) pending += (middle + 1)..range.last
        }
    }

    private val NON_SEARCH_CHARACTER = Regex("[^\\p{L}\\p{N}]+")
}
