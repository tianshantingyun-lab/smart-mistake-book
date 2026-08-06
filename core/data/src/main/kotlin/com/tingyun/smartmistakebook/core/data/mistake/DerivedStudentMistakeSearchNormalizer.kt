package com.tingyun.smartmistakebook.core.data.mistake

import java.text.Normalizer
import java.util.Locale

/**
 * Byte-for-byte search semantics of StudentMistakeSearchNormalizer, kept here because the
 * authority module intentionally does not expose its indexing implementation.
 */
internal object DerivedStudentMistakeSearchNormalizer {
    fun document(
        title: String?,
        stemPreview: String,
        practiceUnitTitle: String,
    ): DerivedStudentMistakeSearchDocument {
        val normalizedText =
            normalize(listOfNotNull(title, stemPreview, practiceUnitTitle).joinToString("\n"))
        return DerivedStudentMistakeSearchDocument(
            normalizedText = normalizedText,
            tokenizedText = tokenize(normalizedText),
        )
    }

    fun query(raw: String): DerivedStudentMistakeSearchQuery {
        val normalized = normalize(raw)
        val tokens = tokenize(normalized).split(' ').filter(String::isNotBlank).distinct()
        require(tokens.isNotEmpty()) {
            "Mistake-library search must contain at least one searchable letter or number"
        }
        return DerivedStudentMistakeSearchQuery(
            normalizedText = normalized,
            ftsMatchExpression =
                tokens.joinToString(" ") { token ->
                    if (token.isCjkToken()) token else "$token*"
                },
        )
    }

    fun normalize(raw: String): String {
        val compatibilityNormalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return buildString(compatibilityNormalized.length) {
            var pendingSpace = false
            compatibilityNormalized.lowercase(Locale.ROOT).codePoints().forEach { codePoint ->
                if (Character.isWhitespace(codePoint)) {
                    pendingSpace = isNotEmpty()
                } else {
                    if (pendingSpace) append(' ')
                    appendCodePoint(codePoint)
                    pendingSpace = false
                }
            }
        }.trim()
    }

    private fun tokenize(normalized: String): String =
        buildString(normalized.length * 2) {
            var pendingSeparator = false
            var wordOpen = false
            normalized.codePoints().forEach { codePoint ->
                when {
                    isCjkCodePoint(codePoint) -> {
                        if (isNotEmpty() && (pendingSeparator || wordOpen)) append(' ')
                        appendCodePoint(codePoint)
                        pendingSeparator = true
                        wordOpen = false
                    }

                    Character.isLetterOrDigit(codePoint) || isCombiningMark(codePoint) -> {
                        if (isNotEmpty() && pendingSeparator) append(' ')
                        appendCodePoint(codePoint)
                        pendingSeparator = false
                        wordOpen = true
                    }

                    else -> {
                        pendingSeparator = isNotEmpty()
                        wordOpen = false
                    }
                }
            }
        }

    private fun isCombiningMark(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private fun isCjkCodePoint(codePoint: Int): Boolean {
        val script = Character.UnicodeScript.of(codePoint)
        return script == Character.UnicodeScript.HAN ||
            script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA ||
            script == Character.UnicodeScript.HANGUL
    }

    private fun String.isCjkToken(): Boolean = codePoints().allMatch(::isCjkCodePoint)
}

internal data class DerivedStudentMistakeSearchDocument(
    val normalizedText: String,
    val tokenizedText: String,
)

internal data class DerivedStudentMistakeSearchQuery(
    val normalizedText: String,
    val ftsMatchExpression: String,
)
