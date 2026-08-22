package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.MistakeDetailRecord
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.MistakeRevisionSummaryRecord
import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for mistake operations.
 */
interface MistakeReadPort {
    fun observeMistakes(): Flow<List<MistakeRecord>>
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
}
