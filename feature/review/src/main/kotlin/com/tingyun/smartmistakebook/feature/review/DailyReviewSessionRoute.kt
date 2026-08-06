package com.tingyun.smartmistakebook.feature.review
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.Jade
import com.tingyun.smartmistakebook.core.ui.Track

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionPortFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionResult
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceKind
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceResult
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommand
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommandFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingResult
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingSignal
import com.tingyun.smartmistakebook.core.data.review.DailyReviewRawAnswerSubmission
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeProblemPreview
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeSession
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeSessionStatus
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeState
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SmartColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID

internal enum class DailyReviewSessionOperation {
    IDLE,
    SAVING,
    SAVED,
    FAILED,
}

internal sealed interface DailyReviewSessionCompletion {
    data class PacingRecorded(
        val session: ReviewHomeSession,
    ) : DailyReviewSessionCompletion

    data object VerifiedAnswerRecorded : DailyReviewSessionCompletion

    data class ExplanationReady(
        val problem: ReviewHomeProblemPreview,
    ) : DailyReviewSessionCompletion
}

internal data class DailyReviewSessionViewState(
    val operation: DailyReviewSessionOperation = DailyReviewSessionOperation.IDLE,
    val pendingCompletionId: Long? = null,
    val pendingCompletion: DailyReviewSessionCompletion? = null,
    val answerEvidenceAvailable: Boolean = true,
)

