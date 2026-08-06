package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.model.CaptureMergeSessionReceiptReference
import kotlinx.coroutines.flow.Flow

internal enum class BatchImportSessionStatus {
    PROCESSING,
    PAUSED,
    COMPLETED,
}

internal enum class BatchImportPageSessionStatus {
    QUEUED,
    IMPORTING,
    READY,
    FAILED,
    SKIPPED,
}

internal enum class BatchImportBoundarySessionStatus {
    PENDING,
    CHECKING,
    SAME_QUESTION,
    NEXT_QUESTION,
    KEPT_SEPARATE,
    FAILED,
}

internal data class BatchImportPageSessionSnapshot(
    val pageIndex: Int,
    val sourceUri: String,
    val status: BatchImportPageSessionStatus,
    val resultDraftSessionId: String?,
    val failureCode: String?,
    val attemptCount: Int,
    val boundaryAfter: BatchImportBoundarySessionStatus,
    /** Persisted time of the latest successful boundary claim. */
    val boundaryClaimedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(pageIndex >= 0) { "Batch page index must not be negative" }
        require(sourceUri.isNotBlank()) { "Batch page source URI must not be blank" }
        resultDraftSessionId?.requireSessionIdentifier("Batch result draft session id")
        require(failureCode == null || failureCode.isNotBlank()) {
            "Batch page failure code must not be blank"
        }
        require(attemptCount >= 0) { "Batch page attempt count must not be negative" }
        require(
            boundaryAfter != BatchImportBoundarySessionStatus.CHECKING ||
                boundaryClaimedAtEpochMillis != null,
        ) { "An actively claimed batch boundary must expose its claim time" }
        require(
            boundaryAfter == BatchImportBoundarySessionStatus.CHECKING ||
                boundaryClaimedAtEpochMillis == null,
        ) { "Only an actively claimed batch boundary may retain a claim time" }
        require(boundaryClaimedAtEpochMillis == null || boundaryClaimedAtEpochMillis >= 0) {
            "Batch boundary claim time must not be negative"
        }
        require(createdAtEpochMillis >= 0 && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Batch page times are invalid"
        }
    }
}

internal data class BatchImportSessionSnapshot(
    val scope: SessionScope,
    val jobId: String,
    val requestId: String,
    val requestFingerprint: String,
    val version: SessionVersion,
    val status: BatchImportSessionStatus,
    val pages: List<BatchImportPageSessionSnapshot>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        jobId.requireSessionIdentifier("Batch import job id")
        requestId.requireSessionIdentifier("Batch import request id")
        requestFingerprint.requireSessionFingerprint("Batch import request fingerprint")
        require(pages.map { it.pageIndex } == pages.indices.toList()) {
            "Batch import pages must be contiguous and ordered"
        }
        require(createdAtEpochMillis >= 0 && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Batch import times are invalid"
        }
    }
}

internal data class CreateBatchImportSessionCommand(
    val scope: SessionScope,
    val operation: SessionOperationIdentity,
    val jobId: String,
    val sourceUris: List<String>,
    val occurredAtEpochMillis: Long,
) {
    init {
        jobId.requireSessionIdentifier("Batch import job id")
        require(sourceUris.isNotEmpty() && sourceUris.all(String::isNotBlank)) {
            "Batch import requires non-blank source URIs"
        }
        require(sourceUris.distinct().size == sourceUris.size) {
            "Batch import source URIs must be unique"
        }
        require(occurredAtEpochMillis >= 0) { "Batch import time must not be negative" }
    }
}

internal data class BatchImportSessionReadQuery(
    val scope: SessionScope,
    val jobId: String,
) {
    init {
        jobId.requireSessionIdentifier("Batch import job id")
    }
}

internal sealed interface BatchImportSessionMutation {
    val scope: SessionScope
    val operation: SessionOperationIdentity
    val jobId: String
    val expectedVersion: SessionVersion
    val occurredAtEpochMillis: Long

    data class SetStatus(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
        val nextStatus: BatchImportSessionStatus,
    ) : BatchImportSessionMutation

    data class RequeueInterruptedPages(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
    ) : BatchImportSessionMutation

