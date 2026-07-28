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
        is AttributedLearningObservationEvent -> learningObservation(event)
        is AttemptCorrection -> correction(event)
    }

    fun learningObservation(event: AttributedLearningObservationEvent): String = digest {
        field("eventType", "ATTRIBUTED_LEARNING_OBSERVATION")
        field("schemaVersion", LEARNING_OBSERVATION_SCHEMA_VERSION)
        field("eventId", event.eventId)
        field("candidateId", event.candidateId)
        field("learnerId", event.learnerId)
        field("practiceUnitId", event.practiceUnitId)
        field("problemRevisionId", event.problemRevisionId)
        field("direction", event.direction)
        field("evidenceLevel", event.evidenceLevel)
        field("evidenceWeight", java.lang.Double.toHexString(event.evidenceWeight))
        field("independence", event.independence)
        field("occurredAtEpochMillis", event.occurredAtEpochMillis)
        field("confirmedAtEpochMillis", event.confirmedAtEpochMillis)
        field("modelVersion", event.modelVersion)
        field("evidenceLocator", event.evidenceLocator)
        field("eventSequence", event.eventSequence)
        observationAttributions(event.attributions)
    }

    fun learningObservationCandidate(candidate: LearningObservationCandidate): String = digest {
        field("payloadType", "LEARNING_OBSERVATION_CANDIDATE")
        field("schemaVersion", LEARNING_OBSERVATION_CANDIDATE_SCHEMA_VERSION)
        field("candidateId", candidate.candidateId)
        field("learnerId", candidate.learnerId)
        field("source", candidate.source)
        field("sourceReferenceId", candidate.sourceReferenceId)
        field("practiceUnitId", candidate.practiceUnitId)
        field("problemRevisionId", candidate.problemRevisionId)
        field("direction", candidate.direction)
        field("evidenceLevel", candidate.evidenceLevel)
        field("evidenceWeight", java.lang.Double.toHexString(candidate.evidenceWeight))
        field("independence", candidate.independence)
        field("occurredAtEpochMillis", candidate.occurredAtEpochMillis)
        field("modelVersion", candidate.modelVersion)
        field("evidenceLocator", candidate.evidenceLocator)
        field("createdAtEpochMillis", candidate.createdAtEpochMillis)
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
        field("attributionCount", canonical.size)
        canonical.forEachIndexed { index, attribution ->
            field("attribution[$index].bindingId", attribution.bindingId)
            field("attribution[$index].knowledgeNodeId", attribution.knowledgeNodeId)
            field("attribution[$index].weight", java.lang.Double.toHexString(attribution.weight))
            field("attribution[$index].basisRevisionId", attribution.basisRevisionId)
            field("attribution[$index].taxonomyVersion", attribution.taxonomyVersion)
            field("attribution[$index].role", attribution.role)
            field("attribution[$index].certainty", attribution.certainty)
        }
    }

    private class CanonicalDigest {
        private val digest = MessageDigest.getInstance("SHA-256")

        fun field(name: String, value: Any?) {
            append(name)
            append(value?.toString() ?: "<null>")
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
    private const val LEARNING_OBSERVATION_SCHEMA_VERSION =
        "learning-ledger-observation-canonical-v1"
    private const val LEARNING_OBSERVATION_CANDIDATE_SCHEMA_VERSION =
        "learning-observation-candidate-canonical-v1"
}
