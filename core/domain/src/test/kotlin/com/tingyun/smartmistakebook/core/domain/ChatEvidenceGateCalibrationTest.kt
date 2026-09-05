package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.domain.ChatEvidenceGateCalibration.GateObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEvidenceGateCalibrationTest {

    @Test
    fun `below the sample floor no suggestions are emitted`() {
        // 5 accepted + 20 rejected = 25 total observations, below the 30 floor.
        val report = ChatEvidenceGateCalibration.calibrate(
            GateObservation(
                acceptedCount = 5,
                rejectedByReason = mapOf("SAME_KC_IN_COOLDOWN" to 20),
                acceptedPerHour = mapOf(1000L to 5),
            ),
        )
        assertFalse(report.hasSufficientObservations)
        assertTrue("expected no suggestions below floor, got ${report.suggestions}", report.suggestions.isEmpty())
    }

    @Test
    fun `window pressure at or above threshold suggests loosening the quota`() {
        // 90 accepted in the peak hour against a 100 cap = 0.9 pressure.
        val report = ChatEvidenceGateCalibration.calibrate(
            GateObservation(
                acceptedCount = 90,
                rejectedByReason = emptyMap(),
                acceptedPerHour = mapOf(1000L to 90, 999L to 5),
            ),
        )
        assertTrue(report.hasSufficientObservations)
        assertEquals(0.9, report.windowPeakPressure, 1e-9)
        assertTrue(
            "expected window-pressure suggestion, got ${report.suggestions}",
            report.suggestions.any { it.contains("窗口配额压力") },
        )
    }

    @Test
    fun `low pressure produces no quota suggestion`() {
        val report = ChatEvidenceGateCalibration.calibrate(
            GateObservation(
                acceptedCount = ChatEvidenceGateCalibration.MIN_OBSERVATIONS.toLong().toInt(),
                rejectedByReason = emptyMap(),
                acceptedPerHour = mapOf(1000L to 20),
            ),
        )
        assertTrue(report.suggestions.isEmpty())
    }

    @Test
    fun `throttling rejections dominate suggests reviewing cooldown and quotas`() {
        // accepted 35 + SAME_KC 20 + WINDOW 15 = 70 total; WINDOW share
        // 15/70 ≈ 21% crosses the 20% suggestion threshold, SAME_KC 29%.
        val report = ChatEvidenceGateCalibration.calibrate(
            GateObservation(
                acceptedCount = 35,
                rejectedByReason = mapOf(
                    "SAME_KC_IN_COOLDOWN" to 20,
                    "LEARNER_WINDOW_QUOTA_EXHAUSTED" to 15,
                ),
                acceptedPerHour = mapOf(1000L to 35),
            ),
        )
        assertTrue(
            report.suggestions.any { it.contains("SAME_KC_IN_COOLDOWN") && it.contains("节流") },
        )
        assertTrue(
            report.suggestions.any { it.contains("LEARNER_WINDOW_QUOTA_EXHAUSTED") },
        )
    }

    @Test
    fun `quality rejections dominate suggests checking model behavior`() {
        val report = ChatEvidenceGateCalibration.calibrate(
            GateObservation(
                acceptedCount = 20,
                rejectedByReason = mapOf("KNOWLEDGE_NODE_NOT_ANCHORED" to 40),
                acceptedPerHour = emptyMap(),
            ),
        )
        assertTrue(
            report.suggestions.any { it.contains("KNOWLEDGE_NODE_NOT_ANCHORED") && it.contains("质量") },
        )
    }

    @Test
    fun `rejection shares are computed over all observations`() {
        val report = ChatEvidenceGateCalibration.calibrate(
            GateObservation(
                acceptedCount = 60,
                rejectedByReason = mapOf("ATTENTION_BELOW_FLOOR" to 40),
                acceptedPerHour = emptyMap(),
            ),
        )
        assertEquals(0.4, report.rejectionShares.getValue("ATTENTION_BELOW_FLOOR"), 1e-9)
        assertEquals(100, report.totalObservations)
    }
}
