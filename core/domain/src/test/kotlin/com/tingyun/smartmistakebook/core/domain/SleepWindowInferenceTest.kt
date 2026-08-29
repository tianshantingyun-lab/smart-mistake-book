package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepWindowInferenceTest {

    @Test
    fun `gaps of at least three hours become sleep windows`() {
        val hour = 3_600_000L
        val activity = listOf(
            22 * hour,
            23 * hour,
            27 * hour, // 4h gap → sleep window 23h..27h
            28 * hour,
            40 * hour, // 12h gap → sleep window 28h..40h
        )

        val windows = SleepWindowInference.infer(activity)

        assertEquals(2, windows.size)
        assertEquals(23 * hour, windows[0].startEpochMillis)
        assertEquals(27 * hour, windows[0].endEpochMillis)
        assertEquals(28 * hour, windows[1].startEpochMillis)
        assertEquals(40 * hour, windows[1].endEpochMillis)
    }

    @Test
    fun `short gaps never produce windows regardless of order`() {
        val hour = 3_600_000L
        val windows = SleepWindowInference.infer(
            listOf(5 * hour, 2 * hour, 3 * hour + 30 * 60_000L),
        )

        assertTrue(windows.isEmpty())
    }

    @Test
    fun `fewer than two stamps yield no windows`() {
        assertTrue(SleepWindowInference.infer(emptyList()).isEmpty())
        assertTrue(SleepWindowInference.infer(listOf(1_000L)).isEmpty())
    }

    @Test
    fun `days since last sleep counts whole days after the window end`() {
        val day = 86_400_000L
        val windows = listOf(
            SleepWindowEntry(epochDay = 0, startEpochMillis = 0, endEpochMillis = day),
        )

        assertEquals(2, SleepWindowInference.daysSinceLastSleep(windows, atMillis = 3 * day))
        assertEquals(0, SleepWindowInference.daysSinceLastSleep(windows, atMillis = day))
        assertNull(SleepWindowInference.daysSinceLastSleep(emptyList(), atMillis = 10 * day))
    }
}
