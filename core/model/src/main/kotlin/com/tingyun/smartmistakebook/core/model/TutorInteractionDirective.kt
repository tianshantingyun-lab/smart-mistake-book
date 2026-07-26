package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TutorExplanationMode {
    DIRECT,
    GUIDED,
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
