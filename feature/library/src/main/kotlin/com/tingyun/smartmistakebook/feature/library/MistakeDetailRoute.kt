package com.tingyun.smartmistakebook.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionSummary
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.MistakeSourceAsset
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.ui.BoundedLocalImage
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.LocalImageLoadState
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.StructuredContentRenderer
import com.tingyun.smartmistakebook.core.ui.studentSubjectLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MistakeDetailRoute(
    errorBookEntryId: String,
    repository: MistakeDetailRepository,
    organizationRepository: MistakeOrganizationRepository,
    modelTasks: ScopedModelTaskPort,
    profile: StudyProfileOverview? = null,
    catalogEntries: List<StudyCatalogEntry>,
    onBack: () -> Unit,
    onExport: (MistakeRevisionKey) -> Unit,
    onTutor: (MistakeRevisionKey) -> Unit,
    onOpenRelatedMistake: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
    modifier: Modifier = Modifier,
    historicalRevisionsEnabled: Boolean = true,
) {
    var selectedRevisionId by rememberSaveable(errorBookEntryId) {
        mutableStateOf<String?>(null)
    }
    val revisionHistoryFlow = remember(
        errorBookEntryId,
        repository,
        historicalRevisionsEnabled,
    ) {
        if (errorBookEntryId.isBlank() || !historicalRevisionsEnabled) {
            flowOf(emptyList())
        } else {
            repository.observeRevisionHistory(errorBookEntryId)
        }
    }
    val revisionHistory by revisionHistoryFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val selectedRevision =
        revisionHistory
            .takeIf { historicalRevisionsEnabled }
            ?.firstOrNull { summary ->
                summary.problemRevisionId == selectedRevisionId && !summary.isCurrent
            }
    val stateFlow: Flow<MistakeDetailState> = remember(
        errorBookEntryId,
        repository,
        selectedRevision,
    ) {
        if (errorBookEntryId.isBlank()) {
            flowOf(MistakeDetailState.NotFound)
        } else if (selectedRevision != null) {
            repository.observeExact(selectedRevision.toKey())
        } else {
            repository.observe(errorBookEntryId)
        }
    }
    val state by stateFlow.collectAsStateWithLifecycle(
        initialValue = MistakeDetailState.Loading,
    )
    MistakeDetailContent(
        state = state,
        onBack = onBack,
        onExport = onExport,
        onTutor = onTutor,
        organizationRepository = organizationRepository,
        modelTasks = modelTasks,
        profile = profile,
        catalogEntries = catalogEntries,
        revisionHistory = revisionHistory,
        selectedRevisionId = selectedRevision?.problemRevisionId,
        onSelectRevision = { summary ->
            if (historicalRevisionsEnabled) {
                selectedRevisionId = summary.problemRevisionId.takeUnless { summary.isCurrent }
            }
        },
        onOpenRelatedMistake = onOpenRelatedMistake,
        onOpenModelSettings = onOpenModelSettings,
        modifier = modifier,
    )
}

@Composable
internal fun MistakeDetailContent(
    state: MistakeDetailState,
    onBack: () -> Unit,
    onExport: (MistakeRevisionKey) -> Unit,
    onTutor: (MistakeRevisionKey) -> Unit = {},
    organizationRepository: MistakeOrganizationRepository? = null,
    modelTasks: ScopedModelTaskPort? = null,
    profile: StudyProfileOverview? = null,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    revisionHistory: List<MistakeRevisionSummary> = emptyList(),
    selectedRevisionId: String? = null,
    onSelectRevision: (MistakeRevisionSummary) -> Unit = {},
    onOpenRelatedMistake: (String) -> Unit = {},
    onOpenModelSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    RootPageColumn(
        modifier = modifier.testTag("mistake_detail_screen"),
    ) {
        MistakeDetailHeader(onBack)
        Spacer(Modifier.height(12.dp))
        if (
            selectedRevisionId != null &&
            state !is MistakeDetailState.Ready &&
            state != MistakeDetailState.Loading
        ) {
            HistoricalRevisionRecovery(
                current = revisionHistory.firstOrNull(MistakeRevisionSummary::isCurrent),
                onSelectRevision = onSelectRevision,
            )
            Spacer(Modifier.height(12.dp))
        }
        when (state) {
            MistakeDetailState.Loading -> LoadingDetail()
            is MistakeDetailState.Ready -> ReadyDetail(
                state = state,
                onExport = { onExport(checkNotNull(state.exportRevisionKeyOrNull())) },
                onTutor = { onTutor(checkNotNull(state.exportRevisionKeyOrNull())) },
                organizationRepository = organizationRepository,
                modelTasks = modelTasks,
                profile = profile,
                catalogEntries = catalogEntries,
                revisionHistory = revisionHistory,
                isViewingHistoricalRevision = selectedRevisionId != null,
                onSelectRevision = onSelectRevision,
                onOpenRelatedMistake = onOpenRelatedMistake,
                onOpenModelSettings = onOpenModelSettings,
            )
            is MistakeDetailState.Legacy -> LegacyDetail(state.detail)
            is MistakeDetailState.CorruptSnapshot -> CorruptDetail(state.identity)
            MistakeDetailState.NotFound -> NotFoundDetail()
        }
    }
}

