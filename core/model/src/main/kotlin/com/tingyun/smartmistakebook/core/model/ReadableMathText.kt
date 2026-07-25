package com.tingyun.smartmistakebook.core.model

/**
 * Converts the deliberately small formula whitelist into readable Unicode text.
 *
 * This is a display fallback, not a TeX engine: it never executes markup, loads remote content,
 * or guesses mathematical meaning outside the supported commands.
 */
object ReadableMathText {
    fun formula(value: String): String {
        var readable = RestrictedFormulaText.sanitize(value)
            .replace("\\rightleftharpoons", "⇌")
            .replace("\\left", "")
            .replace("\\right", "")
            .replace("\\infty", "∞")
            .replace("\\approx", "≈")
            .replace("\\times", "×")
            .replace("\\cdot", "·")
            .replace("\\div", "÷")
            .replace("\\pm", "±")
            .replace("\\le", "≤")
            .replace("\\ge", "≥")
            .replace("\\ne", "≠")
            .replace("\\sum", "∑")
            .replace("\\int", "∫")
            .replace("\\alpha", "α")
            .replace("\\beta", "β")
            .replace("\\gamma", "γ")
            .replace("\\delta", "δ")
            .replace("\\Delta", "Δ")
            .replace("\\lambda", "λ")
            .replace("\\mu", "μ")
            .replace("\\omega", "ω")
            .replace("\\pi", "π")
            .replace("\\sigma", "σ")
            .replace("\\theta", "θ")
            .replace("\\sin", "sin")
            .replace("\\cos", "cos")
            .replace("\\tan", "tan")
            .replace("\\lim", "lim")
            .replace("\\log", "log")
            .replace("\\ln", "ln")

        repeat(MAX_NESTED_REPLACEMENT_PASSES) {
            readable = FRACTION_PATTERN.replace(readable) { match ->
                "(${match.groupValues[1]})/(${match.groupValues[2]})"
            }
            readable = SQUARE_ROOT_PATTERN.replace(readable) { match ->
                "√(${match.groupValues[1]})"
            }
            readable = TEXT_PATTERN.replace(readable) { match -> match.groupValues[1] }
            readable = VECTOR_PATTERN.replace(readable) { match -> "${match.groupValues[1]}⃗" }
            readable = VECTOR_ATOM_PATTERN.replace(readable) { match -> "${match.groupValues[1]}⃗" }
            readable = OVERLINE_PATTERN.replace(readable) { match -> "${match.groupValues[1]}̅" }
        }

        readable = SUPERSCRIPT_PATTERN.replace(readable) { match ->
            val source = match.groupValues[1].ifEmpty { match.groupValues[2] }
            readableScript(source, SUPERSCRIPTS, "^")
        }
        readable = SUBSCRIPT_PATTERN.replace(readable) { match ->
            val source = match.groupValues[1].ifEmpty { match.groupValues[2] }
            readableScript(source, SUBSCRIPTS, "_")
        }
        return readable.replace("{", "").replace("}", "")
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

    private const val MAX_NESTED_REPLACEMENT_PASSES = 4
    private val FRACTION_PATTERN = Regex("\\\\frac\\{([^{}]+)\\}\\{([^{}]+)\\}")
    private val SQUARE_ROOT_PATTERN = Regex("\\\\sqrt\\{([^{}]+)\\}")
    private val TEXT_PATTERN = Regex("\\\\text\\{([^{}]+)\\}")
    private val VECTOR_PATTERN = Regex("\\\\vec\\{([^{}]+)\\}")
    private val VECTOR_ATOM_PATTERN = Regex("\\\\vec[ \\t]*([A-Za-z0-9])")
    private val OVERLINE_PATTERN = Regex("\\\\overline\\{([^{}]+)\\}")
    private val SUPERSCRIPT_PATTERN =
        Regex("\\^\\{([A-Za-z0-9+\\-=]+)\\}|\\^([A-Za-z0-9])")
    private val SUBSCRIPT_PATTERN =
        Regex("_\\{([A-Za-z0-9+\\-=]+)\\}|_([A-Za-z0-9])")
    private val SUPERSCRIPTS = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', 'n' to 'ⁿ',
    )
    private val SUBSCRIPTS = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌',
    )

    private fun readableScript(
        source: String,
        glyphs: Map<Char, Char>,
        fallbackMarker: String,
    ): String {
        val converted = source.map(glyphs::get)
        return if (converted.all { it != null }) {
            converted.joinToString(separator = "") { requireNotNull(it).toString() }
        } else {
            "$fallbackMarker($source)"
        }
    }
}
