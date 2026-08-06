package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.RevealTutorSolutionCommand
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.canExposeSolutionFor

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
)

internal fun transientDirectPreviewExposureKey(
    exposureKey: TutorAnswerExposureKey?,
    explanationMode: TutorExplanationMode?,
    activeMessage: TutorActiveStreamMessage?,
): TutorAnswerExposureKey? {
    if (exposureKey == null || explanationMode != TutorExplanationMode.DIRECT) return null
    if (activeMessage?.identity?.requestId != exposureKey.modelTaskRequestId) return null
    if (activeMessage.snapshot?.hasVisibleNonWhitespace != true) return null
    return exposureKey
}

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
                            output?.canExposeSolutionFor(input) == true
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
    val mapped = timeline.mapNotNull { item ->
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
                    output?.canExposeSolutionFor(input) == true
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
                    )
                } else {
                    null
                }
            }
        }
    }
    return mapped
        .groupBy { target ->
            val command = target.exposureCommand
            listOf(
                command.sessionId,
                command.questionDocumentId,
                command.revisionNumber,
                command.cycleOrdinal,
                command.turnOrdinal,
            )
        }
        .values
        .map { group ->
            group.maxBy { target ->
                when (target.exposureCommand.surfaceKind) {
                    TutorAnswerExposureSurfaceKind.RESPOND_REPLY -> 1
                    else -> 0
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
