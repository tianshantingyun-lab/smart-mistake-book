package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CaptureWorkspaceSaveApplication(
    val identity: CaptureDraftWorkspaceIdentity?,
    val updatedAtEpochMillis: Long?,
    val error: String?,
)

internal fun applyWorkspaceWriteResult(
    result: CaptureWorkspaceWriteResult,
): CaptureWorkspaceSaveApplication = when (result) {
    is CaptureWorkspaceWriteResult.Saved -> CaptureWorkspaceSaveApplication(
        identity = result.snapshot.identity,
        updatedAtEpochMillis = result.snapshot.updatedAtEpochMillis,
        error = null,
    )
    is CaptureWorkspaceWriteResult.Failed -> CaptureWorkspaceSaveApplication(
        identity = null,
        updatedAtEpochMillis = null,
        error = CAPTURE_WORKSPACE_SAVE_ERROR,
    )
}

internal class CaptureWorkspaceCommands(
    private val writer: CaptureWorkspaceWriter,
    private val scope: CoroutineScope,
    private val state: CaptureScreenState,
) {
    suspend fun saveNow(
        workspace: CaptureWorkspaceUiState,
        occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ): CaptureDraftWorkspaceIdentity? {
        state.workspaceSaving = true
        return try {
            withContext(NonCancellable) {
                val applied = applyWorkspaceWriteResult(
                    writer.save(
                        state = workspace,
                        currentIdentity = { state.workspaceIdentity },
                        occurredAtEpochMillis = occurredAtEpochMillis,
                    ),
                )
                if (applied.identity != null) {
                    state.workspaceIdentity = applied.identity
                    state.workspaceUpdatedAtEpochMillis =
                        applied.updatedAtEpochMillis ?: state.workspaceUpdatedAtEpochMillis
                }
                state.workspaceSaveError = applied.error
                applied.identity
            }
        } finally {
            state.workspaceSaving = false
        }
    }

    suspend fun flushNow(): Boolean {
        val current = state.workspaceState ?: return true
        return saveNow(current) != null
    }

    fun afterFlush(action: () -> Unit) {
        if (!captureWorkspaceFlushCanStart(state.workspaceSaving, state.workflowInProgress)) return
        scope.launch {
            if (flushNow()) action()
        }
    }

    fun requestBack(onBack: () -> Unit) {
        when (captureWorkspaceLeaveDecision(hasWorkspace = state.workspaceState != null)) {
            CaptureWorkspaceLeaveDecision.LEAVE_NOW -> onBack()
            CaptureWorkspaceLeaveDecision.FLUSH_THEN_LEAVE -> afterFlush(onBack)
        }
    }
}
