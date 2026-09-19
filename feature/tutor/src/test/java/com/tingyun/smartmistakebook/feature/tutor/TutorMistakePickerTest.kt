package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加号里「从错题库选择」的筛选与身份映射。
 *
 * 这条入口此前只是跳到错题本页（`Routes.Library` 没有参数、列表也没有选择回调），选完不返回
 * 任何东西；现在它在当前页里挑题并把题的身份交给讲题会话，所以"筛出来的确实是学生认得出的
 * 那几道题"和"身份映射一字不差"这两件事必须钉住。
 */
class TutorMistakePickerTest {

    private fun entry(
        problemId: String,
        title: String,
        subject: String = "MATH",
        chapterLabels: List<String> = emptyList(),
        knowledgeLabels: List<String> = emptyList(),
    ) = StudyCatalogEntry(
        entryId = "entry-$problemId",
        problemId = problemId,
        problemRevisionId = "$problemId-rev-2",
        practiceUnitId = "unit-$problemId",
        subject = subject,
        title = title,
        problemMarkdown = "题干 $title",
        sourceKey = null,
        isCuratedExample = false,
        chapterLabels = chapterLabels,
        knowledgeLabels = knowledgeLabels,
        nextReviewAtEpochMillis = null,
        retrievability = null,
    )

    private val entries = listOf(
        entry("p1", "求函数的单调区间", chapterLabels = listOf("导数及其应用")),
        entry("p2", "自由落体运动的位移", subject = "PHYSICS", knowledgeLabels = listOf("自由落体运动")),
        entry("p3", "Fe 与稀硝酸的反应", subject = "CHEMISTRY", knowledgeLabels = listOf("铁及其化合物")),
    )

    @Test
    fun anEmptyQueryKeepsEveryCandidate() {
        assertEquals(entries, filterMistakeCandidates(entries, ""))
        assertEquals("只有空白也不该筛掉任何题", entries, filterMistakeCandidates(entries, "   "))
    }

    @Test
    fun theTitleMatchesCaseInsensitively() {
        val matched = filterMistakeCandidates(entries, "FE 与稀硝酸")

        assertEquals(listOf("p3"), matched.map { it.problemId })
    }

    @Test
    fun chapterAndKnowledgeLabelsAreSearchable() {
        assertEquals(
            listOf("p1"),
            filterMistakeCandidates(entries, "导数").map { it.problemId },
        )
        assertEquals(
            listOf("p2"),
            filterMistakeCandidates(entries, "自由落体").map { it.problemId },
        )
        assertEquals(
            listOf("p3"),
            filterMistakeCandidates(entries, "铁及其化合物").map { it.problemId },
        )
    }

    @Test
    fun theSubjectIsSearchableToo() {
        assertEquals(
            listOf("p2"),
            filterMistakeCandidates(entries, "physics").map { it.problemId },
        )
    }

    @Test
    fun aQueryThatMatchesNothingReturnsNothing() {
        assertTrue(filterMistakeCandidates(entries, "集合的补集").isEmpty())
    }

    @Test
    fun thePickedEntryKeepsItsExactRevisionIdentity() {
        val key = entries[1].toMistakeRevisionKey()

        assertEquals("entry-p2", key.entryId)
        assertEquals("p2", key.problemId)
        assertEquals("p2-rev-2", key.problemRevisionId)
    }
}
