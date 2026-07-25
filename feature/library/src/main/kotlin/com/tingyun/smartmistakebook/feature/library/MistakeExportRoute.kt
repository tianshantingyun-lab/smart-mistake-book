package com.tingyun.smartmistakebook.feature.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.export.MistakePdfBatchEligibility
import com.tingyun.smartmistakebook.core.export.MistakePdfBatchEligibilityResult
import com.tingyun.smartmistakebook.core.export.MistakePdfBatchIneligibility
import com.tingyun.smartmistakebook.core.export.MistakePdfDeliveryIntents
import com.tingyun.smartmistakebook.core.export.MistakePdfDocumentSaver
import com.tingyun.smartmistakebook.core.export.MistakePdfEligibility
import com.tingyun.smartmistakebook.core.export.MistakePdfEligibilityResult
import com.tingyun.smartmistakebook.core.export.MistakePdfExportException
import com.tingyun.smartmistakebook.core.export.MistakePdfExportFailure
import com.tingyun.smartmistakebook.core.export.MistakePdfExporter
import com.tingyun.smartmistakebook.core.export.MistakePdfIneligibility
import com.tingyun.smartmistakebook.core.export.MistakePdfPreview
import com.tingyun.smartmistakebook.core.export.MistakePdfSaveResult
import com.tingyun.smartmistakebook.core.export.PreparedMistakePdf
import com.tingyun.smartmistakebook.core.export.PreparedMistakePdfToken
import com.tingyun.smartmistakebook.core.export.PreparedPdfPrintAdapter
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.studentSubjectLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val A4_PREVIEW_WIDTH_PX = 794
private const val A4_PREVIEW_HEIGHT_PX = 1_123
const val MAX_LIBRARY_BATCH_EXPORT_QUESTIONS = MistakePdfBatchEligibility.MAX_QUESTIONS

@Composable
fun MistakeExportRoute(
    key: MistakeRevisionKey,
    repository: MistakeDetailRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exporter = remember(context) { MistakePdfExporter(context) }
    val stateFlow: Flow<MistakeDetailState> = remember(key, repository) {
        repository.observeExact(key)
    }
    val detailState by stateFlow.collectAsStateWithLifecycle(
        initialValue = MistakeDetailState.Loading,
    )
    val preparation by produceState<MistakeExportPreparation>(
        initialValue = MistakeExportPreparation.Preparing,
        key1 = key,
        key2 = detailState,
        key3 = exporter,
    ) {
        value = withContext(Dispatchers.IO) {
            prepareMistakeExport(key, detailState, exporter)
        }
    }

    MistakeExportContent(
        preparation = preparation,
        copy = SingleExportCopy,
        onBack = onBack,
        context = context,
        exporter = exporter,
        modifier = modifier,
    )
}

@Composable
fun MistakeBatchExportRoute(
    entries: List<StudyCatalogEntry>,
    requestedCount: Int = entries.size,
    repository: MistakeDetailRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exporter = remember(context) { MistakePdfExporter(context) }
    val keys = remember(entries) {
        entries.map { entry ->
            MistakeRevisionKey(
                entryId = entry.entryId,
                problemId = entry.problemId,
                problemRevisionId = entry.problemRevisionId,
            )
        }
    }
    val preparation by produceState<MistakeExportPreparation>(
        initialValue = MistakeExportPreparation.Preparing,
        key1 = keys,
        key2 = requestedCount,
        key3 = repository,
    ) {
        value = withContext(Dispatchers.IO) {
            prepareMistakeBatchExport(keys, requestedCount, repository, exporter)
        }
    }

    MistakeExportContent(
        preparation = preparation,
        copy = BatchExportCopy,
        onBack = onBack,
        context = context,
        exporter = exporter,
        modifier = modifier,
    )
}

@Composable
fun InvalidMistakeExportRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    MistakeExportContent(
        preparation = MistakeExportPreparation.Blocked(
            "没有找到要导出的这一版题目，请返回错题详情后再试。",
        ),
        copy = SingleExportCopy,
        onBack = onBack,
        context = context,
        exporter = remember(context) { MistakePdfExporter(context) },
        modifier = modifier,
    )
}

