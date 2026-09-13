package com.tingyun.smartmistakebook.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.domain.BatchImportJob
import com.tingyun.smartmistakebook.core.domain.BatchImportPage
import com.tingyun.smartmistakebook.core.domain.BatchImportPageStatus
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.BatchImportStatus
import com.tingyun.smartmistakebook.core.domain.BatchOrganizationUnavailableException
import com.tingyun.smartmistakebook.core.domain.CreateBatchImportRequest
import com.tingyun.smartmistakebook.core.domain.CreatePdfImportRequest
import com.tingyun.smartmistakebook.core.domain.MAX_BATCH_IMPORT_PAGES
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageLazyColumn
import com.tingyun.smartmistakebook.core.ui.SmartColors
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BatchImportRoute(
    repository: BatchImportRepository,
    onOpenDraft: (String) -> Unit,
    onSplitReady: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val jobs by repository.observeBatchImports().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var isOrganizing by remember { mutableStateOf(false) }
    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "操作暂时没有完成，请稍后重试。"
            }
        }
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH_IMPORT_PAGES),
    ) { selected ->
        if (selected.size < 2) {
            message = "请至少选择 2 张试卷照片；单张题目仍用普通录入。"
            return@rememberLauncherForActivityResult
        }
        message = null
        scope.launch {
            try {
                repository.createBatchImport(
                    CreateBatchImportRequest(
                        requestId = UUID.randomUUID().toString(),
                        localUris = selected.map { it.toString() },
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "这组照片暂时没有保存下来，请重新选择。"
            }
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { selected ->
        selected ?: return@rememberLauncherForActivityResult
        message = null
        scope.launch {
            try {
                repository.createPdfImport(
                    CreatePdfImportRequest(
                        requestId = UUID.randomUUID().toString(),
                        localUri = selected.toString(),
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "这份 PDF 暂时没有导入，请确认文件可以正常打开。"
            }
        }
    }
    val latestJob = jobs.firstOrNull()
    BatchImportContent(
        job = latestJob,
        message = message,
        onChoosePhotos = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onChoosePdf = { pdfPicker.launch(arrayOf("application/pdf")) },
        onPause = { jobId -> launchAction { repository.pauseBatchImport(jobId) } },
        onResume = { jobId -> launchAction { repository.resumeBatchImport(jobId) } },
        onRetry = { jobId, page ->
            launchAction { repository.retryBatchImportPage(jobId, page) }
        },
        onSkip = { jobId, page ->
            launchAction { repository.skipBatchImportPage(jobId, page) }
        },
        onSplitReady = onSplitReady,
        isOrganizing = isOrganizing,
        onOrganize = { jobId ->
            scope.launch {
                isOrganizing = true
                try {
                    repository.organizeBatch(jobId)
                    message = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: BatchOrganizationUnavailableException) {
                    message = "请先在“我的”里配置模型服务，再整理相邻页面。"
                } catch (_: Exception) {
                    message = "这次还没有全部分好，页面都已保留，可以稍后继续。"
                } finally {
                    isOrganizing = false
                }
            }
        },
        onOpenDraft = onOpenDraft,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun BatchImportContent(
    job: BatchImportJob?,
    message: String?,
    onChoosePhotos: () -> Unit,
    onChoosePdf: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String, Int) -> Unit,
    onSkip: (String, Int) -> Unit,
    onSplitReady: () -> Unit = {},
    isOrganizing: Boolean = false,
    onOrganize: (String) -> Unit = {},
    onOpenDraft: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RootPageLazyColumn(
        modifier = modifier.testTag("batch_import_root"),
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 0.dp),
    ) {
        item("header") {
            BatchImportHeader(onBack)
            Text(
                text = "选择多张照片，或导入一份 PDF。系统按页保存，某一页失败不会影响其他页。",
                color = SmartColors.InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            PrimaryActionButton(
                text = when {
                    job == null -> "选择多张照片"
                    job.status == BatchImportStatus.COMPLETED && job.failedCount == 0 ->
                        "再选一组照片"
                    else -> "完成当前这组后再选择"
                },
                onClick = onChoosePhotos,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("batch_import_choose"),
                icon = Icons.Outlined.ImageSearch,
                enabled = job == null ||
                    (job.status == BatchImportStatus.COMPLETED && job.failedCount == 0),
            )
            Spacer(Modifier.height(8.dp))
            OutlineActionChip(
                text = when {
                    job == null -> "导入整份 PDF"
                    job.status == BatchImportStatus.COMPLETED && job.failedCount == 0 ->
                        "再导入一份 PDF"
                    else -> "完成当前任务后再导入 PDF"
                },
                onClick = onChoosePdf,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("batch_import_choose_pdf"),
                icon = Icons.Outlined.PictureAsPdf,
                enabled = job == null ||
                    (job.status == BatchImportStatus.COMPLETED && job.failedCount == 0),
            )
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = SmartColors.ErrorWarm, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
        }
        if (job == null) {
            item("empty") { BatchImportEmpty() }
        } else {
            item("summary") {
                BatchImportSummary(
                    job = job,
                    onPause = { onPause(job.jobId) },
                    onResume = { onResume(job.jobId) },
                )
                Spacer(Modifier.height(12.dp))
            }
            if (
                job.status == BatchImportStatus.COMPLETED &&
                job.failedCount == 0 &&
                job.remainingBoundaryCount > 0
            ) {
                item("organization") {
                    BatchOrganizationCard(
                        job = job,
                        isOrganizing = isOrganizing,
                        onOrganize = { onOrganize(job.jobId) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            job.pages.forEachIndexed { index, page ->
                item("page_${page.pageIndex}") {
                    BatchImportPageRow(
                        page = page,
                        continuesPrevious = index > 0 &&
                            job.pages[index - 1].draftId != null &&
                            job.pages[index - 1].draftId == page.draftId,
                        onRetry = { onRetry(job.jobId, page.pageIndex) },
                        onSkip = { onSkip(job.jobId, page.pageIndex) },
                        onOpenDraft = onOpenDraft,
                    )
                }
            }
        }
        item("footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BatchOrganizationCard(
    job: BatchImportJob,
    isOrganizing: Boolean,
    onOrganize: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("batch_import_organization"),
        color = SmartColors.Paper,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SmartColors.Outline),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isOrganizing) "正在分好每一道题" else "自动分好题目",
                color = SmartColors.Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    isOrganizing -> "正在按页面顺序整理，可以离开本页，原页面不会丢失。"
                    else ->
                        "自动识别跨页题目，之后会按一道道题显示，不需要手工合并。" +
                            "整理会直接交给已配置模型，不再逐次询问。"
                },
                color = SmartColors.InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                isOrganizing -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = SmartColors.Jade,
                    trackColor = SmartColors.Outline.copy(alpha = 0.6f),
                )
                else -> PrimaryActionButton(
                    text = "开始分题",
                    onClick = onOrganize,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }
}

@Composable
private fun BatchImportHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
        }
        Spacer(Modifier.size(4.dp))
        Text("整卷导入", style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun BatchImportSummary(
    job: BatchImportJob,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val completed = batchImportCompletedCount(job)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("batch_import_summary"),
        color = SmartColors.JadeSoft.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SmartColors.Outline),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = batchImportTitle(job),
                        color = SmartColors.Ink,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = batchImportSummaryLine(job),
                        color = SmartColors.InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                when (job.status) {
                    BatchImportStatus.PROCESSING -> OutlineActionChip("暂停", onPause)
                    BatchImportStatus.PAUSED -> OutlineActionChip("继续", onResume)
                    BatchImportStatus.COMPLETED -> Unit
                }
            }
            LinearProgressIndicator(
                progress = { completed.toFloat() / job.pages.size },
                modifier = Modifier.fillMaxWidth(),
                color = SmartColors.Jade,
                trackColor = SmartColors.Outline.copy(alpha = 0.6f),
            )
            Text(
                text = "可以离开本页；已保存的题会继续留在待处理题目中。",
                color = SmartColors.InkSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun BatchImportPageRow(
    page: BatchImportPage,
    continuesPrevious: Boolean,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onOpenDraft: (String) -> Unit,
) {
    val readyDraft = page.draftId
    val clickable = readyDraft != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 68.dp)
            .clickable(enabled = clickable) { readyDraft?.let(onOpenDraft) }
            .padding(horizontal = 6.dp, vertical = 10.dp)
            .testTag("batch_import_page_${page.pageIndex}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (page.status) {
                BatchImportPageStatus.READY -> Icons.Outlined.CheckCircle
                BatchImportPageStatus.FAILED -> Icons.Outlined.ErrorOutline
                BatchImportPageStatus.SKIPPED -> Icons.Outlined.CheckCircle
                else -> Icons.Outlined.HourglassTop
            },
            contentDescription = null,
            tint = if (page.status == BatchImportPageStatus.FAILED) {
                SmartColors.ErrorWarm
            } else {
                SmartColors.Jade
            },
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = if (continuesPrevious) {
                    "第 ${page.pageIndex + 1} 页 · 接上页"
                } else {
                    "第 ${page.pageIndex + 1} 页"
                },
                style = MaterialTheme.typography.titleSmall,
                color = SmartColors.Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (continuesPrevious) {
                    "已和上一页放在同一道题里"
                } else {
                    batchImportPageLabel(page.status)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (page.status == BatchImportPageStatus.FAILED) {
                    SmartColors.ErrorWarm
                } else {
                    SmartColors.InkSecondary
                },
            )
        }
        when {
            page.status == BatchImportPageStatus.FAILED -> Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlineActionChip("跳过", onSkip)
                OutlineActionChip("重试", onRetry)
            }
            readyDraft != null -> Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "打开这道题",
                tint = SmartColors.InkSecondary,
            )
        }
    }
    PaperDivider(color = SmartColors.Outline.copy(alpha = 0.7f))
}

@Composable
private fun BatchImportEmpty() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("还没有批量导入", style = MaterialTheme.typography.titleMedium)
        Text("可选择多张照片，也可直接导入 PDF。", color = SmartColors.InkSecondary)
    }
}

internal fun batchImportTitle(job: BatchImportJob): String = when (job.status) {
    BatchImportStatus.PROCESSING -> "正在保存 ${job.pages.size} 页"
    BatchImportStatus.PAUSED -> "已暂停，进度保留在本机"
    BatchImportStatus.COMPLETED -> if (job.failedCount == 0) {
        if (job.remainingBoundaryCount == 0) {
            "${job.questionCount} 道题已分好"
        } else {
            "${job.pages.size} 页已保存"
        }
    } else {
        "其余页面已保存"
    }
}

internal fun batchImportPageLabel(status: BatchImportPageStatus): String = when (status) {
    BatchImportPageStatus.QUEUED -> "准备中"
    BatchImportPageStatus.IMPORTING -> "正在保存"
    BatchImportPageStatus.READY -> "已保存，点此继续"
    BatchImportPageStatus.FAILED -> "这一页没有保存"
    BatchImportPageStatus.SKIPPED -> "已跳过"
}

internal fun batchImportSummaryLine(job: BatchImportJob): String = buildList {
    add("已保存 ${job.readyCount} 页")
    if (job.skippedCount > 0) add("已跳过 ${job.skippedCount} 页")
    add("需重试 ${job.failedCount} 页")
    add("剩余 ${job.remainingCount} 页")
}.joinToString(" · ")

internal fun batchImportCompletedCount(job: BatchImportJob): Int =
    job.readyCount + job.failedCount + job.skippedCount