@Composable
private fun HistoricalRevisionRecovery(
    current: MistakeRevisionSummary?,
    onSelectRevision: (MistakeRevisionSummary) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mistake_detail_historical_recovery"),
        shape = RoundedCornerShape(10.dp),
        color = JadeSoft.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "这个历史题面无法完整读取，当前版本没有受到影响。",
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (current != null) {
                PrimaryActionButton(
                    text = "回到当前第 ${current.revisionNumber} 版",
                    onClick = { onSelectRevision(current) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mistake_detail_recover_current_revision"),
                    contentDescription = "退出无法读取的历史题面并回到当前版本",
                )
            }
        }
    }
}

@Composable
private fun MistakeDetailHeader(onBack: () -> Unit) {
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
                .testTag("mistake_detail_back"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = Ink,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = "错题详情",
            color = Ink,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun LoadingDetail() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp)
            .testTag("mistake_detail_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = JadeActive,
        )
        Text(
            text = "正在读取本机题目…",
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReadyDetail(
    state: MistakeDetailState.Ready,
    onExport: () -> Unit,
    onTutor: () -> Unit,
    organizationRepository: MistakeOrganizationRepository?,
    modelTasks: ScopedModelTaskPort?,
    profile: StudyProfileOverview?,
    catalogEntries: List<StudyCatalogEntry>,
    revisionHistory: List<MistakeRevisionSummary>,
    isViewingHistoricalRevision: Boolean,
    onSelectRevision: (MistakeRevisionSummary) -> Unit,
    onOpenRelatedMistake: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag("mistake_detail_ready"),
    ) {
        IdentityBlock(
            identity = state.detail.identity,
            status = if (isViewingHistoricalRevision) "历史题面 · 只读" else "当前题面",
        )
        RevisionHistorySection(
            history = revisionHistory,
            viewedRevisionId = state.detail.identity.problemRevisionId,
            onSelectRevision = onSelectRevision,
        )
        Spacer(Modifier.height(12.dp))
        if (isViewingHistoricalRevision) {
            val current = revisionHistory.firstOrNull(MistakeRevisionSummary::isCurrent)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mistake_detail_historical_notice"),
                shape = RoundedCornerShape(10.dp),
                color = JadeSoft.copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "你正在查看旧题面。讲题、复习和重新整理只使用当前版本，避免把过期内容写进学习记录。",
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (current != null) {
                Spacer(Modifier.height(10.dp))
                PrimaryActionButton(
                    text = "回到当前第 ${current.revisionNumber} 版",
                    onClick = { onSelectRevision(current) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mistake_detail_return_current_revision"),
                    contentDescription = "回到当前题面",
                )
            }
        } else {
            PrimaryActionButton(
                text = "讲解这道题",
                onClick = onTutor,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mistake_detail_start_tutor"),
                icon = Icons.AutoMirrored.Outlined.Chat,
                contentDescription = "继续或开始讲解这道错题",
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlineActionChip(
            text = if (isViewingHistoricalRevision) "导出这一版 A4" else "导出 A4",
            onClick = onExport,
            modifier = Modifier.testTag("mistake_detail_export_a4"),
            icon = Icons.Outlined.PictureAsPdf,
        )
        SourceSection(
            source = state.detail.source,
            revisionId = state.detail.identity.problemRevisionId,
        )
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader("题面")
        Spacer(Modifier.height(12.dp))
        StructuredContentRenderer(
            document = state.questionDocument.document,
            choicesEnabled = false,
        )
        if (!isViewingHistoricalRevision && organizationRepository != null && modelTasks != null) {
            MistakeOrganizationSection(
                key = checkNotNull(state.exportRevisionKeyOrNull()),
                organizationRepository = organizationRepository,
                modelTasks = modelTasks,
                profile = profile,
                catalogEntries = catalogEntries,
                onOpenRelatedMistake = onOpenRelatedMistake,
                onOpenModelSettings = onOpenModelSettings,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

internal fun MistakeDetailState.exportRevisionKeyOrNull(): MistakeRevisionKey? =
    (this as? MistakeDetailState.Ready)?.detail?.identity?.let { identity ->
        MistakeRevisionKey(
            entryId = identity.errorBookEntryId,
            problemId = identity.problemId,
            problemRevisionId = identity.problemRevisionId,
        )
    }

@Composable
private fun LegacyDetail(detail: MistakeDetail) {
    Column(
        modifier = Modifier.testTag("mistake_detail_legacy"),
    ) {
        IdentityBlock(detail.identity, status = "早期保存的题目")
        SourceSection(
            source = detail.source,
            revisionId = detail.identity.problemRevisionId,
        )
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader("题面文本")
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("mistake_detail_legacy_notice"),
            color = JadeSoft.copy(alpha = 0.45f),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "较早保存的题面",
                    color = Ink,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "这道题没有可用原图，因此暂不支持导出；已保存的题面仍可查看。",
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        SafeMarkdownText(
            markdown = detail.fallbackMarkdown,
            color = Ink,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CorruptDetail(identity: MistakeDetailIdentity) {
    Column(
        modifier = Modifier.testTag("mistake_detail_corrupt"),
    ) {
        IdentityBlock(identity, status = "题面暂时无法读取")
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader("题面状态")
        Spacer(Modifier.height(12.dp))
        Text(
            text = "题面暂时无法显示",
            color = Ink,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "这份题面内容不完整，暂时不能显示；本机原图和记录仍保留。",
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NotFoundDetail() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .testTag("mistake_detail_not_found"),
    ) {
        Text(
            text = "未找到这道题",
            color = Ink,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "这条记录可能已删除，或已不在当前错题本中。",
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun IdentityBlock(
    identity: MistakeDetailIdentity,
    status: String,
) {
    Text(
        text = identity.title,
        color = Ink,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "${identity.subject.studentSubjectLabel()} · 第 ${identity.revisionNumber} 版 · $status",
        color = InkSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun RevisionHistorySection(
    history: List<MistakeRevisionSummary>,
    viewedRevisionId: String,
    onSelectRevision: (MistakeRevisionSummary) -> Unit,
) {
    if (history.isEmpty()) return
    val ordered = remember(history) { history.sortedByDescending(MistakeRevisionSummary::revisionNumber) }
    val viewed = ordered.firstOrNull { it.problemRevisionId == viewedRevisionId } ?: return
    var expanded by rememberSaveable(viewedRevisionId, ordered.size) { mutableStateOf(false) }

    Spacer(Modifier.height(18.dp))
    SectionHeader(
        title = "题面版本",
        action = if (ordered.size > 1) {
            {
                OutlineActionChip(
                    text = if (expanded) "收起" else "查看 ${ordered.size} 个版本",
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("mistake_revision_history_toggle"),
                    selected = expanded,
                )
            }
        } else {
            null
        },
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .testTag("mistake_revision_summary"),
        shape = RoundedCornerShape(10.dp),
        color = JadeSoft.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "正在查看第 ${viewed.revisionNumber} 版${if (viewed.isCurrent) " · 当前" else " · 历史只读"}",
                color = Ink,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (ordered.size == 1) {
                    "当前只有 1 个已保存题面版本"
                } else {
                    "共保留 ${ordered.size} 个已保存版本，旧版不会被后续修改覆盖"
                },
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("mistake_revision_history_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ordered.forEach { revision ->
                val selected = revision.problemRevisionId == viewedRevisionId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mistake_revision_${revision.revisionNumber}")
                        .then(
                            if (selected) Modifier else Modifier.clickable {
                                onSelectRevision(revision)
                            },
                        ),
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) JadeSoft else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "第 ${revision.revisionNumber} 版${if (revision.isCurrent) " · 当前" else ""}",
                            color = if (revision.isCurrent) JadeActive else Ink,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = revision.title,
                            color = Ink,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = formatRevisionTime(revision.createdAtEpochMillis),
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun formatRevisionTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "保存时间未记录"
    return SimpleDateFormat(REVISION_TIME_PATTERN, Locale.getDefault())
        .format(Date(epochMillis))
}

private const val REVISION_TIME_PATTERN = "yyyy年M月d日 HH:mm"

@Composable
private fun SourceSection(
    source: MistakeSourceSet,
    revisionId: String,
) {
    val assets = source.displayAssets()
    if (assets.isEmpty()) return

    var expanded by rememberSaveable(revisionId) { mutableStateOf(false) }
    Spacer(Modifier.height(18.dp))
    SectionHeader(
        title = "原图",
        action = {
            OutlineActionChip(
                text = if (expanded) "收起原图" else sourceToggleLabel(assets.size),
                onClick = { expanded = !expanded },
                modifier = Modifier.testTag("mistake_detail_source_toggle"),
                icon = Icons.Outlined.Image,
                selected = expanded,
            )
        },
    )
    if (expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .testTag("mistake_detail_source_content"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            assets.forEachIndexed { index, asset ->
                SourceAssetPreview(
                    asset = asset,
                    position = index + 1,
                    total = assets.size,
                )
            }
        }
    }
}

@Composable
private fun SourceAssetPreview(
    asset: MistakeSourceAsset,
    position: Int,
    total: Int,
) {
    val availableLocation = asset.location as? MistakeSourceLocation.Available
    val imageUri = availableLocation?.localUri
    var imageExpanded by rememberSaveable(asset.sourceAssetId) { mutableStateOf(false) }
    var loadState by remember(imageUri) {
        mutableStateOf(
            if (imageUri == null) LocalImageLoadState.UNAVAILABLE else LocalImageLoadState.LOADING,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Outline, RoundedCornerShape(8.dp))
            .background(JadeSoft.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .clickable(enabled = loadState == LocalImageLoadState.AVAILABLE) {
                imageExpanded = !imageExpanded
            }
            .padding(12.dp)
            .testTag("mistake_detail_source_asset_$position"),
    ) {
        Text(
            text = if (total == 1) "原图" else "原图 $position / $total",
            color = Ink,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (imageUri != null) {
            BoundedLocalImage(
                imageUri = imageUri,
                contentDescription = if (total == 1) "错题原图" else "错题原图 $position",
                expanded = imageExpanded,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                onLoadStateChange = { loadState = it },
            )
        }
        Text(
            text = sourceStatusText(
                hasLocalLocation = imageUri != null,
                loadState = loadState,
                expanded = imageExpanded,
            ),
            modifier = Modifier.padding(top = 8.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun MistakeSourceSet.displayAssets(): List<MistakeSourceAsset> = when (this) {
    MistakeSourceSet.Missing -> emptyList()
    is MistakeSourceSet.Present -> assets
}

internal fun sourceStatusText(
    hasLocalLocation: Boolean,
    loadState: LocalImageLoadState,
    expanded: Boolean,
): String = when {
    !hasLocalLocation -> "原图记录存在，但本机文件当前不可用。"
    loadState == LocalImageLoadState.LOADING -> "正在打开本机原图…"
    loadState == LocalImageLoadState.UNAVAILABLE -> "原图文件暂时打不开。"
    expanded -> "点击图片区域收起"
    else -> "点击图片区域放大查看"
}

private fun sourceToggleLabel(assetCount: Int): String = if (assetCount == 1) {
    "查看原图"
} else {
    "查看原图（$assetCount）"
}