@Composable
private fun MistakeExportContent(
    preparation: MistakeExportPreparation,
    copy: ExportCopy,
    onBack: () -> Unit,
    context: Context,
    exporter: MistakePdfExporter,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var pendingSaveTokenValue by rememberSaveable { mutableStateOf<String?>(null) }
    var actionInProgress by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<ExportActionMessage?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val token = PreparedMistakePdfToken.fromPersistedValue(pendingSaveTokenValue)
        pendingSaveTokenValue = null
        val destination = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || destination == null) {
            actionInProgress = false
            return@rememberLauncherForActivityResult
        }
        if (token == null) {
            actionMessage = ExportActionMessage(
                "保存所需的文件状态已失效，请重新打开后再保存。",
                isError = true,
            )
            actionInProgress = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val saveResult = withContext(Dispatchers.IO) {
                exporter.reopenVerified(token)?.let { prepared ->
                    MistakePdfDocumentSaver.save(context, prepared, destination)
                }
            }
            actionMessage = saveResult?.toActionMessage() ?: ExportActionMessage(
                "保存所需的文件已不可用，请重新打开后再保存。",
                isError = true,
            )
            actionInProgress = false
        }
    }

    RootPageColumn(
        modifier = modifier.testTag("mistake_export_screen"),
    ) {
        MistakeExportHeader(onBack, copy)
        Spacer(Modifier.height(12.dp))
        when (preparation) {
            MistakeExportPreparation.Preparing -> PreparingExport(copy.preparingText)
            is MistakeExportPreparation.Blocked -> BlockedExport(preparation.message, copy)
            is MistakeExportPreparation.Ready -> ReadyExport(
                preparation = preparation,
                actionInProgress = actionInProgress,
                actionMessage = actionMessage,
                onSave = {
                    actionMessage = null
                    actionInProgress = true
                    pendingSaveTokenValue = preparation.prepared.recoveryToken.toPersistedValue()
                    runCatching {
                        saveLauncher.launch(
                            MistakePdfDeliveryIntents.createDocument(preparation.displayName),
                        )
                    }.onFailure {
                        pendingSaveTokenValue = null
                        actionInProgress = false
                        actionMessage = ExportActionMessage(
                            "暂时无法打开保存位置，请稍后再试。",
                            isError = true,
                        )
                    }
                },
                onShare = {
                    actionMessage = null
                    actionInProgress = true
                    scope.launch {
                        actionMessage = runCatching {
                            val shareIntent = withContext(Dispatchers.IO) {
                                MistakePdfDeliveryIntents.share(
                                    context = context,
                                    prepared = preparation.prepared,
                                    displayName = preparation.displayName,
                                )
                            }
                            context.startActivity(Intent.createChooser(shareIntent, copy.shareTitle))
                            ExportActionMessage("已打开分享方式。", isError = false)
                        }.getOrElse {
                            ExportActionMessage("暂时无法打开分享方式，请稍后再试。", isError = true)
                        }
                        actionInProgress = false
                    }
                },
                onPrint = {
                    actionMessage = null
                    actionInProgress = true
                    actionMessage = runCatching {
                        val printManager = context.getSystemService(Context.PRINT_SERVICE)
                            as? PrintManager
                            ?: error("Print service unavailable")
                        printManager.print(
                            preparation.displayName.removeSuffix(".pdf"),
                            PreparedPdfPrintAdapter(
                                prepared = preparation.prepared,
                                displayName = preparation.displayName,
                            ),
                            PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .build(),
                        )
                        ExportActionMessage("已打开打印设置。", isError = false)
                    }.getOrElse {
                        ExportActionMessage("暂时无法打开打印设置，请稍后再试。", isError = true)
                    }
                    actionInProgress = false
                },
            )
        }
    }
}

