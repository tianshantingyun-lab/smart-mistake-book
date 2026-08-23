package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests: fixed formulas must produce fixed AST structures and fixed
 * box dimensions (at the default 16px metrics). Any change to parser output
 * or the layout algorithm shows up here as an explicit snapshot diff.
 */
class MathBoxGoldenTest {

    private val delta = 0.001f

    // ---- AST goldens -------------------------------------------------------

    @Test
    fun `golden ast - fraction`() {
        assertEquals(
            MathNode.Fraction(MathNode.Atom("1"), MathNode.Atom("2")),
            MathParser.parse("\\frac{1}{2}"),
        )
    }

    @Test
    fun `golden ast - radical`() {
        assertEquals(
            MathNode.Radical(MathNode.Atom("x")),
            MathParser.parse("\\sqrt{x}"),
        )
    }

    @Test
    fun `golden ast - pmatrix`() {
        assertEquals(
            MathNode.Matrix(
                rows = listOf(
                    listOf(MathNode.Atom("a"), MathNode.Atom("b")),
                    listOf(MathNode.Atom("c"), MathNode.Atom("d")),
                ),
                leftDelimiter = "(",
                rightDelimiter = ")",
            ),
            MathParser.parse("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}"),
        )
    }

    @Test
    fun `golden ast - cases`() {
        assertEquals(
            MathNode.Cases(listOf(MathNode.Atom("a"), MathNode.Atom("b"))),
            MathParser.parse("\\begin{cases} a \\\\ b \\end{cases}"),
        )
    }

    @Test
    fun `golden ast - superscript`() {
        assertEquals(
            MathNode.Superscript(MathNode.Atom("x"), MathNode.Atom("2")),
            MathParser.parse("x^2"),
        )
    }

    // ---- Box dimension goldens (DEFAULT metrics: 16px font) ---------------

    @Test
    fun `golden box - atom`() {
        val box = MathBoxBuilder.buildBox(MathParser.parse("x"))
        assertTrue(box is MathBox.Leaf)
        assertEquals(8f, box.width, delta)
        assertEquals(16f, box.height, delta)
        assertEquals(6.4f, box.baseline, delta)
        assertEquals("x", (box as MathBox.Leaf).content)
    }

    @Test
    fun `golden box - fraction`() {
        val box = MathBoxBuilder.buildBox(MathParser.parse("\\frac{1}{2}"))
        assertTrue(box is MathBox.FractionBox)
        // width = max(8,8) + 2*8; height = 16+16+4+1; baseline = 16+2+0.5
        assertEquals(24f, box.width, delta)
        assertEquals(37f, box.height, delta)
        assertEquals(18.5f, box.baseline, delta)
    }

    @Test
    fun `golden box - radical`() {
        val box = MathBoxBuilder.buildBox(MathParser.parse("\\sqrt{x}"))
        assertTrue(box is MathBox.RadicalBox)
        // width = 8 + 2*8; height = 16 + 4; baseline = 6.4 + 4
        assertEquals(24f, box.width, delta)
        assertEquals(20f, box.height, delta)
        assertEquals(10.4f, box.baseline, delta)
    }

    @Test
    fun `golden box - matrix grid`() {
        val box = MathBoxBuilder.buildBox(
            MathParser.parse("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}"),
        )
        assertTrue(box is MathBox.MatrixBox)
        val matrix = box as MathBox.MatrixBox
        assertEquals(2, matrix.rows.size)
        assertEquals(2, matrix.rows[0].size)
        // width = 8+8 (columns) + 8 (gap) + 16 (delimiters)
        assertEquals(40f, matrix.width, delta)
        // height = 16*2 + 4 spacing
        assertEquals(36f, matrix.height, delta)
        assertEquals(18f, matrix.baseline, delta)
    }

    @Test
    fun `golden box - cases`() {
        val box = MathBoxBuilder.buildBox(
            MathParser.parse("\\begin{cases} a \\\\ b \\end{cases}"),
        )
        assertTrue(box is MathBox.CasesBox)
        val cases = box as MathBox.CasesBox
        assertEquals(2, cases.rows.size)
        assertEquals(4f, cases.rowGap, delta)
        // width = brace(12) + row(8) + padding(8)
        assertEquals(28f, cases.width, delta)
        // height = 16 + 16 + 4 spacing
        assertEquals(36f, cases.height, delta)
        assertEquals(18f, cases.baseline, delta)
    }

    @Test
    fun `golden box - aligned rows`() {
        val box = MathBoxBuilder.buildBox(
            MathParser.parse("\\begin{aligned} a \\\\ b \\end{aligned}"),
        )
        assertTrue(box is MathBox.AlignedBox)
        val aligned = box as MathBox.AlignedBox
        assertEquals(2, aligned.rows.size)
        assertEquals(8f, aligned.width, delta)
        assertEquals(36f, aligned.height, delta)
    }

    // ---- Rendered-text goldens ---------------------------------------------

    @Test
    fun `golden render - fraction`() {
        assertEquals("(1)/(2)", ReadableMathText.formula("\\frac{1}{2}"))
    }

    @Test
    fun `golden render - radical`() {
        assertEquals("√(x)", ReadableMathText.formula("\\sqrt{x}"))
    }

    @Test
    fun `golden render - superscript`() {
        assertEquals("x²", ReadableMathText.formula("x^2"))
    }

    @Test
    fun `golden render - cases`() {
        assertEquals(
            "{ 1; 0",
            ReadableMathText.formula("\\begin{cases} 1 \\\\ 0 \\end{cases}"),
        )
    }

    // ---- Pipeline goldens ----------------------------------------------------

    @Test
    fun `golden pipeline - quadratic formula parses within budget`() {
        val result = parseAndBuildBox("\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}")
        assertTrue(result.isSuccess)
        assertTrue(result.node is MathNode.Fraction)
        assertTrue(result.nodeCount <= MathBudget.MAX_AST_NODE_COUNT)
        assertTrue(result.depth <= MathBudget.MAX_AST_DEPTH)
    }
}
