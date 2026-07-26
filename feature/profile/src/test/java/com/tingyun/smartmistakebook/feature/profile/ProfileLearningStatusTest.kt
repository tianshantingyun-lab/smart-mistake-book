package com.tingyun.smartmistakebook.feature.profile

import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileLearningStatusTest {
    @Test
    fun subjectSummariesUseEachRealKnowledgeRecordOnce() {
        val duplicate = summary(
            id = "math:function",
            subject = SubjectKind.MATH,
            status = MasteryStatus.LEARNING,
        )
        val overview = StudyProfileOverview(
            weaknesses = listOf(duplicate),
            strengths = listOf(
                duplicate.copy(status = MasteryStatus.MASTERED),
                summary(
                    id = "math:geometry",
                    subject = SubjectKind.MATH,
                    status = MasteryStatus.MASTERED,
                ),
                summary(
                    id = "physics:newton",
                    subject = SubjectKind.PHYSICS,
                    status = MasteryStatus.CONFLICTED,
                ),
            ),
        )

        val groups = profileSubjectSummaries(overview)

        assertEquals(listOf(SubjectKind.MATH, SubjectKind.PHYSICS), groups.map { it.subject })
        assertEquals(
            listOf(MasteryStatus.LEARNING, MasteryStatus.MASTERED),
            groups.first().statuses,
        )
        assertEquals(listOf(MasteryStatus.CONFLICTED), groups.last().statuses)
    }

    @Test
    fun weaknessSummaryNeverShowsMoreThanTwoUniqueItems() {
        val overview = StudyProfileOverview(
            weaknesses = listOf(
                summary("one", SubjectKind.MATH, MasteryStatus.LEARNING),
                summary("two", SubjectKind.PHYSICS, MasteryStatus.CONFLICTED),
                summary("three", SubjectKind.CHEMISTRY, MasteryStatus.STALE),
                summary("one", SubjectKind.MATH, MasteryStatus.LEARNING),
            ),
        )

        assertEquals(listOf("one", "two"), profileWeaknessSummaries(overview).map { it.knowledgeNodeId })
    }

    private fun summary(
        id: String,
        subject: SubjectKind,
        status: MasteryStatus,
    ) = StudyKnowledgeSummary(
        knowledgeNodeId = id,
        displayName = id.substringAfter(':'),
        status = status,
        lowerBoundIndependentCorrect = 0.5,
        subject = subject,
    )
}
