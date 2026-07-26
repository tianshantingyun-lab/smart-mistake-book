package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorVisualTargetEvidence
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface

internal sealed interface TutorConversationTimelineItem {
    val occurredAtEpochMillis: Long
    val stableId: String

    data class Plan(
        val task: ModelTaskSnapshot,
    ) : TutorConversationTimelineItem {
        private val input = task.request.input as TutorPlanInput

        override val occurredAtEpochMillis: Long = task.request.occurredAtEpochMillis
        override val stableId: String = "plan:${input.cycleOrdinal}:${input.turnOrdinal}:${task.request.requestId}"
    }

    data class ChoiceFeedback(
        val response: TutorTurnResponse,
        val planTask: ModelTaskSnapshot?,
    ) : TutorConversationTimelineItem {
        override val occurredAtEpochMillis: Long = requireNotNull(
            response.choiceSubmittedAtEpochMillis,
        )
        override val stableId: String = "choice:${response.cycleOrdinal}:${response.turnOrdinal}"
    }

    data class Reply(
        val task: ModelTaskSnapshot,
    ) : TutorConversationTimelineItem {
        override val occurredAtEpochMillis: Long = task.request.occurredAtEpochMillis
        override val stableId: String = "reply:${task.request.requestId}"
    }
}

internal data class TutorTurnKey(
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
)

private fun TutorPlanInput.turnKey() = TutorTurnKey(cycleOrdinal, turnOrdinal)

private fun TutorTurnResponse.turnKey() = TutorTurnKey(cycleOrdinal, turnOrdinal)

private fun ModelTaskSnapshot.matches(question: TutorQuestionContext): Boolean = when (
    val input = request.input
) {
    is TutorPlanInput -> input.sessionId == question.sessionId &&
        input.draftRevisionNumber == question.revisionNumber &&
        input.questionDocument.id == question.questionDocument.document.id

    is TutorRespondInput -> input.sessionId == question.sessionId &&
        input.draftRevisionNumber == question.revisionNumber &&
        input.questionDocument.id == question.questionDocument.document.id

    else -> false
}

private fun TutorTurnResponse.matches(question: TutorQuestionContext): Boolean =
    sessionId == question.sessionId &&
        revisionNumber == question.revisionNumber &&
        questionDocumentId == question.questionDocument.document.id

internal fun latestTutorPlanTasks(
    question: TutorQuestionContext,
    tasks: List<ModelTaskSnapshot>,
): List<ModelTaskSnapshot> = latestExactTutorPlanTasks(
    tasks.filter { it.matches(question) && it.request.input is TutorPlanInput },
)

private fun latestExactTutorPlanTasks(
    tasks: List<ModelTaskSnapshot>,
): List<ModelTaskSnapshot> = tasks
    .groupBy { (it.request.input as TutorPlanInput).turnKey() }
    .values
    .map { attempts ->
        attempts.maxWith(
            compareBy<ModelTaskSnapshot>(ModelTaskSnapshot::createdAtEpochMillis)
                .thenBy { it.request.occurredAtEpochMillis }
                .thenBy { it.request.requestId }
                .thenBy(ModelTaskSnapshot::stateVersion),
        )
    }
    .sortedWith(
        compareBy<ModelTaskSnapshot> {
            (it.request.input as TutorPlanInput).cycleOrdinal
        }.thenBy { (it.request.input as TutorPlanInput).turnOrdinal }
            .thenBy { it.request.requestId },
    )

internal data class TutorConversationProjection(
    val planTasks: List<ModelTaskSnapshot>,
    val respondTasks: List<ModelTaskSnapshot>,
    val responses: List<TutorTurnResponse>,
    val latestPlanTasks: List<ModelTaskSnapshot>,
    val latestRespondTasks: List<ModelTaskSnapshot>,
    val timeline: List<TutorConversationTimelineItem>,
    val currentCycle: Int,
    val currentCyclePlanTasks: List<ModelTaskSnapshot>,
    val currentCycleResponses: List<TutorTurnResponse>,
    val responsesByTurn: Map<TutorTurnKey, TutorTurnResponse>,
    val observedPlanTask: ModelTaskSnapshot?,
)

