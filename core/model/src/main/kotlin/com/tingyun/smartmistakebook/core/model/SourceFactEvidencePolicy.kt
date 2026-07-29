package com.tingyun.smartmistakebook.core.model

/**
 * Immutable evidence ceiling for one source-fact kind.
 *
 * A null [maximumEvidenceLevel] means the fact records workflow provenance but cannot itself
 * produce learning evidence.
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
        get() =
            maximumEvidenceLevel == LearningObservationEvidenceLevel.LOW_CONFIDENCE ||
                maximumEvidenceLevel == LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE

    val canEstablishIndependentMastery: Boolean
        get() =
            direction == LearningObservationDirection.POSITIVE &&
                independence == LearningObservationIndependence.INDEPENDENT &&
                maximumEvidenceLevel.isAtLeast(
                    LearningObservationEvidenceLevel.HIGH_CONFIDENCE,
                )
}

/**
 * Pure local gate between canonical source facts and proposed learning evidence.
 *
 * The observation path has no persisted local-answer verification receipt. VERIFIED facts
 * therefore fail closed here; trusted assessment answers continue through
 * [AssessmentEvidenceSnapshot].
 */
object SourceFactEvidencePolicy {
    /**
     * Stable identity shared by the canonical fact, its local authority, and its candidate.
     *
     * Tutor facts already have a one-to-one evidence-request identity. Sources without that
     * scope fall back to the immutable source-fact id instead of accepting a caller alias.
     */
    fun canonicalSourceReferenceId(sourceFact: LearningObservationSourceFact): String =
        sourceFact.evidenceRequestId ?: sourceFact.sourceFactId

    fun limitsFor(sourceFact: LearningObservationSourceFact): SourceFactEvidenceLimits =
        when (sourceFact.factKind) {
            LearningObservationFactKind.VERIFIED_CORRECT_RESPONSE,
            LearningObservationFactKind.VERIFIED_INCORRECT_RESPONSE,
            -> throw IllegalArgumentException(
                "Verified response facts require a persisted local verification receipt",
            )

            LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE ->
                SourceFactEvidenceLimits(
                    direction = LearningObservationDirection.POSITIVE,
                    maximumEvidenceLevel = LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
                    maximumEvidenceWeight = MODEL_EVALUATION_MAXIMUM_WEIGHT,
                    independence = LearningObservationIndependence.UNKNOWN,
                )

            LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE ->
                SourceFactEvidenceLimits(
                    direction = LearningObservationDirection.NEGATIVE,
                    maximumEvidenceLevel = LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
                    maximumEvidenceWeight = MODEL_EVALUATION_MAXIMUM_WEIGHT,
                    independence = LearningObservationIndependence.UNKNOWN,
                )

            LearningObservationFactKind.MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE ->
                SourceFactEvidenceLimits(
                    direction = LearningObservationDirection.POSITIVE,
                    maximumEvidenceLevel = LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
                    maximumEvidenceWeight = ASSISTED_MODEL_EVALUATION_MAXIMUM_WEIGHT,
                    independence = LearningObservationIndependence.ASSISTED,
                )

            LearningObservationFactKind.OPEN_RESPONSE_SUBMITTED ->
                SourceFactEvidenceLimits(
                    direction = null,
                    maximumEvidenceLevel = null,
                    maximumEvidenceWeight = 0.0,
                    independence = LearningObservationIndependence.UNKNOWN,
                )

            LearningObservationFactKind.SPECIFIC_STUCK ->
                SourceFactEvidenceLimits(
                    direction = LearningObservationDirection.NEGATIVE,
                    maximumEvidenceLevel = LearningObservationEvidenceLevel.MEDIUM_CONFIDENCE,
                    maximumEvidenceWeight = SPECIFIC_STUCK_MAXIMUM_WEIGHT,
                    independence = LearningObservationIndependence.UNKNOWN,
                )

            LearningObservationFactKind.IMPORTED_VISIBLE_ERROR ->
                SourceFactEvidenceLimits(
                    direction = LearningObservationDirection.NEGATIVE,
                    maximumEvidenceLevel = LearningObservationEvidenceLevel.CONFIRMED,
                    maximumEvidenceWeight = IMPORTED_VISIBLE_ERROR_MAXIMUM_WEIGHT,
                    independence = LearningObservationIndependence.UNKNOWN,
                )
        }

