package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StudyExperienceRepositoryTest {
    @Test
    fun `mistake count is derived from the shared catalog snapshot`() {
        val entry = StudyCatalogEntry(
            entryId = "entry-1",
            problemId = "problem-1",
            problemRevisionId = "revision-1",
            practiceUnitId = "practice-1",
            subject = "MATH",
            title = "题目",
            problemMarkdown = "题干",
            sourceKey = null,
            isCuratedExample = false,
            nextReviewAtEpochMillis = null,
            retrievability = null,
        )

        assertEquals(2, StudyExperienceSnapshot(catalog = listOf(entry, entry.copy(entryId = "entry-2"))).mistakeCount)
    }

    @Test
    fun `choice submission rejects a response ordinal supplied as zero`() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyChoiceSubmission(
                requestId = "request-1",
                presentationId = "presentation-1",
                practiceUnitId = "practice-1",
                selectedChoiceId = "A",
                responseOrdinal = 0,
                durationSeconds = 10,
                occurredAtEpochMillis = 1,
            )
        }
    }

    @Test
    fun `answer reveal requires an idempotency request id`() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyAnswerRevealRequest(
                requestId = "",
                presentationId = "presentation-1",
                practiceUnitId = "practice-1",
                occurredAtEpochMillis = 1,
            )
        }
    }

    @Test
    fun `knowledge coverage derives its question occurrence total`() {
        val gap = StudyKnowledgeCoverageGap(
            groundingKey = "gap-1",
            subject = SubjectKind.PHYSICS,
            expectedParentKnowledgeDisplayName = "牛顿运动定律",
            query = "连接体的整体与隔离分析",
            relatedQuestionCount = 2,
            firstObservedAtEpochMillis = 1,
            lastObservedAtEpochMillis = 2,
        )

        assertEquals(
            2,
            StudyKnowledgeCoverageOverview(pendingGaps = listOf(gap))
                .pendingQuestionOccurrenceCount,
        )
    }

    @Test
    fun `knowledge coverage derives reviewed totals without completeness guesses`() {
        val overview = StudyKnowledgeCoverageOverview(
            reviewedSubjects = listOf(
                StudyKnowledgeSubjectCoverage(
                    subject = SubjectKind.MATH,
                    topicCount = 2,
                    atomicKnowledgeCount = 5,
                    reviewedSourceCount = 2,
                    latestReviewedAtEpochMillis = 10,
                ),
                StudyKnowledgeSubjectCoverage(
                    subject = SubjectKind.PHYSICS,
                    topicCount = 1,
                    atomicKnowledgeCount = 3,
                    reviewedSourceCount = 1,
                    latestReviewedAtEpochMillis = 20,
                ),
            ),
        )

        assertEquals(2, overview.reviewedSubjectCount)
        assertEquals(3, overview.reviewedTopicCount)
        assertEquals(8, overview.reviewedAtomicKnowledgeCount)
        assertEquals(3, overview.reviewedSourceCount)
    }
}
