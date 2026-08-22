package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.BatchImportJobRecord
import com.tingyun.smartmistakebook.core.database.BatchImportPageRecord
import com.tingyun.smartmistakebook.core.database.CreateBatchImportJobCommand
import com.tingyun.smartmistakebook.core.database.ResolveBatchImportBoundaryCommand
import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for batch import operations.
 */
interface BatchImportReadPort {
    fun observeBatchImportJobs(): Flow<List<BatchImportJobRecord>>
}

/**
 * Write port for batch import job/page/boundary state transitions.
 */
interface BatchImportWritePort {
    suspend fun createBatchImportJob(
        command: CreateBatchImportJobCommand,
    ): BatchImportJobRecord

    suspend fun readBatchImportJob(jobId: String): BatchImportJobRecord?

    suspend fun updateBatchImportJobStatus(
        jobId: String,
        expectedStatus: String,
        nextStatus: String,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun requeueInterruptedBatchImportPages(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int

    suspend fun claimNextBatchImportPage(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): BatchImportPageRecord?

    suspend fun completeBatchImportPage(
        jobId: String,
        pageIndex: Int,
        draftId: String,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun claimBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun requeueInterruptedBatchImportBoundaries(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int

    suspend fun resolveBatchImportBoundary(
        command: ResolveBatchImportBoundaryCommand,
    ): BatchImportJobRecord

    suspend fun failBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun failBatchImportPage(
        jobId: String,
        pageIndex: Int,
        failureCode: String,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun retryBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun skipBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun finishBatchImportIfSettled(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun hasRetainedBatchImportSourceUri(sourceUri: String): Boolean
}
