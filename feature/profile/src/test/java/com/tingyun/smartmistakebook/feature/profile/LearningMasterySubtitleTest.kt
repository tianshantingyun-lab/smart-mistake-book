package com.tingyun.smartmistakebook.feature.profile

import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningMasterySubtitleTest {
    @Test
    fun `empty profile explains that learning history is automatic`() {
        assertEquals(
            "做过题后，这里会自动形成你的学习情况",
            learningMasterySubtitle(StudyProfileOverview()),
        )
    }

    @Test
    fun `summary groups learning records by subject without internal terminology`() {
        val overview = StudyProfileOverview(
            weaknesses = listOf(summary("函数单调性", SubjectKind.MATH)),
            strengths = listOf(
                summary("牛顿第二定律", SubjectKind.PHYSICS, MasteryStatus.MASTERED),
                summary("函数零点", SubjectKind.MATH, MasteryStatus.MASTERED),
            ),
        )

        assertEquals(
            "2 科 · 3 个知识点有学习记录",
            learningMasterySubtitle(overview),
        )
    }

    private fun summary(
        name: String,
        subject: SubjectKind,
        status: MasteryStatus = MasteryStatus.LEARNING,
    ) = StudyKnowledgeSummary(
        knowledgeNodeId = "$subject:$name",
        displayName = name,
        status = status,
        conservativeMasteryScore = 0.5,
        subject = subject,
    )
}
