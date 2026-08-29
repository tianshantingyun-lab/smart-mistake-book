package com.tingyun.smartmistakebook.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Sleep-window inference from device usage (spec mastery-scheduling §2.14):
 * the app records foreground activity stamps silently and infers nights as
 * the long gaps between consecutive activity. No notification, prompt, or
 * visible UI is ever produced. Inferred windows are analysis context for the
 * time-of-day and objective-event channels; they never enter the forgetting
 * curve directly.
 */
@kotlinx.serialization.Serializable
data class SleepWindowEntry(
    /** Local day (epoch day) on which the sleep window started. */
    val epochDay: Long,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
) {
    init {
        require(endEpochMillis > startEpochMillis) {
            "A sleep window must end after it starts"
        }
    }

    val durationMillis: Long get() = endEpochMillis - startEpochMillis
}

object SleepWindowInference {

    /** A usage gap of at least three hours is treated as sleep (spec §2.14). */
    const val MIN_SLEEP_GAP_MILLIS: Long = 3L * 60 * 60 * 1000

    /**
     * Infers sleep windows from ordered activity stamps: every consecutive
     * pair whose gap reaches [MIN_SLEEP_GAP_MILLIS] is one window.
     */
    fun infer(activityTimestamps: List<Long>): List<SleepWindowEntry> {
        if (activityTimestamps.size < 2) return emptyList()
        val ordered = activityTimestamps.sorted()
        val windows = mutableListOf<SleepWindowEntry>()
        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val next = ordered[index]
            if (next - previous >= MIN_SLEEP_GAP_MILLIS) {
                windows += SleepWindowEntry(
                    epochDay = previous / DAY_MILLIS,
                    startEpochMillis = previous,
                    endEpochMillis = next,
                )
            }
        }
        return windows
    }

    /** Whole days elapsed between the last sleep window's end and [atMillis]. */
    fun daysSinceLastSleep(
        windows: List<SleepWindowEntry>,
        atMillis: Long,
    ): Int? = windows.maxOfOrNull(SleepWindowEntry::endEpochMillis)?.let { lastEnd ->
        ((atMillis - lastEnd).coerceAtLeast(0) / DAY_MILLIS).toInt()
    }

    private const val DAY_MILLIS = 86_400_000L
}

/** Durable activity journal owned by the data layer (DataStore in production). */
interface SleepJournalStore {
    /** Records one silent activity stamp (app became visible). */
    suspend fun recordActivity(atEpochMillis: Long)

    /** Inferred sleep windows over the retained journal horizon. */
    val recentWindows: Flow<List<SleepWindowEntry>>
}
