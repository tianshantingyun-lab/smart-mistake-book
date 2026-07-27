package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A bounded free-text entry on the Tutor home screen.
 *
 * It deliberately carries no question document, learning ledger, or write authority. The model
 * may understand the message and request one of two local reads, but local policy remains the
 * authority and free text never becomes learning evidence.
 */
@Serializable
@SerialName("tutor_lobby")
data class TutorLobbyInput(
    val conversationId: String,
    val messageOrdinal: Int,
    val studentMessage: String,
    val priorMessages: List<TutorChatHistoryEntry> = emptyList(),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.TUTOR_LOBBY

    override val subjectId: String
        get() = conversationId

    init {
        conversationId.requireSafeModelText(
            "Tutor lobby conversation id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(messageOrdinal > 0) { "Tutor lobby message ordinal must be positive" }
        studentMessage.requireSafeTutorStudentMessage(
            "Tutor lobby student message",
            TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS,
        )
        require(priorMessages.size <= TutorRespondInput.MAX_PRIOR_MESSAGES) {
            "Tutor lobby contains too many prior messages"
        }
        require(
            priorMessages.sumOf { message ->
                message.studentMessage.length + message.assistantMarkdown.length
            } <= TutorRespondInput.MAX_PRIOR_MESSAGE_CHARS,
        ) { "Tutor lobby prior messages exceed their text budget" }
    }

    companion object {
        const val MAX_STUDENT_MESSAGE_CHARS = TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS
        const val MAX_PRIOR_MESSAGES = TutorRespondInput.MAX_PRIOR_MESSAGES
        const val MAX_PRIOR_MESSAGE_CHARS = TutorRespondInput.MAX_PRIOR_MESSAGE_CHARS
    }
}

/** A persistable response that cannot claim or request a local write. */
@Serializable
@SerialName("tutor_lobby_output")
data class TutorLobbyOutput(
    val conversationId: String,
    val messageOrdinal: Int,
    val messageMarkdown: String,
    val intentDecision: TutorIntentDecision = TutorIntentDecision.ambiguousDefault(),
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        conversationId.requireSafeModelText(
            "Tutor lobby output conversation id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(messageOrdinal > 0) { "Tutor lobby output message ordinal must be positive" }
        messageMarkdown.requireTutorMarkdown(
            "Tutor lobby response",
            TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS,
        )
        require(intentDecision.requestedLocalCapability in ALLOWED_LOCAL_CAPABILITIES) {
            "Tutor lobby cannot request a local write or current-question action"
        }
        modelVersion.requireSafeModelText(
            "Tutor lobby model version",
            MAX_MODEL_VERSION_CHARS,
            false,
        )
    }

    companion object {
        val ALLOWED_LOCAL_CAPABILITIES = setOf(
            TutorRequestedLocalCapability.NONE,
            TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
            TutorRequestedLocalCapability.READ_LEARNING_PROGRESS,
        )
    }
}
