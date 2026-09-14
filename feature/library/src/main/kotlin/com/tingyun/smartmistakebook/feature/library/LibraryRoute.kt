package com.tingyun.smartmistakebook.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import com.tingyun.smartmistakebook.core.domain.ArchivedMistakeRef
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.LibraryCatalogRepository
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageLazyColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SmartColors
import com.tingyun.smartmistakebook.core.ui.SubjectIcon
import kotlinx.coroutines.launch

@Composable
fun LibraryRoute(
    entries: List<StudyCatalogEntry>,
    catalogRepository: LibraryCatalogRepository? = null,
    mistakeDetailRepository: MistakeDetailRepository? = null,
    onCapture: () -> Unit,
    onBatchImport: () -> Unit,
    onExportVisible: (List<String>) -> Unit,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val libraryViewModel: LibraryViewModel = viewModel()
    LaunchedEffect(catalogRepository) {
        if (catalogRepository != null) {
            libraryViewModel.bindRepository(catalogRepository)
        } else {
            libraryViewModel.updateCatalog(entries.map(StudyCatalogEntry::toLibraryMistake))
        }
    }
    LibraryContent(
        mistakeCount = if (catalogRepository != null) {
            libraryViewModel.uiState.totalCount
        } else {
            entries.size
        },
        usePaging = catalogRepository != null,
        mistakeDetailRepository = mistakeDetailRepository,
        onCapture = onCapture,
        onBatchImport = onBatchImport,
        onExportVisible = onExportVisible,
        onOpenItem = onOpenItem,
        viewModel = libraryViewModel,
        modifier = modifier,
    )
}

