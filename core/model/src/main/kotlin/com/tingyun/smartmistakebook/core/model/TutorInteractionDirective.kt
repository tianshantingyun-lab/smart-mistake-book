package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TutorExplanationMode {
    DIRECT,
    GUIDED,
}

/** Structured model intent; question punctuation is never used as an interaction authority. */
@Serializable
enum class TutorResponseIntent {
    EXPLAIN,
    ASK,
}

@Serializable
data class TutorInteractionChoice(
    val id: String,
    val labelMarkdown: String,
) {
    init {
        id.requireSafeModelText("Tutor interaction choice id", ModelTaskRequest.MAX_ID_CHARS, false)
        labelMarkdown.requireTutorMarkdown(
            "Tutor interaction choice label",
            TutorTurnPlan.MAX_CHOICE_CHARS,
        )
    }
}

/**
 * Local rendering contract for the next student interaction.
 *
 * The field that carries this contract is optional so cached outputs written before directives
 * were introduced continue to decode and render through their legacy diagnostic/action fields.
 */
@Serializable
sealed interface TutorInteractionDirective {
    @Serializable
    @SerialName("continue")
    data object Continue : TutorInteractionDirective

    @Serializable
    @SerialName("free_response")
    data class FreeResponse(
        val promptMarkdown: String,
    ) : TutorInteractionDirective {
        init {
            promptMarkdown.requireTutorMarkdown(
                "Tutor free-response prompt",
                TutorTurnPlan.MAX_STEM_CHARS,
            )
        }
    }

    @Serializable
    @SerialName("choices")
    data class Choices(
        val promptMarkdown: String,
        val choices: List<TutorInteractionChoice>,
    ) : TutorInteractionDirective {
        init {
            promptMarkdown.requireTutorMarkdown(
                "Tutor choice prompt",
                TutorTurnPlan.MAX_STEM_CHARS,
            )
            require(choices.size in MIN_CHOICES..MAX_CHOICES) {
                "A tutor choice directive requires two to four choices"
            }
            require(choices.map(TutorInteractionChoice::id).distinct().size == choices.size) {
                "Tutor interaction choice ids must be unique"
            }
        }
    }

    @Serializable
    @SerialName("visual_target")
    data class VisualTarget(
        val promptMarkdown: String,
        val targetId: String,
    ) : TutorInteractionDirective {
        init {
            promptMarkdown.requireTutorMarkdown(
                "Tutor visual-target prompt",
                TutorTurnPlan.MAX_STEM_CHARS,
            )
            targetId.requireSafeModelText(
                "Tutor visual-target id",
                ModelTaskRequest.MAX_ID_CHARS,
                false,
            )
        }
    }

    companion object {
        const val MIN_CHOICES = 2
        const val MAX_CHOICES = 4
    }
}

/**
 * Model-authored interaction shape retained for the trusted Host to review.
 *
 * This proposal is never presentation authority. The ordinary [TutorTurnPlan.interactionDirective]
 * remains the only field consumed by existing renderers, and local reduction replaces it with a
 * Host-authored free-response prompt until an exact Host-owned answer capability is available.
 */
@Serializable
data class TutorGuidedInteractionProposal(
    val directive: TutorInteractionDirective,
    /** Exact inline scene proposed with a visual target. Generated scenes cannot self-certify. */
    val visualScene: TutorVisualDocumentScene? = null,
) {
    init {
        when (directive) {
            is TutorInteractionDirective.Choices -> require(visualScene == null) {
                "A choice interaction proposal cannot carry a visual scene"
            }
            is TutorInteractionDirective.VisualTarget -> Unit
            TutorInteractionDirective.Continue,
            is TutorInteractionDirective.FreeResponse,
            -> error("Only choice and visual-target interactions may be retained as proposals")
        }
    }
}
