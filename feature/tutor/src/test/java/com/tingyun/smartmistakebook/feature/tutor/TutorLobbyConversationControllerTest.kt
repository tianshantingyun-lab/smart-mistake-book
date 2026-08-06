package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorLobbyAllocatedTurn
import com.tingyun.smartmistakebook.core.domain.TutorLobbyConversation
import com.tingyun.smartmistakebook.core.domain.TutorLobbyTurnRequest
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TutorLobbyConversationControllerTest {
    @Test
    fun initialConversationComesOnlyFromTheNarrowLobbyPort() = runTest {
        val port = FakeTutorConversationLobbyPort()

        val conversation = TutorLobbyConversationController(port).loadInitialConversation()

        assertEquals(port.initialConversation, conversation)
        assertEquals(1, port.openCalls)
    }

    @Test
    fun newConversationPassesOnlyTheOpaqueCurrentToken() = runTest {
        val port = FakeTutorConversationLobbyPort()
        val controller = TutorLobbyConversationController(port)
        val current = controller.loadInitialConversation()

        val next = controller.startNewConversation(current)

        assertEquals(current.conversationToken, port.startedFromToken)
        assertEquals("tutor-lobby:next", next.conversationId)
    }

    @Test
    fun turnAllocationForwardsOnlyPresentationInputAndUsesPortOrdinal() = runTest {
        val port = FakeTutorConversationLobbyPort()
        val controller = TutorLobbyConversationController(port)
        val current = controller.loadInitialConversation()

        val allocated = controller.allocateTurn(
            conversation = current,
            message = "这一步为什么这样处理？",
            mode = TutorExplanationMode.GUIDED,
            modeVersion = 4,
        )

        assertEquals(7, allocated.turnOrdinal)
        assertEquals(
            TutorLobbyTurnRequest(
                conversationToken = current.conversationToken,
                studentMessage = "这一步为什么这样处理？",
                explanationMode = TutorExplanationMode.GUIDED,
                modeVersion = 4,
            ),
            port.turnRequests.single(),
        )
    }
}

private class FakeTutorConversationLobbyPort : TutorConversationLobbyPort {
    val initialConversation = TutorLobbyConversation(
        conversationId = "tutor-lobby",
        conversationToken = TOKEN_A,
    )
    var openCalls = 0
        private set
    var startedFromToken: String? = null
        private set
    val turnRequests = mutableListOf<TutorLobbyTurnRequest>()

    override suspend fun openCurrent(): TutorLobbyConversation {
        openCalls += 1
        return initialConversation
    }

    override suspend fun startNew(currentConversationToken: String): TutorLobbyConversation {
        startedFromToken = currentConversationToken
        return TutorLobbyConversation("tutor-lobby:next", TOKEN_B)
    }

    override suspend fun allocateTurn(request: TutorLobbyTurnRequest): TutorLobbyAllocatedTurn {
        turnRequests += request
        return TutorLobbyAllocatedTurn(
            conversation = TutorLobbyConversation(
                conversationId = initialConversation.conversationId,
                conversationToken = TOKEN_C,
            ),
            turnOrdinal = 7,
        )
    }
}

private const val TOKEN_A =
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val TOKEN_B =
    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val TOKEN_C =
    "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