    data class ClaimNextPage(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
    ) : BatchImportSessionMutation

    data class CompletePage(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
        val pageIndex: Int,
        val draftSessionId: String,
    ) : BatchImportSessionMutation

    data class FailPage(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
        val pageIndex: Int,
        val failureCode: String,
    ) : BatchImportSessionMutation

    data class RetryPage(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
        val pageIndex: Int,
    ) : BatchImportSessionMutation

    data class SkipPage(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
        val pageIndex: Int,
    ) : BatchImportSessionMutation

    data class RequeueInterruptedBoundaries(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
    ) : BatchImportSessionMutation

    data class ClaimBoundary(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
        val pageIndex: Int,
    ) : BatchImportSessionMutation

    data class ResolveBoundary(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
        val pageIndex: Int,
        val primaryDraftSessionId: String,
        val followingDraftSessionId: String,
        val resolution: BatchImportBoundarySessionStatus,
        /** Exact persisted claim time observed before capture work started. */
        val boundaryClaimedAtEpochMillis: Long,
        /**
         * Opaque acknowledgement returned by the capture owner after it merged distinct drafts.
         *
         * Batch coordination never performs that merge itself. The reference only proves that the
         * separate capture mutation finished before batch remaps its session references.
         */
        val captureMergeReceiptRef: String? = null,
    ) : BatchImportSessionMutation {
        init {
            require(pageIndex >= 0) { "Batch boundary index must not be negative" }
            primaryDraftSessionId.requireSessionIdentifier("Primary draft session id")
            followingDraftSessionId.requireSessionIdentifier("Following draft session id")
            captureMergeReceiptRef?.requireSessionIdentifier("Capture merge receipt reference")
            require(boundaryClaimedAtEpochMillis >= 0) {
                "Batch boundary claim time must not be negative"
            }
            require(occurredAtEpochMillis >= boundaryClaimedAtEpochMillis) {
                "Batch boundary resolution cannot precede its claim"
            }
            require(
                resolution == BatchImportBoundarySessionStatus.SAME_QUESTION ||
                    resolution == BatchImportBoundarySessionStatus.NEXT_QUESTION ||
                    resolution == BatchImportBoundarySessionStatus.KEPT_SEPARATE,
            ) { "Batch boundary resolution must be terminal" }
            require(
                resolution == BatchImportBoundarySessionStatus.SAME_QUESTION ||
                    captureMergeReceiptRef == null,
            ) { "Only a same-question boundary may carry a capture merge receipt" }
            require(
                resolution != BatchImportBoundarySessionStatus.SAME_QUESTION ||
                    primaryDraftSessionId == followingDraftSessionId ||
                    captureMergeReceiptRef != null,
            ) { "Distinct same-question drafts require a capture merge receipt" }
            if (
                resolution == BatchImportBoundarySessionStatus.SAME_QUESTION &&
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
        }
    }

    data class FailBoundary(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
        val pageIndex: Int,
    ) : BatchImportSessionMutation

    data class FinishIfSettled(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val jobId: String,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
    ) : BatchImportSessionMutation
}

internal data class BatchImportSessionMutationResult(
    val receipt: SessionMutationReceipt,
    val snapshot: BatchImportSessionSnapshot?,
    val claimedPage: BatchImportPageSessionSnapshot? = null,
) {
    init {
        require(
            claimedPage == null ||
                receipt.disposition == SessionMutationDisposition.APPLIED,
        ) { "A claimed batch page requires an applied mutation" }
    }
}

internal interface BatchImportSessionPort {
    fun observe(scope: SessionScope): Flow<List<BatchImportSessionSnapshot>>

    suspend fun read(query: BatchImportSessionReadQuery): BatchImportSessionSnapshot?

    suspend fun create(
        command: CreateBatchImportSessionCommand,
    ): BatchImportSessionMutationResult

    suspend fun mutate(
        command: BatchImportSessionMutation,
    ): BatchImportSessionMutationResult

    suspend fun hasRetainedSourceUri(
        scope: SessionScope,
        sourceUri: String,
    ): Boolean
}
