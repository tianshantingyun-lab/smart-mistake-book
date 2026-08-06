package com.tingyun.smartmistakebook.core.data.mistake

import org.junit.Assert.assertEquals
import org.junit.Test

class DerivedStudentMistakeSearchNormalizerTest {
    @Test
    fun matchesStudentAuthorityNfkcWhitespaceCaseAndCjkTokenSemantics() {
        val document =
            DerivedStudentMistakeSearchNormalizer.document(
                title = "  Ｆoo　函数 ",
                stemPreview = "求ＡＢＣ",
                practiceUnitTitle = "二次函数",
            )
        val query = DerivedStudentMistakeSearchNormalizer.query(" FOO　函数 ")

        assertEquals("foo 函数 求abc 二次函数", document.normalizedText)
        assertEquals("foo 函 数 求 abc 二 次 函 数", document.tokenizedText)
        assertEquals("foo 函数", query.normalizedText)
        assertEquals("foo* 函 数", query.ftsMatchExpression)
    }
}
