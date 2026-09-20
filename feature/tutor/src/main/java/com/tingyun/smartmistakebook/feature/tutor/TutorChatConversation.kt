package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImageIntake
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorHistoryBudget
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.canExposeSolutionFor
import com.tingyun.smartmistakebook.core.model.requiresRoundQuestionBinding
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.ui.AttachedImagesSection
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.ThinkingCollapsibleCard
import com.tingyun.smartmistakebook.core.ui.TutorReplyMarkdown
import com.tingyun.smartmistakebook.core.ui.TutorVisualSceneRenderer

private data class TutorRespondExchangeKey(
    val sessionId: String,
    val revisionNumber: Int,
    val questionDocumentId: String,
    val responseOrdinal: Int,
    val studentMessage: String,
    val visibleTutorContextMarkdown: String?,
    val priorMessages: List<TutorChatHistoryEntry>,
    val requestedMove: TutorMoveType?,
)

private fun TutorRespondInput.exchangeKey() = TutorRespondExchangeKey(
    sessionId = sessionId,
    revisionNumber = draftRevisionNumber,
    questionDocumentId = questionDocument.id,
    responseOrdinal = responseOrdinal,
    studentMessage = studentMessage,
    visibleTutorContextMarkdown = visibleTutorContextMarkdown,
    priorMessages = priorMessages,
    requestedMove = requestedMove,
)

internal fun latestTutorRespondTasks(tasks: List<ModelTaskSnapshot>): List<ModelTaskSnapshot> = tasks
    .filter { it.request.input is TutorRespondInput }
    .groupBy { (it.request.input as TutorRespondInput).exchangeKey() }
    .values
    .map { attempts ->
        attempts.maxWith(
            compareBy<ModelTaskSnapshot>(ModelTaskSnapshot::createdAtEpochMillis)
                .thenBy(ModelTaskSnapshot::updatedAtEpochMillis)
                .thenBy { it.request.requestId },
        )
    }
    .sortedWith(
        compareBy<ModelTaskSnapshot> {
            (it.request.input as TutorRespondInput).responseOrdinal
        }.thenBy(ModelTaskSnapshot::createdAtEpochMillis)
            .thenBy { it.request.requestId },
    )

internal fun ModelTaskSnapshot.canRetryTutorResponse(): Boolean =
    request.input is TutorRespondInput && status == ModelTaskStatus.RETRYABLE_FAILURE

private fun ModelTaskSnapshot.requiresTutorModelSettings(): Boolean {
    val code = failure?.code ?: return false
    return code.requiresModelSettings()
}

internal fun tutorChatHistory(
    tasks: List<ModelTaskSnapshot>,
    answerExposureKeys: Set<TutorAnswerExposureKey>,
): List<TutorChatHistoryEntry> = TutorHistoryBudget.bounded(
    tutorChatExchanges(tasks, answerExposureKeys),
)

/**
 * 会话里所有已成功的完整轮次（不裁剪）。装配器要拿到**未裁剪**的列表才能把被挤出原样
 * 窗口的轮次压成摘要；先裁剪再摘要就等于让它们无声消失。
 */
internal fun tutorChatExchanges(
    tasks: List<ModelTaskSnapshot>,
    answerExposureKeys: Set<TutorAnswerExposureKey>,
): List<TutorChatHistoryEntry> {
    val succeeded = latestTutorRespondTasks(tasks).mapNotNull { task ->
        val input = task.request.input as TutorRespondInput
        val output = task.output as? TutorRespondOutput
        if (task.status != ModelTaskStatus.SUCCEEDED || output == null) return@mapNotNull null
        val answerWasExposed = output.canExposeSolutionFor(
            input,
            requiresRoundQuestionBinding = task.request.requiresRoundQuestionBinding,
        ) && task.toRespondAnswerExposureKey() in answerExposureKeys
        TutorChatHistoryEntry(
            studentMessage = input.studentMessage,
            assistantMarkdown = if (output.solutionRevealed && !answerWasExposed) {
                HIDDEN_TUTOR_ANSWER_CONTEXT
            } else {
                output.messageMarkdown
            },
        )
    }
    return succeeded
}

private const val HIDDEN_TUTOR_ANSWER_CONTEXT =
    "上一条讲解包含完整答案，但学生还没有完整看到；不要假设学生已经读过答案。"
private const val UNAUTHORIZED_TUTOR_ANSWER_MESSAGE =
    "我先不直接展开完整答案。你可以继续问当前步骤，或者点“看完整讲解”。"
private val LOCAL_REVEAL_SOLUTION_MOVE = TutorSuggestedMove(
    id = "local-reveal-solution",
    label = "看完整讲解",
    type = TutorMoveType.REVEAL_SOLUTION,
)

/**
 * Recovers the most recent contiguous suffix of exact student messages from persisted requests.
 * Model output and task success are intentionally irrelevant: an omitted or failed reply cannot
 * erase what the student actually said.
 */
