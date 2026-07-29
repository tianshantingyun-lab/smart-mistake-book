package com.tingyun.smartmistakebook.core.model

/**
 * A local capability proving that this exact source fact was produced from an application-side
 * answer comparison. It is intentionally not serializable and cannot be supplied as model data.
 */
class TrustedLocalAnswerAuthority private constructor(
    private val sourceFactId: String,
    private val direction: LearningObservationDirection,
) {
    internal fun authorizes(
        sourceFact: LearningObservationSourceFact,
        requiredDirection: LearningObservationDirection,
    ): Boolean =
        sourceFactId == sourceFact.sourceFactId && direction == requiredDirection

    companion object {
        /**
         * Call only after comparing the learner answer with a trusted local answer key.
         */
        fun fromLocallyVerifiedAnswer(
            sourceFact: LearningObservationSourceFact,
            answerWasCorrect: Boolean,
        ): TrustedLocalAnswerAuthority {
            val expectedFactKind = if (answerWasCorrect) {
                LearningObservationFactKind.VERIFIED_CORRECT_RESPONSE
            } else {
                LearningObservationFactKind.VERIFIED_INCORRECT_RESPONSE
            }
            require(sourceFact.factKind == expectedFactKind) {
                "Trusted local answer authority must match the verified source-fact result"
            }
            return TrustedLocalAnswerAuthority(
                sourceFactId = sourceFact.sourceFactId,
                direction = if (answerWasCorrect) {
                    LearningObservationDirection.POSITIVE
                } else {
                    LearningObservationDirection.NEGATIVE
                },
            )
        }
    }
}

/**
 * Immutable evidence ceiling for one source-fact kind.
 *
 * A null [maximumEvidenceLevel] means the fact records workflow provenance but cannot itself
 * produce a learning-evidence candidate.
 */
class SourceFactEvidenceLimits internal constructor(
    val direction: LearningObservationDirection?,
    val maximumEvidenceLevel: LearningObservationEvidenceLevel?,
    val maximumEvidenceWeight: Double,
    val independence: LearningObservationIndependence,
) {
    init {
        require(maximumEvidenceWeight.isFinite() && maximumEvidenceWeight in 0.0..1.0) {
            "Source-fact evidence weight ceiling must be between zero and one"
        }
        require((maximumEvidenceLevel == null) == (maximumEvidenceWeight == 0.0)) {
            "Non-evidentiary source facts must have a zero evidence-weight ceiling"
        }
        require((maximumEvidenceLevel == null) == (direction == null)) {
            "Non-evidentiary source facts cannot imply a learning direction"
        }
    }

    val isRevisableWeakEvidence: Boolean
        get() = maximumEvidenceLevel == LearningObservationEvidenceLevel.LOW_CONFIDENCE

    val canEstablishIndependentMastery: Boolean
        get() =
            direction == LearningObservationDirection.POSITIVE &&
                independence == LearningObservationIndependence.INDEPENDENT &&
                maximumEvidenceLevel.isAtLeast(
                    LearningObservationEvidenceLevel.HIGH_CONFIDENCE,
                )
}

/**
 * Pure local gate between immutable source facts and model-proposed learning evidence.
 *
 * Candidates and attributed events do not currently persist a source-fact id. Callers must pass
 * the associated [LearningObservationSourceFact] explicitly; this policy never guesses an
 * association from sourceReferenceId or another model-controlled field.
 */
