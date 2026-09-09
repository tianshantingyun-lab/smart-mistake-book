package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureInputSource

internal data class CaptureReturnedImageApplication(
    val deleteUris: List<String> = emptyList(),
    val clearPendingCamera: Boolean = false,
    val receivedUri: String? = null,
    val receivedSource: CaptureInputSource? = null,
    val resetDraft: Boolean = false,
    val clearCaptureError: Boolean = false,
    val replacementUri: String? = null,
    val replacementSource: CaptureInputSource? = null,
    val clearReplacementError: Boolean = false,
    val appendUri: String? = null,
    val appendSource: CaptureInputSource? = null,
    val resetPurpose: Boolean = false,
    val clearReplacementRequest: Boolean = false,
    val captureError: String? = null,
)

internal fun captureReturnedImageApplication(
    plan: CaptureReturnedImagePlan,
    source: CaptureInputSource,
    purpose: CaptureAcquisitionPurpose,
): CaptureReturnedImageApplication = when (plan) {
    is CaptureReturnedImagePlan.ApplyAsNew -> CaptureReturnedImageApplication(
        deleteUris = listOfNotNull(plan.deletePreviousUri, plan.alsoDeleteUri),
        clearPendingCamera = plan.alsoDeleteUri != null,
        receivedUri = plan.uri,
        receivedSource = source,
        resetDraft = plan.resetDraft,
        clearCaptureError = true,
    )
    is CaptureReturnedImagePlan.ReplaceExisting -> CaptureReturnedImageApplication(
        deleteUris = listOfNotNull(plan.deletePreviousUri),
        replacementUri = plan.uri,
        replacementSource = source,
        clearReplacementError = true,
        clearCaptureError = true,
    )
    is CaptureReturnedImagePlan.Append -> CaptureReturnedImageApplication(
        appendUri = plan.uri,
        appendSource = source,
    )
    is CaptureReturnedImagePlan.KeepCurrent -> CaptureReturnedImageApplication(
        deleteUris = listOfNotNull(plan.deleteUri),
        resetPurpose = plan.resetPurpose,
        clearReplacementRequest = plan.clearReplacementRequest,
        captureError = plan.error,
    )
}

/**
 * Applies a returned-image plan onto screen state. Cross-command steps (draft
 * reset, additional-page persistence) delegate to the owning commands; only
 * owned-uri deletion is injected as an external effect.
 */
internal class CaptureReturnedImageCommands(
    private val state: CaptureScreenState,
    private val draftState: CaptureDraftStateCommands,
    private val sourceImport: CaptureSourceImportCommands,
    private val onDeleteOwnedUri: (String) -> Unit,
) {
    fun apply(
        plan: CaptureReturnedImagePlan,
        source: CaptureInputSource,
        purpose: CaptureAcquisitionPurpose,
    ) {
        val application = captureReturnedImageApplication(plan, source, purpose)
        application.deleteUris.forEach(onDeleteOwnedUri)
        if (application.clearPendingCamera) state.pendingCameraUri = null
        application.receivedUri?.let { uri ->
            state.receivedImageUri = uri
            state.receivedInputSource = requireNotNull(application.receivedSource).name
        }
        if (application.resetDraft) draftState.resetDraft()
        if (application.clearCaptureError) state.captureError = null
        application.replacementUri?.let { uri ->
            state.replacementCandidateUri = uri
            state.replacementInputSourceName = requireNotNull(application.replacementSource).name
        }
        if (application.clearReplacementError) state.replacementError = null
        application.appendUri?.let { uri ->
            sourceImport.persistAdditionalPage(uri, requireNotNull(application.appendSource))
        }
        if (application.resetPurpose) {
            state.acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
        }
        if (application.clearReplacementRequest) {
            state.replacementRequestId = null
            state.replacementOccurredAtEpochMillis = null
        }
        application.captureError?.let { state.captureError = it }
    }
}
