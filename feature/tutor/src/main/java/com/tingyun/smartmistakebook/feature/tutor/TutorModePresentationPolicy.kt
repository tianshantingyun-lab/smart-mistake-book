package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorGuidancePolicy
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceRequest
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceState
import com.tingyun.smartmistakebook.core.domain.TutorProblemScope
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective

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
}

internal fun replayTutorGuidance(
    problem: TutorProblemScope,
    requestedMode: TutorExplanationMode,
    answerWasExposed: Boolean,
    events: List<TutorGuidanceEvent>,
): TutorGuidanceState {
    var state = TutorGuidanceState(problem, TutorExplanationMode.GUIDED)
    events.forEach { event ->
        state = when (event) {
            is TutorGuidanceEvent.Question -> TutorGuidancePolicy.evaluate(
                state,
                TutorGuidanceRequest.question(
                    event.requestId,
                    problem,
                    event.masteryRelevant,
                ),
            ).state
            is TutorGuidanceEvent.Evidence -> {
                val evidence = TutorGuidancePolicy.evaluate(
                    state,
                    TutorGuidanceRequest.evidence(event.requestId, problem),
                )
                if (!evidence.mayWriteLearningEvidence || event.selectionWasCorrect) {
                    evidence.state
                } else {
                    TutorGuidancePolicy.evaluate(
                        evidence.state,
                        TutorGuidanceRequest.struggle("${event.requestId}:struggle", problem),
                    ).state
                }
            }
            is TutorGuidanceEvent.Hint -> TutorGuidancePolicy.evaluate(
                state,
                TutorGuidanceRequest.hint(event.requestId, problem),
            ).state
        }
    }
    if (answerWasExposed || requestedMode == TutorExplanationMode.DIRECT) {
        state = TutorGuidancePolicy.transitionMode(
            state,
            TutorExplanationMode.DIRECT,
        ).state
    }
    return state
}

internal fun TutorGuidanceState.authorizeEvidence(requestId: String) =
    TutorGuidancePolicy.evaluate(
        this,
        TutorGuidanceRequest.evidence(requestId, problem),
    )
