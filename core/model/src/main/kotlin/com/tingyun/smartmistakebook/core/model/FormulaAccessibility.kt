package com.tingyun.smartmistakebook.core.model

/**
 * Pure, JVM-testable builders for formula accessibility (TalkBack) strings.
 *
 * The composable `FormulaBlock` in core:ui delegates its contentDescription
 * construction to these functions, keeping the spoken-text logic out of the
 * Compose layer so it can be asserted in plain unit tests.
 */
object FormulaAccessibility {
    /**
     * The spoken description of a formula: the author-provided alternative
     * text when present, otherwise the raw latex truncated to the
     * accessibility budget.
     */
    fun spokenDescription(alternativeText: String, latex: String): String =
        alternativeText.ifBlank {
            "公式文本：${latex.take(StructuredContentLimits.MAX_ACCESSIBILITY_CHARS)}"
        }

    /** The full TalkBack contentDescription for a formula block. */
    fun contentDescription(spokenDescription: String): String =
        "公式（结构化渲染）：$spokenDescription"
}
