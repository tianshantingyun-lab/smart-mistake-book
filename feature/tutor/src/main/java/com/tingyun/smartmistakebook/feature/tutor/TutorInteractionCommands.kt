package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.toContiguousTutorHistory
import com.tingyun.smartmistakebook.core.domain.toTutorConversationMemory
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun tutorContinueAfterMove(
    hasChoicePayload: Boolean,
    nextHistorySize: Int,
): Boolean = hasChoicePayload && nextHistorySize < TutorPlanInput.MAX_TURNS

internal class TutorInteractionCommands(
    private val scope: CoroutineScope,
    private val sink: TutorInteractionSink,
) {
    fun submitChoice(choiceId: String) {
        val output = sink.currentPlanOutput()
        val item = output?.plan?.diagnosticItem
        val evaluation = item?.evaluateChoice(choiceId)
        if (
            !tutorChoiceSubmissionCanStart(
                hasPlanOutput = output != null,
                hasDiagnosticItem = item != null,
                hasEvaluation = evaluation != null,
                interactionBusy = sink.interactionBusy(),
            )
        ) {
            return
        }
        val submittedItem = requireNotNull(item)
        val submittedEvaluation = requireNotNull(evaluation)
        val question = sink.question()
        val input = sink.currentInput()
        sink.setInteractionError(null)
        sink.setInteractionBusy(true)
        scope.launch {
            try {
                sink.interactions.recordChoice(
                    RecordTutorChoiceCommand(
                        sessionId = question.sessionId,
                        questionDocumentId = question.questionDocument.document.id,
                        revisionNumber = question.revisionNumber,
                        cycleOrdinal = input.cycleOrdinal,
                        turnOrdinal = input.turnOrdinal,
                        diagnosticStemMarkdown = submittedItem.stemMarkdown,
                        selectedChoiceId = submittedEvaluation.choice.id,
                        selectedChoiceMarkdown = submittedEvaluation.choice.markdown,
                        selectionWasCorrect = submittedEvaluation.isCorrect,
                        feedbackMarkdown = requireNotNull(submittedEvaluation.choice.feedbackMarkdown),
                        occurredAtEpochMillis = sink.clock(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                sink.setInteractionError(TUTOR_CHOICE_SAVE_ERROR)
            } finally {
                sink.setInteractionBusy(false)
            }
        }
    }

    fun continueTurn(requestedMove: TutorMoveType) {
        if (sink.interactionBusy()) return
        if (
            !tutorMoveCanStart(
                interactionBusy = sink.interactionBusy(),
                hasExecutableProvider = sink.hasExecutableProvider(),
            )
        ) {
            sink.openModelSettings()
            return
        }
        val question = sink.question()
        val input = sink.currentInput()
        sink.setInteractionBusy(true)
        scope.launch {
            sink.setInteractionError(null)
            try {
                val movedResponse = sink.interactions.recordMove(
                    RecordTutorMoveCommand(
                        sessionId = question.sessionId,
                        questionDocumentId = question.questionDocument.document.id,
                        revisionNumber = question.revisionNumber,
                        cycleOrdinal = input.cycleOrdinal,
                        turnOrdinal = input.turnOrdinal,
                        requestedMove = requestedMove,
                        occurredAtEpochMillis = sink.clock(),
                    ),
                )
                val nextHistory = sink.currentCycleResponses()
                    .filterNot { it.turnOrdinal == movedResponse.turnOrdinal }
                    .plus(movedResponse)
                    .toContiguousTutorHistory()
                if (tutorContinueAfterMove(movedResponse.hasChoicePayload, nextHistory.size)) {
                    sink.executeTurn(
                        input.cycleOrdinal,
                        input.priorConversationMemory,
                        input.priorCycleStudentMessages,
                        nextHistory,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                sink.setInteractionError(TUTOR_MOVE_SAVE_ERROR)
            } finally {
                sink.setInteractionBusy(false)
            }
        }
    }

    fun restartCycle() {
        val memory = sink.tutorResponses().toTutorConversationMemory(sink.answerExposureKeys())
        if (
            !tutorRestartCanStart(
                hasExecutableProvider = sink.hasExecutableProvider(),
                hasConversationMemory = memory != null,
            )
        ) {
            if (!sink.hasExecutableProvider()) sink.openModelSettings()
            return
        }
        sink.executeTurn(
            sink.currentCycle() + 1,
            requireNotNull(memory),
            priorCycleStudentMessages(sink.tutorRespondTasks()),
            emptyList(),
        )
    }
}

internal class TutorInteractionSink(
    val currentPlanOutput: () -> TutorPlanOutput?,
    val currentInput: () -> TutorPlanInput,
    val question: () -> TutorQuestionContext,
    val clock: () -> Long,
    val interactionBusy: () -> Boolean,
    val setInteractionBusy: (Boolean) -> Unit,
    val setInteractionError: (String?) -> Unit,
    val hasExecutableProvider: () -> Boolean,
    val openModelSettings: () -> Unit,
    val currentCycleResponses: () -> List<TutorTurnResponse>,
    val tutorResponses: () -> List<TutorTurnResponse>,
    val tutorRespondTasks: () -> List<ModelTaskSnapshot>,
    val answerExposureKeys: () -> Set<TutorAnswerExposureKey>,
    val currentCycle: () -> Int,
    val executeTurn: (
        Int,
        TutorConversationMemory?,
        List<String>,
        List<TutorTurnHistoryEntry>,
    ) -> Unit,
    val interactions: TutorInteractionRepository,
)
