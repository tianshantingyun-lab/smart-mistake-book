package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureInputSource

internal data class CaptureReturnedImageApplication(
    val deleteUris: List<String> = emptyList(),
    val clearPendingCamera: Boolean = false,
    val receivedUri: String? = null,
    val receivedSource: CaptureInputSource? = null,
    val resetDraft: Boolean = false,
    val bindPurpose: CaptureAcquisitionPurpose? = null,
    val bindUri: String? = null,
    val clearCaptureError: Boolean = false,
    val replacementUri: String? = null,
    val replacementSource: CaptureInputSource? = null,
    val clearReplacementError: Boolean = false,
    val appendUri: String? = null,
    val appendSource: CaptureInputSource? = null,
    val cancelAcquisition: Boolean = false,
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
        bindPurpose = purpose,
        bindUri = plan.uri,
        clearCaptureError = true,
    )
    is CaptureReturnedImagePlan.ReplaceExisting -> CaptureReturnedImageApplication(
        deleteUris = listOfNotNull(plan.deletePreviousUri),
        replacementUri = plan.uri,
        replacementSource = source,
        bindPurpose = purpose,
        bindUri = plan.uri,
        clearReplacementError = true,
        clearCaptureError = true,
    )
    is CaptureReturnedImagePlan.Append -> CaptureReturnedImageApplication(
        bindPurpose = purpose,
        bindUri = plan.uri,
        appendUri = plan.uri,
        appendSource = source,
    )
    is CaptureReturnedImagePlan.KeepCurrent -> CaptureReturnedImageApplication(
        deleteUris = listOfNotNull(plan.deleteUri),
        cancelAcquisition = plan.cancelAcquisition,
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
        if (application.bindPurpose != null && application.bindUri != null) {
            sink.bindReturnedSource(application.bindPurpose, application.bindUri)
        }
        if (application.clearCaptureError) sink.clearCaptureError()
        application.replacementUri?.let { uri ->
            sink.setReplacement(uri, requireNotNull(application.replacementSource))
        }
        if (application.clearReplacementError) sink.clearReplacementError()
        application.appendUri?.let { uri ->
            sink.persistAdditionalPage(uri, requireNotNull(application.appendSource))
        }
        if (application.cancelAcquisition) sink.cancelAcquisition()
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
    val bindReturnedSource: (CaptureAcquisitionPurpose, String) -> Unit,
    val clearCaptureError: () -> Unit,
    val setReplacement: (String, CaptureInputSource) -> Unit,
    val clearReplacementError: () -> Unit,
    val persistAdditionalPage: (String, CaptureInputSource) -> Unit,
    val cancelAcquisition: () -> Unit,
    val resetPurpose: () -> Unit,
    val clearReplacementRequest: () -> Unit,
    val setCaptureError: (String) -> Unit,
)
