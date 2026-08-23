package com.tingyun.smartmistakebook.core.domain

import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LogDurationModelTest {

    private val model = LogDurationModel()

    @Test
    fun `cold start falls back to the global prior`() {
        assertEquals(
            LogDurationModel.GLOBAL_PRIOR_SECONDS,
            model.expectedSeconds("learner", "math", "choice", 0.5),
            0.0,
        )
        assertEquals(
            LogDurationModel.GLOBAL_PRIOR_SECONDS,
            model.p80Seconds("learner", "math", "choice", 0.5),
            0.0,
        )
    }

    @Test
    fun `ema update weights the newest log sample by alpha`() {
        val alpha = 0.2
        model.record("learner", "math", "choice", 0.5, 100.0)
        // First sample initializes the EMA directly.
        assertEquals(100.0, model.expectedSeconds("learner", "math", "choice", 0.5), 1e-9)

        model.record("learner", "math", "choice", 0.5, 400.0)
        val expectedTwo = exp(alpha * ln(400.0) + (1.0 - alpha) * ln(100.0))
        assertEquals(expectedTwo, model.expectedSeconds("learner", "math", "choice", 0.5), 1e-9)

        model.record("learner", "math", "choice", 0.5, 25.0)
        val expectedThree = exp(
            alpha * ln(25.0) + (1.0 - alpha) * (alpha * ln(400.0) + (1.0 - alpha) * ln(100.0)),
        )
        assertEquals(expectedThree, model.expectedSeconds("learner", "math", "choice", 0.5), 1e-9)
    }

    @Test
    fun `p80 uses the requested percentile of the observed samples`() {
        repeat(10) { index ->
            model.record("learner", "math", "choice", 0.5, (index + 1) * 10.0)
        }
        // Sorted log samples of 10..100; index (10 * 0.8).toInt() = 8 picks
        // the ninth sample, i.e. 90 seconds.
        assertEquals(90.0, model.p80Seconds("learner", "math", "choice", 0.5), 1e-9)
    }

    @Test
    fun `p80 window keeps only the newest 120 samples`() {
        // One extreme outlier followed by 120 identical samples: the outlier
        // must be evicted from the bounded window.
        model.record("learner", "math", "choice", 0.5, 1_000_000.0)
        repeat(120) {
            model.record("learner", "math", "choice", 0.5, 10.0)
        }
        assertEquals(10.0, model.p80Seconds("learner", "math", "choice", 0.5), 1e-9)
    }

    @Test
    fun `buckets are isolated by learner subject item type and difficulty`() {
        model.record("learner-a", "math", "choice", 0.5, 300.0)
        assertEquals(300.0, model.expectedSeconds("learner-a", "math", "choice", 0.5), 1e-9)

        // Different learner.
        assertEquals(
            LogDurationModel.GLOBAL_PRIOR_SECONDS,
            model.expectedSeconds("learner-b", "math", "choice", 0.5),
            0.0,
        )
        // Different subject.
        assertEquals(
            LogDurationModel.GLOBAL_PRIOR_SECONDS,
            model.expectedSeconds("learner-a", "physics", "choice", 0.5),
            0.0,
        )
        // Different item type, including the null-typed bucket.
        assertEquals(
            LogDurationModel.GLOBAL_PRIOR_SECONDS,
            model.expectedSeconds("learner-a", "math", null, 0.5),
            0.0,
        )
        assertEquals(
            LogDurationModel.GLOBAL_PRIOR_SECONDS,
            model.expectedSeconds("learner-a", "math", "fill-in", 0.5),
            0.0,
        )
        // Different difficulty.
        assertEquals(
            LogDurationModel.GLOBAL_PRIOR_SECONDS,
            model.expectedSeconds("learner-a", "math", "choice", 0.9),
            0.0,
        )
    }

    @Test
    fun `non-positive or non-finite observed durations are rejected`() {
        assertRejected { model.record("learner", null, null, 0.5, 0.0) }
        assertRejected { model.record("learner", null, null, 0.5, -12.0) }
        assertRejected { model.record("learner", null, null, 0.5, Double.NaN) }
        assertRejected { model.record("learner", null, null, 0.5, Double.POSITIVE_INFINITY) }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }
}
