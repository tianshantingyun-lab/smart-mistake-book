package com.tingyun.smartmistakebook.core.model

enum class LearningObservationSource {
    IMPORTED_MISTAKE,
    TUTOR_CHOICE,
    TUTOR_FREE_RESPONSE,
    TUTOR_VISUAL_TARGET,
    CAPTURED_REVIEW_RESPONSE,
}

enum class LearningObservationCandidateStatus {
    WAITING_FOR_ANCHOR,
    WAITING_FOR_ORGANIZATION,
    WAITING_FOR_ATTRIBUTION,
    WAITING_FOR_PROJECTION,
    PENDING_CONFIRMATION,
    READY,
    MATERIALIZED,
    REJECTED,
}

enum class LearningObservationDirection {
    POSITIVE,
    NEGATIVE,
}

enum class LearningObservationEvidenceLevel {
    LOW_CONFIDENCE,
    MEDIUM_CONFIDENCE,
    HIGH_CONFIDENCE,
    CONFIRMED,
}

enum class LearningObservationIndependence {
    INDEPENDENT,
    ASSISTED,
    UNKNOWN,
}

data class LearningObservationKnowledgeAttribution(
    val bindingId: String,
    val knowledgeNodeId: String,
    val weight: Double,
    val basisRevisionId: String,
    val taxonomyVersion: String,
    val role: EvidenceAttributionRole,
    val certainty: EvidenceAttributionCertainty,
) {
    init {
        requireObservationId(bindingId, "Observation binding id")
        requireObservationId(knowledgeNodeId, "Observation knowledge-node id")
        require(weight.isFinite() && weight > 0.0 && weight <= 1.0) {
            "Observation attribution weight must be greater than zero and at most one"
        }
        requireObservationId(basisRevisionId, "Observation attribution basis revision")
        requireObservationId(taxonomyVersion, "Observation attribution taxonomy version")
    }
}

/**
 * Immutable evidence proposed by an ingestion/model boundary. It is deliberately not a ledger
 * event: only a locally gated [AttributedLearningObservationEvent] can reach projection.
 */
data class LearningObservationCandidate(
    val candidateId: String,
    val learnerId: String,
    val source: LearningObservationSource,
    val sourceReferenceId: String,
    val practiceUnitId: String?,
    val problemRevisionId: String?,
    val direction: LearningObservationDirection,
    val evidenceLevel: LearningObservationEvidenceLevel,
    val evidenceWeight: Double,
    val independence: LearningObservationIndependence,
    val proposedAttributions: List<LearningObservationKnowledgeAttribution>,
    val occurredAtEpochMillis: Long,
    val modelVersion: String,
    val evidenceLocator: String,
    val status: LearningObservationCandidateStatus,
    val retryCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireObservationId(candidateId, "Observation candidate id")
        requireObservationId(learnerId, "Observation learner id")
        requireObservationId(sourceReferenceId, "Observation source reference id")
        require((practiceUnitId == null) == (problemRevisionId == null)) {
            "Observation anchor ids must either both be present or both be absent"
        }
        practiceUnitId?.let { requireObservationId(it, "Observation practice-unit id") }
        problemRevisionId?.let { requireObservationId(it, "Observation problem revision id") }
        require(evidenceWeight.isFinite() && evidenceWeight > 0.0 && evidenceWeight <= 1.0) {
            "Observation evidence weight must be greater than zero and at most one"
        }
        validateObservationAttributions(proposedAttributions)
        require(problemRevisionId == null || proposedAttributions.all {
            it.basisRevisionId == problemRevisionId
        }) { "Observation attribution basis must match its anchored problem revision" }
        require(occurredAtEpochMillis >= 0) { "Observation time must not be negative" }
        requireObservationId(modelVersion, "Observation model version")
        requireObservationLocator(evidenceLocator)
        require(retryCount >= 0) { "Observation retry count must not be negative" }
        require(createdAtEpochMillis >= occurredAtEpochMillis) {
            "Observation candidate creation must not precede the observation"
        }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Observation candidate update must not precede creation"
        }
    }
}

/**
 * A confirmed, anchored and attributed observation admitted by the local persistence gate.
 * Subject is intentionally absent: storage derives it from the authoritative problem chain.
 */
