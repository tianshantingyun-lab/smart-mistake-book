package com.tingyun.smartmistakebook.feature.capture

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CaptureAcquisitionLaunchers(
    val takePicture: ManagedActivityResultLauncher<Uri, Boolean>,
    val pickPhoto: ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>,
)

@Composable
internal fun rememberCaptureAcquisitionLaunchers(
    context: Context,
    scope: CoroutineScope,
    pendingCameraUri: String?,
    receivedImageUri: String?,
    replacementCandidateUri: String?,
    acquisitionPurpose: CaptureAcquisitionPurpose,
    onPendingCameraUriChange: (String?) -> Unit,
    onPhotoImportInProgressChange: (Boolean) -> Unit,
    applyReturnedImagePlan: (
        CaptureReturnedImagePlan,
        CaptureInputSource,
        CaptureAcquisitionPurpose,
    ) -> Unit,
): CaptureAcquisitionLaunchers {
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val completedCaptureUri = pendingCameraUri
        val completedPurpose = acquisitionPurpose
        onPendingCameraUriChange(null)
        completedCaptureUri?.let { revokeCaptureGrant(context, it) }
        val eligible = completedCaptureUri != null &&
            ownedCaptureWithinLimit(context, completedCaptureUri)
        applyReturnedImagePlan(
            captureCameraActivityResultPlan(
                saved = saved,
                completedUri = completedCaptureUri,
                eligible = eligible,
                purpose = acquisitionPurpose,
                currentReceivedUri = receivedImageUri,
                currentReplacementUri = replacementCandidateUri,
            ),
            CaptureInputSource.CAMERA,
            completedPurpose,
        )
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            applyReturnedImagePlan(
                capturePickerCancelledPlan(),
                CaptureInputSource.PHOTO_PICKER,
                CaptureAcquisitionPurpose.NEW_CAPTURE,
            )
            onPhotoImportInProgressChange(false)
        } else {
            val completedPurpose = acquisitionPurpose
            scope.launch {
                var importedUri: Uri? = null
                try {
                    val result = withContext(Dispatchers.IO) {
                        importPickedPhoto(context, uri)
                    }
                    result
                        .onSuccess { localUri ->
                            val plan = capturePickerReturnedImagePlan(
                                importedUri = localUri.toString(),
                                purpose = completedPurpose,
                                currentReceivedUri = receivedImageUri,
                                pendingCameraUri = pendingCameraUri,
                            )
                            importedUri = if (plan is CaptureReturnedImagePlan.Append) {
                                null
                            } else {
                                localUri
                            }
                            applyReturnedImagePlan(
                                plan,
                                CaptureInputSource.PHOTO_PICKER,
                                completedPurpose,
                            )
                        }
                        .onFailure {
                            applyReturnedImagePlan(
                                capturePickerReturnedImagePlan(
                                    importedUri = null,
                                    purpose = completedPurpose,
                                    currentReceivedUri = receivedImageUri,
                                    pendingCameraUri = pendingCameraUri,
                                ),
                                CaptureInputSource.PHOTO_PICKER,
                                completedPurpose,
                            )
                        }
                } finally {
                    onPhotoImportInProgressChange(false)
                    if (!isActive) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            importedUri?.toString()?.let { deleteOwnedCapture(context, it) }
                        }
                    }
                }
            }
        }
    }
    return CaptureAcquisitionLaunchers(takePicture, pickPhoto)
}
