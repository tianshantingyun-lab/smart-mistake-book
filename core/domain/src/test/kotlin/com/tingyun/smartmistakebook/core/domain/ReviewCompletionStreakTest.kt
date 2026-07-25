package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewCompletionStreakTest {
    @Test
    fun todayCompletionAnchorsTheCurrentStreak() {
        assertEquals(
            3,
            ReviewCompletionStreak.count(
                completedLocalDays = listOf(20_100, 20_099, 20_098, 20_096),
                currentLocalDay = 20_100,
            ),
        )
    }

    @Test
    fun yesterdayCompletionKeepsTheStreakAliveUntilTodayIsFinished() {
        assertEquals(
            2,
            ReviewCompletionStreak.count(
                completedLocalDays = listOf(20_099, 20_098),
                currentLocalDay = 20_100,
            ),
        )
    }

    @Test
    fun anOlderGapEndsTheVisibleStreak() {
        assertEquals(
            0,
            ReviewCompletionStreak.count(
                completedLocalDays = listOf(20_098, 20_097),
                currentLocalDay = 20_100,
            ),
        )
    }

    @Test
    fun duplicatesDoNotInflateTheStreak() {
        assertEquals(
            2,
            ReviewCompletionStreak.count(
                completedLocalDays = listOf(20_100, 20_100, 20_099),
                currentLocalDay = 20_100,
            ),
        )
    }
}
