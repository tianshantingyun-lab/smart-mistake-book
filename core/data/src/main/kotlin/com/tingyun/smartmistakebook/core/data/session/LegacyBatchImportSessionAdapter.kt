package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.database.BatchImportJobRecord
import com.tingyun.smartmistakebook.core.database.BatchImportPageRecord
import com.tingyun.smartmistakebook.core.database.CreateBatchImportJobCommand
import com.tingyun.smartmistakebook.core.database.LegacyBatchSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.RecordBatchImportBoundarySessionResolutionCommand
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Transitional owner of legacy batch-import coordination.
 *
 * Only page progress and draft-session references cross this boundary; committed student
 * questions are never read or written here.
 */
internal class LegacyBatchImportSessionAdapter(
    private val boundScope: SessionScope,
    private val legacy: LegacyBatchSessionDatabasePort,
) : BatchImportSessionPort {
    override fun observe(scope: SessionScope): Flow<List<BatchImportSessionSnapshot>> {
        scope.requireBoundTo(boundScope)
        return legacy.observeBatchImportJobs().map { records ->
            records.map { it.toSession(boundScope) }
        }
    }

    override suspend fun read(
        query: BatchImportSessionReadQuery,
    ): BatchImportSessionSnapshot? {
        query.scope.requireBoundTo(boundScope)
        return legacy.readBatchImportJob(query.jobId)?.toSession(boundScope)
    }

    override suspend fun create(
        command: CreateBatchImportSessionCommand,
    ): BatchImportSessionMutationResult {
        command.scope.requireBoundTo(boundScope)
        val existing = legacy.readBatchImportJob(command.jobId)
        if (existing != null) {
            val samePayload =
                existing.requestId == command.operation.requestId &&
                    existing.requestFingerprint == command.operation.payloadFingerprint &&
                    existing.pages.map(BatchImportPageRecord::sourceUri) == command.sourceUris
            return result(
                operation = command.operation,
                disposition =
                    if (samePayload) {
                        SessionMutationDisposition.DUPLICATE
                    } else {
                        SessionMutationDisposition.REJECTED
                    },
                snapshot = existing.toSession(boundScope),
                occurredAtEpochMillis = command.occurredAtEpochMillis,
            )
        }
        val created =
            legacy.createBatchImportJob(
                CreateBatchImportJobCommand(
                    jobId = command.jobId,
                    requestId = command.operation.requestId,
                    requestFingerprint = command.operation.payloadFingerprint,
                    sourceUris = command.sourceUris,
                    occurredAtEpochMillis = command.occurredAtEpochMillis,
                ),
            ).toSession(boundScope)
        return result(
            operation = command.operation,
            disposition = SessionMutationDisposition.APPLIED,
            snapshot = created,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
    }

    override suspend fun mutate(
        command: BatchImportSessionMutation,
    ): BatchImportSessionMutationResult {
        command.requireValid(boundScope)
        val beforeRecord =
            legacy.readBatchImportJob(command.jobId)
                ?: return result(
                    operation = command.operation,
                    disposition = SessionMutationDisposition.NOT_FOUND,
                    snapshot = null,
                    occurredAtEpochMillis = command.occurredAtEpochMillis,
                )
        val before = beforeRecord.toSession(boundScope)
        if (before.version != command.expectedVersion) {
            return result(
                operation = command.operation,
                disposition = SessionMutationDisposition.RELOAD_REQUIRED,
                snapshot = before,
                occurredAtEpochMillis = command.occurredAtEpochMillis,
            )
        }
        if (
            command is BatchImportSessionMutation.ResolveBoundary &&
            before.pages.getOrNull(command.pageIndex)?.boundaryClaimedAtEpochMillis !=
            command.boundaryClaimedAtEpochMillis
        ) {
            return result(
                operation = command.operation,
                disposition = SessionMutationDisposition.RELOAD_REQUIRED,
                snapshot = before,
                occurredAtEpochMillis = command.occurredAtEpochMillis,
            )
        }

        var claimedPage: BatchImportPageSessionSnapshot? = null
        val applied =
            when (command) {
                is BatchImportSessionMutation.SetStatus ->
                    if (before.status == command.nextStatus) {
                        false
                    } else {
                        legacy.updateBatchImportJobStatus(
                            jobId = command.jobId,
                            expectedStatus = before.status.name,
                            nextStatus = command.nextStatus.name,
                            occurredAtEpochMillis = command.occurredAtEpochMillis,
                        )
                    }

                is BatchImportSessionMutation.RequeueInterruptedPages ->
                    legacy.requeueInterruptedBatchImportPages(
                        command.jobId,
                        command.occurredAtEpochMillis,
                    ) > 0

                is BatchImportSessionMutation.ClaimNextPage -> {
                    claimedPage =
                        legacy.claimNextBatchImportPage(
                            command.jobId,
                            command.occurredAtEpochMillis,
                        )?.toSession()
                    claimedPage != null
                }

                is BatchImportSessionMutation.CompletePage ->
                    legacy.completeBatchImportPage(
                        jobId = command.jobId,
                        pageIndex = command.pageIndex,
                        draftId = command.draftSessionId,
                        occurredAtEpochMillis = command.occurredAtEpochMillis,
                    )

                is BatchImportSessionMutation.FailPage ->
                    legacy.failBatchImportPage(
                        jobId = command.jobId,
                        pageIndex = command.pageIndex,
                        failureCode = command.failureCode,
                        occurredAtEpochMillis = command.occurredAtEpochMillis,
                    )

                is BatchImportSessionMutation.RetryPage ->
                    legacy.retryBatchImportPage(
                        command.jobId,
                        command.pageIndex,
                        command.occurredAtEpochMillis,
                    )

                is BatchImportSessionMutation.SkipPage ->
                    legacy.skipBatchImportPage(
                        command.jobId,
                        command.pageIndex,
                        command.occurredAtEpochMillis,
                    )

                is BatchImportSessionMutation.RequeueInterruptedBoundaries ->
                    legacy.requeueInterruptedBatchImportBoundaries(
                        command.jobId,
                        command.occurredAtEpochMillis,
                    ) > 0

                is BatchImportSessionMutation.ClaimBoundary ->
                    legacy.claimBatchImportBoundary(
                        command.jobId,
                        command.pageIndex,
                        command.occurredAtEpochMillis,
                    )

                is BatchImportSessionMutation.ResolveBoundary -> {
                    legacy.recordBatchImportBoundarySessionResolution(
                        RecordBatchImportBoundarySessionResolutionCommand(
                            jobId = command.jobId,
                            pageIndex = command.pageIndex,
                            primaryDraftSessionId = command.primaryDraftSessionId,
                            followingDraftSessionId = command.followingDraftSessionId,
                            resolution = command.resolution.name,
                            boundaryClaimedAtEpochMillis =
                                command.boundaryClaimedAtEpochMillis,
                            captureMergeReceiptRef = command.captureMergeReceiptRef,
                            occurredAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    )
                    true
                }

                is BatchImportSessionMutation.FailBoundary ->
                    legacy.failBatchImportBoundary(
                        command.jobId,
                        command.pageIndex,
                        command.occurredAtEpochMillis,
                    )

                is BatchImportSessionMutation.FinishIfSettled ->
                    legacy.finishBatchImportIfSettled(
                        command.jobId,
                        command.occurredAtEpochMillis,
                    )
            }
        val after = legacy.readBatchImportJob(command.jobId)?.toSession(boundScope)
        return result(
            operation = command.operation,
            disposition =
                if (applied) {
                    SessionMutationDisposition.APPLIED
                } else if (after == before || command.isAlreadySatisfiedBy(after)) {
                    SessionMutationDisposition.DUPLICATE
                } else {
                    SessionMutationDisposition.RELOAD_REQUIRED
                },
            snapshot = after,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
            claimedPage = claimedPage,
        )
    }

    override suspend fun hasRetainedSourceUri(
        scope: SessionScope,
        sourceUri: String,
    ): Boolean {
        scope.requireBoundTo(boundScope)
        require(sourceUri.isNotBlank()) { "Batch source URI must not be blank" }
        return legacy.hasRetainedBatchImportSourceUri(sourceUri)
    }
}

private fun BatchImportSessionMutation.requireValid(boundScope: SessionScope) {
    scope.requireBoundTo(boundScope)
    jobId.requireSessionIdentifier("Batch import job id")
    require(occurredAtEpochMillis >= 0) { "Batch import mutation time must not be negative" }
    when (this) {
        is BatchImportSessionMutation.CompletePage -> {
            require(pageIndex >= 0) { "Batch page index must not be negative" }
            draftSessionId.requireSessionIdentifier("Batch result draft session id")
        }

        is BatchImportSessionMutation.FailPage -> {
            require(pageIndex >= 0) { "Batch page index must not be negative" }
            require(failureCode.isNotBlank()) { "Batch failure code must not be blank" }
        }

        is BatchImportSessionMutation.RetryPage ->
            require(pageIndex >= 0) { "Batch page index must not be negative" }

        is BatchImportSessionMutation.SkipPage ->
            require(pageIndex >= 0) { "Batch page index must not be negative" }

        is BatchImportSessionMutation.ClaimBoundary ->
            require(pageIndex >= 0) { "Batch boundary index must not be negative" }

        is BatchImportSessionMutation.ResolveBoundary -> {
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
        }

        is BatchImportSessionMutation.FailBoundary ->
            require(pageIndex >= 0) { "Batch boundary index must not be negative" }

        else -> Unit
    }
}

private fun BatchImportSessionMutation.isAlreadySatisfiedBy(
    snapshot: BatchImportSessionSnapshot?,
): Boolean {
    snapshot ?: return false
    return when (this) {
        is BatchImportSessionMutation.SetStatus -> snapshot.status == nextStatus
        is BatchImportSessionMutation.CompletePage ->
            snapshot.pages.getOrNull(pageIndex)?.let {
                it.status == BatchImportPageSessionStatus.READY &&
                    it.resultDraftSessionId == draftSessionId
            } == true
        is BatchImportSessionMutation.FailPage ->
            snapshot.pages.getOrNull(pageIndex)?.status == BatchImportPageSessionStatus.FAILED
        is BatchImportSessionMutation.RetryPage ->
            snapshot.pages.getOrNull(pageIndex)?.status == BatchImportPageSessionStatus.QUEUED
        is BatchImportSessionMutation.SkipPage ->
            snapshot.pages.getOrNull(pageIndex)?.status == BatchImportPageSessionStatus.SKIPPED
        is BatchImportSessionMutation.ClaimBoundary ->
            snapshot.pages.getOrNull(pageIndex)?.boundaryAfter ==
                BatchImportBoundarySessionStatus.CHECKING
        is BatchImportSessionMutation.ResolveBoundary ->
            snapshot.pages.getOrNull(pageIndex)?.boundaryAfter == resolution
        is BatchImportSessionMutation.FailBoundary ->
            snapshot.pages.getOrNull(pageIndex)?.boundaryAfter ==
                BatchImportBoundarySessionStatus.FAILED
        is BatchImportSessionMutation.FinishIfSettled ->
            snapshot.status == BatchImportSessionStatus.COMPLETED
        is BatchImportSessionMutation.RequeueInterruptedPages,
        is BatchImportSessionMutation.ClaimNextPage,
        is BatchImportSessionMutation.RequeueInterruptedBoundaries,
        -> false
    }
}

private fun result(
    operation: SessionOperationIdentity,
    disposition: SessionMutationDisposition,
    snapshot: BatchImportSessionSnapshot?,
    occurredAtEpochMillis: Long,
    claimedPage: BatchImportPageSessionSnapshot? = null,
) = BatchImportSessionMutationResult(
    receipt =
        SessionMutationReceipt(
            operation = operation,
            disposition = disposition,
            currentVersion = snapshot?.version,
            recordedAtEpochMillis = occurredAtEpochMillis,
        ),
    snapshot = snapshot,
    claimedPage = claimedPage,
)

private fun BatchImportJobRecord.toSession(scope: SessionScope): BatchImportSessionSnapshot {
    val fingerprint =
        CanonicalSha256("batch-import-session-state-v1")
            .field("jobId", jobId)
            .field("requestId", requestId)
            .field("requestFingerprint", requestFingerprint)
            .field("status", status)
            .field("pageCount", pages.size)
            .apply {
                pages.forEach { page ->
                    field("pageIndex", page.pageIndex)
                    field("sourceUri", page.sourceUri)
                    field("pageStatus", page.status)
                    nullableField("resultDraftSessionId", page.resultDraftId)
                    nullableField("failureCode", page.failureCode)
                    field("attemptCount", page.attemptCount)
                    field("boundaryAfterStatus", page.boundaryAfterStatus)
                    nullableField(
                        "boundaryClaimedAtEpochMillis",
                        page.boundaryClaimedAtEpochMillis?.toString(),
                    )
                    field("pageUpdatedAtEpochMillis", page.updatedAtEpochMillis)
                }
            }.finish()
    return BatchImportSessionSnapshot(
        scope = scope,
        jobId = jobId,
        requestId = requestId,
        requestFingerprint = requestFingerprint,
        version = SessionVersion(updatedAtEpochMillis, fingerprint),
        status = BatchImportSessionStatus.valueOf(status),
        pages = pages.map(BatchImportPageRecord::toSession),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private fun BatchImportPageRecord.toSession() =
    BatchImportPageSessionSnapshot(
        pageIndex = pageIndex,
        sourceUri = sourceUri,
        status = BatchImportPageSessionStatus.valueOf(status),
        resultDraftSessionId = resultDraftId,
        failureCode = failureCode,
        attemptCount = attemptCount,
        boundaryAfter = BatchImportBoundarySessionStatus.valueOf(boundaryAfterStatus),
        boundaryClaimedAtEpochMillis = boundaryClaimedAtEpochMillis,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
