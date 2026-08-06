package com.tingyun.smartmistakebook.core.mastery.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionalBudgetFingerprintPageCompatibilityTest {
    @Test
    fun old256CheckpointResumesInto512PagesWithTheCanonicalGoldenReceipt() {
        val effectiveHistory =
            (0 until SOURCE_EVENT_COUNT)
                .asSequence()
                .filterNot { sourceIndex -> sourceIndex == SUPERSEDED_SOURCE_INDEX }
                .map(::replayRow)
                .toList()
        assertEquals(EFFECTIVE_ROW_COUNT, effectiveHistory.size)
        assertFalse(effectiveHistory.any { it.eventId == "event-0137" })
        assertTrue(effectiveHistory.any { it.direction == "POSITIVE" })
        assertTrue(effectiveHistory.any { it.direction == "NEGATIVE" })

        val seed = directionalBudgetInputSeedFingerprint()
        assertEquals(SEED_GOLDEN, seed)

        val old256Checkpoint =
            extendDirectionalBudgetInputFingerprint(
                previousFingerprint = seed,
                rows = effectiveHistory.take(OLD_PAGE_SIZE),
            )
        assertEquals(OLD_256_CHECKPOINT_GOLDEN, old256Checkpoint)

        val recoveredThroughNew512Page =
            extendDirectionalBudgetInputFingerprint(
                previousFingerprint = old256Checkpoint,
                rows = effectiveHistory.drop(OLD_PAGE_SIZE).take(NEW_PAGE_SIZE),
            )
        val recoveredReceipt =
            extendDirectionalBudgetInputFingerprint(
                previousFingerprint = recoveredThroughNew512Page,
                rows = effectiveHistory.drop(OLD_PAGE_SIZE + NEW_PAGE_SIZE),
            )

        val freshNew512Page =
            extendDirectionalBudgetInputFingerprint(
                previousFingerprint = seed,
                rows = effectiveHistory.take(NEW_PAGE_SIZE),
            )
        val freshReceipt =
            extendDirectionalBudgetInputFingerprint(
                previousFingerprint = freshNew512Page,
                rows = effectiveHistory.drop(NEW_PAGE_SIZE),
            )

        assertEquals(CANONICAL_RECEIPT_GOLDEN, recoveredReceipt)
        assertEquals(CANONICAL_RECEIPT_GOLDEN, freshReceipt)
        assertEquals(freshReceipt, recoveredReceipt)
    }

    private fun replayRow(sourceIndex: Int): MasteryEventAttributionReplayRow =
        MasteryEventAttributionReplayRow(
            eventId = "event-${sourceIndex.toString().padStart(4, '0')}",
            candidateId = "candidate-$sourceIndex",
            sourceFactId = "fact-$sourceIndex",
            sourceProofFingerprint = fingerprint(40_000 + sourceIndex),
            learnerId = "learner-golden",
            subject = "MATHEMATICS",
            direction = if (sourceIndex % 2 == 0) "POSITIVE" else "NEGATIVE",
            eventSequence = sourceIndex + 1L,
            occurredAtEpochMillis = 1_700_000_000_000L + sourceIndex,
            admittedAtEpochMillis = 1_700_010_000_000L + sourceIndex,
            projectionPolicyVersion = "projection-golden-v1",
            admissionPolicyVersion = "admission-golden-v1",
            calibrationVersion = "calibration-golden-v1",
            calibrationSnapshotFingerprint = null,
            calibrationProfileId = null,
            reviewResolutionFingerprint = null,
            problemFamilyFingerprint = fingerprint(30_000 + sourceIndex % 31),
            presentationFingerprint = fingerprint(20_000 + sourceIndex % 29),
            evidenceQualityMicros = 900_000L,
            independentlyAnswered = true,
            eventCanonicalFingerprint = fingerprint(sourceIndex + 1),
            ordinal = 0,
            knowledgeNodeId = "math.node.${(sourceIndex % 17).toString().padStart(2, '0')}",
            taxonomyVersion = "taxonomy-golden-v1",
            knowledgePackVersion = "pack-golden-v1",
            knowledgeNodeRefFingerprint = fingerprint(10_000 + sourceIndex),
            evidenceMassMicros = 100_000L + sourceIndex % 5 * 25_000L,
        )

    private fun fingerprint(seed: Int): String = seed.toString(16).padStart(64, '0')

    private companion object {
        const val SOURCE_EVENT_COUNT = 772
        const val SUPERSEDED_SOURCE_INDEX = 137
        const val EFFECTIVE_ROW_COUNT = SOURCE_EVENT_COUNT - 1
        const val OLD_PAGE_SIZE = 256
        const val NEW_PAGE_SIZE = 512
        const val SEED_GOLDEN =
            "01de47aaab682e734171859eec5cfba336cdd3a742f5831eaefc3797bb2f3803"
        const val OLD_256_CHECKPOINT_GOLDEN =
            "3207378d74f085a5084b55ae27216f0e0279d86d335fd5235142fd226fb132a6"
        const val CANONICAL_RECEIPT_GOLDEN =
            "ff1fde66c18cd993bfbcc88aa1b0f5a7aec91fb55cd454c00047db6e40e53f68"
    }
}