@Composable
private fun MistakeExportHeader(
    onBack: () -> Unit,
    copy: ExportCopy,
) {
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
                .testTag("mistake_export_back"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = copy.backDescription,
                tint = Ink,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = "导出 A4",
            color = Ink,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun PreparingExport(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp)
            .testTag("mistake_export_preparing"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = JadeActive,
        )
        Text(
            text = text,
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BlockedExport(
    message: String,
    copy: ExportCopy,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mistake_export_blocked"),
        color = JadeSoft.copy(alpha = 0.38f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = copy.blockedTitle,
                color = Ink,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = copy.retentionText,
                color = JadeActive,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ReadyExport(
    preparation: MistakeExportPreparation.Ready,
    actionInProgress: Boolean,
    actionMessage: ExportActionMessage?,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
) {
    DisposableEffect(preparation.previewBitmap) {
        onDispose {
            if (!preparation.previewBitmap.isRecycled) {
                preparation.previewBitmap.recycle()
            }
        }
    }
    Column(
        modifier = Modifier.testTag("mistake_export_ready"),
    ) {
        Text(
            text = preparation.title,
            color = Ink,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = preparation.subtitle,
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        LocalModeLine(
            text = preparation.scopeText,
            icon = Icons.Outlined.PictureAsPdf,
            contentDescription = "导出内容",
        )
        PaperDivider(Modifier.padding(vertical = 18.dp))
        Text(
            text = if (preparation.prepared.pageCount == 1) {
                "A4 预览"
            } else {
                "A4 预览 · 共 ${preparation.prepared.pageCount} 页"
            },
            color = Ink,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("mistake_export_preview"),
            color = JadeSoft.copy(alpha = 0.35f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Outline),
        ) {
            Image(
                bitmap = preparation.previewBitmap.asImageBitmap(),
                contentDescription = preparation.previewContentDescription,
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth()
                    .aspectRatio(595f / 842f)
                    .background(Color.White),
                contentScale = ContentScale.Fit,
            )
        }
        Spacer(Modifier.height(18.dp))
        PrimaryActionButton(
            text = if (actionInProgress) "正在处理…" else "保存 PDF",
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("mistake_export_save"),
            icon = Icons.Outlined.SaveAlt,
            enabled = !actionInProgress,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlineActionChip(
                text = "分享",
                onClick = onShare,
                modifier = Modifier
                    .weight(1f)
                    .testTag("mistake_export_share"),
                icon = Icons.Outlined.Share,
                enabled = !actionInProgress,
            )
            OutlineActionChip(
                text = "打印",
                onClick = onPrint,
                modifier = Modifier
                    .weight(1f)
                    .testTag("mistake_export_print"),
                icon = Icons.Outlined.Print,
                enabled = !actionInProgress,
            )
        }
        actionMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = message.text,
                modifier = Modifier.testTag("mistake_export_action_message"),
                color = if (message.isError) ErrorWarm else JadeActive,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun prepareMistakeExport(
    key: MistakeRevisionKey,
    state: MistakeDetailState,
    exporter: MistakePdfExporter,
): MistakeExportPreparation {
    if (state == MistakeDetailState.Loading) return MistakeExportPreparation.Preparing
    if (!state.matchesExactKey(key)) {
        return MistakeExportPreparation.Blocked(
            "没能确认这一版题目与当前错题一致，因此没有生成文件。",
        )
    }
    return when (val eligibility = MistakePdfEligibility.check(state)) {
        is MistakePdfEligibilityResult.Ineligible -> {
            if (MistakePdfIneligibility.LOADING in eligibility.reasons) {
                MistakeExportPreparation.Preparing
            } else {
                MistakeExportPreparation.Blocked(exportBlockedMessage(eligibility.reasons))
            }
        }
        is MistakePdfEligibilityResult.Eligible -> try {
            val prepared = exporter.prepare(eligibility.input)
            check(prepared.verifyIntegrity()) { "Prepared PDF failed integrity verification" }
            val previewBitmap = MistakePdfPreview.open(prepared).use { preview ->
                check(preview.sourceFile.canonicalFile == prepared.file.canonicalFile) {
                    "Preview did not use the prepared PDF"
                }
                preview.renderPage(
                    pageIndex = 0,
                    maxWidthPx = A4_PREVIEW_WIDTH_PX,
                    maxHeightPx = A4_PREVIEW_HEIGHT_PX,
                )
            }
            val identity = (state as MistakeDetailState.Ready).detail.identity
            MistakeExportPreparation.Ready(
                title = identity.title,
                subtitle = "${identity.subject.studentSubjectLabel()} · 第 ${identity.revisionNumber} 版",
                scopeText = "只导出你刚才看到的题面",
                previewContentDescription = "这道错题的 A4 第一页预览",
                prepared = prepared,
                previewBitmap = previewBitmap,
                displayName = exportDisplayName(identity.title, identity.revisionNumber),
            )
        } catch (failure: MistakePdfExportException) {
            MistakeExportPreparation.Blocked(exportFailureMessage(failure.failure))
        } catch (_: Exception) {
            MistakeExportPreparation.Blocked(
                "这次没有整理出完整的 A4 文件，请稍后重新打开。",
            )
        }
    }
}

private suspend fun prepareMistakeBatchExport(
    keys: List<MistakeRevisionKey>,
    requestedCount: Int,
    repository: MistakeDetailRepository,
    exporter: MistakePdfExporter,
): MistakeExportPreparation {
    require(requestedCount >= keys.size) { "Requested count cannot be smaller than key count" }
    if (requestedCount > MistakePdfBatchEligibility.MAX_QUESTIONS) {
        return MistakeExportPreparation.Blocked(
            "当前结果超过 ${MistakePdfBatchEligibility.MAX_QUESTIONS} 道。按科目、板块、知识点或掌握程度筛选后，就能直接导出。",
        )
    }
    val states = try {
        val readStates = repository.readExact(keys)
        check(readStates.size == keys.size) { "Batch detail read changed result cardinality" }
        readStates.mapIndexed { index, state ->
            val key = keys[index]
            state.takeIf { it.matchesExactKey(key) }
                ?: MistakeDetailState.NotFound
        }
    } catch (_: Exception) {
        return MistakeExportPreparation.Blocked(
            "这次没有读完整当前列表，请稍后重新打开。",
        )
    }
    return when (val eligibility = MistakePdfBatchEligibility.check(states)) {
        is MistakePdfBatchEligibilityResult.Ineligible -> MistakeExportPreparation.Blocked(
            batchBlockedMessage(eligibility.reason),
        )
        is MistakePdfBatchEligibilityResult.Eligible -> try {
            val prepared = exporter.prepare(eligibility.input)
            check(prepared.verifyIntegrity()) { "Prepared batch PDF failed integrity verification" }
            val previewBitmap = MistakePdfPreview.open(prepared).use { preview ->
                check(preview.sourceFile.canonicalFile == prepared.file.canonicalFile) {
                    "Preview did not use the prepared batch PDF"
                }
                preview.renderPage(
                    pageIndex = 0,
                    maxWidthPx = A4_PREVIEW_WIDTH_PX,
                    maxHeightPx = A4_PREVIEW_HEIGHT_PX,
                )
            }
            val omittedText = eligibility.omittedCount.takeIf { it > 0 }?.let { count ->
                " · 自动略过 $count 道尚未整理完整的题"
            }.orEmpty()
            MistakeExportPreparation.Ready(
                title = "错题练习",
                subtitle = "当前筛选 · 已整理 ${eligibility.includedCount} 道",
                scopeText = "只包含当前筛选结果$omittedText",
                previewContentDescription = "当前错题练习的 A4 第一页预览",
                prepared = prepared,
                previewBitmap = previewBitmap,
                displayName = "错题练习-${eligibility.includedCount}道.pdf",
            )
        } catch (failure: MistakePdfExportException) {
            MistakeExportPreparation.Blocked(batchExportFailureMessage(failure.failure))
        } catch (_: Exception) {
            MistakeExportPreparation.Blocked(
                "这次没有整理出完整的 A4 文件，请稍后重新打开。",
            )
        }
    }
}

internal fun MistakeDetailState.matchesExactKey(key: MistakeRevisionKey): Boolean = when (this) {
    MistakeDetailState.Loading -> true
    MistakeDetailState.NotFound -> true
    is MistakeDetailState.Ready -> detail.identity.matches(key)
    is MistakeDetailState.Legacy -> detail.identity.matches(key)
    is MistakeDetailState.CorruptSnapshot -> identity.matches(key)
}

private fun MistakeDetailIdentity.matches(key: MistakeRevisionKey): Boolean =
    errorBookEntryId == key.entryId &&
        problemId == key.problemId &&
        problemRevisionId == key.problemRevisionId

internal fun exportBlockedMessage(reasons: Set<MistakePdfIneligibility>): String = when {
    MistakePdfIneligibility.UNSUPPORTED_BLOCK in reasons ->
        "题面里有暂时不能稳定排版的图形。为避免导出失真的题目，这一版先不生成文件。"
    MistakePdfIneligibility.LEGACY_CONTENT in reasons ->
        "这是一条旧格式记录，题面不完整，暂时无法导出。"
    MistakePdfIneligibility.CORRUPT_CONTENT in reasons ->
        "这版题面没有通过完整性检查，因此不会用备用文字拼出一份可能错误的文件。"
    MistakePdfIneligibility.NOT_FOUND in reasons ->
        "没有找到这一版题目，它可能已经不在当前错题本中。"
    MistakePdfIneligibility.UNCONFIRMED_OR_INVALID_DOCUMENT in reasons ->
        "这版题面还不完整，暂时无法导出。"
    MistakePdfIneligibility.EMPTY_SUPPORTED_BLOCK in reasons ->
        "这版题面有空白内容，暂时无法生成完整的练习页。"
    MistakePdfIneligibility.INVALID_CHOICE_STATE in reasons ->
        "这版选择题的选项状态不完整，暂时无法可靠排版。"
    else -> "这版题面还没有准备好，请稍后重新打开。"
}

private fun exportFailureMessage(failure: MistakePdfExportFailure): String = when (failure) {
    MistakePdfExportFailure.PAGE_LIMIT_EXCEEDED,
    MistakePdfExportFailure.PDF_SIZE_LIMIT_EXCEEDED,
    -> "这道题的内容超出了单题 A4 导出的范围，题目仍会原样保留。"
    MistakePdfExportFailure.EXISTING_FILE_INTEGRITY_CONFLICT,
    MistakePdfExportFailure.GENERATED_PDF_INVALID,
    MistakePdfExportFailure.FILE_PUBLISH_FAILED,
    -> "这次没有整理出完整的 A4 文件，请稍后重新打开。"
}

private fun batchBlockedMessage(reason: MistakePdfBatchIneligibility): String = when (reason) {
    MistakePdfBatchIneligibility.EMPTY -> "当前没有可导出的错题。"
    MistakePdfBatchIneligibility.TOO_MANY_QUESTIONS ->
        "当前结果较多。按科目、板块、知识点或掌握程度筛选后，就能直接导出。"
    MistakePdfBatchIneligibility.NO_READY_QUESTION ->
        "当前这些题的题面还没有整理完整，暂时不能生成练习文件。"
}

private fun batchExportFailureMessage(failure: MistakePdfExportFailure): String = when (failure) {
    MistakePdfExportFailure.PAGE_LIMIT_EXCEEDED,
    MistakePdfExportFailure.PDF_SIZE_LIMIT_EXCEEDED,
    -> "当前结果的页数较多。缩小筛选范围后再导出，错题内容不会改变。"
    MistakePdfExportFailure.EXISTING_FILE_INTEGRITY_CONFLICT,
    MistakePdfExportFailure.GENERATED_PDF_INVALID,
    MistakePdfExportFailure.FILE_PUBLISH_FAILED,
    -> "这次没有整理出完整的 A4 文件，请稍后重新打开。"
}

internal fun exportDisplayName(title: String, revisionNumber: Int): String {
    val safeTitle = title
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(48)
        .ifBlank { "未命名错题" }
    return "错题-$safeTitle-第${revisionNumber}版.pdf"
}

private fun MistakePdfSaveResult.toActionMessage(): ExportActionMessage = when (this) {
    MistakePdfSaveResult.SAVED -> ExportActionMessage(
        text = "PDF 已保存，并已重新核对完整。",
        isError = false,
    )
    MistakePdfSaveResult.INVALID_PREPARED_FILE -> ExportActionMessage(
        text = "文件状态已经变化，请重新打开后再保存。",
        isError = true,
    )
    MistakePdfSaveResult.DESTINATION_UNAVAILABLE -> ExportActionMessage(
        text = "没有完成保存，请换一个位置后再试。",
        isError = true,
    )
    MistakePdfSaveResult.DESTINATION_INTEGRITY_MISMATCH -> ExportActionMessage(
        text = "保存后的文件没有通过核对，请换一个位置后再试。",
        isError = true,
    )
}

private sealed interface MistakeExportPreparation {
    data object Preparing : MistakeExportPreparation

    data class Blocked(val message: String) : MistakeExportPreparation

    data class Ready(
        val title: String,
        val subtitle: String,
        val scopeText: String,
        val previewContentDescription: String,
        val prepared: PreparedMistakePdf,
        val previewBitmap: Bitmap,
        val displayName: String,
    ) : MistakeExportPreparation
}

private data class ExportCopy(
    val backDescription: String,
    val preparingText: String,
    val blockedTitle: String,
    val retentionText: String,
    val shareTitle: String,
)

private val SingleExportCopy = ExportCopy(
    backDescription = "返回错题详情",
    preparingText = "正在整理这道题的 A4 版式…",
    blockedTitle = "这道题暂时不能导出",
    retentionText = "题目仍保留在错题本中。",
    shareTitle = "分享这道错题",
)

private val BatchExportCopy = ExportCopy(
    backDescription = "返回错题本",
    preparingText = "正在整理当前列表的 A4 版式…",
    blockedTitle = "当前列表暂时不能导出",
    retentionText = "错题仍保留在错题本中。",
    shareTitle = "分享错题练习",
)

private data class ExportActionMessage(
    val text: String,
    val isError: Boolean,
)
