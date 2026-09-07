package com.tingyun.smartmistakebook.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingyun.smartmistakebook.core.domain.KnowledgeQuizFeedbackResult
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewQueueEntry
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewSessionPlan
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SmartColors
import kotlinx.coroutines.CancellationException

/**
 * 知识点复习会话（spec dual-review-entry §3.3/§3.4）：一次"今日知识点复习"从首页进入后，
 * 沿 [KnowledgeReviewSessionPlan] 逐知识点：向模型要一道选择题 → 学生作答 → 判答 →
 * 回写掌握度 → 下一知识点，直到队列完成。dispatch（取题、回写）都作为 suspend 回调从
 * app 层注入——本屏不持有 repository/gateway，保持 feature/review 薄。
 */
@Composable
fun KnowledgeReviewSessionScreen(
    plan: KnowledgeReviewSessionPlan,
    onBack: () -> Unit,
    loadQuiz: suspend (KnowledgeReviewQueueEntry) -> TutorAssessmentItem?,
    submitAnswer: suspend (
        requestId: String,
        knowledgeNodeId: String,
        correctChoiceId: String,
        selectedChoiceId: String,
        occurredAtEpochMillis: Long,
    ) -> KnowledgeQuizFeedbackResult,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (plan.queue.isEmpty()) {
        EmptyKnowledgeReviewScreen(onBack = onBack, modifier = modifier)
        return
    }
    val viewModel: KnowledgeReviewSessionViewModel = viewModel(
        factory = KnowledgeReviewSessionViewModelFactory(plan),
    )
    // 进入节点且未取题时自动取题；continueToNext 清空后这里会为新节点再次取题。
    LaunchedEffect(viewModel.currentIndex, viewModel.loadStatus, viewModel.submittedChoice) {
        if (
            viewModel.currentEntry != null &&
            viewModel.loadStatus == KnowledgeQuizLoadStatus.IDLE &&
            viewModel.currentItem == null &&
            viewModel.submittedChoice == null
        ) {
            viewModel.loadCurrentQuiz(loadQuiz)
        }
    }

    val entry = viewModel.currentEntry
    val item = viewModel.currentItem
    val progressIndex = viewModel.currentIndex.coerceIn(0, viewModel.queueSize - 1)
    RootPageColumn(modifier = modifier.testTag("knowledge_review_session_root")) {
        KnowledgeSessionHeader(onBack = onBack)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = {
                (progressIndex + 1).toFloat() / viewModel.queueSize.toFloat()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp),
            color = SmartColors.Jade,
            trackColor = SmartColors.Track,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "第 ${progressIndex + 1} / ${viewModel.queueSize} 个知识点",
            color = SmartColors.InkSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        PaperDivider(Modifier.padding(vertical = 18.dp))
        if (entry != null) {
            SectionHeader(title = entry.displayName)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "知识点复习 · 掌握度巩固",
                style = MaterialTheme.typography.bodySmall,
                color = SmartColors.InkSecondary,
            )
            Spacer(Modifier.height(16.dp))
        }
        when (viewModel.loadStatus) {
            KnowledgeQuizLoadStatus.LOADING -> Text(
                text = "正在为这个知识点出题…",
                modifier = Modifier.testTag("knowledge_review_loading"),
                color = SmartColors.InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            KnowledgeQuizLoadStatus.FAILED -> KnowledgeQuizLoadFailure(
                onRetry = {
                    viewModel.loadCurrentQuiz(loadQuiz)
                },
                onBack = onBack,
            )
            KnowledgeQuizLoadStatus.IDLE -> Unit
            KnowledgeQuizLoadStatus.LOADED -> if (item != null) {
                KnowledgeQuizContent(
                    viewModel = viewModel,
                    item = item,
                    submitAnswer = submitAnswer,
                )
            }
        }
        // 已回写的节点（含进程重建后仅剩判决、题目待重观察的情形）渲染判决与前进按钮。
        viewModel.submittedChoice?.let { submittedChoice ->
            Spacer(Modifier.height(12.dp))
            viewModel.feedbackResult?.let { result ->
                KnowledgeQuizVerdict(result = result)
                Spacer(Modifier.height(10.dp))
            }
            if (viewModel.completed) {
                PrimaryActionButton(
                    text = "完成今日知识点复习",
                    onClick = onFinished,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("knowledge_review_finish"),
                )
            } else {
                OutlinedButton(
                    onClick = { viewModel.continueToNext() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("knowledge_review_next"),
                ) {
                    Text("下一知识点")
                }
            }
        }
    }
}

@Composable
private fun KnowledgeSessionHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("knowledge_review_back"),
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
                text = "知识点复习",
                color = SmartColors.Ink,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun KnowledgeQuizContent(
    viewModel: KnowledgeReviewSessionViewModel,
    item: TutorAssessmentItem,
    submitAnswer: suspend (
        requestId: String,
        knowledgeNodeId: String,
        correctChoiceId: String,
        selectedChoiceId: String,
        occurredAtEpochMillis: Long,
    ) -> KnowledgeQuizFeedbackResult,
) {
    SafeMarkdownText(
        markdown = item.stemMarkdown,
        color = SmartColors.Ink,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 18.sp,
            lineHeight = 29.sp,
        ),
    )
    Spacer(Modifier.height(20.dp))
    item.choices.forEach { choice ->
        KnowledgeChoiceRow(
            choice = choice,
            isCorrect = item.evaluateChoice(choice.id).isCorrect,
            selectedChoice = viewModel.selectedChoice,
            submittedChoice = viewModel.submittedChoice,
            enabled = viewModel.submitStatus == KnowledgeQuizSubmitStatus.IDLE,
            onSelect = viewModel::select,
        )
        Spacer(Modifier.height(10.dp))
    }
    if (viewModel.submittedChoice == null) {
        Spacer(Modifier.height(6.dp))
        PrimaryActionButton(
            text = when (viewModel.submitStatus) {
                KnowledgeQuizSubmitStatus.RECORDING -> "正在回写掌握度…"
                KnowledgeQuizSubmitStatus.FAILED -> "重新提交答案"
                else -> "提交答案"
            },
            onClick = {
                viewModel.submitAnswer(
                    requestId = "knowledge-quiz:review:${item.id}:${viewModel.submittedChoice ?: viewModel.selectedChoice}",
                    occurredAtEpochMillis = System.currentTimeMillis(),
                    submit = submitAnswer,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("knowledge_review_submit"),
            enabled = viewModel.selectedChoice != null &&
                viewModel.submitStatus == KnowledgeQuizSubmitStatus.IDLE,
            contentDescription = if (viewModel.selectedChoice == null) {
                "请先选择一个答案"
            } else {
                "提交当前选择并回写掌握度"
            },
        )
    }
}

@Composable
private fun KnowledgeChoiceRow(
    choice: TutorChoice,
    isCorrect: Boolean,
    selectedChoice: String?,
    submittedChoice: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val isSelected = selectedChoice == choice.id
    val isSubmittedSelection = submittedChoice == choice.id
    val outlineColor = when {
        isSubmittedSelection && isCorrect -> SmartColors.Jade
        isSubmittedSelection -> SmartColors.ErrorWarm
        isSelected -> SmartColors.Jade
        else -> SmartColors.Outline
    }
    val backgroundColor = if (isSelected) SmartColors.JadeSoft else SmartColors.Paper
    val shape = RoundedCornerShape(8.dp)
    val stateLabel = when {
        isSubmittedSelection && isCorrect -> "已提交，回答正确"
        isSubmittedSelection -> "已提交，需要修正"
        !enabled && isSelected -> "已选中，当前选择已锁定"
        isSelected -> "已选中，尚未提交"
        else -> "可选择"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, outlineColor, shape)
            .clickable(
                enabled = enabled && submittedChoice == null,
                role = Role.RadioButton,
            ) { onSelect(choice.id) }
            .semantics {
                role = Role.RadioButton
                selected = isSelected
                stateDescription = stateLabel
            }
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .testTag("knowledge_review_choice_${choice.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .border(1.dp, outlineColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = choice.id,
                color = SmartColors.Ink,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        SafeMarkdownText(
            markdown = choice.markdown,
            modifier = Modifier.weight(1f),
            color = SmartColors.Ink,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
        )
    }
}

@Composable
private fun KnowledgeQuizVerdict(result: KnowledgeQuizFeedbackResult) {
    val correct = result.isCorrect
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("knowledge_review_verdict"),
        color = if (correct) SmartColors.JadeSoft else SmartColors.Paper,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (correct) SmartColors.Jade else SmartColors.ErrorWarm,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (correct) SmartColors.JadeDark else SmartColors.ErrorWarm,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = if (correct) "回答正确" else "这次选择还差一步",
                    color = if (correct) SmartColors.JadeDark else SmartColors.ErrorWarm,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                result.rejectedReason?.let { reason ->
                    Text(
                        text = "这次作答已记录为观察（$reason）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = SmartColors.InkSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun KnowledgeQuizLoadFailure(
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "这道知识点暂时没有生成出来。",
            modifier = Modifier.testTag("knowledge_review_load_failed"),
            color = SmartColors.InkSecondary,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryActionButton(
            text = "重试出题",
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().testTag("knowledge_review_retry"),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回复习首页")
        }
    }
}

@Composable
private fun EmptyKnowledgeReviewScreen(
    onBack: () -> Unit,
    modifier: Modifier,
) {
    RootPageColumn(modifier = modifier.testTag("knowledge_review_empty")) {
        KnowledgeSessionHeader(onBack = onBack)
        Spacer(Modifier.height(12.dp))
        PaperDivider(Modifier.padding(vertical = 18.dp))
        Text(
            text = "今天没有需要复习的知识点——错题里涉及的知识点都已掌握或尚未到期。",
            color = SmartColors.InkSecondary,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
