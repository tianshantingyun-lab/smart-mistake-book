package com.tingyun.smartmistakebook.core.model

enum class LearningEvidenceDirection {
    POSITIVE,
    NEGATIVE,
    NONE,
}

enum class LearningEvidenceReason {
    INDEPENDENT_CORRECT,
    CORRECT_AFTER_HINT,
    CORRECT_ON_RETRY,
    SELF_REPORTED_RECALL,
    ANSWER_REVEALED,
    INDEPENDENT_INCORRECT,
    INCORRECT_AFTER_HINT,
    INCORRECT_ON_RETRY,
    INCORRECT_AFTER_REVEAL,
    SELF_REPORTED_STUCK,
    VISUAL_INTERACTION_SATISFIED,
    VISUAL_INTERACTION_VIOLATED,
}

/** Signed evidence whose independence is derived from its reason, never supplied by a caller. */
data class LearningEvidence(
    val direction: LearningEvidenceDirection,
    val weight: Double,
    val reason: LearningEvidenceReason,
) {
    init {
        require(weight.isFinite() && weight in 0.0..1.0) {
            "Evidence weight must be between zero and one"
        }
        require((direction == LearningEvidenceDirection.NONE) == (weight == 0.0)) {
            "Only no-evidence decisions may have zero weight"
        }
        require(
            when (reason) {
                LearningEvidenceReason.INDEPENDENT_CORRECT,
                LearningEvidenceReason.CORRECT_AFTER_HINT,
                LearningEvidenceReason.CORRECT_ON_RETRY,
                LearningEvidenceReason.SELF_REPORTED_RECALL,
                LearningEvidenceReason.VISUAL_INTERACTION_SATISFIED,
                -> direction == LearningEvidenceDirection.POSITIVE

                LearningEvidenceReason.INDEPENDENT_INCORRECT,
                LearningEvidenceReason.INCORRECT_AFTER_HINT,
                LearningEvidenceReason.INCORRECT_ON_RETRY,
                LearningEvidenceReason.INCORRECT_AFTER_REVEAL,
                LearningEvidenceReason.SELF_REPORTED_STUCK,
                LearningEvidenceReason.VISUAL_INTERACTION_VIOLATED,
                -> direction == LearningEvidenceDirection.NEGATIVE

                LearningEvidenceReason.ANSWER_REVEALED ->
                    direction == LearningEvidenceDirection.NONE
            },
        ) { "Evidence direction must match its reason" }
    }

    val isIndependent: Boolean
        get() = reason == LearningEvidenceReason.INDEPENDENT_CORRECT ||
            reason == LearningEvidenceReason.INDEPENDENT_INCORRECT

    val signedWeight: Double
        get() = when (direction) {
            LearningEvidenceDirection.POSITIVE -> weight
            LearningEvidenceDirection.NEGATIVE -> -weight
            LearningEvidenceDirection.NONE -> 0.0
        }
}

/**
 * A locally fixed review control, not a mathematical answer key. Its snapshot deliberately carries
 * no knowledge attribution, so a student's one-tap report can adjust only this question's revisit
 * cadence and can never become knowledge-mastery evidence.
 */
object LocalReviewSelfReportContract {
    const val ASSESSMENT_ITEM_ID_PREFIX = "local-review-self-report:"
    const val ANSWER_SPEC_ID = "local-review-self-report-v1"
    const val ITEM_FAMILY_ID = "local-review-self-report"
    const val TAXONOMY_VERSION = "local-review-self-report-v1"

    fun matches(snapshot: AssessmentEvidenceSnapshot): Boolean =
        snapshot.assessmentItemId.startsWith(ASSESSMENT_ITEM_ID_PREFIX) &&
            snapshot.assessmentItemId.length > ASSESSMENT_ITEM_ID_PREFIX.length &&
            snapshot.answerSpecId == ANSWER_SPEC_ID &&
            snapshot.itemFamilyId == ITEM_FAMILY_ID &&
            snapshot.sourceBundleId == null &&
            snapshot.taxonomyVersion == TAXONOMY_VERSION &&
            snapshot.calibration.support == CalibrationSupport.UNKNOWN &&
            snapshot.attributions.isEmpty()
}

enum class CalibrationSupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

