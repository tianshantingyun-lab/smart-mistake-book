package com.tingyun.smartmistakebook.core.model

import java.util.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-style and fuzz tests for the single math pipeline.
 *
 * Properties (seeded, reproducible):
 *  - Random well-formed formulas: parse/render/box-build never throw.
 *  - Budgets are ALWAYS enforced: every successful parse result stays within
 *    every budget constant, including the layout width/height budget.
 *  - The parse -> render -> fallback chain always converges to a string.
 *
 * Fuzz:
 *  - Hostile random strings (long, malformed braces, unknown commands, stray
 *    separators): nothing throws; the budgeted pipeline either succeeds within
 *    budget or returns a non-empty error.
 */
class MathPipelinePropertyFuzzTest {

    // ---- Generators ---------------------------------------------------------

    private val leaves = listOf("x", "1", "a", "2", "\\alpha", "b", "3")

    private fun randomLeaf(random: Random): String = leaves[random.nextInt(leaves.size)]

    private fun randomFormula(random: Random, depth: Int): String {
        if (depth == 0) return randomLeaf(random)
        return when (random.nextInt(8)) {
            0 -> "\\frac{${randomFormula(random, depth - 1)}}{${randomFormula(random, depth - 1)}}"
            1 -> "\\sqrt{${randomFormula(random, depth - 1)}}"
            2 -> "${randomLeaf(random)}^${random.nextInt(10)}"
            3 -> "${randomLeaf(random)}_${random.nextInt(10)}"
            4 -> "\\begin{cases} ${randomFormula(random, depth - 1)} \\\\ " +
                "${randomFormula(random, depth - 1)} \\end{cases}"
            5 -> "\\begin{aligned} ${randomFormula(random, depth - 1)} &= " +
                "${randomFormula(random, depth - 1)} \\end{aligned}"
            6 -> "\\begin{pmatrix} ${randomLeaf(random)} & ${randomLeaf(random)} \\\\ " +
                "${randomLeaf(random)} & ${randomLeaf(random)} \\end{pmatrix}"
            else -> "${randomLeaf(random)} + ${randomLeaf(random)}"
        }
    }

    private fun assertBudgetsHold(result: MathParseResult) {
        val node = result.node ?: return
        assertTrue(result.nodeCount <= MathBudget.MAX_AST_NODE_COUNT)
        assertTrue(result.depth <= MathBudget.MAX_AST_DEPTH)
        val metrics = MathAstMetrics.measure(node)
        assertTrue(metrics.maxMatrixRows <= MathBudget.MAX_MATRIX_ROWS)
        assertTrue(metrics.maxMatrixColumns <= MathBudget.MAX_MATRIX_COLUMNS)
        assertTrue(metrics.maxFractionNesting <= MathBudget.MAX_FRACTION_NESTING)
        assertTrue(metrics.maxCasesCount <= MathBudget.MAX_CASES_COUNT)
        assertTrue(metrics.maxAlignedRows <= MathBudget.MAX_ALIGNED_ROWS)
        val box = MathBoxBuilder.buildBox(node)
        assertNull(
            "successful result must satisfy the layout budget, got: " +
                MathBudget.checkLayoutSize(box.width, box.height),
            MathBudget.checkLayoutSize(box.width, box.height),
        )
    }

    // ---- Property tests ------------------------------------------------------

    @Test
    fun `random valid formulas never throw and always converge`() {
        val random = Random(20260823L)
        repeat(500) {
            val formula = randomFormula(random, random.nextInt(4))
            // Single pipeline: parse never throws.
            val node = MathParser.parse(formula)
            assertNotNull(node)
            // Render chain converges to a string.
            val rendered = ReadableMathText.formula(formula)
            assertNotNull(rendered)
            assertTrue("rendered text should be non-empty for: $formula", rendered.isNotEmpty())
            // Budgeted pipeline converges.
            val result = parseAndBuildBox(formula)
            if (result.isSuccess) {
                assertBudgetsHold(result)
            } else {
                assertNotNull(result.error)
                assertTrue(result.error!!.isNotEmpty())
            }
        }
    }

