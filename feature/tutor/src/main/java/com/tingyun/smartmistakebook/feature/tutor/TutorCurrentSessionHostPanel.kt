package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceActionResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseActionResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseRetryAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseStatus
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHint
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintShownAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintShownResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintStatus
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionInteraction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicyResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicySnapshot
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicyUpdate
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPresentation
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetActionResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetPreparation
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetPreparationResult
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContext
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Production presentation owner for one confirmed question.
 *
 * It never renders a model task directly. A completed task first passes through the trusted Host;
 * only the Host's constrained presentation and opaque action token reach Compose.
 */
@Composable
internal fun TutorCurrentSessionHostPanel(
    question: TutorQuestionContext,
    modelTasks: ScopedModelTaskPort,
    sessionHost: TutorCurrentSessionHostPort,
    masteryContextRepository: TutorMasteryContextRepository? = null,
    learningWritesAllowed: Boolean,
    explanationMode: TutorExplanationMode,
    visualIntent: TutorCurrentSessionVisualIntent = TutorCurrentSessionVisualIntent.NONE,
    onExplanationModeChange: (TutorExplanationMode) -> Unit,
    onOpenModelSettings: () -> Unit,
    visualSourceAssetsReader: suspend () -> List<TutorVisualSourceAssetScope> = { emptyList() },
    visualOriginalAvailable: Boolean = false,
    onOpenVisualOriginal: () -> Unit = {},
    autoStartAuthorization: TutorAutoStartAuthorization? = null,
    onAutoStartAuthorizationConsumed: (String) -> Unit = {},
    conversationEnabled: Boolean = true,
    planContextReady: Boolean = true,
    headerContent: @Composable () -> Unit = {},
    leadingContent: @Composable ColumnScope.() -> Unit = {},
    trailingContent: @Composable ColumnScope.() -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var requestedVisualIntent by rememberSaveable(question.sessionId, question.revisionNumber) {
        mutableStateOf(visualIntent)
    }
    val effectiveVisualIntent = if (visualIntent == TutorCurrentSessionVisualIntent.USER_EXPLICIT) {
        visualIntent
    } else {
        requestedVisualIntent
    }
    var policy by remember(question.sessionId) {
        mutableStateOf<TutorCurrentSessionPolicySnapshot?>(null)
    }
    var policyRecoveryComplete by remember(question.sessionId) { mutableStateOf(false) }
    var policyFailed by remember(question.sessionId) { mutableStateOf(false) }
    var provider by remember(question.sessionId) {
        mutableStateOf<ProviderCapabilitySnapshot?>(null)
    }
    var providerFailed by remember(question.sessionId) { mutableStateOf(false) }
    var manualLaunchNonce by rememberSaveable(question.sessionId) { mutableIntStateOf(0) }
    var manualApprovedAt by rememberSaveable(question.sessionId) { mutableLongStateOf(0L) }
    var consumedLaunchKey by rememberSaveable(question.sessionId) { mutableStateOf<String?>(null) }
    var activatedTaskRequestId by remember(question.sessionId) { mutableStateOf<String?>(null) }
    var activationError by rememberSaveable(question.sessionId) { mutableStateOf(false) }
    var actionBusy by remember(question.sessionId) { mutableStateOf(false) }
    var actionGeneration by remember(question.sessionId) { mutableLongStateOf(0L) }
    var freeResponseDraft by remember(question.sessionId) { mutableStateOf("") }
    var submittedFreeResponse by remember(question.sessionId) {
        mutableStateOf<String?>(null)
    }
    var submittedFreeResponsePresentationToken by remember(question.sessionId) {
        mutableStateOf<String?>(null)
    }
    var freeResponseSubmittedLocally by remember(question.sessionId) { mutableStateOf(false) }
    var freeResponseStartedAt by remember(question.sessionId) { mutableLongStateOf(0L) }
    var actionFeedback by remember(question.sessionId) {
        mutableStateOf<com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceFeedback?>(
            null,
        )
    }
    var interactionBlocked by remember(question.sessionId) { mutableStateOf(false) }
    var compositionEgressLease by remember(question.sessionId) {
        mutableStateOf<TutorCompositionEgressLease?>(null)
    }
    var locallyVisibleHintToken by remember(question.sessionId) { mutableStateOf<String?>(null) }
    var hintCommitBusyToken by remember(question.sessionId) { mutableStateOf<String?>(null) }
    var hintCommitFailedToken by remember(question.sessionId) { mutableStateOf<String?>(null) }

    val presentationAllowed = activatedTaskRequestId != null &&
        policy?.explanationMode == explanationMode &&
        policy?.learningWritesAllowed == learningWritesAllowed &&
        policy?.visualIntent == effectiveVisualIntent
    val presentation by remember(question.sessionId, sessionHost, presentationAllowed) {
        if (presentationAllowed) {
            sessionHost.observePresentation(question.sessionId).catch { emit(null) }
        } else {
            flowOf(null)
        }
    }.collectAsState(initial = null)
    val hintSubmissionBlocked = presentation?.hint?.let { hint ->
        isHintSubmissionBlocked(
            hint = hint,
            locallyVisibleHintToken = locallyVisibleHintToken,
            hintCommitBusyToken = hintCommitBusyToken,
            hintCommitFailedToken = hintCommitFailedToken,
        )
    } == true
    val tasks by remember(question.sessionId, modelTasks) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_PLAN)
    }.collectAsState(initial = emptyList())

    val masteryRequest = remember(question) { question.masteryContextRequestOrNull() }
    var masteryContext by remember(question.sessionId, masteryRequest) {
        mutableStateOf(TutorMasteryContext.EMPTY)
    }
    LaunchedEffect(masteryContextRepository, masteryRequest) {
        masteryContext = if (masteryContextRepository == null || masteryRequest == null) {
            TutorMasteryContext.EMPTY
        } else {
            try {
                masteryContextRepository.read(masteryRequest).boundedTo(masteryRequest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TutorMasteryContext.EMPTY
            }
        }
    }

    LaunchedEffect(question.sessionId, sessionHost) {
        policyRecoveryComplete = false
        val resumed = try {
            sessionHost.resume(question.sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val restoredPolicy = try {
            sessionHost.currentPolicy(question.sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TutorCurrentSessionPolicyResult.Unavailable
        }
        val recoveredSnapshot = when (restoredPolicy) {
            is TutorCurrentSessionPolicyResult.Current -> restoredPolicy.snapshot
            TutorCurrentSessionPolicyResult.Unavailable -> when (resumed) {
                is TutorCurrentSessionHostResult.Ready -> resumed.snapshot.toPolicySnapshot()
                is TutorCurrentSessionHostResult.Revoked -> resumed.snapshot.toPolicySnapshot()
                else -> null
            }
        }
        recoveredSnapshot?.let { snapshot ->
            policy = snapshot
            requestedVisualIntent = snapshot.visualIntent
        }
        policyRecoveryComplete = true
    }

    LaunchedEffect(
        question.sessionId,
        explanationMode,
        learningWritesAllowed,
        effectiveVisualIntent,
        policyRecoveryComplete,
        sessionHost,
    ) {
        if (!policyRecoveryComplete) return@LaunchedEffect
        val samePolicy = policy?.let { current ->
            current.explanationMode == explanationMode &&
                current.learningWritesAllowed == learningWritesAllowed &&
                current.visualIntent == effectiveVisualIntent
        } == true
        activatedTaskRequestId = null
        actionGeneration += 1L
        actionBusy = false
        policy = null
        policyFailed = false
        activationError = false
        actionFeedback = null
        if (!samePolicy) freeResponseDraft = ""
        submittedFreeResponse = null
        freeResponseSubmittedLocally = false
        freeResponseStartedAt = 0L
        compositionEgressLease = null
        locallyVisibleHintToken = null
        hintCommitBusyToken = null
        hintCommitFailedToken = null
        val result = try {
            sessionHost.updatePolicy(
                TutorCurrentSessionPolicyUpdate(
                    sessionId = question.sessionId,
                    explanationMode = explanationMode,
                    learningWritesAllowed = learningWritesAllowed,
                    visualIntent = effectiveVisualIntent,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TutorCurrentSessionPolicyResult.Unavailable
        }
        when (result) {
            is TutorCurrentSessionPolicyResult.Current -> policy = result.snapshot
            TutorCurrentSessionPolicyResult.Unavailable -> policyFailed = true
        }
    }

    LaunchedEffect(question.sessionId, modelTasks) {
        providerFailed = false
        provider = try {
            modelTasks.capabilities()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            providerFailed = true
            null
        }
    }

    val currentPolicy = policy
    val matchingTasks = remember(tasks, question, currentPolicy) {
        currentPolicy?.let { snapshot ->
            tasks.filter { task -> task.matchesHostQuestion(question, snapshot) }
        }.orEmpty()
    }
    val currentTask = matchingTasks.lastOrNull()
    val presentationOwnerTask = activatedTaskRequestId?.let { requestId ->
        matchingTasks.firstOrNull { task -> task.request.requestId == requestId }
    }

    LaunchedEffect(
        currentTask?.request?.requestId,
        currentTask?.status,
        currentPolicy,
        sessionHost,
    ) {
        val task = currentTask ?: return@LaunchedEffect
        if (task.status != ModelTaskStatus.SUCCEEDED || currentPolicy == null) return@LaunchedEffect
        val result = try {
            sessionHost.activate(question.sessionId, task.request.requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        when (result) {
            is TutorCurrentSessionHostResult.Ready -> {
                activatedTaskRequestId = task.request.requestId
                activationError = false
            }
            else -> {
                activatedTaskRequestId = null
                activationError = true
            }
        }
    }

    val currentProvider = provider?.takeIf { candidate ->
        candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
            candidate.supports(ModelTaskKind.TUTOR_PLAN)
    }
    val now = clock()
    val automaticApproval = autoStartAuthorization?.takeIf { authorization ->
        val candidate = currentProvider ?: return@takeIf false
        authorization.matches(
            sessionId = question.sessionId,
            questionDocumentId = question.questionDocument.document.id,
            revisionNumber = question.revisionNumber,
            provider = candidate,
            promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
            nowEpochMillis = now,
        )
    }
    val launchAuthorizationKey = when {
        manualLaunchNonce > 0 -> "manual:$manualLaunchNonce"
        automaticApproval != null -> "auto:${automaticApproval.authorizationId}"
        else -> null
    }
    val launchKey = launchAuthorizationKey?.let { authorizationKey ->
        currentPolicy?.let { snapshot ->
            "$authorizationKey:mode:${snapshot.modeVersion}:writes:" +
                "${snapshot.learningWritePermissionVersion}:visual:" +
                "${snapshot.visualIntent.name}:${snapshot.visualIntentVersion}"
        }
    }

    LaunchedEffect(
        launchKey,
        currentPolicy,
        currentProvider,
        masteryContext,
        question,
        matchingTasks.size,
        planContextReady,
    ) {
        if (!planContextReady) return@LaunchedEffect
        val key = launchKey ?: return@LaunchedEffect
        val snapshot = currentPolicy ?: return@LaunchedEffect
        val candidate = currentProvider ?: return@LaunchedEffect
        if (key == consumedLaunchKey) return@LaunchedEffect
        val approvedAt = automaticApproval?.approvedAtEpochMillis
            ?: manualApprovedAt.takeIf { it > 0L }
            ?: clock()
        compositionEgressLease = candidate
            .takeIf { it.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER }
            ?.let {
                TutorCompositionEgressLease.grant(
                    question = question,
                    provider = candidate,
                    approvedAtEpochMillis = approvedAt,
                )
            }
        val taskAlreadyStarted = currentTask?.status?.let(ACTIVE_MODEL_STATUSES::contains) == true
        if (taskAlreadyStarted || currentTask?.status == ModelTaskStatus.SUCCEEDED) {
            consumedLaunchKey = key
            automaticApproval?.let { onAutoStartAuthorizationConsumed(it.authorizationId) }
            return@LaunchedEffect
        }
        consumedLaunchKey = key
        activationError = false
        val requestId = tutorPlanRequestId(
            question = question,
            masteryContext = masteryContext,
            provider = candidate,
            attempt = matchingTasks.size,
            explanationMode = snapshot.explanationMode,
            modeVersion = snapshot.modeVersion,
            learningWritePermissionVersion = snapshot.learningWritePermissionVersion,
        )
        val request = buildTutorPlanRequest(
            question = question,
            masteryContext = masteryContext,
            provider = candidate,
            requestId = requestId,
            occurredAtEpochMillis = clock(),
            approvedAtEpochMillis = approvedAt,
            explanationMode = snapshot.explanationMode,
            modeVersion = snapshot.modeVersion,
            learningWritePermissionVersion = snapshot.learningWritePermissionVersion,
            allowLongTermLearningWrites = snapshot.learningWritesAllowed,
        )
        automaticApproval?.let { onAutoStartAuthorizationConsumed(it.authorizationId) }
        try {
            modelTasks.execute(request).collect()
        } catch (cancelled: CancellationException) {
            modelTasks.cancel(requestId)
            throw cancelled
        } catch (_: Exception) {
            activationError = true
        }
    }

    LaunchedEffect(presentation?.presentationToken, presentation?.hint?.slotToken) {
        actionGeneration += 1L
        actionBusy = false
        actionFeedback = null
        interactionBlocked = false
        locallyVisibleHintToken = presentation?.hint
            ?.takeIf { it.status == TutorCurrentSessionHintStatus.SHOWN }
            ?.slotToken
        hintCommitBusyToken = null
        hintCommitFailedToken = null
        presentation?.presentationToken?.let { visibleToken ->
            if (submittedFreeResponsePresentationToken != visibleToken) {
                submittedFreeResponse = null
                freeResponseSubmittedLocally = false
                submittedFreeResponsePresentationToken = visibleToken
            }
        }
        freeResponseStartedAt = clock()
    }

    val commitVisibleHint: (TutorCurrentSessionHint, Boolean) -> Unit = { hint, waitForStableUi ->
        val visible = presentation
        if (
            visible == null ||
            !presentationAllowed ||
            visible.explanationMode != TutorExplanationMode.GUIDED ||
            visible.hint?.slotToken != hint.slotToken ||
            hintCommitBusyToken == hint.slotToken
        ) {
            Unit
        } else {
            locallyVisibleHintToken = hint.slotToken
            hintCommitFailedToken = null
            hintCommitBusyToken = hint.slotToken
            val generation = actionGeneration
            scope.launch {
                try {
                    if (waitForStableUi) delay(HINT_STABLE_VISIBILITY_MILLIS)
                    if (generation != actionGeneration) return@launch
                    when (
                        sessionHost.recordHintShown(
                            TutorCurrentSessionHintShownAction(
                                sessionId = visible.sessionId,
                                presentationToken = visible.presentationToken,
                                slotToken = hint.slotToken,
                            ),
                        )
                    ) {
                        TutorCurrentSessionHintShownResult.Recorded,
                        TutorCurrentSessionHintShownResult.Duplicate,
                        -> if (generation == actionGeneration) {
                            hintCommitFailedToken = null
                        }
                        is TutorCurrentSessionHintShownResult.Rejected ->
                            if (generation == actionGeneration) {
                                hintCommitFailedToken = hint.slotToken
                            }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (generation == actionGeneration) {
                        hintCommitFailedToken = hint.slotToken
                    }
                } finally {
                    if (
                        generation == actionGeneration &&
                        hintCommitBusyToken == hint.slotToken
                    ) {
                        hintCommitBusyToken = null
                    }
                }
            }
        }
    }

    TutorConversationFrame(
        header = headerContent,
        autoScrollVersion = presentation?.presentationToken ?: currentTask?.status,
        modifier = modifier,
    ) {
        item("host_question") {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = leadingContent,
            )
        }
        when {
            !conversationEnabled -> Unit
            presentation != null -> item("host_presentation") {
                HostPresentation(
                    presentation = requireNotNull(presentation),
                    actionBusy = actionBusy,
                    actionFeedback = actionFeedback,
                    freeResponseDraft = freeResponseDraft,
                    submittedFreeResponse = submittedFreeResponse,
                    freeResponseSubmittedLocally = freeResponseSubmittedLocally,
                    locallyVisibleHintToken = locallyVisibleHintToken,
                    hintCommitBusyToken = hintCommitBusyToken,
                    hintCommitFailedToken = hintCommitFailedToken,
                    hintSubmissionBlocked = hintSubmissionBlocked,
                    interactionBlocked = interactionBlocked,
                    visualContent = { onTargetHit ->
                        TutorCurrentSessionHostVisual(
                            question = question,
                            ownerTask = presentationOwnerTask,
                            inlineScene = presentation?.visualScene,
                            visualIntent = requireNotNull(presentation).visualIntent,
                            policyFence = requireNotNull(presentation).let { visible ->
                                "mode:${currentPolicy?.modeVersion}:writes:" +
                                    "${currentPolicy?.learningWritePermissionVersion}:visual:" +
                                    "${visible.visualIntent.name}:${visible.visualIntentVersion}"
                            },
                            modelTasks = modelTasks,
                            provider = provider,
                            providerLoadFailed = providerFailed,
                            compositionEgressLease = compositionEgressLease,
                            sourceAssetsReader = visualSourceAssetsReader,
                            originalAvailable = visualOriginalAvailable,
                            onOpenOriginal = onOpenVisualOriginal,
                            onTargetHit = onTargetHit,
                            clock = clock,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    onChoice = choice@ { choiceId ->
                        val visible = presentation ?: return@choice
                        if (
                            !presentationAllowed || actionBusy ||
                            hintSubmissionBlocked ||
                            visible.explanationMode != TutorExplanationMode.GUIDED
                        ) {
                            return@choice
                        }
                        actionBusy = true
                        val generation = actionGeneration
                        scope.launch {
                            try {
                                val result = sessionHost.submitChoice(
                                    TutorCurrentSessionChoiceAction(
                                        sessionId = visible.sessionId,
                                        presentationToken = visible.presentationToken,
                                        choiceId = choiceId,
                                    ),
                                )
                                if (generation != actionGeneration) return@launch
                                when (result) {
                                    is TutorCurrentSessionChoiceActionResult.Answered ->
                                        actionFeedback = result.feedback
                                    is TutorCurrentSessionChoiceActionResult.Rejected -> {
                                        activationError = true
                                        interactionBlocked = true
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (generation == actionGeneration) {
                                    activationError = true
                                    interactionBlocked = true
                                }
                            } finally {
                                if (generation == actionGeneration) actionBusy = false
                            }
                        }
                    },
                    onFreeResponseChange = { value -> freeResponseDraft = value },
                    onFreeResponse = freeResponse@ {
                        val visible = presentation ?: return@freeResponse
                        val interaction = visible.interaction as?
                            TutorCurrentSessionInteraction.FreeResponse ?: return@freeResponse
                        val answer = freeResponseDraft.trim()
                        if (
                            !presentationAllowed || actionBusy || hintSubmissionBlocked ||
                            answer.isEmpty()
                        ) {
                            return@freeResponse
                        }
                        actionBusy = true
                        submittedFreeResponse = answer
                        submittedFreeResponsePresentationToken = visible.presentationToken
                        val generation = actionGeneration
                        val elapsed = (clock() - freeResponseStartedAt)
                            .coerceAtLeast(0L)
                            .takeIf { freeResponseStartedAt > 0L }
                        scope.launch {
                            try {
                                when (
                                    sessionHost.submitFreeResponse(
                                        TutorCurrentSessionFreeResponseAction(
                                            sessionId = visible.sessionId,
                                            actionToken = interaction.actionToken,
                                            answer = answer,
                                            elapsedDurationMillis = elapsed,
                                        ),
                                    )
                                ) {
                                    TutorCurrentSessionFreeResponseActionResult.Accepted,
                                    TutorCurrentSessionFreeResponseActionResult.Duplicate,
                                    -> if (generation == actionGeneration) {
                                        freeResponseDraft = ""
                                        freeResponseSubmittedLocally = true
                                    }
                                    is TutorCurrentSessionFreeResponseActionResult.Rejected ->
                                        if (generation == actionGeneration) {
                                            submittedFreeResponse = null
                                            activationError = true
                                            interactionBlocked = true
                                        }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (generation == actionGeneration) {
                                    submittedFreeResponse = null
                                    activationError = true
                                    interactionBlocked = true
                                }
                            } finally {
                                if (generation == actionGeneration) actionBusy = false
                            }
                        }
                    },
                    onFreeResponseRetry = retry@ {
                        val visible = presentation ?: return@retry
                        val interaction = visible.interaction as?
                            TutorCurrentSessionInteraction.FreeResponse ?: return@retry
                        if (
                            !presentationAllowed || actionBusy ||
                            hintSubmissionBlocked ||
                            interaction.submissionStatus !=
                            TutorCurrentSessionFreeResponseStatus.RETRY_AVAILABLE
                        ) {
                            return@retry
                        }
                        actionBusy = true
                        val generation = actionGeneration
                        scope.launch {
                            try {
                                when (
                                    sessionHost.retryFreeResponse(
                                        TutorCurrentSessionFreeResponseRetryAction(
                                            sessionId = visible.sessionId,
                                            actionToken = interaction.actionToken,
                                        ),
                                    )
                                ) {
                                    TutorCurrentSessionFreeResponseActionResult.Accepted,
                                    TutorCurrentSessionFreeResponseActionResult.Duplicate,
                                    -> if (generation == actionGeneration) {
                                        freeResponseSubmittedLocally = true
                                    }
                                    is TutorCurrentSessionFreeResponseActionResult.Rejected ->
                                        if (generation == actionGeneration) {
                                            activationError = true
                                            interactionBlocked = true
                                        }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (generation == actionGeneration) {
                                    activationError = true
                                    interactionBlocked = true
                                }
                            } finally {
                                if (generation == actionGeneration) actionBusy = false
                            }
                        }
                    },
                    onVisualTarget = visualTarget@ { proof ->
                        val visible = presentation ?: return@visualTarget
                        if (
                            !presentationAllowed || actionBusy ||
                            hintSubmissionBlocked ||
                            visible.explanationMode != TutorExplanationMode.GUIDED ||
                            visible.interaction !is TutorCurrentSessionInteraction.VisualTarget
                        ) return@visualTarget
                        actionBusy = true
                        val generation = actionGeneration
                        scope.launch {
                            try {
                                val prepared = sessionHost.prepareVisualTarget(
                                    TutorCurrentSessionVisualTargetPreparation(
                                        sessionId = visible.sessionId,
                                        presentationToken = visible.presentationToken,
                                        hitProof = proof,
                                    ),
                                ) as? TutorCurrentSessionVisualTargetPreparationResult.Ready
                                    ?: run {
                                        if (generation == actionGeneration) {
                                            interactionBlocked = true
                                        }
                                        return@launch
                                    }
                                val result = sessionHost.submitVisualTarget(
                                    TutorCurrentSessionVisualTargetAction(
                                        sessionId = visible.sessionId,
                                        actionToken = prepared.prepared.actionToken,
                                        targetId = proof.selectedTargetId,
                                    ),
                                )
                                if (generation != actionGeneration) return@launch
                                when (result) {
                                    is TutorCurrentSessionVisualTargetActionResult.Answered ->
                                        actionFeedback =
                                            com.tingyun.smartmistakebook.core.domain
                                                .TutorCurrentSessionChoiceFeedback(
                                                    result.feedback.feedbackMarkdown,
                                                )
                                    is TutorCurrentSessionVisualTargetActionResult.Rejected -> {
                                        activationError = true
                                        interactionBlocked = true
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (generation == actionGeneration) {
                                    activationError = true
                                    interactionBlocked = true
                                }
                            } finally {
                                if (generation == actionGeneration) actionBusy = false
                            }
                        }
                    },
                    onDirectExplanation = {
                        onExplanationModeChange(TutorExplanationMode.DIRECT)
                    },
                    onShowHint = { hint -> commitVisibleHint(hint, true) },
                    onRetryHint = { hint -> commitVisibleHint(hint, false) },
                    visualAlreadyRequested =
                        effectiveVisualIntent == TutorCurrentSessionVisualIntent.USER_EXPLICIT,
                    onRequestVisual = {
                        requestedVisualIntent = TutorCurrentSessionVisualIntent.USER_EXPLICIT
                    },
                )
            }
            currentTask?.status?.let(ACTIVE_MODEL_STATUSES::contains) == true -> item("host_loading") {
                Text(
                    text = "正在讲解…",
                    color = InkSecondary,
                    modifier = Modifier.testTag("tutor_host_loading"),
                )
            }
            else -> item("host_start") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (policyFailed || providerFailed || activationError) {
                        Text(
                            text = "暂时无法讲解",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    PrimaryActionButton(
                        text = if (currentProvider == null) "配置模型" else "开始讲解",
                        onClick = {
                            if (currentProvider == null) {
                                onOpenModelSettings()
                            } else {
                                manualApprovedAt = clock()
                                manualLaunchNonce += 1
                            }
                        },
                        enabled = !policyFailed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("captured_tutor_start_model"),
                    )
                }
            }
        }
        item("host_footer") {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = trailingContent,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun HostPresentation(
    presentation: TutorCurrentSessionPresentation,
    actionBusy: Boolean,
    actionFeedback:
        com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceFeedback?,
    freeResponseDraft: String,
    submittedFreeResponse: String?,
    freeResponseSubmittedLocally: Boolean,
    locallyVisibleHintToken: String?,
    hintCommitBusyToken: String?,
    hintCommitFailedToken: String?,
    hintSubmissionBlocked: Boolean,
    interactionBlocked: Boolean,
    visualContent: @Composable (((TutorVisualHitProof) -> Unit)?) -> Unit,
    onChoice: (String) -> Unit,
    onFreeResponseChange: (String) -> Unit,
    onFreeResponse: () -> Unit,
    onFreeResponseRetry: () -> Unit,
    onVisualTarget: (TutorVisualHitProof) -> Unit,
    onDirectExplanation: () -> Unit,
    onShowHint: (TutorCurrentSessionHint) -> Unit,
    onRetryHint: (TutorCurrentSessionHint) -> Unit,
    visualAlreadyRequested: Boolean,
    onRequestVisual: () -> Unit,
) {
    val effectiveHintSubmissionBlocked = hintSubmissionBlocked ||
        (presentation.hint?.let { hint ->
            isHintSubmissionBlocked(
                hint = hint,
                locallyVisibleHintToken = locallyVisibleHintToken,
                hintCommitBusyToken = hintCommitBusyToken,
                hintCommitFailedToken = hintCommitFailedToken,
            )
        } == true)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("tutor_host_presentation"),
    ) {
        presentation.text.forEach { item ->
            SafeMarkdownText(
                markdown = item.markdown,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (!visualAlreadyRequested && presentation.visualScene == null) {
            OutlineActionChip(
                text = "图解",
                onClick = onRequestVisual,
                modifier = Modifier.testTag("tutor_request_visual"),
            )
        }
        visualContent(
            onVisualTarget.takeIf {
                actionFeedback == null &&
                    presentation.interaction is TutorCurrentSessionInteraction.VisualTarget &&
                    !effectiveHintSubmissionBlocked &&
                    !interactionBlocked
            },
        )
        if (actionFeedback == null && presentation.explanationMode == TutorExplanationMode.GUIDED) {
            when (val interaction = presentation.interaction) {
                is TutorCurrentSessionInteraction.Choices -> {
                    SafeMarkdownText(
                        markdown = interaction.promptMarkdown,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (interactionBlocked) {
                        OutlineActionChip(
                            text = "直接讲解",
                            onClick = onDirectExplanation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tutor_host_direct_explanation"),
                        )
                    } else {
                        interaction.choices.forEach { choice ->
                            OutlineActionChip(
                                text = choice.labelMarkdown,
                                onClick = { onChoice(choice.choiceId) },
                                enabled = !actionBusy && !effectiveHintSubmissionBlocked,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("tutor_host_choice_${choice.choiceId}"),
                            )
                        }
                    }
                }
                is TutorCurrentSessionInteraction.FreeResponse -> {
                    SafeMarkdownText(
                        markdown = interaction.promptMarkdown,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (interactionBlocked) {
                        Text(
                            text = "无法提交",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlineActionChip(
                            text = "直接讲解",
                            onClick = onDirectExplanation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tutor_host_direct_explanation"),
                        )
                    } else {
                        val submissionStatus = when {
                            actionBusy -> TutorCurrentSessionFreeResponseStatus.SENDING
                            freeResponseSubmittedLocally || submittedFreeResponse != null ->
                                TutorCurrentSessionFreeResponseStatus.COMPLETED
                            else -> interaction.submissionStatus
                        }
                        if (submissionStatus == TutorCurrentSessionFreeResponseStatus.COMPLETED) {
                            submittedFreeResponse?.let { answer ->
                                SafeMarkdownText(
                                    markdown = answer,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            } ?: Text(
                                text = "已提交",
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else if (submissionStatus == TutorCurrentSessionFreeResponseStatus.SENDING) {
                            Text(
                                text = "正在提交",
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else if (
                            submissionStatus == TutorCurrentSessionFreeResponseStatus.UNAVAILABLE
                        ) {
                            Text(
                                text = "无法提交",
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else if (
                            submissionStatus == TutorCurrentSessionFreeResponseStatus.RETRY_AVAILABLE
                        ) {
                            PrimaryActionButton(
                                text = "重试",
                                enabled = !actionBusy && !effectiveHintSubmissionBlocked,
                                onClick = onFreeResponseRetry,
                                modifier = Modifier.testTag("tutor_host_free_response_retry"),
                            )
                        } else {
                            TutorChatComposer(
                                value = freeResponseDraft,
                                enabled = !actionBusy && !effectiveHintSubmissionBlocked,
                                sending = actionBusy,
                                onValueChange = onFreeResponseChange,
                                onSend = onFreeResponse,
                                explanationMode = presentation.explanationMode,
                                showAttachments = false,
                                showGuidanceControl = false,
                                modifier = Modifier.testTag("tutor_host_free_response"),
                            )
                        }
                        if (
                            submissionStatus == TutorCurrentSessionFreeResponseStatus.READY
                        ) {
                            OutlineActionChip(
                                text = "直接讲解",
                                onClick = onDirectExplanation,
                                modifier = Modifier.testTag("tutor_host_direct_explanation"),
                            )
                        }
                    }
                }
                is TutorCurrentSessionInteraction.VisualTarget -> {
                    SafeMarkdownText(
                        markdown = interaction.promptMarkdown,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (interactionBlocked) {
                        OutlineActionChip(
                            text = "直接讲解",
                            onClick = onDirectExplanation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tutor_host_direct_explanation"),
                        )
                    }
                }
                null -> Unit
            }
        }
        actionFeedback?.let { feedback ->
            SafeMarkdownText(
                markdown = feedback.feedbackMarkdown,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        presentation.hint?.let { hint ->
            val visible = hint.status == TutorCurrentSessionHintStatus.SHOWN ||
                locallyVisibleHintToken == hint.slotToken
            if (visible) {
                SafeMarkdownText(
                    markdown = hint.markdown,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("tutor_host_hint_content"),
                )
                if (hintCommitFailedToken == hint.slotToken) {
                    OutlineActionChip(
                        text = "重试",
                        onClick = { onRetryHint(hint) },
                        enabled = hintCommitBusyToken != hint.slotToken,
                        modifier = Modifier.testTag("tutor_host_hint_retry"),
                    )
                }
            } else if (hint.status == TutorCurrentSessionHintStatus.AVAILABLE) {
                OutlineActionChip(
                    text = "提示一下",
                    onClick = { onShowHint(hint) },
                    enabled = hintCommitBusyToken != hint.slotToken,
                    modifier = Modifier.testTag("tutor_host_hint_action"),
                )
            }
        }
    }
}

private const val HINT_STABLE_VISIBILITY_MILLIS = 100L

/**
 * A hint that is locally visible but not yet durably recorded must block answer submission.
 * The durable record carries the hint fact into the evidence ledger; allowing an answer to slip
 * through the persistence window would record it as an independent attempt.
 */
internal fun isHintSubmissionBlocked(
    hint: TutorCurrentSessionHint?,
    locallyVisibleHintToken: String?,
    hintCommitBusyToken: String?,
    hintCommitFailedToken: String?,
): Boolean {
    if (hint == null || locallyVisibleHintToken != hint.slotToken) return false
    if (hint.status == TutorCurrentSessionHintStatus.SHOWN) return false
    return hintCommitBusyToken == hint.slotToken || hintCommitFailedToken == hint.slotToken
}

private fun ModelTaskSnapshot.matchesHostQuestion(
    question: TutorQuestionContext,
    policy: TutorCurrentSessionPolicySnapshot,
): Boolean {
    val input = request.input as? TutorPlanInput ?: return false
    return input.sessionId == question.sessionId &&
        input.draftRevisionNumber == question.revisionNumber &&
        input.questionDocument == question.questionDocument.document &&
        input.explanationMode == policy.explanationMode &&
        input.modeVersion == policy.modeVersion &&
        input.allowLongTermLearningWrites == policy.learningWritesAllowed &&
        input.learningWritePermissionVersion == policy.learningWritePermissionVersion
}

private fun TutorCurrentSessionHostSnapshot.toPolicySnapshot(): TutorCurrentSessionPolicySnapshot =
    TutorCurrentSessionPolicySnapshot(
        sessionId = sessionId,
        explanationMode = explanationMode,
        modeVersion = modeVersion,
        learningWritesAllowed = learningWritesAllowed,
        learningWritePermissionVersion = learningWritePermissionVersion,
        visualIntent = visualIntent,
        visualIntentVersion = visualIntentVersion,
    )

private val ACTIVE_MODEL_STATUSES = setOf(
    ModelTaskStatus.WAITING_FOR_MODEL,
    ModelTaskStatus.QUEUED,
    ModelTaskStatus.RUNNING,
    ModelTaskStatus.STREAMING,
)
