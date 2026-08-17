package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource

enum class CaptureAcquisitionPurpose {
    NEW_CAPTURE,
    REPLACE_DRAFT,
    APPEND_DRAFT,
}

sealed interface CaptureAction {
    data class SourceSelected(
        val uri: String,
        val source: CaptureInputSource,
        val origin: CaptureEntryOrigin,
        val purpose: CaptureAcquisitionPurpose,
        val expectedPageCount: Int? = null,
    ) : CaptureAction

    data class ResumeRequested(val draftId: String) : CaptureAction

    data class Confirm(
        val workspaceIdentity: CaptureDraftWorkspaceIdentity,
        val origin: CaptureEntryOrigin,
    ) : CaptureAction

    data object RetryFailedWorkflow : CaptureAction

    data object Reset : CaptureAction
}
