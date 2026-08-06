package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorLobbyAllocatedTurn
import com.tingyun.smartmistakebook.core.domain.TutorLobbyConversation
import com.tingyun.smartmistakebook.core.domain.TutorLobbyTurnRequest
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode

/**
 * Presentation-facing lobby lifecycle. Learner identity, persistence versions, fingerprints and
 * idempotency authority remain behind [TutorConversationLobbyPort].
 */
internal class TutorLobbyConversationController(
    private val lobby: TutorConversationLobbyPort,
) {
    suspend fun loadInitialConversation(): TutorLobbyConversation = lobby.openCurrent()

    suspend fun startNewConversation(
        current: TutorLobbyConversation,
    ): TutorLobbyConversation = lobby.startNew(current.conversationToken)

    suspend fun allocateTurn(
        conversation: TutorLobbyConversation,
        message: String,
        mode: TutorExplanationMode,
        modeVersion: Long,
    ): TutorLobbyAllocatedTurn = lobby.allocateTurn(
        TutorLobbyTurnRequest(
            conversationToken = conversation.conversationToken,
            studentMessage = message,
            explanationMode = mode,
            modeVersion = modeVersion,
        ),
    )
}
