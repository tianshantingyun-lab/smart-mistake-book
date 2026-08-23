package com.tingyun.smartmistakebook.core.domain

import kotlin.math.ln
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HalfLifeRegressionTest {

    /** theta = [2, 0, ...] so that h = 2^2 = 4 seconds for the zero feature vector. */
    private val controlled = HalfLifeRegressionPredictor(
        HLRParameters(List(HLR_FEATURE_COUNT) { index -> if (index == 0) 2.0 else 0.0 }),
    )
    private val zeroFeatures = HLRFeatures(difficulty = 0.0)
    private val defaultPredictor = HalfLifeRegressionPredictor()

    @Test
    fun `recall probability follows P = 2 to the power of minus delta over h`() {
        val halfLife = controlled.computeHalfLife(zeroFeatures)
        assertEquals(4.0, halfLife, 1e-12)
        assertEquals(1.0, controlled.predict(zeroFeatures, 0.0), 1e-12)
        assertEquals(0.5, controlled.predict(zeroFeatures, halfLife), 1e-12)
        assertEquals(0.25, controlled.predict(zeroFeatures, 2.0 * halfLife), 1e-12)
        assertEquals(2.0.pow(-1.0 / 4.0), controlled.predict(zeroFeatures, 1.0), 1e-12)
    }

    @Test
    fun `default parameters reproduce the exponential decay formula`() {
        val features = HLRFeatures()
        val halfLife = defaultPredictor.computeHalfLife(features)
        for (deltaSeconds in listOf(0.0, halfLife / 2.0, halfLife, 3.0 * halfLife)) {
            assertEquals(
                2.0.pow(-deltaSeconds / halfLife),
                defaultPredictor.predict(features, deltaSeconds),
                1e-12,
            )
        }
    }

    @Test
    fun `target-recall interval is the exact inverse of the forward prediction`() {
        val features = HLRFeatures(
            independentCorrectCount = 0.4,
            difficulty = 0.7,
            consecutiveCorrectStreak = 0.2,
        )
        val halfLife = defaultPredictor.computeHalfLife(features)
        for (target in listOf(0.9, 0.7, 0.5, 0.3)) {
            val interval = defaultPredictor.intervalForTargetRecall(features, target)
            // Closed form: t = h * ln(1/P) / ln(2)
            assertEquals(halfLife * ln(1.0 / target) / ln(2.0), interval, 1e-9)
            // Forward/inverse round-trip: predicting at the solved interval
            // must reproduce the requested recall probability.
            assertEquals(target, defaultPredictor.predict(features, interval), 1e-9)
        }
    }

    @Test
    fun `theta vectors with the wrong dimension are rejected`() {
        assertRejected { HLRParameters(List(HLR_FEATURE_COUNT - 1) { 0.0 }) }
        assertRejected { HLRParameters(List(HLR_FEATURE_COUNT + 1) { 0.0 }) }
        assertRejected { HalfLifeRegressionPredictor(HLRParameters(List(3) { 0.0 })) }
    }

    @Test
    fun `target recall outside the open unit interval is rejected`() {
        for (target in listOf(0.0, 1.0, -0.1, 1.5)) {
            assertRejected { defaultPredictor.intervalForTargetRecall(HLRFeatures(), target) }
        }
    }

    @Test
    fun `negative deltas are rejected`() {
        assertRejected { defaultPredictor.predict(HLRFeatures(), -1.0) }
        assertRejected { controlled.predict(zeroFeatures, -0.001) }
    }

    @Test
    fun `non-finite features and parameters are rejected`() {
        assertRejected { HLRFeatures(difficulty = Double.NaN) }
        assertRejected { HLRFeatures(evidenceMass = Double.POSITIVE_INFINITY) }
        assertRejected { HLRParameters(List(HLR_FEATURE_COUNT) { Double.NaN }) }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message != null)
        }
    }
}
