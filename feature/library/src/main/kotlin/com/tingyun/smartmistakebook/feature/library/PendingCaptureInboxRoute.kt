package com.tingyun.smartmistakebook.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.PendingCaptureItem
import com.tingyun.smartmistakebook.core.domain.PendingCaptureStage
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkMuted
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.RootPageLazyColumn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Composable
fun PendingCaptureInboxRoute(
    repository: CaptureWorkflowRepository,
    onResumeDraft: (String) -> Unit,
    onOpenTutorSession: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateFlow = remember(repository) {
        repository.observePendingCaptures()
            .map<List<PendingCaptureItem>, PendingCaptureInboxState>(
                PendingCaptureInboxState::Ready,
            )
            .catch { emit(PendingCaptureInboxState.Unavailable) }
    }
    val state by stateFlow.collectAsStateWithLifecycle(
        initialValue = PendingCaptureInboxState.Loading,
    )

    PendingCaptureInboxContent(
        state = state,
        onOpenItem = { item ->
            when (val target = pendingCaptureOpenTarget(item)) {
                is PendingCaptureOpenTarget.Draft -> onResumeDraft(target.draftId)
                is PendingCaptureOpenTarget.TutorSession -> {
                    onOpenTutorSession(target.sessionId)
                }
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}

internal sealed interface PendingCaptureInboxState {
    data object Loading : PendingCaptureInboxState
    data class Ready(val items: List<PendingCaptureItem>) : PendingCaptureInboxState
    data object Unavailable : PendingCaptureInboxState
}

internal sealed interface PendingCaptureOpenTarget {
    data class Draft(val draftId: String) : PendingCaptureOpenTarget
    data class TutorSession(val sessionId: String) : PendingCaptureOpenTarget
}

internal fun pendingCaptureOpenTarget(item: PendingCaptureItem): PendingCaptureOpenTarget {
    val sessionId = item.tutorSessionId
    return if (item.stage == PendingCaptureStage.TUTOR_SESSION_READY && sessionId != null) {
        PendingCaptureOpenTarget.TutorSession(sessionId)
    } else {
        PendingCaptureOpenTarget.Draft(item.draftId)
    }
}

@Composable
internal fun PendingCaptureInboxContent(
    state: PendingCaptureInboxState,
    onOpenItem: (PendingCaptureItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RootPageLazyColumn(
        modifier = modifier.testTag("pending_capture_inbox"),
    ) {
        item(key = "pending_header") {
            PendingCaptureHeader(onBack)
            Text(
                text = "还没完成的拍题会留在这里；存入错题本后自动移出。",
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            PaperDivider(Modifier.padding(top = 16.dp, bottom = 10.dp))
        }
        when (state) {
            PendingCaptureInboxState.Loading -> item(key = "pending_loading") {
                PendingLoading()
            }
            PendingCaptureInboxState.Unavailable -> item(key = "pending_unavailable") {
                PendingUnavailable()
            }
            is PendingCaptureInboxState.Ready -> if (state.items.isEmpty()) {
                item(key = "pending_empty") { PendingEmpty() }
            } else {
                item(key = "pending_summary") {
                    PendingCaptureSummaryCard(summarizePendingCaptures(state.items))
                    Spacer(Modifier.height(6.dp))
                }
                items(
                    count = state.items.size,
                    key = { index -> state.items[index].draftId },
                ) { index ->
                    val item = state.items[index]
                    PendingCaptureRow(
                        item = item,
                        onClick = { onOpenItem(item) },
                    )
                }
            }
        }
        item(key = "pending_footer") { Spacer(Modifier.height(20.dp)) }
    }
}

internal data class PendingCaptureSummary(
    val total: Int,
    val modelWorking: Int,
    val readyForStudent: Int,
    val needsAttention: Int,
) {
    init {
        require(total >= 0)
        require(modelWorking >= 0)
        require(readyForStudent >= 0)
        require(needsAttention >= 0)
        require(total == modelWorking + readyForStudent + needsAttention)
    }
}

internal fun summarizePendingCaptures(items: List<PendingCaptureItem>): PendingCaptureSummary {
    var modelWorking = 0
    var readyForStudent = 0
    var needsAttention = 0
    items.forEach { item ->
        when (item.stage) {
            PendingCaptureStage.MODEL_WORKING -> modelWorking++
            PendingCaptureStage.SOURCE_UNAVAILABLE,
            PendingCaptureStage.RECAPTURE_REQUIRED,
            PendingCaptureStage.RETRY_OR_MANUAL,
            -> needsAttention++
            PendingCaptureStage.TUTOR_SESSION_READY,
            PendingCaptureStage.MANUAL_REVIEW_REQUIRED,
            PendingCaptureStage.READY_TO_REVIEW,
            PendingCaptureStage.READY_TO_CONTINUE,
            -> readyForStudent++
        }
    }
    return PendingCaptureSummary(
        total = items.size,
        modelWorking = modelWorking,
        readyForStudent = readyForStudent,
        needsAttention = needsAttention,
    )
}

@Composable
private fun PendingCaptureSummaryCard(summary: PendingCaptureSummary) {
    val details = buildList {
        if (summary.modelWorking > 0) add("正在整理 ${summary.modelWorking} 道")
        if (summary.readyForStudent > 0) add("等你继续 ${summary.readyForStudent} 道")
        if (summary.needsAttention > 0) add("需要处理 ${summary.needsAttention} 道")
    }.joinToString(" · ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pending_capture_summary"),
        shape = RoundedCornerShape(10.dp),
        color = JadeSoft.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${summary.total} 道临时题记录保留在本机",
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = details,
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PendingCaptureHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .testTag("pending_capture_back"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = Ink,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "待处理题目",
            color = Ink,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun PendingCaptureRow(
    item: PendingCaptureItem,
    onClick: () -> Unit,
) {
    val needsAttention = item.stage == PendingCaptureStage.SOURCE_UNAVAILABLE ||
        item.stage == PendingCaptureStage.RECAPTURE_REQUIRED ||
        item.stage == PendingCaptureStage.RETRY_OR_MANUAL
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 76.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .testTag("pending_capture_${item.draftId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                needsAttention -> Icons.Outlined.WarningAmber
                item.stage == PendingCaptureStage.TUTOR_SESSION_READY -> Icons.AutoMirrored.Outlined.Chat
                else -> Icons.Outlined.Schedule
            },
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (needsAttention) ErrorWarm else JadeActive,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = item.title,
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pendingCaptureStageLabel(item.stage),
                color = if (needsAttention) ErrorWarm else InkSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    item.subject,
                    pendingCaptureOriginLabel(item.origin),
                    formatPendingTime(item.updatedAtEpochMillis),
                ).joinToString(" · "),
                color = InkMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = if (item.stage == PendingCaptureStage.TUTOR_SESSION_READY) {
                "打开待讲题目"
            } else {
                "继续处理"
            },
            tint = InkSecondary,
        )
    }
    PaperDivider(color = Outline.copy(alpha = 0.7f))
}

@Composable
private fun PendingLoading() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp)
            .testTag("pending_capture_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(30.dp), color = JadeActive)
        Text("正在读取本机题目…", color = InkSecondary)
    }
}

@Composable
private fun PendingEmpty() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 44.dp)
            .testTag("pending_capture_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("没有待处理题目", color = Ink, style = MaterialTheme.typography.titleMedium)
        Text("新拍的题会先安全保存，再出现在这里。", color = InkSecondary)
    }
}

