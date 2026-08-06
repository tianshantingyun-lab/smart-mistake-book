package com.tingyun.smartmistakebook.core.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Shared canonical payload contract used by storage and projection idempotency checks. */
object LearningLedgerFingerprint {
    const val SCHEMA_VERSION = "learning-ledger-canonical-v2"
    const val ATTEMPT_SCHEMA_VERSION = "learning-ledger-attempt-canonical-v3"

    fun event(event: LearningLedgerEvent): String = when (event) {
        is Attempt -> attempt(event)
        is AnswerRevealOutcome -> answerReveal(event)
        is TutorAnswerExposureOutcome -> tutorAnswerExposure(event)
        is AdmittedLearningObservationEvent -> admittedLearningObservation(event)
        is AttributedLearningObservationEvent -> learningObservation(event)
        is AttemptCorrection -> correction(event)
    }

    fun admittedLearningObservation(event: AdmittedLearningObservationEvent): String {
        require(event.admission.matches(event.observation)) {
            "Admitted learning observation must match its canonical admission proof"
        }
        return event.admission.admissionFingerprint
    }

    fun learningObservationAdmission(
        rawEventCanonicalFingerprint: String,
        sourceFactProofFingerprint: String,
        policyVersion: String,
    ): String = digest {
        observationField("payloadType", "ADMITTED_LEARNING_OBSERVATION")
        observationField("schemaVersion", LEARNING_OBSERVATION_ADMISSION_SCHEMA_VERSION)
        observationField("rawEventCanonicalFingerprint", rawEventCanonicalFingerprint)
        observationField("sourceFactProofFingerprint", sourceFactProofFingerprint)
        observationField("policyVersion", policyVersion)
    }

    fun learningObservation(event: AttributedLearningObservationEvent): String = digest {
        observationField("eventType", "ATTRIBUTED_LEARNING_OBSERVATION")
        observationField(
            "schemaVersion",
            if (event.sourceFactId == null) {
                LEARNING_OBSERVATION_SCHEMA_VERSION_V1
            } else {
                LEARNING_OBSERVATION_SCHEMA_VERSION_V2
            },
        )
        observationField("eventId", event.eventId)
        observationField("candidateId", event.candidateId)
        event.sourceFactId?.let { observationField("sourceFactId", it) }
        observationField("learnerId", event.learnerId)
        observationField("practiceUnitId", event.practiceUnitId)
        observationField("problemRevisionId", event.problemRevisionId)
        observationField("direction", event.direction)
        observationField("evidenceLevel", event.evidenceLevel)
        observationField("evidenceWeight", java.lang.Double.toHexString(event.evidenceWeight))
        observationField("independence", event.independence)
        observationField("occurredAtEpochMillis", event.occurredAtEpochMillis)
        observationField("confirmedAtEpochMillis", event.confirmedAtEpochMillis)
        observationField("modelVersion", event.modelVersion)
        observationField("evidenceLocator", event.evidenceLocator)
        observationField("eventSequence", event.eventSequence)
        observationAttributions(event.attributions)
    }

    fun learningObservationCandidate(candidate: LearningObservationCandidate): String = digest {
        observationField("payloadType", "LEARNING_OBSERVATION_CANDIDATE")
        observationField(
            "schemaVersion",
            if (candidate.sourceFactId == null) {
                LEARNING_OBSERVATION_CANDIDATE_SCHEMA_VERSION_V1
            } else {
                LEARNING_OBSERVATION_CANDIDATE_SCHEMA_VERSION_V2
            },
        )
        observationField("candidateId", candidate.candidateId)
        observationField("learnerId", candidate.learnerId)
        observationField("source", candidate.source)
        observationField("sourceReferenceId", candidate.sourceReferenceId)
        candidate.sourceFactId?.let { observationField("sourceFactId", it) }
        observationField("practiceUnitId", candidate.practiceUnitId)
        observationField("problemRevisionId", candidate.problemRevisionId)
        observationField("direction", candidate.direction)
        observationField("evidenceLevel", candidate.evidenceLevel)
        observationField("evidenceWeight", java.lang.Double.toHexString(candidate.evidenceWeight))
        observationField("independence", candidate.independence)
        observationField("occurredAtEpochMillis", candidate.occurredAtEpochMillis)
        observationField("modelVersion", candidate.modelVersion)
        observationField("evidenceLocator", candidate.evidenceLocator)
        observationField("createdAtEpochMillis", candidate.createdAtEpochMillis)
        observationAttributions(candidate.proposedAttributions)
    }

