package com.tingyun.smartmistakebook.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.domain.SplitImportJobSummary
import com.tingyun.smartmistakebook.core.domain.SplitImportQuestionSummary
import com.tingyun.smartmistakebook.core.domain.SplitImportRepository
import com.tingyun.smartmistakebook.core.domain.SplitImportStatus
import com.tingyun.smartmistakebook.core.domain.SplitImportConfirmState
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.RootPageLazyColumn
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val SPLIT_IMPORT_TABLES = arrayOf(
    "split_import_job",
    "split_import_question",
)

/**
 * Review the pieces the model cut out of a whole-page / whole-PDF import.
 * The list shows every cut question; A student may uncheck pieces they will
 * not keep, then confirms the selected ones, and each confirmed piece opens
 * its own capture confirmation screen. The bubble entry returns here later.
 */
@Composable
fun SplitImportReviewRoute(
    repository: SplitImportRepository,
    onOpenDraft: (String) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val jobs by repository.observeActiveImports().collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    var selectedJobId by remember { mutableStateOf<String?>(null) }
    val job = jobs.firstOrNull { it.jobId == selectedJobId } ?: jobs.firstOrNull()
        ?: return

    if (job.status != SplitImportStatus.READY &&
        job.status != SplitImportStatus.PREPARING
    ) {
        return
    }

    RootPageLazyColumn(modifier = modifier.testTag("split_review_list")) {
        item(key = "split_review_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onFinished) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = Ink,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "录入整份题目",
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "切分完成，勾选要录入的题目",
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "${job.questions.filter { it.selected }.size}/${job.questions.size}",
                    color = JadeActive,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("split_review_count"),
                )
            }
            PaperDivider()
        }
        job.sourceUri?.let { sourceUri ->
            item(key = "split_review_source") {
                SplitSourceImage(
                    sourceUri = sourceUri,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        job.questions.forEach { question ->
            item(key = "split_q_${question.questionOrdinal}") {
                SplitQuestionCard(
                    ordinal = question.questionOrdinal,
                    selected = question.selected,
                    confirmed = question.confirmState != SplitImportConfirmState.PENDING,
                    sourceUri = job.sourceUri,
                    question = question,
                    onToggle = {
                        scope.launch {
                            repository.updateSelection(
                                jobId = job.jobId,
                                questionOrdinal = question.questionOrdinal,
                                selected = !question.selected,
                                occurredAtEpochMillis = System.currentTimeMillis(),
                            )
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        item(key = "split_review_footer") {
            Spacer(Modifier.height(6.dp))
            PrimaryActionButton(
                text = "录入所选（${job.questions.count { it.selected }}）",
                enabled = job.questions.any { it.selected },
                onClick = {
                    scope.launch {
                        val confirmList = job.questions
                            .filter { it.selected && it.confirmState == SplitImportConfirmState.PENDING }
                            .sortedBy { it.questionOrdinal }
                        for (question in confirmList) {
                            val draftId = question.splitDraftId
                            if (draftId != null) {
                                repository.markConfirmed(
                                    jobId = job.jobId,
                                    questionOrdinal = question.questionOrdinal,
                                    confirmState = SplitImportConfirmState.SAVED,
                                    splitDraftId = draftId,
                                    occurredAtEpochMillis = System.currentTimeMillis(),
                                )
                            }
                        }
                        val firstDraft = confirmList.mapNotNull { it.splitDraftId }.firstOrNull()
                        if (firstDraft != null) {
                            onOpenDraft(firstDraft)
                        } else {
                            repository.complete(job.jobId, System.currentTimeMillis())
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("split_review_confirm"),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SplitQuestionCard(
    ordinal: Int,
    selected: Boolean,
    confirmed: Boolean,
    sourceUri: String?,
    question: SplitImportQuestionSummary,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("split_question_$ordinal"),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) JadeSoft.copy(alpha = 0.28f) else Color(0xFFF7F5F0),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag("split_question_check_$ordinal"),
            )
            Spacer(Modifier.width(10.dp))
            sourceUri?.let {
                SplitRegionThumb(
                    question = question,
                    sourceUri = it,
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "第 $ordinal 题",
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (confirmed) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "已录入",
                        color = JadeActive,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Icon(
                imageVector = if (confirmed) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight
                },
                contentDescription = null,
                tint = if (confirmed) JadeActive else InkSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SplitSourceImage(
    sourceUri: String,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("split_review_source_image"),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF7F5F0),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "原卷页面",
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            com.tingyun.smartmistakebook.core.ui.BoundedLocalImage(
                imageUri = sourceUri,
                contentDescription = "整卷原图",
                expanded = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SplitRegionThumb(
    question: SplitImportQuestionSummary,
    sourceUri: String,
) {
    val region = question.region
    androidx.compose.material3.Surface(
        modifier = Modifier
            .size(width = 72.dp, height = 96.dp)
            .testTag("split_question_thumb_${question.questionOrdinal}"),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFEFEDE6),
    ) {
        Box(Modifier.fillMaxSize()) {
            com.tingyun.smartmistakebook.core.ui.BoundedLocalImage(
                imageUri = sourceUri,
                contentDescription = "题目区域",
                expanded = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    com.tingyun.smartmistakebook.core.ui.PrimaryActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    )
}