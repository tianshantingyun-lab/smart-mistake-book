package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorGuidancePolicy
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceRequest
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceState
import com.tingyun.smartmistakebook.core.domain.TutorProblemScope
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorVisualTargetEvidence
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeGuidance
import com.tingyun.smartmistakebook.core.model.TutorTeachingConstraint
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorFreeResponseEvaluation
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface

internal data class TutorTurnPresentation(
    val showDiagnostic: Boolean,
    val showCompleteExplanation: Boolean,
    val followUpMoves: List<TutorSuggestedMove>,
)

internal fun tutorTurnPresentation(
    mode: TutorExplanationMode,
    suggestedMoves: List<TutorSuggestedMove>,
): TutorTurnPresentation {
    val guided = mode == TutorExplanationMode.GUIDED
    return TutorTurnPresentation(
        showDiagnostic = guided,
        showCompleteExplanation = !guided,
        followUpMoves = suggestedMoves.take(MAX_MODEL_RELEVANT_FOLLOW_UPS),
    )
}

internal const val MAX_MODEL_RELEVANT_FOLLOW_UPS = 2

internal fun visibleTutorInteractionDirective(
    mode: TutorExplanationMode,
    directive: TutorInteractionDirective?,
): TutorInteractionDirective? = directive.takeIf { mode == TutorExplanationMode.GUIDED }

internal sealed interface TutorGuidanceEvent {
    val requestId: String

    data class Question(
        override val requestId: String,
        val masteryRelevant: Boolean,
    ) : TutorGuidanceEvent

    data class Evidence(
        override val requestId: String,
        val selectionWasCorrect: Boolean,
    ) : TutorGuidanceEvent

    data class Hint(override val requestId: String) : TutorGuidanceEvent

    data class Exposure(override val requestId: String) : TutorGuidanceEvent
}

internal fun freeResponseEvidenceEvent(
    requestId: String,
    output: TutorRespondOutput,
): TutorGuidanceEvent.Evidence? = when (output.freeResponseEvaluation) {
    TutorFreeResponseEvaluation.CORRECT ->
        TutorGuidanceEvent.Evidence(requestId, selectionWasCorrect = true)
    TutorFreeResponseEvaluation.INCORRECT ->
        TutorGuidanceEvent.Evidence(requestId, selectionWasCorrect = false)
    TutorFreeResponseEvaluation.UNKNOWN -> null
}

internal data class TutorGuidanceReplayTransition(
    val state: TutorGuidanceState,
    val cancelEvidenceRequestId: String?,
    val stateBeforePendingCancellation: TutorGuidanceState?,
)

internal fun replayTutorGuidanceTransition(
    problem: TutorProblemScope,
    requestedMode: TutorExplanationMode,
    answerWasExposed: Boolean,
    events: List<TutorGuidanceEvent>,
): TutorGuidanceReplayTransition {
    var state = TutorGuidanceState(problem, TutorExplanationMode.GUIDED)
    var cancelEvidenceRequestId: String? = null
    var stateBeforePendingCancellation: TutorGuidanceState? = null

    fun applyUnconsumedTransition(next: TutorGuidanceState) {
        if (
            state.mode == TutorExplanationMode.GUIDED &&
            next.mode == TutorExplanationMode.DIRECT &&
            state.pendingEvidenceRequestId != null
        ) {
            cancelEvidenceRequestId = state.pendingEvidenceRequestId
            stateBeforePendingCancellation =
                stateBeforePendingCancellation ?: state
        }
        state = next
    }

    events.forEach { event ->
        when (event) {
            is TutorGuidanceEvent.Question -> applyUnconsumedTransition(
                TutorGuidancePolicy.evaluate(
                    state,
                    TutorGuidanceRequest.question(
                        event.requestId,
                        problem,
                        event.masteryRelevant,
                    ),
                ).state,
            )
            is TutorGuidanceEvent.Evidence -> {
                val evidence = TutorGuidancePolicy.evaluate(
                    state,
                    TutorGuidanceRequest.evidence(event.requestId, problem),
                )
                state = evidence.state
                if (evidence.mayWriteLearningEvidence && !event.selectionWasCorrect) {
                    applyUnconsumedTransition(
                        TutorGuidancePolicy.evaluate(
                            state,
                            TutorGuidanceRequest.struggle(
                                "${event.requestId}:struggle",
                                problem,
                            ),
                        ).state,
                    )
                }
            }
            is TutorGuidanceEvent.Hint -> applyUnconsumedTransition(
                TutorGuidancePolicy.evaluate(
                    state,
                    TutorGuidanceRequest.hint(event.requestId, problem),
                ).state,
            )
            is TutorGuidanceEvent.Exposure -> applyUnconsumedTransition(
                TutorGuidancePolicy.evaluate(
                    state,
                    TutorGuidanceRequest.directExplanation(event.requestId, problem),
                ).state,
            )
        }
    }
    if (answerWasExposed || requestedMode == TutorExplanationMode.DIRECT) {
        val transition = TutorGuidancePolicy.transitionMode(
            state,
            TutorExplanationMode.DIRECT,
        )
        applyUnconsumedTransition(transition.state)
    }
    return TutorGuidanceReplayTransition(
        state = state,
        cancelEvidenceRequestId = cancelEvidenceRequestId,
        stateBeforePendingCancellation = stateBeforePendingCancellation,
    )
}