    fun answerReveal(outcome: AnswerRevealOutcome): String = digest {
        field("eventType", "ANSWER_REVEAL_OUTCOME")
        field("schemaVersion", SCHEMA_VERSION)
        field("outcomeId", outcome.outcomeId)
        field("presentationId", outcome.presentationId)
        field("eventSequence", outcome.eventSequence)
        field("occurredAtEpochMillis", outcome.occurredAtEpochMillis)
        field("studyDayEpochDay", outcome.studyDay.epochDay)
        field("studyDayTimeZoneId", outcome.studyDay.timeZoneId)
        field("studyDayUtcOffsetMinutes", outcome.studyDay.utcOffsetMinutes)
        assessmentSnapshot(outcome.assessmentSnapshot)
    }

    fun attempt(attempt: Attempt): String = digest {
        field("eventType", "ATTEMPT")
        field(
            "schemaVersion",
            when (attempt.submittedResponse) {
                is AttemptSubmittedResponse.Choice -> ATTEMPT_SCHEMA_VERSION
                AttemptSubmittedResponse.LegacyUnavailable -> SCHEMA_VERSION
            },
        )
        field("attemptId", attempt.attemptId)
        field("presentationId", attempt.presentationId)
        field("responseOrdinal", attempt.responseOrdinal)
        field("eventSequence", attempt.eventSequence)
        field("occurredAtEpochMillis", attempt.occurredAtEpochMillis)
        field("durationSeconds", attempt.durationSeconds)
        field("studyDayEpochDay", attempt.studyDay.epochDay)
        field("studyDayTimeZoneId", attempt.studyDay.timeZoneId)
        field("studyDayUtcOffsetMinutes", attempt.studyDay.utcOffsetMinutes)
        field("evidenceDirection", attempt.evidence.direction)
        field("evidenceWeight", java.lang.Double.toHexString(attempt.evidence.weight))
        field("evidenceReason", attempt.evidence.reason)
        field("problemMemoryOutcome", attempt.problemMemoryOutcome)
        when (val response = attempt.submittedResponse) {
            is AttemptSubmittedResponse.Choice -> {
                field("submittedResponseType", "CHOICE")
                field("submittedChoiceId", response.choiceId)
                field("submittedChoiceMarkdown", response.choiceMarkdown)
                field("responseSubmittedAtEpochMillis", response.submittedAtEpochMillis)
            }

            AttemptSubmittedResponse.LegacyUnavailable -> Unit
        }
        assessmentSnapshot(attempt.assessmentSnapshot)
    }

    fun tutorAnswerExposure(outcome: TutorAnswerExposureOutcome): String = digest {
        field("eventType", "TUTOR_ANSWER_EXPOSURE_OUTCOME")
        field("schemaVersion", TUTOR_ANSWER_EXPOSURE_SCHEMA_VERSION)
        field("outcomeId", outcome.outcomeId)
        field("exposureId", outcome.exposureId)
        field("sessionId", outcome.sessionId)
        field("questionDocumentId", outcome.questionDocumentId)
        field("questionRevisionNumber", outcome.questionRevisionNumber)
        field("cycleOrdinal", outcome.cycleOrdinal)
        field("turnOrdinal", outcome.turnOrdinal)
        field("problemRevisionId", outcome.problemRevisionId)
        field("practiceUnitId", outcome.practiceUnitId)
        field("occurredAtEpochMillis", outcome.occurredAtEpochMillis)
        field("eventSequence", outcome.eventSequence)
    }

    fun correction(correction: AttemptCorrection): String = digest {
        field("eventType", "ATTEMPT_CORRECTION")
        field("schemaVersion", SCHEMA_VERSION)
        field("correctionId", correction.correctionId)
        field("attemptId", correction.attemptId)
        field("replacementEvidenceDirection", correction.replacementEvidence.direction)
        field("replacementEvidenceWeight", java.lang.Double.toHexString(correction.replacementEvidence.weight))
        field("replacementEvidenceReason", correction.replacementEvidence.reason)
        field("replacementMemoryOutcome", correction.replacementMemoryOutcome)
        field("reasonMarkdown", correction.reasonMarkdown)
        field("occurredAtEpochMillis", correction.occurredAtEpochMillis)
        field("eventSequence", correction.eventSequence)
    }