/** Versioned calibration assertion. Support expires instead of sticking to mastery forever. */
data class CalibrationSnapshot(
    val support: CalibrationSupport,
    val sourceId: String,
    val version: String,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long,
) {
    init {
        require(sourceId.isNotBlank()) { "Calibration source id must not be blank" }
        require(version.isNotBlank()) { "Calibration version must not be blank" }
        require(validFromEpochMillis >= 0) { "Calibration validity start must not be negative" }
        require(validUntilEpochMillis > validFromEpochMillis) {
            "Calibration validity end must follow its start"
        }
    }

    fun supportAt(atEpochMillis: Long): CalibrationSupport =
        if (atEpochMillis in validFromEpochMillis..validUntilEpochMillis) support else CalibrationSupport.UNKNOWN

    companion object {
        fun unknown() = CalibrationSnapshot(
            support = CalibrationSupport.UNKNOWN,
            sourceId = "unknown-calibration-source",
            version = "unknown-calibration-v0",
            validFromEpochMillis = 0,
            validUntilEpochMillis = 1,
        )
    }
}

enum class AssessmentSnapshotVerification {
    VERIFIED,
    STALE,
    UNVERIFIED,
}

enum class EvidenceAttributionRole {
    PRIMARY,
    SECONDARY,
}

enum class EvidenceAttributionCertainty {
    DIRECT,
    AMBIGUOUS,
}

data class KnowledgeEvidenceAttribution(
    val bindingId: String,
    val knowledgeNodeId: String,
    val weight: Double,
    val basisRevisionId: String,
    val taxonomyVersion: String,
    val role: EvidenceAttributionRole,
    val certainty: EvidenceAttributionCertainty,
) {
    init {
        require(bindingId.isNotBlank()) { "Binding id must not be blank" }
        require(knowledgeNodeId.isNotBlank()) { "Knowledge-node id must not be blank" }
        require(weight.isFinite() && weight > 0.0 && weight <= 1.0) {
            "Attribution weight must be greater than zero and at most one"
        }
        require(basisRevisionId.isNotBlank()) { "Attribution basis revision must not be blank" }
        require(taxonomyVersion.isNotBlank()) { "Attribution taxonomy version must not be blank" }
    }
}

