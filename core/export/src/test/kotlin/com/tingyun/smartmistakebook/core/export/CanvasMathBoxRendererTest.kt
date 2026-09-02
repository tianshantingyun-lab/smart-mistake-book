package com.tingyun.smartmistakebook.core.export

import com.tingyun.smartmistakebook.core.model.MathBox
import com.tingyun.smartmistakebook.core.model.MathMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasMathBoxRendererTest {

    private val metrics = MathMetrics.of(13f)

    @Test
    fun `builds a box for a simple formula`() {
        val box = CanvasMathBoxRenderer.buildMathBox("f(x)=x^2-2x", metrics)

        assertNotNull(box)
        assertTrue(box!!.width > 0f)
        assertTrue(box.height > 0f)
    }

    @Test
    fun `fraction renders taller than a flat line`() {
        val fraction = CanvasMathBoxRenderer.buildMathBox("\\frac{1}{2}", metrics)
        val flat = CanvasMathBoxRenderer.buildMathBox("1/2", metrics)

        assertNotNull(fraction)
        assertNotNull(flat)
        assertTrue("fraction bar should make the box taller", fraction!!.height > flat!!.height)
    }

    @Test
    fun `radical matrix and cases all produce laid-out boxes`() {
        val formulas = listOf(
            "\\sqrt{x+1}",
            "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
            "\\begin{cases} x & x>0 \\\\ -x & x\\le 0 \\end{cases}",
        )

        formulas.forEach { formula ->
            assertNotNull("expected a box for $formula", CanvasMathBoxRenderer.buildMathBox(formula, metrics))
        }
    }

    @Test
    fun `oversized or unbalanced input degrades to null`() {
        assertNull(CanvasMathBoxRenderer.buildMathBox("x".repeat(2_100), metrics))
    }

    @Test
    fun `box content sizes scale with font metrics`() {
        val small = CanvasMathBoxRenderer.buildMathBox("\\frac{a}{b}", MathMetrics.of(10f))
        val large = CanvasMathBoxRenderer.buildMathBox("\\frac{a}{b}", MathMetrics.of(20f))

        assertNotNull(small)
        assertNotNull(large)
        assertTrue(large!!.width > small!!.width)
        assertTrue(large.height > small.height)
    }
}
