package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.MistakeDetailRecord
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.MistakeRevisionSummaryRecord
import com.tingyun.smartmistakebook.core.database.dao.ArchivedEntrySummaryRow
import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for mistake operations.
 */
interface MistakeReadPort {
    fun observeMistakes(): Flow<List<MistakeRecord>>

    /**
     * 一道题的 KC 范围，取自与 [observeMistakes] **同一份 SQL**。
     *
     * 审计 N-19：`observeMistakes` 的目录视图每行带 5 个相关子查询，而会话里
     * "这道题绑在哪些 KC 上"是**每张卡一次**——整读目录就成了 `O(N²)`。这里只多给一个入口，
     * 不另写谓词：两处的范围因此不可能分叉（N-20 记的正是"同一个事实四个答案"）。
     *
     * `null` 与空集对调用方是同一个意思：**没有可言的 KC 范围**。
     */
    suspend fun knowledgeNodeIdsForPracticeUnit(practiceUnitId: String): Set<String>?
    suspend fun countMistakes(): Int
    suspend fun findMistakeBySourceKey(sourceKey: String): MistakeRecord?
    suspend fun readMistakeDetail(errorBookEntryId: String): MistakeDetailRecord?
    suspend fun readExactMistakeDetail(
        entryId: String,
        problemId: String,
        problemRevisionId: String,
    ): MistakeDetailRecord?

    suspend fun readCurrentMistakeDetails(
        entryIds: List<String>,
    ): List<MistakeDetailRecord>

    suspend fun readMistakeRevisionHistory(
        problemId: String,
    ): List<MistakeRevisionSummaryRecord>

    /**
     * Writes the learner's private note on an error-book entry. The note is
     * user text on the entry (not the append-only revision); it never enters a
     * model egress payload. Returns false when the entry no longer exists.
     */
    suspend fun updateErrorBookEntryNote(
        entryId: String,
        note: String?,
        updatedAtEpochMillis: Long,
    ): Boolean

    /**
     * Reversibly removes an error-book entry from every user-facing surface
     * (catalog, search, review, mastery) by archiving it; learning history is
     * preserved and [restoreErrorBookEntry] brings it back.
     */
    suspend fun archiveErrorBookEntry(entryId: String, at: Long): Boolean

    suspend fun restoreErrorBookEntry(entryId: String, at: Long): Boolean

    fun observeArchivedErrorBookEntries(): Flow<List<ArchivedEntrySummaryRow>>
}
