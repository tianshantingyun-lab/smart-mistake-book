package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureScreenPolicyTest {
    @Test
    fun assessmentBlocksEntryOnlyForNonPassDecisions() {
        assertFalse(captureAssessmentBlocksEntry(null))
        assertFalse(captureAssessmentBlocksEntry(CaptureAssessmentDecision.PASS))
        assertTrue(captureAssessmentBlocksEntry(CaptureAssessmentDecision.RECAPTURE))
    }

    @Test
    fun candidateKindFollowsStructuredAndTransitionalPrecedence() {
        assertEquals(
            CaptureCandidateKind.MODEL_STRUCTURED,
            captureCandidateKind(
                hasStructuredCandidate = true,
                recognitionStateIsCandidateAvailable = true,
            ),
        )
        assertEquals(
            CaptureCandidateKind.LOCAL_TRANSITIONAL,
            captureCandidateKind(
                hasStructuredCandidate = false,
                recognitionStateIsCandidateAvailable = true,
            ),
        )
        assertEquals(
            CaptureCandidateKind.NONE,
            captureCandidateKind(
                hasStructuredCandidate = false,
                recognitionStateIsCandidateAvailable = false,
            ),
        )
    }

    @Test
    fun settingsOnlyRequiresOpenSettingsAndProviderMatchNotFalse() {
        assertTrue(
            captureSettingsOnly(
                recoveryActionIsOpenSettings = true,
                recoveryTaskMatchesProvider = null,
            ),
        )
        assertTrue(
            captureSettingsOnly(
                recoveryActionIsOpenSettings = true,
                recoveryTaskMatchesProvider = true,
            ),
        )
        assertFalse(
            captureSettingsOnly(
                recoveryActionIsOpenSettings = true,
                recoveryTaskMatchesProvider = false,
            ),
        )
        assertFalse(
            captureSettingsOnly(
                recoveryActionIsOpenSettings = false,
                recoveryTaskMatchesProvider = null,
            ),
        )
    }

    @Test
    fun continuationRequiredNeedsApprovalGapAndAnyDurableAnchor() {
        assertTrue(
            captureContinuationRequired(
                captureEgressApprovalRequired = true,
                captureEgressManifestAvailable = false,
                hasFreshCaptureEgressIntent = false,
                hasDraftId = true,
                hasResumeDraftId = false,
                hasCaptureRecoveryTask = false,
                hasPersistedCaptureEgress = false,
                hasSavedCaptureEgress = false,
                hasAssessmentRequestId = true,
            ),
        )
        assertFalse(
            captureContinuationRequired(
                captureEgressApprovalRequired = true,
                captureEgressManifestAvailable = true,
                hasFreshCaptureEgressIntent = false,
                hasDraftId = true,
                hasResumeDraftId = false,
                hasCaptureRecoveryTask = false,
                hasPersistedCaptureEgress = false,
                hasSavedCaptureEgress = false,
                hasAssessmentRequestId = true,
            ),
        )
        assertFalse(
            captureContinuationRequired(
                captureEgressApprovalRequired = true,
                captureEgressManifestAvailable = false,
                hasFreshCaptureEgressIntent = false,
                hasDraftId = true,
                hasResumeDraftId = false,
                hasCaptureRecoveryTask = false,
                hasPersistedCaptureEgress = false,
                hasSavedCaptureEgress = false,
                hasAssessmentRequestId = false,
            ),
        )
    }

    @Test
    fun candidateGateOpensOnlyForUsableStructuredOrConfirmedCandidate() {
        assertTrue(
            captureCandidateGateOpen(
                candidateIsUsable = true,
                hasStructuredCandidate = true,
                correctedStructuredCandidate = false,
                finalConfirmationPending = false,
                assessmentBlocksEntry = false,
            ),
        )
        assertTrue(
            captureCandidateGateOpen(
                candidateIsUsable = true,
                hasStructuredCandidate = true,
                correctedStructuredCandidate = false,
                finalConfirmationPending = true,
                assessmentBlocksEntry = true,
            ),
        )
        assertFalse(
            captureCandidateGateOpen(
                candidateIsUsable = true,
                hasStructuredCandidate = true,
                correctedStructuredCandidate = false,
                finalConfirmationPending = false,
                assessmentBlocksEntry = true,
            ),
        )
        assertFalse(
            captureCandidateGateOpen(
                candidateIsUsable = false,
                hasStructuredCandidate = true,
                correctedStructuredCandidate = false,
                finalConfirmationPending = false,
                assessmentBlocksEntry = false,
            ),
        )
    }
}
