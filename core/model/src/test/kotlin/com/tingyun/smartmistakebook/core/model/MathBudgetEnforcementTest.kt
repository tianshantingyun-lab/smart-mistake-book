package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that EVERY budget constant in [MathBudget] is actually enforced by
 * [parseAndBuildBox]/[MathBudget.checkBudget]. Violations degrade to an error
 * result (text fallback path); nothing throws.
 */
class MathBudgetEnforcementTest {

    private fun resultOf(input: String): MathParseResult = parseAndBuildBox(input)

    private fun assertBudgetError(input: String, messagePart: String): MathParseResult {
        val result = resultOf(input)
        assertNull("expected a budget violation for: $input", result.node)
        assertNotNull(result.error)
        assertTrue(
            "error '${result.error}' should mention '$messagePart'",
            result.error!!.contains(messagePart),
        )
        return result
    }

    @Test
    fun `input length budget is enforced`() {
        assertBudgetError("x".repeat(MathBudget.MAX_INPUT_LENGTH + 1), "输入过长")
    }

    @Test
    fun `token count budget is enforced`() {
        // 300 * "x+" = 600 tokens, well under the char limit.
        assertBudgetError("x+".repeat(300), "token 数过多")
    }

    @Test
    fun `ast node count budget is enforced via checkBudget`() {
        // Node counts are bounded by the token budget in practice, so exercise
        // the pure validator directly.
        val error = MathBudget.checkBudget(
            input = "x",
            tokenCount = 10,
            astNodeCount = MathBudget.MAX_AST_NODE_COUNT + 1,
            astDepth = 5,
        )
        assertNotNull(error)
        assertTrue(error!!.contains("结构过复杂"))
        assertNull(
            MathBudget.checkBudget("x", 10, MathBudget.MAX_AST_NODE_COUNT, 5),
        )
    }

    @Test
    fun `ast depth budget is enforced`() {
        var formula = "a"
        repeat(MathBudget.MAX_AST_DEPTH + 1) {
            formula = "\\frac{$formula}{a}"
        }
        assertBudgetError(formula, "嵌套过深")
    }

    @Test
    fun `matrix row budget is enforced`() {
        val rows = MathBudget.MAX_MATRIX_ROWS + 1
        val body = "a" + "\\\\a".repeat(rows - 1)
        assertBudgetError("\\begin{matrix}$body\\end{matrix}", "矩阵行数过多")
    }

    @Test
    fun `matrix column budget is enforced`() {
        val columns = MathBudget.MAX_MATRIX_COLUMNS + 1
        val body = "a" + "&a".repeat(columns - 1)
        assertBudgetError("\\begin{matrix}$body\\end{matrix}", "矩阵列数过多")
    }

    @Test
    fun `fraction nesting budget is enforced`() {
        var formula = "a"
        repeat(MathBudget.MAX_FRACTION_NESTING + 1) {
            formula = "\\frac{$formula}{a}"
        }
        // Depth here is only MAX_FRACTION_NESTING + 2, so the fraction
        // nesting check (not the depth check) must fire.
        assertBudgetError(formula, "嵌套分数过深")
    }

    @Test
    fun `cases count budget is enforced`() {
        val rows = MathBudget.MAX_CASES_COUNT + 1
        val body = "x" + "\\\\x".repeat(rows - 1)
        assertBudgetError("\\begin{cases}$body\\end{cases}", "分段项数过多")
    }

    @Test
    fun `aligned row budget is enforced`() {
        val rows = MathBudget.MAX_ALIGNED_ROWS + 1
        val body = "x" + "\\\\x".repeat(rows - 1)
        assertBudgetError("\\begin{aligned}$body\\end{aligned}", "对齐行数过多")
    }

    @Test
    fun `layout width budget is enforced`() {
        // 240 atoms + 239 spaces = 479 tokens; the inter-item gaps push the
        // laid-out width past MAX_BOX_WIDTH_CHARS.
        val formula = ("x ".repeat(239)) + "x"
        assertBudgetError(formula, "布局过宽")
    }

    @Test
    fun `layout height budget is enforced`() {
        // 50 aligned rows of 3-level stacked fractions: tall but within every
        // structural budget, so the layout height check must be the one firing.
        val row = "\\frac{\\frac{a}{a}}{a}"
        val body = row + "\\\\$row".repeat(MathBudget.MAX_ALIGNED_ROWS - 1)
        assertBudgetError("\\begin{aligned}$body\\end{aligned}", "布局过高")
    }

    @Test
    fun `normal formulas pass all budgets`() {
        val result = resultOf("\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}")
        assertNull(result.error)
        assertNotNull(result.node)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `checkBudget returns null when all constraints hold`() {
        assertNull(MathBudget.checkBudget("x", 1, 1, 1, MathAstMetrics.EMPTY))
    }

    @Test
    fun `checkLayoutSize rejects non finite dimensions`() {
        assertNotNull(MathBudget.checkLayoutSize(Float.NaN, 10f))
        assertNotNull(MathBudget.checkLayoutSize(10f, Float.POSITIVE_INFINITY))
        assertNotNull(MathBudget.checkLayoutSize(-1f, 10f))
        assertNull(MathBudget.checkLayoutSize(10f, 10f))
    }

    @Test
    fun `checkLayoutSize scales with metrics`() {
        // 6400px wide: over budget at 16px metrics (800 chars > 512),
        // but the limit scales with the font size, so at 32px metrics
        // (400 chars) it passes.
        assertNotNull(MathBudget.checkLayoutSize(6400f, 10f, MathMetrics.DEFAULT))
        assertNull(MathBudget.checkLayoutSize(6400f, 10f, MathMetrics.of(32f)))
    }

    @Test
    fun `ast metrics measure all structural dimensions`() {
        val node = MathParser.parse(
            "\\frac{\\begin{pmatrix} a & b & c \\\\ d & e & f \\end{pmatrix}}" +
                "{\\begin{cases} 1 \\\\ 2 \\\\ 3 \\end{cases}}",
        )
        val metrics = MathAstMetrics.measure(node)
        assertEquals(2, metrics.maxMatrixRows)
        assertEquals(3, metrics.maxMatrixColumns)
        assertEquals(1, metrics.maxFractionNesting)
        assertEquals(3, metrics.maxCasesCount)
        assertEquals(0, metrics.maxAlignedRows)
    }

    @Test
    fun `metrics measure fraction nesting through containers`() {
        val node = MathParser.parse("\\frac{a}{\\sqrt{\\frac{b}{c}}}")
        assertEquals(2, MathAstMetrics.measure(node).maxFractionNesting)
    }
}
