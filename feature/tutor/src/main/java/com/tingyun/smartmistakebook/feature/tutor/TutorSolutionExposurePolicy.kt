package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.RevealTutorSolutionCommand
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.canExposeSolutionFor
import com.tingyun.smartmistakebook.core.model.requiresRoundQuestionBinding

internal data class PlanSolutionPreviewKey(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val modelTaskRequestId: String,
)

internal data class TutorSolutionExposureTarget(
    val stableId: String,
    val exposureCommand: RecordTutorSolutionExposureCommand,
    val notBeforeEpochMillis: Long,
    val pendingRevealCommand: RevealTutorSolutionCommand? = null,
    /**
     * false = 只揭示、不落账。学生显式附加了题的那一轮用它：暴露账本与会话锚都是"会话题"形状
     * （键取 `input.questionDocument`、物化到会话锚），而这一轮学生看到的是所附之题的答案——
     * 记下去就是把展示算到会话题头上，附加题自己一次都不记。
     */
    val recordsExposure: Boolean = true,
)

private data class TutorTurnIdentity(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
)

internal fun ModelTaskSnapshot.toPlanSolutionPreviewKey(): PlanSolutionPreviewKey? {
    val input = request.input as? TutorPlanInput ?: return null
    return PlanSolutionPreviewKey(
        sessionId = input.sessionId,
        questionDocumentId = input.questionDocument.id,
        revisionNumber = input.draftRevisionNumber,
        cycleOrdinal = input.cycleOrdinal,
        turnOrdinal = input.turnOrdinal,
        modelTaskRequestId = request.requestId,
    )
}

internal fun tutorSolutionExposureCandidateKeys(
    timeline: List<TutorConversationTimelineItem>,
    responses: List<TutorTurnResponse>,
    longTermWritesBlocked: Boolean,
): Set<TutorAnswerExposureKey> {
    if (longTermWritesBlocked) return emptySet()
    val responsesByTurn = responses.associateBy(TutorTurnResponse::turnIdentity)
    return buildSet {
        timeline.forEach { item ->
            when (item) {
                is TutorConversationTimelineItem.Plan -> {
                    val task = item.task
                    val input = task.request.input as? TutorPlanInput ?: return@forEach
                    val responseUnlocked =
                        responsesByTurn[input.turnIdentity()]?.solutionRevealed == true
                    task.toPlanAnswerExposureKey()?.takeIf {
                        responseUnlocked &&
                            task.status == ModelTaskStatus.SUCCEEDED &&
                            task.output is TutorPlanOutput
                    }?.let(::add)
                }

                is TutorConversationTimelineItem.Reply -> {
                    val task = item.task
                    val input = task.request.input as? TutorRespondInput
                    val output = task.output as? TutorRespondOutput
                    task.toRespondAnswerExposureKey()?.takeIf {
                        task.status == ModelTaskStatus.SUCCEEDED &&
                            input != null &&
                            output?.canExposeSolutionFor(
                                input,
                                requiresRoundQuestionBinding =
                                task.request.requiresRoundQuestionBinding,
                            ) == true
                    }?.let(::add)
                }

                is TutorConversationTimelineItem.ChoiceFeedback -> Unit
            }
        }
    }
}

