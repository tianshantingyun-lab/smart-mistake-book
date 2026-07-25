package com.tingyun.smartmistakebook.feature.tutor

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedMistakeTutorAnchorTest {
    @Test
    fun savedMistakeUsesItsStableSessionProblemRevisionAndPracticeUnitAnchor() {
        val anchor = savedMistakeTutorAnchor(
            sessionId = "mistake-tutor-session",
            problemRevisionId = "problem-revision-7",
            practiceUnitId = "practice-unit-3",
            anchoredAtEpochMillis = 9_000,
        )

        assertEquals("mistake-tutor-session", anchor.sessionId)
        assertEquals("problem-revision-7", anchor.problemRevisionId)
        assertEquals("practice-unit-3", anchor.practiceUnitId)
        assertEquals(9_000L, anchor.anchoredAtEpochMillis)
    }
}
