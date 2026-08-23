package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.LearningModelVersion
import kotlin.math.abs
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CalibrationReportBuilderTest {

    private val modelVersion = LearningModelVersion(
        modelId = "test-model",
        version = "v1",
        algorithmHash = "sha-test-1",
    )

    @Test
    fun `known distribution produces exact brier log-loss ece and max deviation`() {
        // Four samples, each landing in its own 0.1-wide bucket:
        // bucket 0: p=0.05/outcome 0, bucket 1: p=0.15/outcome 1,
        // bucket 8: p=0.85/outcome 1, bucket 9: p=0.95/outcome 0.
        val resolved = listOf(
            CalibrationInput(predictedScore = 0.05, wasIndependentCorrect = false),
            CalibrationInput(predictedScore = 0.15, wasIndependentCorrect = true),
            CalibrationInput(predictedScore = 0.85, wasIndependentCorrect = true),
            CalibrationInput(predictedScore = 0.95, wasIndependentCorrect = false),
        )

        val report = CalibrationReportBuilder.build(
            modelVersion = modelVersion,
            resolved = resolved,
            totalPredictions = 7,
            generatedAtEpochMillis = 123,
        )

        assertEquals(7, report.totalPredictions)
        assertEquals(4, report.resolvedPredictions)
        assertEquals(123L, report.generatedAtEpochMillis)
        assertEquals(10, report.buckets.size)

        // Brier: mean squared error over the four samples.
        val expectedBrier = (
            (0.05 - 0.0) * (0.05 - 0.0) +
                (0.15 - 1.0) * (0.15 - 1.0) +
                (0.85 - 1.0) * (0.85 - 1.0) +
                (0.95 - 0.0) * (0.95 - 0.0)
            ) / 4.0
        assertEquals(expectedBrier, report.overallBrierScore, 1e-12)

        // Log loss: mean negative log-likelihood with clamped probabilities.
        val expectedLogLoss = (
            -ln(1.0 - 0.05) - ln(0.15) - ln(0.85) - ln(1.0 - 0.95)
            ) / 4.0
        assertEquals(expectedLogLoss, report.overallLogLoss!!, 1e-12)

        // ECE: equal weights of 0.25 over |mean - positive rate| per bucket.
        val expectedEce = (
            abs(0.05 - 0.0) + abs(0.15 - 1.0) + abs(0.85 - 1.0) + abs(0.95 - 0.0)
            ) / 4.0
        assertEquals(expectedEce, report.expectedCalibrationError, 1e-12)
        assertEquals(0.95, report.maximumCalibrationDeviation, 1e-12)

        val bucket0 = report.buckets[0]
        assertEquals(0.0, bucket0.scoreRangeLow, 0.0)
        assertEquals(0.1, bucket0.scoreRangeHigh, 1e-12)
        assertEquals(1, bucket0.predictionCount)
        assertEquals(0.05, bucket0.meanPredictedScore, 1e-12)
        assertEquals(0.0, bucket0.actualPositiveRate, 0.0)
        assertEquals(0.0025, bucket0.brierContribution, 1e-12)
        assertEquals(-ln(0.95), bucket0.logLossContribution!!, 1e-12)

        val emptyBucket = report.buckets[4]
        assertEquals(0, emptyBucket.predictionCount)
        assertEquals(0.0, emptyBucket.meanPredictedScore, 0.0)
        assertEquals(0.0, emptyBucket.actualPositiveRate, 0.0)
        assertEquals(0.0, emptyBucket.brierContribution, 0.0)
        assertNull(emptyBucket.logLossContribution)
    }

    @Test
    fun `multiple samples in one bucket aggregate exactly`() {
        val resolved = listOf(
            CalibrationInput(predictedScore = 0.5, wasIndependentCorrect = true),
            CalibrationInput(predictedScore = 0.55, wasIndependentCorrect = false),
        )

        val report = CalibrationReportBuilder.build(
            modelVersion = modelVersion,
            resolved = resolved,
            totalPredictions = 2,
            generatedAtEpochMillis = 0,
        )

        val bucket = report.buckets[5]
        assertEquals(2, bucket.predictionCount)
        assertEquals(0.525, bucket.meanPredictedScore, 1e-12)
        assertEquals(0.5, bucket.actualPositiveRate, 1e-12)
        val expectedBrier = (
            (0.5 - 1.0) * (0.5 - 1.0) + (0.55 - 0.0) * (0.55 - 0.0)
            ) / 2.0
        assertEquals(expectedBrier, bucket.brierContribution, 1e-12)
        assertEquals(expectedBrier, report.overallBrierScore, 1e-12)
        assertEquals(abs(0.525 - 0.5), report.expectedCalibrationError, 1e-12)
        assertEquals(abs(0.525 - 0.5), report.maximumCalibrationDeviation, 1e-12)
    }

    @Test
    fun `empty resolved set yields a zeroed report`() {
        val report = CalibrationReportBuilder.build(
            modelVersion = modelVersion,
            resolved = emptyList(),
            totalPredictions = 0,
            generatedAtEpochMillis = 5,
        )

        assertEquals(0.0, report.overallBrierScore, 0.0)
        assertNull(report.overallLogLoss)
        assertEquals(0.0, report.expectedCalibrationError, 0.0)
        assertEquals(0.0, report.maximumCalibrationDeviation, 0.0)
        assertTrue(report.buckets.all { it.predictionCount == 0 })
    }

    @Test
    fun `scores outside the unit interval are rejected`() {
        assertRejected {
            CalibrationReportBuilder.build(
                modelVersion = modelVersion,
                resolved = listOf(CalibrationInput(predictedScore = 1.5, wasIndependentCorrect = true)),
                totalPredictions = 1,
                generatedAtEpochMillis = 0,
            )
        }
        assertRejected {
            CalibrationReportBuilder.build(
                modelVersion = modelVersion,
                resolved = listOf(CalibrationInput(predictedScore = -0.1, wasIndependentCorrect = true)),
                totalPredictions = 1,
                generatedAtEpochMillis = 0,
            )
        }
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
