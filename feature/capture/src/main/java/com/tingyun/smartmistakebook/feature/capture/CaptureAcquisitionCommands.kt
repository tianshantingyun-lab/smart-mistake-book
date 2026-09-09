package com.tingyun.smartmistakebook.feature.capture

import android.content.Context
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera/photo-picker acquisition flow. Keeps its Android launchers and
 * owned-URI lifecycle; screen state is written directly, cross-command steps
 * (workspace flush, returned-image application) delegate to their commands,
 * and the cache-prune latch is injected.
 */
internal class CaptureAcquisitionCommands(
    private val context: Context,
    private val scope: CoroutineScope,
    private val launchers: CaptureAcquisitionLaunchers,
    private val state: CaptureScreenState,
    private val workspaceCommands: CaptureWorkspaceCommands,
    private val returnedImages: CaptureReturnedImageCommands,
    private val onWaitForCachePrune: suspend () -> Unit,
) {
    fun launchCamera(
        purpose: CaptureAcquisitionPurpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
        workspaceAlreadyFlushed: Boolean = false,
    ) {
        when (
            captureAcquisitionLaunchDecision(
                hasWorkspace = state.workspaceState != null,
                workspaceAlreadyFlushed = workspaceAlreadyFlushed,
                cameraLaunchInProgress = state.cameraLaunchInProgress,
                photoImportInProgress = state.photoImportInProgress,
                workflowInProgress = state.workflowInProgress,
            )
        ) {
            CaptureAcquisitionLaunchDecision.FLUSH_WORKSPACE_FIRST -> {
                workspaceCommands.afterFlush {
                    launchCamera(purpose, workspaceAlreadyFlushed = true)
                }
                return
            }
            CaptureAcquisitionLaunchDecision.BUSY -> return
            CaptureAcquisitionLaunchDecision.LAUNCH -> Unit
        }
        state.acquisitionPurposeName = purpose.name
        val replacementPrep = captureReplacementLaunchPrep(
            purpose = purpose,
            nowEpochMillis = System.currentTimeMillis(),
            newRequestId = { UUID.randomUUID().toString() },
        )
        applyReplacementPrep(replacementPrep)
        state.cameraLaunchInProgress = true
        scope.launch {
            var createdUri: Uri? = null
            try {
                onWaitForCachePrune()
                val result = withContext(Dispatchers.IO) { createCaptureUri(context) }
                state.cameraLaunchInProgress = false
                val created = result.getOrNull()
                createdUri = created
                when (
                    val outcome = captureCameraCreateOutcome(
                        createdUriString = created?.toString(),
                        launchSucceeded = created != null &&
                            runCatching { launchers.takePicture.launch(created) }.isSuccess,
                    )
                ) {
                    is CaptureCameraCreateOutcome.Ready -> {
                        state.pendingCameraUri = outcome.uriString
                        state.captureError = null
                    }
                    is CaptureCameraCreateOutcome.LaunchFailed -> {
                        revokeCaptureGrant(context, outcome.uriString)
                        returnedImages.apply(
                            captureCameraUnavailablePlan(outcome.uriString),
                            CaptureInputSource.CAMERA,
                            purpose,
                        )
                        state.pendingCameraUri = null
                    }
                    CaptureCameraCreateOutcome.CreateFailed -> {
                        returnedImages.apply(
                            captureCreatePhotoFileFailedPlan(),
                            CaptureInputSource.CAMERA,
                            purpose,
                        )
                    }
                }
            } finally {
                if (!isActive) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        createdUri?.toString()?.let { deleteOwnedCapture(context, it) }
                    }
                }
            }
        }
    }

    fun launchPhotoPicker(
        purpose: CaptureAcquisitionPurpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
        workspaceAlreadyFlushed: Boolean = false,
    ) {
        when (
            captureAcquisitionLaunchDecision(
                hasWorkspace = state.workspaceState != null,
                workspaceAlreadyFlushed = workspaceAlreadyFlushed,
                cameraLaunchInProgress = state.cameraLaunchInProgress,
                photoImportInProgress = state.photoImportInProgress,
                workflowInProgress = state.workflowInProgress,
            )
        ) {
            CaptureAcquisitionLaunchDecision.FLUSH_WORKSPACE_FIRST -> {
                workspaceCommands.afterFlush {
                    launchPhotoPicker(purpose, workspaceAlreadyFlushed = true)
                }
                return
            }
            CaptureAcquisitionLaunchDecision.BUSY -> return
            CaptureAcquisitionLaunchDecision.LAUNCH -> Unit
        }
        state.acquisitionPurposeName = purpose.name
        state.photoImportInProgress = true
        when (
            capturePickerLaunchOutcome(
                launchSucceeded = runCatching {
                    launchers.pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }.isSuccess,
            )
        ) {
            CapturePickerLaunchOutcome.Launched -> Unit
            CapturePickerLaunchOutcome.Unavailable -> {
                returnedImages.apply(
                    capturePhotoPickerUnavailablePlan(),
                    CaptureInputSource.PHOTO_PICKER,
                    purpose,
                )
                state.photoImportInProgress = false
            }
        }
    }

    fun requestRetake(hasDraft: Boolean) {
        launchCamera(retakeAcquisitionPurpose(hasDraft = hasDraft))
    }

    private fun applyReplacementPrep(prep: CaptureReplacementLaunchPrep) {
        if (prep.requestId != null) {
            state.replacementRequestId = prep.requestId
            state.replacementOccurredAtEpochMillis = prep.occurredAtEpochMillis
        }
        if (prep.clearReplacementError) {
            state.replacementError = null
        }
    }
}
