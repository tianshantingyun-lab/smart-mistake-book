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

internal class CaptureReturnedImageCommands(
    private val sink: CaptureReturnedImageSink,
) {
    fun apply(
        plan: CaptureReturnedImagePlan,
        source: CaptureInputSource,
        purpose: CaptureAcquisitionPurpose,
    ) {
        val application = captureReturnedImageApplication(plan, source, purpose)
        application.deleteUris.forEach(sink.deleteOwnedUri)
        if (application.clearPendingCamera) sink.clearPendingCamera()
        application.receivedUri?.let { uri ->
            sink.setReceivedImage(uri, requireNotNull(application.receivedSource))
        }
        if (application.resetDraft) sink.resetDraft()
        if (application.clearCaptureError) sink.clearCaptureError()
        application.replacementUri?.let { uri ->
            sink.setReplacement(uri, requireNotNull(application.replacementSource))
        }
        if (application.clearReplacementError) sink.clearReplacementError()
        application.appendUri?.let { uri ->
            sink.persistAdditionalPage(uri, requireNotNull(application.appendSource))
        }
        if (application.resetPurpose) sink.resetPurpose()
        if (application.clearReplacementRequest) sink.clearReplacementRequest()
        application.captureError?.let(sink.setCaptureError)
    }
}

internal class CaptureReturnedImageSink(
    val deleteOwnedUri: (String) -> Unit,
    val clearPendingCamera: () -> Unit,
    val setReceivedImage: (String, CaptureInputSource) -> Unit,
    val resetDraft: () -> Unit,
    val clearCaptureError: () -> Unit,
    val setReplacement: (String, CaptureInputSource) -> Unit,
    val clearReplacementError: () -> Unit,
    val persistAdditionalPage: (String, CaptureInputSource) -> Unit,
    val resetPurpose: () -> Unit,
    val clearReplacementRequest: () -> Unit,
    val setCaptureError: (String) -> Unit,
)
