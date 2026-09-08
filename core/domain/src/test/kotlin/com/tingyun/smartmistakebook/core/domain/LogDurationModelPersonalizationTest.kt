package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogDurationModelPersonalizationTest {

    private fun modelWithSamples(): LogDurationModel {
        val model = LogDurationModel()
        // 6 samples for (learner-1, math) — crosses the PERSONALIZED_MIN_SAMPLES=5
        // floor so the personalized estimate takes over.
        repeat(6) { model.record("learner-1", "math", null, 5.0, 120.0) }
        return model
    }

    @Test
    fun `enough samples in the learner subject bucket activate the personalized estimate`() {
        val model = modelWithSamples()
        val estimate = model.expectedSecondsForNew(
            learnerId = "learner-1",
            subjectId = "math",
            itemType = null,
            difficulty = 5.0,
            tierBaselineSeconds = LogDurationModel.TIER_BASELINE_MEDIUM_SECONDS,
        )
        // Personalized EMA ≈ 120s — NOT the 180s tier baseline.
        assertEquals(120.0, estimate, 1e-6)
    }

    @Test
    fun `different subject does not leak into the bucket`() {
        val model = modelWithSamples()
        val estimate = model.expectedSecondsForNew(
            learnerId = "learner-1",
            subjectId = "physics",
            itemType = null,
            difficulty = 5.0,
            tierBaselineSeconds = LogDurationModel.TIER_BASELINE_MEDIUM_SECONDS,
        )
        // No physics samples → falls back to the tier baseline.
        assertEquals(LogDurationModel.TIER_BASELINE_MEDIUM_SECONDS.toDouble(), estimate, 1e-9)
    }

    @Test
    fun `below the sample floor the tier baseline is used`() {
        val model = LogDurationModel()
        repeat(3) { model.record("learner-1", "math", null, 5.0, 120.0) }
        val estimate = model.expectedSecondsForNew(
            learnerId = "learner-1",
            subjectId = "math",
            itemType = null,
            difficulty = 5.0,
            tierBaselineSeconds = LogDurationModel.TIER_BASELINE_EASY_SECONDS,
        )
        assertEquals(LogDurationModel.TIER_BASELINE_EASY_SECONDS.toDouble(), estimate, 1e-9)
    }

    @Test
    fun `no tier baseline falls back to the global prior`() {
        val model = LogDurationModel()
        val estimate = model.expectedSecondsForNew(
            learnerId = "learner-1",
            subjectId = "math",
            itemType = null,
            difficulty = 5.0,
            tierBaselineSeconds = null,
        )
        assertEquals(LogDurationModel.GLOBAL_PRIOR_SECONDS, estimate, 1e-9)
    }

    @Test
    fun `difficulty drift does not break the personalization bucket`() {
        // The record side stored difficulty 5.0 (prior); the query side now
        // asks with the CURRENT difficulty 7.2 — with the (learner, subject)
        // bucketing the sample must still be found.
        val model = modelWithSamples()
        val estimate = model.expectedSecondsForNew(
            learnerId = "learner-1",
            subjectId = "math",
            itemType = null,
            difficulty = 7.2,
            tierBaselineSeconds = LogDurationModel.TIER_BASELINE_HARD_SECONDS,
        )
        assertEquals(120.0, estimate, 1e-6)
        assertTrue(model.totalSamples() == 6)
    }
}
