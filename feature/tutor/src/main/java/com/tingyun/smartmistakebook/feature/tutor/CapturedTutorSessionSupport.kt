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
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
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


internal data class TutorVisualGenerationExecutionState(
    val identity: CapturedTutorVisualWorkIdentity,
    val workSeeds: List<TutorVisualWorkSeed>,
    val sourceAssets: List<TutorVisualSourceAssetScope>,
    val provider: ProviderCapabilitySnapshot?,
    val approvedAtEpochMillis: Long?,
    val generationTasks: List<ModelTaskSnapshot>,
    val autoAnchors: Set<TutorVisualTurnAnchor>,
    val pendingRetry: PendingTutorEgressAction.RetryVisual?,
    val executionFailures: Set<TutorVisualExecutionKey>,
)

internal data class TutorVisualReviewExecutionState(
    val identity: CapturedTutorVisualWorkIdentity,
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

internal data class CapturedTutorVisualWorkIdentity(
    val sessionId: String,
    val revisionNumber: Int,
    val questionDocumentFingerprint: String,
)

internal data class CapturedTutorQuestionLifecycleIdentity(
    val sessionId: String,
    val revisionNumber: Int,
    val questionDocumentFingerprint: String,
)

internal fun TutorQuestionContext.toCapturedTutorQuestionLifecycleIdentity() =
    CapturedTutorQuestionLifecycleIdentity(
        sessionId = sessionId,
        revisionNumber = revisionNumber,
        questionDocumentFingerprint = CapturedQuestionDocumentFingerprint.of(questionDocument),
    )

internal data class TutorVisualReviewWorkItem(
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

internal fun visibleTutorChoiceDirective(
    timeline: List<TutorConversationTimelineItem>,
    sourceRequestId: String,
): TutorInteractionDirective.Choices? =
    visibleTutorInteractionDirective(timeline, sourceRequestId)
        as? TutorInteractionDirective.Choices

internal fun visibleTutorFreeResponseRequestId(
    timeline: List<TutorConversationTimelineItem>,
    pendingEvidenceRequestId: String?,
): String? {
    val requestId = pendingEvidenceRequestId ?: return null
    return requestId.takeIf {
        visibleTutorInteractionDirective(timeline, requestId) is
            TutorInteractionDirective.FreeResponse
    }
}

internal fun visibleTutorInteractionDirective(
    timeline: List<TutorConversationTimelineItem>,
    sourceRequestId: String,
): TutorInteractionDirective? {
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
    }
}

internal fun tutorEvidenceCancellation(
    question: TutorQuestionContext,
    requestId: String,
    clock: () -> Long = System::currentTimeMillis,
) = CancelTutorEvidenceCommand(
    sessionId = question.sessionId,
    questionDocumentId = question.questionDocument.document.id,
    revisionNumber = question.revisionNumber,
    evidenceRequestId = requestId,
    occurredAtEpochMillis = clock(),
)

internal fun tutorPendingTutorResponseMessage(
    timeline: List<TutorConversationTimelineItem>,
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

internal fun tutorCancellationIsConfirmed(
    requestId: String,
    locallyCancelledEvidenceRequestIds: Set<String>,
    replayedCancellationRequestId: String?,
    replayedPendingIsCancelled: Boolean?,
): Boolean =
    requestId in locallyCancelledEvidenceRequestIds ||
        (
            requestId == replayedCancellationRequestId &&
                replayedPendingIsCancelled == true
            )

internal fun tutorPendingInteractionIsCurrentlyBlocked(
    guidanceResolution: TutorGuidanceModeResolution,
    cancellationPendingEvidenceRequestIds: Set<String>,
    replayedCancellationRequestId: String?,
    cancellationIsConfirmed: (String) -> Boolean,
): Boolean =
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
                replayedCancellationRequestId?.let(cancellationIsConfirmed) != true
            )

internal fun tutorConversationAutoScrollVersion(
    timeline: List<TutorConversationTimelineItem>,
): List<List<*>> =
    timeline.map { timelineItem ->
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

internal fun tutorVisualInsertionToken(
    resolvedVisualStates: Map<TutorVisualTurnAnchor, TutorVisualResolution>,
): List<String> =
    resolvedVisualStates.values
        .filterIsInstance<TutorVisualResolution.Ready>()
        .map { it.scene.sceneId }
        .sorted()

internal fun tutorActiveReplyExists(
    timeline: List<TutorConversationTimelineItem>,
    activeRequestId: String?,
): Boolean =
    activeRequestId != null && timeline.any { item ->
        item is TutorConversationTimelineItem.Reply &&
            item.task.request.requestId == activeRequestId
    }

internal fun tutorTailId(
    timeline: List<TutorConversationTimelineItem>,
    activeMessage: TutorActiveStreamMessage?,
): String? = timeline.lastOrNull()?.stableId.takeIf { activeMessage == null }

internal fun pendingResponseRetryIsRebuildable(
    pendingAction: PendingTutorEgressAction?,
    latestRespondTasks: List<ModelTaskSnapshot>,
): Boolean =
    (pendingAction as? PendingTutorEgressAction.RetryResponse)?.let { pending ->
        latestRespondTasks.any { task ->
            task.request.requestId == pending.requestId &&
                task.isRebuildableTutorRequest()
        }
    } ?: true

internal fun tutorExpectedConversationItemCount(
    timelineSize: Int,
    showActiveReply: Boolean,
    showPlanRecoveryDisclosure: Boolean,
    showVisualRetryDisclosure: Boolean,
    showRespondDisclosure: Boolean,
    showChatStartError: Boolean,
): Int =
    2 +
        timelineSize +
        (if (showActiveReply) 1 else 0) +
        (if (showPlanRecoveryDisclosure) 1 else 0) +
        (if (showVisualRetryDisclosure) 1 else 0) +
        (if (showRespondDisclosure) 1 else 0) +
        (if (showChatStartError) 1 else 0)

internal fun tutorShowActiveReply(
    activeMessage: TutorActiveStreamMessage?,
    activeReplyExists: Boolean,
): Boolean = activeMessage != null && !activeReplyExists

internal fun tutorShowPlanRecoveryDisclosure(
    hasPlanFreshApproval: Boolean,
    hasPendingPlanAction: Boolean,
    executableProviderIsExternal: Boolean,
): Boolean =
    (hasPlanFreshApproval || hasPendingPlanAction) && executableProviderIsExternal

internal fun tutorShowVisualRetryDisclosure(
    hasPendingVisualRetry: Boolean,
    currentProviderIsExternal: Boolean,
): Boolean = hasPendingVisualRetry && currentProviderIsExternal

internal fun tutorShowRespondDisclosure(
    respondSupported: Boolean,
    hasCurrentPlan: Boolean,
    respondAuthorized: Boolean,
    hasPlanFreshApproval: Boolean,
    hasPendingVisualRetry: Boolean,
    pendingResponseRetryIsRebuildable: Boolean,
): Boolean =
    respondSupported && hasCurrentPlan && !respondAuthorized &&
        !hasPlanFreshApproval && !hasPendingVisualRetry &&
        pendingResponseRetryIsRebuildable

internal fun tutorShowChatStartError(
    composerAvailable: Boolean,
    chatStartError: String?,
): Boolean = !composerAvailable && chatStartError != null

internal fun tutorIsCurrentTurn(
    taskCycleOrdinal: Int,
    taskTurnOrdinal: Int,
    currentCycleOrdinal: Int,
    currentTurnOrdinal: Int,
): Boolean =
    taskCycleOrdinal == currentCycleOrdinal && taskTurnOrdinal == currentTurnOrdinal

internal fun tutorPlanExecutionMatches(
    provider: ProviderCapabilitySnapshot?,
    task: ModelTaskSnapshot,
): Boolean = provider?.let(task::matchesTutorProvider) == true

internal fun tutorResolvedVisual(
    visualAnchor: TutorVisualTurnAnchor?,
    resolvedVisualStates: Map<TutorVisualTurnAnchor, TutorVisualResolution>,
): TutorVisualResolution =
    visualAnchor?.let(resolvedVisualStates::get) ?: TutorVisualResolution.Hidden

internal fun tutorAwaitingContinuation(
    awaitingRequestId: String?,
    taskRequestId: String,
): Boolean = awaitingRequestId == taskRequestId

internal fun tutorPlanInteractionEnabled(
    isTail: Boolean,
    isCurrentTurn: Boolean,
    hasFreshApproval: Boolean,
    pendingInteractionBlocked: Boolean,
    responseActionAwaitingAuthorization: Boolean,
): Boolean =
    isTail && isCurrentTurn && !hasFreshApproval &&
        !pendingInteractionBlocked && !responseActionAwaitingAuthorization

internal fun tutorHintRequestEnabled(
    guidedMode: Boolean,
    hintsUsed: Int,
    maxHints: Int,
    respondSupported: Boolean,
    respondAuthorized: Boolean,
    chatSending: Boolean,
    pendingInteractionBlocked: Boolean,
    responseActionAwaitingAuthorization: Boolean,
): Boolean =
    guidedMode && hintsUsed < maxHints && respondSupported && respondAuthorized &&
        !chatSending && !pendingInteractionBlocked && !responseActionAwaitingAuthorization

internal fun capturedTutorConversationAuthorityFingerprint(
    planRequestId: String,
    respondRequestIds: List<String>,
): String {
    val digest = CanonicalSha256("captured-tutor-response-authority-v1")
        .field("planRequestId", planRequestId)
        .field("respondRequestCount", respondRequestIds.size)
    respondRequestIds.forEachIndexed { index, requestId ->
        digest.field("respondRequestId[$index]", requestId)
    }
    return digest.finish()
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


internal sealed interface CapturedTutorSessionUiState {
    data object Loading : CapturedTutorSessionUiState
    data class Ready(val session: ConfirmedTutorSession) : CapturedTutorSessionUiState
    data object Missing : CapturedTutorSessionUiState
    data object Unavailable : CapturedTutorSessionUiState
}

internal data class CapturedTutorChoiceRuntimeIdentity(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val planRequestId: String,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val modeVersion: Long,
    val learningWritePermissionVersion: Long,
)

internal fun ModelTaskSnapshot.hasCurrentLocalRecoveryAdmission(
    allTasks: List<ModelTaskSnapshot>,
    question: TutorQuestionContext,
    questionDocumentFingerprint: String,
    presentationCredentials: Collection<TutorLocalRecoveryPresentationCredential>,
): Boolean {
    if (!request.isLocalTutorRecoveryRequest()) return true
    val credential = presentationCredentials.lastOrNull { candidate ->
        candidate.recoveredRequestId == request.requestId
    }
        ?: return false
    val input = request.input
    val sourceTask = allTasks.firstOrNull { task ->
        task.request.requestId == credential.sourceRequestId &&
            task.requestFingerprint == credential.sourceRequestFingerprint
    } ?: return false
    val sourceInput = sourceTask.request.input
    val inputCycleAndTurn = when (input) {
        is TutorPlanInput -> input.cycleOrdinal to input.turnOrdinal
        is TutorRespondInput -> input.cycleOrdinal to input.turnOrdinal
        else -> return false
    }
    val sourceCycleAndTurn = when (sourceInput) {
        is TutorPlanInput -> sourceInput.cycleOrdinal to sourceInput.turnOrdinal
        is TutorRespondInput -> sourceInput.cycleOrdinal to sourceInput.turnOrdinal
        else -> return false
    }
    val sourceMode = when (sourceInput) {
        is TutorPlanInput -> sourceInput.explanationMode
        is TutorRespondInput -> sourceInput.explanationMode
        else -> return false
    }
    val sourceModeVersion = when (sourceInput) {
        is TutorPlanInput -> sourceInput.modeVersion
        is TutorRespondInput -> sourceInput.modeVersion
        else -> return false
    }
    val sourceWritePermissionVersion = when (sourceInput) {
        is TutorPlanInput -> sourceInput.learningWritePermissionVersion
        is TutorRespondInput -> sourceInput.learningWritePermissionVersion
        else -> return false
    }
    val sourceAllowsLearningWrites = when (sourceInput) {
        is TutorPlanInput -> sourceInput.allowLongTermLearningWrites
        is TutorRespondInput -> sourceInput.allowLongTermLearningWrites
        else -> return false
    }
    val inputMode = when (input) {
        is TutorPlanInput -> input.explanationMode
        is TutorRespondInput -> input.explanationMode
        else -> return false
    }
    val inputModeVersion = when (input) {
        is TutorPlanInput -> input.modeVersion
        is TutorRespondInput -> input.modeVersion
        else -> return false
    }
    val inputWritePermissionVersion = when (input) {
        is TutorPlanInput -> input.learningWritePermissionVersion
        is TutorRespondInput -> input.learningWritePermissionVersion
        else -> return false
    }
    val inputAllowsLearningWrites = when (input) {
        is TutorPlanInput -> input.allowLongTermLearningWrites
        is TutorRespondInput -> input.allowLongTermLearningWrites
        else -> return false
    }
    val inputMatchesQuestion = when (input) {
        is TutorPlanInput ->
            input.sessionId == question.sessionId &&
                input.draftRevisionNumber == question.revisionNumber &&
                input.subject == question.subject &&
                input.questionDocument == question.questionDocument.document
        is TutorRespondInput ->
            input.sessionId == question.sessionId &&
                input.draftRevisionNumber == question.revisionNumber &&
                input.subject == question.subject &&
                input.questionDocument == question.questionDocument.document
        else -> false
    }
    val sourceMatchesQuestion = when (sourceInput) {
        is TutorPlanInput ->
            sourceInput.sessionId == question.sessionId &&
                sourceInput.draftRevisionNumber == question.revisionNumber &&
                sourceInput.subject == question.subject &&
                sourceInput.questionDocument == question.questionDocument.document
        is TutorRespondInput ->
            sourceInput.sessionId == question.sessionId &&
                sourceInput.draftRevisionNumber == question.revisionNumber &&
                sourceInput.subject == question.subject &&
                sourceInput.questionDocument == question.questionDocument.document
        else -> false
    }
    val staticAdmissionMatches = credential.recoveredRequestId == request.requestId &&
        credential.recoveredRequestFingerprint == requestFingerprint &&
        request.egressManifest == null &&
        sourceTask.stateVersion == credential.sourceTaskStateVersion &&
        sourceTask.request.input.kind == credential.sourceTaskKind &&
        input.kind == credential.sourceTaskKind &&
        sourceCycleAndTurn == (credential.cycleOrdinal to credential.turnOrdinal) &&
        inputCycleAndTurn == sourceCycleAndTurn &&
        sourceMode == credential.effectiveExplanationMode &&
        inputMode == credential.effectiveExplanationMode &&
        sourceModeVersion == credential.modeVersion &&
        inputModeVersion == credential.modeVersion &&
        sourceWritePermissionVersion == credential.learningWritePermissionVersion &&
        inputWritePermissionVersion == credential.learningWritePermissionVersion &&
        sourceAllowsLearningWrites == credential.learningWritesAllowed &&
        inputAllowsLearningWrites == credential.learningWritesAllowed &&
        sourceMatchesQuestion &&
        inputMatchesQuestion &&
        credential.presentationScopeKey == localRecoveryPresentationScopeKey(
            sessionId = question.sessionId,
            revisionNumber = question.revisionNumber,
            questionDocumentFingerprint = questionDocumentFingerprint,
        ) &&
        credential.questionDocumentFingerprint == questionDocumentFingerprint
    if (!staticAdmissionMatches) return false
    return true
}

internal data class TutorLearningWriteAuthority(
    val allowed: Boolean,
    val permissionVersion: Long,
) {
    init {
        require(permissionVersion >= 0)
    }

    fun allows(
        requestPermissionVersion: Long,
        requestModeVersion: Long,
        currentModeVersion: Long,
    ): Boolean =
        allowed &&
            requestPermissionVersion == permissionVersion &&
            requestModeVersion == currentModeVersion
}

