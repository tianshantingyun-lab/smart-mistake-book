package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.CaptureMergeSessionReceiptReference

/**
 * Session-only half of a batch boundary decision.
 *
 * Capture owns merging source bundles. This command can only record the resulting boundary and
 * remap batch page references after capture returned [captureMergeReceiptRef].
 */
data class RecordBatchImportBoundarySessionResolutionCommand(
    val jobId: String,
    val pageIndex: Int,
    val primaryDraftSessionId: String,
    val followingDraftSessionId: String,
    val resolution: String,
    val boundaryClaimedAtEpochMillis: Long,
    val captureMergeReceiptRef: String?,
    val occurredAtEpochMillis: Long,
) {
    init {
        requireOpaqueSessionValue(jobId, "Batch job id")
        require(pageIndex >= 0) { "Batch boundary page index must not be negative" }
        requireOpaqueSessionValue(primaryDraftSessionId, "Primary draft session id")
        requireOpaqueSessionValue(followingDraftSessionId, "Following draft session id")
        require(boundaryClaimedAtEpochMillis >= 0) {
            "Batch boundary claim time must not be negative"
        }
        require(
            resolution == StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION ||
                resolution == StudyDbValue.BatchImportBoundaryStatus.NEXT_QUESTION ||
                resolution == StudyDbValue.BatchImportBoundaryStatus.KEPT_SEPARATE,
        ) { "Batch boundary resolution must be terminal" }
        captureMergeReceiptRef?.let {
            requireOpaqueSessionValue(it, "Capture merge receipt reference")
        }
        require(
            resolution == StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION ||
                captureMergeReceiptRef == null,
        ) { "Only a same-question boundary may carry a capture merge receipt" }
        require(
            resolution != StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION ||
                primaryDraftSessionId == followingDraftSessionId ||
                captureMergeReceiptRef != null,
        ) { "Distinct same-question drafts require a capture merge receipt" }
        if (
            resolution == StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION &&
            primaryDraftSessionId != followingDraftSessionId
        ) {
            require(
                captureMergeReceiptRef ==
                    CaptureMergeSessionReceiptReference.forBatchBoundary(
                        jobId = jobId,
                        pageIndex = pageIndex,
                        primaryDraftSessionId = primaryDraftSessionId,
                        followingDraftSessionId = followingDraftSessionId,
                    ),
            ) { "Capture merge receipt does not match this batch boundary" }
        }
        require(occurredAtEpochMillis >= 0) {
            "Batch boundary resolution time must not be negative"
        }
        require(occurredAtEpochMillis >= boundaryClaimedAtEpochMillis) {
            "Batch boundary resolution cannot precede its claim"
        }
    }
}

/**
 * Narrow migration/session bridge used while batch coordination still lives in the legacy store.
 *
 * It deliberately refuses non-Room implementations instead of falling back to the legacy
 * combined boundary transaction, because that transaction also mutates capture drafts.
 */
suspend fun StudyDatabasePort.recordBatchImportBoundarySessionResolution(
    command: RecordBatchImportBoundarySessionResolutionCommand,
): BatchImportJobRecord {
    val room =
        this as? RoomStudyDatabase
            ?: throw UnsupportedOperationException(
                "Session-only batch boundary resolution requires the audited Room bridge",
            )
    return RoomBatchImportStore(room.database).recordBoundaryResolution(command)
}

private fun requireOpaqueSessionValue(
    value: String,
    label: String,
) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_SESSION_VALUE_CHARS &&
            value.none(Char::isISOControl),
    ) { "$label must be a bounded opaque value" }
}

private const val MAX_SESSION_VALUE_CHARS = 256
