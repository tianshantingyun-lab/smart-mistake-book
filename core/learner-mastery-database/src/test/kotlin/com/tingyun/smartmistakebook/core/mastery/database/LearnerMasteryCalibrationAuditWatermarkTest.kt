package com.tingyun.smartmistakebook.core.mastery.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryCalibrationAuditWatermarkTest {
    @Test
    fun fixedWidthSingletonRoundTripsAndRejectsFingerprintCorruption() {
        val persisted =
            emptyState().forPersistence(
                previous = null,
                nextTransition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
            )

        val encoded = persisted.encode()

        assertTrue(encoded.length < 2_048)
        assertEquals(persisted, decodeLearnerMasteryCalibrationAuditWatermark(encoded))
        val corrupted = encoded.dropLast(1) + if (encoded.last() == 'a') "b" else "a"
        assertNull(decodeLearnerMasteryCalibrationAuditWatermark(corrupted))
    }

    @Test
    fun sameStateMatchesWithoutCreatingAnotherPersistenceRevision() {
        val persisted =
            emptyState().forPersistence(
                previous = null,
                nextTransition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
            )
        val sameCurrentState = emptyState()

        assertTrue(persisted.exactlyMatches(sameCurrentState))
        assertEquals(1L, persisted.auditRevision)
    }

    @Test
    fun immutableHeadMustMoveWhenAnAppendOnlyCountMoves() {
        val prior =
            emptyState().copy(
                eventCount = 1L,
                eventMaxRowId = 1L,
                eventMaxSequence = 1L,
                eventHeadFingerprint = "c".repeat(64),
            ).forPersistence(
                previous = null,
                nextTransition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
            )
        val invalidNext = prior.copy(eventCount = 2L, eventMaxRowId = 2L)
        val validNext =
            invalidNext.copy(
                eventMaxSequence = 2L,
                eventHeadFingerprint = "d".repeat(64),
            )

        assertTrue(!prior.canIncrementallyAdvanceTo(invalidNext))
        assertTrue(prior.canIncrementallyAdvanceTo(validNext))
    }

    @Test
    fun appendOnlyRowsWithIncrementalAuditorsCanAdvance() {
        val prior =
            emptyState().forPersistence(
                previous = null,
                nextTransition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
            )
        val advancingStates =
            listOf(
                prior.copy(
                    attributionCount = 1L,
                    attributionMaxRowId = 1L,
                    attributionHeadFingerprint = "c".repeat(64),
                ),
                prior.copy(
                    supersessionCount = 1L,
                    supersessionMaxRowId = 1L,
                    supersessionHeadFingerprint = "d".repeat(64),
                ),
                prior.copy(
                    calibrationSnapshotCount = 1L,
                    calibrationSnapshotMaxRowId = 1L,
                    calibrationSnapshotHeadFingerprint = "e".repeat(64),
                ),
            )

        advancingStates.forEach { next ->
            assertTrue(prior.canIncrementallyAdvanceTo(next))
            assertEquals(
                LearnerMasteryCalibrationAuditTransition.INCREMENTAL,
                next.forPersistence(
                    previous = prior,
                    nextTransition = LearnerMasteryCalibrationAuditTransition.INCREMENTAL,
                ).transition,
            )
        }
    }

    @Test
    fun reviewRowsWithIncrementalAuditorsCanAdvance() {
        val prior =
            emptyState().forPersistence(
                previous = null,
                nextTransition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
            )
        val advancingStates =
            listOf(
                prior.copy(
                    reviewCaseCount = 1L,
                    reviewCaseMaxRowId = 1L,
                    reviewCaseHeadFingerprint = "c".repeat(64),
                ),
                prior.copy(
                    reviewResolutionCount = 1L,
                    reviewResolutionMaxRowId = 1L,
                    reviewResolutionHeadFingerprint = "d".repeat(64),
                ),
            )

        advancingStates.forEach { next ->
            assertTrue(prior.canIncrementallyAdvanceTo(next))
            assertEquals(
                LearnerMasteryCalibrationAuditTransition.INCREMENTAL,
                next.forPersistence(
                    previous = prior,
                    nextTransition = LearnerMasteryCalibrationAuditTransition.INCREMENTAL,
                ).transition,
            )
        }
    }

    @Test
    fun incrementallyAuditedRowsMayStillBeResetAfterAnExplicitFullAudit() {
        val prior =
            emptyState().forPersistence(
                previous = null,
                nextTransition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
            )
        val fullAuditStates =
            listOf(
                prior.copy(
                    attributionCount = 1L,
                    attributionMaxRowId = 1L,
                    attributionHeadFingerprint = "c".repeat(64),
                ),
                prior.copy(
                    supersessionCount = 1L,
                    supersessionMaxRowId = 1L,
                    supersessionHeadFingerprint = "d".repeat(64),
                ),
                prior.copy(
                    calibrationSnapshotCount = 1L,
                    calibrationSnapshotMaxRowId = 1L,
                    calibrationSnapshotHeadFingerprint = "e".repeat(64),
                ),
            )

        fullAuditStates.forEach { next ->
            assertTrue(prior.canIncrementallyAdvanceTo(next))
            assertEquals(
                LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
                next.forPersistence(
                    previous = prior,
                    nextTransition = LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
                ).transition,
            )
        }
    }

    private fun emptyState(): LearnerMasteryCalibrationAuditWatermark =
        LearnerMasteryCalibrationAuditWatermark(
            protocolVersion = LEARNER_MASTERY_CALIBRATION_AUDIT_PROTOCOL,
            guardFingerprint = "b".repeat(64),
            schemaVersion = LEARNER_MASTERY_DATABASE_VERSION,
            eventCount = 0L,
            eventMaxRowId = 0L,
            eventMaxSequence = 0L,
            eventHeadFingerprint = ZERO_FINGERPRINT,
            attributionCount = 0L,
            attributionMaxRowId = 0L,
            attributionHeadFingerprint = ZERO_FINGERPRINT,
            reviewCaseCount = 0L,
            reviewCaseMaxRowId = 0L,
            reviewCaseHeadFingerprint = ZERO_FINGERPRINT,
            reviewResolutionCount = 0L,
            reviewResolutionMaxRowId = 0L,
            reviewResolutionHeadFingerprint = ZERO_FINGERPRINT,
            supersessionCount = 0L,
            supersessionMaxRowId = 0L,
            supersessionHeadFingerprint = ZERO_FINGERPRINT,
            calibrationSnapshotCount = 0L,
            calibrationSnapshotMaxRowId = 0L,
            calibrationSnapshotHeadFingerprint = ZERO_FINGERPRINT,
            projectionCount = 0L,
            projectionMaxEventSequence = 0L,
            activeGenerationCount = 0L,
            activeGenerationId = 0L,
            activeGenerationSnapshotFingerprint = ZERO_FINGERPRINT,
            activeGenerationManifestFingerprint = ZERO_FINGERPRINT,
            activeGenerationProjectionPolicyFingerprint = ZERO_FINGERPRINT,
            activeGenerationCalibrationVersionFingerprint = ZERO_FINGERPRINT,
        )

    private companion object {
        val ZERO_FINGERPRINT = "0".repeat(64)
    }
}
