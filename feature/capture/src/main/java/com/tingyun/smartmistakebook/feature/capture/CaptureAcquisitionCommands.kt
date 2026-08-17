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

internal class CaptureAcquisitionCommands(
    private val context: Context,
    private val scope: CoroutineScope,
    private val launchers: CaptureAcquisitionLaunchers,
    private val sink: CaptureAcquisitionSink,
) {
    fun launchCamera(
        purpose: CaptureAcquisitionPurpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
        workspaceAlreadyFlushed: Boolean = false,
    ) {
        when (
            captureAcquisitionLaunchDecision(
                hasWorkspace = sink.hasWorkspace(),
                workspaceAlreadyFlushed = workspaceAlreadyFlushed,
                cameraLaunchInProgress = sink.cameraLaunchInProgress(),
                photoImportInProgress = sink.photoImportInProgress(),
                workflowInProgress = sink.workflowInProgress(),
            )
        ) {
            CaptureAcquisitionLaunchDecision.FLUSH_WORKSPACE_FIRST -> {
                sink.afterWorkspaceFlush {
                    launchCamera(purpose, workspaceAlreadyFlushed = true)
                }
                return
            }
            CaptureAcquisitionLaunchDecision.BUSY -> return
            CaptureAcquisitionLaunchDecision.LAUNCH -> Unit
        }
        sink.setPurpose(purpose)
        val replacementPrep = captureReplacementLaunchPrep(
            purpose = purpose,
            nowEpochMillis = System.currentTimeMillis(),
            newRequestId = { UUID.randomUUID().toString() },
        )
        sink.applyReplacementPrep(replacementPrep)
        sink.setCameraLaunchInProgress(true)
        scope.launch {
            var createdUri: Uri? = null
            try {
                sink.waitForCachePrune()
                val result = withContext(Dispatchers.IO) { createCaptureUri(context) }
                sink.setCameraLaunchInProgress(false)
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
                        sink.setPendingCameraUri(outcome.uriString)
                        sink.clearCaptureError()
                    }
                    is CaptureCameraCreateOutcome.LaunchFailed -> {
                        revokeCaptureGrant(context, outcome.uriString)
                        sink.applyReturnedImagePlan(
                            captureCameraUnavailablePlan(outcome.uriString),
                            CaptureInputSource.CAMERA,
                            purpose,
                        )
                        sink.setPendingCameraUri(null)
                    }
                    CaptureCameraCreateOutcome.CreateFailed -> {
                        sink.applyReturnedImagePlan(
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
                hasWorkspace = sink.hasWorkspace(),
                workspaceAlreadyFlushed = workspaceAlreadyFlushed,
                cameraLaunchInProgress = sink.cameraLaunchInProgress(),
                photoImportInProgress = sink.photoImportInProgress(),
                workflowInProgress = sink.workflowInProgress(),
            )
        ) {
            CaptureAcquisitionLaunchDecision.FLUSH_WORKSPACE_FIRST -> {
                sink.afterWorkspaceFlush {
                    launchPhotoPicker(purpose, workspaceAlreadyFlushed = true)
                }
                return
            }
            CaptureAcquisitionLaunchDecision.BUSY -> return
            CaptureAcquisitionLaunchDecision.LAUNCH -> Unit
        }
        sink.setPurpose(purpose)
        sink.setPhotoImportInProgress(true)
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
                sink.applyReturnedImagePlan(
                    capturePhotoPickerUnavailablePlan(),
                    CaptureInputSource.PHOTO_PICKER,
                    purpose,
                )
                sink.setPhotoImportInProgress(false)
            }
        }
    }

    fun requestRetake(hasDraft: Boolean) {
        launchCamera(retakeAcquisitionPurpose(hasDraft = hasDraft))
    }
}

internal class CaptureAcquisitionSink(
    val hasWorkspace: () -> Boolean,
    val cameraLaunchInProgress: () -> Boolean,
    val photoImportInProgress: () -> Boolean,
    val workflowInProgress: () -> Boolean,
    val setPurpose: (CaptureAcquisitionPurpose) -> Unit,
    val applyReplacementPrep: (CaptureReplacementLaunchPrep) -> Unit,
    val setCameraLaunchInProgress: (Boolean) -> Unit,
    val setPhotoImportInProgress: (Boolean) -> Unit,
    val setPendingCameraUri: (String?) -> Unit,
    val clearCaptureError: () -> Unit,
    val applyReturnedImagePlan: (
        CaptureReturnedImagePlan,
        CaptureInputSource,
        CaptureAcquisitionPurpose,
    ) -> Unit,
    val afterWorkspaceFlush: (() -> Unit) -> Unit,
    val waitForCachePrune: suspend () -> Unit,
)
