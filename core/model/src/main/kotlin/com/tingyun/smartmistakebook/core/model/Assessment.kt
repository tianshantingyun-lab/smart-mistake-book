package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TutorChoice(
    val id: String,
    val markdown: String,
    val feedbackMarkdown: String? = null,
    val followUpIds: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Choice id must not be blank" }
        require(markdown.isNotBlank()) { "Choice content must not be blank" }
        require(feedbackMarkdown == null || feedbackMarkdown.isNotBlank()) {
            "Choice feedback must not be blank when provided"
        }
        require(followUpIds.none(String::isBlank)) { "Choice follow-up ids must not be blank" }
        require(followUpIds.distinct().size == followUpIds.size) {
            "Choice follow-up ids must be unique"
        }
    }
}

data class TutorChoiceEvaluation(
    val choice: TutorChoice,
    val isCorrect: Boolean,
)

@Serializable
data class TutorAssessmentItem(
    val id: String,
    val stemMarkdown: String,
    val choices: List<TutorChoice>,
    val correctChoiceId: String,
    val promptMarkdown: String? = null,
    val initialFollowUpIds: List<String> = emptyList(),
    val knowledgeNodeIds: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "Assessment item id must not be blank" }
        require(stemMarkdown.isNotBlank()) { "Assessment stem must not be blank" }
        require(choices.size >= 2) { "An assessment item needs at least two choices" }
        require(choices.map(TutorChoice::id).distinct().size == choices.size) {
            "Choice ids must be unique"
        }
        require(choices.any { it.id == correctChoiceId }) {
            "The correct choice must belong to the assessment item"
        }
        require(promptMarkdown == null || promptMarkdown.isNotBlank()) {
            "Assessment prompt must not be blank when provided"
        }
        require(initialFollowUpIds.none(String::isBlank)) {
            "Initial follow-up ids must not be blank"
        }
        require(initialFollowUpIds.distinct().size == initialFollowUpIds.size) {
            "Initial follow-up ids must be unique"
        }
    }

    fun evaluateChoice(choiceId: String): TutorChoiceEvaluation {
        val choice = choices.firstOrNull { it.id == choiceId }
            ?: throw IllegalArgumentException("Choice $choiceId does not belong to assessment $id")
        return TutorChoiceEvaluation(
            choice = choice,
            isCorrect = choice.id == correctChoiceId,
        )
    }
}

/**
 * 把知识点复习的考察输出（[KnowledgeQuizOutput]，spec dual-review-entry §3.3 P1 选择题）
 * 映射成可渲染、可判答的 [TutorAssessmentItem]，复用其 [TutorAssessmentItem.evaluateChoice] 判答。
 * 知识点复习考察项是纯选择题（无分叉 feedback），故选项不携带 feedbackMarkdown；判别标准
 * 统一落在 evaluateChoice（选中 == correctChoiceId 即正确），UI 无需自行解释。
 */
fun KnowledgeQuizOutput.toTutorAssessmentItem(
    knowledgeNodeId: String,
    itemId: String,
): TutorAssessmentItem = TutorAssessmentItem(
    id = itemId,
    stemMarkdown = questionMarkdown,
    choices = choices.map { choice ->
        TutorChoice(
            id = choice.choiceId,
            markdown = choice.markdown,
        )
    },
    correctChoiceId = correctChoiceId,
    knowledgeNodeIds = setOf(knowledgeNodeId),
)

enum class TutorAssistanceKind {
    HINT,
    ANSWER_REVEAL,
}

data class AssessmentAssistanceEvent(
    val eventId: String,
    val assessmentItemId: String,
    val presentationId: String,
    val kind: TutorAssistanceKind,
    val contentMarkdown: String,
    val occurredAtEpochMillis: Long,
    val eventSequence: Long,
) {
    init {
        require(eventId.isNotBlank()) { "Event id must not be blank" }
        require(assessmentItemId.isNotBlank()) { "Assessment item id must not be blank" }
        require(presentationId.isNotBlank()) { "Assistance presentation id must not be blank" }
        require(contentMarkdown.isNotBlank()) { "Assistance content must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Event time must not be negative" }
        require(eventSequence > 0) { "Assistance event sequence must be positive" }
    }
}

/** The only assistance event that presentation and mastery policy are allowed to consume. */
data class PersistedAssessmentAssistance(
    val event: AssessmentAssistanceEvent,
    val persistedAtEpochMillis: Long,
) {
    init {
        require(persistedAtEpochMillis >= event.occurredAtEpochMillis) {
            "Persistence time must not precede the event"
        }
    }
}

data class AssessmentSubmissionContext(
    val assessmentItemId: String,
    val selectedChoiceId: String,
    val presentationId: String,
    val responseSequence: Long,
    val responseOrdinal: Int = 1,
    val persistedAssistance: List<PersistedAssessmentAssistance> = emptyList(),
) {
    init {
        require(assessmentItemId.isNotBlank()) { "Assessment item id must not be blank" }
        require(selectedChoiceId.isNotBlank()) { "Selected choice id must not be blank" }
        require(presentationId.isNotBlank()) { "Response presentation id must not be blank" }
        require(responseSequence > 0) { "Response sequence must be positive" }
        require(responseOrdinal > 0) { "Response ordinal must be positive" }
        require(persistedAssistance.all { it.event.assessmentItemId == assessmentItemId }) {
            "Assistance must belong to the submitted assessment item"
        }
        require(persistedAssistance.all { it.event.presentationId == presentationId }) {
            "Assistance must belong to the same assessment presentation as the response"
        }
        require(persistedAssistance.map { it.event.eventId }.distinct().size == persistedAssistance.size) {
            "Persisted assistance event ids must be unique"
        }
        require(
            persistedAssistance.map { it.event.eventSequence }.distinct().size == persistedAssistance.size,
        ) { "Persisted assistance event sequences must be unique" }
    }

    val effectiveAssistance: List<PersistedAssessmentAssistance>
        get() = persistedAssistance.filter { it.event.eventSequence < responseSequence }

    val answerWasRevealed: Boolean
        get() = effectiveAssistance.any { it.event.kind == TutorAssistanceKind.ANSWER_REVEAL }

    val hintWasUsed: Boolean
        get() = effectiveAssistance.any { it.event.kind == TutorAssistanceKind.HINT }
}
