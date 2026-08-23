package com.tingyun.smartmistakebook.core.database

/**
 * Deterministic CJK segmentation for the library full-text index.
 *
 * Android's bundled SQLite ships only byte-oriented tokenizers (simple,
 * unicode61, porter); none of them segment Chinese, so a contiguous Chinese
 * phrase becomes one opaque token and single-character queries never match.
 * Inserting a space between every CJK code point turns each character into an
 * indexed token; queries are transformed with the same function so both sides
 * agree. Latin words and digits stay whole.
 */
object CjkTextTokenizer {

    fun isCjk(codePoint: Int): Boolean {
        val block = Character.UnicodeBlock.of(codePoint) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    /** Inserts a space between adjacent CJK characters; collapses whitespace. */
    fun segment(text: String): String {
        if (text.isBlank()) return ""
        val builder = StringBuilder(text.length + 16)
        var previousWasCjk = false
        var previousWasSpace = true
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint)) {
                if (!previousWasSpace) {
                    builder.append(' ')
                    previousWasSpace = true
                }
                previousWasCjk = false
            } else {
                val currentIsCjk = isCjk(codePoint)
                if (previousWasCjk && currentIsCjk) {
                    builder.append(' ')
                }
                builder.appendCodePoint(codePoint)
                previousWasCjk = currentIsCjk
                previousWasSpace = false
            }
            index += charCount
        }
        return builder.toString().trim()
    }

    /**
     * Splits a user query into index-aligned tokens using exactly the same
     * transformation applied on the indexing side, so MATCH expressions built
     * from these tokens can always find indexed text.
     */
    fun tokens(query: String): List<String> =
        segment(query).split(' ').filter(String::isNotEmpty)

    /** Quotes a single token as an FTS4 string literal (double quotes doubled). */
    fun quotedPhrase(token: String): String =
        "\"" + token.replace("\"", "\"\"") + "\""

    /**
     * Builds the implicit-AND FTS4 MATCH expression for a user query:
     * every token becomes a quoted phrase, separated by spaces. Returns an
     * empty string when the query carries no searchable token.
     */
    fun matchExpression(query: String): String =
        tokens(query).joinToString(separator = " ", transform = ::quotedPhrase)
}
