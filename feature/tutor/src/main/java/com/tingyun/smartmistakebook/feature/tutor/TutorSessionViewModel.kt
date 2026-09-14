package com.tingyun.smartmistakebook.feature.tutor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.SaveTutorDraftRequest
import com.tingyun.smartmistakebook.core.domain.SaveTutorDraftToLibraryUseCase
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.model.TutorConversationIds
import com.tingyun.smartmistakebook.core.model.ActionType
import com.tingyun.smartmistakebook.core.model.AppFailure
import com.tingyun.smartmistakebook.core.model.AppFailureCode
import com.tingyun.smartmistakebook.core.model.Retryability
import com.tingyun.smartmistakebook.core.model.appFailure
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TutorSessionViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: CaptureWorkflowRepository,
    private val conversations: TutorConversationRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val saveToLibrary: SaveTutorDraftToLibraryUseCase =
        SaveTutorDraftToLibraryUseCase(repository),
) : ViewModel() {
    private val sessionId: String = savedStateHandle
        .get<String>(KEY_SESSION_ID)
        ?.takeIf(String::isNotBlank)
        ?: ""

    private val _uiState = MutableStateFlow<TutorSessionUiState>(TutorSessionUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _saveInProgress = MutableStateFlow(false)
    val saveInProgress = _saveInProgress.asStateFlow()

    private val _saveError = MutableStateFlow<AppFailure?>(null)
    val saveError = _saveError.asStateFlow()

    private val _endInProgress = MutableStateFlow(false)
    val endInProgress = _endInProgress.asStateFlow()

    private val _endError = MutableStateFlow<AppFailure?>(null)
    val endError = _endError.asStateFlow()

    private val _longTermWritesBlocked = MutableStateFlow(
        savedStateHandle.get<Boolean>(KEY_LONG_TERM_BLOCKED) == true,
    )
    val longTermWritesBlocked = _longTermWritesBlocked.asStateFlow()

    private val _pendingEnd = MutableStateFlow(false)
    val pendingEnd = _pendingEnd.asStateFlow()

    private var saveRequestId: String? = savedStateHandle.get(KEY_SAVE_REQUEST_ID)
    private var saveOccurredAtEpochMillis: Long? =
        savedStateHandle.get(KEY_SAVE_OCCURRED_AT)
    private var endOccurredAtEpochMillis: Long? =
        savedStateHandle.get(KEY_END_OCCURRED_AT)

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _uiState.value = if (sessionId.isBlank()) {
                TutorSessionUiState.Missing
            } else {
                try {
                    withContext(ioDispatcher) {
                        repository.readTutorSession(sessionId)
                    }?.let(TutorSessionUiState::Ready)
                        ?: TutorSessionUiState.Missing
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    TutorSessionUiState.Unavailable
                }
            }
            val ready = _uiState.value as? TutorSessionUiState.Ready
            if (ready != null && sessionId.isNotBlank()) {
                runCatching {
                    withContext(ioDispatcher) {
                        conversations.createConversation(
                            CreateTutorConversationCommand(
                                conversationId = TutorConversationIds.captured(sessionId),
                                anchorKind = TutorConversationAnchorKind.EPHEMERAL_DRAFT,
                                anchorId = sessionId,
                                anchorRevisionId =
                                    "${ready.session.draftId}:${ready.session.draftRevisionNumber}",
                                title = ready.session.title,
                                createdAtEpochMillis = ready.session.createdAtEpochMillis,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun save(session: ConfirmedTutorSession) {
        if (_longTermWritesBlocked.value) {
            _saveError.value = appFailure(
                code = AppFailureCode.VALIDATION_FAILED,
                title = "这次不会写入长期记录",
                message = "你已选择这次不写入长期记录。",
                dataPreserved = true,
            )
            return
        }
        if (
            _saveInProgress.value ||
            _endInProgress.value ||
            session.disposition != TutorSessionDisposition.ACTIVE
        ) {
            return
        }
        val requestId = saveRequestId ?: UUID.randomUUID().toString().also {
            saveRequestId = it
        }
        val occurredAt = saveOccurredAtEpochMillis ?: System.currentTimeMillis().also {
            saveOccurredAtEpochMillis = it
        }
        _saveInProgress.value = true
        _saveError.value = null
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    saveToLibrary(
                        SaveTutorDraftRequest(
                            draftId = session.draftId,
                            sessionId = session.sessionId,
                            occurredAtEpochMillis = occurredAt,
                            requestId = requestId,
                        ),
                    )
                }
                _uiState.value = withContext(ioDispatcher) {
                    repository.readTutorSession(session.sessionId)
                }?.let(TutorSessionUiState::Ready)
                    ?: TutorSessionUiState.Missing
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _saveError.value = appFailure(
                    code = AppFailureCode.DATABASE_WRITE_FAILED,
                    title = "还没有保存完成",
                    message = "还没有保存完成，请直接重试；不会重复加入错题本。",
                    dataPreserved = true,
                    retryability = Retryability.RETRYABLE,
                    primaryAction = ActionType.RETRY,
                )
            } finally {
                _saveInProgress.value = false
            }
        }
    }

    fun endWithoutSaving(session: ConfirmedTutorSession) {
        if (
            _saveInProgress.value ||
            _endInProgress.value ||
            session.disposition != TutorSessionDisposition.ACTIVE
        ) {
            return
        }
        val occurredAt = endOccurredAtEpochMillis ?: System.currentTimeMillis().also {
            endOccurredAtEpochMillis = it
        }
        _endInProgress.value = true
        _endError.value = null
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    repository.endTutorSessionWithoutSaving(
                        EndTutorSessionWithoutSaveRequest(
                            sessionId = session.sessionId,
                            occurredAtEpochMillis = occurredAt,
                        ),
                    )
                }
                _pendingEnd.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _endError.value = appFailure(
                    code = AppFailureCode.DATABASE_WRITE_FAILED,
                    title = "还没有结束成功",
                    message = "还没有结束成功，这道临时题仍保留；你可以直接重试。",
                    dataPreserved = true,
                    retryability = Retryability.RETRYABLE,
                    primaryAction = ActionType.RETRY,
                )
            } finally {
                _endInProgress.value = false
            }
        }
    }

    fun markLongTermWritesBlocked() {
        _longTermWritesBlocked.value = true
    }

    fun onEndConsumed() {
        _pendingEnd.value = false
    }

    private companion object {
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_SAVE_REQUEST_ID = "saveRequestId"
        const val KEY_SAVE_OCCURRED_AT = "saveOccurredAtEpochMillis"
        const val KEY_END_OCCURRED_AT = "endOccurredAtEpochMillis"
        const val KEY_LONG_TERM_BLOCKED = "longTermWritesBlocked"
    }
}