internal class DailyReviewSessionViewModel(
    private val home: ReviewHomeState.Ready,
    private val pacingActions: DailyReviewPacingActionPort,
    private val answerSubmissionPorts: DailyReviewAnswerSubmissionPortFactory,
    private val pacingCommandFactory: DailyReviewPacingCommandFactory,
    private val assistanceActions: DailyReviewAssistanceActionPort =
        DailyReviewAssistanceActionPort { _, _, _ ->
            DailyReviewAssistanceResult.PresentationDisqualified
        },
) : ViewModel() {
    private val mutableState = MutableStateFlow(DailyReviewSessionViewState())
    val state: StateFlow<DailyReviewSessionViewState> = mutableState.asStateFlow()

    private var operationJob: Job? = null
    private var completionSequence = 0L
    private var pendingPacingSignal: DailyReviewPacingSignal? = null
    private var pendingPacingCommand: DailyReviewPacingCommand? = null

    fun recordPacing(
        signal: DailyReviewPacingSignal,
    ) {
        if (operationJob?.isActive == true) return
        if (
            home.session?.status != ReviewHomeSessionStatus.ACTIVE ||
            home.nextProblem == null
        ) {
            mutableState.value =
                mutableState.value.copy(
                    operation = DailyReviewSessionOperation.FAILED,
                )
            return
        }
        mutableState.value =
            mutableState.value.copy(
                operation = DailyReviewSessionOperation.SAVING,
            )
        val command =
            if (pendingPacingSignal == signal) {
                pendingPacingCommand
            } else {
                null
            } ?: try {
                pacingCommandFactory.create(signal, home).also { created ->
                    pendingPacingSignal = signal
                    pendingPacingCommand = created
                }
            } catch (_: Exception) {
                mutableState.value =
                    mutableState.value.copy(
                        operation = DailyReviewSessionOperation.FAILED,
                    )
                return
            }
        operationJob =
            viewModelScope.launch {
                val result =
                    try {
                        pacingActions.record(command)
                    } catch (cancelled: CancellationException) {
                        mutableState.value =
                            mutableState.value.copy(operation = DailyReviewSessionOperation.IDLE)
                        throw cancelled
                    } catch (_: Exception) {
                        DailyReviewPacingResult.ReloadRequired
                    }
                when (result) {
                    is DailyReviewPacingResult.Recorded -> {
                        pendingPacingSignal = null
                        pendingPacingCommand = null
                        publishCompletion(
                            DailyReviewSessionCompletion.PacingRecorded(result.session),
                        )
                    }
                    DailyReviewPacingResult.ReloadRequired ->
                        mutableState.value =
                            mutableState.value.copy(
                                operation = DailyReviewSessionOperation.FAILED,
                            )
                }
            }
    }

    fun submitAnswer(
        submission: DailyReviewRawAnswerSubmission,
    ) {
        if (operationJob?.isActive == true) return
        if (!mutableState.value.answerEvidenceAvailable) {
            mutableState.value =
                mutableState.value.copy(operation = DailyReviewSessionOperation.FAILED)
            return
        }
        mutableState.value =
            mutableState.value.copy(
                operation = DailyReviewSessionOperation.SAVING,
            )
        operationJob =
            viewModelScope.launch {
                val answerPort =
                    try {
                        answerSubmissionPorts.open(home)
                    } catch (cancelled: CancellationException) {
                        mutableState.value =
                            mutableState.value.copy(operation = DailyReviewSessionOperation.IDLE)
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                val result =
                    if (answerPort == null) {
                        DailyReviewAnswerSubmissionResult.VerifierUnavailable
                    } else {
                        try {
                            answerPort.submit(submission)
                        } catch (cancelled: CancellationException) {
                            mutableState.value =
                                mutableState.value.copy(operation = DailyReviewSessionOperation.IDLE)
                            throw cancelled
                        } catch (_: Exception) {
                            DailyReviewAnswerSubmissionResult.VerifierUnavailable
                        }
                    }
                when (result) {
                    is DailyReviewAnswerSubmissionResult.Recorded ->
                        publishCompletion(
                            DailyReviewSessionCompletion.VerifiedAnswerRecorded,
                        )
                    DailyReviewAnswerSubmissionResult.ReloadRequired,
                    DailyReviewAnswerSubmissionResult.Rejected,
                    DailyReviewAnswerSubmissionResult.VerifierUnavailable,
                    ->
                        mutableState.value =
                            mutableState.value.copy(
                                operation = DailyReviewSessionOperation.FAILED,
                            )
                }
            }
    }

    fun openExplanation() {
        if (operationJob?.isActive == true) return
        val problem = home.nextProblem
        val presentationId = home.session?.currentPresentationId
        if (problem == null || presentationId == null) return
        mutableState.value =
            mutableState.value.copy(operation = DailyReviewSessionOperation.SAVING)
        val eventId = assistanceEventId(home, DailyReviewAssistanceKind.ANSWER_REVEAL)
        operationJob =
            viewModelScope.launch {
                val result =
                    try {
                        assistanceActions.record(
                            home = home,
                            assistanceEventId = eventId,
                            kind = DailyReviewAssistanceKind.ANSWER_REVEAL,
                        )
                    } catch (cancelled: CancellationException) {
                        mutableState.value = mutableState.value.copy(
                            operation = DailyReviewSessionOperation.IDLE,
                        )
                        throw cancelled
                    } catch (_: Exception) {
                        DailyReviewAssistanceResult.PresentationDisqualified
                    }
                publishCompletion(
                    completion = DailyReviewSessionCompletion.ExplanationReady(problem),
                    answerEvidenceAvailable =
                        mutableState.value.answerEvidenceAvailable &&
                            result !is DailyReviewAssistanceResult.PresentationDisqualified,
                )
            }
    }

    fun acknowledgeCompletion(
        id: Long,
    ) {
        if (mutableState.value.pendingCompletionId == id) {
            mutableState.value =
                mutableState.value.copy(
                    operation = DailyReviewSessionOperation.IDLE,
                    pendingCompletionId = null,
                    pendingCompletion = null,
                )
        }
    }

    private fun publishCompletion(
        completion: DailyReviewSessionCompletion,
        answerEvidenceAvailable: Boolean = mutableState.value.answerEvidenceAvailable,
    ) {
        completionSequence += 1L
        mutableState.value =
            DailyReviewSessionViewState(
                operation = DailyReviewSessionOperation.SAVED,
                pendingCompletionId = completionSequence,
                pendingCompletion = completion,
                answerEvidenceAvailable = answerEvidenceAvailable,
            )
    }
}

internal fun assistanceEventId(
    home: ReviewHomeState.Ready,
    kind: DailyReviewAssistanceKind,
): String {
    val session = requireNotNull(home.session)
    val presentationId = requireNotNull(session.currentPresentationId)
    val source = "${session.sessionId}:$presentationId:${kind.name}"
    return "review-assistance:${UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8))}"
}

internal class DailyReviewSessionViewModelFactory(
    private val home: ReviewHomeState.Ready,
    private val pacingActions: DailyReviewPacingActionPort,
    private val answerSubmissionPorts: DailyReviewAnswerSubmissionPortFactory,
    private val pacingCommandFactory: DailyReviewPacingCommandFactory,
    private val assistanceActions: DailyReviewAssistanceActionPort,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
    ): T {
        require(modelClass.isAssignableFrom(DailyReviewSessionViewModel::class.java)) {
            "Unsupported daily-review session ViewModel"
        }
        return DailyReviewSessionViewModel(
            home = home,
            pacingActions = pacingActions,
            answerSubmissionPorts = answerSubmissionPorts,
            pacingCommandFactory = pacingCommandFactory,
            assistanceActions = assistanceActions,
        ) as T
    }
}

/**
 * Saved-question review flow.
 *
 * The default path accepts one-tap pacing signals and never forces the student to retype a complex
 * derivation. Choice, numeric, and visual-target components submit only raw learner input to the
 * host-issued verifier; no trusted answer token crosses into this feature.
 */
