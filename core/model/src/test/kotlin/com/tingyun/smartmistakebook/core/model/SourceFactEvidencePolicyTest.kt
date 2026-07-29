package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceFactEvidencePolicyTest {
    @Test
    fun `model answer reversals remain directional revisable weak evidence`() {
        listOf(
            LearningObservationSource.TUTOR_CHOICE,
            LearningObservationSource.TUTOR_VISUAL_TARGET,
        ).forEach { source ->
            LearningObservationSourceFact(
                sourceFactId = "fact-${source.name.lowercase()}",
                learnerScopeId = "learner-1",
                source = source,
                factKind = LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
                anchorId = "anchor-1",
                subject = SubjectKind.MATH,
                conversationGeneration = 1,
                conversationId = "conversation-1",
                turnReceiptId = "turn-1",
                evidenceRequestId = "request-1",
                responseFingerprint = SHA256,
                responseSummary = "Model-evaluated response.",
                occurredAtEpochMillis = 1_000,
                sourceVersion = "model-evaluation-v1",
            )
        }

        val evaluatedCorrect = sourceFact(
            sourceFactId = "fact-correct",
            factKind = LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
        )
        val evaluatedIncorrect = sourceFact(
            sourceFactId = "fact-incorrect",
            factKind = LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE,
        )

        val correctLimits = SourceFactEvidencePolicy.limitsFor(evaluatedCorrect)
        val incorrectLimits = SourceFactEvidencePolicy.limitsFor(evaluatedIncorrect)

        assertEquals(LearningObservationDirection.POSITIVE, correctLimits.direction)
        assertEquals(LearningObservationDirection.NEGATIVE, incorrectLimits.direction)
        listOf(correctLimits, incorrectLimits).forEach { limits ->
            assertEquals(
                LearningObservationEvidenceLevel.LOW_CONFIDENCE,
                limits.maximumEvidenceLevel,
            )
            assertEquals(0.25, limits.maximumEvidenceWeight, 0.0)
            assertEquals(LearningObservationIndependence.UNKNOWN, limits.independence)
            assertTrue(limits.isRevisableWeakEvidence)
            assertFalse(limits.canEstablishIndependentMastery)
        }

        SourceFactEvidencePolicy.requireCandidate(
            sourceFact = evaluatedCorrect,
            candidate = candidate(
                sourceFact = evaluatedCorrect,
                direction = LearningObservationDirection.POSITIVE,
            ),
        )
        SourceFactEvidencePolicy.requireCandidate(
            sourceFact = evaluatedIncorrect,
            candidate = candidate(
                sourceFact = evaluatedIncorrect,
                direction = LearningObservationDirection.NEGATIVE,
            ),
        )
    }

    @Test
    fun `correct after hints or repeated stuck is assisted and cannot establish mastery`() {
        val assistedCorrect = sourceFact(
            sourceFactId = "fact-assisted",
            factKind = LearningObservationFactKind.MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE,
        )
        val limits = SourceFactEvidencePolicy.limitsFor(assistedCorrect)

        assertEquals(LearningObservationDirection.POSITIVE, limits.direction)
        assertEquals(
            LearningObservationEvidenceLevel.LOW_CONFIDENCE,
            limits.maximumEvidenceLevel,
        )
        assertEquals(0.2, limits.maximumEvidenceWeight, 0.0)
        assertEquals(LearningObservationIndependence.ASSISTED, limits.independence)
        assertTrue(limits.isRevisableWeakEvidence)
        assertFalse(limits.canEstablishIndependentMastery)

        SourceFactEvidencePolicy.requireCandidate(
            sourceFact = assistedCorrect,
            candidate = candidate(
                sourceFact = assistedCorrect,
                direction = LearningObservationDirection.POSITIVE,
                evidenceWeight = 0.2,
                independence = LearningObservationIndependence.ASSISTED,
            ),
        )
        assertIllegalArgument {
            SourceFactEvidencePolicy.requireCandidate(
                sourceFact = assistedCorrect,
                candidate = candidate(
                    sourceFact = assistedCorrect,
                    direction = LearningObservationDirection.POSITIVE,
                    independence = LearningObservationIndependence.INDEPENDENT,
                ),
            )
        }
        assertIllegalArgument {
            SourceFactEvidencePolicy.requireCandidate(
                sourceFact = assistedCorrect,
                candidate = candidate(
                    sourceFact = assistedCorrect,
                    direction = LearningObservationDirection.POSITIVE,
                    evidenceLevel = LearningObservationEvidenceLevel.HIGH_CONFIDENCE,
                    evidenceWeight = 0.2,
                    independence = LearningObservationIndependence.ASSISTED,
                ),
            )
        }
    }

    @Test
    fun `verified evidence requires authority for that exact local answer result`() {
        val verifiedCorrect = sourceFact(
            sourceFactId = "fact-verified",
            factKind = LearningObservationFactKind.VERIFIED_CORRECT_RESPONSE,
        )
        assertIllegalArgument {
            SourceFactEvidencePolicy.limitsFor(verifiedCorrect)
        }

        val otherFactAuthority = TrustedLocalAnswerAuthority.fromLocallyVerifiedAnswer(
            sourceFact = sourceFact(
                sourceFactId = "fact-other",
                factKind = LearningObservationFactKind.VERIFIED_CORRECT_RESPONSE,
            ),
            answerWasCorrect = true,
        )
        assertIllegalArgument {
            SourceFactEvidencePolicy.limitsFor(verifiedCorrect, otherFactAuthority)
        }
        assertIllegalArgument {
            TrustedLocalAnswerAuthority.fromLocallyVerifiedAnswer(
                sourceFact = verifiedCorrect,
                answerWasCorrect = false,
            )
        }

        val authority = TrustedLocalAnswerAuthority.fromLocallyVerifiedAnswer(
            sourceFact = verifiedCorrect,
            answerWasCorrect = true,
        )
        val limits = SourceFactEvidencePolicy.limitsFor(verifiedCorrect, authority)
        assertEquals(LearningObservationDirection.POSITIVE, limits.direction)
        assertEquals(
            LearningObservationEvidenceLevel.CONFIRMED,
            limits.maximumEvidenceLevel,
        )
        assertEquals(1.0, limits.maximumEvidenceWeight, 0.0)
        assertEquals(LearningObservationIndependence.INDEPENDENT, limits.independence)
        assertTrue(limits.canEstablishIndependentMastery)

        val modelFact = sourceFact(
            sourceFactId = "fact-model",
            factKind = LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
        )
        assertIllegalArgument {
            SourceFactEvidencePolicy.limitsFor(modelFact, authority)
        }
    }

    @Test
    fun `candidate and attribution gates reject model evidence upgrades`() {
        val sourceFact = sourceFact(
            sourceFactId = "fact-model",
            factKind = LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
        )

        assertIllegalArgument {
            SourceFactEvidencePolicy.requireCandidate(
                sourceFact = sourceFact,
                candidate = candidate(
                    sourceFact = sourceFact,
                    direction = LearningObservationDirection.POSITIVE,
                    evidenceLevel = LearningObservationEvidenceLevel.HIGH_CONFIDENCE,
                    evidenceWeight = 0.25,
                ),
            )
        }
        assertIllegalArgument {
            SourceFactEvidencePolicy.requireCandidate(
                sourceFact = sourceFact,
                candidate = candidate(
                    sourceFact = sourceFact,
                    direction = LearningObservationDirection.POSITIVE,
                    evidenceWeight = 0.9,
                ),
            )
        }
        assertIllegalArgument {
            SourceFactEvidencePolicy.requireCandidate(
                sourceFact = sourceFact,
                candidate = candidate(
                    sourceFact = sourceFact,
                    direction = LearningObservationDirection.POSITIVE,
                    independence = LearningObservationIndependence.INDEPENDENT,
                ),
            )
        }

        val readyCandidate = candidate(
            sourceFact = sourceFact,
            direction = LearningObservationDirection.POSITIVE,
            status = LearningObservationCandidateStatus.READY,
        )
        assertIllegalArgument {
            SourceFactEvidencePolicy.requireAttribution(
                sourceFact = sourceFact,
                candidate = readyCandidate,
                event = event(readyCandidate),
            )
        }
    }

    private fun sourceFact(
        sourceFactId: String,
        factKind: LearningObservationFactKind,
    ) = LearningObservationSourceFact(
        sourceFactId = sourceFactId,
        learnerScopeId = "learner-1",
        source = LearningObservationSource.TUTOR_CHOICE,
        factKind = factKind,
        anchorId = "anchor-1",
        subject = SubjectKind.MATH,
        conversationGeneration = 1,
        conversationId = "conversation-1",
        turnReceiptId = "turn-1",
        evidenceRequestId = "request-1",
        responseFingerprint = SHA256,
        responseSummary = "Locally bounded response evidence.",
        occurredAtEpochMillis = 1_000,
        sourceVersion = "source-v1",
    )

    private fun candidate(
        sourceFact: LearningObservationSourceFact,
        direction: LearningObservationDirection,
        evidenceLevel: LearningObservationEvidenceLevel =
            LearningObservationEvidenceLevel.LOW_CONFIDENCE,
        evidenceWeight: Double = 0.25,
        independence: LearningObservationIndependence =
            LearningObservationIndependence.UNKNOWN,
        status: LearningObservationCandidateStatus =
            LearningObservationCandidateStatus.PENDING_CONFIRMATION,
    ) = LearningObservationCandidate(
        candidateId = "candidate-1",
        learnerId = sourceFact.learnerScopeId,
        source = sourceFact.source,
        sourceReferenceId = "model-proposal-1",
        practiceUnitId = "unit-1",
        problemRevisionId = "revision-1",
        direction = direction,
        evidenceLevel = evidenceLevel,
        evidenceWeight = evidenceWeight,
        independence = independence,
        proposedAttributions = listOf(attribution()),
        occurredAtEpochMillis = sourceFact.occurredAtEpochMillis,
        modelVersion = "model-v1",
        evidenceLocator = "turn:1/model-evaluation",
        status = status,
        retryCount = 0,
        createdAtEpochMillis = 1_100,
        updatedAtEpochMillis = 1_100,
    )

    private fun event(
        candidate: LearningObservationCandidate,
    ) = AttributedLearningObservationEvent(
        eventId = "event-1",
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
        confirmedAtEpochMillis = 1_200,
        modelVersion = candidate.modelVersion,
        evidenceLocator = candidate.evidenceLocator,
        eventSequence = 1,
    )

    private fun attribution() = LearningObservationKnowledgeAttribution(
        bindingId = "binding-1",
        knowledgeNodeId = "knowledge-1",
        weight = 1.0,
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

    private companion object {
        const val SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
