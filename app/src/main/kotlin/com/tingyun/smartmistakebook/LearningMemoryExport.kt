package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgeItem
import com.tingyun.smartmistakebook.core.domain.LearningMasteryLoadState
import com.tingyun.smartmistakebook.core.domain.LearningMasteryOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageCursor
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject
import com.tingyun.smartmistakebook.core.ui.studentLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

internal data class LearningMemoryExportDocument(
    val exportedAtEpochMillis: Long,
    val text: String,
    val subjectCount: Int,
    val knowledgePointCount: Int,
) {
    init {
        require(exportedAtEpochMillis >= 0L) {
            "Learning memory export time must not be negative"
        }
        require(subjectCount > 0) {
            "Learning memory export requires at least one subject"
        }
        require(knowledgePointCount >= 0) {
            "Learning memory export knowledge count must not be negative"
        }
    }
}

internal sealed interface LearningMemoryExportLoadResult {
    data class Ready(
        val document: LearningMemoryExportDocument,
    ) : LearningMemoryExportLoadResult

    data object Unavailable : LearningMemoryExportLoadResult
}

internal const val MAX_EXPORT_KNOWLEDGE_POINTS = 5_000

internal fun buildLearningMemoryExport(
    overview: LearningMasteryOverview,
    knowledgeBySubject: Map<LearningMasterySubject, List<LearningMasteryKnowledgeItem>>,
    exportedAtEpochMillis: Long,
): LearningMemoryExportDocument {
    val exportedAt = Instant.ofEpochMilli(exportedAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .format(EXPORT_TIME_FORMAT)
    val totalAvailable = overview.subjects.sumOf { subject ->
        knowledgeBySubject[subject.subject].orEmpty().size
    }
    val included = mutableListOf<LearningMasteryKnowledgeItem>()
    val text = buildString {
        appendLine("智能错题本学习记录")
        appendLine("导出时间：$exportedAt")
        overview.subjects.forEach { subjectOverview ->
            val subject = subjectOverview.subject
            val items = knowledgeBySubject[subject].orEmpty()
            appendLine()
            appendLine("科目：${subject.studentLabel()}")
            appendLine("掌握状态：${subjectOverview.status.studentLabel()}")
            if (items.isEmpty()) {
                appendLine(
                    if (included.size >= MAX_EXPORT_KNOWLEDGE_POINTS) {
                        "知识点：已省略"
                    } else {
                        "知识点：暂无"
                    },
                )
            } else if (included.size >= MAX_EXPORT_KNOWLEDGE_POINTS) {
                appendLine("知识点：已省略")
            } else {
                appendLine("知识点：")
                for (item in items) {
                    if (included.size >= MAX_EXPORT_KNOWLEDGE_POINTS) break
                    included += item
                    val path = (item.displayPath + item.displayName).joinToString(" / ")
                    appendLine("- $path（${item.status.studentLabel()}）")
                }
            }
        }
        appendLine()
        if (included.size == MAX_EXPORT_KNOWLEDGE_POINTS && included.size < totalAvailable) {
            appendLine("知识点超过导出上限，已包含前 ${included.size} 条。")
        } else {
            appendLine("共 ${included.size} 条知识点。")
        }
    }
    return LearningMemoryExportDocument(
        exportedAtEpochMillis = exportedAtEpochMillis,
        text = text,
        subjectCount = overview.subjects.size,
        knowledgePointCount = included.size,
    )
}

internal suspend fun LearningMasteryDisplayRepository.loadLearningMemoryExport(
    nowEpochMillis: Long = System.currentTimeMillis(),
): LearningMemoryExportLoadResult {
    val overviewState = observeSubjectOverview().first { state ->
        state !is LearningMasteryLoadState.Loading
    }
    val overview = (overviewState as? LearningMasteryLoadState.Content<LearningMasteryOverview>)
        ?.value ?: return LearningMemoryExportLoadResult.Unavailable
    val knowledgeBySubject =
        linkedMapOf<LearningMasterySubject, MutableList<LearningMasteryKnowledgeItem>>()
    overview.subjects.forEach { subjectOverview ->
        val subject = subjectOverview.subject
        val items = mutableListOf<LearningMasteryKnowledgeItem>()
        var cursor: LearningMasteryPageCursor? = null
        do {
            val pageState = observeKnowledgePage(
                LearningMasteryPageRequest(
                    subject = subject,
                    revision = overview.revision,
                    cursor = cursor,
                    limit = LearningMasteryPageRequest.MAX_LIMIT,
                ),
            ).first { state -> state !is LearningMasteryLoadState.Loading }
            when (pageState) {
                is LearningMasteryLoadState.Content -> {
                    items += pageState.value.items
                    cursor = pageState.value.nextCursor
                }

                is LearningMasteryLoadState.Empty -> cursor = null
                is LearningMasteryLoadState.Error ->
                    return LearningMemoryExportLoadResult.Unavailable
                LearningMasteryLoadState.Loading -> error("Unreachable loading state")
            }
        } while (cursor != null)
        if (items.isNotEmpty()) knowledgeBySubject[subject] = items
    }
    return LearningMemoryExportLoadResult.Ready(
        buildLearningMemoryExport(
            overview = overview,
            knowledgeBySubject = knowledgeBySubject,
            exportedAtEpochMillis = nowEpochMillis,
        ),
    )
}

private val EXPORT_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
