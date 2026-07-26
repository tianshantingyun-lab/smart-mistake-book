package com.tingyun.smartmistakebook.feature.profile

import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningMasterySubtitleTest {
    @Test
    fun recentChangesUseOnlyRealActivityTimesInNewestFirstOrder() {
        val overview = StudyProfileOverview(
            weaknesses = listOf(
                summary("older", lastEvidenceAt = 1_000L),
                summary("newer", lastEvidenceAt = 3_000L),
                summary("untimed", lastEvidenceAt = null),
            ),
            strengths = listOf(
                summary("middle", lastEvidenceAt = 2_000L),
                summary("newer", lastEvidenceAt = 9_000L),
            ),
        )

        assertEquals(
            listOf("newer", "middle", "older"),
            profileRecentChanges(overview).map { it.summary.knowledgeNodeId },
        )
    }

    @Test
    fun futureActivityIsDescribedAsTodayWithoutInventingAChange() {
        assertEquals(
            "今天",
            profileRecentActivityLabel(
                occurredAtEpochMillis = 2 * DAY_MILLIS,
                nowEpochMillis = DAY_MILLIS,
            ),
        )
        assertEquals(
            "昨天",
            profileRecentActivityLabel(
                occurredAtEpochMillis = DAY_MILLIS,
                nowEpochMillis = 2 * DAY_MILLIS,
            ),
        )
    }

    private fun summary(
        id: String,
        lastEvidenceAt: Long?,
    ) = StudyKnowledgeSummary(
        knowledgeNodeId = id,
        displayName = id,
        status = MasteryStatus.LEARNING,
        lowerBoundIndependentCorrect = 0.5,
        lastEvidenceAtEpochMillis = lastEvidenceAt,
        subject = SubjectKind.MATH,
    )

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