object SourceFactEvidencePolicy {
    fun limitsFor(
        sourceFact: LearningObservationSourceFact,
        trustedLocalAnswerAuthority: TrustedLocalAnswerAuthority? = null,
    ): SourceFactEvidenceLimits =
        when (sourceFact.factKind) {
            LearningObservationFactKind.VERIFIED_CORRECT_RESPONSE -> verifiedLimits(
                sourceFact = sourceFact,
                direction = LearningObservationDirection.POSITIVE,
                trustedLocalAnswerAuthority = trustedLocalAnswerAuthority,
            )

            LearningObservationFactKind.VERIFIED_INCORRECT_RESPONSE -> verifiedLimits(
                sourceFact = sourceFact,
                direction = LearningObservationDirection.NEGATIVE,
                trustedLocalAnswerAuthority = trustedLocalAnswerAuthority,
            )

            LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE -> {
                requireNoTrustedAuthority(trustedLocalAnswerAuthority)
                SourceFactEvidenceLimits(
                    direction = LearningObservationDirection.POSITIVE,
                    maximumEvidenceLevel = LearningObservationEvidenceLevel.LOW_CONFIDENCE,
                    maximumEvidenceWeight = MODEL_EVALUATION_MAXIMUM_WEIGHT,
                    independence = LearningObservationIndependence.UNKNOWN,
                )
            }

            LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE -> {
                requireNoTrustedAuthority(trustedLocalAnswerAuthority)
                SourceFactEvidenceLimits(
                    direction = LearningObservationDirection.NEGATIVE,
                    maximumEvidenceLevel = LearningObservationEvidenceLevel.LOW_CONFIDENCE,
                    maximumEvidenceWeight = MODEL_EVALUATION_MAXIMUM_WEIGHT,
                    independence = LearningObservationIndependence.UNKNOWN,
                )
            }

            LearningObservationFactKind.MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE -> {
                requireNoTrustedAuthority(trustedLocalAnswerAuthority)
                SourceFactEvidenceLimits(
                    direction = LearningObservationDirection.POSITIVE,
                    maximumEvidenceLevel = LearningObservationEvidenceLevel.LOW_CONFIDENCE,
                    maximumEvidenceWeight = ASSISTED_MODEL_EVALUATION_MAXIMUM_WEIGHT,
                    independence = LearningObservationIndependence.ASSISTED,
                )
            }

            LearningObservationFactKind.OPEN_RESPONSE_SUBMITTED -> {
                requireNoTrustedAuthority(trustedLocalAnswerAuthority)
                SourceFactEvidenceLimits(
                    direction = null,
                    maximumEvidenceLevel = null,
                    maximumEvidenceWeight = 0.0,
                    independence = LearningObservationIndependence.UNKNOWN,
                )
            }

            LearningObservationFactKind.SPECIFIC_STUCK -> {
                requireNoTrustedAuthority(trustedLocalAnswerAuthority)
                SourceFactEvidenceLimits(
                    direction = LearningObservationDirection.NEGATIVE,
                    maximumEvidenceLevel = LearningObservationEvidenceLevel.LOW_CONFIDENCE,
                    maximumEvidenceWeight = SPECIFIC_STUCK_MAXIMUM_WEIGHT,
                    independence = LearningObservationIndependence.UNKNOWN,
                )
            }

            LearningObservationFactKind.IMPORTED_VISIBLE_ERROR -> {
                requireNoTrustedAuthority(trustedLocalAnswerAuthority)
                SourceFactEvidenceLimits(
                    direction = LearningObservationDirection.NEGATIVE,
                    maximumEvidenceLevel = LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
                    maximumEvidenceWeight = IMPORTED_VISIBLE_ERROR_MAXIMUM_WEIGHT,
                    independence = LearningObservationIndependence.UNKNOWN,
                )
            }
        }

    /**
     * Admission gate for a newly proposed candidate.
     */
    fun requireCandidate(
        sourceFact: LearningObservationSourceFact,
        candidate: LearningObservationCandidate,
        trustedLocalAnswerAuthority: TrustedLocalAnswerAuthority? = null,
    ) {
        val limits = limitsFor(sourceFact, trustedLocalAnswerAuthority)
        requireCandidateAssociation(sourceFact, candidate)
        candidate.requireSafeInitialStatus()
        requireEvidenceWithinLimits(
            direction = candidate.direction,
            evidenceLevel = candidate.evidenceLevel,
            evidenceWeight = candidate.evidenceWeight,
            independence = candidate.independence,
            limits = limits,
        )
    }

    /**
     * Attribution gate for a candidate/event pair explicitly associated with one source fact.
     */
    fun requireAttribution(
        sourceFact: LearningObservationSourceFact,
        candidate: LearningObservationCandidate,
        event: AttributedLearningObservationEvent,
        trustedLocalAnswerAuthority: TrustedLocalAnswerAuthority? = null,
    ) {
        val limits = limitsFor(sourceFact, trustedLocalAnswerAuthority)
        requireCandidateAssociation(sourceFact, candidate)
        require(candidate.status == LearningObservationCandidateStatus.READY) {
            "Only a ready observation candidate may cross the attribution boundary"
        }
        require(event.candidateId == candidate.candidateId) {
            "Attributed event must reference the explicitly associated candidate"
        }
        require(event.learnerId == candidate.learnerId) {
            "Attributed event learner must match its candidate"
        }
        require(event.occurredAtEpochMillis == candidate.occurredAtEpochMillis) {
            "Attributed event time must match its candidate"
        }
        requireEvidenceWithinLimits(
            direction = candidate.direction,
            evidenceLevel = candidate.evidenceLevel,
            evidenceWeight = candidate.evidenceWeight,
            independence = candidate.independence,
            limits = limits,
        )
        requireEvidenceWithinLimits(
            direction = event.direction,
            evidenceLevel = event.evidenceLevel,
            evidenceWeight = event.evidenceWeight,
            independence = event.independence,
            limits = limits,
        )
    }

