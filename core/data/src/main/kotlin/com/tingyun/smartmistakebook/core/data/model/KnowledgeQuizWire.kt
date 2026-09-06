package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.KnowledgeQuizChoice
import com.tingyun.smartmistakebook.core.model.KnowledgeQuizInput
import com.tingyun.smartmistakebook.core.model.KnowledgeQuizOutput
import kotlinx.serialization.json.JsonObject

/** Wire keys the knowledge-quiz model response may carry (spec dual-review-entry §3.3). */
internal val KNOWLEDGE_QUIZ_WIRE_KEYS = setOf("questionMarkdown", "choices", "correctChoiceId")

/** Wire keys each knowledge-quiz choice may carry. */
internal val KNOWLEDGE_QUIZ_CHOICE_WIRE_KEYS = setOf("choiceId", "markdown")

/**
 * Parses a KNOWLEDGE_QUIZ model reply into [KnowledgeQuizOutput]. The node id is not echoed
 * back by the model; it is already fixed by [KnowledgeQuizInput], so this parser only carries
 * the question, choices and the single correct id the model returned.
 */
internal fun JsonObject.toKnowledgeQuiz(
    input: KnowledgeQuizInput,
    modelVersion: String,
): KnowledgeQuizOutput {
    requireOnlyKeys(KNOWLEDGE_QUIZ_WIRE_KEYS)
    val choices = array("choices").mapIndexed { index, element ->
        val choice = element.objectValue()
        choice.requireOnlyKeys(KNOWLEDGE_QUIZ_CHOICE_WIRE_KEYS)
        KnowledgeQuizChoice(
            choiceId = choice.requiredString("choiceId"),
            markdown = choice.requiredString("markdown"),
        )
    }
    return KnowledgeQuizOutput(
        questionMarkdown = requiredString("questionMarkdown"),
        choices = choices,
        correctChoiceId = requiredString("correctChoiceId"),
        modelVersion = modelVersion,
    )
}
