package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that box dimensions are fully parameterized by [MathMetrics]
 * (font-size driven) instead of the old fixed 8f/16f constants, including
 * the 200% system-font scenario.
 */
class MathBoxScalingTest {

    private val delta = 0.001f

    @Test
    fun `default metrics reproduce the legacy constants exactly`() {
        val metrics = MathMetrics.DEFAULT
        assertEquals(8f, metrics.charWidth, delta)
        assertEquals(16f, metrics.charHeight, delta)
        assertEquals(4f, metrics.lineSpacing, delta)
        assertEquals(1f, metrics.fracLineThickness, delta)
        assertEquals(4f, metrics.radicalExtraHeight, delta)
        assertEquals(MathMetrics.of(16f), metrics)
    }

    @Test
    fun `metrics derive linearly from font size`() {
        val doubled = MathMetrics.of(32f)
        assertEquals(MathMetrics.DEFAULT.charWidth * 2f, doubled.charWidth, delta)
        assertEquals(MathMetrics.DEFAULT.charHeight * 2f, doubled.charHeight, delta)
        assertEquals(MathMetrics.DEFAULT.lineSpacing * 2f, doubled.lineSpacing, delta)
        assertEquals(MathMetrics.DEFAULT.fracLineThickness * 2f, doubled.fracLineThickness, delta)
        assertEquals(MathMetrics.DEFAULT.radicalExtraHeight * 2f, doubled.radicalExtraHeight, delta)
    }

    @Test
    fun `invalid font sizes fall back to the default`() {
        assertEquals(MathMetrics.DEFAULT, MathMetrics.of(Float.NaN))
        assertEquals(MathMetrics.DEFAULT, MathMetrics.of(0f))
        assertEquals(MathMetrics.DEFAULT, MathMetrics.of(-4f))
        assertEquals(MathMetrics.DEFAULT, MathMetrics.of(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `200 percent font scale doubles every box dimension`() {
        // Simulates the system font scale of 2.0: the composable converts
        // 16.sp to 32px and derives metrics from that value.
        val formulas = listOf(
            "x",
            "\\frac{1}{2}",
            "\\sqrt{x}",
            "x^2",
            "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
            "\\begin{cases} a \\\\ b \\end{cases}",
            "\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}",
            "\\frac{\\sqrt{x}^2}{y_1}",
        )
        val doubleMetrics = MathMetrics.of(16f * 2f)
        formulas.forEach { formula ->
            val node = MathParser.parse(formula)
            val base = MathBoxBuilder.buildBox(node)
            val scaled = MathBoxBuilder.buildBox(node, doubleMetrics)
            assertEquals(
                "width should double for: $formula",
                base.width * 2f, scaled.width, delta,
            )
            assertEquals(
                "height should double for: $formula",
                base.height * 2f, scaled.height, delta,
            )
            assertEquals(
                "baseline should double for: $formula",
                base.baseline * 2f, scaled.baseline, delta,
            )
        }
    }

    @Test
    fun `arbitrary font sizes scale proportionally`() {
        val node = MathParser.parse("\\frac{a+b}{\\sqrt{c}}")
        val base = MathBoxBuilder.buildBox(node)
        val metrics = MathMetrics.of(24f)
        val scaled = MathBoxBuilder.buildBox(node, metrics)
        val factor = 24f / 16f
        assertEquals(base.width * factor, scaled.width, delta)
        assertEquals(base.height * factor, scaled.height, delta)
    }

    @Test
    fun `buildBox keeps legacy dimensions with default metrics`() {
        // Guards against accidental dimension drift for existing production
        // formulas rendered at the default font size.
        val box = MathBoxBuilder.buildBox(MathParser.parse("\\frac{1}{2}"))
        assertEquals(24f, box.width, delta)
        assertEquals(37f, box.height, delta)
    }
}
