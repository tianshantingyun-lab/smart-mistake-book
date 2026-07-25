package com.tingyun.smartmistakebook

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewReminderTimeCalculatorTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun schedulesLaterTodayWhenReminderTimeHasNotPassed() {
        val now = Instant.parse("2026-07-21T19:00:00Z").toEpochMilli()

        val result = ReviewReminderTimeCalculator.nextTriggerEpochMillis(
            nowEpochMillis = now,
            minutesAfterMidnight = 20 * 60 + 30,
            zoneId = utc,
        )

        assertEquals(Instant.parse("2026-07-21T20:30:00Z").toEpochMilli(), result)
    }

    @Test
    fun schedulesTomorrowWhenReminderTimeIsNow() {
        val now = Instant.parse("2026-07-21T20:30:00Z").toEpochMilli()

        val result = ReviewReminderTimeCalculator.nextTriggerEpochMillis(
            nowEpochMillis = now,
            minutesAfterMidnight = 20 * 60 + 30,
            zoneId = utc,
        )

        assertEquals(Instant.parse("2026-07-22T20:30:00Z").toEpochMilli(), result)
    }

    @Test
    fun followsLocalDayAcrossDaylightSavingGap() {
        val newYork = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-03-08T06:00:00Z").toEpochMilli()

        val result = ReviewReminderTimeCalculator.nextTriggerEpochMillis(
            nowEpochMillis = now,
            minutesAfterMidnight = 2 * 60 + 30,
            zoneId = newYork,
        )

        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), result)
    }
}
