package com.tingyun.smartmistakebook.feature.capture

/**
 * Why an image is being acquired: a fresh capture, a replacement of the draft's
 * source set, or an extra page appended to it. Decides which workflow command
 * the returned image runs (`captureResultAction`, `nextCaptureTaskRetry`) and
 * therefore which draft revision the result is committed against.
 */
enum class CaptureAcquisitionPurpose {
    NEW_CAPTURE,
    REPLACE_DRAFT,
    APPEND_DRAFT,
}
