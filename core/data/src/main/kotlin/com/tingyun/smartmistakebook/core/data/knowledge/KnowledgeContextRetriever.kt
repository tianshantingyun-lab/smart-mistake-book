package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import java.util.Locale

/** Selects the small trusted ontology slice disclosed to the organization model. */
internal object KnowledgeContextRetriever {
    fun select(
        candidates: List<KnowledgeNodeSeedRecord>,
        relations: List<KnowledgeNodeRelationRecord> = emptyList(),
        questionText: String,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> {
        require(limit > 0) { "Knowledge context limit must be positive" }
        if (candidates.isEmpty()) return emptyList()

        val trusted = candidates.filter { node ->
            node.verificationStatus == KnowledgeNodeVerificationStatus.CURATED.name ||
                node.verificationStatus == KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name
        }
        val byId = trusted.associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
        val prerequisitesByDependent = relations.groupBy(
            KnowledgeNodeRelationRecord::dependentKnowledgeNodeId,
            KnowledgeNodeRelationRecord::prerequisiteKnowledgeNodeId,
        )
        val query = SearchText.of(questionText)
        val rankedAtoms = trusted.asSequence()
            .filter { it.granularity == KnowledgeNodeGranularity.ATOMIC.name }
            .map { node ->
                val parentName = node.parentKnowledgeNodeId?.let(byId::get)?.canonicalName.orEmpty()
                ScoredNode(node, relevance(node, parentName, query))
            }
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<ScoredNode> { it.score }
                    .thenBy { it.node.canonicalName }
                    .thenBy { it.node.knowledgeNodeId },
            )
            .toList()

        if (rankedAtoms.isEmpty()) {
            return trusted.asSequence()
                .filter { it.granularity == KnowledgeNodeGranularity.TOPIC.name }
                .sortedWith(compareBy(KnowledgeNodeSeedRecord::canonicalName, KnowledgeNodeSeedRecord::knowledgeNodeId))
                .take(limit)
                .toList()
        }

        val selected = LinkedHashMap<String, KnowledgeNodeSeedRecord>(limit)
        rankedAtoms.forEach { scored ->
            val prerequisites = prerequisitesByDependent[scored.node.knowledgeNodeId]
                .orEmpty()
                .mapNotNull(byId::get)
            val additions = (prerequisites.flatMap { prerequisite ->
                listOfNotNull(prerequisite.parentKnowledgeNodeId?.let(byId::get), prerequisite)
            } + listOfNotNull(scored.node.parentKnowledgeNodeId?.let(byId::get), scored.node))
                .distinctBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
                .filterNot { selected.containsKey(it.knowledgeNodeId) }
            if (selected.size + additions.size <= limit) {
                additions.forEach { selected[it.knowledgeNodeId] = it }
            }
        }
        return selected.values.toList()
    }

    private fun relevance(
        node: KnowledgeNodeSeedRecord,
        parentName: String,
        query: SearchText,
    ): Int {
        val hasDirectFeature = node.canonicalName.hasSearchFeatureIn(query.features) ||
            node.aliases.any { it.hasSearchFeatureIn(query.features) } ||
            node.boundaryMarkdown.orEmpty().hasSearchFeatureIn(query.features)
        if (!hasDirectFeature) return 0
        val canonical = SearchText.of(node.canonicalName)
        val aliases = node.aliases.map(SearchText::of)
        val parent = SearchText.of(parentName)
        val boundary = SearchText.of(node.boundaryMarkdown.orEmpty())
        val directScore = overlap(canonical, query, weight = 8) +
            aliases.sumOf { overlap(it, query, weight = 7) } +
            overlap(boundary, query, weight = 1) +
            exactPhraseBoost(canonical, query, 80) +
            aliases.maxOfOrNull { exactPhraseBoost(it, query, 70) }.orZero()
        if (directScore == 0) return 0
        return directScore + overlap(parent, query, weight = 3)
    }

    private fun overlap(source: SearchText, query: SearchText, weight: Int): Int =
        source.features.count(query.features::contains) * weight

    private fun exactPhraseBoost(source: SearchText, query: SearchText, boost: Int): Int =
        if (source.compact.length >= 2 && query.compact.contains(source.compact)) boost else 0

    private fun String.hasSearchFeatureIn(queryFeatures: Set<String>): Boolean {
        val normalized = lowercase(Locale.ROOT)
        val compact = buildString(normalized.length) {
            normalized.forEach { character ->
                if (character.isLetterOrDigit()) append(character)
            }
        }
        for (width in 2..minOf(3, compact.length)) {
            for (start in 0..compact.length - width) {
                if (compact.substring(start, start + width) in queryFeatures) return true
            }
        }
        return false
    }

    private data class ScoredNode(val node: KnowledgeNodeSeedRecord, val score: Int)

    private data class SearchText(val compact: String, val features: Set<String>) {
        companion object {
            fun of(raw: String): SearchText {
                val normalized = raw.lowercase(Locale.ROOT)
                val compact = normalized.filter(Char::isLetterOrDigit)
                val characterNgrams = buildSet {
                    if (compact.length >= 2) {
                        compact.windowed(2).forEach(::add)
                        compact.windowed(3).forEach(::add)
                    }
                }
                val wordTokens = normalized.split(Regex("[^\\p{L}\\p{N}]+"))
                    .asSequence()
                    .filter { it.length >= 2 }
                    .toSet()
                return SearchText(compact, characterNgrams + wordTokens)
            }
        }
    }

    private fun Int?.orZero(): Int = this ?: 0
}
