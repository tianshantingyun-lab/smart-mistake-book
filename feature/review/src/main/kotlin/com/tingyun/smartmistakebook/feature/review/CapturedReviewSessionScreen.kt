package com.tingyun.smartmistakebook.feature.review

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyReviewRating
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReport
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewAdvanceResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionProgress
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.ReviewRetryReason
import com.tingyun.smartmistakebook.core.model.reviewRetryError
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SmartColors
import com.tingyun.smartmistakebook.core.ui.studentSubjectLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Reviews the student's exact saved transcription without inventing an answer key or another task. */
@Composable
fun CapturedReviewSessionScreen(
    onBack: () -> Unit,
    entry: StudyCatalogEntry,
    presentationId: String,
    queuePosition: Int,
    queueSize: Int,
    onSubmit: suspend (StudyReviewSelfReportSubmission) -> StudyReviewSelfReportSubmissionResult,
    onSubmitRating: suspend (StudyReviewRatingSubmission) -> StudyReviewRatingSubmissionResult,
    onContinue: (StudyReviewAdvanceResult) -> Unit,
    onNeedsTutor: (StudyReviewSelfReportSubmissionResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state: CapturedReviewSessionViewModel = viewModel(key = presentationId)
    LaunchedEffect(state.status, state.resultDispatched) {
        val recordedResult = state.recordedResult()
        if (
            state.status == CapturedReviewSubmissionStatus.RECORDED &&
            recordedResult != null &&
            !state.resultDispatched
        ) {
            if (recordedResult.report == StudyReviewSelfReport.NEEDS_HELP) {
                onNeedsTutor(recordedResult)
            } else {
                onContinue(recordedResult)
            }
            state.markResultDispatched()
        } else if (
            state.status == CapturedReviewSubmissionStatus.RECORDED &&
            state.ratingResult != null &&
            !state.resultDispatched
        ) {
            onContinue(state.ratingResult!!)
            state.markResultDispatched()
        }
    }

    val safeQueueSize = queueSize.coerceAtLeast(1)
    val safeQueuePosition = queuePosition.coerceIn(1, safeQueueSize)
    RootPageColumn(modifier = modifier.testTag("captured_review_session_root")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("captured_review_back"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回今日复习")
            }
            Text(
                text = "今日复习",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = SmartColors.Ink,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = entry.subject.studentSubjectLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = SmartColors.InkSecondary,
            )
        }
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
        LocalModeLine(text = "第 $safeQueuePosition / $safeQueueSize 题 · 复做你保存的原题")
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader(title = entry.title)
        Spacer(Modifier.height(12.dp))
        SafeMarkdownText(
            markdown = entry.problemMarkdown,
            color = SmartColors.Ink,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 19.sp,
                lineHeight = 30.sp,
            ),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "按这次的实际完成情况选一下",
            style = MaterialTheme.typography.bodyMedium,
            color = SmartColors.InkSecondary,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryActionButton(
            text = state.actionText(StudyReviewSelfReport.RECALL_COMPLETED),
            onClick = {
                state.submit(
                    report = StudyReviewSelfReport.RECALL_COMPLETED,
                    practiceUnitId = entry.practiceUnitId,
                    presentationId = presentationId,
                    submit = onSubmit,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review_self_report_recalled"),
            enabled = state.canSubmit(StudyReviewSelfReport.RECALL_COMPLETED),
            contentDescription = "记录为这次已独立完成",
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                state.submit(
                    report = StudyReviewSelfReport.RECALLED_WITH_EFFORT,
                    practiceUnitId = entry.practiceUnitId,
                    presentationId = presentationId,
                    submit = onSubmit,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review_self_report_effort"),
            enabled = state.canSubmit(StudyReviewSelfReport.RECALLED_WITH_EFFORT),
        ) {
            Text(state.actionText(StudyReviewSelfReport.RECALLED_WITH_EFFORT))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                state.submit(
                    report = StudyReviewSelfReport.NEEDS_HELP,
                    practiceUnitId = entry.practiceUnitId,
                    presentationId = presentationId,
                    submit = onSubmit,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review_self_report_stuck"),
            enabled = state.canSubmit(StudyReviewSelfReport.NEEDS_HELP),
        ) {
            Text(state.actionText(StudyReviewSelfReport.NEEDS_HELP))
        }
        // Four-key rating vocabulary (spec §2.21): the lightest grade "很轻松"
        // rides the rating channel; the three keys above keep their audited
        // self-report semantics. Together all four FSRS grades are reachable.
        OutlinedButton(
            onClick = {
                state.submitRating(
                    rating = StudyReviewRating.EASY,
                    practiceUnitId = entry.practiceUnitId,
                    presentationId = presentationId,
                    submit = onSubmitRating,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review_rating_easy"),
            enabled = state.canSubmit(StudyReviewSelfReport.RECALL_COMPLETED),
        ) {
            Text("很轻松（比平时省力）")
        }
        if (state.status == CapturedReviewSubmissionStatus.FAILED) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = reviewRetryError(ReviewRetryReason.SUBMISSION_RECORDING).message,
                modifier = Modifier.testTag("review_self_report_retry_message"),
                style = MaterialTheme.typography.bodySmall,
                color = SmartColors.ErrorWarm,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

internal enum class CapturedReviewSubmissionStatus {
    IDLE,
    RECORDING,
    RECORDED,
    FAILED,
}

internal class CapturedReviewSessionViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    internal var status by mutableStateOf(restoredStatus())
        private set

    internal var resultDispatched by mutableStateOf(
        savedStateHandle.get<Boolean>(RESULT_DISPATCHED_KEY) ?: false,
    )
        private set

    internal var ratingResult by mutableStateOf<StudyReviewRatingSubmissionResult?>(null)
        private set

    private val presentationStartedAtEpochMillis: Long =
        savedStateHandle.get<Long>(PRESENTATION_STARTED_AT_KEY)
            ?: System.currentTimeMillis().also {
                savedStateHandle[PRESENTATION_STARTED_AT_KEY] = it
            }

    fun canSubmit(report: StudyReviewSelfReport): Boolean {
        if (status == CapturedReviewSubmissionStatus.RECORDING ||
            status == CapturedReviewSubmissionStatus.RECORDED
        ) return false
        val pending = pendingReport()
        return pending == null || pending == report
    }

    fun actionText(report: StudyReviewSelfReport): String = when {
        status == CapturedReviewSubmissionStatus.RECORDING && pendingReport() == report ->
            "正在保存…"
        status == CapturedReviewSubmissionStatus.FAILED && pendingReport() == report ->
            when (report) {
                StudyReviewSelfReport.RECALL_COMPLETED -> "重新记录独立完成"
                StudyReviewSelfReport.RECALLED_WITH_EFFORT -> "重新记录勉强做对"
                StudyReviewSelfReport.NEEDS_HELP -> "重新记录并去讲题"
            }
        report == StudyReviewSelfReport.RECALL_COMPLETED -> "我已独立做完"
        report == StudyReviewSelfReport.RECALLED_WITH_EFFORT -> "勉强做对，但费了些劲"
        else -> "这里还卡住，去讲题"
    }

    fun submit(
        report: StudyReviewSelfReport,
        practiceUnitId: String,
        presentationId: String,
        submit: suspend (StudyReviewSelfReportSubmission) -> StudyReviewSelfReportSubmissionResult,
    ) {
        if (!canSubmit(report)) return
        val command = runCatching {
            submissionCommand(report, practiceUnitId, presentationId)
        }.getOrElse {
            updateStatus(CapturedReviewSubmissionStatus.FAILED)
            return
        }
        updateStatus(CapturedReviewSubmissionStatus.RECORDING)
        viewModelScope.launch {
            try {
                val result = submit(command)
                check(result.report == report) { "Persisted review report disagrees with the local control" }
                persistResult(result)
                updateStatus(CapturedReviewSubmissionStatus.RECORDED)
            } catch (cancelled: CancellationException) {
                updateStatus(CapturedReviewSubmissionStatus.IDLE)
                throw cancelled
            } catch (_: Exception) {
                updateStatus(CapturedReviewSubmissionStatus.FAILED)
            }
        }
    }

    fun submitRating(
        rating: StudyReviewRating,
        practiceUnitId: String,
        presentationId: String,
        submit: suspend (StudyReviewRatingSubmission) -> StudyReviewRatingSubmissionResult,
    ) {
        // The rating channel shares the self-report lock: one subjective
        // report per presentation visit, whichever key it came from.
        if (status == CapturedReviewSubmissionStatus.RECORDING ||
            status == CapturedReviewSubmissionStatus.RECORDED
        ) return
        updateStatus(CapturedReviewSubmissionStatus.RECORDING)
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val command = StudyReviewRatingSubmission(
                    requestId = "request:$presentationId:rating:$rating:1",
                    presentationId = presentationId,
                    practiceUnitId = practiceUnitId,
                    rating = rating,
                    durationSeconds = elapsedSeconds(now),
                    occurredAtEpochMillis = now,
                )
                val result = submit(command)
                ratingResult = result
                updateStatus(CapturedReviewSubmissionStatus.RECORDED)
            } catch (cancelled: CancellationException) {
                updateStatus(CapturedReviewSubmissionStatus.IDLE)
                throw cancelled
            } catch (_: Exception) {
                updateStatus(CapturedReviewSubmissionStatus.FAILED)
            }
        }
    }

    fun recordedResult(): StudyReviewSelfReportSubmissionResult? {
        val attemptId = savedStateHandle.get<String>(RESULT_ATTEMPT_ID_KEY) ?: return null
        val report = savedStateHandle.get<String>(RESULT_REPORT_KEY)
            ?.let { runCatching { StudyReviewSelfReport.valueOf(it) }.getOrNull() }
            ?: return null
        val evidenceReason = savedStateHandle.get<String>(RESULT_EVIDENCE_REASON_KEY)
            ?.let { runCatching { LearningEvidenceReason.valueOf(it) }.getOrNull() }
            ?: return null
        val progress = StudyReviewSessionProgress(
            sessionId = savedStateHandle.get<String>(RESULT_SESSION_ID_KEY) ?: return null,
            planId = savedStateHandle.get<String>(RESULT_PLAN_ID_KEY) ?: return null,
            currentOrdinal = savedStateHandle.get<Int>(RESULT_CURRENT_ORDINAL_KEY) ?: return null,
            queueSize = savedStateHandle.get<Int>(RESULT_QUEUE_SIZE_KEY) ?: return null,
            stateVersion = savedStateHandle.get<Long>(RESULT_STATE_VERSION_KEY) ?: return null,
            status = savedStateHandle.get<String>(RESULT_PROGRESS_STATUS_KEY)
                ?.let { runCatching { StudyReviewSessionStatus.valueOf(it) }.getOrNull() }
                ?: return null,
        )
        return StudyReviewSelfReportSubmissionResult(
            attemptId = attemptId,
            created = savedStateHandle.get<Boolean>(RESULT_CREATED_KEY) ?: false,
            report = report,
            evidenceReason = evidenceReason,
            progress = progress,
            nextPracticeUnitId = savedStateHandle[RESULT_NEXT_PRACTICE_UNIT_ID_KEY],
        )
    }

    fun markResultDispatched() {
        resultDispatched = true
        savedStateHandle[RESULT_DISPATCHED_KEY] = true
    }

    private fun submissionCommand(
        report: StudyReviewSelfReport,
        practiceUnitId: String,
        presentationId: String,
    ): StudyReviewSelfReportSubmission {
        savedStateHandle.get<String>(PENDING_REQUEST_ID_KEY)?.let { requestId ->
            return StudyReviewSelfReportSubmission(
                requestId = requestId,
                presentationId = requireNotNull(savedStateHandle[PENDING_PRESENTATION_ID_KEY]),
                practiceUnitId = requireNotNull(savedStateHandle[PENDING_PRACTICE_UNIT_ID_KEY]),
                report = StudyReviewSelfReport.valueOf(
                    requireNotNull(savedStateHandle[PENDING_REPORT_KEY]),
                ),
                durationSeconds = requireNotNull(savedStateHandle[PENDING_DURATION_SECONDS_KEY]),
                occurredAtEpochMillis = requireNotNull(savedStateHandle[PENDING_OCCURRED_AT_KEY]),
            ).also { existing ->
                require(existing.presentationId == presentationId)
                require(existing.practiceUnitId == practiceUnitId)
                require(existing.report == report)
            }
        }
        val now = System.currentTimeMillis()
        return StudyReviewSelfReportSubmission(
            requestId = "request:$presentationId:self-report:1",
            presentationId = presentationId,
            practiceUnitId = practiceUnitId,
            report = report,
            durationSeconds = elapsedSeconds(now),
            occurredAtEpochMillis = now,
        ).also(::persistCommand)
    }

    private fun persistCommand(command: StudyReviewSelfReportSubmission) {
        savedStateHandle[PENDING_REQUEST_ID_KEY] = command.requestId
        savedStateHandle[PENDING_PRESENTATION_ID_KEY] = command.presentationId
        savedStateHandle[PENDING_PRACTICE_UNIT_ID_KEY] = command.practiceUnitId
        savedStateHandle[PENDING_REPORT_KEY] = command.report.name
        savedStateHandle[PENDING_DURATION_SECONDS_KEY] = command.durationSeconds
        savedStateHandle[PENDING_OCCURRED_AT_KEY] = command.occurredAtEpochMillis
    }

    private fun persistResult(result: StudyReviewSelfReportSubmissionResult) {
        savedStateHandle[RESULT_ATTEMPT_ID_KEY] = result.attemptId
        savedStateHandle[RESULT_CREATED_KEY] = result.created
        savedStateHandle[RESULT_REPORT_KEY] = result.report.name
        savedStateHandle[RESULT_EVIDENCE_REASON_KEY] = result.evidenceReason.name
        savedStateHandle[RESULT_SESSION_ID_KEY] = result.progress.sessionId
        savedStateHandle[RESULT_PLAN_ID_KEY] = result.progress.planId
        savedStateHandle[RESULT_CURRENT_ORDINAL_KEY] = result.progress.currentOrdinal
        savedStateHandle[RESULT_QUEUE_SIZE_KEY] = result.progress.queueSize
        savedStateHandle[RESULT_STATE_VERSION_KEY] = result.progress.stateVersion
        savedStateHandle[RESULT_PROGRESS_STATUS_KEY] = result.progress.status.name
        savedStateHandle[RESULT_NEXT_PRACTICE_UNIT_ID_KEY] = result.nextPracticeUnitId
        resultDispatched = false
        savedStateHandle[RESULT_DISPATCHED_KEY] = false
    }

    private fun pendingReport(): StudyReviewSelfReport? = savedStateHandle.get<String>(PENDING_REPORT_KEY)
        ?.let { runCatching { StudyReviewSelfReport.valueOf(it) }.getOrNull() }

    private fun updateStatus(value: CapturedReviewSubmissionStatus) {
        status = value
        savedStateHandle[STATUS_KEY] = value.name
    }

    private fun restoredStatus(): CapturedReviewSubmissionStatus {
        if (savedStateHandle.get<String>(RESULT_ATTEMPT_ID_KEY) != null) {
            return CapturedReviewSubmissionStatus.RECORDED
        }
        val restored = savedStateHandle.get<String>(STATUS_KEY)
            ?.let { runCatching { CapturedReviewSubmissionStatus.valueOf(it) }.getOrNull() }
            ?: CapturedReviewSubmissionStatus.IDLE
        return if (restored == CapturedReviewSubmissionStatus.RECORDING) {
            CapturedReviewSubmissionStatus.FAILED
        } else {
            restored
        }
    }

    private fun elapsedSeconds(nowEpochMillis: Long): Int =
        ((nowEpochMillis - presentationStartedAtEpochMillis).coerceAtLeast(0L) / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private companion object {
        const val STATUS_KEY = "captured_review_status"
        const val PRESENTATION_STARTED_AT_KEY = "captured_review_started_at"
        const val PENDING_REQUEST_ID_KEY = "captured_review_pending_request"
        const val PENDING_PRESENTATION_ID_KEY = "captured_review_pending_presentation"
        const val PENDING_PRACTICE_UNIT_ID_KEY = "captured_review_pending_practice_unit"
        const val PENDING_REPORT_KEY = "captured_review_pending_report"
        const val PENDING_DURATION_SECONDS_KEY = "captured_review_pending_duration"
        const val PENDING_OCCURRED_AT_KEY = "captured_review_pending_occurred_at"
        const val RESULT_ATTEMPT_ID_KEY = "captured_review_result_attempt"
        const val RESULT_CREATED_KEY = "captured_review_result_created"
        const val RESULT_REPORT_KEY = "captured_review_result_report"
        const val RESULT_EVIDENCE_REASON_KEY = "captured_review_result_evidence"
        const val RESULT_SESSION_ID_KEY = "captured_review_result_session"
        const val RESULT_PLAN_ID_KEY = "captured_review_result_plan"
        const val RESULT_CURRENT_ORDINAL_KEY = "captured_review_result_ordinal"
        const val RESULT_QUEUE_SIZE_KEY = "captured_review_result_queue_size"
        const val RESULT_STATE_VERSION_KEY = "captured_review_result_state_version"
        const val RESULT_PROGRESS_STATUS_KEY = "captured_review_result_status"
        const val RESULT_NEXT_PRACTICE_UNIT_ID_KEY = "captured_review_result_next_practice"
        const val RESULT_DISPATCHED_KEY = "captured_review_result_dispatched"
    }
}
