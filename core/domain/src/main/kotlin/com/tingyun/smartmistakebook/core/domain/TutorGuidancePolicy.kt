package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode

data class TutorProblemScope(
    val problemId: String,
    val revisionNumber: Int,
) {
    init {
        require(problemId.isNotBlank())
        require(revisionNumber > 0)
    }
}

data class TutorGuidanceState(
    val problem: TutorProblemScope,
    val mode: TutorExplanationMode,
    val questionsAsked: Int = 0,
    val hintsUsed: Int = 0,
    val strugglesObserved: Int = 0,
    val pendingEvidenceRequestId: String? = null,
) {
    init {
        require(questionsAsked >= 0 && hintsUsed >= 0 && strugglesObserved >= 0)
        require(pendingEvidenceRequestId == null || pendingEvidenceRequestId.isNotBlank())
    }
}

enum class TutorGuidanceAction {
    QUESTION,
    HINT,
    STRUGGLE,
    DIRECT_EXPLANATION,
    VISUAL_BROWSE,
    ANSWER_EVIDENCE,
}

data class TutorGuidanceRequest(
    val requestId: String,
    val problem: TutorProblemScope,
    val action: TutorGuidanceAction,
    val masteryRelevant: Boolean = true,
) {
    init {
        require(requestId.isNotBlank())
    }

    companion object {
        fun question(
            requestId: String,
            problem: TutorProblemScope,
            masteryRelevant: Boolean,
        ) = TutorGuidanceRequest(
            requestId,
            problem,
            TutorGuidanceAction.QUESTION,
            masteryRelevant,
        )

        fun hint(requestId: String, problem: TutorProblemScope) =
            TutorGuidanceRequest(requestId, problem, TutorGuidanceAction.HINT)

        fun struggle(requestId: String, problem: TutorProblemScope) =
            TutorGuidanceRequest(requestId, problem, TutorGuidanceAction.STRUGGLE)

        fun directExplanation(requestId: String, problem: TutorProblemScope) =
            TutorGuidanceRequest(requestId, problem, TutorGuidanceAction.DIRECT_EXPLANATION)

        fun visualBrowse(requestId: String, problem: TutorProblemScope) =
            TutorGuidanceRequest(requestId, problem, TutorGuidanceAction.VISUAL_BROWSE)

        fun evidence(requestId: String, problem: TutorProblemScope) =
            TutorGuidanceRequest(requestId, problem, TutorGuidanceAction.ANSWER_EVIDENCE)
    }
}

enum class TutorGuidanceOutcome {
    GUIDED,
    DIRECT_EXPLANATION,
    READ_ONLY,
    EVIDENCE_ACCEPTED,
    REJECTED_STALE,
}

data class TutorGuidanceDecision(
    val outcome: TutorGuidanceOutcome,
    val state: TutorGuidanceState,
    val mayWriteLearningEvidence: Boolean = false,
)

data class TutorGuidanceModeTransition(
    val state: TutorGuidanceState,
    val cancelEvidenceRequestId: String?,
    val requestDirectContinuation: Boolean,
)

/**
 * Fail-closed local authority for guided tutoring.
 *
 * Model output can suggest an interaction, but only this policy can keep it in the current
 * problem, spend the bounded guidance budget, or authorize one exact evidence completion.
 */
object TutorGuidancePolicy {
    const val MAX_QUESTIONS = 3
    const val MAX_HINTS = 1
    const val STRUGGLES_BEFORE_DIRECT = 2

    fun transitionMode(
        state: TutorGuidanceState,
        target: TutorExplanationMode,
    ): TutorGuidanceModeTransition {
        val leavingGuided = state.mode == TutorExplanationMode.GUIDED &&
            target == TutorExplanationMode.DIRECT
        return TutorGuidanceModeTransition(
            state = state.copy(
                mode = target,
                pendingEvidenceRequestId = if (leavingGuided) {
                    null
                } else {
                    state.pendingEvidenceRequestId
                },
            ),
            cancelEvidenceRequestId = state.pendingEvidenceRequestId.takeIf { leavingGuided },
            requestDirectContinuation = leavingGuided,
        )
    }

    fun evaluate(
        state: TutorGuidanceState,
        request: TutorGuidanceRequest,
    ): TutorGuidanceDecision {
        if (request.problem != state.problem) return state.rejected()
        if (request.action == TutorGuidanceAction.VISUAL_BROWSE) {
            return TutorGuidanceDecision(TutorGuidanceOutcome.READ_ONLY, state)
        }
        if (request.action == TutorGuidanceAction.ANSWER_EVIDENCE) {
            val current = state.mode == TutorExplanationMode.GUIDED &&
                request.requestId == state.pendingEvidenceRequestId
            return if (current) {
                TutorGuidanceDecision(
                    outcome = TutorGuidanceOutcome.EVIDENCE_ACCEPTED,
                    state = state.copy(pendingEvidenceRequestId = null),
                    mayWriteLearningEvidence = true,
                )
            } else {
                state.rejected()
            }
        }
        if (
            state.mode == TutorExplanationMode.DIRECT ||
            request.action == TutorGuidanceAction.DIRECT_EXPLANATION
        ) {
            return state.direct()
        }
        return when (request.action) {
            TutorGuidanceAction.QUESTION -> if (
                request.masteryRelevant && state.questionsAsked < MAX_QUESTIONS
            ) {
                TutorGuidanceDecision(
                    TutorGuidanceOutcome.GUIDED,
                    state.copy(
                        questionsAsked = state.questionsAsked + 1,
                        pendingEvidenceRequestId = request.requestId,
                    ),
                )
            } else {
                state.direct()
            }
            TutorGuidanceAction.HINT -> if (state.hintsUsed < MAX_HINTS) {
                TutorGuidanceDecision(
                    TutorGuidanceOutcome.GUIDED,
                    state.copy(hintsUsed = state.hintsUsed + 1),
                )
            } else {
                state.direct()
            }
            TutorGuidanceAction.STRUGGLE -> {
                val next = state.copy(strugglesObserved = state.strugglesObserved + 1)
                if (next.strugglesObserved >= STRUGGLES_BEFORE_DIRECT) {
                    next.direct()
                } else {
                    TutorGuidanceDecision(TutorGuidanceOutcome.GUIDED, next)
                }
            }
            TutorGuidanceAction.DIRECT_EXPLANATION,
            TutorGuidanceAction.VISUAL_BROWSE,
            TutorGuidanceAction.ANSWER_EVIDENCE,
            -> error("Handled above")
        }
    }

    private fun TutorGuidanceState.direct() = TutorGuidanceDecision(
        TutorGuidanceOutcome.DIRECT_EXPLANATION,
        copy(
            mode = TutorExplanationMode.DIRECT,
            pendingEvidenceRequestId = null,
        ),
    )

    private fun TutorGuidanceState.rejected() = TutorGuidanceDecision(
        TutorGuidanceOutcome.REJECTED_STALE,
        this,
    )
}
