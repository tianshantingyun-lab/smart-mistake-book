package com.tingyun.smartmistakebook.feature.review

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Silent collector behavior (spec mastery-scheduling §2.14). */
class ReviewInteractionTrackerTest {

    @Test
    fun `only upward scroll deltas are counted`() {
        val tracker = ReviewInteractionTracker()

        tracker.onScrollDelta(-120)
        tracker.onScrollDelta(-1)
        tracker.onScrollDelta(300)
        tracker.onScrollDelta(0)

        assertEquals(2, tracker.scrollUpCount)
    }

    @Test
    fun `every pause counts one interruption`() {
        val tracker = ReviewInteractionTracker()

        tracker.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        tracker.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        tracker.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        tracker.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)

        assertEquals(2, tracker.interruptionCount)
        assertEquals(0, tracker.scrollUpCount)
    }
}