internal fun priorCycleStudentMessages(tasks: List<ModelTaskSnapshot>): List<String> {
    val latestByOrdinal = latestTutorRespondTasks(tasks)
        .groupBy { task -> (task.request.input as TutorRespondInput).responseOrdinal }
        .values
        .map { sameOrdinal ->
            sameOrdinal.maxWith(
                compareBy<ModelTaskSnapshot>(ModelTaskSnapshot::createdAtEpochMillis)
                    .thenBy(ModelTaskSnapshot::updatedAtEpochMillis)
                    .thenBy { task -> task.request.requestId },
            )
        }
        .sortedBy { task -> (task.request.input as TutorRespondInput).responseOrdinal }
    var expectedOrdinal = (latestByOrdinal.lastOrNull()?.request?.input as? TutorRespondInput)
        ?.responseOrdinal
        ?: return emptyList()
    var retainedChars = 0
    return buildList {
        for (task in latestByOrdinal.asReversed()) {
            val input = task.request.input as TutorRespondInput
            if (input.responseOrdinal != expectedOrdinal) break
            val message = input.studentMessage
            if (
                size >= TutorPlanInput.MAX_PRIOR_CYCLE_STUDENT_MESSAGES ||
                retainedChars + message.length >
                TutorPlanInput.MAX_PRIOR_CYCLE_STUDENT_MESSAGE_CHARS
            ) {
                break
            }
            add(message)
            retainedChars += message.length
            expectedOrdinal -= 1
        }
    }.asReversed()
}

@Composable
internal fun TutorChatExchange(
    task: ModelTaskSnapshot,
    resolvedVisualScene: TutorVisualDocumentScene? = null,
    awaitingContinuation: Boolean = false,
    interactionEnabled: Boolean,
    recoveryEnabled: Boolean,
    executionMatchesCurrentProvider: Boolean,
    onRetry: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onMove: (TutorSuggestedMove) -> Unit,
    onRevealSolution: (TutorSuggestedMove) -> Unit,
    localIntentContent: @Composable (TutorRespondInput, TutorRespondOutput) -> Unit = { _, _ -> },
    onOpenVisualOriginal: () -> Unit = {},
    onReportVisualIncorrect: (String) -> Unit = {},
    attachedImageResolver: (suspend (AttachedImage) -> String?)? = null,
    /** 学生消息附图的规范资产读取器；为 null 时不渲染气泡里的图片。 */
    studentImageIntake: LobbyMessageImageIntake? = null,
    assistantBottomModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val input = task.request.input as TutorRespondInput
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TutorStudentMessageBubble(
            message = input.studentMessage,
            modifier = Modifier.testTag("tutor_chat_user_${input.responseOrdinal}"),
            attachedAssetIds = input.studentImageAssetRefs,
            imageIntake = studentImageIntake,
        )
        TutorAssistantReplyBubble(
            task = task,
            awaitingContinuation = awaitingContinuation,
            showActions = interactionEnabled,
            recoveryEnabled = recoveryEnabled,
            executionMatchesCurrentProvider = executionMatchesCurrentProvider,
            onRetry = onRetry,
            onOpenModelSettings = onOpenModelSettings,
            onMove = onMove,
            onRevealSolution = onRevealSolution,
            localIntentContent = localIntentContent,
            resolvedVisualScene = resolvedVisualScene,
            onOpenVisualOriginal = onOpenVisualOriginal,
            onReportVisualIncorrect = onReportVisualIncorrect,
            attachedImageResolver = attachedImageResolver,
            assistantBottomModifier = assistantBottomModifier,
        )
    }
}

