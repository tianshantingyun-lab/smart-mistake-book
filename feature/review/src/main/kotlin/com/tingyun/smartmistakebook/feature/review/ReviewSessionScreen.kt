package com.tingyun.smartmistakebook.feature.review

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealRequest
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealResult
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionProgress
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.domain.TutorCapabilityBlockReason
import com.tingyun.smartmistakebook.core.domain.TutorCapabilityDecision
import com.tingyun.smartmistakebook.core.domain.TutorCapabilityGate
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.ReviewRetryReason
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoiceEvaluation
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import com.tingyun.smartmistakebook.core.model.reviewRetryError
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SmartColors
import com.tingyun.smartmistakebook.core.ui.SubjectIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ReviewSessionScreen(
    onBack: () -> Unit,
    capabilities: AppCapabilitySnapshot,
    practiceUnitId: String,
    presentationId: String,
    teachingArtifact: VerifiedTeachingArtifact?,
    profile: StudyProfileOverview,
    queuePosition: Int,
    queueSize: Int,
    onSubmitChoice: suspend (StudyChoiceSubmission) -> StudyReviewChoiceSubmissionResult,
    onRevealAnswer: suspend (StudyAnswerRevealRequest) -> StudyAnswerRevealResult,
    onContinue: (StudyReviewChoiceSubmissionResult) -> Unit,
    /**
     * Pretest route for free-response items (spec batch-intake §3/§6 P2): a
     * question with a teaching artifact but no machine-checkable assessment
     * item routes to the tutor-judged pretest instead of showing "cannot
     * answer". Null keeps the legacy unavailable state (e.g. the surface has
     * no tutor navigation).
     */
    onRequestTutorPretest: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (val decision = TutorCapabilityGate().evaluate(capabilities, teachingArtifact)) {
        is TutorCapabilityDecision.Available -> {
            val sessionViewModel: ReviewSessionViewModel = viewModel(key = presentationId)
            val assessmentItem = decision.artifact.assessmentItems.singleOrNull()
            if (assessmentItem == null) {
                if (onRequestTutorPretest != null) {
                    // PretestRouting.TUTOR_JUDGED_FLOW: no options to score,
                    // but a teaching artifact exists — route to the tutor
                    // judge rather than declaring the question unanswerable.
                    ReviewSessionPretestTutorRoute(
                        onRequestTutorPretest = onRequestTutorPretest,
                        onBack = onBack,
                        modifier = modifier,
                    )
                } else {
                    ReviewSessionUnavailable(
                        reason = TutorCapabilityBlockReason.NO_ASSESSMENT_ITEM,
                        onBack = onBack,
                        modifier = modifier,
                    )
                }
            } else {
                ReviewSessionContent(
                    artifact = decision.artifact,
                    assessmentItem = assessmentItem,
                    practiceUnitId = practiceUnitId,
                    presentationId = presentationId,
                    profile = profile,
                    queuePosition = queuePosition,
                    queueSize = queueSize,
                    onSubmitChoice = onSubmitChoice,
                    onRevealAnswer = onRevealAnswer,
                    onContinue = onContinue,
                    onBack = onBack,
                    modifier = modifier,
                    sessionViewModel = sessionViewModel,
                )
            }
        }

        is TutorCapabilityDecision.Blocked -> ReviewSessionUnavailable(
            reason = decision.reason,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@Composable
private fun ReviewSessionContent(
    artifact: VerifiedTeachingArtifact,
    assessmentItem: TutorAssessmentItem,
    practiceUnitId: String,
    presentationId: String,
    profile: StudyProfileOverview,
    queuePosition: Int,
    queueSize: Int,
    onSubmitChoice: suspend (StudyChoiceSubmission) -> StudyReviewChoiceSubmissionResult,
    onRevealAnswer: suspend (StudyAnswerRevealRequest) -> StudyAnswerRevealResult,
    onContinue: (StudyReviewChoiceSubmissionResult) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    sessionViewModel: ReviewSessionViewModel,
) {
    val selectedChoice = sessionViewModel.selectedChoiceFor(assessmentItem)
    val submittedChoice = sessionViewModel.submittedChoiceFor(assessmentItem)
    val reviewSubmissionResult = sessionViewModel.reviewResultFor(assessmentItem)
    val submissionResult = reviewSubmissionResult?.attempt
    val safeQueueSize = queueSize.coerceAtLeast(1)
    val safeQueuePosition = queuePosition.coerceIn(1, safeQueueSize)
    RootPageColumn(
        modifier = modifier.testTag("review_session_root"),
    ) {
        SessionHeader(onBack = onBack, subject = artifact.subject)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { safeQueuePosition.toFloat() / safeQueueSize.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp),
            color = SmartColors.Jade,
            trackColor = SmartColors.Track,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "第 $safeQueuePosition / $safeQueueSize 题",
            color = SmartColors.InkSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader(title = artifact.title)
        Spacer(Modifier.height(12.dp))
        Text(
            text = reviewAdaptationMessage(profile),
            style = MaterialTheme.typography.bodyMedium,
            color = SmartColors.InkSecondary,
        )
        Spacer(Modifier.height(16.dp))
        SafeMarkdownText(
            markdown = assessmentItem.stemMarkdown,
            color = SmartColors.Ink,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 19.sp,
                lineHeight = 30.sp,
            ),
        )
        Spacer(Modifier.height(20.dp))
        assessmentItem.choices.forEach { choice ->
            ReviewChoiceRow(
                choice = choice,
                isCorrect = assessmentItem.evaluateChoice(choice.id).isCorrect,
                selectedChoice = selectedChoice,
                submittedChoice = submittedChoice,
                enabled = sessionViewModel.submissionStatus == ReviewSubmissionStatus.IDLE &&
                    sessionViewModel.revealStatus != ReviewRevealStatus.RECORDING,
                onSelect = { choiceId -> sessionViewModel.select(assessmentItem, choiceId) },
            )
            Spacer(Modifier.height(10.dp))
        }
        if (submittedChoice == null) {
            PrimaryActionButton(
                text = when (sessionViewModel.submissionStatus) {
                    ReviewSubmissionStatus.RECORDING -> "正在提交答案"
                    ReviewSubmissionStatus.FAILED -> "重新提交答案"
                    else -> if (sessionViewModel.revealStatus == ReviewRevealStatus.REVEALED) {
                        "提交已选答案（已看讲解）"
                    } else {
                        "提交答案"
                    }
                },
                onClick = {
                    sessionViewModel.requestSubmit(
                        assessmentItem = assessmentItem,
                        practiceUnitId = practiceUnitId,
                        presentationId = presentationId,
                        submit = onSubmitChoice,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review_submit_answer"),
                enabled = selectedChoice != null &&
                    sessionViewModel.submissionStatus != ReviewSubmissionStatus.RECORDING &&
                    sessionViewModel.revealStatus != ReviewRevealStatus.RECORDING,
                contentDescription = if (selectedChoice == null) {
                    "请先选择一个答案"
                } else {
                    "提交当前选择"
                },
            )
            Spacer(Modifier.height(10.dp))
        }
        if (sessionViewModel.submissionStatus == ReviewSubmissionStatus.RECORDING) {
            Text(
                text = "正在记录本次作答…",
                color = SmartColors.InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (sessionViewModel.submissionStatus == ReviewSubmissionStatus.FAILED) {
            Text(
                text = reviewRetryError(ReviewRetryReason.SUBMISSION_RECORDING).message,
                color = SmartColors.ErrorWarm,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (submittedChoice != null && submissionResult != null) {
            RecordedAttemptFeedback(
                evaluation = assessmentItem.evaluateChoice(submittedChoice),
            )
            Spacer(Modifier.height(10.dp))
        }
        androidx.compose.material3.TextButton(
            onClick = {
                sessionViewModel.requestReveal(
                    assessmentItem = assessmentItem,
                    practiceUnitId = practiceUnitId,
                    presentationId = presentationId,
                    reveal = onRevealAnswer,
                )
            },
            enabled = sessionViewModel.revealStatus != ReviewRevealStatus.RECORDING &&
                sessionViewModel.revealStatus != ReviewRevealStatus.REVEALED &&
                sessionViewModel.submissionStatus != ReviewSubmissionStatus.RECORDING,
            modifier = Modifier.testTag("review_reveal_answer"),
        ) {
            Text(
                when (sessionViewModel.revealStatus) {
                    ReviewRevealStatus.HIDDEN -> "不会做，查看完整讲解"
                    ReviewRevealStatus.RECORDING -> "正在记录并打开"
                    ReviewRevealStatus.REVEALED -> "完整讲解已打开"
                    ReviewRevealStatus.FAILED -> "重试打开讲解"
                },
            )
        }
        if (sessionViewModel.revealStatus == ReviewRevealStatus.FAILED) {
            Text(
                text = reviewRetryError(ReviewRetryReason.REVEAL_RECORDING).message,
                color = SmartColors.ErrorWarm,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        sessionViewModel.revealedExplanation?.let { explanation ->
            AnswerExplanation(explanation = explanation)
            Spacer(Modifier.height(10.dp))
        }
        if (reviewSubmissionResult != null) {
            androidx.compose.material3.TextButton(
                onClick = { onContinue(reviewSubmissionResult) },
                modifier = Modifier.testTag("review_next_item"),
            ) {
                Text(
                    if (reviewSubmissionResult.progress.status ==
                        StudyReviewSessionStatus.COMPLETED
                    ) "完成今日复习" else "下一题",
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * Pretest route for free-response items (spec batch-intake §3): the question
 * has a teaching artifact but no machine-scorable options, so the review
 * surface hands the student to the tutor-judged flow — the tutor session
 * judges their attempt and the verdict lands as the first real attempt.
 */
@Composable
private fun ReviewSessionPretestTutorRoute(
    onRequestTutorPretest: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    RootPageColumn(modifier = modifier.testTag("review_session_root")) {
        SessionHeader(onBack = onBack)
        Spacer(Modifier.height(12.dp))
        PaperDivider(Modifier.padding(vertical = 18.dp))
        Text(
            text = "这道题需要先试做再由讲解判定——去讲题里完成首次作答。",
            modifier = Modifier.testTag("review_pretest_tutor_hint"),
            color = SmartColors.InkSecondary,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryActionButton(
            text = "去讲题判定",
            onClick = onRequestTutorPretest,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("review_pretest_tutor_button"),
        )
    }
}

@Composable
private fun ReviewSessionUnavailable(
    reason: TutorCapabilityBlockReason,
    onBack: () -> Unit,
    modifier: Modifier,
) {    RootPageColumn(modifier = modifier.testTag("review_session_root")) {
        SessionHeader(onBack = onBack)
        Spacer(Modifier.height(12.dp))
        PaperDivider(Modifier.padding(vertical = 18.dp))
        Text(
            text = when (reason) {
                TutorCapabilityBlockReason.TUTOR_DISABLED_BY_BUILD -> "当前版本未启用交互式复习。"
                TutorCapabilityBlockReason.NO_VERIFIED_TEACHING_ARTIFACT -> "当前题目暂时无法复习。"
                TutorCapabilityBlockReason.NO_ASSESSMENT_ITEM -> "当前复习内容没有可作答的问题。"
            },
            modifier = Modifier.testTag("review_session_unavailable"),
            color = SmartColors.InkSecondary,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SessionHeader(onBack: () -> Unit, subject: String = "GENERAL") {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("review_session_back"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回复习首页",
                tint = SmartColors.Ink,
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "复习中",
                color = SmartColors.Ink,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        SubjectIcon(
            subject = runCatching { SubjectKind.valueOf(subject) }
                .getOrDefault(SubjectKind.GENERAL),
            contentDescription = subject,
            modifier = Modifier.size(42.dp),
        )
    }
}

@Composable
private fun RecordedAttemptFeedback(
    evaluation: TutorChoiceEvaluation,
) {
    val correct = evaluation.isCorrect
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_explanation"),
        color = if (correct) SmartColors.JadeSoft else SmartColors.Paper,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (correct) SmartColors.Jade else SmartColors.ErrorWarm,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (correct) "选择正确" else "这次选择还差一步",
                color = if (correct) SmartColors.JadeDark else SmartColors.ErrorWarm,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun AnswerExplanation(explanation: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_explanation"),
        color = SmartColors.JadeSoft,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SmartColors.Jade),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "完整讲解",
                color = SmartColors.JadeDark,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            SafeMarkdownText(
                markdown = explanation,
                color = SmartColors.Ink,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp),
            )
        }
    }
}

internal enum class ReviewSubmissionStatus {
    IDLE,
    RECORDING,
    RECORDED,
    FAILED,
}

internal enum class ReviewRevealStatus {
    HIDDEN,
    RECORDING,
    REVEALED,
    FAILED,
}

internal class ReviewSessionViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    var selectedChoice: String? by mutableStateOf(savedStateHandle[SELECTED_CHOICE_KEY])
        private set

    private var selectedAssessmentItemId: String? by mutableStateOf(
        savedStateHandle[SELECTED_ASSESSMENT_ITEM_KEY],
    )

    private var submittedChoice: String? by mutableStateOf(savedStateHandle[SUBMITTED_CHOICE_KEY])

    private var submittedAssessmentItemId: String? by mutableStateOf(
        savedStateHandle[SUBMITTED_ASSESSMENT_ITEM_KEY],
    )

    internal var submissionStatus by mutableStateOf(restoredSubmissionStatus())
        private set

    internal var revealStatus by mutableStateOf(restoredRevealStatus())
        private set

    var revealedExplanation: String? by mutableStateOf(savedStateHandle[REVEALED_EXPLANATION_KEY])
        private set

    private val presentationStartedAtEpochMillis: Long =
        savedStateHandle.get<Long>(PRESENTATION_STARTED_AT_KEY)
            ?: System.currentTimeMillis().also {
                savedStateHandle[PRESENTATION_STARTED_AT_KEY] = it
            }

    fun select(assessmentItem: TutorAssessmentItem, choiceId: String) {
        if (submittedChoiceFor(assessmentItem) != null) return
        if (savedStateHandle.get<String>(PENDING_SUBMISSION_REQUEST_ID_KEY) != null) return
        if (assessmentItem.choices.none { it.id == choiceId }) return

        selectedAssessmentItemId = assessmentItem.id
        selectedChoice = choiceId
        savedStateHandle[SELECTED_ASSESSMENT_ITEM_KEY] = assessmentItem.id
        savedStateHandle[SELECTED_CHOICE_KEY] = choiceId
    }

    fun selectedChoiceFor(assessmentItem: TutorAssessmentItem): String? = selectedChoice?.takeIf {
        selectedAssessmentItemId == assessmentItem.id &&
            assessmentItem.choices.any { choice -> choice.id == it }
    }

    fun submittedChoiceFor(assessmentItem: TutorAssessmentItem): String? = submittedChoice?.takeIf {
        submittedAssessmentItemId == assessmentItem.id &&
            assessmentItem.choices.any { choice -> choice.id == it }
    }

    fun resultFor(assessmentItem: TutorAssessmentItem): StudyChoiceSubmissionResult? {
        if (submittedChoiceFor(assessmentItem) == null) return null
        val attemptId = savedStateHandle.get<String>(ATTEMPT_ID_KEY) ?: return null
        val evidenceReason = savedStateHandle.get<String>(EVIDENCE_REASON_KEY)
            ?.let { runCatching { LearningEvidenceReason.valueOf(it) }.getOrNull() }
            ?: return null
        val isCorrect = savedStateHandle.get<Boolean>(RESULT_IS_CORRECT_KEY) ?: return null
        return StudyChoiceSubmissionResult(
            attemptId = attemptId,
            created = savedStateHandle.get<Boolean>(RESULT_CREATED_KEY) ?: false,
            isCorrect = isCorrect,
            evidenceReason = evidenceReason,
        )
    }

    fun reviewResultFor(
        assessmentItem: TutorAssessmentItem,
    ): StudyReviewChoiceSubmissionResult? {
        val attempt = resultFor(assessmentItem) ?: return null
        val sessionId = savedStateHandle.get<String>(REVIEW_SESSION_ID_KEY) ?: return null
        val planId = savedStateHandle.get<String>(REVIEW_PLAN_ID_KEY) ?: return null
        val currentOrdinal = savedStateHandle.get<Int>(REVIEW_CURRENT_ORDINAL_KEY) ?: return null
        val queueSize = savedStateHandle.get<Int>(REVIEW_QUEUE_SIZE_KEY) ?: return null
        val stateVersion = savedStateHandle.get<Long>(REVIEW_STATE_VERSION_KEY) ?: return null
        val status = savedStateHandle.get<String>(REVIEW_PROGRESS_STATUS_KEY)
            ?.let { runCatching { StudyReviewSessionStatus.valueOf(it) }.getOrNull() }
            ?: return null
        return StudyReviewChoiceSubmissionResult(
            attempt = attempt,
            progress = StudyReviewSessionProgress(
                sessionId = sessionId,
                planId = planId,
                currentOrdinal = currentOrdinal,
                queueSize = queueSize,
                stateVersion = stateVersion,
                status = status,
            ),
            nextPracticeUnitId = savedStateHandle[NEXT_PRACTICE_UNIT_ID_KEY],
        )
    }

    fun requestSubmit(
        assessmentItem: TutorAssessmentItem,
        practiceUnitId: String,
        presentationId: String,
        submit: suspend (StudyChoiceSubmission) -> StudyReviewChoiceSubmissionResult,
    ) {
        if (submittedChoiceFor(assessmentItem) != null) return
        if (submissionStatus == ReviewSubmissionStatus.RECORDING) return
        if (revealStatus == ReviewRevealStatus.RECORDING) return
        val choiceId = selectedChoiceFor(assessmentItem) ?: return

        val command = runCatching {
            submissionCommand(
                assessmentItem = assessmentItem,
                practiceUnitId = practiceUnitId,
                presentationId = presentationId,
                choiceId = choiceId,
            )
        }.getOrElse {
            updateSubmissionStatus(ReviewSubmissionStatus.FAILED)
            return
        }
        updateSubmissionStatus(ReviewSubmissionStatus.RECORDING)
        viewModelScope.launch {
            try {
                val result = submit(command)
                check(result.attempt.isCorrect == assessmentItem.evaluateChoice(choiceId).isCorrect) {
                    "Persisted assessment result disagrees with the verified review artifact"
                }
                submittedChoice = choiceId
                submittedAssessmentItemId = assessmentItem.id
                savedStateHandle[SUBMITTED_CHOICE_KEY] = choiceId
                savedStateHandle[SUBMITTED_ASSESSMENT_ITEM_KEY] = assessmentItem.id
                savedStateHandle[ATTEMPT_ID_KEY] = result.attempt.attemptId
                savedStateHandle[RESULT_CREATED_KEY] = result.attempt.created
                savedStateHandle[RESULT_IS_CORRECT_KEY] = result.attempt.isCorrect
                savedStateHandle[EVIDENCE_REASON_KEY] = result.attempt.evidenceReason.name
                savedStateHandle[REVIEW_SESSION_ID_KEY] = result.progress.sessionId
                savedStateHandle[REVIEW_PLAN_ID_KEY] = result.progress.planId
                savedStateHandle[REVIEW_CURRENT_ORDINAL_KEY] = result.progress.currentOrdinal
                savedStateHandle[REVIEW_QUEUE_SIZE_KEY] = result.progress.queueSize
                savedStateHandle[REVIEW_STATE_VERSION_KEY] = result.progress.stateVersion
                savedStateHandle[REVIEW_PROGRESS_STATUS_KEY] = result.progress.status.name
                savedStateHandle[NEXT_PRACTICE_UNIT_ID_KEY] = result.nextPracticeUnitId
                updateSubmissionStatus(ReviewSubmissionStatus.RECORDED)
            } catch (cancelled: CancellationException) {
                updateSubmissionStatus(ReviewSubmissionStatus.IDLE)
                throw cancelled
            } catch (_: Exception) {
                updateSubmissionStatus(ReviewSubmissionStatus.FAILED)
            }
        }
    }

    fun requestReveal(
        assessmentItem: TutorAssessmentItem,
        practiceUnitId: String,
        presentationId: String,
        reveal: suspend (StudyAnswerRevealRequest) -> StudyAnswerRevealResult,
    ) {
        if (submissionStatus == ReviewSubmissionStatus.RECORDING) return
        if (revealStatus == ReviewRevealStatus.RECORDING || revealedExplanation != null) return

        val command = runCatching {
            revealCommand(practiceUnitId, presentationId)
        }.getOrElse {
            updateRevealStatus(ReviewRevealStatus.FAILED)
            return
        }
        updateRevealStatus(ReviewRevealStatus.RECORDING)
        viewModelScope.launch {
            try {
                val result = reveal(command)
                check(result.explanationMarkdown.isNotBlank()) {
                    "Persisted answer reveal returned empty review content"
                }
                revealedExplanation = result.explanationMarkdown
                savedStateHandle[REVEALED_EXPLANATION_KEY] = result.explanationMarkdown
                updateRevealStatus(ReviewRevealStatus.REVEALED)
            } catch (cancelled: CancellationException) {
                updateRevealStatus(ReviewRevealStatus.HIDDEN)
                throw cancelled
            } catch (_: Exception) {
                updateRevealStatus(ReviewRevealStatus.FAILED)
            }
        }
    }

    private fun submissionCommand(
        assessmentItem: TutorAssessmentItem,
        practiceUnitId: String,
        presentationId: String,
        choiceId: String,
    ): StudyChoiceSubmission {
        savedStateHandle.get<String>(PENDING_SUBMISSION_REQUEST_ID_KEY)?.let { requestId ->
            return StudyChoiceSubmission(
                requestId = requestId,
                presentationId = requireNotNull(
                    savedStateHandle[PENDING_SUBMISSION_PRESENTATION_ID_KEY],
                ),
                practiceUnitId = requireNotNull(
                    savedStateHandle[PENDING_SUBMISSION_PRACTICE_UNIT_ID_KEY],
                ),
                selectedChoiceId = requireNotNull(
                    savedStateHandle[PENDING_SUBMISSION_CHOICE_ID_KEY],
                ),
                responseOrdinal = 1,
                durationSeconds = requireNotNull(
                    savedStateHandle[PENDING_SUBMISSION_DURATION_SECONDS_KEY],
                ),
                occurredAtEpochMillis = requireNotNull(
                    savedStateHandle[PENDING_SUBMISSION_OCCURRED_AT_KEY],
                ),
            ).also { existing ->
                require(existing.presentationId == presentationId)
                require(existing.practiceUnitId == practiceUnitId)
                require(existing.selectedChoiceId == choiceId)
                require(assessmentItem.choices.any { it.id == existing.selectedChoiceId })
            }
        }

        val now = System.currentTimeMillis()
        return StudyChoiceSubmission(
            requestId = "request:$presentationId:choice:1",
            presentationId = presentationId,
            practiceUnitId = practiceUnitId,
            selectedChoiceId = choiceId,
            responseOrdinal = 1,
            durationSeconds = elapsedSeconds(now),
            occurredAtEpochMillis = now,
        ).also(::persistSubmissionCommand)
    }

    private fun persistSubmissionCommand(command: StudyChoiceSubmission) {
        savedStateHandle[PENDING_SUBMISSION_REQUEST_ID_KEY] = command.requestId
        savedStateHandle[PENDING_SUBMISSION_PRESENTATION_ID_KEY] = command.presentationId
        savedStateHandle[PENDING_SUBMISSION_PRACTICE_UNIT_ID_KEY] = command.practiceUnitId
        savedStateHandle[PENDING_SUBMISSION_CHOICE_ID_KEY] = command.selectedChoiceId
        savedStateHandle[PENDING_SUBMISSION_DURATION_SECONDS_KEY] = command.durationSeconds
        savedStateHandle[PENDING_SUBMISSION_OCCURRED_AT_KEY] = command.occurredAtEpochMillis
    }

    private fun revealCommand(
        practiceUnitId: String,
        presentationId: String,
    ): StudyAnswerRevealRequest {
        savedStateHandle.get<String>(PENDING_REVEAL_REQUEST_ID_KEY)?.let { requestId ->
            return StudyAnswerRevealRequest(
                requestId = requestId,
                presentationId = requireNotNull(savedStateHandle[PENDING_REVEAL_PRESENTATION_ID_KEY]),
                practiceUnitId = requireNotNull(savedStateHandle[PENDING_REVEAL_PRACTICE_UNIT_ID_KEY]),
                occurredAtEpochMillis = requireNotNull(savedStateHandle[PENDING_REVEAL_OCCURRED_AT_KEY]),
            ).also { existing ->
                require(existing.presentationId == presentationId)
                require(existing.practiceUnitId == practiceUnitId)
            }
        }

        return StudyAnswerRevealRequest(
            requestId = "request:$presentationId:reveal:1",
            presentationId = presentationId,
            practiceUnitId = practiceUnitId,
            occurredAtEpochMillis = System.currentTimeMillis(),
        ).also(::persistRevealCommand)
    }

    private fun persistRevealCommand(command: StudyAnswerRevealRequest) {
        savedStateHandle[PENDING_REVEAL_REQUEST_ID_KEY] = command.requestId
        savedStateHandle[PENDING_REVEAL_PRESENTATION_ID_KEY] = command.presentationId
        savedStateHandle[PENDING_REVEAL_PRACTICE_UNIT_ID_KEY] = command.practiceUnitId
        savedStateHandle[PENDING_REVEAL_OCCURRED_AT_KEY] = command.occurredAtEpochMillis
    }

    private fun updateSubmissionStatus(status: ReviewSubmissionStatus) {
        submissionStatus = status
        savedStateHandle[SUBMISSION_STATUS_KEY] = status.name
    }

    private fun updateRevealStatus(status: ReviewRevealStatus) {
        revealStatus = status
        savedStateHandle[REVEAL_STATUS_KEY] = status.name
    }

    private fun restoredSubmissionStatus(): ReviewSubmissionStatus {
        if (savedStateHandle.get<String>(ATTEMPT_ID_KEY) != null) {
            return ReviewSubmissionStatus.RECORDED
        }
        val restored = savedStateHandle.get<String>(SUBMISSION_STATUS_KEY)
            ?.let { runCatching { ReviewSubmissionStatus.valueOf(it) }.getOrNull() }
            ?: ReviewSubmissionStatus.IDLE
        return if (restored == ReviewSubmissionStatus.RECORDING) {
            ReviewSubmissionStatus.FAILED
        } else {
            restored
        }
    }

    private fun restoredRevealStatus(): ReviewRevealStatus {
        if (savedStateHandle.get<String>(REVEALED_EXPLANATION_KEY) != null) {
            return ReviewRevealStatus.REVEALED
        }
        val restored = savedStateHandle.get<String>(REVEAL_STATUS_KEY)
            ?.let { runCatching { ReviewRevealStatus.valueOf(it) }.getOrNull() }
            ?: ReviewRevealStatus.HIDDEN
        return if (restored == ReviewRevealStatus.RECORDING) {
            ReviewRevealStatus.FAILED
        } else {
            restored
        }
    }

    private fun elapsedSeconds(nowEpochMillis: Long): Int =
        ((nowEpochMillis - presentationStartedAtEpochMillis).coerceAtLeast(0L) / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private companion object {
        const val SELECTED_CHOICE_KEY = "review_selected_choice"
        const val SELECTED_ASSESSMENT_ITEM_KEY = "review_selected_assessment_item"
        const val SUBMITTED_CHOICE_KEY = "review_submitted_choice"
        const val SUBMITTED_ASSESSMENT_ITEM_KEY = "review_submitted_assessment_item"
        const val SUBMISSION_STATUS_KEY = "review_submission_status"
        const val REVEAL_STATUS_KEY = "review_reveal_status"
        const val REVEALED_EXPLANATION_KEY = "review_revealed_explanation"
        const val PRESENTATION_STARTED_AT_KEY = "review_presentation_started_at"
        const val ATTEMPT_ID_KEY = "review_attempt_id"
        const val RESULT_CREATED_KEY = "review_result_created"
        const val RESULT_IS_CORRECT_KEY = "review_result_is_correct"
        const val EVIDENCE_REASON_KEY = "review_evidence_reason"
        const val REVIEW_SESSION_ID_KEY = "review_result_session_id"
        const val REVIEW_PLAN_ID_KEY = "review_result_plan_id"
        const val REVIEW_CURRENT_ORDINAL_KEY = "review_result_current_ordinal"
        const val REVIEW_QUEUE_SIZE_KEY = "review_result_queue_size"
        const val REVIEW_STATE_VERSION_KEY = "review_result_state_version"
        const val REVIEW_PROGRESS_STATUS_KEY = "review_result_progress_status"
        const val NEXT_PRACTICE_UNIT_ID_KEY = "review_result_next_practice_unit_id"
        const val PENDING_SUBMISSION_REQUEST_ID_KEY = "review_pending_submission_request_id"
        const val PENDING_SUBMISSION_PRESENTATION_ID_KEY = "review_pending_submission_presentation_id"
        const val PENDING_SUBMISSION_PRACTICE_UNIT_ID_KEY = "review_pending_submission_practice_unit_id"
        const val PENDING_SUBMISSION_CHOICE_ID_KEY = "review_pending_submission_choice_id"
        const val PENDING_SUBMISSION_DURATION_SECONDS_KEY = "review_pending_submission_duration_seconds"
        const val PENDING_SUBMISSION_OCCURRED_AT_KEY = "review_pending_submission_occurred_at"
        const val PENDING_REVEAL_REQUEST_ID_KEY = "review_pending_reveal_request_id"
        const val PENDING_REVEAL_PRESENTATION_ID_KEY = "review_pending_reveal_presentation_id"
        const val PENDING_REVEAL_PRACTICE_UNIT_ID_KEY = "review_pending_reveal_practice_unit_id"
        const val PENDING_REVEAL_OCCURRED_AT_KEY = "review_pending_reveal_occurred_at"
    }
}

private fun reviewAdaptationMessage(profile: StudyProfileOverview): String = when {
    !profile.hasLearningEvidence -> "按你的错题记录安排本题复习。"
    !profile.projectionIsCurrent -> "刚才的作答已保存，本题按当前安排继续。"
    else -> "根据你的复习记录安排本题。"
}
