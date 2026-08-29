package com.tingyun.smartmistakebook.feature.review

import androidx.lifecycle.Lifecycle

/**
 * Silent review-interaction collector (spec mastery-scheduling §2.14). The
 * review surface feeds raw gestures into this tracker without ever showing
 * a prompt, a badge, or any other UI; the counts travel with the review
 * submission into review_log and only ever correct evidence weights — they
 * never enter the forgetting curve (Benjamin 1998).
 */
class ReviewInteractionTracker {
    /** Upward scroll direction changes observed on the problem surface. */
    var scrollUpCount: Int = 0
        private set

    /** Process-level interruptions (background/foreground flips) during one visit. */
    var interruptionCount: Int = 0
        private set

    /** Last observed scroll offset, internal to the delta collector. */
    internal var lastScrollValue: Int? = null

    /** @param delta signed scroll pixel delta; negative means scrolling back up. */
    fun onScrollDelta(delta: Int) {
        if (delta < 0) scrollUpCount += 1
    }

    fun onLifecycleEvent(event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_PAUSE) interruptionCount += 1
    }
}