/** Immutable assessment/binding facts accepted by the content verification boundary. */
data class AssessmentEvidenceSnapshot(
    val snapshotId: String,
    val assessmentItemId: String,
    val practiceUnitId: String,
    val problemRevisionId: String,
    val answerSpecId: String,
    val itemFamilyId: String,
    val sourceBundleId: String?,
    val taxonomyVersion: String,
    val verification: AssessmentSnapshotVerification,
    val calibration: CalibrationSnapshot,
    val attributions: List<KnowledgeEvidenceAttribution>,
    val capturedAtEpochMillis: Long,
) {
    init {
        require(snapshotId.isNotBlank()) { "Assessment snapshot id must not be blank" }
        require(assessmentItemId.isNotBlank()) { "Assessment item id must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(problemRevisionId.isNotBlank()) { "Problem revision id must not be blank" }
        require(answerSpecId.isNotBlank()) { "Answer-spec id must not be blank" }
        require(itemFamilyId.isNotBlank()) { "Item family id must not be blank" }
        require(sourceBundleId == null || sourceBundleId.isNotBlank()) {
            "Source bundle id must not be blank when provided"
        }
        require(taxonomyVersion.isNotBlank()) { "Taxonomy version must not be blank" }
        require(capturedAtEpochMillis >= 0) { "Snapshot capture time must not be negative" }
        require(attributions.map(KnowledgeEvidenceAttribution::bindingId).distinct().size == attributions.size) {
            "Attribution binding ids must be unique"
        }
        require(attributions.map(KnowledgeEvidenceAttribution::knowledgeNodeId).distinct().size == attributions.size) {
            "A snapshot must have at most one accepted attribution per knowledge node"
        }
        require(attributions.all {
            it.basisRevisionId == problemRevisionId && it.taxonomyVersion == taxonomyVersion
        }) { "Attribution basis must match the accepted assessment snapshot" }
        require(
            attributions.filter { it.certainty == EvidenceAttributionCertainty.DIRECT }
                .sumOf(KnowledgeEvidenceAttribution::weight) <= 1.0 + 1e-9,
        ) { "Direct attribution weights must not exceed one unit of evidence" }
        require(
            attributions.none { it.certainty == EvidenceAttributionCertainty.DIRECT } ||
                attributions.any {
                    it.certainty == EvidenceAttributionCertainty.DIRECT &&
                        it.role == EvidenceAttributionRole.PRIMARY
                },
        ) { "Direct attributions require a primary binding" }
    }
}

data class StudyDayContext(
    val epochDay: Long,
    val timeZoneId: String,
    val utcOffsetMinutes: Int,
) {
    init {
        require(timeZoneId.isNotBlank()) { "Study-day time-zone id must not be blank" }
        require(utcOffsetMinutes in -18 * 60..18 * 60) { "UTC offset is outside the supported range" }
    }
}

enum class ProblemMemoryOutcome {
    INDEPENDENT_RECALL,
    ASSISTED_RECALL,
    RETRIEVAL_FAILURE,
    ANSWER_REVEALED,
}

sealed interface LearningLedgerEvent {
    val ledgerEventId: String
    val occurredAtEpochMillis: Long
    val eventSequence: Long
}

/** Events accepted by incremental projection. Corrections remain full-replay-only. */
sealed interface IncrementalLearningEvent : LearningLedgerEvent

sealed interface AttemptSubmittedResponse {
    data class Choice(
        val choiceId: String,
        val choiceMarkdown: String,
        val submittedAtEpochMillis: Long,
    ) : AttemptSubmittedResponse {
        init {
            require(choiceId.isNotBlank()) { "Submitted choice id must not be blank" }
            require(choiceMarkdown.isNotBlank()) { "Submitted choice markdown must not be blank" }
            require(submittedAtEpochMillis >= 0) { "Response submission time must not be negative" }
        }
    }

    /** A v14-or-earlier attempt whose exact submitted choice was never persisted. */
    data object LegacyUnavailable : AttemptSubmittedResponse
}

data class Attempt(
    val attemptId: String,
    val presentationId: String,
    val responseOrdinal: Int,
    val assessmentSnapshot: AssessmentEvidenceSnapshot,
    val evidence: LearningEvidence,
    val problemMemoryOutcome: ProblemMemoryOutcome,
    override val occurredAtEpochMillis: Long,
    val durationSeconds: Int,
    val studyDay: StudyDayContext,
    override val eventSequence: Long,
    val submittedResponse: AttemptSubmittedResponse = AttemptSubmittedResponse.LegacyUnavailable,
) : IncrementalLearningEvent {
    init {
        require(attemptId.isNotBlank()) { "Attempt id must not be blank" }
        require(presentationId.isNotBlank()) { "Presentation id must not be blank" }
        require(responseOrdinal > 0) { "Response ordinal must be positive" }
        require(!evidence.isIndependent || responseOrdinal == 1) {
            "Only the first response to a presentation may be independent evidence"
        }
        require(assessmentSnapshot.verification == AssessmentSnapshotVerification.VERIFIED) {
            "Attempts may only use a verified assessment snapshot"
        }
        require(assessmentSnapshot.capturedAtEpochMillis <= occurredAtEpochMillis) {
            "Assessment snapshot must exist before the attempt"
        }
        require(occurredAtEpochMillis >= 0) { "Attempt time must not be negative" }
        require(durationSeconds >= 0) { "Attempt duration must not be negative" }
        require(eventSequence > 0) { "Attempt sequence must be positive" }
        if (submittedResponse is AttemptSubmittedResponse.Choice) {
            require(submittedResponse.submittedAtEpochMillis == occurredAtEpochMillis) {
                "Response submission time must match the attempt time"
            }
        }
        require(
            when (problemMemoryOutcome) {
                ProblemMemoryOutcome.INDEPENDENT_RECALL ->
                    evidence.direction == LearningEvidenceDirection.POSITIVE && evidence.isIndependent
                ProblemMemoryOutcome.ASSISTED_RECALL ->
                    evidence.direction == LearningEvidenceDirection.POSITIVE && !evidence.isIndependent
                ProblemMemoryOutcome.RETRIEVAL_FAILURE ->
                    evidence.direction == LearningEvidenceDirection.NEGATIVE
                ProblemMemoryOutcome.ANSWER_REVEALED ->
                    evidence.reason == LearningEvidenceReason.ANSWER_REVEALED
            },
        ) { "Problem-memory outcome must match the learning evidence" }
    }

    override val ledgerEventId: String
        get() = attemptId

    val practiceUnitId: String
        get() = assessmentSnapshot.practiceUnitId
    val problemRevisionId: String
        get() = assessmentSnapshot.problemRevisionId
    val knowledgeNodeIds: Set<String>
        get() = assessmentSnapshot.attributions.mapTo(linkedSetOf(), KnowledgeEvidenceAttribution::knowledgeNodeId)
    val itemFamilyId: String
        get() = assessmentSnapshot.itemFamilyId
    val sourceBundleId: String?
        get() = assessmentSnapshot.sourceBundleId
    val answerSpecId: String
        get() = assessmentSnapshot.answerSpecId
    val studyDayEpochDay: Long
        get() = studyDay.epochDay
}

/** A persisted terminal reveal still updates memory when the learner leaves without submitting. */
data class AnswerRevealOutcome(
    val outcomeId: String,
    val presentationId: String,
    val assessmentSnapshot: AssessmentEvidenceSnapshot,
    override val occurredAtEpochMillis: Long,
    val studyDay: StudyDayContext,
    override val eventSequence: Long,
) : IncrementalLearningEvent {
    init {
        require(outcomeId.isNotBlank()) { "Answer-reveal outcome id must not be blank" }
        require(presentationId.isNotBlank()) { "Answer-reveal presentation id must not be blank" }
        require(assessmentSnapshot.verification == AssessmentSnapshotVerification.VERIFIED) {
            "Answer reveals may only use a verified assessment snapshot"
        }
        require(assessmentSnapshot.capturedAtEpochMillis <= occurredAtEpochMillis) {
            "Assessment snapshot must exist before the answer reveal"
        }
        require(occurredAtEpochMillis >= 0) { "Answer-reveal time must not be negative" }
        require(eventSequence > 0) { "Answer-reveal sequence must be positive" }
    }

    override val ledgerEventId: String
        get() = outcomeId

    val practiceUnitId: String
        get() = assessmentSnapshot.practiceUnitId
}

/** A tutor answer that crossed the UI visibility boundary and was later anchored to one problem. */
data class TutorAnswerExposureOutcome(
    val outcomeId: String,
    val exposureId: String,
    val sessionId: String,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val problemRevisionId: String,
    val practiceUnitId: String,
    override val occurredAtEpochMillis: Long,
    override val eventSequence: Long,
) : IncrementalLearningEvent {
    init {
        require(outcomeId.isNotBlank()) { "Tutor exposure outcome id must not be blank" }
        require(exposureId.isNotBlank()) { "Tutor exposure id must not be blank" }
        require(sessionId.isNotBlank()) { "Tutor exposure session id must not be blank" }
        require(questionDocumentId.isNotBlank()) { "Tutor exposure question document id must not be blank" }
        require(questionRevisionNumber > 0) { "Tutor exposure question revision must be positive" }
        require(cycleOrdinal > 0 && turnOrdinal > 0) { "Tutor exposure ordinals must be positive" }
        require(problemRevisionId.isNotBlank()) { "Tutor exposure problem revision must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Tutor exposure practice unit must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Tutor exposure time must not be negative" }
        require(eventSequence > 0) { "Tutor exposure sequence must be positive" }
    }

    override val ledgerEventId: String
        get() = outcomeId
}

/** Corrections are only consumed by a full ledger replay; incremental projection rejects them by type. */
data class AttemptCorrection(
    val correctionId: String,
    val attemptId: String,
    val replacementEvidence: LearningEvidence,
    val replacementMemoryOutcome: ProblemMemoryOutcome,
    val reasonMarkdown: String,
    override val occurredAtEpochMillis: Long,
    override val eventSequence: Long,
) : LearningLedgerEvent {
    init {
        require(correctionId.isNotBlank()) { "Correction id must not be blank" }
        require(attemptId.isNotBlank()) { "Attempt id must not be blank" }
        require(reasonMarkdown.isNotBlank()) { "Correction reason must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Correction time must not be negative" }
        require(eventSequence > 0) { "Correction sequence must be positive" }
        require(
            when (replacementMemoryOutcome) {
                ProblemMemoryOutcome.INDEPENDENT_RECALL ->
                    replacementEvidence.direction == LearningEvidenceDirection.POSITIVE &&
                        replacementEvidence.isIndependent
                ProblemMemoryOutcome.ASSISTED_RECALL ->
                    replacementEvidence.direction == LearningEvidenceDirection.POSITIVE &&
                        !replacementEvidence.isIndependent
                ProblemMemoryOutcome.RETRIEVAL_FAILURE ->
                    replacementEvidence.direction == LearningEvidenceDirection.NEGATIVE
                ProblemMemoryOutcome.ANSWER_REVEALED ->
                    replacementEvidence.reason == LearningEvidenceReason.ANSWER_REVEALED
            },
        ) { "Replacement memory outcome must match replacement evidence" }
    }

    override val ledgerEventId: String
        get() = correctionId
}

data class ProjectionCheckpoint(
    val lastSequence: Long,
    val projectorVersion: String,
    val projectedAtEpochMillis: Long,
) {
    init {
        require(lastSequence >= 0) { "Projection sequence must not be negative" }
        require(projectorVersion.isNotBlank()) { "Projector version must not be blank" }
        require(projectedAtEpochMillis >= 0) { "Projection time must not be negative" }
    }

    companion object {
        fun empty(projectorVersion: String = "unprojected-v0") = ProjectionCheckpoint(
            lastSequence = 0,
            projectorVersion = projectorVersion,
            projectedAtEpochMillis = 0,
        )
    }
}

data class ProblemMemoryState(
    val practiceUnitId: String,
    val stabilityDays: Double,
    /**
     * FSRS difficulty in the 1..10 domain (spec mastery-scheduling §3.2);
     * projection v5 migrated the previous 0..1 scale one-to-one via D = 1 + 9·d.
     */
    val difficulty: Double,
    val lastReviewedAtEpochMillis: Long,
    val nextReviewAtEpochMillis: Long,
    val independentCorrectCount: Int = 0,
    val assistedCorrectCount: Int = 0,
    val lapseCount: Int = 0,
    val answerRevealCount: Int = 0,
    val lastLapseAtEpochMillis: Long? = null,
    val clockAnomalyCount: Int = 0,
    val lastClockAnomalyAtEpochMillis: Long? = null,
    val lastAttemptId: String,
    val projectorVersion: String,
    val checkpointSequence: Long,
    val lastEvidenceReason: String? = null,
    val lastEvidenceDirection: String? = null,
    val consecutiveCrossDaySuccess: Int = 0,
    val consecutiveCrossDayAgain: Int = 0,
) {
    init {
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(stabilityDays.isFinite() && stabilityDays > 0.0) { "Stability must be positive" }
        require(difficulty.isFinite() && difficulty in 1.0..10.0) {
            "Difficulty must be between one and ten"
        }
        require(
            consecutiveCrossDaySuccess >= 0 && consecutiveCrossDayAgain >= 0,
        ) { "Cross-day streak counters must not be negative" }
        require(lastReviewedAtEpochMillis >= 0 && nextReviewAtEpochMillis >= 0) {
            "Review times must not be negative"
        }
        require(nextReviewAtEpochMillis >= lastReviewedAtEpochMillis) {
            "Next review must not precede the latest review"
        }
        require(
            independentCorrectCount >= 0 && assistedCorrectCount >= 0 && lapseCount >= 0 &&
                answerRevealCount >= 0 && clockAnomalyCount >= 0,
        ) { "Memory counters must not be negative" }
        require(lastLapseAtEpochMillis == null || lastLapseAtEpochMillis in 0..lastReviewedAtEpochMillis) {
            "Last lapse must not occur after the latest review"
        }
        require(lastClockAnomalyAtEpochMillis == null || lastClockAnomalyAtEpochMillis >= 0) {
            "Clock-anomaly time must not be negative"
        }
        require(lastAttemptId.isNotBlank()) { "Last attempt id must not be blank" }
        require(projectorVersion.isNotBlank()) { "Projector version must not be blank" }
        require(checkpointSequence >= 0) { "Checkpoint sequence must not be negative" }
    }

    /**
     * Leech (spec §2.16): at least six lapses and the two most recent
     * cross-day reviews were both graded Again. Leeched cards leave regular
     * scheduling until a cross-day success resets [consecutiveCrossDayAgain].
     */
    val isLeeched: Boolean
        get() = lapseCount >= LEECH_LAPSE_THRESHOLD && consecutiveCrossDayAgain >= LEECH_AGAIN_STREAK

    /**
     * Graduation (spec §2.10): three consecutive cross-day successes and a
     * regular interval of at least ninety days move the card into
     * maintenance scheduling at a lower target retention.
     */
    val isGraduationEligible: Boolean
        get() = consecutiveCrossDaySuccess >= GRADUATION_SUCCESS_STREAK

    companion object {
        const val LEECH_LAPSE_THRESHOLD = 6
        const val LEECH_AGAIN_STREAK = 2
        const val GRADUATION_SUCCESS_STREAK = 3
    }
}

enum class MasteryStatus {
    UNKNOWN,
    LEARNING,
    MASTERED,
    CONFLICTED,
    STALE,
}

/**
 * Trust state of an event's timestamp. Determines whether the event can
 * contribute to study-day breadth calculations and cross-day learning.
 */
enum class EventTimeTrust {
    /** Device clock was consistent; effective time equals raw device time. */
    TRUSTED,
    /** Device clock rolled back; effective time was clamped forward to projection watermark. */
    CLOCK_ROLLBACK_CLAMPED,
    /** Device clock jumped far ahead; effective time was clamped back to projection watermark. */
    FUTURE_TIMESTAMP_CLAMPED,
    /** Event was imported from an external source without verified time. */
    IMPORTED_UNVERIFIED,
}

data class IndependentCorrectObservation(
    val itemFamilyId: String,
    val studyDayEpochDay: Long,
    val occurredAtEpochMillis: Long,
    val eventSequence: Long = 0,
    val bindingId: String = "legacy-binding",
    val evidenceWeight: Double = 1.0,
    val calibration: CalibrationSnapshot = CalibrationSnapshot.unknown(),
    /** Trust level of the event timestamp. Only TRUSTED observations create study-day breadth. */
    val timeTrust: EventTimeTrust = EventTimeTrust.TRUSTED,
) {
    /** Backward-compatible property: true only when timestamp is fully trusted. */
    val isStudyDayTrusted: Boolean get() = timeTrust == EventTimeTrust.TRUSTED
    init {
        require(itemFamilyId.isNotBlank()) { "Item family id must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Observation time must not be negative" }
        require(eventSequence >= 0) { "Observation sequence must not be negative" }
        require(bindingId.isNotBlank()) { "Observation binding id must not be blank" }
        require(evidenceWeight.isFinite() && evidenceWeight > 0.0 && evidenceWeight <= 1.0) {
            "Observation evidence weight must be greater than zero and at most one"
        }
    }

    fun calibrationSupportAt(atEpochMillis: Long): CalibrationSupport = calibration.supportAt(atEpochMillis)
}

data class KnowledgeMasteryState(
    val knowledgeNodeId: String,
    val masteryScore: Double,
    val conservativeMasteryScore: Double,
    val evidenceMass: Double,
    val independentCorrectObservations: List<IndependentCorrectObservation> = emptyList(),
    val lastIndependentErrorAtEpochMillis: Long? = null,
    val lastIndependentErrorSequence: Long? = null,
    val status: MasteryStatus,
    val calibrationSupport: CalibrationSupport,
    val projectorVersion: String,
    val checkpointSequence: Long,
    val lastEvidenceAtEpochMillis: Long? = independentCorrectObservations.maxOfOrNull {
        it.occurredAtEpochMillis
    },
    val conflictSinceSequence: Long? = null,
    val lastEvidenceReason: String? = null,
    val lastEvidenceDirection: String? = null,
) {
    init {
        require(knowledgeNodeId.isNotBlank()) { "Knowledge-node id must not be blank" }
        require(masteryScore.isFinite() && masteryScore in 0.0..1.0) {
            "Mastery probability must be between zero and one"
        }
        require(
            conservativeMasteryScore.isFinite() &&
                conservativeMasteryScore in 0.0..masteryScore,
        ) { "Mastery lower bound must be between zero and the point estimate" }
        require(evidenceMass.isFinite() && evidenceMass >= 0.0) { "Evidence mass must not be negative" }
        require(lastIndependentErrorAtEpochMillis == null || lastIndependentErrorAtEpochMillis >= 0) {
            "Independent error time must not be negative"
        }
        require(lastIndependentErrorSequence == null || lastIndependentErrorSequence > 0) {
            "Independent error sequence must be positive when provided"
        }
        require(lastEvidenceAtEpochMillis == null || lastEvidenceAtEpochMillis >= 0) {
            "Latest evidence time must not be negative"
        }
        require(conflictSinceSequence == null || conflictSinceSequence > 0) {
            "Conflict sequence must be positive when provided"
        }
        require(projectorVersion.isNotBlank()) { "Projector version must not be blank" }
        require(checkpointSequence >= 0) { "Checkpoint sequence must not be negative" }
    }
}

enum class LearnerSnapshotFreshness {
    CURRENT,
    STALE,
}

enum class ProjectionStatus {
    CURRENT,
    CATCHING_UP,
    WAITING_FOR_GAP,
    CONFLICTED,
}

data class AppliedAttemptRecord(
    val attemptId: String,
    val canonicalFingerprint: String,
    val eventSequence: Long,
    val presentationId: String,
    val responseOrdinal: Int,
) {
    init {
        require(attemptId.isNotBlank()) { "Applied attempt id must not be blank" }
        require(canonicalFingerprint.isNotBlank()) { "Applied attempt fingerprint must not be blank" }
        require(eventSequence > 0) { "Applied attempt sequence must be positive" }
        require(presentationId.isNotBlank()) { "Applied presentation id must not be blank" }
        require(responseOrdinal > 0) { "Applied response ordinal must be positive" }
    }
}

data class AppliedCorrectionRecord(
    val correctionId: String,
    val attemptId: String,
    val canonicalFingerprint: String,
    val eventSequence: Long,
) {
    init {
        require(correctionId.isNotBlank()) { "Applied correction id must not be blank" }
        require(attemptId.isNotBlank()) { "Corrected attempt id must not be blank" }
        require(canonicalFingerprint.isNotBlank()) { "Applied correction fingerprint must not be blank" }
        require(eventSequence > 0) { "Applied correction sequence must be positive" }
    }
}

data class AppliedAnswerRevealRecord(
    val outcomeId: String,
    val presentationId: String,
    val canonicalFingerprint: String,
    val eventSequence: Long,
) {
    init {
        require(outcomeId.isNotBlank()) { "Applied answer-reveal outcome id must not be blank" }
        require(presentationId.isNotBlank()) { "Applied answer-reveal presentation id must not be blank" }
        require(canonicalFingerprint.isNotBlank()) { "Applied answer-reveal fingerprint must not be blank" }
        require(eventSequence > 0) { "Applied answer-reveal sequence must be positive" }
    }
}

data class AppliedTutorAnswerExposureRecord(
    val outcomeId: String,
    val exposureId: String,
    val canonicalFingerprint: String,
    val eventSequence: Long,
) {
    init {
        require(outcomeId.isNotBlank()) { "Applied tutor-exposure outcome id must not be blank" }
        require(exposureId.isNotBlank()) { "Applied tutor-exposure id must not be blank" }
        require(canonicalFingerprint.isNotBlank()) { "Applied tutor-exposure fingerprint must not be blank" }
        require(eventSequence > 0) { "Applied tutor-exposure sequence must be positive" }
    }
}

/**
 * Authoritative per-presentation state loaded from persistence for incremental projection.
 * It remains outside [LearnerSnapshot] so unbounded presentation history is never embedded in
 * every learner snapshot.
 */
data class PresentationProjectionState(
    val presentationId: String,
    val asOfLedgerSequence: Long,
    val memoryProjectionApplied: Boolean,
    val answerRevealSequence: Long? = null,
) {
    init {
        require(presentationId.isNotBlank()) { "Presentation projection id must not be blank" }
        require(asOfLedgerSequence >= 0) { "Presentation projection watermark must not be negative" }
        require(answerRevealSequence == null || answerRevealSequence > 0) {
            "Answer-reveal sequence must be positive when provided"
        }
        require(answerRevealSequence == null || answerRevealSequence <= asOfLedgerSequence) {
            "Answer-reveal sequence must not exceed its presentation projection watermark"
        }
        require(answerRevealSequence == null || memoryProjectionApplied) {
            "A terminal answer reveal must have a projected presentation-memory outcome"
        }
    }
}

data class LearnerSnapshot(
    val learnerId: String,
    val problemMemoryStates: Map<String, ProblemMemoryState> = emptyMap(),
    val knowledgeMasteryStates: Map<String, KnowledgeMasteryState> = emptyMap(),
    val checkpoint: ProjectionCheckpoint,
    val knownLedgerHeadSequence: Long = checkpoint.lastSequence,
    val generatedAtEpochMillis: Long,
    val correctionWatermarkEpochMillis: Long? = null,
    val freshness: LearnerSnapshotFreshness = LearnerSnapshotFreshness.CURRENT,
    val projectionStatus: ProjectionStatus = ProjectionStatus.CURRENT,
    val appliedAttemptRecords: Map<String, AppliedAttemptRecord> = emptyMap(),
    val appliedCorrectionRecords: Map<String, AppliedCorrectionRecord> = emptyMap(),
    val appliedAnswerRevealRecords: Map<String, AppliedAnswerRevealRecord> = emptyMap(),
    val appliedTutorAnswerExposureRecords: Map<String, AppliedTutorAnswerExposureRecord> = emptyMap(),
) {
    init {
        require(learnerId.isNotBlank()) { "Learner id must not be blank" }
        require(generatedAtEpochMillis >= 0) { "Snapshot time must not be negative" }
        require(correctionWatermarkEpochMillis == null || correctionWatermarkEpochMillis >= 0) {
            "Correction watermark must not be negative"
        }
        require(knownLedgerHeadSequence >= checkpoint.lastSequence) {
            "Known ledger head must not precede the projected checkpoint"
        }
        require(
            projectionStatus != ProjectionStatus.CURRENT ||
                knownLedgerHeadSequence == checkpoint.lastSequence,
        ) { "A current projection must reach the known ledger head" }
        require(generatedAtEpochMillis >= checkpoint.projectedAtEpochMillis) {
            "Snapshot generation must not precede its projection checkpoint"
        }
        require(problemMemoryStates.all { (id, state) -> id == state.practiceUnitId }) {
            "Problem-memory map keys must match state ids"
        }
        require(knowledgeMasteryStates.all { (id, state) -> id == state.knowledgeNodeId }) {
            "Knowledge-mastery map keys must match state ids"
        }
        require(problemMemoryStates.values.all {
            it.checkpointSequence <= checkpoint.lastSequence && it.projectorVersion == checkpoint.projectorVersion
        }) { "Problem-memory states must share the snapshot checkpoint" }
        require(knowledgeMasteryStates.values.all {
            it.checkpointSequence <= checkpoint.lastSequence && it.projectorVersion == checkpoint.projectorVersion
        }) { "Knowledge-mastery states must share the snapshot checkpoint" }
        require(appliedAttemptRecords.all { (id, record) ->
            id == record.attemptId && record.eventSequence <= checkpoint.lastSequence
        }) { "Applied-attempt records must match their keys and checkpoint" }
        require(appliedCorrectionRecords.all { (id, record) ->
            id == record.correctionId && record.eventSequence <= checkpoint.lastSequence
        }) { "Applied-correction records must match their keys and checkpoint" }
        require(appliedAnswerRevealRecords.all { (id, record) ->
            id == record.outcomeId && record.eventSequence <= checkpoint.lastSequence
        }) { "Applied answer-reveal records must match their keys and checkpoint" }
        require(appliedTutorAnswerExposureRecords.all { (id, record) ->
            id == record.outcomeId && record.eventSequence <= checkpoint.lastSequence
        }) { "Applied tutor-exposure records must match their keys and checkpoint" }
    }

    val appliedAttemptIds: Set<String>
        get() = appliedAttemptRecords.keys

    val decisionWatermarkEpochMillis: Long
        get() = maxOf(
            generatedAtEpochMillis,
            checkpoint.projectedAtEpochMillis,
            correctionWatermarkEpochMillis ?: 0L,
        )

    companion object {
        fun empty(
            learnerId: String,
            projectorVersion: String = "unprojected-v0",
        ) = LearnerSnapshot(
            learnerId = learnerId,
            checkpoint = ProjectionCheckpoint.empty(projectorVersion),
            generatedAtEpochMillis = 0,
        )
    }
}

/** One LLM-authored teaching advisory row (three-store closed loop, v39). */
data class TeachingAdvisoryRecord(
    val advisoryId: String,
    val learnerId: String,
    val practiceUnitId: String?,
    val knowledgeNodeId: String?,
    /** TEACHING_FOCUS / MISCONCEPTION. */
    val advisoryKind: String,
    val payloadMarkdown: String,
    val confidence: Double?,
    val sourceId: String,
    val createdAtEpochMillis: Long,
)

