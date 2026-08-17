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
    private val sink: CaptureWorkspaceSink,
) {
    suspend fun saveNow(
        state: CaptureWorkspaceUiState,
        occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ): CaptureDraftWorkspaceIdentity? {
        sink.setSaving(true)
        return try {
            withContext(NonCancellable) {
                val applied = applyWorkspaceWriteResult(
                    writer.save(
                        state = state,
                        currentIdentity = sink.currentIdentity,
                        occurredAtEpochMillis = occurredAtEpochMillis,
                    ),
                )
                sink.applySave(applied)
                applied.identity
            }
        } finally {
            sink.setSaving(false)
        }
    }

    suspend fun flushNow(): Boolean {
        val current = sink.currentWorkspace() ?: return true
        return saveNow(current) != null
    }

    fun afterFlush(action: () -> Unit) {
        if (!captureWorkspaceFlushCanStart(sink.saving(), sink.workflowInProgress())) return
        scope.launch {
            if (flushNow()) action()
        }
    }

    fun requestBack(onBack: () -> Unit) {
        when (captureWorkspaceLeaveDecision(hasWorkspace = sink.currentWorkspace() != null)) {
            CaptureWorkspaceLeaveDecision.LEAVE_NOW -> onBack()
            CaptureWorkspaceLeaveDecision.FLUSH_THEN_LEAVE -> afterFlush(onBack)
        }
    }
}

internal class CaptureWorkspaceSink(
    val currentWorkspace: () -> CaptureWorkspaceUiState?,
    val currentIdentity: () -> CaptureDraftWorkspaceIdentity?,
    val saving: () -> Boolean,
    val workflowInProgress: () -> Boolean,
    val setSaving: (Boolean) -> Unit,
    val applySave: (CaptureWorkspaceSaveApplication) -> Unit,
)
