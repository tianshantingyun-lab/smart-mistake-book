package com.tingyun.smartmistakebook.feature.review

import androidx.lifecycle.Lifecycle

/**
 * Silent review-interaction collector (spec mastery-scheduling §2.14). The
 * review surface feeds raw gestures into this tracker without ever showing
 * a prompt, a badge, or any other UI; the counts travel with the review
 * submission into review_log and only ever correct evidence weights — they
 * never enter the forgetting curve (Benjamin 1998).
 */
class ReviewInteractionTracker(
    private val clock: () -> Long = DEFAULT_CLOCK,
) {
    /** Upward scroll direction changes observed on the problem surface. */
    var scrollUpCount: Int = 0
        private set

    /** Process-level interruptions (app switches / screen-away) during one visit. */
    var interruptionCount: Int = 0
        private set

    /** Cumulative time spent away from the app while the question was open. */
    var awayMillis: Long = 0
        private set

    private var pausedAtMillis: Long? = null

    /** @param delta signed scroll pixel delta; negative means scrolling back up. */
    fun onScrollDelta(delta: Int) {
        if (delta < 0) scrollUpCount += 1
    }

    fun onLifecycleEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_PAUSE -> {
                if (pausedAtMillis == null) {
                    interruptionCount += 1
                    pausedAtMillis = clock()
                }
            }
            Lifecycle.Event.ON_RESUME -> {
                pausedAtMillis?.let { pausedAt ->
                    awayMillis += (clock() - pausedAt).coerceAtLeast(0)
                }
                pausedAtMillis = null
            }
            else -> Unit
        }
    }

    private companion object {
        val DEFAULT_CLOCK: () -> Long = { System.currentTimeMillis() }
    }
}