data class AttributedLearningObservationEvent(
    val eventId: String,
    val candidateId: String,
    val learnerId: String,
    val practiceUnitId: String,
    val problemRevisionId: String,
    val direction: LearningObservationDirection,
    val evidenceLevel: LearningObservationEvidenceLevel,
    val evidenceWeight: Double,
    val independence: LearningObservationIndependence,
    val attributions: List<LearningObservationKnowledgeAttribution>,
    override val occurredAtEpochMillis: Long,
    val confirmedAtEpochMillis: Long,
    val modelVersion: String,
    val evidenceLocator: String,
    override val eventSequence: Long,
) : IncrementalLearningEvent {
    init {
        requireObservationId(eventId, "Learning observation event id")
        requireObservationId(candidateId, "Learning observation candidate id")
        requireObservationId(learnerId, "Learning observation learner id")
        requireObservationId(practiceUnitId, "Learning observation practice-unit id")
        requireObservationId(problemRevisionId, "Learning observation problem revision id")
        require(evidenceLevel == LearningObservationEvidenceLevel.CONFIRMED) {
            "Only confirmed learning observations may become ledger events"
        }
        require(evidenceWeight.isFinite() && evidenceWeight > 0.0 && evidenceWeight <= 1.0) {
            "Learning observation evidence weight must be greater than zero and at most one"
        }
        validateObservationAttributions(attributions)
        require(attributions.all { it.basisRevisionId == problemRevisionId }) {
            "Learning observation attribution basis must match its problem revision"
        }
        require(occurredAtEpochMillis >= 0) { "Learning observation time must not be negative" }
        require(confirmedAtEpochMillis >= occurredAtEpochMillis) {
            "Learning observation confirmation must not precede the observation"
        }
        requireObservationId(modelVersion, "Learning observation model version")
        requireObservationLocator(evidenceLocator)
        require(eventSequence > 0) { "Learning observation sequence must be positive" }
    }

    override val ledgerEventId: String
        get() = eventId
}

enum class LearningEvidenceReviewReason {
    EVENT_ID_CONFLICT,
    CANDIDATE_ALREADY_MATERIALIZED,
    CANDIDATE_NOT_READY,
    LOW_CONFIDENCE,
    SOURCE_AUTHORITY_MISSING,
    SOURCE_AUTHORITY_MISMATCH,
    MISSING_AUTHORITY,
    ATTRIBUTION_CONFLICT,
    SUBJECT_MISMATCH,
    NO_DIRECT_ATTRIBUTION,
}

enum class LearningEvidenceReviewStatus {
    OPEN,
    RESOLVED,
    REJECTED,
}

data class LearningEvidenceReviewCase(
    val reviewCaseId: String,
    val candidateId: String,
    val learnerId: String,
    val proposedEventId: String,
    val reason: LearningEvidenceReviewReason,
    val detail: String,
    val status: LearningEvidenceReviewStatus,
    val createdAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long? = null,
) {
    init {
        requireObservationId(reviewCaseId, "Learning-evidence review-case id")
        requireObservationId(candidateId, "Learning-evidence review candidate id")
        requireObservationId(learnerId, "Learning-evidence review learner id")
        requireObservationId(proposedEventId, "Learning-evidence review proposed event id")
        require(detail.isNotBlank() && detail.length <= 4_000) {
            "Learning-evidence review detail must be non-blank and at most 4000 characters"
        }
        require(createdAtEpochMillis >= 0) { "Learning-evidence review time must not be negative" }
        require(resolvedAtEpochMillis == null || resolvedAtEpochMillis >= createdAtEpochMillis) {
            "Learning-evidence review resolution must not precede creation"
        }
        require(
            (status == LearningEvidenceReviewStatus.OPEN) == (resolvedAtEpochMillis == null),
        ) { "Only open learning-evidence review cases may omit a resolution time" }
    }
}

data class AppliedLearningObservationRecord(
    val observationEventId: String,
    val canonicalFingerprint: String,
    val eventSequence: Long,
) {
    init {
        requireObservationId(observationEventId, "Applied learning-observation event id")
        requireObservationId(canonicalFingerprint, "Applied learning-observation fingerprint")
        require(eventSequence > 0) { "Applied learning-observation sequence must be positive" }
    }
}

private fun validateObservationAttributions(
    attributions: List<LearningObservationKnowledgeAttribution>,
) {
    require(attributions.map { it.bindingId }.distinct().size == attributions.size) {
        "Observation attribution binding ids must be unique"
    }
    require(attributions.map { it.knowledgeNodeId }.distinct().size == attributions.size) {
        "An observation may attribute at most once to each knowledge node"
    }
    val direct = attributions.filter {
        it.certainty == EvidenceAttributionCertainty.DIRECT
    }
    require(direct.sumOf { it.weight } <= 1.0 + 1e-9) {
        "Direct observation attribution weights must not exceed one"
    }
    require(direct.isEmpty() || direct.any { it.role == EvidenceAttributionRole.PRIMARY }) {
        "Direct observation attributions require a primary attribution"
    }
}

private fun requireObservationId(value: String, label: String) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= 256 &&
            value.none(Char::isISOControl),
    ) { "$label must be a trimmed non-blank id of at most 256 characters" }
}

private fun requireObservationLocator(value: String) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= 2_048 &&
            value.none { it.isISOControl() && it !in "\n\r\t" },
    ) { "Observation evidence locator must be a trimmed non-blank value of at most 2048 characters" }
}