@Composable
private fun TutorStudentMessageBubble(
    message: String,
    modifier: Modifier = Modifier,
    attachedAssetIds: List<String> = emptyList(),
    imageIntake: LobbyMessageImageIntake? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            color = JadeSoft.copy(alpha = 0.72f),
            shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                if (attachedAssetIds.isNotEmpty() && imageIntake != null) {
                    MessageImagesRow(
                        assetIds = attachedAssetIds,
                        imageIntake = imageIntake,
                        testTagPrefix = "session",
                    )
                }
                Text(
                    text = message,
                    modifier = Modifier.padding(
                        top = if (attachedAssetIds.isEmpty()) 0.dp else 8.dp,
                    ),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun TutorAssistantReplyBubble(
    task: ModelTaskSnapshot,
    awaitingContinuation: Boolean,
    showActions: Boolean,
    recoveryEnabled: Boolean,
    executionMatchesCurrentProvider: Boolean,
    onRetry: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onMove: (TutorSuggestedMove) -> Unit,
    onRevealSolution: (TutorSuggestedMove) -> Unit,
    localIntentContent: @Composable (TutorRespondInput, TutorRespondOutput) -> Unit,
    resolvedVisualScene: TutorVisualDocumentScene?,
    onOpenVisualOriginal: () -> Unit,
    onReportVisualIncorrect: (String) -> Unit,
    attachedImageResolver: (suspend (AttachedImage) -> String?)?,
    assistantBottomModifier: Modifier,
) {
    val input = task.request.input as TutorRespondInput
    val output = task.output as? TutorRespondOutput
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .testTag("tutor_chat_assistant_${input.responseOrdinal}"),
        color = Paper,
        shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Box {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (task.status) {
                    ModelTaskStatus.SUCCEEDED -> {
                        if (output == null) {
                            TutorTurnFailureCard(detail = TUTOR_REPLY_INCOMPLETE_DETAIL)
                        } else if (
                            output.solutionRevealed &&
                            !output.canExposeSolutionFor(
                                input,
                                requiresRoundQuestionBinding =
                                task.request.requiresRoundQuestionBinding,
                            )
                        ) {
                            SafeMarkdownText(
                                markdown = UNAUTHORIZED_TUTOR_ANSWER_MESSAGE,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (showActions) {
                                OutlineActionChip(
                                    text = LOCAL_REVEAL_SOLUTION_MOVE.label,
                                    onClick = {
                                        onRevealSolution(LOCAL_REVEAL_SOLUTION_MOVE)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("tutor_chat_local_reveal_solution"),
                                )
                            }
                        } else {
                            ThinkingCollapsibleCard(
                                thinkingMarkdown = output.thinkingMarkdown,
                                thinking = false,
                            )
                            TutorReplyMarkdown(
                                markdown = output.messageMarkdown,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            attachedImageResolver?.let { resolver ->
                                output.attachedImages
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { images ->
                                        AttachedImagesSection(
                                            images = images,
                                            resolve = resolver,
                                        )
                                    }
                            }
                            if (!TutorVisualIsolation.STRUCTURED_SCENE_ISOLATED) {
                                resolvedVisualScene?.let { scene ->
                                    TutorVisualSceneRenderer(
                                        scene = scene,
                                        onOpenOriginal = onOpenVisualOriginal,
                                        onReportIncorrect = {
                                            onReportVisualIncorrect(scene.sceneId)
                                        },
                                    )
                                }
                            }
                            localIntentContent(input, output)
                            // 2D/3D 结构化场景已隔离：不渲染模型直接输出的 visualScene。
                            if (!TutorVisualIsolation.STRUCTURED_SCENE_ISOLATED) {
                                output.visualScene?.let { TutorVisualSceneRenderer(it) }
                            }
                            if (output.solutionRevealed) {
                                Box(
                                    modifier = Modifier
                                        .size(1.dp)
                                        .testTag("tutor_chat_assistant_bottom_${input.responseOrdinal}")
                                        .then(assistantBottomModifier),
                                )
                            }
                            if (showActions) {
                                output.suggestedMoves.forEach { move ->
                                    OutlineActionChip(
                                        text = move.label,
                                        onClick = {
                                            if (move.type == TutorMoveType.REVEAL_SOLUTION) {
                                                onRevealSolution(move)
                                            } else {
                                                onMove(move)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("tutor_chat_move_${move.id}"),
                                    )
                                }
                            }
                        }
                    }

                    ModelTaskStatus.RETRYABLE_FAILURE,
                    ModelTaskStatus.PERMANENT_FAILURE,
                    ModelTaskStatus.CANCELLED,
                    -> {
                        val settingsRequired = task.requiresTutorModelSettings()
                        val actionLabel = when {
                            recoveryEnabled && executionMatchesCurrentProvider && settingsRequired ->
                                "检查模型设置"
                            showActions && task.canRetryTutorResponse() -> "重试"
                            else -> null
                        }
                        TutorTurnFailureCard(
                            detail = when {
                                !executionMatchesCurrentProvider -> "旧配置中的回复没有完成。"
                                settingsRequired -> "模型设置需要更新，题目已经保存。"
                                else -> TUTOR_REPLY_INCOMPLETE_DETAIL
                            },
                            primaryActionLabel = actionLabel,
                            primaryActionTestTag = if (settingsRequired) {
                                "tutor_chat_model_settings"
                            } else {
                                "tutor_chat_retry"
                            },
                            onPrimaryAction = if (settingsRequired) {
                                onOpenModelSettings
                            } else {
                                onRetry
                            },
                        )
                    }

                    else -> if (executionMatchesCurrentProvider && awaitingContinuation) {
                        Text(
                            "回复已暂停，点下方“继续对话”后接着完成。",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("tutor_chat_reply_paused"),
                        )
                    } else if (executionMatchesCurrentProvider) {
                        // 逐 token 的思考链与回答正文由屏幕组件的在途区统一渲染（同一条实时流，
                        // 与大厅同一套）：这里只留一个"还在生成"的紧凑指示，同一段文本不渲染两次。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(20.dp)
                                    .testTag("tutor_chat_reply_progress"),
                                color = JadeActive,
                                strokeWidth = 2.dp,
                            )
                            Text(
                                TUTOR_LIVE_PLACEHOLDER,
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        Text(
                            "旧配置中的回复未完成",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("tutor_chat_legacy_incomplete"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 失败卡的正文：内容不在快照里（输出缺失）或模型没给出可用的回复时，学生看到的就是这一句。
 */
private const val TUTOR_REPLY_INCOMPLETE_DETAIL = "这次回复没有完成。"
