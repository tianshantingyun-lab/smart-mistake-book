package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of the prediction audit closed loop (audit §6.3 / §6.4):
 * prediction generation, sink abstraction, and outcome backfill pure logic.
 */
class HLRPredictionAuditServiceTest {

    private val now = 1_700_000_000_000L
    private val service = HLRPredictionAuditService()

    @Test
    fun `predict produces deterministic shadow prediction with conservative score`() {
        val features = HLRFeatures(
            independentCorrectCount = 2.0,
            evidenceMass = 2.0,
            difficulty = 0.5,
            timeBetweenReviewsDays = 1.0,
            daysSinceFirstSeen = 5.0,
            consecutiveCorrectStreak = 1.0,
        )
        val prediction = service.predict(
            RecallPredictionInput(
                practiceUnitId = "unit-a",
                features = features,
                deltaSeconds = 86_400.0,
                nowEpochMillis = now,
            ),
        )

        assertEquals("shadow-unit-a-$now", prediction.predictionId)
        assertEquals("hlr-shadow-v1:0.1.0-experimental", prediction.modelVersion)
        assertEquals(HLRPredictionAuditService.DEFAULT_PREDICTION_WINDOW_MILLIS, prediction.horizonMillis)
        assertTrue(prediction.probability in 0.0..1.0)
        assertTrue(
            abs(prediction.conservativeScore - prediction.probability * 0.9) < 1e-9,
        )
        assertEquals(features.toString().hashCode().toString(), prediction.featureFingerprint)
        // Determinism: same input must produce the same audit row.
        val again = service.predict(
            RecallPredictionInput(
                practiceUnitId = "unit-a",
                features = features,
                deltaSeconds = 86_400.0,
                nowEpochMillis = now,
            ),
        )
        assertEquals(prediction, again)
    }

    @Test
    fun `planPredictions covers only units with memory states and frames a window`() {
        val request = ReviewPlanningRequest(
            learnerSnapshot = snapshot(listOf(memory("unit-a"))),
            candidates = listOf(
                candidate("unit-a"),
                candidate("unit-b"),
            ),
            localDayEpochDay = 10,
            timeZoneId = "Asia/Shanghai",
            timeBudgetSeconds = 600,
            planningAtEpochMillis = now,
        )

        val audits = service.planPredictions(
            request = request,
            scoredPracticeUnitIds = listOf("unit-a", "unit-b"),
        )

        assertEquals(1, audits.size)
        val audit = audits.single()
        assertEquals("unit-a", audit.practiceUnitId)
        assertEquals("kc-a", audit.knowledgeNodeId)
        assertEquals(now, audit.windowStartEpochMillis)
        assertEquals(
            now + HLRPredictionAuditService.DEFAULT_PREDICTION_WINDOW_MILLIS,
            audit.windowEndEpochMillis,
        )
        assertEquals(now, audit.prediction.generatedAtEpochMillis)
    }

    @Test
    fun `record to resolve chain completes through an in-memory sink`() {
        val sink = InMemoryAuditSink()
        val request = ReviewPlanningRequest(
            learnerSnapshot = snapshot(listOf(memory("unit-a"))),
            candidates = listOf(candidate("unit-a")),
            localDayEpochDay = 10,
            timeZoneId = "Asia/Shanghai",
            timeBudgetSeconds = 600,
            planningAtEpochMillis = now,
        )

        kotlinx.coroutines.runBlocking {
            service.planPredictions(
                request = request,
                scoredPracticeUnitIds = listOf("unit-a"),
            ).forEach { sink.record(it) }
            assertEquals(1, sink.pending.size)

            val resolved = sink.resolveOutcome(
                practiceUnitId = "unit-a",
                wasIndependentCorrect = true,
                observedAtEpochMillis = now + 3_600_000,
                responseLatencyMs = 42_000,
                hintCount = 0,
            )
            assertEquals(1, resolved)
            assertTrue(sink.pending.isEmpty())
            val outcome = sink.outcomes.single()
            assertEquals("unit-a", outcome.practiceUnitId)
            assertTrue(outcome.wasIndependentCorrect)
            assertEquals(now + 3_600_000, outcome.observedAtEpochMillis)
        }
    }

    private fun snapshot(memories: List<ProblemMemoryState>): LearnerSnapshot {
        val mastery = KnowledgeMasteryState(
            knowledgeNodeId = "kc-a",
            masteryScore = 0.4,
            conservativeMasteryScore = 0.2,
            evidenceMass = 2.0,
            status = MasteryStatus.LEARNING,
            calibrationSupport = CalibrationSupport.SUPPORTED,
            projectorVersion = LearningProjector.VERSION,
            checkpointSequence = 4,
        )
        return LearnerSnapshot(
            learnerId = "learner-1",
            problemMemoryStates = memories.associateBy(ProblemMemoryState::practiceUnitId),
            knowledgeMasteryStates = mapOf("kc-a" to mastery),
            checkpoint = ProjectionCheckpoint(4, LearningProjector.VERSION, now),
            generatedAtEpochMillis = now,
        )
    }

    private fun memory(unitId: String) = ProblemMemoryState(
        practiceUnitId = unitId,
        stabilityDays = 1.0,
        difficulty = 0.5,
        lastReviewedAtEpochMillis = now - 5 * DAY_MILLIS,
        nextReviewAtEpochMillis = now - DAY_MILLIS,
        lapseCount = 1,
        lastAttemptId = "attempt-$unitId",
        projectorVersion = LearningProjector.VERSION,
        checkpointSequence = 4,
    )

    private fun candidate(unitId: String) = ReviewCandidate(
        practiceUnitId = unitId,
        knowledgeNodeIds = setOf("kc-a"),
        itemFamilyId = "family-$unitId",
        sourceBundleId = null,
        difficulty = 0.5,
        estimatedDurationSeconds = 60,
    )

    private data class ResolvedOutcome(
        val practiceUnitId: String,
        val wasIndependentCorrect: Boolean,
        val observedAtEpochMillis: Long,
    )

    private class InMemoryAuditSink : PredictionAuditSink {
        val pending = mutableListOf<RecallPredictionAudit>()
        val outcomes = mutableListOf<ResolvedOutcome>()

        override suspend fun record(prediction: RecallPredictionAudit) {
            pending += prediction
        }

        override suspend fun resolveOutcome(
            practiceUnitId: String,
            wasIndependentCorrect: Boolean,
            observedAtEpochMillis: Long,
            responseLatencyMs: Long?,
            hintCount: Int,
        ): Int {
            val matches = pending.filter { it.practiceUnitId == practiceUnitId }
            pending.removeAll(matches.toSet())
            repeat(matches.size) {
                outcomes += ResolvedOutcome(practiceUnitId, wasIndependentCorrect, observedAtEpochMillis)
            }
            return matches.size
        }
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
