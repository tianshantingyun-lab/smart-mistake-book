package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationRequest
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import kotlinx.coroutines.CancellationException

/**
 * One-question visual continuation for the trusted Host presentation.
 *
 * Text activation is independent: this component consumes only the plan's bounded visual hand-off,
 * executes the existing generic generate/review tasks, and inserts a locally validated scene later.
 * Scene browsing is intentionally disconnected from every learning-evidence API.
 */
@Composable
internal fun TutorCurrentSessionHostVisual(
    question: TutorQuestionContext,
    ownerTask: ModelTaskSnapshot?,
    inlineScene: TutorVisualScene?,
    visualIntent: TutorCurrentSessionVisualIntent,
    policyFence: String,
    modelTasks: ScopedModelTaskPort,
    provider: ProviderCapabilitySnapshot?,
    providerLoadFailed: Boolean,
    compositionEgressLease: TutorCompositionEgressLease?,
    sourceAssetsReader: suspend () -> List<TutorVisualSourceAssetScope>,
    originalAvailable: Boolean,
    onOpenOriginal: () -> Unit,
    onTargetHit: ((TutorVisualHitProof) -> Unit)?,
    clock: () -> Long,
    modifier: Modifier = Modifier,
) {
    val generationTasks by remember(question.sessionId, modelTasks) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_VISUAL_GENERATE)
    }.collectAsState(initial = emptyList())
    val reviewTasks by remember(question.sessionId, modelTasks) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_VISUAL_REVIEW)
    }.collectAsState(initial = emptyList())
    val seed = remember(
        ownerTask?.request?.requestId,
        ownerTask?.stateVersion,
        visualIntent,
        question.title,
    ) {
        ownerTask?.let { task ->
            tutorVisualWorkSeeds(planTasks = listOf(task), respondTasks = emptyList())
                .singleOrNull()
                ?: explicitCurrentTutorVisualSeed(task, question, visualIntent)
        }
    }
    val semanticFence = remember(
        question.sessionId,
        question.revisionNumber,
        ownerTask?.request?.requestId,
        ownerTask?.requestFingerprint,
        policyFence,
    ) {
        "session:${question.sessionId}:question:${question.questionDocument.document.id}:" +
            "revision:${question.revisionNumber}:owner:${ownerTask?.request?.requestId.orEmpty()}:" +
            "owner-fingerprint:${ownerTask?.requestFingerprint.orEmpty()}:policy:$policyFence"
    }
    val seedKey = seed?.let { work ->
        "${ownerTask?.request?.requestId}:${work.anchor}:${work.request.focusMarkdown}"
    }
    val latestSourceReader by rememberUpdatedState(sourceAssetsReader)
    var sourceState by remember(question.sessionId, question.revisionNumber, seedKey) {
        mutableStateOf<HostVisualSourceState>(
            if (seed == null) HostVisualSourceState.Ready(emptyList())
            else HostVisualSourceState.Loading,
        )
    }
    var retryNonce by remember(question.sessionId, question.revisionNumber, seedKey) {
        mutableIntStateOf(0)
    }
    var retryLease by remember(
        question.sessionId,
        question.revisionNumber,
        provider?.providerId,
        provider?.providerConfigurationVersion,
    ) { mutableStateOf<TutorCompositionEgressLease?>(null) }
    var failedSemanticRequestId by remember(question.sessionId, question.revisionNumber, seedKey) {
        mutableStateOf<String?>(null)
    }
    var completedSemanticRequestIds by remember(
        question.sessionId,
        question.revisionNumber,
        seedKey,
    ) { mutableStateOf<Set<String>>(emptySet()) }
    var reportedSceneIds by remember(question.sessionId, question.revisionNumber) {
        mutableStateOf<Set<String>>(emptySet())
    }

    LaunchedEffect(question.sessionId, question.revisionNumber, seedKey) {
        if (seed == null) return@LaunchedEffect
        sourceState = try {
            val assets = latestSourceReader()
                .sortedBy(TutorVisualSourceAssetScope::pageIndex)
            if (assets.map(TutorVisualSourceAssetScope::pageIndex) != assets.indices.toList()) {
                HostVisualSourceState.Failed
            } else {
                HostVisualSourceState.Ready(assets)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HostVisualSourceState.Failed
        }
    }

    val visualProvider = provider?.takeIf { candidate ->
        candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
            candidate.supports(ModelTaskKind.TUTOR_VISUAL_GENERATE)
    }
    val sourceAssets = (sourceState as? HostVisualSourceState.Ready)?.assets.orEmpty()
    val expectedGenerationRequestId = if (
        seed != null && visualProvider != null && sourceAssets.isNotEmpty()
    ) {
        tutorVisualGenerateRequestId(
            question = question,
            provider = visualProvider,
            sourceAssets = sourceAssets,
            anchor = seed.anchor,
            focusMarkdown = seed.request.focusMarkdown,
            explanationMarkdown = seed.explanationMarkdown,
            semanticFence = semanticFence,
        )
    } else {
        null
    }
    val effectiveLease = retryLease ?: compositionEgressLease
    val authorizationNow = clock().coerceAtLeast(0L)
    val stableLocalGenerationApproval = remember(expectedGenerationRequestId) { authorizationNow }
    val generationApprovedAt = visualProvider?.let { candidate ->
        when (candidate.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> effectiveLease?.approvedAtFor(
                question = question,
                provider = candidate,
                taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
                nowEpochMillis = authorizationNow,
            )
            ModelExecutionLocation.LOCAL_NO_EGRESS -> stableLocalGenerationApproval
            ModelExecutionLocation.UNAVAILABLE -> null
        }
    }
    val generationRequest = if (
        seed != null && visualProvider != null && generationApprovedAt != null &&
        sourceAssets.isNotEmpty()
    ) {
        runCatching {
            buildTutorVisualGenerateRequest(
                question = question,
                provider = visualProvider,
                sourceAssets = sourceAssets,
                anchor = seed.anchor,
                focusMarkdown = seed.request.focusMarkdown,
                explanationMarkdown = seed.explanationMarkdown,
                occurredAtEpochMillis = if (
                    visualProvider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER
                ) generationApprovedAt else stableLocalGenerationApproval,
                approvedAtEpochMillis = generationApprovedAt,
                semanticFence = semanticFence,
            )
        }.getOrNull()
    } else {
        null
    }

    LaunchedEffect(
        expectedGenerationRequestId,
        generationApprovedAt,
        retryNonce,
        modelTasks,
    ) {
        val baseRequest = generationRequest ?: return@LaunchedEffect
        val existing = latestTutorVisualTask(generationTasks, baseRequest)
        val request = when {
            existing == null -> baseRequest
            existing.status == ModelTaskStatus.SUCCEEDED ||
                existing.status in HOST_VISUAL_ACTIVE_MODEL_STATUSES -> return@LaunchedEffect
            retryNonce == 0 -> return@LaunchedEffect
            else -> freshTutorVisualRetryRequest(baseRequest, existing) ?: return@LaunchedEffect
        }
        try {
            failedSemanticRequestId = null
            completedSemanticRequestIds = completedSemanticRequestIds - baseRequest.requestId
            val outcome = collectTutorVisualExecution(modelTasks.execute(request))
            when (outcome) {
                TutorVisualExecutionOutcome.COMPLETED ->
                    completedSemanticRequestIds =
                        completedSemanticRequestIds + baseRequest.requestId
                TutorVisualExecutionOutcome.FAILED ->
                    failedSemanticRequestId = baseRequest.requestId
            }
        } catch (cancelled: CancellationException) {
            modelTasks.cancel(request.requestId)
            throw cancelled
        }
    }

    val generatedResolution = when {
        inlineScene != null && ownerTask != null ->
            inlineTutorVisualResolution(inlineScene, ownerTask.request.requestId)
        seed == null -> TutorVisualResolution.Hidden
        sourceState == HostVisualSourceState.Loading -> TutorVisualResolution.Preparing
        sourceState == HostVisualSourceState.Failed || sourceAssets.isEmpty() ->
            TutorVisualResolution.Fallback(TutorVisualFallbackReason.SOURCE_UNAVAILABLE)
        provider == null -> tutorVisualProviderLoadingResolution(
            provider = null,
            providerLoadFailed = providerLoadFailed,
        ) ?: TutorVisualResolution.Preparing
        visualProvider == null ->
            TutorVisualResolution.Fallback(TutorVisualFallbackReason.PROVIDER_UNAVAILABLE)
        expectedGenerationRequestId == null ->
            TutorVisualResolution.Fallback(TutorVisualFallbackReason.INVALID_OUTPUT)
        else -> resolveTutorVisual(
            anchor = seed.anchor,
            question = question,
            generationTasks = generationTasks,
            reviewTasks = reviewTasks,
            expectedGenerationRequestId = expectedGenerationRequestId,
            reviewProvider = visualProvider,
        )
    }
    val reviewCandidate = when (generatedResolution) {
        is TutorVisualResolution.Reviewing -> generatedResolution
        is TutorVisualResolution.Fallback -> generatedResolution.reviewCandidate
        else -> null
    }
    val expectedReviewRequestId = if (visualProvider != null && reviewCandidate != null) {
        tutorVisualReviewRequestId(
            generationRequestId = reviewCandidate.generationTask.request.requestId,
            provider = visualProvider,
            generated = reviewCandidate.output,
            reviewReasonCodes = reviewCandidate.reasonCodes,
        )
    } else {
        null
    }
    val stableLocalReviewApproval = remember(expectedReviewRequestId) { authorizationNow }
    val reviewApprovedAt = visualProvider?.takeIf {
        it.supports(ModelTaskKind.TUTOR_VISUAL_REVIEW)
    }?.let { candidate ->
        when (candidate.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> effectiveLease?.approvedAtFor(
                question = question,
                provider = candidate,
                taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
                nowEpochMillis = authorizationNow,
            )
            ModelExecutionLocation.LOCAL_NO_EGRESS -> stableLocalReviewApproval
            ModelExecutionLocation.UNAVAILABLE -> null
        }
    }
    val reviewRequest = if (
        visualProvider != null && reviewCandidate != null && reviewApprovedAt != null
    ) {
        runCatching {
            buildTutorVisualReviewRequest(
                question = question,
                provider = visualProvider,
                sourceAssets = sourceAssets,
                generationRequest = reviewCandidate.generationTask.request,
                generated = reviewCandidate.output,
                reviewReasonCodes = reviewCandidate.reasonCodes,
                occurredAtEpochMillis = if (
                    visualProvider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER
                ) reviewApprovedAt else stableLocalReviewApproval,
                approvedAtEpochMillis = reviewApprovedAt,
            )
        }.getOrNull()
    } else {
        null
    }

    LaunchedEffect(expectedReviewRequestId, reviewApprovedAt, retryNonce, modelTasks) {
        val baseRequest = reviewRequest ?: return@LaunchedEffect
        val existing = latestTutorVisualTask(reviewTasks, baseRequest)
        val request = when {
            existing == null -> baseRequest
            existing.status == ModelTaskStatus.SUCCEEDED ||
                existing.status in HOST_VISUAL_ACTIVE_MODEL_STATUSES -> return@LaunchedEffect
            retryNonce == 0 -> return@LaunchedEffect
            else -> freshTutorVisualRetryRequest(baseRequest, existing) ?: return@LaunchedEffect
        }
        try {
            failedSemanticRequestId = null
            completedSemanticRequestIds = completedSemanticRequestIds - baseRequest.requestId
            val outcome = collectTutorVisualExecution(modelTasks.execute(request))
            when (outcome) {
                TutorVisualExecutionOutcome.COMPLETED ->
                    completedSemanticRequestIds =
                        completedSemanticRequestIds + baseRequest.requestId
                TutorVisualExecutionOutcome.FAILED ->
                    failedSemanticRequestId = baseRequest.requestId
            }
        } catch (cancelled: CancellationException) {
            modelTasks.cancel(request.requestId)
            throw cancelled
        }
    }

    val hasGenerationTask = expectedGenerationRequestId != null && generationTasks.any { task ->
        task.request.requestId == expectedGenerationRequestId ||
            task.request.requestId.startsWith("$expectedGenerationRequestId:retry:")
    }
    val hasReviewTask = expectedReviewRequestId != null && reviewTasks.any { task ->
        task.request.requestId == expectedReviewRequestId ||
            task.request.requestId.startsWith("$expectedReviewRequestId:retry:")
    }
    val resolution = when {
        generatedResolution is TutorVisualResolution.Ready &&
            generatedResolution.scene.sceneId in reportedSceneIds ->
            TutorVisualResolution.Fallback(TutorVisualFallbackReason.REPORTED)
        generatedResolution !is TutorVisualResolution.Ready &&
            (
                failedSemanticRequestId == expectedGenerationRequestId ||
                    failedSemanticRequestId == expectedReviewRequestId
            ) ->
            TutorVisualResolution.Fallback(
                reason = TutorVisualFallbackReason.NOT_STARTED,
                canRetry = true,
                reviewCandidate = reviewCandidate,
            )
        generatedResolution == TutorVisualResolution.Preparing &&
            expectedGenerationRequestId != null && generationApprovedAt != null &&
            generationRequest == null ->
            TutorVisualResolution.Fallback(TutorVisualFallbackReason.INVALID_OUTPUT)
        generatedResolution == TutorVisualResolution.Preparing &&
            expectedGenerationRequestId != null && !hasGenerationTask &&
            expectedGenerationRequestId in completedSemanticRequestIds ->
            TutorVisualResolution.Fallback(
                reason = TutorVisualFallbackReason.NOT_STARTED,
                canRetry = true,
            )
        generatedResolution == TutorVisualResolution.Preparing &&
            !hasGenerationTask && generationApprovedAt == null ->
            TutorVisualResolution.Fallback(
                reason = TutorVisualFallbackReason.NOT_STARTED,
                canRetry = visualProvider != null,
            )
        generatedResolution is TutorVisualResolution.Reviewing &&
            expectedReviewRequestId != null && reviewApprovedAt != null &&
            reviewRequest == null ->
            TutorVisualResolution.Fallback(TutorVisualFallbackReason.INVALID_OUTPUT)
        generatedResolution is TutorVisualResolution.Reviewing &&
            expectedReviewRequestId != null && !hasReviewTask &&
            expectedReviewRequestId in completedSemanticRequestIds ->
            TutorVisualResolution.Fallback(
                reason = TutorVisualFallbackReason.NOT_STARTED,
                canRetry = true,
                reviewCandidate = reviewCandidate,
            )
        generatedResolution is TutorVisualResolution.Reviewing && reviewApprovedAt == null ->
            TutorVisualResolution.Fallback(
                reason = TutorVisualFallbackReason.NOT_STARTED,
                canRetry = visualProvider?.supports(ModelTaskKind.TUTOR_VISUAL_REVIEW) == true,
                reviewCandidate = reviewCandidate,
            )
        else -> generatedResolution
    }

    TutorVisualPresentation(
        state = resolution,
        mode = TutorVisualPresentationMode.CURRENT_EXPANDED,
        originalAvailable = originalAvailable,
        onRetry = {
            retryLease = visualProvider
                ?.takeIf { candidate ->
                    candidate.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER
                }
                ?.let { candidate ->
                    TutorCompositionEgressLease.grant(
                        question = question,
                        provider = candidate,
                        approvedAtEpochMillis = clock().coerceAtLeast(0L),
                    )
                }
            failedSemanticRequestId = null
            retryNonce += 1
        },
        onOpenOriginal = onOpenOriginal,
        onReportIncorrect = { sceneId -> reportedSceneIds = reportedSceneIds + sceneId },
        ownerModelTaskRequestId = ownerTask?.request?.requestId,
        onTargetHit = onTargetHit,
        modifier = modifier,
    )
}

