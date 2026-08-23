package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowState
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.AppFailure
import com.tingyun.smartmistakebook.core.model.AppFailureCode

enum class CaptureResumeLoadState {
    NOT_REQUESTED,
    LOADING,
    READY,
    MISSING,
    SOURCE_UNAVAILABLE,
    REDIRECTING,
}

data class CaptureWorkflowUiState(
    val workflow: CaptureWorkflowState = CaptureWorkflowState(),
    val resumeState: CaptureResumeLoadState = CaptureResumeLoadState.NOT_REQUESTED,
    val providerCapabilities: ProviderCapabilitySnapshot? = null,
    val userError: AppFailure? = null,
    val pendingTutorSessionId: String? = null,
    val importedDraft: CaptureDraftImportedEvent? = null,
    val confirmedTutorSession: ConfirmedTutorSession? = null,
) {
    val busy: Boolean
        get() = workflow.phase != com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase.IDLE &&
            workflow.phase != com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase.REVIEWING &&
            workflow.phase != com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase.FAILED &&
            workflow.phase != com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase.SAVED
}

data class CaptureDraftImportedEvent(
    val requestId: String,
    val occurredAtEpochMillis: Long,
    val sourceUri: String,
    val purpose: CaptureAcquisitionPurpose,
    val summary: CaptureDraftSummary,
)
