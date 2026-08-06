package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.ReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryCrossStoreCodecTest {
    @Test
    fun reviewObservationRoundTripsWithoutAddingMasteryClaims() {
        val payload = currentReviewObservation()

        val decoded =
            LearnerMasteryCrossStoreCodec.decode(
                payloadType = payload.payloadType,
                payloadVersion = payload.payloadVersion,
                wire = LearnerMasteryCrossStoreCodec.encode(payload),
            )

        assertEquals(payload, decoded)
        assertEquals(payload.payloadCanonicalFingerprint, decoded.payloadCanonicalFingerprint)
    }

    @Test
    fun unsupportedReviewObservationVersionFailsClosed() {
        val payload = currentReviewObservation()
        val failure =
            runCatching {
                LearnerMasteryCrossStoreCodec.decode(
                    payloadType = payload.payloadType,
                    payloadVersion = payload.payloadVersion + 1,
                    wire = LearnerMasteryCrossStoreCodec.encode(payload),
                )
            }

        assertTrue(failure.isFailure)
    }

    @Test
    fun legacyReviewObservationV1DecodesForArchiveInspectionOnly() {
        val payload = archivedReviewObservationV1()

        val decoded =
            LearnerMasteryCrossStoreCodec.decode(
                payloadType = payload.payloadType,
                payloadVersion = payload.payloadVersion,
                wire = LearnerMasteryCrossStoreCodec.encode(payload),
            )

        assertEquals(payload, decoded)
        assertTrue(decoded is ReviewObservationCapturedV1)
    }

    private fun currentReviewObservation(): ReviewObservationCapturedV2 =
        ReviewObservationCapturedV2(
            problemRevision = revision(),
            reviewSessionId = "review-session-codec",
            reviewQueueItemId = "review-item-codec",
            observationId = "review-observation-codec",
            submissionId = "review-submission-codec",
            presentationId = "review-presentation-codec",
            responseForm = ReviewResponseForm.VISUAL_TARGET,
            responseOpaqueBinding = "b".repeat(64),
            responseBindingAlgorithmVersion = "test-hmac-sha256-v1",
            verificationOutcome = ReviewVerificationOutcome.CORRECT,
            attemptOrdinal = 2,
            hintCount = 1,
            answerWasRevealed = false,
            verificationPolicyVersion = "review-verification-codec-v2",
            elapsedDurationMillis = null,
            capturedAtEpochMillis = 10_000L,
        )

    private fun archivedReviewObservationV1(): ReviewObservationCapturedV1 =
        ReviewObservationCapturedV1(
            problemRevision = revision(),
            reviewSessionId = "review-session-codec",
            reviewQueueItemId = "review-item-codec",
            observationId = "review-observation-codec",
            submissionId = "review-submission-codec",
            presentationId = "review-presentation-codec",
            responseForm = ReviewResponseForm.VISUAL_TARGET,
            responseCanonicalFingerprint = "b".repeat(64),
            verificationOutcome = ReviewVerificationOutcome.CORRECT,
            attemptOrdinal = 2,
            hintCount = 1,
            answerWasRevealed = false,
            verificationPolicyVersion = "review-verification-codec-v1",
            elapsedDurationMillis = null,
            capturedAtEpochMillis = 10_000L,
        )

    private fun revision(): StudentProblemRevisionRef =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = "learner-codec",
                    subject = SubjectKind.PHYSICS,
                    problemId = "problem-codec",
                    practiceUnitId = "practice-codec",
                ),
            revisionId = "revision-codec",
            revisionNumber = 2,
            documentCanonicalFingerprint = "a".repeat(64),
        )
}
