package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision

internal enum class CaptureAcquisitionPurpose {
    NEW_CAPTURE,
    REPLACE_DRAFT,
    APPEND_DRAFT,
}

internal enum class CaptureResultAction {
    APPLY_AS_NEW,
    REPLACE_EXISTING,
    APPEND_EXISTING,
    KEEP_CURRENT,
}

internal fun captureResultAction(
    saved: Boolean,
    isEligibleImage: Boolean,
    purpose: CaptureAcquisitionPurpose,
): CaptureResultAction = when {
    !saved || !isEligibleImage -> CaptureResultAction.KEEP_CURRENT
    purpose == CaptureAcquisitionPurpose.REPLACE_DRAFT -> CaptureResultAction.REPLACE_EXISTING
    purpose == CaptureAcquisitionPurpose.APPEND_DRAFT -> CaptureResultAction.APPEND_EXISTING
    else -> CaptureResultAction.APPLY_AS_NEW
}

internal fun retakeAcquisitionPurpose(hasDraft: Boolean): CaptureAcquisitionPurpose =
    if (hasDraft) CaptureAcquisitionPurpose.REPLACE_DRAFT
    else CaptureAcquisitionPurpose.NEW_CAPTURE

internal fun prepareCaptureCommitAttempt(
    workspace: CaptureWorkspaceUiState,
    workspaceUpdatedAtEpochMillis: Long,
    requestIdFactory: () -> String,
    nowEpochMillis: () -> Long,
): CaptureWorkspaceUiState {
    val acceptedWorkspace = workspace.prepareForFinalCommit()
    if (acceptedWorkspace.finalConfirmationRequest != null) return acceptedWorkspace
    return acceptedWorkspace.ensureFinalConfirmation(
        requestIdFactory = requestIdFactory,
        occurredAtEpochMillis = {
            maxOf(nowEpochMillis(), workspaceUpdatedAtEpochMillis)
        },
    )
}

internal const val MAX_CAPTURE_TITLE_CHARS = 24
internal const val MAX_CAPTURE_SOURCE_PAGES = 8

internal fun resumeAssessmentRequestId(draftId: String, pageIndex: Int = 0): String =
    if (pageIndex == 0) {
        "capture-assess:resume:$draftId"
    } else {
        "capture-assess:resume:$draftId:p$pageIndex"
    }

internal fun resumeParseRequestId(draftId: String, revisionNumber: Int): String =
    "capture-parse:resume:$draftId:r$revisionNumber"

internal fun suggestCaptureTitle(candidateText: String): String {
    val firstMeaningfulLine = candidateText
        .lineSequence()
        .map { line -> line.replace(CAPTURE_TITLE_WHITESPACE, " ").trim() }
        .firstOrNull(String::isNotBlank)
        .orEmpty()
    if (firstMeaningfulLine.isBlank()) return "新拍题目"
    return if (firstMeaningfulLine.length <= MAX_CAPTURE_TITLE_CHARS) {
        firstMeaningfulLine
    } else {
        firstMeaningfulLine.take(MAX_CAPTURE_TITLE_CHARS - 1).trimEnd() + "…"
    }
}

internal fun shouldAutoPersistCapture(
    receivedImageUri: String?,
    draftId: String?,
    workflowInProgress: Boolean,
): Boolean = receivedImageUri != null && draftId == null && !workflowInProgress

internal fun shouldAutoCommitCaptureCandidate(
    draftId: String?,
    candidateUsable: Boolean,
    workflowInProgress: Boolean,
    workspaceSaving: Boolean,
    committedEntryId: String?,
    commitOutcomeUnknown: Boolean,
    autoCommittedDraftId: String?,
    modelStructuredCandidate: Boolean,
    userEditedFields: Set<CaptureDraftEditedField>,
    transcriptionEditedByUser: Boolean,
    titleEditedByUser: Boolean,
): Boolean =
    draftId != null &&
        candidateUsable &&
        !workflowInProgress &&
        !workspaceSaving &&
        committedEntryId == null &&
        !commitOutcomeUnknown &&
        autoCommittedDraftId != draftId &&
        modelStructuredCandidate &&
        userEditedFields.isEmpty() &&
        !transcriptionEditedByUser &&
        !titleEditedByUser

internal fun captureAssessmentBlocksEntry(
    assessmentDecision: CaptureAssessmentDecision?,
): Boolean = assessmentDecision != null && assessmentDecision != CaptureAssessmentDecision.PASS

internal fun captureCandidateKind(
    hasStructuredCandidate: Boolean,
    recognitionStateIsCandidateAvailable: Boolean,
): CaptureCandidateKind = when {
    hasStructuredCandidate -> CaptureCandidateKind.MODEL_STRUCTURED
    recognitionStateIsCandidateAvailable -> CaptureCandidateKind.LOCAL_TRANSITIONAL
    else -> CaptureCandidateKind.NONE
}

internal fun captureSettingsOnly(
    recoveryActionIsOpenSettings: Boolean,
    recoveryTaskMatchesProvider: Boolean?,
): Boolean = recoveryActionIsOpenSettings && recoveryTaskMatchesProvider != false

internal fun captureContinuationRequired(
    captureEgressApprovalRequired: Boolean,
    captureEgressManifestAvailable: Boolean,
    hasFreshCaptureEgressIntent: Boolean,
    hasDraftId: Boolean,
    hasResumeDraftId: Boolean,
    hasCaptureRecoveryTask: Boolean,
    hasPersistedCaptureEgress: Boolean,
    hasSavedCaptureEgress: Boolean,
    hasAssessmentRequestId: Boolean,
): Boolean =
    captureEgressApprovalRequired &&
        !captureEgressManifestAvailable &&
        !hasFreshCaptureEgressIntent &&
        hasDraftId &&
        (
            hasResumeDraftId ||
                hasCaptureRecoveryTask ||
                hasPersistedCaptureEgress ||
                hasSavedCaptureEgress ||
                hasAssessmentRequestId
            )

internal fun captureCandidateGateOpen(
    candidateIsUsable: Boolean,
    hasStructuredCandidate: Boolean,
    correctedStructuredCandidate: Boolean,
    finalConfirmationPending: Boolean,
    assessmentBlocksEntry: Boolean,
): Boolean =
    candidateIsUsable &&
        (hasStructuredCandidate || correctedStructuredCandidate || finalConfirmationPending) &&
        (!assessmentBlocksEntry || finalConfirmationPending)

private val CAPTURE_TITLE_WHITESPACE = Regex("\\s+")
