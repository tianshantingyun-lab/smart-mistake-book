package com.tingyun.smartmistakebook.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorProseSegmentsTest {
    @Test
    fun plainProseStaysASingleMarkdownSegment() {
        val segments = splitTutorProseSegments("先看定义域，再讨论单调区间。")

        assertEquals(listOf<TutorProseSegment>(TutorProseSegment.Markdown("先看定义域，再讨论单调区间。")), segments)
    }

    @Test
    fun displayMathBecomesItsOwnSegment() {
        val segments = splitTutorProseSegments("配方得\n\$\$x^{2}-5x+6=0\$\$\n所以两根为 2 和 3。")

        assertEquals(3, segments.size)
        assertEquals(TutorProseSegment.DisplayMath("x^{2}-5x+6=0"), segments[1])
        assertTrue((segments[0] as TutorProseSegment.Markdown).text.contains("配方得"))
        assertTrue((segments[2] as TutorProseSegment.Markdown).text.contains("两根"))
    }

    @Test
    fun inlineMathBecomesReadableTextInsideTheMarkdownSegment() {
        val segments = splitTutorProseSegments("当 \$x^{2}\$ 取最小值时，函数单调。")

        assertEquals(1, segments.size)
        val text = (segments.single() as TutorProseSegment.Markdown).text
        assertTrue("inline formula must be converted, got=\$text", text.contains("x²"))
        assertTrue(text.startsWith("当 "))
        assertTrue(text.endsWith(" 取最小值时，函数单调。"))
    }

    @Test
    fun anUnclosedDollarSignDoesNotSwallowTheRestOfTheAnswer() {
        val segments = splitTutorProseSegments("单价是 \$5，随后继续讲解第二问。")

        assertEquals(1, segments.size)
        val text = (segments.single() as TutorProseSegment.Markdown).text
        assertTrue(text.contains("随后继续讲解第二问"))
        assertEquals("单价是 \$5，随后继续讲解第二问。", text)
    }

    @Test
    fun escapedDollarAndCrossLineMathStayLiteral() {
        val escaped = splitTutorProseSegments("价格写成 \\\$5 的时候不要当成公式。")
        assertEquals(1, escaped.size)
        assertEquals(
            "价格写成 \\\$5 的时候不要当成公式。",
            (escaped.single() as TutorProseSegment.Markdown).text,
        )

        val crossLine = splitTutorProseSegments("公式 \$x+1\n继续这一行\$ 不应被当成一行内的公式。")
        assertEquals(1, crossLine.size)
        assertTrue((crossLine.single() as TutorProseSegment.Markdown).text.contains("继续这一行"))
    }

    @Test
    fun emptyMarkdownProducesNoSegments() {
        assertTrue(splitTutorProseSegments("").isEmpty())
    }
}