internal fun replayTutorGuidance(
    problem: TutorProblemScope,
    requestedMode: TutorExplanationMode,
    answerWasExposed: Boolean,
    events: List<TutorGuidanceEvent>,
): TutorGuidanceState = replayTutorGuidanceTransition(
    problem = problem,
    requestedMode = requestedMode,
    answerWasExposed = answerWasExposed,
    events = events,
).state

internal fun tutorResponseModeTransitionFor(
    state: TutorGuidanceState,
    studentMessage: String,
) = TutorGuidancePolicy.transitionMode(
    state,
    tutorResponseModeFor(state.mode, studentMessage),
)

internal data class TutorGuidanceModeResolution(
    val state: TutorGuidanceState,
    val cancelEvidenceRequestId: String?,
    val blockPendingInteraction: Boolean,
)

internal fun resolveTutorGuidanceMode(
    replay: TutorGuidanceReplayTransition,
    requestedMode: TutorExplanationMode,
    answerWasExposed: Boolean,
    cancellationConfirmed: Boolean?,
): TutorGuidanceModeResolution {
    val replayCancellationPending =
        replay.cancelEvidenceRequestId != null && cancellationConfirmed != true
    val replayBaseState = if (replayCancellationPending) {
        requireNotNull(replay.stateBeforePendingCancellation)
    } else {
        replay.state
    }
    val baseState = if (
        replay.cancelEvidenceRequestId == null &&
        cancellationConfirmed == true
    ) {
        replayBaseState.copy(pendingEvidenceRequestId = null)
    } else {
        replayBaseState
    }
    val targetDirect =
        replay.cancelEvidenceRequestId != null ||
            replay.state.mode == TutorExplanationMode.DIRECT ||
            requestedMode == TutorExplanationMode.DIRECT ||
            answerWasExposed
    val transition = if (targetDirect) {
        TutorGuidancePolicy.transitionMode(baseState, TutorExplanationMode.DIRECT)
    } else {
        null
    }
    val cancelEvidenceRequestId =
        replay.cancelEvidenceRequestId ?: transition?.cancelEvidenceRequestId
    val cancellationAcknowledged =
        cancelEvidenceRequestId == null || cancellationConfirmed == true
    val state = if (transition != null && cancellationAcknowledged) {
        transition.state
    } else {
        baseState
    }
    return TutorGuidanceModeResolution(
        state = state,
        cancelEvidenceRequestId = cancelEvidenceRequestId.takeUnless {
            cancellationAcknowledged
        },
        blockPendingInteraction =
            !cancellationAcknowledged ||
                (
                    !targetDirect &&
                        state.pendingEvidenceRequestId != null &&
                        cancellationConfirmed == null
                    ),
    )
}

internal suspend inline fun continueTutorResponseAfterEvidenceCancellation(
    cancelEvidenceRequestId: String?,
    cancellationConfirmed: Boolean,
    crossinline cancelEvidence: suspend (String) -> Unit,
    crossinline continueResponse: () -> Unit,
) {
    if (cancelEvidenceRequestId != null && !cancellationConfirmed) {
        cancelEvidence(cancelEvidenceRequestId)
    }
    continueResponse()
}

internal fun TutorGuidanceState.authorizeEvidence(requestId: String) =
    TutorGuidancePolicy.evaluate(
        this,
        TutorGuidanceRequest.evidence(requestId, problem),
    )

internal fun masteryTargetsAreRelevant(
    targetedEvidenceLabels: List<String>,
    teachingConstraints: List<TutorKnowledgeGuidance>,
): Boolean {
    if (targetedEvidenceLabels.isEmpty()) return false
    val guidanceByLabel = teachingConstraints.associateBy(TutorKnowledgeGuidance::label)
    return targetedEvidenceLabels.all { label ->
        guidanceByLabel[label]?.constraint == TutorTeachingConstraint.MAY_GUIDE
    }
}

internal inline fun requestTutorExplanationModeChange(
    mode: TutorExplanationMode,
    cancelPendingEvidence: () -> Unit,
    persistMode: (TutorExplanationMode) -> Unit,
) {
    if (mode == TutorExplanationMode.DIRECT) cancelPendingEvidence()
    persistMode(mode)
}

internal fun buildTutorGuidanceEvents(
    questionDocumentId: String,
    revisionNumber: Int,
    currentCycleTasks: List<ModelTaskSnapshot>,
    currentCycleResponses: List<TutorTurnResponse>,
    tutorRespondTasks: List<ModelTaskSnapshot>,
    visualTargetEvidence: List<TutorVisualTargetEvidence>,
): List<TutorGuidanceEvent> {
    val visualEvidenceByRequestId = visualTargetEvidence
        .filter { evidence ->
            evidence.questionDocumentId == questionDocumentId &&
                evidence.revisionNumber == revisionNumber
        }
        .associateBy { evidence -> evidence.modelTaskRequestId }
    return buildList {
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
