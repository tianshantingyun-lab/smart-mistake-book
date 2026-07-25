package com.tingyun.smartmistakebook

import org.junit.Assert.assertEquals
import org.junit.Test

class LearningMasteryRecentActivityTest {
    @Test
    fun `recent activity uses familiar day labels and treats future clocks as today`() {
        val now = 10 * DAY_MILLIS

        assertEquals("今天", recentActivityLabel(now, now))
        assertEquals("昨天", recentActivityLabel(now - DAY_MILLIS, now))
        assertEquals("5天前", recentActivityLabel(now - 5 * DAY_MILLIS, now))
        assertEquals("今天", recentActivityLabel(now + DAY_MILLIS, now))
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
