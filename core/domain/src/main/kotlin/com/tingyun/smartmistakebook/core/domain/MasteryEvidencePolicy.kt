package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AssessmentSubmissionContext
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason as EvidenceReason
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem

enum class MasteryEvidenceKind {
    INDEPENDENT,
    ASSISTED,
    EXCLUDED,
}

enum class MasteryEvidenceReason {
    INDEPENDENT_CORRECT_RESPONSE,
    CORRECT_AFTER_HINT,
    CORRECT_ON_RETRY,
    ANSWER_WAS_REVEALED,
    INCORRECT_RESPONSE,
    INCORRECT_AFTER_REVEAL,
}

data class MasteryEvidenceDecision(
    val kind: MasteryEvidenceKind,
    val reason: MasteryEvidenceReason,
    val evidence: LearningEvidence,
    val problemMemoryOutcome: ProblemMemoryOutcome,
) {
    val contributesToMastery: Boolean
        get() = evidence.direction != LearningEvidenceDirection.NONE

    val isIndependent: Boolean
        get() = evidence.isIndependent

    val signedWeight: Double
        get() = evidence.signedWeight
}

object MasteryEvidencePolicy {
    const val VERSION = LearningCoreVersions.EVIDENCE

    fun evaluate(
        assessmentItem: TutorAssessmentItem,
        context: AssessmentSubmissionContext,
    ): MasteryEvidenceDecision {
        require(context.assessmentItemId == assessmentItem.id) {
            "Submission context must belong to the assessed item"
        }
        val evaluation = assessmentItem.evaluateChoice(context.selectedChoiceId)

        return when {
            context.answerWasRevealed && !evaluation.isCorrect -> MasteryEvidenceDecision(
                MasteryEvidenceKind.ASSISTED,
                MasteryEvidenceReason.INCORRECT_AFTER_REVEAL,
                LearningEvidence(
                    direction = LearningEvidenceDirection.NEGATIVE,
                    weight = 0.6,
                    reason = EvidenceReason.INCORRECT_AFTER_REVEAL,
                ),
                ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            )

            context.answerWasRevealed -> MasteryEvidenceDecision(
                MasteryEvidenceKind.EXCLUDED,
                MasteryEvidenceReason.ANSWER_WAS_REVEALED,
                LearningEvidence(
                    direction = LearningEvidenceDirection.NONE,
                    weight = 0.0,
                    reason = EvidenceReason.ANSWER_REVEALED,
                ),
                ProblemMemoryOutcome.ANSWER_REVEALED,
            )

            !evaluation.isCorrect && context.hintWasUsed -> MasteryEvidenceDecision(
                MasteryEvidenceKind.ASSISTED,
                MasteryEvidenceReason.INCORRECT_RESPONSE,
                LearningEvidence(
                    direction = LearningEvidenceDirection.NEGATIVE,
                    weight = 0.9,
                    reason = EvidenceReason.INCORRECT_AFTER_HINT,
                ),
                ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            )

            !evaluation.isCorrect && context.responseOrdinal > 1 -> MasteryEvidenceDecision(
                MasteryEvidenceKind.ASSISTED,
                MasteryEvidenceReason.INCORRECT_RESPONSE,
                LearningEvidence(
                    direction = LearningEvidenceDirection.NEGATIVE,
                    weight = 0.9,
                    reason = EvidenceReason.INCORRECT_ON_RETRY,
                ),
                ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            )

            !evaluation.isCorrect -> MasteryEvidenceDecision(
                MasteryEvidenceKind.INDEPENDENT,
                MasteryEvidenceReason.INCORRECT_RESPONSE,
                LearningEvidence(
                    direction = LearningEvidenceDirection.NEGATIVE,
                    weight = 1.0,
                    reason = EvidenceReason.INDEPENDENT_INCORRECT,
                ),
                ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            )

            context.hintWasUsed -> MasteryEvidenceDecision(
                MasteryEvidenceKind.ASSISTED,
                MasteryEvidenceReason.CORRECT_AFTER_HINT,
                LearningEvidence(
                    direction = LearningEvidenceDirection.POSITIVE,
                    weight = 0.6,
                    reason = EvidenceReason.CORRECT_AFTER_HINT,
                ),
                ProblemMemoryOutcome.ASSISTED_RECALL,
            )

            context.responseOrdinal > 1 -> MasteryEvidenceDecision(
                MasteryEvidenceKind.ASSISTED,
                MasteryEvidenceReason.CORRECT_ON_RETRY,
                LearningEvidence(
                    direction = LearningEvidenceDirection.POSITIVE,
                    weight = 0.6,
                    reason = EvidenceReason.CORRECT_ON_RETRY,
                ),
                ProblemMemoryOutcome.ASSISTED_RECALL,
            )

            else -> MasteryEvidenceDecision(
                MasteryEvidenceKind.INDEPENDENT,
                MasteryEvidenceReason.INDEPENDENT_CORRECT_RESPONSE,
                LearningEvidence(
                    direction = LearningEvidenceDirection.POSITIVE,
                    weight = 1.0,
                    reason = EvidenceReason.INDEPENDENT_CORRECT,
                ),
                ProblemMemoryOutcome.INDEPENDENT_RECALL,
            )
        }
    }
}