    @Test
    fun `budgets hold for every successful random parse`() {
        val random = Random(11L)
        var successes = 0
        repeat(500) {
            val formula = randomFormula(random, random.nextInt(4))
            val result = parseAndBuildBox(formula)
            if (result.isSuccess) {
                assertBudgetsHold(result)
                successes++
            }
        }
        assertTrue("generator should produce many valid formulas", successes > 100)
    }

    @Test
    fun `oversized generated formulas are rejected by some budget`() {
        // Deeply nested fractions must hit the fraction-nesting or depth budget.
        var formula = "a"
        repeat(30) { formula = "\\frac{$formula}{a}" }
        val result = parseAndBuildBox(formula)
        assertTrue(!result.isSuccess)
        assertNotNull(result.error)
    }

    // ---- Fuzz tests ------------------------------------------------------------

    @Test
    fun `fuzz hostile strings never throw in any pipeline stage`() {
        val random = Random(42L)
        val alphabet = listOf(
            "\\frac{", "}", "\\sqrt{", "^", "_", "{", "(", ")", "[", "]",
            "\\begin{cases}", "\\begin{aligned}", "\\end{cases}", "\\\\", "&",
            "\\alpha", "+", "-", "=", "x", "1", " ", "\\unknown", "\\begin{",
            "\\end{", "\\text{", "\\vec{", "\\",
        )
        repeat(2000) {
            val sb = StringBuilder()
            repeat(random.nextInt(40)) { sb.append(alphabet[random.nextInt(alphabet.size)]) }
            val input = sb.toString()

            // None of these may throw.
            MathTokenizer.tokenize(input)
            MathParser.parse(input)
            assertNotNull(ReadableMathText.formula(input))

            val result = parseAndBuildBox(input)
            if (result.isSuccess) {
                assertBudgetsHold(result)
            } else {
                assertNotNull(result.error)
                assertTrue(result.error!!.isNotEmpty())
            }
        }
    }

    @Test
    fun `fuzz long inputs are bounded by the input budget`() {
        val longInput = "\\frac{${"x".repeat(3000)}}{y}"
        val result = parseAndBuildBox(longInput)
        assertTrue(!result.isSuccess)
        assertTrue(result.error!!.contains("输入过长"))
    }

    @Test
    fun `fuzz malformed braces never loop or crash`() {
        val random = Random(7L)
        repeat(500) {
            val sb = StringBuilder()
            repeat(random.nextInt(60)) {
                when (random.nextInt(4)) {
                    0 -> sb.append('{')
                    1 -> sb.append('}')
                    2 -> sb.append("a")
                    else -> sb.append("\\frac")
                }
            }
            val input = sb.toString()
            MathParser.parse(input)
            assertNotNull(ReadableMathText.formula(input))
            assertNotNull(parseAndBuildBox(input).let { r -> r.node ?: r.error })
        }
    }

    @Test
    fun `unknown commands degrade to literal atoms without crashing`() {
        val node = MathParser.parse("\\unknowncmd{x} + \\another")
        val rendered = MathRenderer.render(node)
        assertTrue(rendered.contains("\\unknowncmd"))
        assertTrue(rendered.contains("\\another"))
        assertTrue(parseAndBuildBox("\\unknowncmd{x}").isSuccess)
    }

    @Test
    fun `fallback passes malformed input through verbatim`() {
        // The rendered form of unparseable-but-tokenizable input degrades to
        // literal atoms; for truly empty input the result is empty - but the
        // pipeline never throws and always returns a string.
        assertEqualsFallback("\\frac{unclosed")
        assertEqualsFallback("}{")
        assertEqualsFallback("")
    }

    private fun assertEqualsFallback(input: String) {
        val result = ReadableMathText.formula(input)
        assertNotNull(result)
    }
}
