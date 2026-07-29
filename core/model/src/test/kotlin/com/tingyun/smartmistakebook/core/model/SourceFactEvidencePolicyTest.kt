package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceFactEvidencePolicyTest {
    @Test
    fun `choice and visual sources accept model evaluations but reject verified claims`() {
        listOf(
            LearningObservationSource.TUTOR_CHOICE,
            LearningObservationSource.TUTOR_VISUAL_TARGET,
        ).forEach { source ->
            listOf(
                LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
                LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE,
                LearningObservationFactKind.MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE,
            ).forEach { factKind ->
                sourceFact(source = source, factKind = factKind)
            }
            assertIllegalArgument {
                sourceFact(
                    source = source,
                    factKind = LearningObservationFactKind.VERIFIED_CORRECT_RESPONSE,
                )
            }
            assertIllegalArgument {
                sourceFact(
                    source = source,
                    factKind = LearningObservationFactKind.VERIFIED_INCORRECT_RESPONSE,
                )
            }
        }
    }

    @Test
    fun `model answer reversals remain directional revisable weak evidence`() {
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
                LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
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
    fun `correct after assistance cannot establish independent mastery`() {
        val assistedCorrect = sourceFact(
            factKind = LearningObservationFactKind.MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE,
        )
        val limits = SourceFactEvidencePolicy.limitsFor(assistedCorrect)

        assertEquals(LearningObservationDirection.POSITIVE, limits.direction)
        assertEquals(
            LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
            limits.maximumEvidenceLevel,
        )
        assertEquals(0.2, limits.maximumEvidenceWeight, 0.0)
        assertEquals(LearningObservationIndependence.ASSISTED, limits.independence)
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
    }

    @Test
    fun `specific stuck remains revisable medium negative evidence`() {
        val stuckFact = sourceFact(
            source = LearningObservationSource.TUTOR_SPECIFIC_STUCK,
            factKind = LearningObservationFactKind.SPECIFIC_STUCK,
        )
        val limits = SourceFactEvidencePolicy.limitsFor(stuckFact)

        assertEquals(LearningObservationDirection.NEGATIVE, limits.direction)
        assertEquals(
            LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
            limits.maximumEvidenceLevel,
        )
        assertEquals(0.25, limits.maximumEvidenceWeight, 0.0)
        assertEquals(LearningObservationIndependence.UNKNOWN, limits.independence)
        assertTrue(limits.isRevisableWeakEvidence)
        assertFalse(limits.canEstablishIndependentMastery)

        SourceFactEvidencePolicy.requireCandidate(
            sourceFact = stuckFact,
            candidate = candidate(
                sourceFact = stuckFact,
                direction = LearningObservationDirection.NEGATIVE,
                evidenceLevel = LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
            ),
        )
    }

    @Test
    fun `verified observation facts fail closed without persisted local receipt`() {
        listOf(
            LearningObservationFactKind.VERIFIED_CORRECT_RESPONSE,
            LearningObservationFactKind.VERIFIED_INCORRECT_RESPONSE,
        ).forEach { factKind ->
            val fact = sourceFact(
                source = LearningObservationSource.CAPTURED_REVIEW_RESPONSE,
                factKind = factKind,
            )
            assertIllegalArgument {
                SourceFactEvidencePolicy.limitsFor(fact)
            }
        }
    }

    @Test
    fun `candidate and attribution gates reject fact swapping and evidence upgrades`() {
        val modelFact = sourceFact(
            sourceFactId = "fact-model",
            factKind = LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
        )
        val otherFact = sourceFact(
            sourceFactId = "fact-other",
            factKind = LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
        )

        assertIllegalArgument {
            SourceFactEvidencePolicy.requireCandidate(
                sourceFact = modelFact,
                candidate = candidate(
                    sourceFact = modelFact,
                    sourceFactId = otherFact.sourceFactId,
                    direction = LearningObservationDirection.POSITIVE,
                ),
            )
        }
        assertIllegalArgument {
            SourceFactEvidencePolicy.requireCandidate(
                sourceFact = modelFact,
                candidate = candidate(
                    sourceFact = modelFact,
                    direction = LearningObservationDirection.POSITIVE,
                    evidenceLevel = LearningObservationEvidenceLevel.HIGH_CONFIDENCE,
                ),
            )
        }

        val readyCandidate = candidate(
            sourceFact = modelFact,
            direction = LearningObservationDirection.POSITIVE,
            status = LearningObservationCandidateStatus.READY,
        )
        assertIllegalArgument {
            SourceFactEvidencePolicy.requireAttribution(
                sourceFact = modelFact,
                candidate = readyCandidate,
                event = event(readyCandidate),
            )
        }
        assertIllegalArgument {
            SourceFactEvidencePolicy.requireAttribution(
                sourceFact = modelFact,
                candidate = readyCandidate,
                event = event(readyCandidate).copy(sourceFactId = otherFact.sourceFactId),
            )
        }
    }

    @Test
    fun `candidate source reference is the canonical request or source fact identity`() {
        val tutorFact = sourceFact(
            sourceFactId = "fact-request-scoped",
            factKind = LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
        )
        val importedFact = sourceFact(
            sourceFactId = "fact-imported",
            source = LearningObservationSource.IMPORTED_MISTAKE,
            factKind = LearningObservationFactKind.IMPORTED_VISIBLE_ERROR,
        )

        assertEquals(
            requireNotNull(tutorFact.evidenceRequestId),
            SourceFactEvidencePolicy.canonicalSourceReferenceId(tutorFact),
        )
        assertEquals(
            importedFact.sourceFactId,
            SourceFactEvidencePolicy.canonicalSourceReferenceId(importedFact),
        )
        assertIllegalArgument {
            SourceFactEvidencePolicy.requireCandidate(
                sourceFact = tutorFact,
                candidate = candidate(
                    sourceFact = tutorFact,
                    sourceReferenceId = "caller-selected-alias",
                    direction = LearningObservationDirection.POSITIVE,
                ),
            )
        }
    }

    @Test
    fun `attribution must exactly inherit reviewed candidate provenance`() {
        val modelFact = sourceFact(
            factKind = LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
        )
        val reviewedCandidate = candidate(
            sourceFact = modelFact,
            direction = LearningObservationDirection.POSITIVE,
            evidenceLevel = LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
            status = LearningObservationCandidateStatus.READY,
        )
        val matchingEvent = event(reviewedCandidate).copy(
            evidenceLevel = LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
        )
        SourceFactEvidencePolicy.requirePersistedCandidate(modelFact, reviewedCandidate)
        SourceFactEvidencePolicy.requireAttribution(
            sourceFact = modelFact,
            candidate = reviewedCandidate,
            event = matchingEvent,
        )

        listOf(
            matchingEvent.copy(candidateId = "candidate-other"),
            matchingEvent.copy(sourceFactId = "fact-other"),
            matchingEvent.copy(learnerId = "learner-other"),
            matchingEvent.copy(practiceUnitId = "unit-other"),
            matchingEvent.copy(
                problemRevisionId = "revision-other",
                attributions = matchingEvent.attributions.map { attribution ->
                    attribution.copy(basisRevisionId = "revision-other")
                },
            ),
            matchingEvent.copy(direction = LearningObservationDirection.NEGATIVE),
            matchingEvent.copy(evidenceLevel = LearningObservationEvidenceLevel.HIGH_CONFIDENCE),
            matchingEvent.copy(evidenceWeight = 0.2),
            matchingEvent.copy(independence = LearningObservationIndependence.ASSISTED),
            matchingEvent.copy(
                attributions = listOf(
                    matchingEvent.attributions.single().copy(
                        knowledgeNodeId = "knowledge-other",
                    ),
                ),
            ),
            matchingEvent.copy(occurredAtEpochMillis = matchingEvent.occurredAtEpochMillis + 1),
            matchingEvent.copy(modelVersion = "model-other"),
            matchingEvent.copy(evidenceLocator = "turn:1/model-evaluation-other"),
        ).forEach { mismatchedEvent ->
            assertIllegalArgument {
                SourceFactEvidencePolicy.requireAttribution(
                    sourceFact = modelFact,
                    candidate = reviewedCandidate,
                    event = mismatchedEvent,
                )
            }
        }
    }

    @Test
    fun `canonical imported visible error may cross the confirmed negative boundary`() {
        val importedFact = sourceFact(
            source = LearningObservationSource.IMPORTED_MISTAKE,
            factKind = LearningObservationFactKind.IMPORTED_VISIBLE_ERROR,
        )
        val readyCandidate = candidate(
            sourceFact = importedFact,
            direction = LearningObservationDirection.NEGATIVE,
            evidenceLevel = LearningObservationEvidenceLevel.CONFIRMED,
            evidenceWeight = 0.8,
            independence = LearningObservationIndependence.UNKNOWN,
            status = LearningObservationCandidateStatus.READY,
        )

        SourceFactEvidencePolicy.requirePersistedCandidate(importedFact, readyCandidate)
        SourceFactEvidencePolicy.requireAttribution(
            sourceFact = importedFact,
            candidate = readyCandidate,
            event = event(readyCandidate),
        )
    }

    private fun sourceFact(
        sourceFactId: String = "fact-1",
        source: LearningObservationSource = LearningObservationSource.TUTOR_CHOICE,
        factKind: LearningObservationFactKind,
    ): LearningObservationSourceFact {
        val tutorScoped = source in LearningObservationSourceFact.tutorSources
        return LearningObservationSourceFact(
            sourceFactId = sourceFactId,
            learnerScopeId = "learner-1",
            source = source,
            factKind = factKind,
            anchorId = "anchor-1",
            subject = SubjectKind.MATH,
            conversationGeneration = if (tutorScoped) 1 else null,
            conversationId = if (tutorScoped) "conversation-1" else null,
            turnReceiptId = if (tutorScoped) "turn-1" else null,
            evidenceRequestId = if (tutorScoped) "request-1" else null,
            responseFingerprint = SHA256,
            responseSummary = "Bounded response evidence.",
            occurredAtEpochMillis = 1_000,
            sourceVersion = "source-v1",
        )
    }

    private fun candidate(
        sourceFact: LearningObservationSourceFact,
        sourceFactId: String? = sourceFact.sourceFactId,
        sourceReferenceId: String =
            SourceFactEvidencePolicy.canonicalSourceReferenceId(sourceFact),
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
        sourceReferenceId = sourceReferenceId,
        sourceFactId = sourceFactId,
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
        sourceFactId = candidate.sourceFactId,
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
