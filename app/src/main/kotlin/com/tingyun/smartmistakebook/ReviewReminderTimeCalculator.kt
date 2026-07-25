package com.tingyun.smartmistakebook

import java.time.Instant
import java.time.ZoneId

internal object ReviewReminderTimeCalculator {
    fun nextTriggerEpochMillis(
        nowEpochMillis: Long,
        minutesAfterMidnight: Int,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        require(minutesAfterMidnight in 0 until MINUTES_PER_DAY)
        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
        val todayCandidate = now.toLocalDate()
            .atStartOfDay(zoneId)
            .plusMinutes(minutesAfterMidnight.toLong())
        val next = if (todayCandidate.isAfter(now)) {
            todayCandidate
        } else {
            now.toLocalDate().plusDays(1).atStartOfDay(zoneId)
                .plusMinutes(minutesAfterMidnight.toLong())
        }
        return next.toInstant().toEpochMilli()
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
