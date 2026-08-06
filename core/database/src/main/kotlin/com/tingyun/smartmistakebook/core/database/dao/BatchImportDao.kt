package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.entity.BatchImportBoundaryResolutionReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.BatchImportJobEntity
import com.tingyun.smartmistakebook.core.database.entity.BatchImportPageEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface BatchImportDao {
    @Transaction
    @Query("SELECT * FROM batch_import_job ORDER BY updated_at_epoch_millis DESC, job_id DESC")
    fun observeJobsWithPages(): Flow<List<BatchImportJobWithPages>>

    @Transaction
    @Query("SELECT * FROM batch_import_job WHERE job_id = :jobId")
    suspend fun readJobWithPages(jobId: String): BatchImportJobWithPages?

    @Query("SELECT * FROM batch_import_job WHERE job_id = :jobId")
    suspend fun readJob(jobId: String): BatchImportJobEntity?

    @Query("SELECT * FROM batch_import_job WHERE request_id = :requestId")
    suspend fun readJobByRequest(requestId: String): BatchImportJobEntity?

    @Query("SELECT * FROM batch_import_page WHERE job_id = :jobId ORDER BY page_index")
    suspend fun readPages(jobId: String): List<BatchImportPageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: BatchImportJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPages(pages: List<BatchImportPageEntity>)

    @Query(
        """
        SELECT *
        FROM batch_import_boundary_resolution_receipt
        WHERE job_id = :jobId
          AND page_index = :pageIndex
        """,
    )
    suspend fun readBoundaryResolutionReceipt(
        jobId: String,
        pageIndex: Int,
    ): BatchImportBoundaryResolutionReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBoundaryResolutionReceipt(
        receipt: BatchImportBoundaryResolutionReceiptEntity,
    )

    @Query(
        """
        UPDATE batch_import_job
        SET status = :nextStatus, updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId AND status = :expectedStatus
        """,
    )
    suspend fun updateJobStatus(
        jobId: String,
        expectedStatus: String,
        nextStatus: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE batch_import_job
        SET updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId
        """,
    )
    suspend fun touchJob(jobId: String, updatedAtEpochMillis: Long): Int

    @Query(
        """
        SELECT * FROM batch_import_page
        WHERE job_id = :jobId AND status = 'QUEUED'
        ORDER BY page_index
        LIMIT 1
        """,
    )
    suspend fun readNextQueuedPage(jobId: String): BatchImportPageEntity?

    @Query(
        """
        UPDATE batch_import_page
        SET status = 'IMPORTING', attempt_count = attempt_count + 1,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId AND page_index = :pageIndex AND status = 'QUEUED'
        """,
    )
    suspend fun claimPage(jobId: String, pageIndex: Int, updatedAtEpochMillis: Long): Int

    @Query(
        """
        UPDATE batch_import_page
        SET status = 'READY', result_draft_id = :draftId, failure_code = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId AND page_index = :pageIndex AND status = 'IMPORTING'
        """,
    )
    suspend fun markPageReady(
        jobId: String,
        pageIndex: Int,
        draftId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE batch_import_page
        SET boundary_after_status = 'CHECKING',
            boundary_claimed_at_epoch_millis = :updatedAtEpochMillis,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId
          AND page_index = :pageIndex
          AND status = 'READY'
          AND boundary_after_status IN ('PENDING', 'FAILED')
          AND updated_at_epoch_millis <= :updatedAtEpochMillis
          AND EXISTS (
              SELECT 1 FROM batch_import_page AS next_page
              WHERE next_page.job_id = :jobId
                AND next_page.page_index = :pageIndex + 1
                AND next_page.status = 'READY'
          )
        """,
    )
    suspend fun claimBoundary(
        jobId: String,
        pageIndex: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE batch_import_page
        SET boundary_after_status = :resolution,
            boundary_claimed_at_epoch_millis = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId
          AND page_index = :pageIndex
          AND boundary_after_status = 'CHECKING'
          AND boundary_claimed_at_epoch_millis IS NOT NULL
          AND boundary_claimed_at_epoch_millis <= :updatedAtEpochMillis
          AND boundary_claimed_at_epoch_millis = :boundaryClaimedAtEpochMillis
        """,
    )
    suspend fun resolveBoundary(
        jobId: String,
        pageIndex: Int,
        resolution: String,
        boundaryClaimedAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE batch_import_page
        SET boundary_after_status = 'FAILED',
            boundary_claimed_at_epoch_millis = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId
          AND page_index = :pageIndex
          AND boundary_after_status = 'CHECKING'
          AND boundary_claimed_at_epoch_millis IS NOT NULL
          AND boundary_claimed_at_epoch_millis <= :updatedAtEpochMillis
        """,
    )
    suspend fun failBoundary(
        jobId: String,
        pageIndex: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE batch_import_page
        SET boundary_after_status = 'FAILED',
            boundary_claimed_at_epoch_millis = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId
          AND boundary_after_status = 'CHECKING'
          AND boundary_claimed_at_epoch_millis IS NOT NULL
          AND boundary_claimed_at_epoch_millis <= :updatedAtEpochMillis
        """,
    )
    suspend fun requeueInterruptedBoundaries(
        jobId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE batch_import_page
        SET result_draft_id = :primaryDraftId, updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId AND result_draft_id = :followingDraftId
        """,
    )
    suspend fun remapDraft(
        jobId: String,
        followingDraftId: String,
        primaryDraftId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE batch_import_page
        SET status = 'FAILED', result_draft_id = NULL, failure_code = :failureCode,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId AND page_index = :pageIndex AND status = 'IMPORTING'
        """,
    )
    suspend fun markPageFailed(
        jobId: String,
        pageIndex: Int,
        failureCode: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE batch_import_page
        SET status = 'QUEUED', failure_code = NULL, updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId AND page_index = :pageIndex AND status = 'FAILED'
        """,
    )
    suspend fun retryPage(jobId: String, pageIndex: Int, updatedAtEpochMillis: Long): Int

    @Query(
        """
        UPDATE batch_import_page
        SET status = 'SKIPPED', failure_code = NULL, updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId AND page_index = :pageIndex AND status = 'FAILED'
        """,
    )
    suspend fun skipPage(jobId: String, pageIndex: Int, updatedAtEpochMillis: Long): Int

    @Query(
        """
        UPDATE batch_import_page
        SET status = 'QUEUED', updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId AND status = 'IMPORTING'
        """,
    )
    suspend fun requeueInterruptedPages(jobId: String, updatedAtEpochMillis: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM batch_import_page
        WHERE job_id = :jobId AND status IN ('QUEUED', 'IMPORTING')
        """,
    )
    suspend fun countActivePages(jobId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM batch_import_page
        WHERE source_uri = :sourceUri AND status IN ('QUEUED', 'IMPORTING', 'FAILED')
        """,
    )
    suspend fun countRetainedSourceUri(sourceUri: String): Int
}

internal data class BatchImportJobWithPages(
    @Embedded
    val job: BatchImportJobEntity,
    @Relation(
        parentColumns = ["job_id"],
        entityColumns = ["job_id"],
    )
    val pages: List<BatchImportPageEntity>,
)
