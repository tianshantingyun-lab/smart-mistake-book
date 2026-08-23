package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TalkBack semantics for formula blocks, asserted on the extracted pure
 * functions ([FormulaAccessibility]). The FormulaBlock composable in core:ui
 * delegates to exactly these functions, so these assertions cover the
 * production contentDescription generation without Compose test infra.
 */
class FormulaAccessibilityTest {

    @Test
    fun `contentDescription uses the fixed spoken prefix`() {
        assertEquals(
            "公式（结构化渲染）：x^2",
            FormulaAccessibility.contentDescription("x^2"),
        )
    }

    @Test
    fun `alternative text takes precedence over latex`() {
        assertEquals(
            "读作：二分之一的平方",
            FormulaAccessibility.spokenDescription("读作：二分之一的平方", "\\frac{1}{2}^2"),
        )
    }

    @Test
    fun `blank alternative text falls back to the latex source`() {
        assertEquals("公式文本：x^2", FormulaAccessibility.spokenDescription("", "x^2"))
        assertEquals("公式文本：x^2", FormulaAccessibility.spokenDescription("   ", "x^2"))
    }

    @Test
    fun `latex fallback is truncated to the accessibility budget`() {
        val latex = "a".repeat(StructuredContentLimits.MAX_ACCESSIBILITY_CHARS + 88)
        val description = FormulaAccessibility.spokenDescription("", latex)
        assertEquals(
            "公式文本：" + "a".repeat(StructuredContentLimits.MAX_ACCESSIBILITY_CHARS),
            description,
        )
    }

    @Test
    fun `full formula block description composes both parts`() {
        val full = FormulaAccessibility.contentDescription(
            FormulaAccessibility.spokenDescription("", "\\frac{a}{b}"),
        )
        assertTrue(full.startsWith("公式（结构化渲染）：公式文本："))
        assertTrue(full.contains("\\frac{a}{b}"))
    }
}
