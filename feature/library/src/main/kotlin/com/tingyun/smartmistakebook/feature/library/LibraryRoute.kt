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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageLazyColumn
import com.tingyun.smartmistakebook.core.ui.SmartColors
import com.tingyun.smartmistakebook.core.ui.SubjectIcon
import com.tingyun.smartmistakebook.core.ui.studentLabel

@Composable
fun LibraryRoute(
    entries: List<StudyCatalogEntry>,
    pendingCaptureCount: Int,
    onCapture: () -> Unit,
    onBatchImport: () -> Unit,
    onOpenPendingCaptures: () -> Unit,
    onExportVisible: (List<String>) -> Unit,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val libraryViewModel: LibraryViewModel = viewModel()
    LaunchedEffect(entries) {
        libraryViewModel.updateCatalog(entries.map(StudyCatalogEntry::toLibraryMistake))
    }
    LibraryContent(
        mistakeCount = entries.size,
        pendingCaptureCount = pendingCaptureCount,
        onCapture = onCapture,
        onBatchImport = onBatchImport,
        onOpenPendingCaptures = onOpenPendingCaptures,
        onExportVisible = onExportVisible,
        onOpenItem = onOpenItem,
        viewModel = libraryViewModel,
        modifier = modifier,
    )
}

@Composable
private fun LibraryContent(
    mistakeCount: Int,
    pendingCaptureCount: Int,
    onCapture: () -> Unit,
    onBatchImport: () -> Unit,
    onOpenPendingCaptures: () -> Unit,
    onExportVisible: (List<String>) -> Unit,
    onOpenItem: (String) -> Unit,
    viewModel: LibraryViewModel,
    modifier: Modifier,
) {
    val uiState = viewModel.uiState
    val emptyState = resolveLibraryEmptyState(
        totalMistakeCount = mistakeCount,
        visibleMistakeCount = uiState.visibleMistakes.size,
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
                PrimaryActionButton(
                    text = "拍照或上传",
                    onClick = onCapture,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("library_capture_button"),
                    icon = Icons.Outlined.PhotoCamera,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pendingCaptureCount > 0) {
                        OutlineActionChip(
                            text = "$pendingCaptureCount 道待处理",
                            onClick = onOpenPendingCaptures,
                            modifier = Modifier.testTag("library_pending_review"),
                            icon = Icons.Outlined.Schedule,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    LibraryOverflowMenu(
                        exportIds = libraryExportIds(uiState.visibleMistakes),
                        onBatchImport = onBatchImport,
                        onOpenPendingCaptures = onOpenPendingCaptures,
                        onExportVisible = onExportVisible,
                    )
                }
                if (emptyState == LibraryEmptyState.CATALOG_EMPTY) {
                    EmptyLibraryResult(
                        state = emptyState,
                        canClear = false,
                        onClear = {},
                    )
                } else {
                    PaperDivider(Modifier.padding(vertical = 8.dp))
                    LibrarySearchField(
                        query = uiState.query,
                        onQueryChange = viewModel::updateQuery,
                    )
                    Spacer(Modifier.height(14.dp))
                    LibraryHierarchy(
                        uiState = uiState,
                        onSelectFacet = viewModel::selectFacet,
                        onSelectOption = { facet, optionId ->
                            viewModel.toggleFilter(facet, optionId)
                            val activeSelection =
                                viewModel.uiState.selections.selectedOptionId(facet)
                            viewModel.selectFacet(nextLibraryFacet(facet, activeSelection))
                        },
                    )
                }
            }
        }
        if (emptyState == LibraryEmptyState.FILTERED_EMPTY) {
            item(key = "library_empty") {
                EmptyLibraryResult(
                    state = emptyState,
                    canClear = uiState.hasActiveFilters,
                    onClear = viewModel::clearAll,
                )
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
private fun LibraryOverflowMenu(
    exportIds: List<String>?,
    onBatchImport: () -> Unit,
    onOpenPendingCaptures: () -> Unit,
    onExportVisible: (List<String>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .semantics { role = Role.Button }
                .testTag("library_more"),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = null,
            )
            Text("更多")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("批量录入") },
                onClick = {
                    expanded = false
                    onBatchImport()
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Collections, contentDescription = null)
                },
                modifier = Modifier.testTag("library_batch_import"),
            )
            DropdownMenuItem(
                text = { Text("图片转文档") },
                onClick = {
                    expanded = false
                    onOpenPendingCaptures()
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Schedule, contentDescription = null)
                },
                modifier = Modifier.testTag("library_image_to_document"),
            )
            DropdownMenuItem(
                text = { Text("导出") },
                onClick = {
                    val snapshot = exportIds ?: return@DropdownMenuItem
                    expanded = false
                    onExportVisible(snapshot)
                },
                enabled = exportIds != null,
                leadingIcon = {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
                },
                modifier = Modifier.testTag("library_export_visible"),
            )
        }
    }
}

@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val fieldHeight = if (LocalDensity.current.fontScale <= 1f) {
        Modifier.height(54.dp)
    } else {
        Modifier.heightIn(min = 54.dp)
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .then(fieldHeight)
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
private fun LibraryHierarchy(
    uiState: LibraryUiState,
    onSelectFacet: (LibraryFacet) -> Unit,
    onSelectOption: (LibraryFacet, String?) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "科目 → 板块/章节 → 知识点 → 掌握程度",
            color = SmartColors.InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        LibraryFacet.entries.forEach { facet ->
            val selected = facet == uiState.activeFacet
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .selectable(
                        selected = selected,
                        role = Role.Button,
                        onClick = { onSelectFacet(facet) },
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .testTag("library_facet_${facet.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = facet.label,
                    modifier = Modifier.weight(1f),
                    color = if (selected) SmartColors.Jade else SmartColors.Ink,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
                selectedFacetLabel(facet, uiState.selections)?.let { label ->
                    Text(
                        text = label,
                        color = SmartColors.InkSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = SmartColors.InkSecondary,
                )
            }
            if (selected) {
                FacetOptions(
                    facet = facet,
                    options = uiState.activeOptions,
                    selectedOptionId = uiState.selections.selectedOptionId(facet),
                    onSelect = { onSelectOption(facet, it) },
                )
            }
        }
    }
}

private fun selectedFacetLabel(
    facet: LibraryFacet,
    selections: LibrarySelections,
): String? = when (facet) {
    LibraryFacet.SUBJECT -> selections.subject?.let { stored ->
        runCatching { SubjectKind.valueOf(stored) }
            .getOrNull()
            ?.studentLabel()
            ?: stored
    }
    LibraryFacet.CHAPTER -> selections.chapter
    LibraryFacet.KNOWLEDGE -> selections.knowledge
    LibraryFacet.MASTERY -> MasteryState.entries
        .firstOrNull { it.id == selections.mastery }
        ?.label
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
            modifier = Modifier
                .semantics { selected = selectedOptionId == null }
                .testTag("library_filter_all"),
            selected = selectedOptionId == null,
        )
        options.forEach { option ->
            OutlineActionChip(
                text = option.label,
                onClick = { onSelect(option.id) },
                modifier = Modifier
                    .semantics { selected = selectedOptionId == option.id }
                    .testTag(filterTag(facet, option)),
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
                LibraryEmptyState.FILTERED_EMPTY -> Icons.Outlined.SearchOff
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
        if (state == LibraryEmptyState.FILTERED_EMPTY && canClear) {
            Spacer(Modifier.height(16.dp))
            OutlineActionChip(
                text = "清除搜索与筛选",
                onClick = onClear,
                modifier = Modifier.testTag("library_clear_filters"),
            )
        }
    }
}
