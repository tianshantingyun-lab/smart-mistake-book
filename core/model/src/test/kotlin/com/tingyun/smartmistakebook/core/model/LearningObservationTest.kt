package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(
            LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
            event.copy(
                evidenceLevel = LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
            ).evidenceLevel,
        )
        assertIllegalArgument {
            event.copy(evidenceLevel = LearningObservationEvidenceLevel.LOW_CONFIDENCE)
        }
        assertIllegalArgument {
            event.copy(confirmedAtEpochMillis = event.occurredAtEpochMillis - 1)
        }
    }

    @Test
    fun `projection admission binds the exact raw event proof and policy`() {
        val raw = attributedEvent(sourceFactId = "source-fact-1")
        val proofFingerprint = "a".repeat(64)
        val admission = LearningObservationProjectionAdmission.create(
            observation = raw,
            sourceFactProofFingerprint = proofFingerprint,
            policyVersion = LEARNING_OBSERVATION_PROJECTION_ADMISSION_POLICY_VERSION,
        )
        val admitted = AdmittedLearningObservationEvent(raw, admission)

        assertEquals(
            LearningLedgerFingerprint.learningObservation(raw),
            admission.rawEventCanonicalFingerprint,
        )
        assertEquals(
            admission.admissionFingerprint,
            LearningLedgerFingerprint.event(admitted),
        )
        assertEquals(
            admission,
            LearningObservationProjectionAdmission.restore(
                observation = raw,
                rawEventCanonicalFingerprint = admission.rawEventCanonicalFingerprint,
                sourceFactProofFingerprint = admission.sourceFactProofFingerprint,
                policyVersion = admission.policyVersion,
                admissionFingerprint = admission.admissionFingerprint,
            ),
        )
        assertIllegalArgument {
            AdmittedLearningObservationEvent(
                observation = raw.copy(evidenceWeight = 0.4),
                admission = admission,
            )
        }
        assertIllegalArgument {
            LearningObservationProjectionAdmission.restore(
                observation = raw,
                rawEventCanonicalFingerprint = admission.rawEventCanonicalFingerprint,
                sourceFactProofFingerprint = "b".repeat(64),
                policyVersion = admission.policyVersion,
                admissionFingerprint = admission.admissionFingerprint,
            )
        }
        assertIllegalArgument {
            LearningObservationProjectionAdmission.restore(
                observation = raw,
                rawEventCanonicalFingerprint = "f".repeat(64),
                sourceFactProofFingerprint = proofFingerprint,
                policyVersion = admission.policyVersion,
                admissionFingerprint = admission.admissionFingerprint,
            )
        }
        assertIllegalArgument {
            LearningObservationProjectionAdmission.create(
                observation = raw.copy(sourceFactId = null),
                sourceFactProofFingerprint = proofFingerprint,
                policyVersion = admission.policyVersion,
            )
        }
        assertIllegalArgument {
            LearningObservationProjectionAdmission.create(
                observation = raw,
                sourceFactProofFingerprint = proofFingerprint,
                policyVersion = "unknown-admission-policy",
            )
        }
    }

    @Test
    fun `only non projectable workflow states are safe for new candidate submission`() {
        listOf(
            LearningObservationCandidateStatus.WAITING_FOR_ANCHOR,
            LearningObservationCandidateStatus.WAITING_FOR_ORGANIZATION,
            LearningObservationCandidateStatus.WAITING_FOR_ATTRIBUTION,
            LearningObservationCandidateStatus.PENDING_CONFIRMATION,
        ).forEach { status ->
            candidate(status = status).requireSafeInitialStatus()
        }

        listOf(
            LearningObservationCandidateStatus.WAITING_FOR_PROJECTION,
            LearningObservationCandidateStatus.READY,
            LearningObservationCandidateStatus.MATERIALIZED,
            LearningObservationCandidateStatus.REJECTED,
        ).forEach { status ->
            assertIllegalArgument {
                candidate(status = status).requireSafeInitialStatus()
            }
        }
    }

    @Test
    fun `external workflow cannot enter or leave materialized terminal state`() {
        LearningObservationCandidateStatus.entries.forEach { status ->
            assertFalse(
                LearningObservationCandidateStatus.MATERIALIZED in
                    status.allowedExternalTransitions(),
            )
        }
        assertEquals(
            setOf(LearningObservationCandidateStatus.REJECTED),
            LearningObservationCandidateStatus.READY.allowedExternalTransitions(),
        )
        assertTrueTerminal(LearningObservationCandidateStatus.MATERIALIZED)
        assertTrueTerminal(LearningObservationCandidateStatus.REJECTED)
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

        val sourcedCandidate = candidate.copy(sourceFactId = "source-fact-1")
        val sourcedEvent = event.copy(sourceFactId = "source-fact-1")
        assertNotEquals(candidate, sourcedCandidate)
        assertNotEquals(event, sourcedEvent)
        assertNotEquals(
            LearningLedgerFingerprint.learningObservationCandidate(candidate),
            LearningLedgerFingerprint.learningObservationCandidate(sourcedCandidate),
        )
        assertNotEquals(
            LearningLedgerFingerprint.learningObservation(event),
            LearningLedgerFingerprint.learningObservation(sourcedEvent),
        )
        assertNotEquals(
            LearningLedgerFingerprint.learningObservationCandidate(sourcedCandidate),
            LearningLedgerFingerprint.learningObservationCandidate(
                sourcedCandidate.copy(sourceFactId = "source-fact-2"),
            ),
        )
    }

    private fun candidate(
        modelVersion: String = "model-v1",
        evidenceLocator: String = "turn:1/choice:choice-a",
        evidenceWeight: Double = 0.8,
        status: LearningObservationCandidateStatus =
            LearningObservationCandidateStatus.PENDING_CONFIRMATION,
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
        status = status,
        retryCount = 0,
        createdAtEpochMillis = 1_100,
        updatedAtEpochMillis = 1_100,
    )

    private fun attributedEvent(
        sourceFactId: String?,
    ): AttributedLearningObservationEvent {
        val candidate = candidate()
        return AttributedLearningObservationEvent(
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
            sourceFactId = sourceFactId,
        )
    }

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

    private fun assertTrueTerminal(status: LearningObservationCandidateStatus) {
        assertEquals(emptySet<LearningObservationCandidateStatus>(), status.allowedExternalTransitions())
    }
}
