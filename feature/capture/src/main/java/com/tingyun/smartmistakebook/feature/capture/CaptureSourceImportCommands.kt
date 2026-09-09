package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
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

/**
 * New-source ingestion and commit orchestration. Reads and writes
 * [CaptureScreenState] directly; the workspace flush, confirmation and the
 * ViewModel's acceptSource are injected so this class stays free of Android
 * and ViewModel types. Derived screen signals (entryGateOpen) are injected as
 * getters bound by the caller, preserving the original capture semantics.
 */
internal class CaptureSourceImportCommands(
    private val scope: CoroutineScope,
    private val state: CaptureScreenState,
    private val workspaceCommands: CaptureWorkspaceCommands,
    private val entryGateOpen: () -> Boolean,
    private val onDeleteOwnedUri: (String?) -> Unit,
    private val onConfirmWorkspace: (CaptureDraftWorkspaceIdentity) -> Unit,
    private val onAcceptSource: (
        uri: String,
        source: CaptureInputSource,
        purpose: CaptureAcquisitionPurpose,
        requestId: String,
        occurredAtEpochMillis: Long,
        expectedPageCount: Int?,
    ) -> Unit,
) {
    fun persistNewSource() {
        val uri = state.receivedImageUri ?: return
        val source = state.receivedInputSource?.let(CaptureInputSource::valueOf) ?: return
        if (!captureWorkflowCanStart(state.workflowInProgress)) return
        val identity = captureImportRequestIdentity(
            existingRequestId = state.importRequestId,
            existingOccurredAtEpochMillis = state.importOccurredAtEpochMillis,
            nowEpochMillis = System.currentTimeMillis(),
            newRequestId = { UUID.randomUUID().toString() },
        )
        rememberImportIdentity(identity)
        state.workflowInProgress = true
        onAcceptSource(
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
                draftId = state.draftId,
                revisionNumber = state.draftRevisionNumber,
                pageCount = state.sourcePages.size,
                workflowInProgress = state.workflowInProgress,
            )
        ) {
            CaptureAppendPageDecision.MissingDraft -> return
            CaptureAppendPageDecision.TooManyPages -> {
                state.captureError = CAPTURE_TOO_MANY_PAGES_ERROR
                onDeleteOwnedUri(localUri)
                return
            }
            CaptureAppendPageDecision.Busy -> return
            CaptureAppendPageDecision.Append -> Unit
        }
        state.pendingAppendOwnedUri = localUri
        state.workflowInProgress = true
        onAcceptSource(
            localUri,
            source,
            CaptureAcquisitionPurpose.APPEND_DRAFT,
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            state.sourcePages.size,
        )
    }

    fun replaceDraftWithCandidate() {
        val candidateUri = state.replacementCandidateUri ?: return
        val source = state.replacementInputSourceName?.let(CaptureInputSource::valueOf) ?: return
        val requestId = state.replacementRequestId ?: return
        val occurredAt = state.replacementOccurredAtEpochMillis ?: return
        if (!captureWorkflowCanStart(state.workflowInProgress)) return
        state.workflowInProgress = true
        state.replacementError = null
        onAcceptSource(
            candidateUri,
            source,
            CaptureAcquisitionPurpose.REPLACE_DRAFT,
            requestId,
            occurredAt,
            null,
        )
    }

    fun keepCurrentDraftAfterReplacementFailure() {
        onDeleteOwnedUri(state.replacementCandidateUri)
        clearReplacementState()
    }

    fun commitCorrection() {
        when (
            val decision = captureCommitDecision(
                entryGateOpen = entryGateOpen(),
                draftId = state.draftId,
                workspace = state.workspaceState,
                workflowInProgress = state.workflowInProgress,
            )
        ) {
            CaptureCommitDecision.Blocked -> return
            CaptureCommitDecision.WorkspaceNotReady -> {
                state.workspaceSaveError = CAPTURE_WORKSPACE_NOT_READY_ERROR
                return
            }
            is CaptureCommitDecision.Prepare -> {
                val finalizedWorkspace = prepareCaptureCommitAttempt(
                    workspace = decision.workspace,
                    workspaceUpdatedAtEpochMillis = state.workspaceUpdatedAtEpochMillis,
                    requestIdFactory = { UUID.randomUUID().toString() },
                    nowEpochMillis = System::currentTimeMillis,
                )
                val finalOccurredAtEpochMillis = checkNotNull(
                    finalizedWorkspace.finalConfirmationRequest,
                ).occurredAtEpochMillis
                if (finalizedWorkspace != decision.workspace) {
                    replaceWorkspace(finalizedWorkspace)
                }
                state.workflowInProgress = true
                scope.launch {
                    val persistedFinalIdentity = state.workspaceIdentity
                        ?.takeIf { it.matchesPersistedFinalState(finalizedWorkspace) }
                    val exactWorkspaceIdentity = persistedFinalIdentity
                        ?: workspaceCommands.saveNow(
                            finalizedWorkspace,
                            finalOccurredAtEpochMillis,
                        )
                    if (exactWorkspaceIdentity == null) {
                        state.workflowInProgress = false
                        return@launch
                    }
                    onConfirmWorkspace(exactWorkspaceIdentity)
                }
            }
        }
    }

    private fun rememberImportIdentity(identity: CaptureImportRequestIdentity) {
        if (identity.persistRequestId) state.importRequestId = identity.requestId
        if (identity.persistOccurredAt) {
            state.importOccurredAtEpochMillis = identity.occurredAtEpochMillis
        }
    }

    private fun clearReplacementState() {
        state.replacementCandidateUri = null
        state.replacementInputSourceName = null
        state.replacementRequestId = null
        state.replacementOccurredAtEpochMillis = null
        state.acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
        state.replacementError = null
    }

    private fun replaceWorkspace(updated: CaptureWorkspaceUiState) {
        state.workspaceState = updated
        state.workspaceChangeVersion += 1
    }
}
