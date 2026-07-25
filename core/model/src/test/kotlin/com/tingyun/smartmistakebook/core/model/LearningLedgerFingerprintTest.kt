package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LearningLedgerFingerprintTest {
    @Test
    fun `legacy attempt retains its exact v2 canonical fingerprint`() {
        assertEquals(
            "7609ec6062b7b077d3d7d4f549591e7e35914aca7982ac9f298b0f611238c1f6",
            LearningLedgerFingerprint.attempt(attempt()),
        )
    }

    @Test
    fun `choice attempt uses v3 and fingerprints the immutable response`() {
        val response = AttemptSubmittedResponse.Choice(
            choiceId = "choice-a",
            choiceMarkdown = "选项 A：\$x^2\$",
            submittedAtEpochMillis = OCCURRED_AT,
        )
        val original = attempt(response)

        assertEquals("learning-ledger-attempt-canonical-v3", LearningLedgerFingerprint.ATTEMPT_SCHEMA_VERSION)
        assertNotEquals(
            LearningLedgerFingerprint.attempt(attempt()),
            LearningLedgerFingerprint.attempt(original),
        )
        assertNotEquals(
            LearningLedgerFingerprint.attempt(original),
            LearningLedgerFingerprint.attempt(
                original.copy(submittedResponse = response.copy(choiceId = "choice-b")),
            ),
        )
        assertNotEquals(
            LearningLedgerFingerprint.attempt(original),
            LearningLedgerFingerprint.attempt(
                original.copy(submittedResponse = response.copy(choiceMarkdown = "选项 B：\$x^2\$")),
            ),
        )
        val later = OCCURRED_AT + 1
        assertNotEquals(
            LearningLedgerFingerprint.attempt(original),
            LearningLedgerFingerprint.attempt(
                original.copy(
                    occurredAtEpochMillis = later,
                    submittedResponse = response.copy(submittedAtEpochMillis = later),
                ),
            ),
        )
    }

    private fun attempt(
        response: AttemptSubmittedResponse = AttemptSubmittedResponse.LegacyUnavailable,
    ) = Attempt(
        attemptId = "attempt-legacy",
        presentationId = "presentation-1",
        responseOrdinal = 1,
        assessmentSnapshot = AssessmentEvidenceSnapshot(
            snapshotId = "snapshot-1",
            assessmentItemId = "assessment-1",
            practiceUnitId = "unit-1",
            problemRevisionId = "revision-1",
            answerSpecId = "answer-1",
            itemFamilyId = "family-1",
            sourceBundleId = null,
            taxonomyVersion = "taxonomy-v1",
            verification = AssessmentSnapshotVerification.VERIFIED,
            calibration = CalibrationSnapshot(
                support = CalibrationSupport.SUPPORTED,
                sourceId = "cal-source",
                version = "cal-v1",
                validFromEpochMillis = 0,
                validUntilEpochMillis = 10_000,
            ),
            attributions = listOf(
                KnowledgeEvidenceAttribution(
                    bindingId = "binding-1",
                    knowledgeNodeId = "knowledge-1",
                    weight = 1.0,
                    basisRevisionId = "revision-1",
                    taxonomyVersion = "taxonomy-v1",
                    role = EvidenceAttributionRole.PRIMARY,
                    certainty = EvidenceAttributionCertainty.DIRECT,
                ),
            ),
            capturedAtEpochMillis = 1_000,
        ),
        evidence = LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = 1.0,
            reason = LearningEvidenceReason.INDEPENDENT_CORRECT,
        ),
        problemMemoryOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
        occurredAtEpochMillis = OCCURRED_AT,
        durationSeconds = 30,
        studyDay = StudyDayContext(
            epochDay = 0,
            timeZoneId = "UTC",
            utcOffsetMinutes = 0,
        ),
        eventSequence = 1,
        submittedResponse = response,
    )

    private companion object {
        const val OCCURRED_AT = 2_000L
    }
}