    private inline fun digest(block: CanonicalDigest.() -> Unit): String =
        CanonicalDigest().apply(block).finish()

    private fun CanonicalDigest.assessmentSnapshot(snapshot: AssessmentEvidenceSnapshot) {
        field("snapshotId", snapshot.snapshotId)
        field("assessmentItemId", snapshot.assessmentItemId)
        field("practiceUnitId", snapshot.practiceUnitId)
        field("problemRevisionId", snapshot.problemRevisionId)
        field("answerSpecId", snapshot.answerSpecId)
        field("itemFamilyId", snapshot.itemFamilyId)
        field("sourceBundleId", snapshot.sourceBundleId)
        field("taxonomyVersion", snapshot.taxonomyVersion)
        field("verification", snapshot.verification)
        field("capturedAtEpochMillis", snapshot.capturedAtEpochMillis)
        field("calibrationSupport", snapshot.calibration.support)
        field("calibrationSourceId", snapshot.calibration.sourceId)
        field("calibrationVersion", snapshot.calibration.version)
        field("calibrationValidFrom", snapshot.calibration.validFromEpochMillis)
        field("calibrationValidUntil", snapshot.calibration.validUntilEpochMillis)
        val canonicalAttributions = snapshot.attributions.sortedBy(
            KnowledgeEvidenceAttribution::bindingId,
        )
        field("attributionCount", canonicalAttributions.size)
        canonicalAttributions.forEachIndexed { index, attribution ->
            field("attribution[$index].bindingId", attribution.bindingId)
            field("attribution[$index].knowledgeNodeId", attribution.knowledgeNodeId)
            field("attribution[$index].weight", java.lang.Double.toHexString(attribution.weight))
            field("attribution[$index].basisRevisionId", attribution.basisRevisionId)
            field("attribution[$index].taxonomyVersion", attribution.taxonomyVersion)
            field("attribution[$index].role", attribution.role)
            field("attribution[$index].certainty", attribution.certainty)
        }
    }

    private fun CanonicalDigest.observationAttributions(
        attributions: List<LearningObservationKnowledgeAttribution>,
    ) {
        val canonical = attributions.sortedBy(LearningObservationKnowledgeAttribution::bindingId)
        observationField("attributionCount", canonical.size)
        canonical.forEachIndexed { index, attribution ->
            observationField("attribution[$index].bindingId", attribution.bindingId)
            observationField("attribution[$index].knowledgeNodeId", attribution.knowledgeNodeId)
            observationField(
                "attribution[$index].weight",
                java.lang.Double.toHexString(attribution.weight),
            )
            observationField("attribution[$index].basisRevisionId", attribution.basisRevisionId)
            observationField("attribution[$index].taxonomyVersion", attribution.taxonomyVersion)
            observationField("attribution[$index].role", attribution.role)
            observationField("attribution[$index].certainty", attribution.certainty)
        }
    }

    private class CanonicalDigest {
        private val digest = MessageDigest.getInstance("SHA-256")

        fun field(name: String, value: Any?) {
            append(name)
            append(value?.toString() ?: "<null>")
        }

        fun observationField(name: String, value: Any?) {
            append(name)
            append(if (value == null) "NULL" else "PRESENT")
            value?.let { append(it.toString()) }
        }

        fun finish(): String = digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

        private fun append(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }

    private const val TUTOR_ANSWER_EXPOSURE_SCHEMA_VERSION =
        "learning-ledger-tutor-answer-exposure-canonical-v1"
    private const val LEARNING_OBSERVATION_SCHEMA_VERSION_V1 =
        "learning-ledger-observation-canonical-v1"
    private const val LEARNING_OBSERVATION_SCHEMA_VERSION_V2 =
        "learning-ledger-observation-canonical-v2"
    private const val LEARNING_OBSERVATION_ADMISSION_SCHEMA_VERSION =
        "learning-ledger-observation-admission-canonical-v1"
    private const val LEARNING_OBSERVATION_CANDIDATE_SCHEMA_VERSION_V1 =
        "learning-observation-candidate-canonical-v1"
    private const val LEARNING_OBSERVATION_CANDIDATE_SCHEMA_VERSION_V2 =
        "learning-observation-candidate-canonical-v2"
}
