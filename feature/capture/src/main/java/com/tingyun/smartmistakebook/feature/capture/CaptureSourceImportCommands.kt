package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val CAPTURE_TOO_MANY_PAGES_ERROR = "单道题最多保存 $MAX_CAPTURE_SOURCE_PAGES 页；请先完成当前题目，再单独录入下一题。"
internal const val CAPTURE_WORKSPACE_NOT_READY_ERROR = "题面还在恢复中，请稍后重试。"

internal data class CaptureImportRequestIdentity(
    val requestId: String,
    val occurredAtEpochMillis: Long,
    val persistRequestId: Boolean,
    val persistOccurredAt: Boolean,
)

internal fun captureImportRequestIdentity(
    existingRequestId: String?,
    existingOccurredAtEpochMillis: Long?,
    nowEpochMillis: Long,
    newRequestId: () -> String,
): CaptureImportRequestIdentity {
    val requestId = existingRequestId ?: newRequestId()
    val occurredAt = existingOccurredAtEpochMillis ?: nowEpochMillis
    return CaptureImportRequestIdentity(
        requestId = requestId,
        occurredAtEpochMillis = occurredAt,
        persistRequestId = existingRequestId == null,
        persistOccurredAt = existingOccurredAtEpochMillis == null,
    )
}

internal fun captureWorkflowCanStart(workflowInProgress: Boolean): Boolean = !workflowInProgress

internal sealed interface CaptureCommitDecision {
    data object Blocked : CaptureCommitDecision
    data object WorkspaceNotReady : CaptureCommitDecision
    data class Prepare(
        val workspace: CaptureWorkspaceUiState,
    ) : CaptureCommitDecision
}

internal fun captureCommitDecision(
    entryGateOpen: Boolean,
    draftId: String?,
    workspace: CaptureWorkspaceUiState?,
    workflowInProgress: Boolean,
): CaptureCommitDecision {
    if (!entryGateOpen || draftId == null || workflowInProgress) return CaptureCommitDecision.Blocked
    if (workspace == null) return CaptureCommitDecision.WorkspaceNotReady
    return CaptureCommitDecision.Prepare(workspace)
}