@Composable
private fun LibraryContent(
    mistakeCount: Int,
    usePaging: Boolean,
    mistakeDetailRepository: MistakeDetailRepository?,
    onCapture: () -> Unit,
    onBatchImport: () -> Unit,
    onExportVisible: (List<String>) -> Unit,
    onOpenItem: (String) -> Unit,
    viewModel: LibraryViewModel,
    modifier: Modifier,
) {
    val uiState = viewModel.uiState
    val scope = rememberCoroutineScope()
    val pagingItems = if (usePaging) viewModel.pagingData.collectAsLazyPagingItems() else null
    val visibleMistakeCount = if (pagingItems != null) pagingItems.itemCount else uiState.visibleMistakes.size
    val loading = usePaging && !uiState.loaded
    val emptyState = resolveLibraryEmptyState(
        totalMistakeCount = mistakeCount,
        visibleMistakeCount = if (loading) 1 else visibleMistakeCount,
        hasActiveSearch = uiState.query.isNotBlank(),
    )
    RootPageLazyColumn(
        modifier = modifier.testTag("library_root"),
        contentPadding = PaddingValues(
            start = 26.dp,
            top = 0.dp,
            end = 26.dp,
            bottom = 12.dp,
        ),
    ) {
        item(key = "library_header") {
            Column {
                Text(
                    text = "错题本",
                    color = SmartColors.Ink,
                    fontSize = 34.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (emptyState == LibraryEmptyState.CATALOG_EMPTY) {
                    EmptyLibraryResult(
                        state = emptyState,
                        canClear = false,
                        onCapture = onCapture,
                        onClear = {},
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    PrimaryActionButton(
                        text = "拍照或上传错题",
                        onClick = onCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("library_capture_button"),
                        icon = Icons.Outlined.PhotoCamera,
                    )
                }
                PaperDivider(Modifier.padding(vertical = 8.dp))
                BatchImportEntryRow(onClick = onBatchImport)
                PaperDivider(Modifier.padding(vertical = 8.dp))
                ArchivedEntriesSection(
                    repository = mistakeDetailRepository,
                    onRestore = { entryId ->
                        scope.launch {
                            mistakeDetailRepository?.restoreEntry(
                                entryId = entryId,
                                at = System.currentTimeMillis(),
                            )
                        }
                    },
                )
                if (emptyState != LibraryEmptyState.CATALOG_EMPTY) {
                    LibrarySearchField(
                        query = uiState.query,
                        onQueryChange = viewModel::updateQuery,
                    )
                    Spacer(Modifier.height(10.dp))
                    SectionHeader(
                        title = "分类筛选",
                        action = {
                            Text(
                                text = "${uiState.totalCount} 道题",
                                modifier = Modifier.testTag("library_result_count"),
                                style = MaterialTheme.typography.labelMedium,
                                color = SmartColors.InkSecondary,
                            )
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    FacetTabs(
                        activeFacet = uiState.activeFacet,
                        onSelect = viewModel::selectFacet,
                    )
                    Spacer(Modifier.height(4.dp))
                    FacetOptions(
                        facet = uiState.activeFacet,
                        options = uiState.activeOptions,
                        selectedOptionId = uiState.selections.selectedOptionId(uiState.activeFacet),
                        onSelect = { viewModel.toggleFilter(uiState.activeFacet, it) },
                    )
                    if ((pagingItems?.itemCount ?: uiState.visibleMistakes.size) > 0) {
                        Spacer(Modifier.height(10.dp))
                        OutlineActionChip(
                            text = "导出当前 ${visibleMistakeCount} 道",
                            onClick = {
                                // 与按钮口径一致：按当前筛选拉全量 id，而不是
                                // Paging 已加载的子集；超限时导出页会提示缩小范围。
                                viewModel.exportVisible(
                                    MAX_LIBRARY_BATCH_EXPORT_QUESTIONS,
                                ) { ids -> onExportVisible(ids) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("library_export_visible"),
                            icon = Icons.Outlined.PictureAsPdf,
                        )
                    }
                }
            }
        }
        if (emptyState == LibraryEmptyState.FILTERED_EMPTY) {
            item(key = "library_empty") {
                EmptyLibraryResult(
                    state = emptyState,
                    canClear = uiState.hasActiveFilters,
                    onCapture = onCapture,
                    onClear = viewModel::clearAll,
                )
            }
        } else if (emptyState == null && pagingItems != null) {
            items(
                count = pagingItems.itemCount,
                contentType = pagingItems.itemContentType { _ -> "library_item" },
            ) { index ->
                val mistake = pagingItems[index]?.toLibraryMistake()
                if (mistake != null) {
                    LibraryItemRow(
                        mistake = mistake,
                        onClick = { onOpenItem(mistake.id) },
                    )
                }
            }
        } else if (emptyState == null) {
            items(
                items = uiState.visibleMistakes,
                key = LibraryMistake::id,
            ) { mistake ->
                LibraryItemRow(
                    mistake = mistake,
                    onClick = { onOpenItem(mistake.id) },
                )
            }
        }
        item(key = "library_footer") {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BatchImportEntryRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
            .testTag("library_batch_import"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Collections, contentDescription = null, tint = SmartColors.Jade)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "批量导入试卷照片",
                style = MaterialTheme.typography.bodySmall,
                color = SmartColors.Ink,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "一次选择多张，保存后逐张继续",
                style = MaterialTheme.typography.labelSmall,
                color = SmartColors.InkSecondary,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "打开批量导入",
            tint = SmartColors.InkSecondary,
        )
    }
}

@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .testTag("library_search_field"),
        singleLine = true,
        placeholder = { Text("搜索题目、板块或知识点") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.testTag("library_search_clear"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "清除搜索",
                    )
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SmartColors.Jade,
            unfocusedBorderColor = SmartColors.Outline,
            focusedTextColor = SmartColors.Ink,
            unfocusedTextColor = SmartColors.Ink,
            focusedLeadingIconColor = SmartColors.Jade,
            unfocusedLeadingIconColor = SmartColors.InkSecondary,
            cursorColor = SmartColors.Jade,
        ),
    )
}

@Composable
private fun FacetTabs(
    activeFacet: LibraryFacet,
    onSelect: (LibraryFacet) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LibraryFacet.entries.forEach { facet ->
            val selected = facet == activeFacet
            Column(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .semantics { role = Role.Tab }
                    .clickable { onSelect(facet) }
                    .padding(vertical = 8.dp)
                    .testTag("library_facet_${facet.id}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = facet.label,
                    color = if (selected) SmartColors.Jade else SmartColors.InkSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(if (selected) 34.dp else 0.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (selected) SmartColors.Jade else SmartColors.Paper),
                )
            }
        }
    }
}

@Composable
private fun FacetOptions(
    facet: LibraryFacet,
    options: List<LibraryFacetOption>,
    selectedOptionId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlineActionChip(
            text = "全部",
            onClick = { onSelect(null) },
            modifier = Modifier.testTag("library_filter_all"),
            selected = selectedOptionId == null,
        )
        options.forEach { option ->
            val displayText = if (option.count > 0) {
                "${option.label} (${option.count})"
            } else {
                option.label
            }
            OutlineActionChip(
                text = displayText,
                onClick = { onSelect(option.id) },
                modifier = Modifier.testTag(filterTag(facet, option)),
                selected = selectedOptionId == option.id,
            )
        }
    }
}

private fun filterTag(facet: LibraryFacet, option: LibraryFacetOption): String = when {
    facet == LibraryFacet.MASTERY && option.id == MasteryState.MASTERED.id ->
        "library_filter_mastered"
    facet == LibraryFacet.KNOWLEDGE && option.id == "导数" -> "library_filter_derivative"
    else -> "library_filter_${facet.id}_${option.id.hashCode().toUInt()}"
}

@Composable
private fun LibraryItemRow(
    mistake: LibraryMistake,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag("library_item_${mistake.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubjectIcon(
            subject = mistake.subject,
            contentDescription = "${mistake.title} 学科",
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = mistake.title,
                style = MaterialTheme.typography.titleMedium,
                color = SmartColors.Ink,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = mistake.summary,
                style = MaterialTheme.typography.bodySmall,
                color = SmartColors.InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "${mistake.contentPath} · ${mistake.mastery.label}",
                style = MaterialTheme.typography.labelMedium,
                color = masteryColor(mistake.mastery),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "打开 ${mistake.title}",
            tint = SmartColors.InkSecondary,
        )
    }
}

@Composable
private fun masteryColor(mastery: MasteryState) = when (mastery) {
    MasteryState.MASTERED -> SmartColors.Jade
    MasteryState.UNKNOWN -> SmartColors.InkSecondary
    MasteryState.LEARNING,
    MasteryState.CONFLICTED,
    MasteryState.STALE,
    -> SmartColors.ErrorWarm
}

@Composable
private fun EmptyLibraryResult(
    state: LibraryEmptyState,
    canClear: Boolean,
    onCapture: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp)
            .testTag("library_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = when (state) {
                LibraryEmptyState.CATALOG_EMPTY -> Icons.Outlined.PhotoCamera
                LibraryEmptyState.FILTERED_EMPTY -> Icons.Outlined.Search
                LibraryEmptyState.SEARCH_EMPTY -> Icons.Outlined.SearchOff
            },
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = SmartColors.InkMuted,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleMedium,
            color = SmartColors.Ink,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.supportingText,
            style = MaterialTheme.typography.bodyMedium,
            color = SmartColors.InkSecondary,
        )
        if (state == LibraryEmptyState.CATALOG_EMPTY) {
            Spacer(Modifier.height(16.dp))
            PrimaryActionButton(
                text = "拍照或上传",
                onClick = onCapture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("library_capture_button"),
                icon = Icons.Outlined.PhotoCamera,
            )
        } else if (canClear) {
            Spacer(Modifier.height(16.dp))
            OutlineActionChip(
                text = "清除搜索与筛选",
                onClick = onClear,
                modifier = Modifier.testTag("library_clear_filters"),
            )
        }
    }
}

/**
 * "已移出的题"折叠区：归档条目从列表/搜索消失后，这里给出唯一的恢复入口，
 * 让详情页"之后可以恢复"的承诺真正可达。
 */
@Composable
private fun ArchivedEntriesSection(
    repository: MistakeDetailRepository?,
    onRestore: (String) -> Unit,
) {
    if (repository == null) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val archived by produceState<List<ArchivedMistakeRef>>(emptyList(), repository) {
        repository.observeArchived().collect { value = it }
    }
    if (archived.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        OutlineActionChip(
            text = "已移出的题（${archived.size}）",
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("library_archived_toggle"),
        )
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 8.dp)) {
                archived.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                color = SmartColors.Ink,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlineActionChip(
                            text = "恢复",
                            onClick = { onRestore(item.entryId) },
                            modifier = Modifier.testTag("library_archived_restore"),
                        )
                    }
                }
                Text(
                    text = "恢复后这道题会回到错题本列表和复习计划。",
                    color = SmartColors.InkSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
