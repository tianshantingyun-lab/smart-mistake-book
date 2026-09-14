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
    /** Non-empty enables the tool protocol for this dispatch (spec §3.1). */
    val toolDeclarations: List<TutorToolName> = emptyList(),
    /** Results of prior tool rounds; round 1 dispatch always leaves this empty. */
    val toolRoundResults: List<TutorToolRoundResult> = emptyList(),
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
        studentMessage.requireSafeModelText(
            "Tutor lobby student message",
            TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS,
            true,
        )
        require(priorMessages.size <= TutorRespondInput.MAX_PRIOR_MESSAGES) {
            "Tutor lobby contains too many prior messages"
        }
        require(
            priorMessages.sumOf { message ->
                message.studentMessage.length + message.assistantMarkdown.length
            } <= TutorRespondInput.MAX_PRIOR_MESSAGE_CHARS,
        ) { "Tutor lobby prior messages exceed their text budget" }
        require(toolDeclarations.size <= MAX_TOOL_DECLARATIONS) {
            "Tutor lobby declares too many tools"
        }
        require(toolDeclarations.distinct().size == toolDeclarations.size) {
            "Tutor lobby tool declarations must be distinct"
        }
        require(toolRoundResults.size <= TutorToolRoundResult.MAX_TOOL_ROUNDS) {
            "Tutor lobby carries too many tool rounds"
        }
        require(toolRoundResults.isEmpty() || toolDeclarations.isNotEmpty()) {
            "Tutor lobby tool rounds require declared tools"
        }
        require(
            toolRoundResults.map(TutorToolRoundResult::roundOrdinal) ==
                (1..toolRoundResults.size).toList(),
        ) {
            "Tutor lobby tool round ordinals must be sequential from one"
        }
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
    /** Optional student-visible reasoning trace; folded by default, never re-fed to the model. */
    val thinkingMarkdown: String? = null,
    /** Optional locally-rendered figures the model asked for; drawn after the body, never in markdown. */
    val attachedImages: List<AttachedImage> = emptyList(),
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        conversationId.requireSafeModelText(
            "Tutor lobby output conversation id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(messageOrdinal > 0) { "Tutor lobby output message ordinal must be positive" }
        messageMarkdown.requireTutorSceneText(
            "Tutor lobby response",
            TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS,
            true,
        )
        thinkingMarkdown.requireThinkingMarkdown("Tutor thinking")
        require(attachedImages.size <= AttachedImage.MAX_ATTACHED_IMAGES) {
            "A tutor lobby reply may attach at most ${AttachedImage.MAX_ATTACHED_IMAGES} figures"
        }
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
        /**
         * Lobby 允许申请的本地能力，只有两条：什么都不申请，或检索错题本。
         *
         * **掌握情况读取被刻意排除**（`READ_LEARNING_PROGRESS`）：它是"某个知识点你
         * 掌握得怎样"，没有当前题就没有锚点；而且它的产出无法归入
         * `TUTOR_LOBBY_DISCLOSURE`（仅学生消息 + 会话上下文），任其进入就要放宽这条
         * 通道的披露面。掌握情况读取因此只保留在有题上下文的地方——讲题会话的
         * `MASTERY_READ` 工具。
         *
         * 这条枚举此前与 Lobby 提示词**互相矛盾**（枚举允许、提示词却写"本地不提供该
         * 查询"），而没有测试会因此变红；`TutorLobbyCapabilityBoundaryTest` 现在锁住
         * 它与提示词的一致性。
         */
        val ALLOWED_LOCAL_CAPABILITIES = setOf(
            TutorRequestedLocalCapability.NONE,
            TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
        )
    }
}
