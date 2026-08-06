package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.RevealTutorSolutionCommand
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedTutorChoiceInteractionRepositoryTest {
    @Test
    fun `durable choice is recorded before trusted learning finalization`() = runBlocking {
        val order = mutableListOf<String>()
        val delegate = RecordingInteractions(order)
        var finalized: TutorTurnResponse? = null
        val repository = TrustedTutorChoiceInteractionRepositoryFactory.create(
            delegate = delegate,
            learningFinalizer =
                TrustedTutorChoiceLearningFinalizer { response ->
                    order += "finalize"
                    finalized = response
                },
        )

        val persisted = repository.recordChoice(choiceCommand())

        assertEquals(listOf("persist", "finalize"), order)
        assertSame(persisted, finalized)
        assertEquals(listOf(persisted), delegate.durableChoices)
    }

    @Test
    fun `failed learning finalization never erases the durable choice`() = runBlocking {
        val order = mutableListOf<String>()
        val delegate = RecordingInteractions(order)
        val repository = TrustedTutorChoiceInteractionRepositoryFactory.create(
            delegate = delegate,
            learningFinalizer =
                TrustedTutorChoiceLearningFinalizer {
                    order += "finalize"
                    error("owner rejected stale authorization")
                },
        )

        val failure = runCatching { repository.recordChoice(choiceCommand()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("persist", "finalize"), order)
        assertEquals(1, delegate.durableChoices.size)
    }

    private class RecordingInteractions(
        private val order: MutableList<String>,
    ) : TutorInteractionRepository {
        val durableChoices = mutableListOf<TutorTurnResponse>()

        override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> =
            flowOf(durableChoices)

        override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse {
            order += "persist"
            return command.toResponse().also(durableChoices::add)
        }

        private fun RecordTutorChoiceCommand.toResponse() = TutorTurnResponse(
            sessionId = sessionId,
            questionDocumentId = questionDocumentId,
            revisionNumber = revisionNumber,
            cycleOrdinal = cycleOrdinal,
            turnOrdinal = turnOrdinal,
            diagnosticStemMarkdown = diagnosticStemMarkdown,
            selectedChoiceId = selectedChoiceId,
            selectedChoiceMarkdown = selectedChoiceMarkdown,
            selectionWasCorrect = selectionWasCorrect,
            feedbackMarkdown = feedbackMarkdown,
            submittedAtEpochMillis = occurredAtEpochMillis,
            updatedAtEpochMillis = occurredAtEpochMillis,
            evidenceRequestId = evidenceRequestId,
        )

        override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse =
            error("not used")

        override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse =
            error("not used")

        override suspend fun recordSolutionExposure(command: RecordTutorSolutionExposureCommand) =
            error("not used")
    }

    private fun choiceCommand() = RecordTutorChoiceCommand(
        sessionId = "conversation-current",
        questionDocumentId = "question-current",
        revisionNumber = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        diagnosticStemMarkdown = "判断方向",
        selectedChoiceId = "choice-b",
        selectedChoiceMarkdown = "向右",
        selectionWasCorrect = true,
        feedbackMarkdown = "方向正确",
        occurredAtEpochMillis = 1_000,
        evidenceRequestId = "evidence-current",
    )

}
