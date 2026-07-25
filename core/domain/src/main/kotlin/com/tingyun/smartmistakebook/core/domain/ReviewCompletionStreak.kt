package com.tingyun.smartmistakebook.core.domain

object ReviewCompletionStreak {
    fun count(
        completedLocalDays: Collection<Long>,
        currentLocalDay: Long,
    ): Int {
        if (completedLocalDays.isEmpty()) return 0
        val completed = completedLocalDays.toHashSet()
        var day = when {
            currentLocalDay in completed -> currentLocalDay
            currentLocalDay > Long.MIN_VALUE && currentLocalDay - 1 in completed ->
                currentLocalDay - 1
            else -> return 0
        }
        var streak = 0
        while (completed.remove(day)) {
            streak += 1
            if (day == Long.MIN_VALUE) break
            day -= 1
        }
        return streak
    }
}