internal class CaptureSourceImportCommands(
    private val scope: CoroutineScope,
    private val sink: CaptureSourceImportSink,
) {
    fun persistNewSource() {
        val uri = sink.receivedImageUri() ?: return
        val source = sink.receivedInputSource() ?: return
        if (!captureWorkflowCanStart(sink.workflowInProgress())) return
        val identity = captureImportRequestIdentity(
            existingRequestId = sink.importRequestId(),
            existingOccurredAtEpochMillis = sink.importOccurredAtEpochMillis(),
            nowEpochMillis = System.currentTimeMillis(),
            newRequestId = { UUID.randomUUID().toString() },
        )
        sink.rememberImportIdentity(identity)
        sink.setWorkflowInProgress(true)
        sink.acceptSource(
            uri,
            source,
            CaptureAcquisitionPurpose.NEW_CAPTURE,
            identity.requestId,
            identity.occurredAtEpochMillis,
            null,
        )
    }

    fun persistAdditionalPage(localUri: String, source: CaptureInputSource) {
        when (
            captureAppendPageDecision(
                draftId = sink.draftId(),
                revisionNumber = sink.revisionNumber(),
                pageCount = sink.pageCount(),
                workflowInProgress = sink.workflowInProgress(),
            )
        ) {
            CaptureAppendPageDecision.MissingDraft -> return
            CaptureAppendPageDecision.TooManyPages -> {
                sink.setCaptureError(CAPTURE_TOO_MANY_PAGES_ERROR)
                sink.deleteOwnedUri(localUri)
                return
            }
            CaptureAppendPageDecision.Busy -> return
            CaptureAppendPageDecision.Append -> Unit
        }
        sink.setPendingAppendOwnedUri(localUri)
        sink.setWorkflowInProgress(true)
        sink.acceptSource(
            localUri,
            source,
            CaptureAcquisitionPurpose.APPEND_DRAFT,
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            sink.pageCount(),
        )
    }

    fun replaceDraftWithCandidate() {
        val candidateUri = sink.replacementCandidateUri() ?: return
        val source = sink.replacementInputSource() ?: return
        val requestId = sink.replacementRequestId() ?: return
        val occurredAt = sink.replacementOccurredAtEpochMillis() ?: return
        if (!captureWorkflowCanStart(sink.workflowInProgress())) return
        sink.setWorkflowInProgress(true)
        sink.clearReplacementError()
        sink.acceptSource(
            candidateUri,
            source,
            CaptureAcquisitionPurpose.REPLACE_DRAFT,
            requestId,
            occurredAt,
            null,
        )
    }

    fun keepCurrentDraftAfterReplacementFailure() {
        sink.deleteOwnedUri(sink.replacementCandidateUri())
        sink.clearReplacementState()
    }

    fun commitCorrection() {
        when (
            val decision = captureCommitDecision(
                entryGateOpen = sink.entryGateOpen(),
                draftId = sink.draftId(),
                workspace = sink.workspace(),
                workflowInProgress = sink.workflowInProgress(),
            )
        ) {
            CaptureCommitDecision.Blocked -> return
            CaptureCommitDecision.WorkspaceNotReady -> {
                sink.setWorkspaceSaveError(CAPTURE_WORKSPACE_NOT_READY_ERROR)
                return
            }
            is CaptureCommitDecision.Prepare -> {
                val finalizedWorkspace = prepareCaptureCommitAttempt(
                    workspace = decision.workspace,
                    workspaceUpdatedAtEpochMillis = sink.workspaceUpdatedAtEpochMillis(),
                    requestIdFactory = { UUID.randomUUID().toString() },
                    nowEpochMillis = System::currentTimeMillis,
                )
                val finalOccurredAtEpochMillis = checkNotNull(
                    finalizedWorkspace.finalConfirmationRequest,
                ).occurredAtEpochMillis
                if (finalizedWorkspace != decision.workspace) {
                    sink.replaceWorkspace(finalizedWorkspace)
                }
                sink.setWorkflowInProgress(true)
                scope.launch {
                    val persistedFinalIdentity = sink.workspaceIdentity()
                        ?.takeIf { it.matchesPersistedFinalState(finalizedWorkspace) }
                    val exactWorkspaceIdentity = persistedFinalIdentity
                        ?: sink.saveWorkspaceNow(
                            finalizedWorkspace,
                            finalOccurredAtEpochMillis,
                        )
                    if (exactWorkspaceIdentity == null) {
                        sink.setWorkflowInProgress(false)
                        return@launch
                    }
                    sink.confirmWorkspace(exactWorkspaceIdentity)
                }
            }
        }
    }
}

internal class CaptureSourceImportSink(
    val receivedImageUri: () -> String?,
    val receivedInputSource: () -> CaptureInputSource?,
    val importRequestId: () -> String?,
    val importOccurredAtEpochMillis: () -> Long?,
    val rememberImportIdentity: (CaptureImportRequestIdentity) -> Unit,
    val draftId: () -> String?,
    val revisionNumber: () -> Int?,
    val pageCount: () -> Int,
    val workflowInProgress: () -> Boolean,
    val setWorkflowInProgress: (Boolean) -> Unit,
    val setCaptureError: (String) -> Unit,
    val deleteOwnedUri: (String?) -> Unit,
    val setPendingAppendOwnedUri: (String?) -> Unit,
    val replacementCandidateUri: () -> String?,
    val replacementInputSource: () -> CaptureInputSource?,
    val replacementRequestId: () -> String?,
    val replacementOccurredAtEpochMillis: () -> Long?,
    val clearReplacementError: () -> Unit,
    val clearReplacementState: () -> Unit,
    val entryGateOpen: () -> Boolean,
    val workspace: () -> CaptureWorkspaceUiState?,
    val workspaceUpdatedAtEpochMillis: () -> Long,
    val workspaceIdentity: () -> CaptureDraftWorkspaceIdentity?,
    val setWorkspaceSaveError: (String) -> Unit,
    val replaceWorkspace: (CaptureWorkspaceUiState) -> Unit,
    val saveWorkspaceNow: suspend (state: CaptureWorkspaceUiState, occurredAtEpochMillis: Long) -> CaptureDraftWorkspaceIdentity?,
    val confirmWorkspace: (CaptureDraftWorkspaceIdentity) -> Unit,
    val acceptSource: (
        uri: String,
        source: CaptureInputSource,
        purpose: CaptureAcquisitionPurpose,
        requestId: String,
        occurredAtEpochMillis: Long,
        expectedPageCount: Int?,
    ) -> Unit,
)
