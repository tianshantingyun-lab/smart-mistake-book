package com.tingyun.smartmistakebook.feature.capture

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureFailureCode
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowAction
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowState
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowStateMachine
import com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.ActionType
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.AppFailure
import com.tingyun.smartmistakebook.core.model.AppFailureCode
import com.tingyun.smartmistakebook.core.model.Retryability
import com.tingyun.smartmistakebook.core.model.appFailure
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the durable capture workflow: resume, source import, replacement, and commit are all
 * translated into [CaptureWorkflowAction]s so stale results fail closed before they can reach UI.
 */
class CaptureViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: CaptureWorkflowRepository,
    private val modelTasks: ModelTaskRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val resumeDraftId: String? = savedStateHandle
        .get<String>(KEY_RESUME_DRAFT_ID)
        ?.takeIf { it.isNotBlank() }

    private val workflowState = MutableStateFlow(CaptureWorkflowState())
    private val resumeState = MutableStateFlow(
        if (resumeDraftId == null) {
            CaptureResumeLoadState.NOT_REQUESTED
        } else {
            CaptureResumeLoadState.LOADING
        },
    )
    private val providerCapabilities = MutableStateFlow<ProviderCapabilitySnapshot?>(null)
    private val userError = MutableStateFlow<AppFailure?>(null)
    private val pendingTutorSessionId = MutableStateFlow<String?>(null)
    private val importedDraftEvent = MutableStateFlow<CaptureDraftImportedEvent?>(null)
    private val confirmedTutorSession = MutableStateFlow<ConfirmedTutorSession?>(null)
    private var pendingSourceCommand: PendingSourceCommand? = null
    private var pendingConfirmCommand: PendingConfirmCommand? = null

    val uiState: StateFlow<CaptureWorkflowUiState> = combine(
        workflowState,
        resumeState,
        providerCapabilities,
        userError,
        importedDraftEvent,
    ) { workflow, resume, provider, message, imported ->
        CaptureWorkflowUiState(
            workflow = workflow,
            resumeState = resume,
            providerCapabilities = provider,
            userError = message,
            importedDraft = imported,
        )
    }.combine(pendingTutorSessionId) { state, sessionId ->
        state.copy(pendingTutorSessionId = sessionId)
    }.combine(confirmedTutorSession) { state, confirmed ->
        state.copy(confirmedTutorSession = confirmed)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CaptureWorkflowUiState(resumeState = resumeState.value),
    )

    init {
        resumeDraftId?.let { draftId ->
            viewModelScope.launch { restore(draftId) }
        }
        refreshProviderCapabilities()
    }

    fun acceptSource(
        uri: String,
        source: CaptureInputSource,
        origin: CaptureEntryOrigin,
        purpose: CaptureAcquisitionPurpose,
        requestId: String = UUID.randomUUID().toString(),
        occurredAtEpochMillis: Long = System.currentTimeMillis(),
        expectedPageCount: Int? = null,
    ) {
        if (uri.isBlank()) return
        if (purpose == CaptureAcquisitionPurpose.NEW_CAPTURE &&
            workflowState.value.phase in BUSY_PHASES
        ) {
            return
        }
        if (purpose != CaptureAcquisitionPurpose.NEW_CAPTURE &&
            workflowState.value.phase !in ALLOW_APPEND_REPLACE_PHASES
        ) {
            return
        }
        if (purpose != CaptureAcquisitionPurpose.NEW_CAPTURE &&
            (workflowState.value.draftId == null ||
                workflowState.value.basisRevisionNumber == null)
        ) {
            return
        }
        pendingConfirmCommand = null
        val command = PendingSourceCommand(
            uri = uri,
            source = source,
            origin = origin,
            purpose = purpose,
            requestId = requestId,
            occurredAtEpochMillis = occurredAtEpochMillis,
            expectedDraftId = workflowState.value.draftId.takeUnless { purpose == CaptureAcquisitionPurpose.NEW_CAPTURE },
            expectedRevisionNumber = workflowState.value.basisRevisionNumber
                .takeUnless { purpose == CaptureAcquisitionPurpose.NEW_CAPTURE },
            expectedPageCount = expectedPageCount,
        )
        pendingSourceCommand = command
        userError.value = null
        runPendingSource(command)
    }

    fun retryFailedWorkflow() {
        if (workflowState.value.phase != CaptureWorkflowPhase.FAILED) return
        pendingConfirmCommand?.let { pending ->
            confirm(pending.workspaceIdentity, pending.origin)
            return
        }
        val command = pendingSourceCommand ?: return
        userError.value = null
        runPendingSource(command)
    }

    fun confirm(workspaceIdentity: CaptureDraftWorkspaceIdentity, origin: CaptureEntryOrigin) {
        val current = workflowState.value
        val draftId = current.draftId ?: return
        val basisRevisionNumber = current.basisRevisionNumber ?: return
        val requestId = "capture-commit:${UUID.randomUUID()}"
        if (current.phase == CaptureWorkflowPhase.SAVED) return
        pendingConfirmCommand = PendingConfirmCommand(
            workspaceIdentity = workspaceIdentity,
            origin = origin,
        )
        workflowState.value = CaptureWorkflowStateMachine.reduce(
            current,
            CaptureWorkflowAction.CommitStarted(
                draftId = draftId,
                basisRevisionNumber = basisRevisionNumber,
                requestId = requestId,
            ),
        )
        viewModelScope.launch {
            try {
                val request = ConfirmCapturedProblemRequest(
                    draftId = draftId,
                    workspaceIdentity = workspaceIdentity,
                )
                val savedEntryId = when (origin) {
                    CaptureEntryOrigin.LIBRARY -> repository.confirmAndCommit(request).errorBookEntryId
                    CaptureEntryOrigin.TUTOR -> repository.confirmForTutoring(request).let { session ->
                        pendingTutorSessionId.value = session.sessionId
                        confirmedTutorSession.value = session
                        session.errorBookEntryId
                    }
                }
                workflowState.value = CaptureWorkflowStateMachine.reduce(
                    workflowState.value,
                    CaptureWorkflowAction.CommitSucceeded(
                        draftId = draftId,
                        basisRevisionNumber = basisRevisionNumber,
                        requestId = requestId,
                        savedEntryId = savedEntryId,
                    ),
                )
                userError.value = null
                pendingConfirmCommand = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                workflowState.value = CaptureWorkflowStateMachine.reduce(
                    workflowState.value,
                    CaptureWorkflowAction.Failed(
                        draftId = draftId,
                        basisRevisionNumber = basisRevisionNumber,
                        requestId = requestId,
                        code = CaptureFailureCode.COMMIT_REJECTED,
                        canRetry = true,
                    ),
                )
                userError.value = appFailure(
                    code = AppFailureCode.DATABASE_WRITE_FAILED,
                    title = "这次保存没有完成",
                    message = when (origin) {
                        CaptureEntryOrigin.LIBRARY ->
                            "题目可能还没有保存完成。请直接重试，系统不会重复建题。"
                        CaptureEntryOrigin.TUTOR ->
                            "题目已经留在本机，但讲题会话可能还没有打开。请直接重试；不会自动存入错题本。"
                    },
                    dataPreserved = true,
                    retryability = Retryability.RETRYABLE,
                    primaryAction = ActionType.RETRY,
                )
            }
        }
    }

    fun refreshProviderCapabilities() {
        viewModelScope.launch {
            providerCapabilities.value = runCatching {
                withContext(ioDispatcher) { modelTasks.capabilities() }
            }.getOrNull()
        }
    }

    fun onTutorSessionConsumed(sessionId: String) {
        if (pendingTutorSessionId.value == sessionId) {
            pendingTutorSessionId.value = null
        }
        if (confirmedTutorSession.value?.sessionId == sessionId) {
            confirmedTutorSession.value = null
        }
    }

    fun consumeDraftImported(requestId: String) {
        if (importedDraftEvent.value?.requestId == requestId) {
            importedDraftEvent.value = null
        }
    }

    fun reset() {
        pendingSourceCommand = null
        pendingConfirmCommand = null
        pendingTutorSessionId.value = null
        importedDraftEvent.value = null
        confirmedTutorSession.value = null
        userError.value = null
        resumeState.value = CaptureResumeLoadState.NOT_REQUESTED
        workflowState.value = CaptureWorkflowStateMachine.reduce(
            workflowState.value,
            CaptureWorkflowAction.Reset(),
        )
    }

    private fun runPendingSource(command: PendingSourceCommand) {
        workflowState.value = CaptureWorkflowStateMachine.reduce(
            workflowState.value,
            CaptureWorkflowAction.ImportStarted(
                draftId = command.expectedDraftId,
                basisRevisionNumber = command.expectedRevisionNumber,
                requestId = command.requestId,
            ),
        )
        viewModelScope.launch {
            try {
                val summary = withContext(ioDispatcher) {
                    when (command.purpose) {
                        CaptureAcquisitionPurpose.NEW_CAPTURE -> repository.importDraft(
                            CaptureDraftImportRequest(
                                requestId = command.requestId,
                                localUri = command.uri,
                                source = command.source,
                                origin = command.origin,
                                occurredAtEpochMillis = command.occurredAtEpochMillis,
                            ),
                        )
                        CaptureAcquisitionPurpose.REPLACE_DRAFT,
                        CaptureAcquisitionPurpose.APPEND_DRAFT,
                        -> {
                            val current = workflowState.value
                            if (current.phase != CaptureWorkflowPhase.IMPORTING) {
                                throw IllegalStateException("Capture workflow left importing")
                            }
                            when (command.purpose) {
                                CaptureAcquisitionPurpose.REPLACE_DRAFT -> repository.replaceDraft(
                                    com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest(
                                        requestId = command.requestId,
                                        replacedDraftId = requireNotNull(command.expectedDraftId),
                                        expectedReplacedRevisionNumber =
                                            requireNotNull(command.expectedRevisionNumber),
                                        localUri = command.uri,
                                        source = command.source,
                                        occurredAtEpochMillis = command.occurredAtEpochMillis,
                                    ),
                                )
                                CaptureAcquisitionPurpose.APPEND_DRAFT -> repository.appendDraftPage(
                                    com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest(
                                        requestId = command.requestId,
                                        draftId = requireNotNull(command.expectedDraftId),
                                        expectedRevisionNumber =
                                            requireNotNull(command.expectedRevisionNumber),
                                        expectedPageCount = requireNotNull(command.expectedPageCount),
                                        localUri = command.uri,
                                        source = command.source,
                                        occurredAtEpochMillis = command.occurredAtEpochMillis,
                                    ),
                                )
                            }
                        }
                    }
                }
                applyDraftSummary(summary, command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                workflowState.value = CaptureWorkflowStateMachine.reduce(
                    workflowState.value,
                    CaptureWorkflowAction.Failed(
                        draftId = command.expectedDraftId,
                        basisRevisionNumber = command.expectedRevisionNumber,
                        requestId = command.requestId,
                        code = CaptureFailureCode.IMPORT_REJECTED,
                        canRetry = true,
                    ),
                )
                userError.value = appFailure(
                    code = AppFailureCode.ASSET_UNREADABLE,
                    title = "题图没有安全保存",
                    message = when (command.purpose) {
                        CaptureAcquisitionPurpose.NEW_CAPTURE,
                        CaptureAcquisitionPurpose.REPLACE_DRAFT,
                        -> "这张图片暂时无法安全保存，原题仍然保留，请重试。"
                        CaptureAcquisitionPurpose.APPEND_DRAFT ->
                            "补拍的页面没有保存成功，原来的页面仍然安全保留，请重试。"
                    },
                    dataPreserved = true,
                    retryability = Retryability.RETRYABLE,
                    primaryAction = ActionType.RETRY,
                )
            }
        }
    }

    private fun applyDraftSummary(
        summary: CaptureDraftSummary,
        command: PendingSourceCommand,
    ) {
        workflowState.value = CaptureWorkflowStateMachine.reduce(
            workflowState.value,
            CaptureWorkflowAction.DraftImported(
                draftId = summary.draftId,
                basisRevisionNumber = summary.revisionNumber,
                requestId = command.requestId,
            ),
        )
        pendingSourceCommand = null
        importedDraftEvent.value = CaptureDraftImportedEvent(
            requestId = command.requestId,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
            sourceUri = command.uri,
            purpose = command.purpose,
            summary = summary,
        )
        userError.value = null
    }

    private suspend fun restore(draftId: String) {
        resumeState.value = CaptureResumeLoadState.LOADING
        val resumable = try {
            withContext(ioDispatcher) { repository.readPendingCapture(draftId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (resumable == null) {
            resumeState.value = CaptureResumeLoadState.MISSING
            workflowState.value = CaptureWorkflowStateMachine.reduce(
                CaptureWorkflowState(),
                CaptureWorkflowAction.Failed(
                    draftId = draftId,
                    basisRevisionNumber = null,
                    requestId = null,
                    code = CaptureFailureCode.IMPORT_REJECTED,
                    canRetry = false,
                ),
            )
            return
        }
        resumable.tutorSessionId?.let { sessionId ->
            resumeState.value = CaptureResumeLoadState.REDIRECTING
            pendingTutorSessionId.value = sessionId
            workflowState.value = CaptureWorkflowState(
                phase = CaptureWorkflowPhase.REVIEWING,
                draftId = resumable.draftId,
                basisRevisionNumber = resumable.currentRevisionNumber,
            )
            return
        }
        workflowState.value = CaptureWorkflowState(
            phase = if (resumable.latestParseTask?.status ==
                com.tingyun.smartmistakebook.core.model.ModelTaskStatus.SUCCEEDED
            ) {
                CaptureWorkflowPhase.REVIEWING
            } else {
                CaptureWorkflowPhase.ASSESSING
            },
            draftId = resumable.draftId,
            basisRevisionNumber = resumable.currentRevisionNumber,
        )
        resumeState.value = CaptureResumeLoadState.READY
    }

    private data class PendingSourceCommand(
        val uri: String,
        val source: CaptureInputSource,
        val origin: CaptureEntryOrigin,
        val purpose: CaptureAcquisitionPurpose,
        val requestId: String,
        val occurredAtEpochMillis: Long,
        val expectedDraftId: String?,
        val expectedRevisionNumber: Int?,
        val expectedPageCount: Int?,
    )

    private data class PendingConfirmCommand(
        val workspaceIdentity: CaptureDraftWorkspaceIdentity,
        val origin: CaptureEntryOrigin,
    )

    private companion object {
        const val KEY_RESUME_DRAFT_ID = "resumeDraftId"
        val BUSY_PHASES = setOf(
            CaptureWorkflowPhase.IMPORTING,
            CaptureWorkflowPhase.ASSESSING,
            CaptureWorkflowPhase.PARSING,
            CaptureWorkflowPhase.COMMITTING,
        )
        val ALLOW_APPEND_REPLACE_PHASES = setOf(
            CaptureWorkflowPhase.ASSESSING,
            CaptureWorkflowPhase.PARSING,
            CaptureWorkflowPhase.REVIEWING,
            CaptureWorkflowPhase.FAILED,
        )
    }
}