internal fun buildTutorConversationProjection(
    question: TutorQuestionContext,
    planTasks: List<ModelTaskSnapshot>,
    respondTasks: List<ModelTaskSnapshot>,
    responses: List<TutorTurnResponse>,
): TutorConversationProjection {
    val exactPlanTasks = planTasks.filter { task ->
        task.matches(question) && task.request.input is TutorPlanInput
    }
    val exactRespondTasks = durableVisibleTutorRespondTasks(
        respondTasks.filter { task ->
            task.matches(question) && task.request.input is TutorRespondInput
        },
    )
    val exactResponses = responses.filter { response -> response.matches(question) }
    val latestPlans = latestExactTutorPlanTasks(exactPlanTasks)
    val latestResponses = latestTutorRespondTasks(exactRespondTasks)
    val currentCycle = maxOf(
        exactPlanTasks.maxOfOrNull { task ->
            (task.request.input as TutorPlanInput).cycleOrdinal
        } ?: 1,
        exactResponses.maxOfOrNull(TutorTurnResponse::cycleOrdinal) ?: 1,
    )
    val currentCyclePlans = latestPlans.filter { task ->
        (task.request.input as TutorPlanInput).cycleOrdinal == currentCycle
    }
    val currentCycleResponses = exactResponses.filter { response ->
        response.cycleOrdinal == currentCycle
    }
    val observedTask = currentCyclePlans.maxWithOrNull(
        compareBy<ModelTaskSnapshot> {
            (it.request.input as TutorPlanInput).turnOrdinal
        }.thenBy(ModelTaskSnapshot::createdAtEpochMillis)
            .thenBy { it.request.requestId },
    )
    return TutorConversationProjection(
        planTasks = exactPlanTasks,
        respondTasks = exactRespondTasks,
        responses = exactResponses,
        latestPlanTasks = latestPlans,
        latestRespondTasks = latestResponses,
        timeline = buildExactTutorConversationTimeline(
            planTasks = latestPlans,
            respondTasks = latestResponses,
            responses = exactResponses,
        ),
        currentCycle = currentCycle,
        currentCyclePlanTasks = currentCyclePlans,
        currentCycleResponses = currentCycleResponses,
        responsesByTurn = exactResponses.associateBy(TutorTurnResponse::turnKey),
        observedPlanTask = observedTask,
    )
}

internal fun buildTutorConversationTimeline(
    question: TutorQuestionContext,
    planTasks: List<ModelTaskSnapshot>,
    respondTasks: List<ModelTaskSnapshot>,
    responses: List<TutorTurnResponse>,
): List<TutorConversationTimelineItem> = buildTutorConversationProjection(
    question = question,
    planTasks = planTasks,
    respondTasks = respondTasks,
    responses = responses,
).timeline

