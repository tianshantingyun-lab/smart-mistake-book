package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCancellationReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
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
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.isModelEgressApprovalFresh
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.ui.BoundedLocalImage
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.StructuredContentRenderer
import com.tingyun.smartmistakebook.core.ui.studentSubjectLabel
import java.util.UUID
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TutorVisualGenerationExecutionState(
    val workSeeds: List<TutorVisualWorkSeed>,
    val sourceAssets: List<TutorVisualSourceAssetScope>,
    val provider: ProviderCapabilitySnapshot?,
    val approvedAtEpochMillis: Long?,
    val generationTasks: List<ModelTaskSnapshot>,
    val autoAnchors: Set<TutorVisualTurnAnchor>,
    val pendingRetry: PendingTutorEgressAction.RetryVisual?,
    val executionFailures: Set<TutorVisualExecutionKey>,
)

private data class TutorVisualReviewExecutionState(
    val workSeeds: List<TutorVisualWorkSeed>,
    val sourceAssets: List<TutorVisualSourceAssetScope>,
    val provider: ProviderCapabilitySnapshot?,
    val approvedAtEpochMillis: Long?,
    val generationTasks: List<ModelTaskSnapshot>,
    val reviewTasks: List<ModelTaskSnapshot>,
    val autoAnchors: Set<TutorVisualTurnAnchor>,
    val pendingRetry: PendingTutorEgressAction.RetryVisual?,
    val executionFailures: Set<TutorVisualExecutionKey>,
)

private data class TutorVisualReviewWorkItem(
    val seed: TutorVisualWorkSeed,
    val candidate: TutorVisualResolution.Reviewing,
    val executionKey: TutorVisualExecutionKey,
)

internal class TutorVisibleChoice private constructor(
    val id: String,
    val labelMarkdown: String,
) {
    companion object {
        fun resolve(
            timeline: List<TutorConversationTimelineItem>,
            response: TutorResponseMessage,
        ): TutorVisibleChoice? {
            val id = response.selectedChoiceId ?: return null
            val sourceRequestId = response.choiceSourceRequestId ?: return null
            val directive = visibleTutorChoiceDirective(timeline, sourceRequestId) ?: return null
            val choice = directive.choices.firstOrNull { choice ->
                choice.id == id && choice.labelMarkdown == response.messageMarkdown
            } ?: return null
            return TutorVisibleChoice(choice.id, choice.labelMarkdown)
        }
    }
}

private fun visibleTutorChoiceDirective(
    timeline: List<TutorConversationTimelineItem>,
    sourceRequestId: String,
): TutorInteractionDirective.Choices? {
    val currentTask = timeline.asReversed().firstNotNullOfOrNull { item ->
        when (item) {
            is TutorConversationTimelineItem.Plan -> item.task
            is TutorConversationTimelineItem.Reply -> item.task
            else -> null
        }
    } ?: return null
    if (currentTask.request.requestId != sourceRequestId) return null
    return when (val output = currentTask.output) {
        is TutorPlanOutput -> output.plan.interactionDirective
        is TutorRespondOutput -> output.interactionDirective
        else -> null
    } as? TutorInteractionDirective.Choices
}

internal fun applyTutorVisualSchedulingBoundary(
    taskKind: ModelTaskKind,
    provider: ProviderCapabilitySnapshot?,
    sourceAssets: List<TutorVisualSourceAssetScope>,
    pendingRetry: PendingTutorEgressAction.RetryVisual?,
    pendingEgressState: PendingTutorEgressState,
    updatePendingEgress: (PendingTutorEgressState) -> Unit,
    cancelScheduledWork: () -> Unit,
): ProviderCapabilitySnapshot? {
    val providerForExecution = provider?.takeIf { candidate ->
        candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
            candidate.supports(taskKind)
    }
    if (providerForExecution != null && sourceAssets.isNotEmpty()) {
        return providerForExecution
    }
    val matchingRetry = pendingRetry?.takeIf { retry -> retry.taskKind == taskKind }
    updatePendingEgress(
        matchingRetry?.let { retry ->
            pendingEgressState.clearVisualRetryIfExecutionBlocked(
                expectedRetry = retry,
                executionAvailable = false,
            )
        } ?: pendingEgressState,
    )
    cancelScheduledWork()
    return null
}

internal data class TutorProviderAuthorityState(
    val provider: ProviderCapabilitySnapshot? = null,
    val loadFailed: Boolean = false,
    val refreshGeneration: Long = 0,
) {
    init {
        require(!loadFailed || provider == null)
        require(refreshGeneration >= 0)
    }

    fun beginRefresh(): TutorProviderAuthorityState =
        TutorProviderAuthorityState(refreshGeneration = refreshGeneration + 1)

    fun afterRefreshSuccess(
        generation: Long,
        refreshedProvider: ProviderCapabilitySnapshot,
    ): TutorProviderAuthorityState =
        if (generation == refreshGeneration) {
            TutorProviderAuthorityState(
                provider = refreshedProvider,
                refreshGeneration = refreshGeneration,
            )
        } else {
            this
        }

    fun afterRefreshFailure(generation: Long): TutorProviderAuthorityState =
        if (generation == refreshGeneration) {
            TutorProviderAuthorityState(
                loadFailed = true,
                refreshGeneration = refreshGeneration,
            )
        } else {
            this
        }
}

