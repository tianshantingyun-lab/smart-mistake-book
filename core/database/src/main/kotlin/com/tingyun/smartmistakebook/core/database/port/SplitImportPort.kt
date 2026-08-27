package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.CreateSplitImportJobCommand
import com.tingyun.smartmistakebook.core.database.SplitImportJobRecord
import com.tingyun.smartmistakebook.core.database.SplitImportQuestionSeed
import kotlinx.coroutines.flow.Flow

/**
 * Split-import ledger: one import action (PDF / batch / single page) cut
 * into individual questions the student confirms before they enter the
 * normal draft workflows.
 */
interface SplitImportPort {
    fun observeActiveSplitImports(): Flow<List<SplitImportJobRecord>>

    suspend fun readSplitImportJob(jobId: String): SplitImportJobRecord?

    suspend fun createSplitImportJob(
        command: CreateSplitImportJobCommand,
        questions: List<SplitImportQuestionSeed>,
    ): SplitImportJobRecord

    suspend fun markSplitImportReady(
        jobId: String,
        questionCount: Int,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun updateSplitImportSelection(
        jobId: String,
        questionOrdinal: Int,
        selected: Boolean,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun markSplitImportQuestionConfirmed(
        jobId: String,
        questionOrdinal: Int,
        confirmState: String,
        splitDraftId: String?,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun completeSplitImportJob(jobId: String, occurredAtEpochMillis: Long): Boolean

    suspend fun abandonSplitImportJob(jobId: String, occurredAtEpochMillis: Long): Boolean
}