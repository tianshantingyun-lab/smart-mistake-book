package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorMoveType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorInteractionRepositoryTest {
    @Test
    fun `choice fields are either complete or absent`() {
        val choice = choiceResponse(turnOrdinal = 1, selectionWasCorrect = true)
        val action = actionResponse(turnOrdinal = 2)

        assertTrue(choice.hasChoicePayload)
        assertTrue(!action.hasChoicePayload)
        assertTrue(
            runCatching {
                choice.copy(selectedChoiceMarkdown = null)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                action.copy(requestedMove = null, solutionRevealed = false)
            }.isFailure,
        )
    }

    @Test
    fun `history excludes action-only rows and summary counts only choices`() {
        val responses = listOf(
            choiceResponse(
                turnOrdinal = 1,
                selectionWasCorrect = true,
                requestedMove = TutorMoveType.DEEPEN_REASONING,
            ),
            choiceResponse(
                turnOrdinal = 2,
                selectionWasCorrect = false,
                requestedMove = TutorMoveType.CONNECT_KNOWLEDGE,
            ),
            actionResponse(
                cycleOrdinal = 2,
                turnOrdinal = 1,
                requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
                solutionRevealed = true,
            ),
        )

        val history = responses.toContiguousTutorHistory()
        val memory = responses.toTutorConversationMemory(
            answerExposureKeys = setOf(planExposure(responses.last())),
        )

        assertEquals(listOf(1, 2), history.map { it.turnOrdinal })
        assertEquals(2, memory?.completedCycleCount)
        assertEquals(2, memory?.answeredTurnCount)
        assertEquals(1, memory?.correctChoiceCount)
        assertEquals("反馈 2", memory?.lastFeedbackMarkdown)
        assertEquals(TutorMoveType.CHANGE_REPRESENTATION, memory?.lastRequestedMove)
        assertEquals(true, memory?.solutionWasRevealed)
    }

    @Test
    fun `action-only rows retain memory without manufacturing choice history`() {
        val responses = listOf(actionResponse(turnOrdinal = 1))

        assertTrue(responses.toContiguousTutorHistory().isEmpty())
        val memory = responses.toTutorConversationMemory(emptySet())
        assertEquals(0, memory?.answeredTurnCount)
        assertEquals(0, memory?.correctChoiceCount)
        assertNull(memory?.lastFeedbackMarkdown)
        assertEquals(TutorMoveType.CHANGE_REPRESENTATION, memory?.lastRequestedMove)
        assertEquals(false, memory?.solutionWasRevealed)
    }

    @Test
    fun `solution unlock without a durable exposure is not conversation memory`() {
        val unlocked = actionResponse(
            turnOrdinal = 1,
            requestedMove = null,
            solutionRevealed = true,
        )

        assertNull(listOf(unlocked).toTutorConversationMemory(emptySet()))

        val exposed = listOf(unlocked).toTutorConversationMemory(
            setOf(planExposure(unlocked)),
        )
        assertEquals(true, exposed?.solutionWasRevealed)
    }

    @Test
    fun `persisted respond reply exposure is conversation memory without a tutor turn`() {
        val exposure = respondExposure(cycleOrdinal = 2, turnOrdinal = 3)

        val memory = emptyList<TutorTurnResponse>().toTutorConversationMemory(setOf(exposure))

        assertEquals(2, memory?.completedCycleCount)
        assertEquals(0, memory?.answeredTurnCount)
        assertEquals(0, memory?.correctChoiceCount)
        assertNull(memory?.lastFeedbackMarkdown)
        assertNull(memory?.lastRequestedMove)
        assertEquals(true, memory?.solutionWasRevealed)
    }

    @Test
    fun `respond reply exposure stays within its exact tutor conversation`() {
        val response = actionResponse(turnOrdinal = 1)
        val exact = respondExposure(cycleOrdinal = 1, turnOrdinal = 1)

        assertEquals(
            true,
            listOf(response).toTutorConversationMemory(setOf(exact))?.solutionWasRevealed,
        )

        val mismatches = listOf(
            exact.copy(sessionId = "other-session"),
            exact.copy(questionDocumentId = "other-question"),
            exact.copy(revisionNumber = 2),
        )
        mismatches.forEach { mismatch ->
            assertEquals(
                false,
                listOf(response).toTutorConversationMemory(setOf(mismatch))?.solutionWasRevealed,
            )
        }
    }

    @Test
    fun `answer exposure must match the exact persisted tutor turn`() {
        val response = actionResponse(
            turnOrdinal = 1,
            requestedMove = null,
            solutionRevealed = true,
        )
        val exact = planExposure(response)
        val mismatches = listOf(
            exact.copy(sessionId = "other-session"),
            exact.copy(questionDocumentId = "other-question"),
            exact.copy(revisionNumber = 2),
            exact.copy(cycleOrdinal = 2),
            exact.copy(turnOrdinal = 2),
        )

        mismatches.forEach { mismatch ->
            assertNull(listOf(response).toTutorConversationMemory(setOf(mismatch)))
        }

        // Turn memory only needs proof that one exact plan surface for this turn was exposed.
        // Exact request identity is still enforced when the UI records and rehydrates that proof.
        assertEquals(
            true,
            listOf(response).toTutorConversationMemory(
                setOf(exact.copy(modelTaskRequestId = "other-plan-request")),
            )?.solutionWasRevealed,
        )
    }

    private fun planExposure(response: TutorTurnResponse) = TutorAnswerExposureKey(
        sessionId = response.sessionId,
        questionDocumentId = response.questionDocumentId,
        revisionNumber = response.revisionNumber,
        cycleOrdinal = response.cycleOrdinal,
        turnOrdinal = response.turnOrdinal,
        surfaceKind = TutorAnswerExposureSurfaceKind.PLAN_SOLUTION,
        modelTaskRequestId = "plan:${response.cycleOrdinal}:${response.turnOrdinal}",
    )

    private fun respondExposure(
        cycleOrdinal: Int,
        turnOrdinal: Int,
        modelTaskRequestId: String = "respond:$cycleOrdinal:$turnOrdinal:1",
        responseOrdinal: Int = 1,
    ) = TutorAnswerExposureKey(
        sessionId = "session-1",
        questionDocumentId = "question-1",
        revisionNumber = 1,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        surfaceKind = TutorAnswerExposureSurfaceKind.RESPOND_REPLY,
        modelTaskRequestId = modelTaskRequestId,
        responseOrdinal = responseOrdinal,
    )

    private fun choiceResponse(
        turnOrdinal: Int,
        selectionWasCorrect: Boolean,
        requestedMove: TutorMoveType? = null,
    ) = TutorTurnResponse(
        sessionId = "session-1",
        questionDocumentId = "question-1",
        revisionNumber = 1,
        cycleOrdinal = 1,
        turnOrdinal = turnOrdinal,
        diagnosticStemMarkdown = "当前题第 $turnOrdinal 步",
        selectedChoiceId = "choice-$turnOrdinal",
        selectedChoiceMarkdown = "选择 $turnOrdinal",
        selectionWasCorrect = selectionWasCorrect,
        feedbackMarkdown = "反馈 $turnOrdinal",
        requestedMove = requestedMove,
        submittedAtEpochMillis = turnOrdinal * 100L,
        updatedAtEpochMillis = turnOrdinal * 100L,
    )

    private fun actionResponse(
        turnOrdinal: Int,
        cycleOrdinal: Int = 1,
        requestedMove: TutorMoveType? = TutorMoveType.CHANGE_REPRESENTATION,
        solutionRevealed: Boolean = false,
    ) = TutorTurnResponse(
        sessionId = "session-1",
        questionDocumentId = "question-1",
        revisionNumber = 1,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        diagnosticStemMarkdown = null,
        selectedChoiceId = null,
        selectedChoiceMarkdown = null,
        selectionWasCorrect = null,
        feedbackMarkdown = null,
        requestedMove = requestedMove,
        solutionRevealed = solutionRevealed,
        submittedAtEpochMillis = turnOrdinal * 100L,
        updatedAtEpochMillis = turnOrdinal * 100L,
    )
}