internal class TutorProviderAuthorityRefreshCoordinator(
    private val loadCapabilities: suspend () -> ProviderCapabilitySnapshot,
    private val currentAuthority: () -> TutorProviderAuthorityState,
    private val updateAuthority: (TutorProviderAuthorityState) -> Unit,
    private val currentPendingEgress: () -> PendingTutorEgressState,
    private val updatePendingEgress: (PendingTutorEgressState) -> Unit,
) {
    suspend fun refresh() {
        val refreshingAuthority = currentAuthority().beginRefresh()
        val refreshGeneration = refreshingAuthority.refreshGeneration
        updateAuthority(refreshingAuthority)
        updatePendingEgress(currentPendingEgress().withoutVisualRetry())
        try {
            val refreshedProvider = loadCapabilities()
            val authorityAfterLoad = currentAuthority()
            if (refreshGeneration == authorityAfterLoad.refreshGeneration) {
                updateAuthority(
                    authorityAfterLoad.afterRefreshSuccess(
                        generation = refreshGeneration,
                        refreshedProvider = refreshedProvider,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val authorityAfterLoad = currentAuthority()
            if (refreshGeneration == authorityAfterLoad.refreshGeneration) {
                updateAuthority(authorityAfterLoad.afterRefreshFailure(refreshGeneration))
            }
        }
    }
}

@Composable
fun CapturedTutorSessionRoute(
    sessionId: String,
    autoStartAuthorization: TutorAutoStartAuthorization? = null,
    onAutoStartAuthorizationConsumed: (String) -> Unit = {},
    repository: CaptureWorkflowRepository,
    modelTasks: ModelTaskRepository,
    interactions: TutorInteractionRepository,
    learningMemory: TutorLearningMemoryRepository,
    learnerScopeId: String = DEFAULT_TUTOR_LEARNER_SCOPE_ID,
    profile: StudyProfileOverview,
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
    var longTermWritesBlocked by rememberSaveable(sessionId) { mutableStateOf(false) }
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
        if (longTermWritesBlocked) {
            saveError = "你已选择这次不写入长期记录。"
            return
        }
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
        interactions = interactions,
        learningMemory = learningMemory,
        learnerScopeId = learnerScopeId,
        profile = profile,
        catalogEntries = catalogEntries,
        longTermWritesBlocked = longTermWritesBlocked,
        onLongTermWritesBlocked = { longTermWritesBlocked = true },
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

private sealed interface CapturedTutorSessionUiState {
    data object Loading : CapturedTutorSessionUiState
    data class Ready(val session: ConfirmedTutorSession) : CapturedTutorSessionUiState
    data object Missing : CapturedTutorSessionUiState
    data object Unavailable : CapturedTutorSessionUiState
}

@Composable
private fun CapturedTutorSessionContent(
    state: CapturedTutorSessionUiState,
    saveInProgress: Boolean,
    saveError: String?,
    endInProgress: Boolean,
    endError: String?,
    onSave: (ConfirmedTutorSession) -> Unit,
    onRequestEnd: () -> Unit,
    onRetryLoad: () -> Unit,
    repository: CaptureWorkflowRepository,
    modelTasks: ModelTaskRepository,
    interactions: TutorInteractionRepository,
    learningMemory: TutorLearningMemoryRepository,
    learnerScopeId: String,
    profile: StudyProfileOverview,
    catalogEntries: List<StudyCatalogEntry>,
    longTermWritesBlocked: Boolean,
    onLongTermWritesBlocked: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    onCameraAttachment: () -> Unit,
    onGalleryAttachment: () -> Unit,
    onLibraryAttachment: () -> Unit,
    onBack: () -> Unit,
    autoStartAuthorization: TutorAutoStartAuthorization?,
    onAutoStartAuthorizationConsumed: (String) -> Unit,
    explanationMode: TutorExplanationMode,
    explanationModeVersion: Long,
    onExplanationModeChange: (TutorExplanationMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is CapturedTutorSessionUiState.Ready -> ReadyCapturedSession(
                session = state.session,
                saveInProgress = saveInProgress,
                saveError = saveError,
                endInProgress = endInProgress,
                endError = endError,
                onSave = onSave,
                onRequestEnd = onRequestEnd,
                visualSourceAssetsReader = {
                    repository.readTutorVisualSourceAssets(state.session.sessionId)
                },
                visualOriginalAvailable = true,
                modelTasks = modelTasks,
                interactions = interactions,
                learningMemory = learningMemory,
                learnerScopeId = learnerScopeId,
                profile = profile,
                catalogEntries = catalogEntries,
                longTermWritesBlocked = longTermWritesBlocked,
                onLongTermWritesBlocked = onLongTermWritesBlocked,
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
                modifier = modifier.testTag("captured_tutor_session_screen"),
            )

        else -> TutorConversationFrame(
            header = {
                TutorPageHeader(onBack)
                PaperDivider()
            },
            autoScrollVersion = state,
            modifier = modifier.testTag("captured_tutor_session_screen"),
        ) {
            item("captured_tutor_non_ready") {
                when (state) {
                    CapturedTutorSessionUiState.Loading -> LoadingTutorQuestion()
                    CapturedTutorSessionUiState.Missing -> TutorQuestionUnavailable(
                        title = "没有找到这次讲题",
                        detail = "这次题面没有保存完整，可返回拍题入口重新上传。",
                        onRetry = onRetryLoad,
                    )
                    CapturedTutorSessionUiState.Unavailable -> TutorQuestionUnavailable(
                        title = "暂时无法打开这次讲题",
                        detail = "题面没有完整载入，本机记录仍会保留。请稍后重试。",
                        onRetry = onRetryLoad,
                    )
                    is CapturedTutorSessionUiState.Ready -> Unit
                }
            }
        }
    }
}

@Composable
internal fun TutorPageHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .testTag("captured_tutor_back"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = Ink,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = "讲题",
            color = Ink,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
internal fun LoadingTutorQuestion() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp)
            .testTag("captured_tutor_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = JadeActive,
        )
        Text("正在打开这道题…", color = InkSecondary)
    }
}

private data class CapturedTutorChoiceRuntimeIdentity(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val planRequestId: String,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val modeVersion: Long,
)

private fun CapturedTutorChoiceLearningMemoryState.blocksCapturedChoiceInteraction(): Boolean =
    when (this) {
        is CapturedTutorChoiceLearningMemoryState.RetryableFailure,
        is CapturedTutorChoiceLearningMemoryState.PermanentConflict,
        -> true

        is CapturedTutorChoiceLearningMemoryState.Ineligible ->
            reason == CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH ||
                reason == CapturedTutorChoiceIneligibleReason.RESPONSE_SCOPE_MISMATCH ||
                reason == CapturedTutorChoiceIneligibleReason.STALE_MODE_EPOCH

        else -> false
    }

@Composable
internal fun ReadyCapturedSession(
    session: ConfirmedTutorSession,
    saveInProgress: Boolean,
    saveError: String?,
    endInProgress: Boolean = false,
    endError: String? = null,
    onSave: (ConfirmedTutorSession) -> Unit,
    onRequestEnd: () -> Unit = {},
    visualSourceAssetsReader: suspend () -> List<TutorVisualSourceAssetScope> = {
        emptyList()
    },
    visualOriginalAvailable: Boolean = false,
    modelTasks: ModelTaskRepository,
    interactions: TutorInteractionRepository,
    learningMemory: TutorLearningMemoryRepository? = null,
    learnerScopeId: String = DEFAULT_TUTOR_LEARNER_SCOPE_ID,
    profile: StudyProfileOverview,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    longTermWritesBlocked: Boolean = false,
    onLongTermWritesBlocked: () -> Unit = {},
    onOpenModelSettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onCameraAttachment: () -> Unit = {},
    onGalleryAttachment: () -> Unit = {},
    onLibraryAttachment: () -> Unit = onOpenMistakeNotebook,
    onBack: () -> Unit = {},
    autoStartAuthorization: TutorAutoStartAuthorization? = null,
    onAutoStartAuthorizationConsumed: (String) -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    explanationModeVersion: Long = 0L,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var sourceExpanded by rememberSaveable(session.sessionId) { mutableStateOf(false) }
    TutorModelPanel(
        question = session.toTutorQuestionContext(),
        profile = profile,
        modelTasks = modelTasks,
        visualSourceAssetsReader = visualSourceAssetsReader,
        visualOriginalAvailable = visualOriginalAvailable,
        interactions = interactions,
        capturedSession = session,
        learningMemory = learningMemory,
        learnerScopeId = learnerScopeId,
        catalogEntries = catalogEntries,
        onLongTermWritesBlocked = onLongTermWritesBlocked,
        onRequestSave = { onSave(session) },
        onRequestEnd = onRequestEnd,
        onOpenMistakeNotebook = onOpenMistakeNotebook,
        onOpenProfile = onOpenProfile,
        onCameraAttachment = onCameraAttachment,
        onGalleryAttachment = onGalleryAttachment,
        onLibraryAttachment = onLibraryAttachment,
        onOpenVisualOriginal = { sourceExpanded = true },
        onOpenModelSettings = onOpenModelSettings,
        autoStartAuthorization = autoStartAuthorization,
        onAutoStartAuthorizationConsumed = onAutoStartAuthorizationConsumed,
        explanationMode = explanationMode,
        explanationModeVersion = explanationModeVersion,
        onExplanationModeChange = onExplanationModeChange,
        clock = clock,
        conversationEnabled = !session.isEndedWithoutSave,
        headerContent = {
            TutorPageHeader(onBack)
            PaperDivider()
        },
        leadingContent = {
            LocalModeLine(text = tutorSessionStatusLine(session))
            SectionHeader(
                title = session.title,
                modifier = Modifier.padding(top = 10.dp),
                action = if (session.disposition == TutorSessionDisposition.ENDED_WITHOUT_SAVE) {
                    null
                } else {
                    {
                        OutlineActionChip(
                            text = if (longTermWritesBlocked) {
                                "本次不记录"
                            } else {
                                tutorSessionSaveLabel(
                                    session.isSaved,
                                    saveInProgress,
                                    saveError != null,
                                )
                            },
                            onClick = { onSave(session) },
                            enabled = session.disposition == TutorSessionDisposition.ACTIVE &&
                                !saveInProgress && !endInProgress && !longTermWritesBlocked,
                            icon = Icons.Outlined.LibraryAddCheck,
                            contentDescription = when {
                                longTermWritesBlocked -> "本次不会存入错题本"
                                session.isSaved -> "本题已存入错题本"
                                else -> "将本题存入错题本"
                            },
                            modifier = Modifier.testTag("captured_tutor_save"),
                        )
                    }
                },
            )
            Text(
                text = session.subject.studentSubjectLabel(),
                modifier = Modifier.padding(top = 4.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(14.dp))
            StructuredContentRenderer(
                document = session.questionDocument.document,
                choicesEnabled = false,
            )
            OutlineActionChip(
                text = if (sourceExpanded) "收起原图" else "查看原图",
                onClick = { sourceExpanded = !sourceExpanded },
                icon = Icons.Outlined.Image,
                contentDescription = if (sourceExpanded) "收起拍题原图" else "查看拍题原图",
                modifier = Modifier
                    .padding(top = 14.dp)
                    .testTag("captured_tutor_source_toggle"),
            )
            if (sourceExpanded) {
                BoundedLocalImage(
                    imageUri = session.sourceImageUri,
                    contentDescription = "拍题原图",
                    expanded = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .testTag("captured_tutor_source_image"),
                )
            }
            if (session.isEndedWithoutSave) EndedTutorSessionNotice()
        },
        trailingContent = {
            saveError?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.testTag("captured_tutor_save_error"),
                    color = ErrorWarm,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (session.disposition == TutorSessionDisposition.ACTIVE) {
                OutlineActionChip(
                    text = if (endInProgress) {
                        "正在结束"
                    } else if (endError != null) {
                        "重试结束且不保存"
                    } else {
                        "结束且不保存"
                    },
                    onClick = onRequestEnd,
                    enabled = !saveInProgress && !endInProgress,
                    icon = Icons.Outlined.DeleteOutline,
                    contentDescription = "结束本次临时讲题且不存入错题本",
                    modifier = Modifier.testTag("captured_tutor_end_without_save"),
                )
            }
            endError?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.testTag("captured_tutor_end_error"),
                    color = ErrorWarm,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
        },
        modifier = modifier,
    )
}

@Composable
private fun EndedTutorSessionNotice() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        color = JadeSoft.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "本次讲题已结束 · 未存入错题本",
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "题面和原图仍保存在本机，但不会生成错题或继续调用模型。",
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun TutorModelPanel(
    question: TutorQuestionContext,
    profile: StudyProfileOverview,
    modelTasks: ModelTaskRepository,
    visualSourceAssetsReader: suspend () -> List<TutorVisualSourceAssetScope> = {
        emptyList()
    },
    visualOriginalAvailable: Boolean = false,
    interactions: TutorInteractionRepository,
    capturedSession: ConfirmedTutorSession? = null,
    learningMemory: TutorLearningMemoryRepository? = null,
    learnerScopeId: String = DEFAULT_TUTOR_LEARNER_SCOPE_ID,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    onLongTermWritesBlocked: () -> Unit = {},
    onRequestSave: () -> Unit = {},
    onRequestEnd: () -> Unit = {},
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onCameraAttachment: () -> Unit = {},
    onGalleryAttachment: () -> Unit = {},
    onLibraryAttachment: () -> Unit = onOpenMistakeNotebook,
    onOpenVisualOriginal: () -> Unit = {},
    onOpenModelSettings: () -> Unit,
    autoStartAuthorization: TutorAutoStartAuthorization? = null,
    onAutoStartAuthorizationConsumed: (String) -> Unit = {},
    conversationEnabled: Boolean = true,
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    explanationModeVersion: Long = 0L,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    headerContent: @Composable () -> Unit = {},
    leadingContent: @Composable ColumnScope.() -> Unit = {},
    trailingContent: @Composable ColumnScope.() -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
    modifier: Modifier = Modifier,
) {
    if (!conversationEnabled) {
        TutorConversationFrame(
            header = headerContent,
            autoScrollVersion = "${question.sessionId}:${question.revisionNumber}:ended",
            modifier = modifier,
        ) {
            item("tutor_question_context") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = leadingContent,
                )
            }
            item("tutor_session_footer") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = trailingContent,
                )
            }
        }
        return
    }
    var providerAuthority by remember(question.sessionId) {
        mutableStateOf(TutorProviderAuthorityState())
    }
    val provider = providerAuthority.provider
    val providerLoadFailed = providerAuthority.loadFailed
    val scope = rememberCoroutineScope()
    val choiceLearningMemoryController = remember(
        capturedSession?.sessionId,
        capturedSession?.draftId,
        capturedSession?.draftRevisionNumber,
        capturedSession?.questionDocument,
        learningMemory,
        learnerScopeId,
    ) {
        if (capturedSession != null && learningMemory != null) {
            CapturedTutorChoiceLearningMemoryController(
                repository = learningMemory,
                learnerScopeId = learnerScopeId,
                session = capturedSession,
                clock = clock,
            )
        } else {
            null
        }
    }
    val activeStreamOwner = remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        modelTasks,
        scope,
    ) {
        TutorActiveStreamOwner(
            scope = scope,
            initialMode = explanationMode,
            cancelDurableRequest = modelTasks::cancel,
        )
    }
    DisposableEffect(activeStreamOwner) {
        onDispose(activeStreamOwner::close)
    }
    val activeStreamState by activeStreamOwner.state.collectAsState()
    val activeMessage = activeStreamState.active
    val lifecycleOwner = LocalLifecycleOwner.current
    val persistedTasks by remember(question.sessionId) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_PLAN)
    }.collectAsState(initial = emptyList())
    val persistedRespondTasks by remember(question.sessionId) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_RESPOND)
    }.collectAsState(initial = emptyList())
    val persistedVisualGenerationTasks by remember(question.sessionId) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_VISUAL_GENERATE)
    }.collectAsState(initial = emptyList())
    val persistedVisualReviewTasks by remember(question.sessionId) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_VISUAL_REVIEW)
    }.collectAsState(initial = emptyList())
    var visualSourceAssets by remember(question.sessionId, question.revisionNumber) {
        mutableStateOf<List<TutorVisualSourceAssetScope>>(emptyList())
    }
    var visualSourceLoadFinished by remember(question.sessionId, question.revisionNumber) {
        mutableStateOf(false)
    }
    var visualSourceLoadFailed by remember(question.sessionId, question.revisionNumber) {
        mutableStateOf(false)
    }
    var visualExecutionFailures by remember(question.sessionId, question.revisionNumber) {
        mutableStateOf(emptySet<TutorVisualExecutionKey>())
    }
    LaunchedEffect(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
    ) {
        visualSourceLoadFinished = false
        try {
            visualSourceAssets = visualSourceAssetsReader()
                .sortedBy(TutorVisualSourceAssetScope::pageIndex)
            visualSourceLoadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            visualSourceAssets = emptyList()
            visualSourceLoadFailed = true
        } finally {
            visualSourceLoadFinished = true
        }
    }
    LaunchedEffect(
        visualSourceAssets.map { source -> source.assetId to source.sha256 },
    ) {
        visualExecutionFailures = emptySet()
    }
    val visiblePersistedRespondTasks = remember(
        persistedRespondTasks,
        activeStreamState.supersededRequestIds,
    ) {
        persistedRespondTasks.filterNot { task ->
            task.request.requestId in activeStreamState.supersededRequestIds
        }
    }
    val longTermWritesBlocked = visiblePersistedRespondTasks.blocksTutorLongTermWrites()
    LaunchedEffect(longTermWritesBlocked) {
        if (longTermWritesBlocked) onLongTermWritesBlocked()
    }
    val persistedResponses by remember(question.sessionId, interactions) {
        interactions.observe(question.sessionId)
    }.collectAsState(initial = emptyList())
    val persistedVisualTargetEvidence by remember(question.sessionId, interactions) {
        interactions.observeVisualTargetEvidence(question.sessionId)
    }.collectAsState(initial = emptyList())
    var interactionBusy by remember(question.sessionId) { mutableStateOf(false) }
    var interactionError by remember(question.sessionId) { mutableStateOf<String?>(null) }
    var pendingEvidenceJob by remember(question.sessionId) { mutableStateOf<Job?>(null) }
    var chatDraft by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
    ) { mutableStateOf("") }
    var chatStartError by rememberSaveable(question.sessionId) { mutableStateOf<String?>(null) }
    var reportedVisualSceneIds by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
    ) { mutableStateOf(emptyList<String>()) }
    var planRecoveryRequestInFlight by remember(question.sessionId) {
        mutableStateOf<String?>(null)
    }
    var pendingEgressState by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        stateSaver = pendingTutorEgressStateSaver,
    ) { mutableStateOf(PendingTutorEgressState()) }
    val pendingVisualRetry =
        pendingEgressState.action as? PendingTutorEgressAction.RetryVisual
    val latestPendingEgressForCapabilityRefresh = rememberUpdatedState(pendingEgressState)
    var draftToClearOnDurableStart by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
    ) { mutableStateOf<String?>(null) }
    var actionToClearOnDurableStartState by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        stateSaver = pendingTutorEgressStateSaver,
    ) { mutableStateOf(PendingTutorEgressState()) }
    var durableStartCleanupRequestId by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
    ) { mutableStateOf<String?>(null) }
    val responseActionAwaitingAuthorization =
        pendingEgressState.action.awaitsResponseAuthorization()
    var consumedAutoStartAuthorizationId by remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
    ) { mutableStateOf<String?>(null) }

    fun consumeAutoStartAuthorization(authorizationId: String) {
        if (consumedAutoStartAuthorizationId == authorizationId) return
        consumedAutoStartAuthorizationId = authorizationId
        onAutoStartAuthorizationConsumed(authorizationId)
    }

    val providerAuthorityRefreshCoordinator = remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        modelTasks,
    ) {
        TutorProviderAuthorityRefreshCoordinator(
            loadCapabilities = modelTasks::capabilities,
            currentAuthority = { providerAuthority },
            updateAuthority = { providerAuthority = it },
            currentPendingEgress = { latestPendingEgressForCapabilityRefresh.value },
            updatePendingEgress = { pendingEgressState = it },
        )
    }

    LaunchedEffect(question.sessionId, providerAuthorityRefreshCoordinator) {
        providerAuthorityRefreshCoordinator.refresh()
    }
    DisposableEffect(
        lifecycleOwner,
        question.sessionId,
        modelTasks,
        providerAuthorityRefreshCoordinator,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    providerAuthorityRefreshCoordinator.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentProvider = provider
    LaunchedEffect(
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
    ) {
        visualExecutionFailures = emptySet()
    }
    val authorizationNow = clock()
    var externalEgressLease by remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
        TUTOR_PROMPT_POLICY_VERSION,
        TUTOR_RESPOND_PROMPT_POLICY_VERSION,
        TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION,
        TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION,
    ) { mutableStateOf<TutorCompositionEgressLease?>(null) }
    var forceResponseDisclosure by remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        currentProvider?.providerConfigurationVersion,
    ) { mutableStateOf(false) }
    fun grantExternalEgressLease(
        providerForExecution: ProviderCapabilitySnapshot,
        approvedAtEpochMillis: Long,
    ) {
        if (providerForExecution.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
            externalEgressLease = TutorCompositionEgressLease.grant(
                question = question,
                provider = providerForExecution,
                approvedAtEpochMillis = approvedAtEpochMillis,
            )
        }
    }
    val conversationProjection = remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        persistedTasks,
        visiblePersistedRespondTasks,
        persistedResponses,
    ) {
        buildTutorConversationProjection(
            question = question,
            planTasks = persistedTasks,
            respondTasks = visiblePersistedRespondTasks,
            responses = persistedResponses,
        )
    }
    val tutorTasks = conversationProjection.planTasks
    val tutorResponses = conversationProjection.responses
    val tutorRespondTasks = conversationProjection.respondTasks
    val timeline = conversationProjection.timeline
    // Deliberately process-only: an answer is not durably unlocked until its exact bottom is visible.
    var planSolutionPreviewKeys by remember(
        question.sessionId,
        question.questionDocument.document.id,
        question.revisionNumber,
    ) { mutableStateOf(emptySet<PlanSolutionPreviewKey>()) }
    val solutionExposureTracker = rememberTutorSolutionExposureTracker(
        question = question,
        timeline = timeline,
        responses = tutorResponses,
        previewKeys = planSolutionPreviewKeys,
        longTermWritesBlocked = longTermWritesBlocked,
        interactions = interactions,
        clock = clock,
    )
    val answerExposureKeys = solutionExposureTracker.answerExposureKeys
    val activeRespondTask = activeMessage?.identity?.requestId?.let { requestId ->
        tutorRespondTasks.firstOrNull { task -> task.request.requestId == requestId }
    }
    val activeRespondInput = activeRespondTask?.request?.input as? TutorRespondInput
    val transientPreviewExposureKey = transientDirectPreviewExposureKey(
        exposureKey = activeRespondTask?.toRespondAnswerExposureKey(),
        explanationMode = activeRespondInput?.explanationMode,
        activeMessage = activeMessage,
    )
    LaunchedEffect(solutionExposureTracker, transientPreviewExposureKey) {
        transientPreviewExposureKey?.let(solutionExposureTracker::markTransientAnswerExposure)
    }
    val currentCycle = conversationProjection.currentCycle
    val currentCycleTasks = conversationProjection.currentCyclePlanTasks
    val currentCycleResponses = conversationProjection.currentCycleResponses
    val observedTask = conversationProjection.observedPlanTask
    val executablePlanProvider = currentProvider?.takeIf { candidate ->
        candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
            candidate.supports(ModelTaskKind.TUTOR_PLAN)
    }
    val matchingAutoStartAuthorization = autoStartAuthorization
        ?.takeUnless { it.authorizationId == consumedAutoStartAuthorizationId }
        ?.takeIf { authorization ->
            executablePlanProvider?.let { providerForExecution ->
                authorization.matches(
                    sessionId = question.sessionId,
                    questionDocumentId = question.questionDocument.document.id,
                    revisionNumber = question.revisionNumber,
                    provider = providerForExecution,
                    promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
                    nowEpochMillis = authorizationNow,
                )
            } == true
        }
    val planLeaseApprovedAt = currentProvider?.let { candidate ->
        when (candidate.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = candidate,
                taskKind = ModelTaskKind.TUTOR_PLAN,
                nowEpochMillis = authorizationNow,
            )
            ModelExecutionLocation.LOCAL_NO_EGRESS -> authorizationNow
            ModelExecutionLocation.UNAVAILABLE -> null
        }
    }
    val planFreshApprovalTask = currentProvider
        ?.takeIf { candidate ->
            candidate.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
                candidate.supports(ModelTaskKind.TUTOR_PLAN)
        }
        ?.let { candidate ->
            observedTask?.takeIf { task ->
                task.isRebuildableTutorRequest() && (
                    task.requiresFreshTutorApproval(candidate) ||
                        (planLeaseApprovedAt == null &&
                            (task.status.isTutorExecutionPending() ||
                                task.status == ModelTaskStatus.RETRYABLE_FAILURE)) ||
                        (planLeaseApprovedAt == null &&
                            task.status == ModelTaskStatus.SUCCEEDED &&
                            task.output !is TutorPlanOutput)
                    )
            }
        }

    fun executeTurn(
        cycleOrdinal: Int,
        priorConversationMemory: TutorConversationMemory?,
        priorCycleStudentMessages: List<String>,
        priorTurns: List<TutorTurnHistoryEntry>,
        oneShotAutoStartAuthorization: TutorAutoStartAuthorization? = null,
    ) {
        if (pendingEgressState.action.awaitsResponseAuthorization()) return
        val providerForExecution = executablePlanProvider ?: return
        val attempt = tutorTasks.count { task ->
            val input = task.request.input as? TutorPlanInput
            input?.cycleOrdinal == cycleOrdinal &&
                input.priorConversationMemory == priorConversationMemory &&
                input.priorCycleStudentMessages == priorCycleStudentMessages &&
                input.priorTurns == priorTurns
        }
        val requestId = tutorPlanRequestId(
            question = question,
            provider = providerForExecution,
            attempt = attempt,
            cycleOrdinal = cycleOrdinal,
            priorConversationMemory = priorConversationMemory,
            priorCycleStudentMessages = priorCycleStudentMessages,
            priorTurns = priorTurns,
        )
        val occurredAt = clock()
        val approvedAt = when (providerForExecution.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = providerForExecution,
                taskKind = ModelTaskKind.TUTOR_PLAN,
                nowEpochMillis = occurredAt,
            ) ?: oneShotAutoStartAuthorization
                ?.takeIf {
                    cycleOrdinal == 1 &&
                        priorConversationMemory == null &&
                        priorCycleStudentMessages.isEmpty() &&
                        priorTurns.isEmpty() &&
                        it.matches(
                            sessionId = question.sessionId,
                            questionDocumentId = question.questionDocument.document.id,
                            revisionNumber = question.revisionNumber,
                            provider = providerForExecution,
                            promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
                            nowEpochMillis = occurredAt,
                        )
                }
                ?.approvedAtEpochMillis ?: run {
                externalEgressLease = null
                pendingEgressState = PendingTutorEgressState(
                    PendingTutorEgressAction.Plan(
                        cycleOrdinal = cycleOrdinal,
                        priorConversationMemory = priorConversationMemory,
                        priorCycleStudentMessages = priorCycleStudentMessages,
                        priorTurns = priorTurns,
                    ),
                )
                return
            }
            ModelExecutionLocation.LOCAL_NO_EGRESS,
            ModelExecutionLocation.UNAVAILABLE,
            -> occurredAt
        }
        val request = buildTutorPlanRequest(
            question = question,
            profile = profile,
            provider = providerForExecution,
            requestId = requestId,
            occurredAtEpochMillis = occurredAt,
            approvedAtEpochMillis = approvedAt,
            cycleOrdinal = cycleOrdinal,
            priorConversationMemory = priorConversationMemory,
            priorCycleStudentMessages = priorCycleStudentMessages,
            priorTurns = priorTurns,
        )
        if (pendingEgressState.action is PendingTutorEgressAction.Plan) {
            pendingEgressState = PendingTutorEgressState()
        }
        scope.launch { modelTasks.execute(request).collect() }
    }

    val recoverableLocalPlanTask = observedTask?.takeIf { task ->
        task.isRebuildableTutorRequest() &&
            currentProvider?.executionLocation == ModelExecutionLocation.LOCAL_NO_EGRESS &&
            task.request.egressManifest == null &&
            task.status.isTutorExecutionPending()
    }
    LaunchedEffect(
        recoverableLocalPlanTask?.request?.requestId,
        recoverableLocalPlanTask?.stateVersion,
    ) {
        recoverableLocalPlanTask?.let { task -> modelTasks.execute(task.request).collect() }
    }
    LaunchedEffect(
        observedTask?.request?.requestId,
        executablePlanProvider?.providerId,
        executablePlanProvider?.modelId,
        executablePlanProvider?.providerConfigurationVersion,
        autoStartAuthorization?.authorizationId,
    ) {
        val authorization = autoStartAuthorization
            ?.takeUnless { it.authorizationId == consumedAutoStartAuthorizationId }
        if (observedTask != null) {
            authorization?.let { consumeAutoStartAuthorization(it.authorizationId) }
            return@LaunchedEffect
        }
        val providerForExecution = executablePlanProvider ?: run {
            if (currentProvider != null) {
                authorization?.let { consumeAutoStartAuthorization(it.authorizationId) }
            }
            return@LaunchedEffect
        }
        when (providerForExecution.executionLocation) {
            ModelExecutionLocation.LOCAL_NO_EGRESS -> {
                executeTurn(1, null, emptyList(), emptyList())
                authorization?.let { consumeAutoStartAuthorization(it.authorizationId) }
            }
            ModelExecutionLocation.EXTERNAL_PROVIDER -> if (authorization != null) {
                val authorizationMatches = authorization.matches(
                    sessionId = question.sessionId,
                    questionDocumentId = question.questionDocument.document.id,
                    revisionNumber = question.revisionNumber,
                    provider = providerForExecution,
                    promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
                    nowEpochMillis = clock(),
                )
                if (!authorizationMatches) {
                    consumeAutoStartAuthorization(authorization.authorizationId)
                    return@LaunchedEffect
                }
                grantExternalEgressLease(
                    providerForExecution = providerForExecution,
                    approvedAtEpochMillis = authorization.approvedAtEpochMillis,
                )
                executeTurn(
                    cycleOrdinal = 1,
                    priorConversationMemory = null,
                    priorCycleStudentMessages = emptyList(),
                    priorTurns = emptyList(),
                    oneShotAutoStartAuthorization = authorization,
                )
                consumeAutoStartAuthorization(authorization.authorizationId)
            }
            ModelExecutionLocation.UNAVAILABLE -> Unit
        }
    }

    if (observedTask == null) {
        TutorConversationFrame(
            header = headerContent,
            autoScrollVersion = listOf(
                question.sessionId,
                question.revisionNumber,
                currentProvider?.providerConfigurationVersion,
                providerLoadFailed,
            ),
            modifier = modifier,
        ) {
            item("tutor_question_context") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = leadingContent,
                )
            }
            item("tutor_model_entry") {
                when {
                    currentProvider == null -> TutorModelStatusCard(
                        title = if (providerLoadFailed) "暂时没准备好" else "正在准备这道题",
                        detail = if (providerLoadFailed) {
                            "题目已经保存，检查设置后可以继续。"
                        } else {
                            "请稍候。"
                        },
                        actionLabel = if (providerLoadFailed) "检查设置" else null,
                        onAction = onOpenModelSettings,
                    )

                    executablePlanProvider == null -> TutorModelStatusCard(
                        title = "需要先连接大模型",
                        detail = "题目已经保存，配置完成后可以从这里继续。",
                        actionLabel = "去设置",
                        onAction = onOpenModelSettings,
                    )

                    executablePlanProvider.executionLocation ==
                        ModelExecutionLocation.LOCAL_NO_EGRESS ||
                        planLeaseApprovedAt != null || matchingAutoStartAuthorization != null ->
                        TutorModelStatusCard(
                            title = "正在准备这道题",
                            detail = "正在整理讲解，请稍候。",
                        )

                    else -> TutorDisclosureCard(
                        provider = executablePlanProvider,
                        onApprove = {
                            grantExternalEgressLease(executablePlanProvider, clock())
                            executeTurn(1, null, emptyList(), emptyList())
                        },
                    )
                }
            }
            item("tutor_session_footer") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = trailingContent,
                )
            }
        }
        return
    }

    val currentInput = observedTask.request.input as TutorPlanInput
    val currentResponse = conversationProjection.responsesByTurn[
        TutorTurnKey(currentInput.cycleOrdinal, currentInput.turnOrdinal)
    ]
    val exactVisualTargetEvidence = persistedVisualTargetEvidence.filter { evidence ->
        evidence.sessionId == question.sessionId &&
            evidence.questionDocumentId == question.questionDocument.document.id &&
            evidence.revisionNumber == question.revisionNumber
    }
    val currentHistory = tutorContiguousHistory(
        planTasks = currentCycleTasks,
        respondTasks = tutorRespondTasks,
        responses = currentCycleResponses,
        visualTargetEvidence = exactVisualTargetEvidence,
    )
    val nextTurnExists = currentCycleTasks.any { task ->
        (task.request.input as? TutorPlanInput)?.turnOrdinal == currentHistory.size + 1
    }
    val currentPlanOutput = observedTask.output as? TutorPlanOutput
    val guidanceProblem = remember(
        question.questionDocument.document.id,
        question.revisionNumber,
    ) {
        TutorProblemScope(
            problemId = question.questionDocument.document.id,
            revisionNumber = question.revisionNumber,
        )
    }
    fun evidenceCancellation(requestId: String) = CancelTutorEvidenceCommand(
        sessionId = question.sessionId,
        questionDocumentId = question.questionDocument.document.id,
        revisionNumber = question.revisionNumber,
        evidenceRequestId = requestId,
        occurredAtEpochMillis = clock(),
    )
    val guidanceEvents = remember(
        currentCycleTasks,
        currentCycleResponses,
        tutorRespondTasks,
        persistedVisualTargetEvidence,
    ) {
        val visualEvidenceByRequestId = persistedVisualTargetEvidence
            .filter { evidence ->
                evidence.questionDocumentId == question.questionDocument.document.id &&
                    evidence.revisionNumber == question.revisionNumber
            }
            .associateBy { evidence -> evidence.modelTaskRequestId }
        buildList {
            currentCycleTasks
                .sortedBy { task -> (task.request.input as TutorPlanInput).turnOrdinal }
                .forEach { task ->
                    val input = task.request.input as TutorPlanInput
                    val output = task.output as? TutorPlanOutput ?: return@forEach
                    var pendingDirective: Pair<String, TutorInteractionDirective>? = null
                    if (output.plan.hasGuidedInteraction()) {
                        add(
                            TutorGuidanceEvent.Question(
                                requestId = task.request.requestId,
                                masteryRelevant = output.isMasteryRelevantTo(input),
                            ),
                        )
                    }
                    if (output.plan.interactionDirective.isEvidencePrompt()) {
                        pendingDirective = task.request.requestId to
                            requireNotNull(output.plan.interactionDirective)
                    }
                    currentCycleResponses
                        .firstOrNull { response -> response.turnOrdinal == input.turnOrdinal }
                        ?.takeIf(TutorTurnResponse::hasChoicePayload)
                        ?.let { response ->
                            add(
                                TutorGuidanceEvent.Evidence(
                                    requestId = task.request.requestId,
                                    selectionWasCorrect = response.selectionWasCorrect == true,
                                ),
                            )
                        }
                    visualEvidenceByRequestId[task.request.requestId]
                        ?.takeIf { evidence ->
                            evidence.anchor == TutorVisualTurnAnchor(
                                surface = TutorVisualTurnSurface.PLAN,
                                cycleOrdinal = input.cycleOrdinal,
                                turnOrdinal = input.turnOrdinal,
                            )
                        }
                        ?.let { evidence ->
                            add(
                                TutorGuidanceEvent.Evidence(
                                    requestId = evidence.modelTaskRequestId,
                                    selectionWasCorrect = evidence.selectionWasCorrect,
                                ),
                            )
                            pendingDirective = null
                        }
                    tutorRespondTasks
                        .filter { respondTask ->
                            val respondInput = respondTask.request.input as? TutorRespondInput
                            respondInput?.cycleOrdinal == input.cycleOrdinal &&
                                respondInput.turnOrdinal == input.turnOrdinal
                        }
                        .sortedBy { respondTask ->
                            (respondTask.request.input as TutorRespondInput).responseOrdinal
                        }
                        .forEach { respondTask ->
                            val respondInput = respondTask.request.input as TutorRespondInput
                            if (respondInput.studentMessage.isTutorHintRequest()) {
                                add(TutorGuidanceEvent.Hint(respondTask.request.requestId))
                            } else {
                                val pending = pendingDirective
                                val respondOutput =
                                    respondTask.output as? TutorRespondOutput ?: return@forEach
                                if (pending?.second is TutorInteractionDirective.FreeResponse) {
                                    freeResponseEvidenceEvent(
                                        requestId = pending.first,
                                        output = respondOutput,
                                    )?.let {
                                        add(it)
                                        pendingDirective = null
                                    }
                                }
                            }
                            val respondOutput =
                                respondTask.output as? TutorRespondOutput ?: return@forEach
                            if (respondOutput.solutionRevealed) {
                                add(TutorGuidanceEvent.Exposure(respondTask.request.requestId))
                                pendingDirective = null
                            } else if (respondOutput.interactionDirective.isEvidencePrompt()) {
                                add(
                                    TutorGuidanceEvent.Question(
                                        requestId = respondTask.request.requestId,
                                        masteryRelevant = output.isMasteryRelevantTo(input),
                                    ),
                                )
                                pendingDirective = respondTask.request.requestId to
                                    requireNotNull(respondOutput.interactionDirective)
                                visualEvidenceByRequestId[respondTask.request.requestId]
                                    ?.takeIf { evidence ->
                                        evidence.anchor == TutorVisualTurnAnchor(
                                            surface = TutorVisualTurnSurface.FOLLOW_UP,
                                            cycleOrdinal = respondInput.cycleOrdinal,
                                            turnOrdinal = respondInput.turnOrdinal,
                                            responseOrdinal = respondInput.responseOrdinal,
                                        )
                                    }
                                    ?.let { evidence ->
                                        add(
                                            TutorGuidanceEvent.Evidence(
                                                requestId = evidence.modelTaskRequestId,
                                                selectionWasCorrect =
                                                    evidence.selectionWasCorrect,
                                            ),
                                        )
                                        pendingDirective = null
                                    }
                            }
                        }
                }
        }
    }
    val replayedGuidanceTransition = remember(
        guidanceProblem,
        guidanceEvents,
    ) {
        replayTutorGuidanceTransition(
            problem = guidanceProblem,
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = false,
            events = guidanceEvents,
        )
    }
    val replayedCancellationRequestId =
        replayedGuidanceTransition.cancelEvidenceRequestId
            ?: replayedGuidanceTransition.state.pendingEvidenceRequestId
    var locallyCancelledEvidenceRequestIds by remember(
        question.sessionId,
        question.questionDocument.document.id,
        question.revisionNumber,
        interactions,
    ) {
        mutableStateOf(emptySet<String>())
    }
    var cancellationPendingEvidenceRequestIds by remember(
        question.sessionId,
        question.questionDocument.document.id,
        question.revisionNumber,
        interactions,
    ) {
        mutableStateOf(emptySet<String>())
    }
    var replayedPendingIsCancelled by remember(
        replayedCancellationRequestId,
        interactions,
    ) {
        mutableStateOf<Boolean?>(
            false.takeIf { replayedCancellationRequestId == null },
        )
    }
    LaunchedEffect(replayedCancellationRequestId, interactions) {
        val requestId = replayedCancellationRequestId
        replayedPendingIsCancelled = if (requestId == null) {
            false
        } else {
            runCatching {
                interactions.isEvidenceCancelled(evidenceCancellation(requestId))
            }.getOrNull()
        }
    }
    val replayedCancellationConfirmed = when {
        replayedCancellationRequestId in locallyCancelledEvidenceRequestIds -> true
        else -> replayedPendingIsCancelled
    }
    val guidanceResolution = remember(
        replayedGuidanceTransition,
        explanationMode,
        answerExposureKeys,
        replayedCancellationConfirmed,
    ) {
        resolveTutorGuidanceMode(
            replay = replayedGuidanceTransition,
            requestedMode = explanationMode,
            answerWasExposed = answerExposureKeys.isNotEmpty(),
            cancellationConfirmed = replayedCancellationConfirmed,
        )
    }
    val guidanceState = guidanceResolution.state
    val effectiveExplanationMode = guidanceState.mode
    val choiceLearningMemoryIdentity = remember(
        question.sessionId,
        question.questionDocument.document.id,
        question.revisionNumber,
        observedTask.request.requestId,
        currentInput.cycleOrdinal,
        currentInput.turnOrdinal,
        explanationModeVersion,
    ) {
        CapturedTutorChoiceRuntimeIdentity(
            sessionId = question.sessionId,
            questionDocumentId = question.questionDocument.document.id,
            revisionNumber = question.revisionNumber,
            planRequestId = observedTask.request.requestId,
            cycleOrdinal = currentInput.cycleOrdinal,
            turnOrdinal = currentInput.turnOrdinal,
            modeVersion = explanationModeVersion,
        )
    }
    val latestChoiceLearningMemoryIdentity =
        rememberUpdatedState(choiceLearningMemoryIdentity)
    val latestChoiceLearningMemoryMode =
        rememberUpdatedState(effectiveExplanationMode)
    var recoveredChoiceLearningMemoryIdentity by remember(
        choiceLearningMemoryController,
        question.sessionId,
        question.questionDocument.document.id,
        question.revisionNumber,
    ) {
        mutableStateOf<CapturedTutorChoiceRuntimeIdentity?>(null)
    }
    val choiceLearningMemoryReady =
        choiceLearningMemoryController == null ||
            recoveredChoiceLearningMemoryIdentity == choiceLearningMemoryIdentity
    val pendingInteractionBlocked =
        guidanceResolution.blockPendingInteraction ||
            (
                replayedCancellationRequestId in cancellationPendingEvidenceRequestIds &&
                    replayedCancellationConfirmed != true
                )

    fun cancellationIsConfirmed(requestId: String): Boolean =
        requestId in locallyCancelledEvidenceRequestIds ||
            (
                requestId == replayedCancellationRequestId &&
                    replayedPendingIsCancelled == true
                )

    fun pendingInteractionIsCurrentlyBlocked(): Boolean =
        (
            guidanceResolution.blockPendingInteraction &&
                (
                guidanceResolution.cancelEvidenceRequestId
                    ?.let { !cancellationIsConfirmed(it) }
                    ?: true
                )
            ) ||
            (
                replayedCancellationRequestId in cancellationPendingEvidenceRequestIds &&
                    replayedCancellationRequestId?.let(::cancellationIsConfirmed) != true
                )

    fun beginEvidenceCancellation(requestId: String) {
        cancellationPendingEvidenceRequestIds =
            cancellationPendingEvidenceRequestIds + requestId
        pendingEvidenceJob?.cancel()
        pendingEvidenceJob = null
        interactionBusy = false
    }

    suspend fun persistEvidenceCancellation(requestId: String) {
        beginEvidenceCancellation(requestId)
        if (!cancellationIsConfirmed(requestId)) {
            interactions.cancelEvidence(evidenceCancellation(requestId))
            locallyCancelledEvidenceRequestIds =
                locallyCancelledEvidenceRequestIds + requestId
        }
        cancellationPendingEvidenceRequestIds =
            cancellationPendingEvidenceRequestIds - requestId
        if (requestId == replayedCancellationRequestId) {
            replayedPendingIsCancelled = true
        }
        interactionError = null
    }

    LaunchedEffect(
        guidanceResolution.cancelEvidenceRequestId,
        replayedCancellationConfirmed,
    ) {
        val requestId = guidanceResolution.cancelEvidenceRequestId
            ?: return@LaunchedEffect
        if (!cancellationIsConfirmed(requestId)) {
            runCatching {
                persistEvidenceCancellation(requestId)
            }.onFailure {
                interactionError = "旧互动暂时没有安全关闭，请重试后再继续。"
            }
        }
    }
    LaunchedEffect(
        choiceLearningMemoryController,
        choiceLearningMemoryIdentity,
    ) {
        val controller = choiceLearningMemoryController ?: return@LaunchedEffect
        val exactIdentity = choiceLearningMemoryIdentity
        val exactPlanTask = observedTask
        val exactGuidanceState = guidanceState
        try {
            val responseSnapshot = interactions.observe(exactIdentity.sessionId).first()
            val recovery = controller.recoverFromInteractionSnapshot(
                planTask = exactPlanTask,
                guidanceState = exactGuidanceState,
                modeVersion = exactIdentity.modeVersion,
                responses = responseSnapshot,
            )
            if (latestChoiceLearningMemoryIdentity.value == exactIdentity) {
                if (recovery.blocksCapturedChoiceInteraction()) {
                    interactionError = "这次选择的学习记录暂时没有恢复，请重试后再继续。"
                } else {
                    recoveredChoiceLearningMemoryIdentity = exactIdentity
                }
            }
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (latestChoiceLearningMemoryIdentity.value == exactIdentity) {
                interactionError = "这次选择的学习记录暂时没有恢复，请重试后再继续。"
            }
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                controller.cancelPreparedChoice(
                    planTask = exactPlanTask,
                    evidenceRequestId = exactIdentity.planRequestId,
                    modeVersion = exactIdentity.modeVersion,
                    reason = TutorLearningEvidenceCancellationReason.TURN_SUPERSEDED,
                )
            }
        }
    }
    LaunchedEffect(
        activeStreamOwner,
        effectiveExplanationMode,
    ) {
        activeStreamOwner.updateMode(effectiveExplanationMode)
    }
    val requestExplanationModeChange: (TutorExplanationMode) -> Unit = { mode ->
        if (mode == TutorExplanationMode.DIRECT) {
            val transition = TutorGuidancePolicy.transitionMode(
                guidanceState,
                TutorExplanationMode.DIRECT,
            )
            transition.cancelEvidenceRequestId?.let(::beginEvidenceCancellation)
            scope.launch {
                runCatching {
                    transition.cancelEvidenceRequestId?.let {
                        persistEvidenceCancellation(it)
                    }
                    choiceLearningMemoryController?.let { controller ->
                        val cancellation = controller.cancelPreparedChoice(
                            planTask = observedTask,
                            evidenceRequestId = observedTask.request.requestId,
                            modeVersion = explanationModeVersion,
                            reason =
                                TutorLearningEvidenceCancellationReason.GUIDANCE_DISABLED,
                        )
                        check(!cancellation.blocksCapturedChoiceInteraction()) {
                            "Exact captured-choice cancellation did not reach a safe terminal"
                        }
                    }
                    onExplanationModeChange(mode)
                }.onFailure {
                    interactionError = "旧互动暂时没有安全关闭，请重试后再继续。"
                }
            }
        } else {
            onExplanationModeChange(mode)
        }
    }
    val respondSupported = currentProvider?.let { candidate ->
        candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
            candidate.supports(ModelTaskKind.TUTOR_RESPOND)
    } == true
    val latestRespondTasks = conversationProjection.latestRespondTasks
    val responseFreshApprovalTask = currentProvider
        ?.takeIf { candidate ->
            candidate.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
                candidate.supports(ModelTaskKind.TUTOR_RESPOND)
        }
        ?.let { candidate ->
            latestRespondTasks.lastOrNull()?.takeIf { task ->
                task.requiresFreshTutorApproval(candidate)
            }
        }
    val responseNeedsFreshAuthorization = responseFreshApprovalTask != null
    val responseLeaseApprovedAt = currentProvider?.let { candidate ->
        when (candidate.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = candidate,
                taskKind = ModelTaskKind.TUTOR_RESPOND,
                nowEpochMillis = authorizationNow,
            )
            ModelExecutionLocation.LOCAL_NO_EGRESS -> authorizationNow
            ModelExecutionLocation.UNAVAILABLE -> null
        }
    }
    val responseDisclosureRequired = currentProvider?.executionLocation ==
        ModelExecutionLocation.EXTERNAL_PROVIDER &&
        (forceResponseDisclosure || responseNeedsFreshAuthorization ||
            responseLeaseApprovedAt == null)
    val activeConversationApprovalAt = when (currentProvider?.executionLocation) {
        ModelExecutionLocation.EXTERNAL_PROVIDER ->
            responseLeaseApprovedAt.takeUnless { responseDisclosureRequired }
        ModelExecutionLocation.LOCAL_NO_EGRESS -> authorizationNow
        ModelExecutionLocation.UNAVAILABLE,
        null,
        -> null
    }
    val respondAuthorized = respondSupported && activeConversationApprovalAt != null
    val chatSending = activeMessage?.activityVisible == true || latestRespondTasks.any { task ->
        currentProvider?.let(task::matchesTutorProvider) == true &&
            task.status.isTutorExecutionPending()
    }
    val activeDurableRespondTask = activeMessage?.identity?.requestId?.let { requestId ->
        latestRespondTasks.firstOrNull { it.request.requestId == requestId }
    }
    LaunchedEffect(
        activeMessage?.identity?.requestId,
        activeMessage?.phase,
        activeDurableRespondTask?.status,
    ) {
        if (activeDurableRespondTask?.status == ModelTaskStatus.SUCCEEDED) {
            activeStreamOwner.acknowledgeDurableSuccess(
                activeDurableRespondTask.request.requestId,
            )
        }
    }
    val visualWorkSeeds = remember(tutorTasks, tutorRespondTasks) {
        tutorVisualWorkSeeds(
            planTasks = tutorTasks,
            respondTasks = tutorRespondTasks,
        )
    }
    val visualGenerateApprovedAt = currentProvider?.let { candidate ->
        when (candidate.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = candidate,
                taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
                nowEpochMillis = authorizationNow,
            )
            ModelExecutionLocation.LOCAL_NO_EGRESS -> authorizationNow
            ModelExecutionLocation.UNAVAILABLE -> null
        }
    }
    val visualReviewApprovedAt = currentProvider?.let { candidate ->
        when (candidate.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = candidate,
                taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
                nowEpochMillis = authorizationNow,
            )
            ModelExecutionLocation.LOCAL_NO_EGRESS -> authorizationNow
            ModelExecutionLocation.UNAVAILABLE -> null
        }
    }
    val autoVisualAnchors = remember(visualWorkSeeds) {
        visualWorkSeeds.takeLast(MAX_AUTO_VISUAL_WORK_ITEMS)
            .mapTo(hashSetOf(), TutorVisualWorkSeed::anchor)
    }
    val resolvedVisualStates = remember(
        visualWorkSeeds,
        persistedVisualGenerationTasks,
        persistedVisualReviewTasks,
        visualSourceAssets,
        visualSourceLoadFinished,
        visualSourceLoadFailed,
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
        currentProvider?.executionLocation,
        providerLoadFailed,
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        reportedVisualSceneIds,
        autoVisualAnchors,
        visualExecutionFailures,
    ) {
        visualWorkSeeds.associate { seed ->
            val providerForVisual = currentProvider
            val expectedGenerateRequestId = providerForVisual?.takeIf {
                visualSourceAssets.isNotEmpty() &&
                    it.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                    it.supports(ModelTaskKind.TUTOR_VISUAL_GENERATE)
            }?.let { provider ->
                tutorVisualGenerateRequestId(
                    question = question,
                    provider = provider,
                    sourceAssets = visualSourceAssets,
                    anchor = seed.anchor,
                    focusMarkdown = seed.request.focusMarkdown,
                    explanationMarkdown = seed.explanationMarkdown,
                )
            }
            val resolution = when {
                !visualSourceLoadFinished -> TutorVisualResolution.Preparing
                visualSourceLoadFailed || visualSourceAssets.isEmpty() ->
                    TutorVisualResolution.Fallback(
                        TutorVisualFallbackReason.SOURCE_UNAVAILABLE,
                    )
                providerForVisual == null ->
                    requireNotNull(
                        tutorVisualProviderLoadingResolution(
                            provider = null,
                            providerLoadFailed = providerLoadFailed,
                        ),
                    )
                providerForVisual.executionLocation == ModelExecutionLocation.UNAVAILABLE ||
                    !providerForVisual.supports(ModelTaskKind.TUTOR_VISUAL_GENERATE) ->
                    TutorVisualResolution.Fallback(
                        TutorVisualFallbackReason.PROVIDER_UNAVAILABLE,
                    )
                else -> {
                    resolveTutorVisual(
                        anchor = seed.anchor,
                        question = question,
                        generationTasks = persistedVisualGenerationTasks,
                        reviewTasks = persistedVisualReviewTasks,
                        expectedGenerationRequestId = requireNotNull(expectedGenerateRequestId),
                        reviewProvider = providerForVisual,
                    )
                }
            }
            val reviewCandidate = when (resolution) {
                is TutorVisualResolution.Reviewing -> resolution
                is TutorVisualResolution.Fallback -> resolution.reviewCandidate
                else -> null
            }
            val expectedReviewRequestId = if (
                providerForVisual != null &&
                reviewCandidate != null
            ) {
                tutorVisualReviewRequestId(
                    generationRequestId =
                        reviewCandidate.generationTask.request.requestId,
                    provider = providerForVisual,
                    generated = reviewCandidate.output,
                    reviewReasonCodes = reviewCandidate.reasonCodes,
                )
            } else {
                null
            }
            val executionFailure = visualExecutionFailures.failureFor(
                anchor = seed.anchor,
                generationRequestId = expectedGenerateRequestId,
                reviewRequestId = expectedReviewRequestId,
            )
            val presented = when {
                resolution is TutorVisualResolution.Ready &&
                    resolution.scene.sceneId in reportedVisualSceneIds ->
                    TutorVisualResolution.Fallback(TutorVisualFallbackReason.REPORTED)
                executionFailure != null && resolution !is TutorVisualResolution.Ready ->
                    TutorVisualResolution.Fallback(
                        reason = TutorVisualFallbackReason.NOT_STARTED,
                        canRetry = true,
                        reviewCandidate = reviewCandidate,
                        retryTaskKind = executionFailure.taskKind,
                        retrySemanticRequestId = executionFailure.semanticRequestId,
                    )
                resolution == TutorVisualResolution.Preparing &&
                    seed.anchor !in autoVisualAnchors ->
                    TutorVisualResolution.Fallback(
                        reason = TutorVisualFallbackReason.NOT_STARTED,
                        canRetry = expectedGenerateRequestId != null,
                        retryTaskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
                        retrySemanticRequestId = expectedGenerateRequestId,
                    )
                else -> resolution
            }
            seed.anchor to presented
        }
    }
    fun requestVisualRetry(anchor: TutorVisualTurnAnchor) {
        val fallback = resolvedVisualStates[anchor] as? TutorVisualResolution.Fallback
            ?: return
        if (!fallback.canRetry || pendingEgressState.action != null) return
        val failedTask = fallback.failedTask
        val taskKind = fallback.retryTaskKind ?: return
        val semanticRequestId = fallback.retrySemanticRequestId ?: return
        val providerForRetry = currentProvider?.takeIf { provider ->
            provider.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                provider.supports(taskKind)
        } ?: return
        if (
            taskKind != ModelTaskKind.TUTOR_VISUAL_GENERATE &&
            taskKind != ModelTaskKind.TUTOR_VISUAL_REVIEW
        ) {
            return
        }
        pendingEgressState = PendingTutorEgressState(
            PendingTutorEgressAction.RetryVisual(
                anchor = anchor,
                taskKind = taskKind,
                failedRequestId = failedTask?.request?.requestId,
                semanticRequestId = semanticRequestId,
                providerId = providerForRetry.providerId,
                modelId = providerForRetry.modelId,
                providerConfigurationVersion =
                    providerForRetry.providerConfigurationVersion,
            ),
        )
        visualExecutionFailures = visualExecutionFailures.filterNotTo(mutableSetOf()) { failure ->
            failure.anchor == anchor &&
                failure.taskKind == taskKind &&
                failure.semanticRequestId == semanticRequestId
        }
    }

    fun openVisualReadOnly(requestId: String) {
        val browse = TutorGuidancePolicy.evaluate(
            guidanceState,
            TutorGuidanceRequest.visualBrowse(requestId, guidanceProblem),
        )
        if (browse.outcome == TutorGuidanceOutcome.READ_ONLY) onOpenVisualOriginal()
    }
    fun reportVisualIncorrect(sceneId: String) {
        if (sceneId !in reportedVisualSceneIds) {
            reportedVisualSceneIds = reportedVisualSceneIds + sceneId
            openVisualReadOnly("visual-report:$sceneId")
        }
    }

    fun clearPendingVisualRetry(retry: PendingTutorEgressAction.RetryVisual) {
        if (pendingEgressState.action == retry) {
            pendingEgressState = PendingTutorEgressState()
        }
    }

    fun rejectStalePendingVisualRetry(
        retry: PendingTutorEgressAction.RetryVisual,
        providerForRetry: ProviderCapabilitySnapshot?,
        semanticRequestId: String?,
    ): Boolean {
        val identityChanged = providerForRetry == null ||
            semanticRequestId == null ||
            !retry.matches(providerForRetry, semanticRequestId)
        if (identityChanged) {
            pendingEgressState = pendingEgressState.clearVisualRetryIfIdentityChanged(
                expectedRetry = retry,
                provider = providerForRetry,
                semanticRequestId = semanticRequestId,
            )
        }
        return identityChanged
    }

    suspend fun executeVisualRequest(
        anchor: TutorVisualTurnAnchor,
        request: ModelTaskRequest,
    ): Boolean {
        val executionKey = TutorVisualExecutionKey(
            anchor = anchor,
            taskKind = request.input.kind,
            semanticRequestId = request.requestId.substringBefore(":retry:"),
        )
        val outcome = collectTutorVisualExecution(modelTasks.execute(request))
        visualExecutionFailures = visualExecutionFailures.afterExecution(
            key = executionKey,
            outcome = outcome,
        )
        return outcome == TutorVisualExecutionOutcome.COMPLETED
    }

    val latestVisualWorkSeeds = rememberUpdatedState(visualWorkSeeds)
    val latestVisualSourceAssets = rememberUpdatedState(visualSourceAssets)
    val latestVisualProvider = rememberUpdatedState(currentProvider)
    val latestVisualGenerateApproval = rememberUpdatedState(visualGenerateApprovedAt)
    val latestVisualReviewApproval = rememberUpdatedState(visualReviewApprovedAt)
    val latestVisualGenerationTasks = rememberUpdatedState(persistedVisualGenerationTasks)
    val latestVisualReviewTasks = rememberUpdatedState(persistedVisualReviewTasks)
    val latestAutoVisualAnchors = rememberUpdatedState(autoVisualAnchors)
    val latestPendingVisualRetry = rememberUpdatedState(pendingVisualRetry)
    val latestVisualExecutionFailures = rememberUpdatedState(visualExecutionFailures)

    LaunchedEffect(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        modelTasks,
    ) {
        val anchorScheduler = TutorVisualAnchorScheduler(this)
        snapshotFlow {
            TutorVisualGenerationExecutionState(
                workSeeds = latestVisualWorkSeeds.value,
                sourceAssets = latestVisualSourceAssets.value,
                provider = latestVisualProvider.value,
                approvedAtEpochMillis = latestVisualGenerateApproval.value,
                generationTasks = latestVisualGenerationTasks.value,
                autoAnchors = latestAutoVisualAnchors.value,
                pendingRetry = latestPendingVisualRetry.value,
                executionFailures = latestVisualExecutionFailures.value,
            )
        }.collect { state ->
            val generationRetry = state.pendingRetry
                ?.takeIf { retry ->
                    retry.taskKind == ModelTaskKind.TUTOR_VISUAL_GENERATE
                }
            val providerForVisual = applyTutorVisualSchedulingBoundary(
                taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
                provider = state.provider,
                sourceAssets = state.sourceAssets,
                pendingRetry = generationRetry,
                pendingEgressState = pendingEgressState,
                updatePendingEgress = { pendingEgressState = it },
                cancelScheduledWork = { anchorScheduler.cancelExcept(emptySet()) },
            )
            if (providerForVisual == null) {
                return@collect
            }
            val workSeeds = state.workSeeds.filter { seed ->
                seed.anchor in state.autoAnchors || seed.anchor == generationRetry?.anchor
            }
            val workItems = workSeeds.map { seed ->
                val semanticRequestId = tutorVisualGenerateRequestId(
                    question = question,
                    provider = providerForVisual,
                    sourceAssets = state.sourceAssets,
                    anchor = seed.anchor,
                    focusMarkdown = seed.request.focusMarkdown,
                    explanationMarkdown = seed.explanationMarkdown,
                )
                seed to TutorVisualExecutionKey(
                    anchor = seed.anchor,
                    taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
                    semanticRequestId = semanticRequestId,
                )
            }
            generationRetry?.let { retry ->
                val currentRetryKey = workItems
                    .firstOrNull { (seed, _) -> seed.anchor == retry.anchor }
                    ?.second
                if (
                    rejectStalePendingVisualRetry(
                        retry = retry,
                        providerForRetry = providerForVisual,
                        semanticRequestId = currentRetryKey?.semanticRequestId,
                    )
                ) {
                    anchorScheduler.cancelExcept(emptySet())
                    return@collect
                }
            }
            anchorScheduler.cancelExcept(
                workItems.mapTo(hashSetOf()) { (_, schedulerKey) -> schedulerKey },
            )
            workItems.forEach { (seed, schedulerKey) ->
                anchorScheduler.launch(schedulerKey) {
                    run seedExecution@ {
                            val pendingRetryForSeed = generationRetry
                                ?.takeIf { retry -> retry.anchor == seed.anchor }
                            if (
                                pendingRetryForSeed != null &&
                                rejectStalePendingVisualRetry(
                                    retry = pendingRetryForSeed,
                                    providerForRetry = providerForVisual,
                                    semanticRequestId = schedulerKey.semanticRequestId,
                                )
                            ) {
                                return@seedExecution
                            }
                            val approvedAt = when {
                                pendingRetryForSeed != null &&
                                    providerForVisual.executionLocation ==
                                    ModelExecutionLocation.EXTERNAL_PROVIDER ->
                                    pendingRetryForSeed.approvedAtFor(
                                        providerForVisual,
                                        schedulerKey.semanticRequestId,
                                    ) ?: return@seedExecution
                                else -> state.approvedAtEpochMillis ?: return@seedExecution
                            }
                            val failedTask =
                                pendingRetryForSeed?.failedRequestId?.let { failedRequestId ->
                                    state.generationTasks.firstOrNull { task ->
                                        task.request.requestId == failedRequestId
                                    }
                                }
                            val occurredAt = if (
                                providerForVisual.executionLocation ==
                                ModelExecutionLocation.EXTERNAL_PROVIDER
                            ) {
                                approvedAt
                            } else {
                                maxOf(
                                    clock(),
                                    failedTask?.updatedAtEpochMillis?.plus(1) ?: 0L,
                                )
                            }
                            val request = runCatching {
                                buildTutorVisualGenerateRequest(
                                    question = question,
                                    provider = providerForVisual,
                                    sourceAssets = state.sourceAssets,
                                    anchor = seed.anchor,
                                    focusMarkdown = seed.request.focusMarkdown,
                                    explanationMarkdown = seed.explanationMarkdown,
                                    occurredAtEpochMillis = occurredAt,
                                    approvedAtEpochMillis = approvedAt,
                                )
                            }.getOrNull() ?: return@seedExecution
                            if (
                                pendingRetryForSeed != null &&
                                !pendingRetryForSeed.matches(providerForVisual, request.requestId)
                            ) {
                                clearPendingVisualRetry(pendingRetryForSeed)
                                return@seedExecution
                            }
                            if (request.requestId != schedulerKey.semanticRequestId) {
                                pendingRetryForSeed?.let(::clearPendingVisualRetry)
                                return@seedExecution
                            }
                            if (
                                pendingRetryForSeed == null &&
                                schedulerKey in state.executionFailures
                            ) {
                                return@seedExecution
                            }
                            val existing = latestTutorVisualTask(state.generationTasks, request)
                            if (pendingRetryForSeed != null) {
                                val retryRequest = when {
                                    pendingRetryForSeed.failedRequestId == null &&
                                        existing == null -> request
                                    failedTask == null ||
                                        existing?.request?.requestId !=
                                        failedTask.request.requestId ||
                                        failedTask.request.input.kind !=
                                        ModelTaskKind.TUTOR_VISUAL_GENERATE ||
                                        !failedTask.matchesTutorProvider(providerForVisual) ||
                                        !failedTask.canRetryVisualTask() -> null
                                    else -> freshTutorVisualRetryRequest(request, failedTask)
                                }
                                if (retryRequest == null) {
                                    clearPendingVisualRetry(pendingRetryForSeed)
                                    return@seedExecution
                                }
                                executeVisualRequest(seed.anchor, retryRequest)
                                clearPendingVisualRetry(pendingRetryForSeed)
                                return@seedExecution
                            }
                            when {
                                existing == null ->
                                    executeVisualRequest(seed.anchor, request)
                                existing.status.isTutorExecutionPending() &&
                                    existing.coversCurrentTutorDisclosure(
                                        providerForVisual,
                                        ModelTaskKind.TUTOR_VISUAL_GENERATE,
                                    ) -> executeVisualRequest(seed.anchor, existing.request)
                            }
                    }
                }
            }
        }
    }

    LaunchedEffect(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        modelTasks,
    ) {
        val anchorScheduler = TutorVisualAnchorScheduler(this)
        snapshotFlow {
            TutorVisualReviewExecutionState(
                workSeeds = latestVisualWorkSeeds.value,
                sourceAssets = latestVisualSourceAssets.value,
                provider = latestVisualProvider.value,
                approvedAtEpochMillis = latestVisualReviewApproval.value,
                generationTasks = latestVisualGenerationTasks.value,
                reviewTasks = latestVisualReviewTasks.value,
                autoAnchors = latestAutoVisualAnchors.value,
                pendingRetry = latestPendingVisualRetry.value,
                executionFailures = latestVisualExecutionFailures.value,
            )
        }.collect { state ->
            val reviewRetry = state.pendingRetry
                ?.takeIf { retry ->
                    retry.taskKind == ModelTaskKind.TUTOR_VISUAL_REVIEW
                }
            val providerForReview = applyTutorVisualSchedulingBoundary(
                taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
                provider = state.provider,
                sourceAssets = state.sourceAssets,
                pendingRetry = reviewRetry,
                pendingEgressState = pendingEgressState,
                updatePendingEgress = { pendingEgressState = it },
                cancelScheduledWork = { anchorScheduler.cancelExcept(emptySet()) },
            )
            if (providerForReview == null) {
                return@collect
            }
            val workSeeds = state.workSeeds.filter { seed ->
                seed.anchor in state.autoAnchors || seed.anchor == reviewRetry?.anchor
            }
            val workItems = workSeeds.mapNotNull { seed ->
                val pendingRetryForSeed = reviewRetry
                    ?.takeIf { retry -> retry.anchor == seed.anchor }
                val resolution = resolveTutorVisual(
                    question = question,
                    anchor = seed.anchor,
                    generationTasks = state.generationTasks,
                    reviewTasks = state.reviewTasks,
                    expectedGenerationRequestId = tutorVisualGenerateRequestId(
                        question = question,
                        provider = providerForReview,
                        sourceAssets = state.sourceAssets,
                        anchor = seed.anchor,
                        focusMarkdown = seed.request.focusMarkdown,
                        explanationMarkdown = seed.explanationMarkdown,
                    ),
                    reviewProvider = providerForReview,
                )
                val candidate = when (resolution) {
                    is TutorVisualResolution.Reviewing -> resolution
                    is TutorVisualResolution.Fallback -> resolution.reviewCandidate
                    else -> null
                } ?: return@mapNotNull null
                if (
                    resolution is TutorVisualResolution.Fallback &&
                    pendingRetryForSeed == null
                ) {
                    return@mapNotNull null
                }
                val semanticRequestId = tutorVisualReviewRequestId(
                    generationRequestId = candidate.generationTask.request.requestId,
                    provider = providerForReview,
                    generated = candidate.output,
                    reviewReasonCodes = candidate.reasonCodes,
                )
                TutorVisualReviewWorkItem(
                    seed = seed,
                    candidate = candidate,
                    executionKey = TutorVisualExecutionKey(
                        anchor = seed.anchor,
                        taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
                        semanticRequestId = semanticRequestId,
                    ),
                )
            }
            reviewRetry?.let { retry ->
                val currentRetryKey = workItems
                    .firstOrNull { workItem -> workItem.seed.anchor == retry.anchor }
                    ?.executionKey
                if (
                    rejectStalePendingVisualRetry(
                        retry = retry,
                        providerForRetry = providerForReview,
                        semanticRequestId = currentRetryKey?.semanticRequestId,
                    )
                ) {
                    anchorScheduler.cancelExcept(emptySet())
                    return@collect
                }
            }
            anchorScheduler.cancelExcept(
                workItems.mapTo(hashSetOf(), TutorVisualReviewWorkItem::executionKey),
            )
            workItems.forEach { workItem ->
                val seed = workItem.seed
                anchorScheduler.launch(workItem.executionKey) {
                    run seedExecution@ {
                            val pendingRetryForSeed = reviewRetry
                                ?.takeIf { retry -> retry.anchor == seed.anchor }
                            if (
                                pendingRetryForSeed != null &&
                                rejectStalePendingVisualRetry(
                                    retry = pendingRetryForSeed,
                                    providerForRetry = providerForReview,
                                    semanticRequestId =
                                        workItem.executionKey.semanticRequestId,
                                )
                            ) {
                                return@seedExecution
                            }
                            val reviewCandidate = workItem.candidate
                            val approvedAt = when {
                                pendingRetryForSeed != null &&
                                    providerForReview.executionLocation ==
                                    ModelExecutionLocation.EXTERNAL_PROVIDER ->
                                    pendingRetryForSeed.approvedAtFor(
                                        providerForReview,
                                        workItem.executionKey.semanticRequestId,
                                    ) ?: return@seedExecution
                                else -> state.approvedAtEpochMillis ?: return@seedExecution
                            }
                            val failedTask =
                                pendingRetryForSeed?.failedRequestId?.let { failedRequestId ->
                                    state.reviewTasks.firstOrNull { task ->
                                        task.request.requestId == failedRequestId
                                    }
                                }
                            val occurredAt = if (
                                providerForReview.executionLocation ==
                                ModelExecutionLocation.EXTERNAL_PROVIDER
                            ) {
                                approvedAt
                            } else {
                                maxOf(
                                    clock(),
                                    failedTask?.updatedAtEpochMillis?.plus(1) ?: 0L,
                                )
                            }
                            val request = runCatching {
                                buildTutorVisualReviewRequest(
                                    question = question,
                                    provider = providerForReview,
                                    sourceAssets = state.sourceAssets,
                                    generationRequest = reviewCandidate.generationTask.request,
                                    generated = reviewCandidate.output,
                                    reviewReasonCodes = reviewCandidate.reasonCodes,
                                    occurredAtEpochMillis = occurredAt,
                                    approvedAtEpochMillis = approvedAt,
                                )
                            }.getOrNull() ?: return@seedExecution
                            if (
                                pendingRetryForSeed != null &&
                                !pendingRetryForSeed.matches(providerForReview, request.requestId)
                            ) {
                                clearPendingVisualRetry(pendingRetryForSeed)
                                return@seedExecution
                            }
                            if (request.requestId != workItem.executionKey.semanticRequestId) {
                                pendingRetryForSeed?.let(::clearPendingVisualRetry)
                                return@seedExecution
                            }
                            if (
                                pendingRetryForSeed == null &&
                                workItem.executionKey in state.executionFailures
                            ) {
                                return@seedExecution
                            }
                            val existing = latestTutorVisualTask(state.reviewTasks, request)
                            if (pendingRetryForSeed != null) {
                                val retryRequest = when {
                                    pendingRetryForSeed.failedRequestId == null &&
                                        existing == null -> request
                                    failedTask == null ||
                                        existing?.request?.requestId !=
                                        failedTask.request.requestId ||
                                        failedTask.request.input.kind !=
                                        ModelTaskKind.TUTOR_VISUAL_REVIEW ||
                                        !failedTask.matchesTutorProvider(providerForReview) ||
                                        !failedTask.canRetryVisualTask() -> null
                                    else -> freshTutorVisualRetryRequest(request, failedTask)
                                }
                                if (retryRequest == null) {
                                    clearPendingVisualRetry(pendingRetryForSeed)
                                    return@seedExecution
                                }
                                executeVisualRequest(seed.anchor, retryRequest)
                                clearPendingVisualRetry(pendingRetryForSeed)
                                return@seedExecution
                            }
                            when {
                                existing == null ->
                                    executeVisualRequest(seed.anchor, request)
                                existing.status.isTutorExecutionPending() &&
                                    existing.coversCurrentTutorDisclosure(
                                        providerForReview,
                                        ModelTaskKind.TUTOR_VISUAL_REVIEW,
                                    ) -> executeVisualRequest(seed.anchor, existing.request)
                            }
                    }
                }
            }
        }
    }

    fun startTutorRespondStream(
        studentMessage: String,
        startsNewTurn: Boolean,
        clearDraftOnPersist: Boolean,
        allowExternalEnvelopeForLocalRecovery: Boolean = false,
        clearPendingActionOnPersist: PendingTutorEgressAction? = null,
        retryConsumed: Boolean = false,
        prepare: suspend () -> ModelTaskRequest,
    ) {
        val providerForExecution = currentProvider ?: return
        if (
            providerForExecution.executionLocation == ModelExecutionLocation.UNAVAILABLE ||
            !providerForExecution.supports(ModelTaskKind.TUTOR_RESPOND)
        ) {
            return
        }
        if (
            providerForExecution.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
            externalEgressLease?.approvedAtFor(
                question = question,
                provider = providerForExecution,
                taskKind = ModelTaskKind.TUTOR_RESPOND,
                nowEpochMillis = clock(),
            ) == null
        ) {
            return
        }
        if (activeMessage?.activityVisible == true) return
        chatStartError = null
        if (clearDraftOnPersist) {
            draftToClearOnDurableStart = chatDraft
            durableStartCleanupRequestId = null
        }
        clearPendingActionOnPersist?.let { action ->
            actionToClearOnDurableStartState = PendingTutorEgressState(
                action.takeIf { pendingEgressState.action == it },
            )
            durableStartCleanupRequestId = null
        }
        activeStreamOwner.submit(
            studentMessage = studentMessage,
            startsNewTurn = startsNewTurn,
            retryConsumed = retryConsumed,
        ) {
            val request = prepare()
            withContext(Dispatchers.Main.immediate) {
                if (
                    draftToClearOnDurableStart != null ||
                    actionToClearOnDurableStartState.action != null
                ) {
                    durableStartCleanupRequestId = request.requestId
                }
            }
            val input = request.input as? TutorRespondInput
                ?: error("Tutor response stream requires TutorRespondInput")
            require(input.studentMessage == studentMessage) {
                "Tutor stream message must match its prepared request"
            }
            require(
                providerForExecution.executionLocation !=
                    ModelExecutionLocation.LOCAL_NO_EGRESS ||
                    request.egressManifest == null ||
                    allowExternalEnvelopeForLocalRecovery,
            ) {
                "Local Tutor recovery cannot silently reuse an external envelope"
            }
            TutorPreparedStream(request.requestId) { identity ->
                modelTasks.executeTutorStream(request, identity)
            }
        }
    }

    fun clearSubmittedStateAfterDurableStart() {
        draftToClearOnDurableStart?.let { submittedDraft ->
            if (chatDraft == submittedDraft) chatDraft = ""
        }
        actionToClearOnDurableStartState.action?.let { submittedAction ->
            if (pendingEgressState.action == submittedAction) {
                pendingEgressState = PendingTutorEgressState()
            }
        }
        draftToClearOnDurableStart = null
        actionToClearOnDurableStartState = PendingTutorEgressState()
        durableStartCleanupRequestId = null
    }

    LaunchedEffect(
        activeMessage?.identity?.requestId,
        activeMessage?.durablyStarted,
    ) {
        if (activeMessage?.durablyStarted != true) return@LaunchedEffect
        clearSubmittedStateAfterDurableStart()
    }

    val durableCleanupTask = durableStartCleanupRequestId?.let { requestId ->
        persistedRespondTasks.firstOrNull { it.request.requestId == requestId }
    }
    LaunchedEffect(
        durableStartCleanupRequestId,
        durableCleanupTask?.stateVersion,
    ) {
        if (durableCleanupTask != null) clearSubmittedStateAfterDurableStart()
    }

    fun collectTutorRespondRequest(
        request: ModelTaskRequest,
        clearDraftOnPersist: Boolean,
        allowExternalEnvelopeForLocalRecovery: Boolean = false,
        clearPendingActionOnPersist: PendingTutorEgressAction? = null,
        retryConsumed: Boolean = false,
    ) {
        val input = request.input as? TutorRespondInput ?: return
        startTutorRespondStream(
            studentMessage = input.studentMessage,
            startsNewTurn = false,
            clearDraftOnPersist = clearDraftOnPersist,
            allowExternalEnvelopeForLocalRecovery = allowExternalEnvelopeForLocalRecovery,
            clearPendingActionOnPersist = clearPendingActionOnPersist,
            retryConsumed = retryConsumed,
            prepare = { request },
        )
    }

    fun executeTutorResponse(
        response: TutorResponseMessage,
        requestedMove: TutorMoveType? = null,
        clearDraftOnPersist: Boolean = false,
    ) {
        val exactMessage = response.messageMarkdown
        val selectedChoiceId = response.selectedChoiceId
        val visibleChoice = TutorVisibleChoice.resolve(timeline, response)
        if (selectedChoiceId != null && visibleChoice == null) {
            interactionError = "这项互动已经过期，请根据当前提示重新选择。"
            return
        }
        if (
            exactMessage.isBlank() ||
            chatSending ||
            interactionBusy ||
            pendingInteractionIsCurrentlyBlocked()
        ) {
            return
        }
        if (exactMessage.isTutorHintRequest()) {
            val hint = TutorGuidancePolicy.evaluate(
                guidanceState,
                TutorGuidanceRequest.hint(
                    "hint:${question.sessionId}:${currentInput.cycleOrdinal}:${currentInput.turnOrdinal}",
                    guidanceProblem,
                ),
            )
            if (hint.outcome == TutorGuidanceOutcome.DIRECT_EXPLANATION) {
                val transition = TutorGuidancePolicy.transitionMode(
                    guidanceState,
                    TutorExplanationMode.DIRECT,
                )
                val previewKey = observedTask.toPlanSolutionPreviewKey()
                transition.cancelEvidenceRequestId?.let(::beginEvidenceCancellation)
                scope.launch {
                    runCatching {
                        transition.cancelEvidenceRequestId?.let {
                            persistEvidenceCancellation(it)
                        }
                        previewKey?.let { key ->
                            planSolutionPreviewKeys = planSolutionPreviewKeys + key
                        }
                    }.onFailure {
                        interactionError = "旧互动暂时没有安全关闭，请重试后再继续。"
                    }
                }
                return
            }
        }
        val requestTransition = tutorResponseModeTransitionFor(
            state = guidanceState,
            studentMessage = exactMessage,
        )
        val requestMode = requestTransition.state.mode
        requestTransition.cancelEvidenceRequestId
            ?.takeUnless(::cancellationIsConfirmed)
            ?.let { requestId ->
                beginEvidenceCancellation(requestId)
                scope.launch {
                    runCatching {
                        continueTutorResponseAfterEvidenceCancellation(
                            cancelEvidenceRequestId = requestId,
                            cancellationConfirmed = false,
                            cancelEvidence = ::persistEvidenceCancellation,
                            continueResponse = {
                                executeTutorResponse(
                                    response = response,
                                    requestedMove = requestedMove,
                                    clearDraftOnPersist = clearDraftOnPersist,
                                )
                            },
                        )
                    }.onFailure {
                        interactionError = "旧互动暂时没有安全关闭，请重试后再继续。"
                    }
                }
                return
            }
        val pendingResponseAction = pendingEgressState.action
            as? PendingTutorEgressAction.NewResponse
        when (val pendingAction = pendingEgressState.action) {
            is PendingTutorEgressAction.Plan,
            is PendingTutorEgressAction.RetryResponse,
            is PendingTutorEgressAction.RetryVisual,
            -> return
            is PendingTutorEgressAction.NewResponse -> if (
                pendingAction.message != exactMessage ||
                pendingAction.selectedChoiceId != selectedChoiceId ||
                pendingAction.choiceSourceRequestId != response.choiceSourceRequestId ||
                pendingAction.requestedMove != requestedMove ||
                pendingAction.clearDraftOnPersist != clearDraftOnPersist
            ) {
                return
            }
            null -> Unit
        }
        val visiblePlan = currentPlanOutput ?: return
        val providerForExecution = currentProvider?.takeIf { candidate ->
            candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                candidate.supports(ModelTaskKind.TUTOR_RESPOND)
        } ?: return
        val responseCycleOrdinal = currentInput.cycleOrdinal
        val responseTurnOrdinal = currentInput.turnOrdinal
        val approvalNow = clock()
        val externalApprovedAt = when (providerForExecution.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = providerForExecution,
                taskKind = ModelTaskKind.TUTOR_RESPOND,
                nowEpochMillis = approvalNow,
            ) ?: run {
                forceResponseDisclosure = true
                externalEgressLease = null
                pendingEgressState = PendingTutorEgressState(
                    PendingTutorEgressAction.NewResponse(
                        message = exactMessage,
                        selectedChoiceId = selectedChoiceId,
                        choiceSourceRequestId = response.choiceSourceRequestId,
                        requestedMove = requestedMove,
                        clearDraftOnPersist = clearDraftOnPersist,
                    ),
                )
                return
            }
            ModelExecutionLocation.LOCAL_NO_EGRESS -> null
            ModelExecutionLocation.UNAVAILABLE -> return
        }
        val tasksForRequest = tutorRespondTasks
        val exposureKeysForRequest = answerExposureKeys
        val currentResponseForRequest = currentResponse
        val answerWasExposed = observedTask.toPlanAnswerExposureKey() in
            exposureKeysForRequest
        val startResponse = {
            startTutorRespondStream(
                studentMessage = exactMessage,
                startsNewTurn = true,
                clearDraftOnPersist = clearDraftOnPersist,
                clearPendingActionOnPersist = pendingResponseAction,
            ) {
                val lastResponseOrdinal = tasksForRequest.maxOfOrNull { task ->
                    (task.request.input as? TutorRespondInput)?.responseOrdinal ?: 0
                } ?: 0
                val responseOrdinal = lastResponseOrdinal + 1
                val priorMessages: List<TutorChatHistoryEntry> = tutorChatHistory(
                    tasksForRequest,
                    answerExposureKeys = exposureKeysForRequest,
                )
                val visibleContext = visibleTutorContextMarkdown(
                    visiblePlan,
                    currentResponseForRequest,
                    answerWasExposed = answerWasExposed,
                )
                val attempt = tasksForRequest.count { task ->
                    (task.request.input as? TutorRespondInput)?.responseOrdinal == responseOrdinal
                }
                val requestId = tutorRespondRequestId(
                    question = question,
                    provider = providerForExecution,
                    responseOrdinal = responseOrdinal,
                    cycleOrdinal = responseCycleOrdinal,
                    turnOrdinal = responseTurnOrdinal,
                    studentMessage = exactMessage,
                    selectedChoiceId = visibleChoice?.id,
                    visibleTutorContextMarkdown = visibleContext,
                    priorMessages = priorMessages,
                    requestedMove = requestedMove,
                    explanationMode = requestMode,
                    attempt = attempt,
                )
                val occurredAt = maxOf(
                    clock(),
                    tasksForRequest.maxOfOrNull { it.createdAtEpochMillis + 1 } ?: 0L,
                )
                buildTutorRespondRequest(
                    question = question,
                    profile = profile,
                    provider = providerForExecution,
                    requestId = requestId,
                    occurredAtEpochMillis = occurredAt,
                    approvedAtEpochMillis = externalApprovedAt ?: occurredAt,
                    responseOrdinal = responseOrdinal,
                    cycleOrdinal = responseCycleOrdinal,
                    turnOrdinal = responseTurnOrdinal,
                    studentMessage = exactMessage,
                    selectedChoice = visibleChoice,
                    visibleTutorContextMarkdown = visibleContext,
                    priorMessages = priorMessages,
                    requestedMove = requestedMove,
                    explanationMode = requestMode,
                )
            }
        }
        startResponse()
    }

    fun executeTutorResponse(
        message: String,
        requestedMove: TutorMoveType? = null,
        clearDraftOnPersist: Boolean = false,
    ) = executeTutorResponse(
        response = TutorResponseMessage.freeResponse(message),
        requestedMove = requestedMove,
        clearDraftOnPersist = clearDraftOnPersist,
    )

    fun pendingTutorResponseMessage(
        pending: PendingTutorEgressAction.NewResponse,
    ): TutorResponseMessage? {
        val selectedChoiceId = pending.selectedChoiceId
            ?: return TutorResponseMessage.freeResponse(pending.message)
        val sourceRequestId = pending.choiceSourceRequestId ?: return null
        val directive = visibleTutorChoiceDirective(timeline, sourceRequestId)
            ?: return null
        return runCatching {
            TutorResponseMessage.directiveChoice(
                directive = directive,
                selectedChoiceId = selectedChoiceId,
                messageMarkdown = pending.message,
                sourceRequestId = sourceRequestId,
            )
        }.getOrNull()
    }

    fun retryTutorResponse(task: ModelTaskSnapshot) {
        if (!task.isRebuildableTutorRequest()) return
        if (!task.canRetryTutorResponseFor(effectiveExplanationMode)) return
        val exactPendingRetry = (pendingEgressState.action as? PendingTutorEgressAction.RetryResponse)
            ?.takeIf { it.requestId == task.request.requestId }
        when (val pendingAction = pendingEgressState.action) {
            null -> Unit
            is PendingTutorEgressAction.RetryResponse -> if (
                pendingAction.requestId != task.request.requestId
            ) {
                return
            }
            is PendingTutorEgressAction.Plan,
            is PendingTutorEgressAction.NewResponse,
            is PendingTutorEgressAction.RetryVisual,
            -> return
        }
        val providerForExecution = currentProvider?.takeIf { candidate ->
            candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                candidate.supports(ModelTaskKind.TUTOR_RESPOND)
        } ?: return
        val request = when (providerForExecution.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> {
                if (
                    externalEgressLease?.approvedAtFor(
                        question = question,
                        provider = providerForExecution,
                        taskKind = ModelTaskKind.TUTOR_RESPOND,
                        nowEpochMillis = clock(),
                    ) == null
                ) {
                    externalEgressLease = null
                    forceResponseDisclosure = true
                    pendingEgressState = PendingTutorEgressState(
                        PendingTutorEgressAction.RetryResponse(task.request.requestId),
                    )
                    return
                }
                task.request
            }
            ModelExecutionLocation.LOCAL_NO_EGRESS -> if (
                task.request.egressManifest == null && task.matchesTutorProvider(providerForExecution)
            ) {
                val nextAttempt = task.nextLocalTutorResponseRetryAttempt() ?: return
                task.request.copy(
                    requestId = task.request.requestId.substringBeforeLast(':') + ":$nextAttempt",
                    occurredAtEpochMillis = maxOf(clock(), task.updatedAtEpochMillis + 1),
                )
            } else if (exactPendingRetry != null) {
                val currentAttempt =
                    task.request.requestId.substringAfterLast(':').toIntOrNull() ?: 0
                if (currentAttempt >= 1) return
                task.request.copy(
                    requestId = task.request.requestId.substringBeforeLast(':') + ":1",
                    occurredAtEpochMillis = maxOf(clock(), task.updatedAtEpochMillis + 1),
                    egressManifest = null,
                )
            } else {
                return
            }
            ModelExecutionLocation.UNAVAILABLE -> return
        }
        if (activeMessage?.activityVisible == true) return
        collectTutorRespondRequest(
            request = request,
            clearDraftOnPersist = false,
            allowExternalEnvelopeForLocalRecovery = exactPendingRetry != null,
            clearPendingActionOnPersist = exactPendingRetry,
            retryConsumed = true,
        )
    }

    val recoverableRespondTask = latestRespondTasks.lastOrNull { task ->
        task.isRebuildableTutorRequest() &&
            currentProvider?.let(task::matchesTutorProvider) == true &&
            task.isPendingTutorRespondFor(effectiveExplanationMode)
    }
    LaunchedEffect(
        recoverableRespondTask?.request?.requestId,
        respondAuthorized,
        responseFreshApprovalTask?.request?.requestId,
    ) {
        if (respondAuthorized && responseFreshApprovalTask == null) {
            recoverableRespondTask
                ?.takeUnless {
                    it.request.requestId == activeMessage?.identity?.requestId
                }
                ?.let { task ->
                    collectTutorRespondRequest(
                        request = task.request,
                        clearDraftOnPersist = false,
                        retryConsumed = task.status == ModelTaskStatus.RETRYABLE_FAILURE,
                    )
                }
        }
    }
    val pendingLocalRetryTask = (pendingEgressState.action as? PendingTutorEgressAction.RetryResponse)
        ?.let { pending ->
            latestRespondTasks.firstOrNull { it.request.requestId == pending.requestId }
        }
    LaunchedEffect(
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
        currentProvider?.executionLocation,
        currentPlanOutput,
        pendingEgressState.action,
        pendingLocalRetryTask?.request?.requestId,
    ) {
        if (
            currentProvider?.executionLocation != ModelExecutionLocation.LOCAL_NO_EGRESS ||
            !respondSupported || currentPlanOutput == null
        ) {
            return@LaunchedEffect
        }
        when (val pendingAction = pendingEgressState.action) {
            is PendingTutorEgressAction.NewResponse -> {
                val response = pendingTutorResponseMessage(pendingAction)
                if (response == null) {
                    pendingEgressState = PendingTutorEgressState()
                    interactionError = "互动内容已更新，请根据当前提示重新选择。"
                } else {
                    executeTutorResponse(
                        response = response,
                        requestedMove = pendingAction.requestedMove,
                        clearDraftOnPersist = pendingAction.clearDraftOnPersist,
                    )
                }
            }
            is PendingTutorEgressAction.RetryResponse ->
                pendingLocalRetryTask?.let(::retryTutorResponse)
            is PendingTutorEgressAction.Plan,
            is PendingTutorEgressAction.RetryVisual,
            null,
            -> Unit
        }
    }

    fun revealCurrentSolution(afterPreviewed: () -> Unit = {}) {
        if (
            interactionBusy ||
            pendingInteractionIsCurrentlyBlocked() ||
            responseActionAwaitingAuthorization
        ) {
            return
        }
        if (currentResponse?.solutionRevealed != true) {
            val previewKey = observedTask.toPlanSolutionPreviewKey() ?: return
            planSolutionPreviewKeys = planSolutionPreviewKeys + previewKey
        }
        interactionError = null
        afterPreviewed()
    }
    LaunchedEffect(
        effectiveExplanationMode,
        observedTask.request.requestId,
    ) {
        if (effectiveExplanationMode == TutorExplanationMode.DIRECT) {
            observedTask.toPlanSolutionPreviewKey()?.let { key ->
                planSolutionPreviewKeys = planSolutionPreviewKeys + key
            }
        }
    }
    LaunchedEffect(
        question.sessionId,
        currentCycle,
        currentHistory,
        currentInput.priorConversationMemory,
        currentInput.priorCycleStudentMessages,
        nextTurnExists,
        executablePlanProvider?.providerConfigurationVersion,
        externalEgressLease?.approvedAtEpochMillis,
        responseActionAwaitingAuthorization,
        explanationMode,
    ) {
        if (
            effectiveExplanationMode == TutorExplanationMode.GUIDED &&
            currentHistory.isNotEmpty() &&
            guidanceState.questionsAsked < TutorGuidancePolicy.MAX_QUESTIONS &&
            !nextTurnExists &&
            !responseActionAwaitingAuthorization
        ) {
            executeTurn(
                currentInput.cycleOrdinal,
                currentInput.priorConversationMemory,
                currentInput.priorCycleStudentMessages,
                currentHistory,
            )
        }
    }
    fun retryCurrentPlan() {
        if (executablePlanProvider == null) {
            onOpenModelSettings()
        } else {
            executeTurn(
                currentInput.cycleOrdinal,
                currentInput.priorConversationMemory,
                currentInput.priorCycleStudentMessages,
                currentInput.priorTurns,
            )
        }
    }

    fun submitCurrentChoice(choiceId: String) {
        val output = observedTask.output as? TutorPlanOutput
        val item = output?.plan?.diagnosticItem
        val evaluation = item?.evaluateChoice(choiceId)
        val evidence = guidanceState.authorizeEvidence(observedTask.request.requestId)
        if (
            effectiveExplanationMode != TutorExplanationMode.GUIDED ||
            !evidence.mayWriteLearningEvidence ||
            output == null || item == null || evaluation == null || interactionBusy ||
            !choiceLearningMemoryReady ||
            pendingInteractionIsCurrentlyBlocked() ||
            responseActionAwaitingAuthorization
        ) {
            return
        }
        val exactIdentity = choiceLearningMemoryIdentity
        val exactPlanTask = observedTask
        val exactGuidanceState = guidanceState
        val command = RecordTutorChoiceCommand(
            sessionId = question.sessionId,
            questionDocumentId = question.questionDocument.document.id,
            revisionNumber = question.revisionNumber,
            cycleOrdinal = currentInput.cycleOrdinal,
            turnOrdinal = currentInput.turnOrdinal,
            diagnosticStemMarkdown = item.stemMarkdown,
            selectedChoiceId = evaluation.choice.id,
            selectedChoiceMarkdown = evaluation.choice.markdown,
            selectionWasCorrect = evaluation.isCorrect,
            feedbackMarkdown = requireNotNull(evaluation.choice.feedbackMarkdown),
            occurredAtEpochMillis = clock(),
            evidenceRequestId = observedTask.request.requestId,
        )
        interactionError = null
        interactionBusy = true
        pendingEvidenceJob = scope.launch {
            try {
                val controller = choiceLearningMemoryController
                if (controller == null) {
                    interactions.recordChoice(command)
                } else {
                    val result = controller.recordPreparedChoice(
                        planTask = exactPlanTask,
                        guidanceState = exactGuidanceState,
                        modeVersion = exactIdentity.modeVersion,
                        command = command,
                        isStillCurrent = {
                            latestChoiceLearningMemoryIdentity.value == exactIdentity &&
                                latestChoiceLearningMemoryMode.value ==
                                TutorExplanationMode.GUIDED
                        },
                        persistChoice = interactions::recordChoice,
                    )
                    check(
                        result is CapturedTutorChoiceLearningMemoryState.Submitted ||
                            result is CapturedTutorChoiceLearningMemoryState.Cancelled,
                    ) {
                        "Captured-choice learning memory did not reach a safe state"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                interactionError = "这个选择暂时没有保存，请重试后再继续。"
            } finally {
                interactionBusy = false
                pendingEvidenceJob = null
            }
        }
    }

    fun submitVisualTargetEvidence(
        requestId: String,
        anchor: TutorVisualTurnAnchor,
        directive: TutorInteractionDirective.VisualTarget,
        hitProof: TutorVisualHitProof,
        inlineScene: TutorVisualScene? = null,
    ) {
        val ready = inlineScene?.let { scene ->
            inlineTutorVisualResolution(scene, requestId)
        } ?: (resolvedVisualStates[anchor] as? TutorVisualResolution.Ready)
            ?: return
        val readyScene = ready.scene
        val expectedPresentation = ready.hitPresentation(requestId) ?: return
        if (
            hitProof.presentation != expectedPresentation ||
            !canSubmitTutorVisualTarget(
                mode = effectiveExplanationMode,
                pendingEvidenceRequestId = guidanceState.pendingEvidenceRequestId,
                requestId = requestId,
                visualReady = true,
                expectedTargetId = directive.targetId,
                hitTargetId = hitProof.selectedTargetId,
                sceneReported = readyScene.sceneId in reportedVisualSceneIds,
            ) ||
            !guidanceState.authorizeEvidence(requestId)
                .mayWriteLearningEvidence ||
            interactionBusy ||
            pendingInteractionIsCurrentlyBlocked() ||
            responseActionAwaitingAuthorization
        ) {
            return
        }
        interactionError = null
        interactionBusy = true
        pendingEvidenceJob = scope.launch {
            try {
                interactions.recordVisualTargetEvidence(
                    RecordTutorVisualTargetEvidenceCommand(
                        sessionId = question.sessionId,
                        questionDocumentId = question.questionDocument.document.id,
                        revisionNumber = question.revisionNumber,
                        anchor = anchor,
                        modelTaskRequestId = requestId,
                        hitProof = hitProof,
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                interactionError = "这个选择暂时没有保存，请重试后再继续。"
            } finally {
                interactionBusy = false
                pendingEvidenceJob = null
            }
        }
    }

    fun submitCurrentVisualTarget(hitProof: TutorVisualHitProof) {
        val output = observedTask.output as? TutorPlanOutput ?: return
        val directive = output.plan.interactionDirective as?
            TutorInteractionDirective.VisualTarget ?: return
        submitVisualTargetEvidence(
            requestId = observedTask.request.requestId,
            anchor = TutorVisualTurnAnchor(
                surface = TutorVisualTurnSurface.PLAN,
                cycleOrdinal = output.cycleOrdinal,
                turnOrdinal = output.turnOrdinal,
            ),
            directive = directive,
            hitProof = hitProof,
            inlineScene = output.plan.visualScene,
        )
    }

    fun continueCurrentTurn(requestedMove: TutorMoveType) {
        if (
            effectiveExplanationMode != TutorExplanationMode.GUIDED ||
            interactionBusy ||
            pendingInteractionIsCurrentlyBlocked() ||
            responseActionAwaitingAuthorization
        ) {
            return
        }
        if (executablePlanProvider == null) {
            onOpenModelSettings()
            return
        }
        interactionBusy = true
        scope.launch {
            interactionError = null
            try {
                val movedResponse = interactions.recordMove(
                    RecordTutorMoveCommand(
                        sessionId = question.sessionId,
                        questionDocumentId = question.questionDocument.document.id,
                        revisionNumber = question.revisionNumber,
                        cycleOrdinal = currentInput.cycleOrdinal,
                        turnOrdinal = currentInput.turnOrdinal,
                        requestedMove = requestedMove,
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
                val nextHistory = tutorContiguousHistory(
                    planTasks = currentCycleTasks,
                    respondTasks = tutorRespondTasks,
                    responses = currentCycleResponses
                        .filterNot { it.turnOrdinal == movedResponse.turnOrdinal }
                        .plus(movedResponse),
                    visualTargetEvidence = exactVisualTargetEvidence,
                )
                if (nextHistory.any { it.turnOrdinal == movedResponse.turnOrdinal }) {
                    val nextState = replayTutorGuidance(
                        problem = guidanceProblem,
                        requestedMode = explanationMode,
                        answerWasExposed = answerExposureKeys.isNotEmpty(),
                        events = if (movedResponse.hasChoicePayload) {
                            guidanceEvents + TutorGuidanceEvent.Evidence(
                                requestId = observedTask.request.requestId,
                                selectionWasCorrect =
                                    movedResponse.selectionWasCorrect == true,
                            )
                        } else {
                            guidanceEvents
                        },
                    )
                    if (nextState.mode == TutorExplanationMode.GUIDED) {
                        executeTurn(
                            currentInput.cycleOrdinal,
                            currentInput.priorConversationMemory,
                            currentInput.priorCycleStudentMessages,
                            nextHistory,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                interactionError = "下一种讲法没有启动，请再试一次。"
            } finally {
                interactionBusy = false
            }
        }
    }

    fun restartCurrentCycle() {
        if (responseActionAwaitingAuthorization) return
        if (executablePlanProvider == null) {
            onOpenModelSettings()
        } else {
            tutorResponses.toTutorConversationMemory(
                answerExposureKeys = answerExposureKeys,
                visualTargetEvidence = exactVisualTargetEvidence,
            )?.let { memory ->
                executeTurn(
                    currentCycle + 1,
                    memory,
                    priorCycleStudentMessages(tutorRespondTasks),
                    emptyList(),
                )
            }
        }
    }

    val conversationListState = rememberLazyListState()
    @Composable
    fun solutionBottomModifier(stableId: String): Modifier {
        val anchorToken = remember(stableId) { Any() }
        DisposableEffect(solutionExposureTracker, stableId, anchorToken) {
            onDispose {
                solutionExposureTracker.removeSolutionBottomBounds(stableId, anchorToken)
            }
        }
        return Modifier
            .testTag("tutor_solution_bottom_$stableId")
            .onGloballyPositioned { coordinates ->
                solutionExposureTracker.updateSolutionBottomBounds(
                    stableId = stableId,
                    token = anchorToken,
                    bounds = coordinates.boundsInWindow(clipBounds = false),
                )
            }
    }
    val activeRequestId = activeMessage?.identity?.requestId
    val activeReplyExists = timeline.any { timelineItem ->
        timelineItem is TutorConversationTimelineItem.Reply &&
            timelineItem.task.request.requestId == activeRequestId
    }
    val tailId = timeline.lastOrNull()?.stableId.takeIf { activeMessage == null }
    val autoScrollVersion = timeline.map { timelineItem ->
        when (timelineItem) {
            is TutorConversationTimelineItem.Plan -> listOf(
                timelineItem.stableId,
                timelineItem.task.stateVersion,
                timelineItem.task.status,
            )
            is TutorConversationTimelineItem.ChoiceFeedback -> listOf(
                timelineItem.stableId,
                timelineItem.response.updatedAtEpochMillis,
                timelineItem.response.requestedMove,
                timelineItem.response.solutionRevealed,
            )
            is TutorConversationTimelineItem.Reply -> listOf(
                timelineItem.stableId,
                timelineItem.task.stateVersion,
                timelineItem.task.status,
            )
        }
    }
    val visualInsertionToken = resolvedVisualStates.values
        .filterIsInstance<TutorVisualResolution.Ready>()
        .map { ready -> ready.scene.sceneId }
        .sorted()
    val composerContent: (@Composable () -> Unit)? = if (
        respondSupported && currentPlanOutput != null && respondAuthorized &&
        !responseActionAwaitingAuthorization
    ) {
        {
            TutorChatComposer(
                value = chatDraft,
                enabled = !chatSending && !interactionBusy && !pendingInteractionBlocked,
                sending = chatSending,
                explanationMode = explanationMode,
                onExplanationModeChange = requestExplanationModeChange,
                onCameraAttachment = onCameraAttachment,
                onGalleryAttachment = onGalleryAttachment,
                onLibraryAttachment = onLibraryAttachment,
                onValueChange = {
                    chatDraft = it
                    chatStartError = null
                },
                onSend = {
                    executeTutorResponse(
                        message = chatDraft,
                        clearDraftOnPersist = true,
                    )
                },
            )
            chatStartError?.let { message ->
                Text(
                    text = message,
                    color = ErrorWarm,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .testTag("tutor_chat_start_error"),
                )
            }
        }
    } else if (currentPlanOutput != null) {
        {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TutorGuidanceModeControl(
                    mode = explanationMode,
                    onModeChange = requestExplanationModeChange,
                )
            }
        }
    } else {
        null
    }
    val pendingPlanAction = pendingEgressState.action as? PendingTutorEgressAction.Plan
    val showActiveReply = activeMessage != null && !activeReplyExists
    val showPlanRecoveryDisclosure =
        (planFreshApprovalTask != null || pendingPlanAction != null) &&
            executablePlanProvider?.executionLocation ==
            ModelExecutionLocation.EXTERNAL_PROVIDER
    val showVisualRetryDisclosure =
        pendingVisualRetry != null &&
            currentProvider?.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER
    val pendingResponseRetryIsRebuildable =
        (pendingEgressState.action as? PendingTutorEgressAction.RetryResponse)?.let { pending ->
            latestRespondTasks.any { task ->
                task.request.requestId == pending.requestId &&
                    task.isRebuildableTutorRequest()
            }
        } ?: true
    val showRespondDisclosure =
        respondSupported && currentPlanOutput != null && !respondAuthorized &&
            planFreshApprovalTask == null && pendingVisualRetry == null &&
            pendingResponseRetryIsRebuildable
    val showChatStartError = composerContent == null && chatStartError != null
    val expectedConversationItemCount =
        2 +
            timeline.size +
            (if (showActiveReply) 1 else 0) +
            (if (showPlanRecoveryDisclosure) 1 else 0) +
            (if (showVisualRetryDisclosure) 1 else 0) +
            (if (showRespondDisclosure) 1 else 0) +
            (if (showChatStartError) 1 else 0)

    TutorConversationFrame(
        header = headerContent,
        autoScrollVersion = listOf(
            autoScrollVersion,
            respondAuthorized,
            chatStartError,
            activeMessage?.renderVersion,
        ),
        expectedItemCount = expectedConversationItemCount,
        forceFollowToken = activeMessage?.turnVersion,
        blockAutoFollowToken = listOf(
            solutionExposureTracker.blockAutoFollowToken,
            visualInsertionToken,
        ),
        modifier = modifier,
        listState = conversationListState,
        listViewportModifier = Modifier.onGloballyPositioned { coordinates ->
            solutionExposureTracker.updateViewportBounds(
                coordinates.boundsInWindow(clipBounds = false),
            )
        },
        composer = composerContent,
    ) {
        item("tutor_question_context") {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = leadingContent,
            )
        }
        items(timeline, key = TutorConversationTimelineItem::stableId) { timelineItem ->
            val isTail = timelineItem.stableId == tailId
            when (timelineItem) {
                is TutorConversationTimelineItem.Plan -> {
                    val taskInput = timelineItem.task.request.input as TutorPlanInput
                    val response = conversationProjection.responsesByTurn[
                        TutorTurnKey(taskInput.cycleOrdinal, taskInput.turnOrdinal)
                    ]
                    val isCurrentTurn = taskInput.cycleOrdinal == currentInput.cycleOrdinal &&
                        taskInput.turnOrdinal == currentInput.turnOrdinal
                    val executionMatches = currentProvider?.let(
                        timelineItem.task::matchesTutorProvider,
                    ) == true
                    val planOutput = timelineItem.task.output as? TutorPlanOutput
                    val visualAnchor = planOutput?.let { output ->
                        TutorVisualTurnAnchor(
                            surface = TutorVisualTurnSurface.PLAN,
                            cycleOrdinal = output.cycleOrdinal,
                            turnOrdinal = output.turnOrdinal,
                        )
                    }
                    val resolvedVisual = visualAnchor
                        ?.let(resolvedVisualStates::get)
                        ?: TutorVisualResolution.Hidden
                    TutorTaskContent(
                        task = timelineItem.task,
                        resolvedVisual = resolvedVisual,
                        visualPresentationMode =
                            tutorPlanVisualPresentationMode(isCurrentTurn),
                        visualOriginalAvailable = visualOriginalAvailable,
                        response = response,
                        solutionRevealPreviewed = timelineItem.task.toPlanSolutionPreviewKey()
                            ?.let { it in planSolutionPreviewKeys } == true,
                        awaitingContinuation = planFreshApprovalTask?.request?.requestId ==
                            timelineItem.task.request.requestId,
                        interactionEnabled = isTail && isCurrentTurn &&
                            planFreshApprovalTask == null &&
                            !pendingInteractionBlocked &&
                            !responseActionAwaitingAuthorization,
                        executionMatchesCurrentProvider = executionMatches,
                        splitChoiceFeedback = true,
                        interactionBusy = interactionBusy || pendingInteractionBlocked,
                        interactionError = interactionError.takeIf { isTail && isCurrentTurn },
                        onRetry = ::retryCurrentPlan,
                        onRetryVisual = {
                            visualAnchor?.let(::requestVisualRetry)
                        },
                        onVisualTargetHit = ::submitCurrentVisualTarget,
                        onSubmitChoice = ::submitCurrentChoice,
                        onDirectiveResponse = { response ->
                            executeTutorResponse(response)
                        },
                        onRequestHint = if (
                            effectiveExplanationMode == TutorExplanationMode.GUIDED &&
                            guidanceState.hintsUsed < TutorGuidancePolicy.MAX_HINTS &&
                            respondSupported && respondAuthorized && !chatSending &&
                            !pendingInteractionBlocked &&
                            !responseActionAwaitingAuthorization
                        ) {
                            {
                                executeTutorResponse(
                                    message = GUIDED_HINT_MESSAGE,
                                )
                            }
                        } else {
                            null
                        },
                        onContinue = ::continueCurrentTurn,
                        onRevealSolution = { revealCurrentSolution() },
                        onRestartCycle = ::restartCurrentCycle,
                        explanationMode = effectiveExplanationMode,
                        onOpenModelSettings = onOpenModelSettings,
                        onOpenVisualOriginal = {
                            val browse = TutorGuidancePolicy.evaluate(
                                guidanceState,
                                TutorGuidanceRequest.visualBrowse(
                                    "visual-open:${timelineItem.task.request.requestId}",
                                    guidanceProblem,
                                ),
                            )
                            if (browse.outcome == TutorGuidanceOutcome.READ_ONLY) {
                                onOpenVisualOriginal()
                            }
                        },
                        onReportVisualIncorrect = ::reportVisualIncorrect,
                        solutionBottomModifier = solutionBottomModifier(timelineItem.stableId),
                    )
                }

                is TutorConversationTimelineItem.ChoiceFeedback -> {
                    val response = timelineItem.response
                    val output = timelineItem.planTask?.output as? TutorPlanOutput
                    val isCurrentTurn = response.cycleOrdinal == currentInput.cycleOrdinal &&
                        response.turnOrdinal == currentInput.turnOrdinal
                    if (output != null) {
                        TutorChoiceFeedbackContent(
                            output = output,
                            response = response,
                            solutionRevealPreviewed = timelineItem.planTask
                                .toPlanSolutionPreviewKey()
                                ?.let { it in planSolutionPreviewKeys } == true,
                            showDirectExplanation = isCurrentTurn &&
                                effectiveExplanationMode == TutorExplanationMode.DIRECT,
                            interactionEnabled = isTail && isCurrentTurn &&
                                !pendingInteractionBlocked &&
                                !responseActionAwaitingAuthorization,
                            interactionBusy = interactionBusy || pendingInteractionBlocked,
                            interactionError = interactionError.takeIf { isTail && isCurrentTurn },
                            onContinue = ::continueCurrentTurn,
                            onRevealSolution = { revealCurrentSolution() },
                            onRestartCycle = ::restartCurrentCycle,
                            solutionBottomModifier = solutionBottomModifier(timelineItem.stableId),
                        )
                    } else {
                        TutorStoredChoiceFeedback(response)
                    }
                }

                is TutorConversationTimelineItem.Reply -> {
                    val executionMatches = currentProvider?.let(
                        timelineItem.task::matchesTutorProvider,
                    ) == true
                    val taskAllowsInteraction =
                        timelineItem.task.status == ModelTaskStatus.SUCCEEDED ||
                            (
                                timelineItem.task.isRebuildableTutorRequest() &&
                                    timelineItem.task.canRetryTutorResponseFor(
                                        effectiveExplanationMode,
                                    )
                                )
                    val opensLocalSettings =
                        timelineItem.task.failure?.code?.requiresModelSettings() == true
                    val recoveryEnabled = isTail && executionMatches &&
                        timelineItem.task.isRebuildableTutorRequest() &&
                        !chatSending && !interactionBusy &&
                        (opensLocalSettings ||
                            (responseFreshApprovalTask == null && respondAuthorized))
                    TutorChatExchange(
                        task = timelineItem.task,
                        activeMessage = activeMessage?.takeIf {
                            it.identity?.requestId == timelineItem.task.request.requestId
                        },
                        resolvedVisual = (
                            timelineItem.task.request.input as? TutorRespondInput
                            )?.let { input ->
                            resolvedVisualStates[
                                TutorVisualTurnAnchor(
                                    surface = TutorVisualTurnSurface.FOLLOW_UP,
                                    cycleOrdinal = input.cycleOrdinal,
                                    turnOrdinal = input.turnOrdinal,
                                    responseOrdinal = input.responseOrdinal,
                                )
                            ]
                        } ?: TutorVisualResolution.Hidden,
                        visualPresentationMode = tutorVisualPresentationMode(isCurrent = isTail),
                        visualOriginalAvailable = visualOriginalAvailable,
                        awaitingContinuation = !respondAuthorized &&
                            timelineItem.task.status.isTutorExecutionPending(),
                        interactionEnabled = isTail && taskAllowsInteraction &&
                            executionMatches && respondAuthorized &&
                            !chatSending && !interactionBusy &&
                            !pendingInteractionBlocked &&
                            !responseActionAwaitingAuthorization,
                        recoveryEnabled = recoveryEnabled &&
                            !responseActionAwaitingAuthorization,
                        executionMatchesCurrentProvider = executionMatches,
                        onRetry = { retryTutorResponse(timelineItem.task) },
                        onRetryVisual = {
                            (timelineItem.task.request.input as? TutorRespondInput)?.let { input ->
                                requestVisualRetry(
                                    TutorVisualTurnAnchor(
                                        surface = TutorVisualTurnSurface.FOLLOW_UP,
                                        cycleOrdinal = input.cycleOrdinal,
                                        turnOrdinal = input.turnOrdinal,
                                        responseOrdinal = input.responseOrdinal,
                                    ),
                                )
                            }
                        },
                        onVisualTargetHit = { hitProof ->
                            (timelineItem.task.output as? TutorRespondOutput)?.let { output ->
                                (output.interactionDirective as? TutorInteractionDirective.VisualTarget)
                                    ?.let { directive ->
                                    submitVisualTargetEvidence(
                                        requestId = timelineItem.task.request.requestId,
                                        anchor = TutorVisualTurnAnchor(
                                            surface = TutorVisualTurnSurface.FOLLOW_UP,
                                            cycleOrdinal = output.cycleOrdinal,
                                            turnOrdinal = output.turnOrdinal,
                                            responseOrdinal = output.responseOrdinal,
                                        ),
                                        directive = directive,
                                        hitProof = hitProof,
                                        inlineScene = output.visualScene,
                                    )
                                }
                            }
                        },
                        onOpenModelSettings = onOpenModelSettings,
                        onOpenVisualOriginal = {
                            openVisualReadOnly(
                                "visual-reply:${timelineItem.task.request.requestId}",
                            )
                        },
                        onReportVisualIncorrect = ::reportVisualIncorrect,
                        onMove = { move ->
                            executeTutorResponse(
                                message = move.label,
                                requestedMove = move.type,
                            )
                        },
                        onRevealSolution = { move ->
                            revealCurrentSolution {
                                executeTutorResponse(
                                    message = move.label,
                                    requestedMove = TutorMoveType.REVEAL_SOLUTION,
                                )
                            }
                        },
                        explanationMode = effectiveExplanationMode,
                        onDirectiveResponse = ::executeTutorResponse,
                        localIntentContent = { input, output ->
                            TutorLocalIntentPanel(
                                output = output,
                                studentMessage = input.studentMessage,
                                catalogEntries = catalogEntries,
                                profile = profile,
                                onRequestSave = onRequestSave,
                                onRequestEnd = onRequestEnd,
                                onOpenMistakeNotebook = onOpenMistakeNotebook,
                                onOpenProfile = onOpenProfile,
                            )
                        },
                        assistantBottomModifier = solutionBottomModifier(timelineItem.stableId),
                    )
                }
            }
        }
        if (showActiveReply) {
            item("tutor_active_reply_${activeMessage.turnVersion}") {
                TutorActiveChatExchange(
                    message = activeMessage,
                    onRetry = { activeStreamOwner.retry() },
                )
            }
        }
        if (showPlanRecoveryDisclosure) {
            item("tutor_plan_recovery_disclosure") {
                TutorDisclosureCard(
                    provider = requireNotNull(executablePlanProvider),
                    title = "继续讲这道题",
                    actionText = "继续讲题",
                    actionContentDescription = "继续讲解当前题",
                    onApprove = {
                        if (planFreshApprovalTask != null && planRecoveryRequestInFlight != null) {
                            return@TutorDisclosureCard
                        }
                        val approvedAt = maxOf(
                            clock(),
                            (planFreshApprovalTask?.updatedAtEpochMillis ?: 0L) + 1,
                        )
                        val providerForRecovery = requireNotNull(executablePlanProvider)
                        grantExternalEgressLease(providerForRecovery, approvedAt)
                        if (planFreshApprovalTask != null) {
                            val canReuseExactRequest =
                                !planFreshApprovalTask.requiresFreshTutorApproval(
                                    providerForRecovery,
                                ) &&
                                    planFreshApprovalTask.coversCurrentTutorDisclosure(
                                        providerForRecovery,
                                        ModelTaskKind.TUTOR_PLAN,
                                    ) &&
                                    planFreshApprovalTask.request.egressManifest
                                        ?.isModelEgressApprovalFresh(approvedAt) == true
                            val request = if (canReuseExactRequest) {
                                planFreshApprovalTask.request
                            } else {
                                rebuildTutorRequestAfterApprovalOrNull(
                                    failedTask = planFreshApprovalTask,
                                    provider = providerForRecovery,
                                    approvedAtEpochMillis = approvedAt,
                                ) ?: return@TutorDisclosureCard
                            }
                            planRecoveryRequestInFlight = request.requestId
                            scope.launch {
                                try {
                                    modelTasks.execute(request).collect()
                                } finally {
                                    planRecoveryRequestInFlight = null
                                }
                            }
                        } else if (pendingPlanAction != null) {
                            executeTurn(
                                cycleOrdinal = pendingPlanAction.cycleOrdinal,
                                priorConversationMemory =
                                pendingPlanAction.priorConversationMemory,
                                priorCycleStudentMessages =
                                pendingPlanAction.priorCycleStudentMessages,
                                priorTurns = pendingPlanAction.priorTurns,
                            )
                        }
                    },
                )
            }
        }
        if (showVisualRetryDisclosure) {
            item("tutor_visual_retry_disclosure") {
                TutorDisclosureCard(
                    provider = requireNotNull(currentProvider),
                    title = "重试图解",
                    actionText = "允许并重试",
                    actionContentDescription = "允许发送当前题目图片并重试图解",
                    onApprove = {
                        val retry = pendingEgressState.action
                            as? PendingTutorEgressAction.RetryVisual
                            ?: return@TutorDisclosureCard
                        if (retry.approvedAtEpochMillis != null) {
                            return@TutorDisclosureCard
                        }
                        val providerForRetry = requireNotNull(currentProvider)
                        val fallback = resolvedVisualStates[retry.anchor]
                            as? TutorVisualResolution.Fallback
                        val failedTask = fallback?.failedTask
                        val retryIsCurrent = fallback?.canRetry == true &&
                            fallback.retrySemanticRequestId?.let { semanticRequestId ->
                                retry.matches(providerForRetry, semanticRequestId)
                            } == true &&
                            retry.matchesFailedTask(failedTask) &&
                            (failedTask == null ||
                                failedTask.matchesTutorProvider(providerForRetry)) &&
                            providerForRetry.supports(retry.taskKind)
                        if (!retryIsCurrent) {
                            clearPendingVisualRetry(retry)
                            return@TutorDisclosureCard
                        }
                        val approvedAt = maxOf(
                            clock(),
                            failedTask?.updatedAtEpochMillis?.plus(1) ?: 0L,
                        )
                        grantExternalEgressLease(providerForRetry, approvedAt)
                        if (pendingEgressState.action == retry) {
                            pendingEgressState = PendingTutorEgressState(
                                retry.copy(approvedAtEpochMillis = approvedAt),
                            )
                        }
                    },
                )
            }
        }
        if (showRespondDisclosure) {
            item("tutor_respond_disclosure") {
                TutorRespondDisclosureCard(
                    provider = requireNotNull(currentProvider),
                    onApprove = {
                        val pendingResponseAction = pendingEgressState.action
                        val pendingRetryTask =
                            (pendingResponseAction as? PendingTutorEgressAction.RetryResponse)
                                ?.let { pending ->
                                    latestRespondTasks.firstOrNull {
                                        it.request.requestId == pending.requestId &&
                                            it.isRebuildableTutorRequest() &&
                                            it.canRetryTutorResponseFor(effectiveExplanationMode)
                                    }
                                }
                        if (
                            pendingResponseAction is PendingTutorEgressAction.RetryResponse &&
                            pendingRetryTask == null
                        ) {
                            return@TutorRespondDisclosureCard
                        }
                        val approvedAt = clock()
                        grantExternalEgressLease(requireNotNull(currentProvider), approvedAt)
                        forceResponseDisclosure = false
                        if (pendingResponseAction is PendingTutorEgressAction.NewResponse) {
                            val response = pendingTutorResponseMessage(pendingResponseAction)
                            if (response == null) {
                                pendingEgressState = PendingTutorEgressState()
                                interactionError = "互动内容已更新，请根据当前提示重新选择。"
                                return@TutorRespondDisclosureCard
                            }
                            run {
                                executeTutorResponse(
                                    response = response,
                                    requestedMove = pendingResponseAction.requestedMove,
                                    clearDraftOnPersist =
                                    pendingResponseAction.clearDraftOnPersist,
                                )
                            }
                            return@TutorRespondDisclosureCard
                        }
                        val taskToRecover = when (pendingResponseAction) {
                            is PendingTutorEgressAction.RetryResponse -> pendingRetryTask
                            else -> responseFreshApprovalTask ?: recoverableRespondTask ?: 
                                latestRespondTasks.lastOrNull { task ->
                                    task.isRebuildableTutorRequest() &&
                                        task.canRetryTutorResponseFor(effectiveExplanationMode)
                                }
                        }
                        taskToRecover?.let { failedTask ->
                            val recoveryApprovedAt = maxOf(
                                approvedAt,
                                failedTask.updatedAtEpochMillis + 1,
                            )
                            val providerForRecovery = requireNotNull(currentProvider)
                            val canReuseExactRequest =
                                !failedTask.requiresFreshTutorApproval(providerForRecovery) &&
                                    failedTask.coversCurrentTutorDisclosure(
                                        providerForRecovery,
                                        ModelTaskKind.TUTOR_RESPOND,
                                    ) &&
                                    failedTask.request.egressManifest
                                        ?.isModelEgressApprovalFresh(recoveryApprovedAt) == true
                            collectTutorRespondRequest(
                                request = if (canReuseExactRequest) {
                                    failedTask.request
                                } else {
                                    rebuildTutorRequestAfterApprovalOrNull(
                                        failedTask = failedTask,
                                        provider = providerForRecovery,
                                        approvedAtEpochMillis = recoveryApprovedAt,
                                    ) ?: return@let
                                },
                                clearDraftOnPersist = false,
                                clearPendingActionOnPersist =
                                    pendingResponseAction as? PendingTutorEgressAction.RetryResponse,
                                retryConsumed =
                                    failedTask.status == ModelTaskStatus.RETRYABLE_FAILURE,
                            )
                        }
                    },
                )
            }
        }
        if (showChatStartError) {
            item("tutor_chat_start_error") {
                Text(
                    text = requireNotNull(chatStartError),
                    color = ErrorWarm,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("tutor_chat_start_error"),
                )
            }
        }
        item("tutor_session_footer") {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = trailingContent,
            )
        }
    }
}

@Composable
private fun TutorStoredChoiceFeedback(
    response: TutorTurnResponse,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = JadeSoft.copy(alpha = 0.28f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "你选择了",
                color = InkSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
            SafeMarkdownText(
                markdown = requireNotNull(response.diagnosticStemMarkdown),
                style = MaterialTheme.typography.bodySmall,
            )
            SafeMarkdownText(
                markdown = requireNotNull(response.selectedChoiceMarkdown),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (response.selectionWasCorrect == true) {
                    "判断正确"
                } else {
                    "这里暴露了关键分叉"
                },
                color = if (response.selectionWasCorrect == true) JadeActive else ErrorWarm,
                style = MaterialTheme.typography.labelLarge,
            )
            SafeMarkdownText(
                markdown = requireNotNull(response.feedbackMarkdown),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TutorDisclosureCard(
    provider: ProviderCapabilitySnapshot,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "开始讲这道题",
    actionText: String = "开始讲题",
    actionContentDescription: String = "开始讲解当前题",
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("captured_tutor_disclosure"),
        color = JadeSoft.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "会把当前题、少量同科学习记录，以及你在本题中发送的消息和已显示的讲解发给 ${provider.providerDisplayName}；需要还原题图关系时，只会再使用本题原图，不会发送其他题目。",
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryActionButton(
                text = actionText,
                onClick = onApprove,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("captured_tutor_start_model"),
                contentDescription = actionContentDescription,
            )
        }
    }
}

@Composable
private fun TutorRespondDisclosureCard(
    provider: ProviderCapabilitySnapshot,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tutor_respond_disclosure"),
        color = JadeSoft.copy(alpha = 0.32f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "继续本题对话",
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "会把当前题、少量同科学习记录、你发送的消息和已显示讲解发给 ${provider.providerDisplayName}；需要补充图解时，只会再使用本题原图，不会发送其他题目或完整学习记录。",
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            PrimaryActionButton(
                text = "继续对话",
                onClick = onApprove,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tutor_respond_disclosure_approve"),
                contentDescription = "允许与当前模型继续本题对话",
            )
        }
    }
}

@Composable
private fun TutorTaskContent(
    task: ModelTaskSnapshot,
    resolvedVisual: TutorVisualResolution = TutorVisualResolution.Hidden,
    visualPresentationMode: TutorVisualPresentationMode =
        TutorVisualPresentationMode.CURRENT_EXPANDED,
    visualOriginalAvailable: Boolean = false,
    response: TutorTurnResponse?,
    solutionRevealPreviewed: Boolean = false,
    awaitingContinuation: Boolean = false,
    interactionEnabled: Boolean,
    executionMatchesCurrentProvider: Boolean,
    splitChoiceFeedback: Boolean,
    interactionBusy: Boolean,
    interactionError: String?,
    onRetry: () -> Unit,
    onRetryVisual: () -> Unit = {},
    onVisualTargetHit: (TutorVisualHitProof) -> Unit = {},
    onSubmitChoice: (String) -> Unit,
    onDirectiveResponse: (TutorResponseMessage) -> Unit,
    onRequestHint: (() -> Unit)?,
    onContinue: (TutorMoveType) -> Unit,
    onRevealSolution: () -> Unit,
    onRestartCycle: () -> Unit,
    explanationMode: TutorExplanationMode,
    onOpenModelSettings: () -> Unit,
    onOpenVisualOriginal: () -> Unit = {},
    onReportVisualIncorrect: (String) -> Unit = {},
    solutionBottomModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val requiresModelSettings = task.failure?.code?.requiresModelSettings() == true
    when (task.status) {
        ModelTaskStatus.SUCCEEDED -> {
            val output = task.output as? TutorPlanOutput
            if (output == null) {
                TutorModelStatusCard(
                    title = "这次讲解暂时没准备好",
                    detail = "题目已经保存，可以再试一次。",
                    modifier = modifier,
                    actionLabel = "重新生成".takeIf {
                        interactionEnabled && executionMatchesCurrentProvider
                    },
                    onAction = onRetry,
                )
            } else {
                TutorTurnContent(
                    output = output,
                    ownerModelTaskRequestId = task.request.requestId,
                    modifier = modifier,
                    response = response,
                    solutionRevealPreviewed = solutionRevealPreviewed,
                    interactionEnabled = interactionEnabled,
                    splitChoiceFeedback = splitChoiceFeedback,
                    interactionBusy = interactionBusy,
                    interactionError = interactionError,
                    resolvedVisual = resolvedVisual,
                    visualPresentationMode = visualPresentationMode,
                    visualOriginalAvailable = visualOriginalAvailable,
                    onRetryVisual = onRetryVisual,
                    onOpenVisualOriginal = onOpenVisualOriginal,
                    onReportVisualIncorrect = onReportVisualIncorrect,
                    onVisualTargetHit = onVisualTargetHit,
                    onSubmitChoice = onSubmitChoice,
                    onDirectiveResponse = onDirectiveResponse,
                    onRequestHint = onRequestHint,
                    onContinue = onContinue,
                    onRevealSolution = onRevealSolution,
                    onRestartCycle = onRestartCycle,
                    explanationMode = explanationMode,
                    solutionBottomModifier = solutionBottomModifier,
                )
            }
        }
        ModelTaskStatus.PERMANENT_FAILURE,
        ModelTaskStatus.RETRYABLE_FAILURE,
        ModelTaskStatus.CANCELLED,
        -> TutorModelStatusCard(
            title = if (executionMatchesCurrentProvider) {
                "这次讲解暂时没完成"
            } else {
                "旧配置中的回复未完成"
            },
            detail = if (!executionMatchesCurrentProvider) {
                "之前的内容仍保留，可从当前配置继续这道题。"
            } else if (requiresModelSettings) {
                "模型设置需要更新，题目已经保存。"
            } else {
                "题目已经保存，可以再试一次。"
            },
            modifier = modifier,
            actionLabel = if (
                !interactionEnabled || !executionMatchesCurrentProvider ||
                (task.status != ModelTaskStatus.RETRYABLE_FAILURE && !requiresModelSettings)
            ) {
                null
            } else if (requiresModelSettings) {
                "检查模型设置"
            } else {
                "重新生成"
            },
            onAction = if (requiresModelSettings) {
                onOpenModelSettings
            } else {
                onRetry
            },
        )
        else -> if (executionMatchesCurrentProvider && awaitingContinuation) {
            TutorModelStatusCard(
                title = "讲解已暂停",
                detail = "点下面的“继续讲题”后接着完成。",
                modifier = modifier,
            )
        } else if (executionMatchesCurrentProvider) {
            TutorModelStatusCard(
                title = "正在准备这道题",
                detail = "正在整理讲解，请稍候。",
                modifier = modifier,
            )
        } else {
            TutorModelStatusCard(
                title = "旧配置中的回复未完成",
                detail = "之前的内容仍保留，可从当前配置继续这道题。",
                modifier = modifier,
            )
        }
    }
}

private fun TutorTurnPlan.hasGuidedInteraction(): Boolean =
    when (interactionDirective) {
        is TutorInteractionDirective.Choices,
        is TutorInteractionDirective.FreeResponse,
        is TutorInteractionDirective.VisualTarget,
        -> true
        TutorInteractionDirective.Continue,
        -> false
        null -> diagnosticItem != null
    }

private fun TutorInteractionDirective?.isEvidencePrompt(): Boolean = when (this) {
    is TutorInteractionDirective.Choices,
    is TutorInteractionDirective.FreeResponse,
    is TutorInteractionDirective.VisualTarget,
    -> true
    TutorInteractionDirective.Continue,
    null,
    -> false
}

private fun TutorPlanOutput.isMasteryRelevantTo(input: TutorPlanInput): Boolean {
    return masteryTargetsAreRelevant(
        targetedEvidenceLabels = plan.targetedEvidenceLabels,
        relevantLearningEvidence = input.relevantLearningEvidence,
    )
}

private fun String.isTutorHintRequest(): Boolean =
    this == GUIDED_HINT_MESSAGE || contains("提示", ignoreCase = true) ||
        contains("hint", ignoreCase = true)

@Composable
private fun TutorModelStatusCard(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("captured_tutor_model_status"),
        color = JadeSoft.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = InkSecondary, style = MaterialTheme.typography.bodyMedium)
            actionLabel?.let { label ->
                OutlineActionChip(text = label, onClick = onAction)
            }
        }
    }
}

@Composable
internal fun TutorQuestionUnavailable(
    title: String,
    detail: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Ink, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = InkSecondary, style = MaterialTheme.typography.bodyMedium)
        OutlineActionChip(
            text = "重新读取",
            onClick = onRetry,
            modifier = Modifier.testTag("captured_tutor_retry"),
        )
    }
}

internal fun tutorSessionSaveLabel(
    isSaved: Boolean,
    saveInProgress: Boolean,
    saveFailed: Boolean,
): String = when {
    isSaved -> "已存入"
    saveInProgress -> "保存中"
    saveFailed -> "重试保存"
    else -> "存入错题本"
}

internal fun tutorSessionStatusLine(isSaved: Boolean): String = if (isSaved) {
    "已存入错题本"
} else {
    "临时题目 · 讲完后再决定是否存入"
}

internal fun tutorSessionStatusLine(session: ConfirmedTutorSession): String =
    tutorSessionStatusLine(session.disposition)

internal fun tutorSessionStatusLine(disposition: TutorSessionDisposition): String = when (
    disposition
) {
    TutorSessionDisposition.ACTIVE -> "临时题目 · 讲完后再决定是否存入"
    TutorSessionDisposition.SAVED -> "已存入错题本"
    TutorSessionDisposition.ENDED_WITHOUT_SAVE -> "本次讲题已结束 · 未存入错题本"
}

private const val MAX_AUTO_VISUAL_WORK_ITEMS = 8
private const val GUIDED_HINT_MESSAGE = "我不确定，请给我一点提示"
