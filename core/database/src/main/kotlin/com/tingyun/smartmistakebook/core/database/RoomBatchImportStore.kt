package com.tingyun.smartmistakebook.core.database

import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.dao.BatchImportJobWithPages
import com.tingyun.smartmistakebook.core.database.entity.BatchImportJobEntity
import com.tingyun.smartmistakebook.core.database.entity.BatchImportPageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomBatchImportStore(
    private val database: StudyDatabase,
) {
    fun observe(): Flow<List<BatchImportJobRecord>> =
        database.batchImportDao().observeJobsWithPages().map { jobs ->
            jobs.map(BatchImportJobWithPages::toRecord)
        }

    suspend fun create(command: CreateBatchImportJobCommand): BatchImportJobRecord =
        database.withWriteTransaction {
            require(command.jobId.isNotBlank())
            require(command.requestId.isNotBlank())
            require(command.requestFingerprint.matches(SHA_256))
            require(command.sourceUris.size in 2..30)
            require(command.sourceUris.all { it.isNotBlank() })
            val dao = database.batchImportDao()
            dao.readJobByRequest(command.requestId)?.let { existing ->
                if (existing.requestFingerprint != command.requestFingerprint) {
                    throw ImmutablePayloadConflictException("batch_import_request", command.requestId)
                }
                return@withWriteTransaction existing.toRecord(dao.readPages(existing.jobId))
            }
            val job = BatchImportJobEntity(
                jobId = command.jobId,
                requestId = command.requestId,
                requestFingerprint = command.requestFingerprint,
                status = StudyDbValue.BatchImportStatus.PROCESSING,
                createdAtEpochMillis = command.occurredAtEpochMillis,
                updatedAtEpochMillis = command.occurredAtEpochMillis,
            )
            val pages = command.sourceUris.mapIndexed { index, uri ->
                BatchImportPageEntity(
                    jobId = command.jobId,
                    pageIndex = index,
                    sourceUri = uri,
                    status = StudyDbValue.BatchImportPageStatus.QUEUED,
                    resultDraftId = null,
                    failureCode = null,
                    attemptCount = 0,
                    createdAtEpochMillis = command.occurredAtEpochMillis,
                    updatedAtEpochMillis = command.occurredAtEpochMillis,
                    boundaryAfterStatus = StudyDbValue.BatchImportBoundaryStatus.PENDING,
                )
            }
            dao.insertJob(job)
            dao.insertPages(pages)
            job.toRecord(pages)
        }

    suspend fun read(jobId: String): BatchImportJobRecord? {
        require(jobId.isNotBlank())
        return database.batchImportDao().readJobWithPages(jobId)?.toRecord()
    }

    suspend fun updateStatus(
        jobId: String,
        expectedStatus: String,
        nextStatus: String,
        occurredAtEpochMillis: Long,
    ): Boolean = database.batchImportDao().updateJobStatus(
        jobId,
        expectedStatus,
        nextStatus,
        occurredAtEpochMillis,
    ) == 1

    suspend fun requeueInterrupted(jobId: String, occurredAtEpochMillis: Long): Int =
        database.withWriteTransaction {
            val dao = database.batchImportDao()
            val changed = dao.requeueInterruptedPages(jobId, occurredAtEpochMillis)
            if (changed > 0) dao.touchJob(jobId, occurredAtEpochMillis)
            changed
        }

    suspend fun claimNext(jobId: String, occurredAtEpochMillis: Long): BatchImportPageRecord? =
        database.withWriteTransaction {
            val dao = database.batchImportDao()
            val job = dao.readJob(jobId) ?: return@withWriteTransaction null
            if (job.status != StudyDbValue.BatchImportStatus.PROCESSING) {
                return@withWriteTransaction null
            }
            val page = dao.readNextQueuedPage(jobId) ?: return@withWriteTransaction null
            if (dao.claimPage(jobId, page.pageIndex, occurredAtEpochMillis) != 1) {
                return@withWriteTransaction null
            }
            dao.touchJob(jobId, occurredAtEpochMillis)
            page.copy(
                status = StudyDbValue.BatchImportPageStatus.IMPORTING,
                attemptCount = page.attemptCount + 1,
                updatedAtEpochMillis = occurredAtEpochMillis,
            ).toRecord()
        }

    suspend fun completePage(
        jobId: String,
        pageIndex: Int,
        draftId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = updatePageAndTouch(jobId, occurredAtEpochMillis) { dao ->
        dao.markPageReady(jobId, pageIndex, draftId, occurredAtEpochMillis) == 1
    }

    suspend fun failPage(
        jobId: String,
        pageIndex: Int,
        failureCode: String,
        occurredAtEpochMillis: Long,
    ): Boolean = updatePageAndTouch(jobId, occurredAtEpochMillis) { dao ->
        dao.markPageFailed(jobId, pageIndex, failureCode, occurredAtEpochMillis) == 1
    }

    suspend fun claimBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = updatePageAndTouch(jobId, occurredAtEpochMillis) { dao ->
        dao.claimBoundary(jobId, pageIndex, occurredAtEpochMillis) == 1
    }

    suspend fun failBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = updatePageAndTouch(jobId, occurredAtEpochMillis) { dao ->
        dao.failBoundary(jobId, pageIndex, occurredAtEpochMillis) == 1
    }

    suspend fun requeueInterruptedBoundaries(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int = database.withWriteTransaction {
        val dao = database.batchImportDao()
        val changed = dao.requeueInterruptedBoundaries(jobId, occurredAtEpochMillis)
        if (changed > 0) dao.touchJob(jobId, occurredAtEpochMillis)
        changed
    }

    suspend fun retryPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = database.withWriteTransaction {
        val dao = database.batchImportDao()
        val job = dao.readJob(jobId) ?: return@withWriteTransaction false
        val changed = dao.retryPage(jobId, pageIndex, occurredAtEpochMillis) == 1
        if (!changed) return@withWriteTransaction false
        if (job.status == StudyDbValue.BatchImportStatus.PROCESSING) {
            dao.touchJob(jobId, occurredAtEpochMillis)
        } else {
            require(
                job.status == StudyDbValue.BatchImportStatus.PAUSED ||
                    job.status == StudyDbValue.BatchImportStatus.COMPLETED,
            ) { "Unsupported batch import status" }
            check(
                dao.updateJobStatus(
                    jobId,
                    job.status,
                    StudyDbValue.BatchImportStatus.PROCESSING,
                    occurredAtEpochMillis,
                ) == 1,
            ) { "Failed page retry could not resume its batch job" }
        }
        true
    }

    suspend fun skipPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = updatePageAndTouch(jobId, occurredAtEpochMillis) { dao ->
        dao.skipPage(jobId, pageIndex, occurredAtEpochMillis) == 1
    }

    suspend fun finishIfSettled(jobId: String, occurredAtEpochMillis: Long): Boolean =
        database.withWriteTransaction {
            val dao = database.batchImportDao()
            if (dao.countActivePages(jobId) != 0) return@withWriteTransaction false
            val job = dao.readJob(jobId) ?: return@withWriteTransaction false
            if (job.status == StudyDbValue.BatchImportStatus.COMPLETED) {
                return@withWriteTransaction false
            }
            require(
                job.status == StudyDbValue.BatchImportStatus.PROCESSING ||
                    job.status == StudyDbValue.BatchImportStatus.PAUSED,
            ) { "Unsupported batch import status" }
            dao.updateJobStatus(
                jobId,
                job.status,
                StudyDbValue.BatchImportStatus.COMPLETED,
                occurredAtEpochMillis,
            ) == 1
        }

    suspend fun hasRetainedSourceUri(sourceUri: String): Boolean {
        require(sourceUri.isNotBlank())
        return database.batchImportDao().countRetainedSourceUri(sourceUri) > 0
    }

    private suspend fun updatePageAndTouch(
        jobId: String,
        occurredAtEpochMillis: Long,
        update: suspend (com.tingyun.smartmistakebook.core.database.dao.BatchImportDao) -> Boolean,
    ): Boolean = database.withWriteTransaction {
        val dao = database.batchImportDao()
        val changed = update(dao)
        if (changed) dao.touchJob(jobId, occurredAtEpochMillis)
        changed
    }
}

private val SHA_256 = Regex("[a-f0-9]{64}")

private fun BatchImportJobEntity.toRecord(pages: List<BatchImportPageEntity>) =
    BatchImportJobRecord(
        jobId = jobId,
        requestId = requestId,
        requestFingerprint = requestFingerprint,
        status = status,
        pages = pages.sortedBy(BatchImportPageEntity::pageIndex)
            .map(BatchImportPageEntity::toRecord),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

private fun BatchImportPageEntity.toRecord() = BatchImportPageRecord(
    jobId = jobId,
    pageIndex = pageIndex,
    sourceUri = sourceUri,
    status = status,
    resultDraftId = resultDraftId,
    failureCode = failureCode,
    attemptCount = attemptCount,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    boundaryAfterStatus = boundaryAfterStatus,
)

private fun BatchImportJobWithPages.toRecord() = job.toRecord(pages)
