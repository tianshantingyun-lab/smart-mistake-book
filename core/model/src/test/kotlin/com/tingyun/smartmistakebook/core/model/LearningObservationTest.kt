package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class LearningObservationTest {
    @Test
    fun `candidate validates immutable provenance and direct attribution mass`() {
        assertIllegalArgument {
            candidate(modelVersion = "")
        }
        assertIllegalArgument {
            candidate(evidenceLocator = " ")
        }
        assertIllegalArgument {
            candidate(evidenceWeight = Double.NaN)
        }
        assertIllegalArgument {
            candidate(
                proposedAttributions = listOf(
                    attribution("binding-1", "knowledge-1", 0.7),
                    attribution("binding-2", "knowledge-2", 0.4),
                ),
            )
        }
    }

    @Test
    fun `candidate remains distinct from confirmed projectable event`() {
        val candidate = candidate()
        val event = AttributedLearningObservationEvent(
            eventId = "observation-1",
            candidateId = candidate.candidateId,
            learnerId = candidate.learnerId,
            practiceUnitId = requireNotNull(candidate.practiceUnitId),
            problemRevisionId = requireNotNull(candidate.problemRevisionId),
            direction = candidate.direction,
            evidenceLevel = LearningObservationEvidenceLevel.CONFIRMED,
            evidenceWeight = candidate.evidenceWeight,
            independence = candidate.independence,
            attributions = candidate.proposedAttributions,
            occurredAtEpochMillis = candidate.occurredAtEpochMillis,
            confirmedAtEpochMillis = candidate.updatedAtEpochMillis,
            modelVersion = candidate.modelVersion,
            evidenceLocator = candidate.evidenceLocator,
            eventSequence = 3,
        )

        val ledgerEvent: IncrementalLearningEvent = event
        assertEquals("observation-1", ledgerEvent.ledgerEventId)
        assertIllegalArgument {
            event.copy(evidenceLevel = LearningObservationEvidenceLevel.LOW_CONFIDENCE)
        }
        assertIllegalArgument {
            event.copy(confirmedAtEpochMillis = event.occurredAtEpochMillis - 1)
        }
    }

    @Test
    fun `applied observation record validates replay identity`() {
        assertIllegalArgument {
            AppliedLearningObservationRecord(
                observationEventId = "",
                canonicalFingerprint = "fingerprint",
                eventSequence = 1,
            )
        }
        assertIllegalArgument {
            AppliedLearningObservationRecord(
                observationEventId = "observation-1",
                canonicalFingerprint = "fingerprint",
                eventSequence = 0,
            )
        }
    }

    @Test
    fun `observation fingerprints use stable unambiguous encoding`() {
        val candidate = candidate()
        val event = AttributedLearningObservationEvent(
            eventId = "observation-1",
            candidateId = candidate.candidateId,
            learnerId = candidate.learnerId,
            practiceUnitId = requireNotNull(candidate.practiceUnitId),
            problemRevisionId = requireNotNull(candidate.problemRevisionId),
            direction = candidate.direction,
            evidenceLevel = LearningObservationEvidenceLevel.CONFIRMED,
            evidenceWeight = candidate.evidenceWeight,
            independence = candidate.independence,
            attributions = candidate.proposedAttributions,
            occurredAtEpochMillis = candidate.occurredAtEpochMillis,
            confirmedAtEpochMillis = candidate.updatedAtEpochMillis,
            modelVersion = candidate.modelVersion,
            evidenceLocator = candidate.evidenceLocator,
            eventSequence = 3,
        )

        assertEquals(
            "28a2c398e8367454951e71d2f5cbe26950199e226342f0fb1eb8d49432e580b6",
            LearningLedgerFingerprint.learningObservationCandidate(candidate),
        )
        assertEquals(
            "144a3d4f37c70428db6800a1208e4a4c3ce72977b9979efab4fa2b0d221797da",
            LearningLedgerFingerprint.learningObservation(event),
        )

        val unanchored = candidate.copy(
            practiceUnitId = null,
            problemRevisionId = null,
            proposedAttributions = emptyList(),
        )
        val literalNull = unanchored.copy(
            practiceUnitId = "<null>",
            problemRevisionId = "<null>",
        )
        assertNotEquals(
            LearningLedgerFingerprint.learningObservationCandidate(unanchored),
            LearningLedgerFingerprint.learningObservationCandidate(literalNull),
        )
    }

    private fun candidate(
        modelVersion: String = "model-v1",
        evidenceLocator: String = "turn:1/choice:choice-a",
        evidenceWeight: Double = 0.8,
        proposedAttributions: List<LearningObservationKnowledgeAttribution> = listOf(
            attribution("binding-1", "knowledge-1", 1.0),
        ),
    ) = LearningObservationCandidate(
        candidateId = "candidate-1",
        learnerId = "learner-1",
        source = LearningObservationSource.TUTOR_CHOICE,
        sourceReferenceId = "choice-1",
        practiceUnitId = "unit-1",
        problemRevisionId = "revision-1",
        direction = LearningObservationDirection.POSITIVE,
        evidenceLevel = LearningObservationEvidenceLevel.HIGH_CONFIDENCE,
        evidenceWeight = evidenceWeight,
        independence = LearningObservationIndependence.INDEPENDENT,
        proposedAttributions = proposedAttributions,
        occurredAtEpochMillis = 1_000,
        modelVersion = modelVersion,
        evidenceLocator = evidenceLocator,
        status = LearningObservationCandidateStatus.PENDING_CONFIRMATION,
        retryCount = 0,
        createdAtEpochMillis = 1_100,
        updatedAtEpochMillis = 1_100,
    )

    private fun attribution(
        bindingId: String,
        knowledgeNodeId: String,
        weight: Double,
    ) = LearningObservationKnowledgeAttribution(
        bindingId = bindingId,
        knowledgeNodeId = knowledgeNodeId,
        weight = weight,
        basisRevisionId = "revision-1",
        taxonomyVersion = "taxonomy-v1",
        role = EvidenceAttributionRole.PRIMARY,
        certainty = EvidenceAttributionCertainty.DIRECT,
    )

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }
}