    private fun verifiedLimits(
        sourceFact: LearningObservationSourceFact,
        direction: LearningObservationDirection,
        trustedLocalAnswerAuthority: TrustedLocalAnswerAuthority?,
    ): SourceFactEvidenceLimits {
        require(
            trustedLocalAnswerAuthority?.authorizes(sourceFact, direction) == true,
        ) {
            "Verified response evidence requires matching trusted local answer authority"
        }
        return SourceFactEvidenceLimits(
            direction = direction,
            maximumEvidenceLevel = LearningObservationEvidenceLevel.CONFIRMED,
            maximumEvidenceWeight = VERIFIED_RESPONSE_MAXIMUM_WEIGHT,
            independence = LearningObservationIndependence.INDEPENDENT,
        )
    }

    private fun requireCandidateAssociation(
        sourceFact: LearningObservationSourceFact,
        candidate: LearningObservationCandidate,
    ) {
        require(candidate.learnerId == sourceFact.learnerScopeId) {
            "Observation candidate learner must match the explicit source fact"
        }
        require(candidate.source == sourceFact.source) {
            "Observation candidate source must match the explicit source fact"
        }
        require(candidate.occurredAtEpochMillis == sourceFact.occurredAtEpochMillis) {
            "Observation candidate time must match the explicit source fact"
        }
    }

    private fun requireEvidenceWithinLimits(
        direction: LearningObservationDirection,
        evidenceLevel: LearningObservationEvidenceLevel,
        evidenceWeight: Double,
        independence: LearningObservationIndependence,
        limits: SourceFactEvidenceLimits,
    ) {
        val maximumEvidenceLevel = requireNotNull(limits.maximumEvidenceLevel) {
            "This source fact cannot produce learning evidence"
        }
        require(direction == limits.direction) {
            "Learning evidence direction must match its source fact"
        }
        require(!evidenceLevel.isStrongerThan(maximumEvidenceLevel)) {
            "Learning evidence level exceeds its source-fact ceiling"
        }
        require(evidenceWeight <= limits.maximumEvidenceWeight + EVIDENCE_WEIGHT_EPSILON) {
            "Learning evidence weight exceeds its source-fact ceiling"
        }
        require(independence == limits.independence) {
            "Learning evidence independence must match its source fact"
        }
    }

    private fun requireNoTrustedAuthority(
        trustedLocalAnswerAuthority: TrustedLocalAnswerAuthority?,
    ) {
        require(trustedLocalAnswerAuthority == null) {
            "Trusted local answer authority applies only to verified response facts"
        }
    }

    private const val VERIFIED_RESPONSE_MAXIMUM_WEIGHT = 1.0
    private const val MODEL_EVALUATION_MAXIMUM_WEIGHT = 0.25
    private const val ASSISTED_MODEL_EVALUATION_MAXIMUM_WEIGHT = 0.2
    private const val SPECIFIC_STUCK_MAXIMUM_WEIGHT = 0.25
    private const val IMPORTED_VISIBLE_ERROR_MAXIMUM_WEIGHT = 0.5
    private const val EVIDENCE_WEIGHT_EPSILON = 1e-9
}

private fun LearningObservationEvidenceLevel?.isAtLeast(
    other: LearningObservationEvidenceLevel,
): Boolean = this != null && evidenceStrength() >= other.evidenceStrength()

private fun LearningObservationEvidenceLevel.isStrongerThan(
    other: LearningObservationEvidenceLevel,
): Boolean = evidenceStrength() > other.evidenceStrength()

private fun LearningObservationEvidenceLevel.evidenceStrength(): Int =
    when (this) {
        LearningObservationEvidenceLevel.LOW_CONFIDENCE -> 0
        LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE -> 1
        LearningObservationEvidenceLevel.HIGH_CONFIDENCE -> 2
        LearningObservationEvidenceLevel.CONFIRMED -> 3
    }
