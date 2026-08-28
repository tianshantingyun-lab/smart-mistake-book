package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * Behavior tests for the FSRS-6 projection (spec mastery-scheduling §2.4,
 * §2.15, §2.16, §2.10) and the legacy kill-switch equivalence.
 */
class FsrsProjectionBehaviorTest {

    private val projector = LearningProjector()
    private val legacyProjector = LearningProjector(
        forgettingCurve = ForgettingCurve(),
        memoryUpdateModel = LegacyExponentialMemoryUpdateModel(),
    )

    @Test
    fun `first review seeds initial stability and difficulty for its rating`() {
        val result = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("a-1", 1, easyEvidence())),
            1,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(FsrsScheduleMath.initialStability(FsrsRating.GOOD), memory.stabilityDays, 1e-9)
        assertEquals(
            FsrsScheduleMath.clampDifficulty(FsrsScheduleMath.initialDifficulty(FsrsRating.GOOD)),
            memory.difficulty,
            1e-9,
        )
        assertEquals(1, memory.consecutiveCrossDaySuccess)
        assertEquals(LearningEvidenceReason.INDEPENDENT_CORRECT.name, memory.lastEvidenceReason)
        assertEquals(LearningEvidenceDirection.POSITIVE.name, memory.lastEvidenceDirection)
    }

    @Test
    fun `same day review takes the short term branch and never grows stability for good`() {
        val first = projector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("a-1", 1, easyEvidence(), occurredAt = DAY_MILLIS)),
            1,
        ).snapshot
        val firstStability = first.problemMemoryStates.getValue("unit-1").stabilityDays

        val second = projector.project(
            first,
            listOf(attempt("a-2", 2, easyEvidence(), occurredAt = DAY_MILLIS + 60_000)),
            2,
        )
        val memory = second.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(
            FsrsScheduleMath.shortTermStability(firstStability, FsrsRating.GOOD),
            memory.stabilityDays,
            1e-9,
        )
        // Same-day repeats never reach the cross-day long-run branch.
        assertEquals(1, memory.consecutiveCrossDaySuccess)
    }

    @Test
    fun `cross day again counts toward the leech streak and lowers stability`() {
        val seeded = seededCrossDay()
        val lastReviewedAt = seeded.memory.lastReviewedAtEpochMillis
        val lapseAt = lastReviewedAt + 3 * DAY_MILLIS
        val result = projector.project(
            seeded.snapshot,
            listOf(
                attempt(
                    "a-lapse",
                    4,
                    wrongEvidence(),
                    occurredAt = lapseAt,
                ),
            ),
            4,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(1, memory.consecutiveCrossDayAgain)
        assertTrue(
            memory.stabilityDays <=
                seeded.memory.stabilityDays / exp(
                    FsrsScheduleMath.DEFAULT_PARAMETERS[17] * FsrsScheduleMath.DEFAULT_PARAMETERS[18],
                ) + 1e-9,
        )
    }

    @Test
    fun `leech freezes difficulty at its ceiling`() {
        val leeched = seededCrossDay().memory.copy(
            lapseCount = ProblemMemoryState.LEECH_LAPSE_THRESHOLD,
            consecutiveCrossDayAgain = ProblemMemoryState.LEECH_AGAIN_STREAK,
            difficulty = 8.0,
        )
        val snapshot = LearnerSnapshot(
            learnerId = "learner-1",
            problemMemoryStates = mapOf("unit-1" to leeched),
            checkpoint = ProjectionCheckpoint(10, LearningProjector.VERSION, 10 * DAY_MILLIS),
            generatedAtEpochMillis = 10 * DAY_MILLIS,
        )

        val result = projector.project(
            snapshot,
            listOf(
                attempt(
                    "a-lapse",
                    11,
                    wrongEvidence(),
                    occurredAt = leeched.lastReviewedAtEpochMillis + 3 * DAY_MILLIS,
                ),
            ),
            11,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertTrue(memory.isLeeched)
        // Spec 2.16: a leeched card's difficulty may not climb further, even
        // though the Again rating would normally push it up.
        assertEquals(8.0, memory.difficulty, 1e-9)
    }

    @Test
    fun `three cross day successes with a ninety day interval schedule maintenance`() {
        var snapshot = LearnerSnapshot.empty("learner-1")
        var sequence = 0L
        // Drive the card through enough successful cross-day reviews for a
        // ninety-day interval; the graduation override must then kick in.
        var nextAt = 0L
        for (index in 1..14) {
            sequence += 1
            val occurredAt = if (index == 1) 0 else nextAt
            val result = projector.project(
                snapshot,
                listOf(attempt("a-$index", sequence, easyEvidence(), occurredAt = occurredAt)),
                sequence,
            )
            snapshot = result.snapshot
            val memory = snapshot.problemMemoryStates.getValue("unit-1")
            nextAt = memory.nextReviewAtEpochMillis
            if (memory.consecutiveCrossDaySuccess >= 3) {
                val regularInterval = FsrsScheduleMath.intervalDays(
                    memory.stabilityDays,
                    FsrsMemoryUpdateModel.DEFAULT_DESIRED_RETENTION,
                )
                if (regularInterval >= 90) {
                    val maintenanceInterval = FsrsScheduleMath.intervalDays(
                        memory.stabilityDays,
                        LearningProjector.GRADUATION_TARGET_RETENTION,
                    )
                    val scheduledInterval =
                        (memory.nextReviewAtEpochMillis - occurredAt) / DAY_MILLIS
                    assertEquals(maintenanceInterval.toLong(), scheduledInterval)
                    assertTrue(maintenanceInterval >= regularInterval)
                    return
                }
            }
        }
        throw AssertionError("graduation never scheduled a maintenance interval")
    }

    @Test
    fun `legacy kill switch keeps the audited exponential behavior`() {
        val result = legacyProjector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(attempt("a-1", 1, easyEvidence(weight = 1.0))),
            1,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        // projection-v4 formula: S = 0.5 * (1 + 1.6 * 1.0) + 0.25
        assertEquals(0.5 * 2.6 + 0.25, memory.stabilityDays, 1e-9)
        assertEquals(5.5 - 0.72, memory.difficulty, 1e-9)
    }

    @Test
    fun `legacy kill switch keeps the ten minute reveal review`() {
        val revealed = attempt("a-1", 1).copy(
            evidence = LearningEvidence(
                LearningEvidenceDirection.NONE,
                0.0,
                LearningEvidenceReason.ANSWER_REVEALED,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.ANSWER_REVEALED,
        )

        val result = legacyProjector.project(
            LearnerSnapshot.empty("learner-1"),
            listOf(revealed),
            1,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(revealed.occurredAtEpochMillis + 10 * 60_000, memory.nextReviewAtEpochMillis)
    }

    private fun seededCrossDay(): LearningProjectionResult {
        var snapshot = LearnerSnapshot.empty("learner-1")
        var nextAt = 0L
        for (index in 1..3) {
            val result = projector.project(
                snapshot,
                listOf(attempt("a-$index", index.toLong(), easyEvidence(), occurredAt = nextAt)),
                index.toLong(),
            )
            snapshot = result.snapshot
            nextAt = snapshot.problemMemoryStates.getValue("unit-1").nextReviewAtEpochMillis
        }
        return LearningProjectionResult(snapshot, emptySet(), emptySet(), emptySet())
    }

    private val LearningProjectionResult.memory
        get() = snapshot.problemMemoryStates.getValue("unit-1")

    private fun attempt(
        id: String,
        sequence: Long,
        evidence: LearningEvidence = easyEvidence(),
        occurredAt: Long = sequence * DAY_MILLIS,
    ): Attempt {
        val outcome = when {
            evidence.direction == LearningEvidenceDirection.NONE -> ProblemMemoryOutcome.ANSWER_REVEALED
            evidence.signedWeight > 0 -> ProblemMemoryOutcome.INDEPENDENT_RECALL
            else -> ProblemMemoryOutcome.RETRIEVAL_FAILURE
        }
        return Attempt(
            attemptId = id,
            presentationId = "presentation-$id",
            responseOrdinal = 1,
            assessmentSnapshot = AssessmentEvidenceSnapshot(
                snapshotId = "snapshot-$id",
                assessmentItemId = "assessment-$id",
                practiceUnitId = "unit-1",
                problemRevisionId = "revision-1",
                answerSpecId = "answer-1",
                itemFamilyId = "family-$id",
                sourceBundleId = "source-$id",
                taxonomyVersion = "taxonomy-v1",
                verification = AssessmentSnapshotVerification.VERIFIED,
                calibration = CalibrationSnapshot(
                    CalibrationSupport.SUPPORTED,
                    "calibration-source",
                    "calibration-v1",
                    0,
                    400 * DAY_MILLIS,
                ),
                attributions = listOf(
                    KnowledgeEvidenceAttribution(
                        bindingId = "binding-$id",
                        knowledgeNodeId = "kc-a",
                        weight = 1.0,
                        basisRevisionId = "revision-1",
                        taxonomyVersion = "taxonomy-v1",
                        role = EvidenceAttributionRole.PRIMARY,
                        certainty = EvidenceAttributionCertainty.DIRECT,
                    ),
                ),
                capturedAtEpochMillis = 0,
            ),
            evidence = evidence,
            problemMemoryOutcome = outcome,
            occurredAtEpochMillis = occurredAt,
            durationSeconds = 60,
            studyDay = StudyDayContext(occurredAt / DAY_MILLIS, "UTC", 0),
            eventSequence = sequence,
        )
    }

    private fun easyEvidence(weight: Double = 1.0) = LearningEvidence(
        LearningEvidenceDirection.POSITIVE,
        weight,
        LearningEvidenceReason.INDEPENDENT_CORRECT,
    )

    private fun wrongEvidence() = LearningEvidence(
        LearningEvidenceDirection.NEGATIVE,
        1.0,
        LearningEvidenceReason.INDEPENDENT_INCORRECT,
    )

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
