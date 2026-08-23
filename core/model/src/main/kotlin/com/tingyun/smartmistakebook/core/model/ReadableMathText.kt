package com.tingyun.smartmistakebook.core.model

/**
 * Converts LaTeX-like formula input into readable Unicode text.
 *
 * There is exactly ONE math parser in the codebase: [MathParser]. The old
 * second, regex-based parser has been removed. When parsing fails for any
 * reason, the original formula text is passed through verbatim (原文透传)
 * instead of being rebuilt by regex substitution, so users always see the
 * source formula rather than a lossy reconstruction.
 */
object ReadableMathText {
    fun formula(value: String): String {
        return try {
            MathRenderer.render(MathParser.parse(value))
        } catch (_: Exception) {
            // Parse failure fallback: pass the original text through unchanged.
            value
        }
    }

    fun inlineMarkdown(value: String, lineBreakReplacement: Char = '，'): String = buildString {
        SafeInlineMarkdown.parse(value).forEach { token ->
            when (token) {
                is InlineToken.Text -> append(token.value)
                is InlineToken.Strong -> append(token.value)
                is InlineToken.Emphasis -> append(token.value)
                is InlineToken.Code -> append(token.value)
                is InlineToken.Formula -> append(formula(token.value))
                InlineToken.LineBreak -> append(lineBreakReplacement)
            }
        }
    }
}
