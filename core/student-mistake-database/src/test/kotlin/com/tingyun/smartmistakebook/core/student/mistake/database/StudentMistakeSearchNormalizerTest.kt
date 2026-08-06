package com.tingyun.smartmistakebook.core.student.mistake.database

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeSearchNormalizerTest {
    @Test
    fun normalizationIsLocaleIndependentAndCompatibilityAware() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals(
                "integer 函数 abc 123",
                StudentMistakeSearchNormalizer.normalize("ＩＮＴＥＧＥＲ  函数　ＡＢＣ １２３"),
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun querySeparatesCjkAndWordTokensWithoutUsingSqliteNocase() {
        val query = StudentMistakeSearchNormalizer.query("函数 ABC")
        assertEquals("函数 abc", query.normalizedText)
        assertEquals("函 数 abc*", query.ftsMatchExpression)
        assertTrue("函*" !in query.ftsMatchExpression)
        assertTrue("NOCASE" !in query.ftsMatchExpression)
    }

    @Test
    fun sourceFingerprintChangesWithAnySearchableSource() {
        val first =
            StudentMistakeSearchNormalizer.document(
                revisionId = "revision-1",
                title = "函数",
                stemMarkdown = "求值",
                practiceUnitTitle = "第一问",
            )
        val changed =
            StudentMistakeSearchNormalizer.document(
                revisionId = "revision-1",
                title = "函数",
                stemMarkdown = "求最值",
                practiceUnitTitle = "第一问",
            )
        assertNotEquals(first.sourceCanonicalFingerprint, changed.sourceCanonicalFingerprint)
    }
}
