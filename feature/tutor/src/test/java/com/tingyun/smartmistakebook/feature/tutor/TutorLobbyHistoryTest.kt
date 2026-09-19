package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.TutorMessageRole
import com.tingyun.smartmistakebook.core.domain.TutorMessageStatus
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lobby route used to hand `priorMessages` to [TutorLobbyInput] after only a
 * count-based `takeLast`, while the input enforces a character budget with a
 * `require`. Once a conversation grew past that budget every send threw, and the
 * route reported it as `NETWORK_UNAVAILABLE` with a retry that reproduced the
 * same throw — the conversation could never advance again. These tests pin the
 * trim that removes that failure.
 */
class TutorLobbyHistoryTest {

    private val conversationId = "tutor-conv:test"

    /**
     * One whole SUCCEEDED exchange of explicit size. The sizes are passed in
     * rather than derived, because the surviving window depends on the exact
     * arithmetic against `MAX_PRIOR_MESSAGE_CHARS`.
     */
    private fun exchangeOf(
        ordinal: Int,
        studentChars: Int,
        assistantChars: Int,
    ): List<TutorMessage> {
        val studentOrdinal = ordinal * 2 - 1
        val assistantOrdinal = ordinal * 2
        val at = ordinal.toLong()
        return listOf(
            TutorMessage(
                messageId = "m-$studentOrdinal",
                conversationId = conversationId,
                ordinal = studentOrdinal,
                role = TutorMessageRole.STUDENT,
                bodyMarkdown = "s$studentOrdinal".padEnd(studentChars, 'x'),
                status = TutorMessageStatus.SUCCEEDED,
                logicalOperationId = "op-$ordinal",
                replyToMessageId = null,
                createdAtEpochMillis = at,
                completedAtEpochMillis = at,
                errorCode = null,
            ),
            TutorMessage(
                messageId = "m-$assistantOrdinal",
                conversationId = conversationId,
                ordinal = assistantOrdinal,
                role = TutorMessageRole.ASSISTANT,
                bodyMarkdown = "a$assistantOrdinal".padEnd(assistantChars, 'y'),
                status = TutorMessageStatus.SUCCEEDED,
                logicalOperationId = "op-$ordinal",
                replyToMessageId = "m-$studentOrdinal",
                createdAtEpochMillis = at,
                completedAtEpochMillis = at,
                errorCode = null,
            ),
        )
    }

    private fun ordinals(history: List<TutorChatHistoryEntry>) =
        history.map { entry -> entry.studentMessage.take(2) }

    /** Each exchange is 12000 characters, so two of them exactly fill the budget. */
    private fun halfBudgetExchange(ordinal: Int): List<TutorMessage> {
        val student = TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS
        return exchangeOf(
            ordinal = ordinal,
            studentChars = student,
            assistantChars = TutorRespondInput.MAX_PRIOR_MESSAGE_CHARS / 2 - student,
        )
    }

    @Test
    fun `an over-budget conversation still builds a lobby input`() {
        // Three 12000-character exchanges: 36000 characters against a 24000 budget.
        val messages = (1..3).flatMap(::halfBudgetExchange)

        val history = messages.toLobbyHistory()

        // The regression assertion: constructing the input used to throw here.
        val input = TutorLobbyInput(
            conversationId = conversationId,
            messageOrdinal = 4,
            studentMessage = "next question",
            priorMessages = history,
        )
        assertTrue(input.priorMessages.isNotEmpty())
        assertEquals(history, input.priorMessages)
    }

    @Test
    fun `the trim drops the oldest exchange and keeps chronological order`() {
        val messages = (1..3).flatMap(::halfBudgetExchange)

        val history = messages.toLobbyHistory()

        assertEquals(2, history.size)
        assertEquals(listOf("s3", "s5"), ordinals(history))
    }

    @Test
    fun `a conversation at the character budget is carried whole`() {
        val messages = (1..2).flatMap(::halfBudgetExchange)

        val history = messages.toLobbyHistory()

        assertEquals(2, history.size)
        assertEquals(listOf("s1", "s3"), ordinals(history))
    }

    @Test
    fun `a short conversation is carried whole`() {
        val messages = (1..3).flatMap { ordinal -> exchangeOf(ordinal, 20, 30) }

        val history = messages.toLobbyHistory()

        assertEquals(3, history.size)
        assertEquals(listOf("s1", "s3", "s5"), ordinals(history))
    }
}
