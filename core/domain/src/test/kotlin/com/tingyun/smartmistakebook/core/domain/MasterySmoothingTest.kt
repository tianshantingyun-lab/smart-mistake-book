package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec mastery-scheduling §2.18: 7-day half-life EMA on the weakness input. */
class MasterySmoothingTest {

    private val now = 100L * DAY_MILLIS

    @Test
    fun `no observations keep the conservative bound unchanged`() {
        val state = mastery(conservative = 0.4, observations = emptyList())

        assertEquals(0.4, MasterySmoothing.smoothedMasteryScore(state, now), 1e-9)
    }

    @Test
    fun `a single weak day cannot collapse the smoothed score`() {
        // A long supported history then a sharp conservative drop on the
        // latest day (single-day swing): the EMA damps the fall.
        val history = (1..20).map { index ->
            IndependentCorrectObservation(
                itemFamilyId = "family-$index",
                studyDayEpochDay = index.toLong(),
                occurredAtEpochMillis = index * DAY_MILLIS,
                evidenceWeight = 1.0,
                calibration = supported(),
            )
        }
        val before = mastery(conservative = 0.85, observations = history)
        val afterBadDay = mastery(conservative = 0.55, observations = history)

        val smoothedBefore = MasterySmoothing.smoothedMasteryScore(before, now)
        val smoothedAfter = MasterySmoothing.smoothedMasteryScore(afterBadDay, now)

        assertTrue(smoothedAfter < smoothedBefore)
        assertTrue(
            "single-day swing must be damped",
            smoothedAfter > afterBadDay.conservativeMasteryScore,
        )
    }

    @Test
    fun `expired calibration support drags the smoothed score down`() {
        val supportedObservation = IndependentCorrectObservation(
            itemFamilyId = "family-a",
            studyDayEpochDay = 1,
            occurredAtEpochMillis = now - DAY_MILLIS,
            evidenceWeight = 1.0,
            calibration = supported(),
        )
        val expiredObservation = supportedObservation.copy(
            itemFamilyId = "family-b",
            calibration = expired(),
        )
        val allSupported = mastery(conservative = 0.7, observations = listOf(supportedObservation))
        val halfExpired = mastery(
            conservative = 0.7,
            observations = listOf(supportedObservation, expiredObservation),
        )

        val smoothedSupported = MasterySmoothing.smoothedMasteryScore(allSupported, now)
        val smoothedExpired = MasterySmoothing.smoothedMasteryScore(halfExpired, now)

        assertTrue(smoothedExpired < smoothedSupported)
    }

    @Test
    fun `ancient and fresh identical observations share the same ema ratio`() {
        // Relative decay only matters across mixed ages: two observations of
        // equal weight and equal support produce the same ratio regardless of
        // how old they are together.
        val bothAncient = mastery(
            conservative = 0.5,
            observations = listOf(
                observationAt(now - 60 * DAY_MILLIS, supported()),
                observationAt(now - 60 * DAY_MILLIS, supported()),
            ),
        )
        val bothFresh = mastery(
            conservative = 0.5,
            observations = listOf(
                observationAt(now - DAY_MILLIS, supported()),
                observationAt(now - DAY_MILLIS, supported()),
            ),
        )

        assertEquals(
            MasterySmoothing.smoothedMasteryScore(bothAncient, now),
            MasterySmoothing.smoothedMasteryScore(bothFresh, now),
            1e-9,
        )
    }

    private fun mastery(
        conservative: Double,
        observations: List<IndependentCorrectObservation>,
    ) = KnowledgeMasteryState(
        knowledgeNodeId = "kc-a",
        masteryScore = conservative + 0.1,
        conservativeMasteryScore = conservative,
        evidenceMass = observations.sumOf(IndependentCorrectObservation::evidenceWeight).coerceAtLeast(1.0),
        independentCorrectObservations = observations,
        status = MasteryStatus.LEARNING,
        calibrationSupport = CalibrationSupport.SUPPORTED,
        projectorVersion = LearningProjector.VERSION,
        checkpointSequence = 1,
    )

    private fun observationAt(at: Long, calibration: CalibrationSnapshot) =
        IndependentCorrectObservation(
            itemFamilyId = "family-x",
            studyDayEpochDay = at / DAY_MILLIS,
            occurredAtEpochMillis = at,
            evidenceWeight = 1.0,
            calibration = calibration,
        )

    private fun supported() = CalibrationSnapshot(
        CalibrationSupport.SUPPORTED,
        "calibration-source",
        "calibration-v1",
        0,
        Long.MAX_VALUE / 2,
    )

    private fun expired() = CalibrationSnapshot(
        CalibrationSupport.UNSUPPORTED,
        "calibration-source",
        "calibration-v1",
        0,
        1,
    )

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
