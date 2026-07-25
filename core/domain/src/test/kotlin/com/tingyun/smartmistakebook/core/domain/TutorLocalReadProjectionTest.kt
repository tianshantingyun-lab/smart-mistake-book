package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLocalReadProjectionTest {
    @Test
    fun mistakeLookupUsesModelTermsButKeepsTheLocalReadBound() {
        val decision = mistakeDecision(listOf("函数", "单调性"))
        val authorization = TutorIntentAuthorityPolicy.authorize(
            decision,
            "帮我查一下错题本里的函数和单调性",
        )
        val catalog = (0 until 30).map { index ->
            entry(
                index = index,
                title = if (index % 2 == 0) "函数单调性 $index" else "空间几何 $index",
                knowledge = if (index % 2 == 0) listOf("导数", "单调性") else listOf("垂直"),
            )
        }

        val result = TutorLocalReadProjection.mistakes(catalog, decision, authorization)

        assertTrue(result.size <= TutorIntentAuthorization.MAX_READ_ITEMS)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { "函数" in it.title || "单调性" in it.knowledgeLabels })
    }

    @Test
    fun emptyLookupShowsTheBoundedReviewOrder() {
        val decision = mistakeDecision(emptyList())
        val authorization = TutorIntentAuthorityPolicy.authorize(decision, "打开错题本看看")
        val catalog = listOf(
            entry(1, nextReviewAt = 30),
            entry(2, nextReviewAt = 10),
            entry(3, nextReviewAt = null),
        )

        assertEquals(
            listOf("entry-2", "entry-1", "entry-3"),
            TutorLocalReadProjection.mistakes(catalog, decision, authorization)
                .map(TutorMistakeLookupItem::entryId),
        )
    }

    @Test
    fun progressReadReturnsOnlyTheExistingProjection() {
        val decision = TutorIntentDecision(
            intent = TutorMessageIntent.LEARNING_PROGRESS_LOOKUP,
            confidence = 0.9,
            explicitActionRequest = true,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.READ_LEARNING_PROGRESS,
        )
        val authorization = TutorIntentAuthorityPolicy.authorize(decision, "看看我的学习情况和掌握进度")
        val weak = StudyKnowledgeSummary("weak-1", "导数变号", MasteryStatus.LEARNING, 0.25)
        val strong = StudyKnowledgeSummary("strong-1", "一次函数", MasteryStatus.MASTERED, 0.91)

        val result = TutorLocalReadProjection.learningProgress(
            StudyProfileOverview(
                hasLearningEvidence = true,
                recordedAttemptCount = 8,
                weaknesses = listOf(weak),
                strengths = listOf(strong),
            ),
            authorization,
        )

        assertEquals(8, result.recordedAttemptCount)
        assertEquals(listOf(weak), result.needsAttention)
        assertEquals(listOf(strong), result.goingWell)
    }

    private fun mistakeDecision(terms: List<String>) = TutorIntentDecision(
        intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
        confidence = 0.9,
        explicitActionRequest = true,
        memoryPreference = TutorMemoryPreference.UNCHANGED,
        requestedLocalCapability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
        lookupTerms = terms,
    )

    private fun entry(
        index: Int,
        title: String = "错题 $index",
        knowledge: List<String> = emptyList(),
        nextReviewAt: Long? = index.toLong(),
    ) = StudyCatalogEntry(
        entryId = "entry-$index",
        problemId = "problem-$index",
        problemRevisionId = "revision-$index",
        practiceUnitId = "practice-$index",
        subject = "MATH",
        title = title,
        problemMarkdown = "题面",
        sourceKey = null,
        isCuratedExample = false,
        chapterLabels = if ("函数" in title) listOf("函数") else listOf("空间几何"),
        knowledgeLabels = knowledge,
        masteryStatus = MasteryStatus.LEARNING,
        nextReviewAtEpochMillis = nextReviewAt,
        retrievability = 0.5,
    )
}
