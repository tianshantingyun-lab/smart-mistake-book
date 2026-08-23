package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser coverage for \begin{cases}, aligned-family and matrix-family
 * environments, including malformed/unclosed input degradation.
 */
class MathEnvironmentParseTest {

    @Test
    fun `cases environment parses to Cases node`() {
        val node = MathParser.parse("\\begin{cases} 1 & x>0 \\\\ 0 & x \\le 0 \\end{cases}")
        assertTrue("expected Cases, got $node", node is MathNode.Cases)
        val cases = node as MathNode.Cases
        assertEquals(2, cases.rows.size)

        // Row 0: two cells -> joined into a Row(1, Row(x,>,0))
        val row0 = cases.rows[0] as MathNode.Row
        assertEquals(2, row0.items.size)
        assertEquals(MathNode.Atom("1"), row0.items[0])
        val condition0 = row0.items[1] as MathNode.Row
        assertEquals(
            listOf(MathNode.Atom("x"), MathNode.Atom(">"), MathNode.Atom("0")),
            condition0.items,
        )

        // Row 1: second condition uses \le
        val row1 = cases.rows[1] as MathNode.Row
        val condition1 = row1.items[1] as MathNode.Row
        assertTrue(condition1.items.contains(MathNode.Atom("≤")))
    }

    @Test
    fun `aligned environment parses to AlignedRows`() {
        val node = MathParser.parse("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}")
        assertTrue(node is MathNode.AlignedRows)
        val aligned = node as MathNode.AlignedRows
        assertEquals(2, aligned.rows.size)
        assertEquals(2, aligned.rows[0].size)
        assertEquals(MathNode.Atom("a"), aligned.rows[0][0])
        assertEquals(
            listOf(MathNode.Atom("="), MathNode.Atom(" "), MathNode.Atom("b")),
            (aligned.rows[0][1] as MathNode.Row).items,
        )
    }

    @Test
    fun `pmatrix environment parses to Matrix with paren delimiters`() {
        val node = MathParser.parse("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}")
        assertTrue(node is MathNode.Matrix)
        val matrix = node as MathNode.Matrix
        assertEquals("(", matrix.leftDelimiter)
        assertEquals(")", matrix.rightDelimiter)
        assertEquals(2, matrix.rows.size)
        assertEquals(
            listOf(MathNode.Atom("a"), MathNode.Atom("b")),
            matrix.rows[0],
        )
        assertEquals(
            listOf(MathNode.Atom("c"), MathNode.Atom("d")),
            matrix.rows[1],
        )
    }

    @Test
    fun `bmatrix environment uses bracket delimiters`() {
        val node = MathParser.parse("\\begin{bmatrix} 1 \\end{bmatrix}")
        val matrix = node as MathNode.Matrix
        assertEquals("[", matrix.leftDelimiter)
        assertEquals("]", matrix.rightDelimiter)
    }

    @Test
    fun `vmatrix environment uses bar delimiters`() {
        val node = MathParser.parse("\\begin{vmatrix} 1 \\end{vmatrix}")
        val matrix = node as MathNode.Matrix
        assertEquals("|", matrix.leftDelimiter)
        assertEquals("|", matrix.rightDelimiter)
    }

    @Test
    fun `environment cells can contain complex expressions`() {
        val node = MathParser.parse("\\begin{cases} \\frac{a}{b} & x^2 \\end{cases}")
        val cases = node as MathNode.Cases
        val row = cases.rows[0] as MathNode.Row
        assertTrue(row.items[0] is MathNode.Fraction)
        assertTrue(row.items[1] is MathNode.Superscript)
    }

    @Test
    fun `unclosed environment degrades gracefully`() {
        val node = MathParser.parse("\\begin{cases} x \\\\ y")
        assertTrue(node is MathNode.Cases)
        assertEquals(2, (node as MathNode.Cases).rows.size)
    }

    @Test
    fun `stray end without begin degrades to empty`() {
        val node = MathParser.parse("\\end{cases}")
        assertTrue(node is MathNode.Row)
        assertTrue((node as MathNode.Row).items.isEmpty())
    }

    @Test
    fun `unknown environment degrades to aligned rows`() {
        val node = MathParser.parse("\\begin{foo} a \\\\ b \\end{foo}")
        assertTrue(node is MathNode.AlignedRows)
        assertEquals(2, (node as MathNode.AlignedRows).rows.size)
    }

    @Test
    fun `align and gather aliases produce AlignedRows`() {
        assertTrue(MathParser.parse("\\begin{align} a \\end{align}") is MathNode.AlignedRows)
        assertTrue(MathParser.parse("\\begin{gather} a \\end{gather}") is MathNode.AlignedRows)
    }

    @Test
    fun `environments nest inside other constructs`() {
        val node = MathParser.parse("\\frac{\\begin{cases} 1 \\\\ 2 \\end{cases}}{x}")
        assertTrue(node is MathNode.Fraction)
        val fraction = node as MathNode.Fraction
        assertTrue(fraction.numerator is MathNode.Cases)
        assertEquals(MathNode.Atom("x"), fraction.denominator)
    }

    @Test
    fun `cases renders with brace prefix and semicolon separators`() {
        val rendered = MathRenderer.render(
            MathParser.parse("\\begin{cases} 1 \\\\ 2 \\end{cases}"),
        )
        assertTrue(rendered.startsWith("{ "))
        assertTrue(rendered.contains("1; 2"))
    }

    @Test
    fun `aligned renders rows with double backslash separators`() {
        val rendered = MathRenderer.render(
            MathParser.parse("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}"),
        )
        assertTrue(rendered.contains("\\\\"))
        assertTrue(rendered.contains("&"))
    }

    @Test
    fun `stray separators outside environments never loop or crash`() {
        // '&' and '\\' outside any environment must terminate and degrade.
        val result = parseAndBuildBox("a & b")
        // Parses only the segment before the stray separator; must converge.
        assertTrue(result.isSuccess || result.error != null)
        val result2 = parseAndBuildBox("a \\\\ b")
        assertTrue(result2.isSuccess || result2.error != null)
    }

    @Test
    fun `cases and aligned flow through the full budgeted pipeline`() {
        val casesResult = parseAndBuildBox("\\begin{cases} x & x>0 \\\\ -x & x \\le 0 \\end{cases}")
        assertTrue(casesResult.isSuccess)
        val alignedResult = parseAndBuildBox("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}")
        assertTrue(alignedResult.isSuccess)
        val matrixResult = parseAndBuildBox("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}")
        assertTrue(matrixResult.isSuccess)
    }
}