internal fun tutorContiguousHistory(
    planTasks: List<ModelTaskSnapshot>,
    respondTasks: List<ModelTaskSnapshot>,
    responses: List<TutorTurnResponse>,
    visualTargetEvidence: List<TutorVisualTargetEvidence>,
): List<TutorTurnHistoryEntry> {
    val visualAnswersByTurn = visualTargetEvidence
        .mapNotNull { evidence ->
            val directive = (planTasks + respondTasks)
                .firstOrNull { task ->
                    task.status == ModelTaskStatus.SUCCEEDED &&
                        task.request.requestId == evidence.modelTaskRequestId &&
                        task.visualAnchor() == evidence.anchor
                }
                ?.visualTargetDirective()
                ?: return@mapNotNull null
            TutorTurnKey(
                cycleOrdinal = evidence.anchor.cycleOrdinal,
                turnOrdinal = evidence.anchor.turnOrdinal,
            ) to VisualTutorAnswer(evidence, directive)
        }
        .groupBy(keySelector = Pair<TutorTurnKey, VisualTutorAnswer>::first)
        .mapValues { (_, candidates) ->
            candidates.maxBy { (_, answer) -> answer.evidence.submittedAtEpochMillis }.second
        }
    val responsesByTurn = responses.associateBy(TutorTurnResponse::turnKey)
    val cycleOrdinal = responses.minOfOrNull(TutorTurnResponse::cycleOrdinal)
        ?: visualTargetEvidence.minOfOrNull { evidence -> evidence.anchor.cycleOrdinal }
        ?: return emptyList()
    return buildList {
        while (size < TutorPlanInput.MAX_TURNS) {
            val turnOrdinal = size + 1
            val turnKey = TutorTurnKey(cycleOrdinal, turnOrdinal)
            val response = responsesByTurn[turnKey] ?: return@buildList
            val requestedMove = response.requestedMove ?: return@buildList
            if (response.hasChoicePayload) {
                add(
                    TutorTurnHistoryEntry(
                        turnOrdinal = turnOrdinal,
                        diagnosticStemMarkdown = requireNotNull(
                            response.diagnosticStemMarkdown,
                        ),
                        selectedChoiceMarkdown = requireNotNull(
                            response.selectedChoiceMarkdown,
                        ),
                        selectionWasCorrect = requireNotNull(response.selectionWasCorrect),
                        feedbackMarkdown = requireNotNull(response.feedbackMarkdown),
                        requestedMove = requestedMove,
                    ),
                )
                continue
            }
            val visualAnswer = visualAnswersByTurn[turnKey] ?: return@buildList
            add(
                TutorTurnHistoryEntry(
                    turnOrdinal = turnOrdinal,
                    diagnosticStemMarkdown = visualAnswer.directive.promptMarkdown,
                    selectedChoiceMarkdown = "图中指定位置",
                    selectionWasCorrect = visualAnswer.evidence.selectionWasCorrect,
                    feedbackMarkdown = "已在图中选中目标位置。",
                    requestedMove = requestedMove,
                ),
            )
        }
    }
}

private data class VisualTutorAnswer(
    val evidence: TutorVisualTargetEvidence,
    val directive: TutorInteractionDirective.VisualTarget,
)

private fun ModelTaskSnapshot.visualTargetDirective(): TutorInteractionDirective.VisualTarget? =
    when (val taskOutput = output) {
        is TutorPlanOutput -> taskOutput.plan.interactionDirective as?
            TutorInteractionDirective.VisualTarget
        is TutorRespondOutput -> taskOutput.interactionDirective as?
            TutorInteractionDirective.VisualTarget
        else -> null
    }

private fun ModelTaskSnapshot.visualAnchor(): TutorVisualTurnAnchor? =
    when (val taskInput = request.input) {
        is TutorPlanInput -> TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.PLAN,
            cycleOrdinal = taskInput.cycleOrdinal,
            turnOrdinal = taskInput.turnOrdinal,
        )
        is TutorRespondInput -> TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.FOLLOW_UP,
            cycleOrdinal = taskInput.cycleOrdinal,
            turnOrdinal = taskInput.turnOrdinal,
            responseOrdinal = taskInput.responseOrdinal,
        )
        else -> null
    }

private fun buildExactTutorConversationTimeline(
    planTasks: List<ModelTaskSnapshot>,
    respondTasks: List<ModelTaskSnapshot>,
    responses: List<TutorTurnResponse>,
): List<TutorConversationTimelineItem> {
    val plansByTurn = planTasks.associateBy { task ->
        (task.request.input as TutorPlanInput).turnKey()
    }
    val items = buildList {
        planTasks.forEach { add(TutorConversationTimelineItem.Plan(it)) }
        responses
            .asSequence()
            .filter(TutorTurnResponse::hasChoicePayload)
            .forEach { response ->
                add(
                    TutorConversationTimelineItem.ChoiceFeedback(
                        response = response,
                        planTask = plansByTurn[response.turnKey()],
                    ),
                )
            }
        respondTasks.forEach {
            add(TutorConversationTimelineItem.Reply(it))
        }
    }
    return items.sortedWith(
        compareBy<TutorConversationTimelineItem>(
            TutorConversationTimelineItem::occurredAtEpochMillis,
        ).thenBy { item ->
            when (item) {
                is TutorConversationTimelineItem.Plan -> 0
                is TutorConversationTimelineItem.ChoiceFeedback -> 1
                is TutorConversationTimelineItem.Reply -> 2
            }
        }.thenBy(TutorConversationTimelineItem::stableId),
    )
}
