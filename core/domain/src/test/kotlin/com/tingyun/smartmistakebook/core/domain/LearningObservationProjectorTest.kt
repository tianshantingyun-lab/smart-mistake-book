package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningObservationDirection
import com.tingyun.smartmistakebook.core.model.LearningObservationEvidenceLevel
import com.tingyun.smartmistakebook.core.model.LearningObservationIndependence
import com.tingyun.smartmistakebook.core.model.LearningObservationKnowledgeAttribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningObservationProjectorTest {
    private val projector = LearningProjector()

    @Test
    fun `confirmed observation updates only direct mastery and never problem memory`() {
        val event = observation(
            sequence = 1,
            direction = LearningObservationDirection.POSITIVE,
            attributions = listOf(
                attribution(
                    bindingId = "binding-direct",
                    knowledgeNodeId = "knowledge-direct",
                    certainty = EvidenceAttributionCertainty.DIRECT,
                ),
                attribution(
                    bindingId = "binding-ambiguous",
                    knowledgeNodeId = "knowledge-ambiguous",
                    certainty = EvidenceAttributionCertainty.AMBIGUOUS,
                ),
            ),
        )

        val result = projector.project(
            previous = LearnerSnapshot.empty("learner-1", LearningProjector.VERSION),
            events = listOf(event),
            knownLedgerHeadSequence = 1,
        )

        assertTrue(result.snapshot.problemMemoryStates.isEmpty())
        assertEquals(setOf("knowledge-direct"), result.snapshot.knowledgeMasteryStates.keys)
        assertEquals(setOf(event.eventId), result.appliedLearningObservationEventIds)
        assertEquals(setOf(event.eventId), result.ambiguousLearningObservationEventIds)
    }

    @Test
    fun `positive and negative observations persist and exact crash replay is idempotent`() {
        val positive = observation(
            sequence = 1,
            direction = LearningObservationDirection.POSITIVE,
        )
        val first = projector.project(
            previous = LearnerSnapshot.empty("learner-1", LearningProjector.VERSION),
            events = listOf(positive),
            knownLedgerHeadSequence = 1,
        )
        val afterPositive = first.snapshot.knowledgeMasteryStates.getValue("knowledge-direct")

        val crashReplay = projector.project(
            previous = first.snapshot,
            events = listOf(positive),
            knownLedgerHeadSequence = 1,
        )
        assertEquals(first.snapshot, crashReplay.snapshot)
        assertEquals(setOf(positive.eventId), crashReplay.ignoredLearningObservationEventIds)

        val negative = observation(
            sequence = 2,
            direction = LearningObservationDirection.NEGATIVE,
            eventId = "observation-negative",
            candidateId = "candidate-negative",
            occurredAtEpochMillis = 2_000,
        )
        val second = projector.project(
            previous = first.snapshot,
            events = listOf(negative),
            knownLedgerHeadSequence = 2,
        )
        val afterNegative = second.snapshot.knowledgeMasteryStates.getValue("knowledge-direct")

        assertTrue(afterNegative.probabilityIndependentCorrect < afterPositive.probabilityIndependentCorrect)
        assertTrue(afterNegative.evidenceMass > afterPositive.evidenceMass)
        assertEquals(2_000L, afterNegative.lastEvidenceAtEpochMillis)
        assertFalse(second.snapshot.problemMemoryStates.containsKey("unit-1"))
    }

    private fun observation(
        sequence: Long,
        direction: LearningObservationDirection,
        eventId: String = "observation-$sequence",
        candidateId: String = "candidate-$sequence",
        occurredAtEpochMillis: Long = 1_000,
        attributions: List<LearningObservationKnowledgeAttribution> = listOf(
            attribution(
                bindingId = "binding-direct",
                knowledgeNodeId = "knowledge-direct",
                certainty = EvidenceAttributionCertainty.DIRECT,
            ),
        ),
    ) = AttributedLearningObservationEvent(
        eventId = eventId,
        candidateId = candidateId,
        learnerId = "learner-1",
        practiceUnitId = "unit-1",
        problemRevisionId = "revision-1",
        direction = direction,
        evidenceLevel = LearningObservationEvidenceLevel.CONFIRMED,
        evidenceWeight = 0.8,
        independence = LearningObservationIndependence.INDEPENDENT,
        attributions = attributions,
        occurredAtEpochMillis = occurredAtEpochMillis,
        confirmedAtEpochMillis = occurredAtEpochMillis,
        modelVersion = "model-v1",
        evidenceLocator = "response:$eventId",
        eventSequence = sequence,
    )

    private fun attribution(
        bindingId: String,
        knowledgeNodeId: String,
        certainty: EvidenceAttributionCertainty,
    ) = LearningObservationKnowledgeAttribution(
        bindingId = bindingId,
        knowledgeNodeId = knowledgeNodeId,
        weight = 1.0,
        basisRevisionId = "revision-1",
        taxonomyVersion = "taxonomy-v1",
        role = EvidenceAttributionRole.PRIMARY,
        certainty = certainty,
    )
}