@Composable
private fun PendingUnavailable() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp)
            .testTag("pending_capture_unavailable"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("待处理题目暂时打不开", color = Ink, style = MaterialTheme.typography.titleMedium)
        Text("题目仍保存在本机。请稍后返回重试。", color = InkSecondary)
    }
}

internal fun pendingCaptureStageLabel(stage: PendingCaptureStage): String = when (stage) {
    PendingCaptureStage.TUTOR_SESSION_READY -> "题目已保存，可以开始讲解"
    PendingCaptureStage.SOURCE_UNAVAILABLE -> "原图暂时打不开，请重新拍摄"
    PendingCaptureStage.RECAPTURE_REQUIRED -> "关键内容看不清，请重新拍摄"
    PendingCaptureStage.MODEL_WORKING -> "正在整理题目，可稍后再来"
    PendingCaptureStage.RETRY_OR_MANUAL -> "上次没有完成，点此继续"
    PendingCaptureStage.MANUAL_REVIEW_REQUIRED -> "题面还没整理完整，点此继续"
    PendingCaptureStage.READY_TO_REVIEW -> "题面已整理，可继续"
    PendingCaptureStage.READY_TO_CONTINUE -> "已保存原图，点此继续"
}

private fun pendingCaptureOriginLabel(origin: CaptureEntryOrigin): String = when (origin) {
    CaptureEntryOrigin.TUTOR -> "讲题拍题"
    CaptureEntryOrigin.LIBRARY -> "错题本录入"
}

private fun formatPendingTime(epochMillis: Long): String = SimpleDateFormat(
    "M月d日 HH:mm",
    Locale.getDefault(),
).format(Date(epochMillis))
