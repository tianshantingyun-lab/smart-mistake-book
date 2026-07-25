package com.tingyun.smartmistakebook.core.model

import java.util.Locale

/**
 * Rejects internal implementation vocabulary from model-authored text that can reach students.
 *
 * Internal prompts, persisted protocol fields, and diagnostic messages may still use engineering
 * terms. This boundary is intentionally applied only to content that can be rendered in the app.
 */
internal object StudentFacingLanguagePolicy {
    private val forbiddenPhrases = setOf(
        "原子知识",
        "原子能力",
        "知识本体",
        "检索召回",
        "学习投影",
        "atomic knowledge",
        "atomic ability",
        "knowledge ontology",
        "knowledge grounding",
        "grounding request",
        "retrieval recall",
        "mastery projection",
        "source_grounded",
    )

    fun requirePlainLanguage(value: String, label: String) {
        val normalized = value.lowercase(Locale.ROOT)
        require(forbiddenPhrases.none(normalized::contains)) {
            "$label contains internal implementation vocabulary"
        }
    }
}