@Composable
fun DailyReviewSessionRoute(
    home: ReviewHomeState.Ready,
    pacingActions: DailyReviewPacingActionPort,
    answerSubmissionPorts: DailyReviewAnswerSubmissionPortFactory,
    assistanceActions: DailyReviewAssistanceActionPort,
    pacingCommandFactory: DailyReviewPacingCommandFactory,
    onBack: () -> Unit,
    onOpenExplanation: (ReviewHomeProblemPreview) -> Unit,
    onPacingRecorded: (ReviewHomeSession) -> Unit,
    onVerifiedAnswerRecorded: () -> Unit,
    modifier: Modifier = Modifier,
    answerContent:
        (@Composable ((DailyReviewRawAnswerSubmission) -> Unit) -> Unit)? = null,
) {
    val factory =
        remember(
            home,
            pacingActions,
            answerSubmissionPorts,
            assistanceActions,
            pacingCommandFactory,
        ) {
            DailyReviewSessionViewModelFactory(
                home = home,
                pacingActions = pacingActions,
                answerSubmissionPorts = answerSubmissionPorts,
                pacingCommandFactory = pacingCommandFactory,
                assistanceActions = assistanceActions,
            )
        }
    val stateHolder: DailyReviewSessionViewModel =
        viewModel(
            key = dailyReviewSessionViewModelKey(home),
            factory = factory,
        )
    val state by stateHolder.state.collectAsState()
    val completionId = state.pendingCompletionId
    val completion = state.pendingCompletion
    LaunchedEffect(completionId) {
        if (completionId != null && completion != null) {
            when (completion) {
                is DailyReviewSessionCompletion.PacingRecorded ->
                    onPacingRecorded(completion.session)
                DailyReviewSessionCompletion.VerifiedAnswerRecorded ->
                    onVerifiedAnswerRecorded()
                is DailyReviewSessionCompletion.ExplanationReady ->
                    onOpenExplanation(completion.problem)
            }
            stateHolder.acknowledgeCompletion(completionId)
        }
    }
    DailyReviewSessionScreen(
        home = home,
        operation = state.operation,
        onBack = onBack,
        onOpenExplanation = { stateHolder.openExplanation() },
        onDone = {
            stateHolder.recordPacing(DailyReviewPacingSignal.DONE)
        },
        onStuck = {
            stateHolder.recordPacing(DailyReviewPacingSignal.STUCK)
        },
        answerContent =
            answerContent?.takeIf { state.answerEvidenceAvailable }?.let { content ->
                {
                    content(stateHolder::submitAnswer)
                }
            },
        modifier = modifier,
    )
}

internal fun dailyReviewSessionViewModelKey(
    home: ReviewHomeState.Ready,
): String {
    val session = home.session
    return listOf(
        home.plan.planId,
        session?.sessionId.orEmpty(),
        session?.version?.toString().orEmpty(),
        session?.currentPresentationId.orEmpty(),
        home.nextProblem?.queueItemId.orEmpty(),
    ).joinToString(separator = ":")
}

@Composable
internal fun DailyReviewSessionScreen(
    home: ReviewHomeState.Ready,
    operation: DailyReviewSessionOperation,
    onBack: () -> Unit,
    onOpenExplanation: (ReviewHomeProblemPreview) -> Unit,
    onDone: () -> Unit,
    onStuck: () -> Unit,
    answerContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val problem = home.nextProblem
    val session = home.session
    val controlsEnabled =
        operation == DailyReviewSessionOperation.IDLE ||
            operation == DailyReviewSessionOperation.FAILED
    RootPageColumn(
        modifier = modifier.testTag("daily_review_session_root"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("daily_review_back"),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                )
            }
            Text(
                text = "复习",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
        }
        if (
            problem == null ||
            session == null ||
            session.status != ReviewHomeSessionStatus.ACTIVE
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "暂时无法打开",
                modifier = Modifier.testTag("daily_review_session_unavailable"),
                color = Ink,
            )
            return@RootPageColumn
        }

        val processed =
            home.plan.completedItemCount + home.plan.skippedItemCount
        val total = home.plan.scheduledItemCount.coerceAtLeast(1)
        LinearProgressIndicator(
            progress = { processed.toFloat() / total.toFloat() },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .testTag("daily_review_session_progress"),
            color = Jade,
            trackColor = Track,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "$processed / ${home.plan.scheduledItemCount}",
            style = MaterialTheme.typography.labelMedium,
            color = InkSecondary,
        )
        PaperDivider()
        Spacer(Modifier.height(16.dp))
        Text(
            text = problem.title ?: "原题",
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        SafeMarkdownText(
            markdown = problem.problemMarkdown,
            color = Ink,
            style =
                MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 19.sp,
                    lineHeight = 30.sp,
                ),
        )
        if (answerContent != null) {
            Spacer(Modifier.height(20.dp))
            answerContent()
        }
        Spacer(Modifier.height(20.dp))
        TextButton(
            onClick = { onOpenExplanation(problem) },
            modifier = Modifier.testTag("daily_review_explanation"),
            enabled = controlsEnabled,
        ) {
            Text("查看讲解")
        }
        Spacer(Modifier.height(8.dp))
        PrimaryActionButton(
            text =
                if (operation == DailyReviewSessionOperation.SAVING) {
                    "正在保存"
                } else if (operation == DailyReviewSessionOperation.SAVED) {
                    "已保存"
                } else {
                    "做完了"
                },
            onClick = onDone,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("daily_review_done"),
            enabled = controlsEnabled,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onStuck,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("daily_review_stuck"),
            enabled = controlsEnabled,
        ) {
            Text("卡住了")
        }
        if (operation == DailyReviewSessionOperation.FAILED) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "未保存",
                modifier = Modifier.testTag("daily_review_save_failed"),
                style = MaterialTheme.typography.bodySmall,
                color = ErrorWarm,
            )
        }
    }
}
