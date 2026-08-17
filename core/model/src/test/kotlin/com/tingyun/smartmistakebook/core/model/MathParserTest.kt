package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathParserTest {

    @Test
    fun `parse simple atom`() {
        val node = MathParser.parse("x")
        assertTrue(node is MathNode.Atom)
        assertEquals("x", (node as MathNode.Atom).symbol)
    }

    @Test
    fun `parse greek letter`() {
        val node = MathParser.parse("\\alpha")
        assertTrue(node is MathNode.Atom)
        assertEquals("α", (node as MathNode.Atom).symbol)
    }

    @Test
    fun `parse fraction`() {
        val node = MathParser.parse("\\frac{a}{b}")
        assertTrue(node is MathNode.Fraction)
        val frac = node as MathNode.Fraction
        assertTrue(frac.numerator is MathNode.Atom)
        assertTrue(frac.denominator is MathNode.Atom)
    }

    @Test
    fun `parse square root`() {
        val node = MathParser.parse("\\sqrt{x}")
        assertTrue(node is MathNode.Radical)
    }

    @Test
    fun `parse superscript`() {
        val node = MathParser.parse("x^2")
        assertTrue(node is MathNode.Superscript)
    }

    @Test
    fun `parse subscript`() {
        val node = MathParser.parse("x_1")
        assertTrue(node is MathNode.Subscript)
    }

    @Test
    fun `parse nested fraction`() {
        val node = MathParser.parse("\\frac{\\frac{a}{b}}{c}")
        assertTrue(node is MathNode.Fraction)
        val frac = node as MathNode.Fraction
        assertTrue(frac.numerator is MathNode.Fraction)
    }

    @Test
    fun `render simple fraction to readable text`() {
        val result = ReadableMathText.formula("\\frac{a}{b}")
        assertEquals("(a)/(b)", result)
    }

    @Test
    fun `render square root to readable text`() {
        val result = ReadableMathText.formula("\\sqrt{x}")
        assertEquals("√(x)", result)
    }

    @Test
    fun `render superscript to unicode`() {
        val result = ReadableMathText.formula("x^2")
        assertEquals("x²", result)
    }

    @Test
    fun `render subscript to unicode`() {
        val result = ReadableMathText.formula("x_1")
        assertEquals("x₁", result)
    }

    @Test
    fun `render greek letter`() {
        val result = ReadableMathText.formula("\\alpha + \\beta")
        assertEquals("α + β", result)
    }

    @Test
    fun `render complex formula`() {
        val result = ReadableMathText.formula("\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}")
        assertTrue(result.contains("√"))
        assertTrue(result.contains("±"))
    }

    @Test
    fun `render trigonometric function`() {
        val result = ReadableMathText.formula("\\sin^2(x) + \\cos^2(x) = 1")
        assertTrue(result.contains("sin"))
        assertTrue(result.contains("cos"))
    }

    @Test
    fun `render set notation`() {
        val result = ReadableMathText.formula("A \\cup B")
        assertTrue(result.contains("∪"))
    }

    @Test
    fun `render comparison operators`() {
        val result = ReadableMathText.formula("a \\leq b \\leq c")
        assertTrue(result.contains("≤"))
    }

    @Test
    fun `render arrow operators`() {
        val result = ReadableMathText.formula("a \\rightarrow b")
        assertTrue(result.contains("→"))
    }

    @Test
    fun `render integral`() {
        val result = ReadableMathText.formula("\\int_0^1 x dx")
        assertTrue(result.contains("∫"))
    }

    @Test
    fun `render summation`() {
        val result = ReadableMathText.formula("\\sum_{i=1}^n i")
        assertTrue(result.contains("∑"))
    }

    @Test
    fun `render product`() {
        val result = ReadableMathText.formula("\\prod_{i=1}^n i")
        assertTrue(result.contains("∏"))
    }

    @Test
    fun `malformed input falls back to legacy`() {
        val result = ReadableMathText.formula("\\frac{unclosed")
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `render vector notation`() {
        val result = ReadableMathText.formula("\\vec{v}")
        assertTrue(result.contains("⃗"))
    }

    @Test
    fun `render overline`() {
        val result = ReadableMathText.formula("\\overline{AB}")
        assertTrue(result.contains("̅"))
    }
}
