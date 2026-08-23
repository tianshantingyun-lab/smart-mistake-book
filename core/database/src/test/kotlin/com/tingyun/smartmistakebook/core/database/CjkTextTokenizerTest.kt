package com.tingyun.smartmistakebook.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class CjkTextTokenizerTest {

    @Test
    fun segmentsCjkRunsIntoSingleCharacterTokens() {
        assertEquals("二 次 方 程 求 解", CjkTextTokenizer.segment("二次方程求解"))
    }

    @Test
    fun keepsLatinAndFormulaRunsWhole() {
        assertEquals("x^2 + 2x = 0", CjkTextTokenizer.segment("x^2 + 2x = 0"))
        assertEquals("abc123", CjkTextTokenizer.segment("abc123"))
    }

    @Test
    fun collapsesWhitespaceAndTrims() {
        assertEquals("二 次", CjkTextTokenizer.segment("  二次  "))
        assertEquals("", CjkTextTokenizer.segment("   "))
        assertEquals("", CjkTextTokenizer.segment(""))
    }

    @Test
    fun queryTokensAlwaysEqualIndexSideSegmentation() {
        for (text in listOf(
            "一元二次方程",
            "求x^2的解",
            "MATH 第三章",
            "勾股定理 a^2 + b^2 = c^2",
        )) {
            assertEquals(
                "token mismatch for: $text",
                CjkTextTokenizer.segment(text).split(' ').filter(String::isNotEmpty),
                CjkTextTokenizer.tokens(text),
            )
        }
    }

    @Test
    fun matchExpressionQuotesEveryTokenAsImplicitAnd() {
        assertEquals(
            "\"二\" \"次\" \"方\" \"程\"",
            CjkTextTokenizer.matchExpression("二次方程"),
        )
        assertEquals("\"abc\"", CjkTextTokenizer.matchExpression("abc"))
        assertEquals("\"abc\" \"123\"", CjkTextTokenizer.matchExpression("abc 123"))
    }

    @Test
    fun blankQueriesProduceNoMatchExpression() {
        assertEquals("", CjkTextTokenizer.matchExpression("   "))
        assertEquals(emptyList<String>(), CjkTextTokenizer.tokens(""))
    }

    @Test
    fun quotedPhraseEscapesEmbeddedQuotes() {
        assertEquals("\"a\"\"b\"", CjkTextTokenizer.quotedPhrase("a\"b"))
        assertEquals("\"因\"", CjkTextTokenizer.quotedPhrase("因"))
    }

    @Test
    fun shortLatinQueriesStaySingleTokens() {
        assertEquals(listOf("pi"), CjkTextTokenizer.tokens("pi"))
        assertEquals(listOf("x"), CjkTextTokenizer.tokens("x"))
    }
}
