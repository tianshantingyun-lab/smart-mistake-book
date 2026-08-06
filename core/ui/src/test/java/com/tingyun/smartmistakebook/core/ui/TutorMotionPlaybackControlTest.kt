package com.tingyun.smartmistakebook.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TutorMotionPlaybackControlTest {
    @Test
    fun speedCyclesThroughConfiguredPlaybackRates() {
        assertEquals(0.5f, PLAYBACK_SPEEDS[0])
        assertEquals(1f, PLAYBACK_SPEEDS[1])
        assertEquals(2f, PLAYBACK_SPEEDS[2])
        assertEquals(2, nextPlaybackSpeedIndex(1))
        assertEquals(0, nextPlaybackSpeedIndex(2))
        assertEquals(1, nextPlaybackSpeedIndex(0))
    }

    @Test
    fun playbackTimeIsClampedToTheSceneDuration() {
        assertEquals(0f, coercePlaybackTimeSeconds(-1f, 4f))
        assertEquals(2.5f, coercePlaybackTimeSeconds(2.5f, 4f))
        assertEquals(4f, coercePlaybackTimeSeconds(9f, 4f))
    }
}
