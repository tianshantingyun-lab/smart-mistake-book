package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode

/**
 * Narrow lobby lifecycle. The caller receives no learning-memory writer, evidence request,
 * knowledge identity, learner id, state version, fingerprint, or idempotency control.
 */
interface TutorConversationLobbyPort {
    suspend fun openCurrent(): TutorLobbyConversation

    suspend fun startNew(
        currentConversationToken: String,
    ): TutorLobbyConversation

    suspend fun allocateTurn(
        request: TutorLobbyTurnRequest,
    ): TutorLobbyAllocatedTurn
}

data class TutorLobbyConversation(
    val conversationId: String,
    val conversationToken: String,
) {
    init {
        require(conversationId.isSafeLobbyValue())
        require(conversationToken.isLowerLobbySha256())
    }
}

data class TutorLobbyTurnRequest(
    val conversationToken: String,
    val studentMessage: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
) {
    init {
        require(conversationToken.isLowerLobbySha256())
        require(studentMessage.isNotBlank() && studentMessage.length <= MAX_MESSAGE_CHARS)
        require(modeVersion >= 0L)
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 4_000
    }
}

data class TutorLobbyAllocatedTurn(
    val conversation: TutorLobbyConversation,
    val turnOrdinal: Int,
) {
    init {
        require(turnOrdinal > 0)
    }
}

private fun String.isSafeLobbyValue(): Boolean =
    isNotBlank() && this == trim() && length <= 256 && none(Char::isISOControl)

private fun String.isLowerLobbySha256(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }
