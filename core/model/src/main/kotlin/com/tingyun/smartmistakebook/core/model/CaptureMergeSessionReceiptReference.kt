package com.tingyun.smartmistakebook.core.model

/**
 * Stable handoff reference shared by capture and batch session owners.
 *
 * The reference is not evidence that a merge happened by itself. Capture returns it only after its
 * own compare-and-merge succeeds; batch accepts exactly this natural-key reference before remapping
 * its page pointers.
 */
object CaptureMergeSessionReceiptReference {
    fun forBatchBoundary(
        jobId: String,
        pageIndex: Int,
        primaryDraftSessionId: String,
        followingDraftSessionId: String,
    ): String {
        require(pageIndex >= 0) { "Batch boundary page index must not be negative" }
        return CanonicalSha256("capture-merge-session-receipt-reference-v1")
            .field("jobId", jobId)
            .field("pageIndex", pageIndex)
            .field("primaryDraftSessionId", primaryDraftSessionId)
            .field("followingDraftSessionId", followingDraftSessionId)
            .finish()
    }
}
