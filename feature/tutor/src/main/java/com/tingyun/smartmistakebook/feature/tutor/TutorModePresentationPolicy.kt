package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorGuidancePolicy
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove

internal data class TutorTurnPresentation(
    val showDiagnostic: Boolean,
    val showCompleteExplanation: Boolean,
    val followUpMoves: List<TutorSuggestedMove>,
)

internal fun tutorTurnPresentation(
    mode: TutorExplanationMode,
    guidedQuestionOrdinal: Int,
    strugglesObserved: Int,
    suggestedMoves: List<TutorSuggestedMove>,
): TutorTurnPresentation {
    val guided = mode == TutorExplanationMode.GUIDED &&
        guidedQuestionOrdinal <= TutorGuidancePolicy.MAX_QUESTIONS &&
        strugglesObserved < TutorGuidancePolicy.STRUGGLES_BEFORE_DIRECT
    return TutorTurnPresentation(
        showDiagnostic = guided,
        showCompleteExplanation = !guided,
        followUpMoves = suggestedMoves.take(MAX_MODEL_RELEVANT_FOLLOW_UPS),
    )
}

internal const val MAX_MODEL_RELEVANT_FOLLOW_UPS = 2