private fun explicitCurrentTutorVisualSeed(
    ownerTask: ModelTaskSnapshot,
    question: TutorQuestionContext,
    visualIntent: TutorCurrentSessionVisualIntent,
): TutorVisualWorkSeed? {
    if (visualIntent != TutorCurrentSessionVisualIntent.USER_EXPLICIT) return null
    if (ownerTask.status != ModelTaskStatus.SUCCEEDED) return null
    val input = ownerTask.request.input as? TutorPlanInput ?: return null
    val output = ownerTask.output as? TutorPlanOutput ?: return null
    if (
        input.sessionId != question.sessionId ||
        input.draftRevisionNumber != question.revisionNumber ||
        input.questionDocument.id != question.questionDocument.document.id ||
        output.sessionId != question.sessionId ||
        output.draftRevisionNumber != question.revisionNumber ||
        output.questionDocumentId != question.questionDocument.document.id
    ) return null
    return TutorVisualWorkSeed(
        anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.PLAN,
            cycleOrdinal = input.cycleOrdinal,
            turnOrdinal = input.turnOrdinal,
        ),
        request = TutorVisualGenerationRequest(
            focusMarkdown = "围绕当前小问生成能直接帮助理解的图解",
        ),
        explanationMarkdown = buildString {
            append(output.plan.openingMarkdown)
            if (output.plan.solutionRevealed) {
                append("\n\n").append(output.plan.solutionMarkdown)
            }
        },
    )
}

private sealed interface HostVisualSourceState {
    data object Loading : HostVisualSourceState
    data object Failed : HostVisualSourceState
    data class Ready(val assets: List<TutorVisualSourceAssetScope>) : HostVisualSourceState
}

private val HOST_VISUAL_ACTIVE_MODEL_STATUSES = setOf(
    ModelTaskStatus.WAITING_FOR_MODEL,
    ModelTaskStatus.QUEUED,
    ModelTaskStatus.RUNNING,
    ModelTaskStatus.STREAMING,
)
