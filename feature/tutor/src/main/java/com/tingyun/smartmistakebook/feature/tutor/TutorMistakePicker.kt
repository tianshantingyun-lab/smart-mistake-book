package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.Paper

/**
 * 「从错题库选择」：在学生当前所在的页面里直接挑一道题，选完交给调用方作为**本轮附件**带进
 * 讲题页面（`onOpenMistakeTutor`）。两个入口共用它：输入框加号里的那一项，以及空态里的
 * 「从错题本选择」快捷按钮。
 *
 * 消灭的失败：这条入口此前只是 `navigate(Routes.Library)`——跳到错题本、自己找、点进详情、
 * 再点「讲解这道题」，四步之后才回到讲题；而且从错题本"选择"这个动作根本不返回任何东西
 * （`Routes.Library` 没有参数、`LibraryRoute` 也没有选择回调）。学生在这个页面上点了"从错题库
 * 选择"，得到的是离开当前页，而不是把题加进来。
 */
@Composable
internal fun TutorMistakePickerDialog(
    entries: List<StudyCatalogEntry>,
    onPick: (StudyCatalogEntry) -> Unit,
    onDismiss: () -> Unit,
    testTagPrefix: String,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(entries, query) { filterMistakeCandidates(entries, query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从错题库选择") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { value -> query = value.take(MAX_PICKER_QUERY_CHARS) },
                    singleLine = true,
                    label = { Text("按题目、章节或知识点筛选") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${testTagPrefix}_picker_query"),
                )
                if (visible.isEmpty()) {
                    Text(
                        text = if (entries.isEmpty()) {
                            "错题本里还没有可以讲的题。先拍一张或从相册导入，再回到这里。"
                        } else {
                            "没有匹配的题，换个词试试。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary,
                        modifier = Modifier.testTag("${testTagPrefix}_picker_empty"),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = PICKER_MAX_HEIGHT)
                            .testTag("${testTagPrefix}_picker_list"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(items = visible, key = { entry -> entry.problemRevisionId }) { entry ->
                            MistakePickerRow(
                                entry = entry,
                                onClick = { onPick(entry) },
                                testTag = "${testTagPrefix}_picker_item_${entry.problemId}",
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("${testTagPrefix}_picker_cancel"),
            ) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun MistakePickerRow(
    entry: StudyCatalogEntry,
    onClick: () -> Unit,
    testTag: String,
) {
    Surface(
        color = Paper,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                maxLines = 2,
            )
            val meta = buildList {
                if (entry.subject.isNotBlank()) add(entry.subject)
                addAll(entry.chapterLabels.take(1))
                addAll(entry.knowledgeLabels.take(2))
            }
            if (meta.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 候选筛选（纯函数，便于单测）：空查询返回全部；否则按题目、章节、知识点、科目做大小写无关的
 * 子串匹配。刻意不做分词与排序猜测——这一步只负责"把范围缩到学生自己认得出的那几道题"，
 * 真正的语义判定不在这里。
 */
internal fun filterMistakeCandidates(
    entries: List<StudyCatalogEntry>,
    query: String,
): List<StudyCatalogEntry> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return entries
    return entries.filter { entry ->
        entry.title.lowercase().contains(needle) ||
            entry.subject.lowercase().contains(needle) ||
            entry.chapterLabels.any { label -> label.lowercase().contains(needle) } ||
            entry.knowledgeLabels.any { label -> label.lowercase().contains(needle) }
    }
}

/** 把错题本条目转成讲题会话需要的题面身份。 */
internal fun StudyCatalogEntry.toMistakeRevisionKey(): MistakeRevisionKey = MistakeRevisionKey(
    entryId = entryId,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
)

private const val MAX_PICKER_QUERY_CHARS = 40
private val PICKER_MAX_HEIGHT = 320.dp