internal fun buildTutorSolutionExposureTargets(
    timeline: List<TutorConversationTimelineItem>,
    responses: List<TutorTurnResponse>,
    previewKeys: Set<PlanSolutionPreviewKey>,
    longTermWritesBlocked: Boolean,
): List<TutorSolutionExposureTarget> {
    if (longTermWritesBlocked) return emptyList()
    val responsesByTurn = responses.associateBy(TutorTurnResponse::turnIdentity)
    return timeline.mapNotNull { item ->
        when (item) {
            is TutorConversationTimelineItem.Plan -> {
                val task = item.task
                val input = task.request.input as? TutorPlanInput ?: return@mapNotNull null
                val output = task.output as? TutorPlanOutput
                val response = responsesByTurn[input.turnIdentity()]?.takeUnless(
                    TutorTurnResponse::hasChoicePayload,
                )
                val previewed = task.toPlanSolutionPreviewKey() in previewKeys
                if (
                    task.status == ModelTaskStatus.SUCCEEDED &&
                    output != null &&
                    output.plan.diagnosticItem == null &&
                    (response?.solutionRevealed == true || previewed)
                ) {
                    TutorSolutionExposureTarget(
                        stableId = item.stableId,
                        exposureCommand = requireNotNull(
                            task.toPlanAnswerExposureKey(),
                        ).toRecordCommand(),
                        notBeforeEpochMillis = maxOf(
                            task.updatedAtEpochMillis,
                            response?.updatedAtEpochMillis ?: 0,
                        ),
                        pendingRevealCommand = RevealTutorSolutionCommand(
                            sessionId = input.sessionId,
                            questionDocumentId = input.questionDocument.id,
                            revisionNumber = input.draftRevisionNumber,
                            cycleOrdinal = input.cycleOrdinal,
                            turnOrdinal = input.turnOrdinal,
                            occurredAtEpochMillis = 0,
                        ).takeIf { previewed && response?.solutionRevealed != true },
                    )
                } else {
                    null
                }
            }

            is TutorConversationTimelineItem.ChoiceFeedback -> item.planTask?.let { task ->
                val previewed = task.toPlanSolutionPreviewKey() in previewKeys
                if (item.response.solutionRevealed || previewed) {
                    TutorSolutionExposureTarget(
                        stableId = item.stableId,
                        exposureCommand = requireNotNull(
                            task.toPlanAnswerExposureKey(),
                        ).toRecordCommand(),
                        notBeforeEpochMillis = maxOf(
                            task.updatedAtEpochMillis,
                            item.response.updatedAtEpochMillis,
                        ),
                        pendingRevealCommand = RevealTutorSolutionCommand(
                            sessionId = item.response.sessionId,
                            questionDocumentId = item.response.questionDocumentId,
                            revisionNumber = item.response.revisionNumber,
                            cycleOrdinal = item.response.cycleOrdinal,
                            turnOrdinal = item.response.turnOrdinal,
                            occurredAtEpochMillis = 0,
                        ).takeIf { previewed && !item.response.solutionRevealed },
                    )
                } else {
                    null
                }
            }

            is TutorConversationTimelineItem.Reply -> {
                val task = item.task
                val input = task.request.input as? TutorRespondInput
                    ?: return@mapNotNull null
                val output = task.output as? TutorRespondOutput
                if (
                    task.status == ModelTaskStatus.SUCCEEDED &&
                    output?.canExposeSolutionFor(
                        input,
                        requiresRoundQuestionBinding = task.request.requiresRoundQuestionBinding,
                    ) == true
                ) {
                    val response = responsesByTurn[input.turnIdentity()]
                    TutorSolutionExposureTarget(
                        stableId = item.stableId,
                        exposureCommand = requireNotNull(
                            task.toRespondAnswerExposureKey(),
                        ).toRecordCommand(),
                        notBeforeEpochMillis = maxOf(
                            task.updatedAtEpochMillis,
                            response?.updatedAtEpochMillis ?: 0,
                        ),
                        pendingRevealCommand = RevealTutorSolutionCommand(
                            sessionId = input.sessionId,
                            questionDocumentId = input.questionDocument.id,
                            revisionNumber = input.draftRevisionNumber,
                            cycleOrdinal = input.cycleOrdinal,
                            turnOrdinal = input.turnOrdinal,
                            occurredAtEpochMillis = 0,
                        ).takeIf { response?.solutionRevealed != true },
                        // 附加轮：揭示照做（学生该看到所附之题的答案），但**不落账**。
                        recordsExposure = input.attachedQuestion == null,
                    )
                } else {
                    null
                }
            }
        }
    }
}

private fun TutorAnswerExposureKey.toRecordCommand() = RecordTutorSolutionExposureCommand(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    surfaceKind = surfaceKind,
    modelTaskRequestId = modelTaskRequestId,
    responseOrdinal = responseOrdinal,
    occurredAtEpochMillis = 0,
)

private fun TutorPlanInput.turnIdentity() = TutorTurnIdentity(
    sessionId = sessionId,
    questionDocumentId = questionDocument.id,
    revisionNumber = draftRevisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
)

private fun TutorRespondInput.turnIdentity() = TutorTurnIdentity(
    sessionId = sessionId,
    questionDocumentId = questionDocument.id,
    revisionNumber = draftRevisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
)

private fun TutorTurnResponse.turnIdentity() = TutorTurnIdentity(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
)