    /** Admission gate for a newly proposed candidate. */
    fun requireCandidate(
        sourceFact: LearningObservationSourceFact,
        candidate: LearningObservationCandidate,
    ) {
        candidate.requireSafeInitialStatus()
        requirePersistedCandidate(sourceFact, candidate)
    }

    /** Revalidation gate for a candidate loaded from storage. */
    fun requirePersistedCandidate(
        sourceFact: LearningObservationSourceFact,
        candidate: LearningObservationCandidate,
    ) {
        val limits = limitsFor(sourceFact)
        requireCandidateAssociation(sourceFact, candidate)
        requireEvidenceWithinLimits(
            direction = candidate.direction,
            evidenceLevel = candidate.evidenceLevel,
            evidenceWeight = candidate.evidenceWeight,
            independence = candidate.independence,
            limits = limits,
        )
    }

    /** Attribution gate for a stored candidate/event pair and its canonical source fact. */
    fun requireAttribution(
        sourceFact: LearningObservationSourceFact,
        candidate: LearningObservationCandidate,
        event: AttributedLearningObservationEvent,
    ) {
        val limits = limitsFor(sourceFact)
        requireCandidateAssociation(sourceFact, candidate)
        require(
            candidate.status == LearningObservationCandidateStatus.READY ||
                candidate.status == LearningObservationCandidateStatus.MATERIALIZED,
        ) {
            "Only a ready or already materialized candidate may cross the attribution boundary"
        }
        require(event.candidateId == candidate.candidateId) {
            "Attributed event must reference the canonical candidate"
        }
        require(event.sourceFactId == candidate.sourceFactId) {
            "Attributed event must inherit the candidate source-fact id"
        }
        require(event.learnerId == candidate.learnerId) {
            "Attributed event learner must match its candidate"
        }
        require(event.practiceUnitId == candidate.practiceUnitId) {
            "Attributed event practice unit must match its candidate"
        }
        require(event.problemRevisionId == candidate.problemRevisionId) {
            "Attributed event problem revision must match its candidate"
        }
        require(event.occurredAtEpochMillis == candidate.occurredAtEpochMillis) {
            "Attributed event time must match its candidate"
        }
        require(event.direction == candidate.direction) {
            "Attributed event direction must match its candidate"
        }
        require(event.evidenceLevel == candidate.evidenceLevel) {
            "Attributed event evidence level must match its candidate"
        }
        require(event.evidenceWeight == candidate.evidenceWeight) {
            "Attributed event weight must match its candidate"
        }
        require(event.independence == candidate.independence) {
            "Attributed event independence must match its candidate"
        }
        require(event.attributions == candidate.proposedAttributions) {
            "Attributed event attributions must match its candidate"
        }
        require(event.modelVersion == candidate.modelVersion) {
            "Attributed event model version must match its candidate"
        }
        require(event.evidenceLocator == candidate.evidenceLocator) {
            "Attributed event evidence locator must match its candidate"
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

    private fun requireCandidateAssociation(
        sourceFact: LearningObservationSourceFact,
        candidate: LearningObservationCandidate,
    ) {
        require(candidate.sourceFactId == sourceFact.sourceFactId) {
            "Observation candidate must name the canonical source fact"
        }
        require(candidate.learnerId == sourceFact.learnerScopeId) {
            "Observation candidate learner must match the canonical source fact"
        }
        require(candidate.source == sourceFact.source) {
            "Observation candidate source must match the canonical source fact"
        }
        require(candidate.sourceReferenceId == canonicalSourceReferenceId(sourceFact)) {
            "Observation candidate source reference must match the canonical source fact"
        }
        require(candidate.occurredAtEpochMillis == sourceFact.occurredAtEpochMillis) {
            "Observation candidate time must match the canonical source fact"
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

    private const val MODEL_EVALUATION_MAXIMUM_WEIGHT = 0.25
    private const val ASSISTED_MODEL_EVALUATION_MAXIMUM_WEIGHT = 0.2
    private const val SPECIFIC_STUCK_MAXIMUM_WEIGHT = 0.25
    private const val IMPORTED_VISIBLE_ERROR_MAXIMUM_WEIGHT = 1.0
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
