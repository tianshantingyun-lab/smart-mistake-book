package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import kotlinx.coroutines.flow.collect

internal fun tutorVisualProviderCanExecute(
    provider: ProviderCapabilitySnapshot?,
    taskKind: ModelTaskKind,
): Boolean = provider != null &&
    provider.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
    provider.supports(taskKind)

internal sealed interface TutorVisualExistingDecision {
    data object Skip : TutorVisualExistingDecision
    data object ExecuteNew : TutorVisualExistingDecision
    data class ExecuteExisting(val request: ModelTaskRequest) : TutorVisualExistingDecision
}

internal fun tutorVisualExistingDecision(
    existing: ModelTaskSnapshot?,
    provider: ProviderCapabilitySnapshot,
    taskKind: ModelTaskKind,
): TutorVisualExistingDecision = when {
    existing == null -> TutorVisualExistingDecision.ExecuteNew
    existing.status.isTutorExecutionPending() &&
        existing.coversCurrentTutorDisclosure(provider, taskKind) ->
        TutorVisualExistingDecision.ExecuteExisting(existing.request)
    else -> TutorVisualExistingDecision.Skip
}

internal class TutorVisualWorkCommands(
    private val sink: TutorVisualWorkSink,
) {
    suspend fun dispatchGenerate() {
        val provider = sink.currentProvider()?.takeIf {
            tutorVisualProviderCanExecute(it, ModelTaskKind.TUTOR_VISUAL_GENERATE)
        }
        val approvedAt = sink.generateApprovedAt()
        val assets = sink.sourceAssets()
        if (provider == null || approvedAt == null || assets.isEmpty()) {
            sink.setGenerateBuildFailures(emptySet())
            return
        }
        val failures = mutableSetOf<TutorVisualTurnAnchor>()
        sink.selectedSeeds().forEach { seed ->
            val request = runCatching {
                buildTutorVisualGenerateRequest(
                    question = sink.question(),
                    provider = provider,
                    sourceAssets = assets,
                    anchor = seed.anchor,
                    focusMarkdown = seed.request.focusMarkdown,
                    explanationMarkdown = seed.explanationMarkdown,
                    occurredAtEpochMillis = sink.clock(),
                    approvedAtEpochMillis = approvedAt,
                )
            }.getOrNull()
            if (request == null) {
                failures += seed.anchor
                return@forEach
            }
            when (
                val decision = tutorVisualExistingDecision(
                    existing = sink.generationTasks().lastOrNull { task ->
                        task.request.input == request.input
                    },
                    provider = provider,
                    taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
                )
            ) {
                TutorVisualExistingDecision.ExecuteNew ->
                    sink.modelTasks.execute(request).collect()
                is TutorVisualExistingDecision.ExecuteExisting ->
                    sink.modelTasks.execute(decision.request).collect()
                TutorVisualExistingDecision.Skip -> Unit
            }
        }
        sink.setGenerateBuildFailures(failures)
    }

    suspend fun dispatchReview() {
        val provider = sink.currentProvider()?.takeIf {
            tutorVisualProviderCanExecute(it, ModelTaskKind.TUTOR_VISUAL_REVIEW)
        }
        val approvedAt = sink.reviewApprovedAt()
        val assets = sink.sourceAssets()
        if (provider == null || approvedAt == null || assets.isEmpty()) {
            sink.setReviewBuildFailures(emptySet())
            return
        }
        val failures = mutableSetOf<TutorVisualTurnAnchor>()
        sink.selectedSeeds().forEach { seed ->
            val resolution = resolveTutorVisual(
                anchor = seed.anchor,
                question = sink.question(),
                generationTasks = sink.generationTasks(),
                reviewTasks = sink.reviewTasks(),
            ) as? TutorVisualResolution.NeedsReview ?: return@forEach
            val request = runCatching {
                buildTutorVisualReviewRequest(
                    question = sink.question(),
                    provider = provider,
                    sourceAssets = assets,
                    generationRequest = resolution.generationTask.request,
                    generated = resolution.output,
                    reviewReasonCodes = resolution.reasonCodes,
                    occurredAtEpochMillis = sink.clock(),
                    approvedAtEpochMillis = approvedAt,
                )
            }.getOrNull()
            if (request == null) {
                failures += seed.anchor
                return@forEach
            }
            when (
                val decision = tutorVisualExistingDecision(
                    existing = sink.reviewTasks().lastOrNull { task ->
                        task.request.input == request.input
                    },
                    provider = provider,
                    taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
                )
            ) {
                TutorVisualExistingDecision.ExecuteNew ->
                    sink.modelTasks.execute(request).collect()
                is TutorVisualExistingDecision.ExecuteExisting ->
                    sink.modelTasks.execute(decision.request).collect()
                TutorVisualExistingDecision.Skip -> Unit
            }
        }
        sink.setReviewBuildFailures(failures)
    }
}

internal class TutorVisualWorkSink(
    val currentProvider: () -> ProviderCapabilitySnapshot?,
    val question: () -> TutorQuestionContext,
    val clock: () -> Long,
    val generateApprovedAt: () -> Long?,
    val reviewApprovedAt: () -> Long?,
    val sourceAssets: () -> List<TutorVisualSourceAssetScope>,
    val selectedSeeds: () -> List<TutorVisualWorkSeed>,
    val generationTasks: () -> List<ModelTaskSnapshot>,
    val reviewTasks: () -> List<ModelTaskSnapshot>,
    val setGenerateBuildFailures: (Set<TutorVisualTurnAnchor>) -> Unit,
    val setReviewBuildFailures: (Set<TutorVisualTurnAnchor>) -> Unit,
    val modelTasks: ModelTaskRepository,
)
