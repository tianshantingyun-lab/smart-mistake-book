package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContext
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorGuidancePolicy
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceOutcome
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceRequest
import com.tingyun.smartmistakebook.core.domain.TutorProblemScope
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.domain.toTutorConversationMemory
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.JadeActive
import java.util.UUID
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CapturedTutorSessionRoute(
    sessionId: String,
    autoStartAuthorization: TutorAutoStartAuthorization? = null,
    onAutoStartAuthorizationConsumed: (String) -> Unit = {},
    repository: CaptureWorkflowRepository,
    modelTasks: ScopedModelTaskPort,
    sessionHost: TutorCurrentSessionHostPort,
    profile: StudyProfileOverview? = null,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    onOpenModelSettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onCameraAttachment: () -> Unit = {},
    onGalleryAttachment: () -> Unit = {},
    onLibraryAttachment: () -> Unit = onOpenMistakeNotebook,
    onBack: () -> Unit,
    onEndedWithoutSave: () -> Unit = onBack,
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    explanationModeVersion: Long = 0L,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    learningWritesAllowed: Boolean = true,
    visualIntent: TutorCurrentSessionVisualIntent = TutorCurrentSessionVisualIntent.NONE,
    masteryContextRepository: TutorMasteryContextRepository? = null,
    questionKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    fallbackKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var reloadToken by remember { mutableIntStateOf(0) }
    var state by remember(sessionId) {
        mutableStateOf<CapturedTutorSessionUiState>(CapturedTutorSessionUiState.Loading)
    }
    var saveInProgress by remember { mutableStateOf(false) }
    var saveError by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var saveRequestId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var saveOccurredAtEpochMillis by rememberSaveable(sessionId) { mutableStateOf<Long?>(null) }
    var endInProgress by remember { mutableStateOf(false) }
    var endError by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var endOccurredAtEpochMillis by rememberSaveable(sessionId) { mutableStateOf<Long?>(null) }
    var showEndConfirmation by rememberSaveable(sessionId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId, reloadToken) {
        state = if (sessionId.isBlank()) {
            CapturedTutorSessionUiState.Missing
        } else {
            try {
                repository.readTutorSession(sessionId)
                    ?.let(CapturedTutorSessionUiState::Ready)
                    ?: CapturedTutorSessionUiState.Missing
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CapturedTutorSessionUiState.Unavailable
            }
        }
    }

    fun saveSession(session: ConfirmedTutorSession) {
        if (saveInProgress || endInProgress || session.disposition != TutorSessionDisposition.ACTIVE) {
            return
        }
        val requestId = saveRequestId ?: UUID.randomUUID().toString().also {
            saveRequestId = it
        }
        val occurredAt = saveOccurredAtEpochMillis ?: System.currentTimeMillis().also {
            saveOccurredAtEpochMillis = it
        }
        saveInProgress = true
        saveError = null
        scope.launch {
            try {
                repository.saveTutorSession(
                    SaveTutorSessionRequest(
                        requestId = requestId,
                        sessionId = session.sessionId,
                        occurredAtEpochMillis = occurredAt,
                    ),
                )
                state = repository.readTutorSession(session.sessionId)
                    ?.let(CapturedTutorSessionUiState::Ready)
                    ?: CapturedTutorSessionUiState.Missing
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveError = "还没有保存完成，请直接重试；不会重复加入错题本。"
            } finally {
                saveInProgress = false
            }
        }
    }

    fun endSessionWithoutSaving(session: ConfirmedTutorSession) {
        if (saveInProgress || endInProgress || session.disposition != TutorSessionDisposition.ACTIVE) {
            return
        }
        val occurredAt = endOccurredAtEpochMillis ?: System.currentTimeMillis().also {
            endOccurredAtEpochMillis = it
        }
        endInProgress = true
        endError = null
        scope.launch {
            try {
                repository.endTutorSessionWithoutSaving(
                    EndTutorSessionWithoutSaveRequest(
                        sessionId = session.sessionId,
                        occurredAtEpochMillis = occurredAt,
                    ),
                )
                onEndedWithoutSave()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                endError = "还没有结束成功，这道临时题仍保留；你可以直接重试。"
            } finally {
                endInProgress = false
            }
        }
    }

    CapturedTutorSessionContent(
        state = state,
        saveInProgress = saveInProgress,
        saveError = saveError,
        endInProgress = endInProgress,
        endError = endError,
        onSave = ::saveSession,
        onRequestEnd = { showEndConfirmation = true },
        onRetryLoad = { reloadToken += 1 },
        repository = repository,
        modelTasks = modelTasks,
        sessionHost = sessionHost,
        profile = profile,
        catalogEntries = catalogEntries,
        learningWritesAllowed = learningWritesAllowed,
        visualIntent = visualIntent,
        onOpenModelSettings = onOpenModelSettings,
        onOpenMistakeNotebook = onOpenMistakeNotebook,
        onOpenProfile = onOpenProfile,
        onCameraAttachment = onCameraAttachment,
        onGalleryAttachment = onGalleryAttachment,
        onLibraryAttachment = onLibraryAttachment,
        onBack = onBack,
        autoStartAuthorization = autoStartAuthorization,
        onAutoStartAuthorizationConsumed = onAutoStartAuthorizationConsumed,
        explanationMode = explanationMode,
        explanationModeVersion = explanationModeVersion,
        onExplanationModeChange = onExplanationModeChange,
        masteryContextRepository = masteryContextRepository,
        questionKnowledgeNodes = questionKnowledgeNodes,
        fallbackKnowledgeNodes = fallbackKnowledgeNodes,
        modifier = modifier,
    )


    if (showEndConfirmation) {
        AlertDialog(
            onDismissRequest = { showEndConfirmation = false },
            title = { Text("结束这次临时讲题？") },
            text = {
                Text("结束后不会加入错题本；题面和原图仍安全保留在本机。返回只表示稍后继续，不会结束。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val session = (state as? CapturedTutorSessionUiState.Ready)?.session
                        showEndConfirmation = false
                        if (session != null) endSessionWithoutSaving(session)
                    },
                    modifier = Modifier.testTag("captured_tutor_end_confirm"),
                ) {
                    Text("结束且不保存", color = ErrorWarm)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEndConfirmation = false },
                    modifier = Modifier.testTag("captured_tutor_end_cancel"),
                ) {
                    Text("继续讲题", color = JadeActive)
                }
            },
            containerColor = com.tingyun.smartmistakebook.core.ui.Paper,
        )
    }
}
