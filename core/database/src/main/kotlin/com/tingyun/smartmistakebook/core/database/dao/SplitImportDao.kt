package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.entity.SplitImportJobEntity
import com.tingyun.smartmistakebook.core.database.entity.SplitImportQuestionEntity
import kotlinx.coroutines.flow.Flow

internal data class SplitImportQuestionRow(
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "question_ordinal")
    val questionOrdinal: Int,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,
    @ColumnInfo(name = "region_left")
    val regionLeft: Double,
    @ColumnInfo(name = "region_top")
    val regionTop: Double,
    @ColumnInfo(name = "region_right")
    val regionRight: Double,
    @ColumnInfo(name = "region_bottom")
    val regionBottom: Double,
    @ColumnInfo(name = "selected")
    val selected: Boolean,
    @ColumnInfo(name = "confirm_state")
    val confirmState: String,
    @ColumnInfo(name = "split_draft_id")
    val splitDraftId: String?,
)

internal data class SplitImportJobWithQuestions(
    @Embedded
    val job: SplitImportJobEntity,
    @Relation(
        parentColumns = ["job_id"],
        entityColumns = ["job_id"],
    )
    val questions: List<SplitImportQuestionEntity>,
)

@Dao
internal interface SplitImportDao {
    @Transaction
    @Query(
        """
        SELECT * FROM split_import_job
        WHERE status IN ('PREPARING', 'READY')
        ORDER BY updated_at_epoch_millis DESC, job_id DESC
        """,
    )
    fun observeActiveJobs(): Flow<List<SplitImportJobWithQuestions>>

    @Transaction
    @Query("SELECT * FROM split_import_job WHERE job_id = :jobId")
    suspend fun readJobWithQuestions(jobId: String): SplitImportJobWithQuestions?

    @Query("SELECT * FROM split_import_job WHERE job_id = :jobId")
    suspend fun readJob(jobId: String): SplitImportJobEntity?

    @Query(
        """
        SELECT * FROM split_import_job
        WHERE source_kind = 'BATCH' AND status = 'READY'
          AND job_id LIKE 'split:' || :batchJobId || ':%'
        ORDER BY updated_at_epoch_millis DESC, job_id DESC
        LIMIT 1
        """,
    )
    suspend fun readLatestReadyBatchSplitJob(batchJobId: String): SplitImportJobEntity?

    @Query("SELECT * FROM split_import_job WHERE source_fingerprint = :sourceFingerprint")
    suspend fun readJobByFingerprint(sourceFingerprint: String): SplitImportJobEntity?

    @Query("SELECT * FROM split_import_question WHERE job_id = :jobId ORDER BY question_ordinal")
    suspend fun readQuestions(jobId: String): List<SplitImportQuestionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: SplitImportJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQuestions(questions: List<SplitImportQuestionEntity>)

    @Query(
        """
        UPDATE split_import_job
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
        UPDATE split_import_question
        SET selected = :selected
        WHERE job_id = :jobId AND question_ordinal = :questionOrdinal
        """,
    )
    suspend fun updateSelected(
        jobId: String,
        questionOrdinal: Int,
        selected: Boolean,
    ): Int

    @Query(
        """
        UPDATE split_import_question
        SET confirm_state = :confirmState, split_draft_id = :splitDraftId
        WHERE job_id = :jobId AND question_ordinal = :questionOrdinal
        """,
    )
    suspend fun updateConfirmState(
        jobId: String,
        questionOrdinal: Int,
        confirmState: String,
        splitDraftId: String?,
    ): Int

    @Query(
        """
        UPDATE split_import_job
        SET question_count = :questionCount, status = 'READY',
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId AND status = 'PREPARING'
        """,
    )
    suspend fun markReady(
        jobId: String,
        questionCount: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE split_import_job
        SET updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE job_id = :jobId
        """,
    )
    suspend fun touchJob(jobId: String, updatedAtEpochMillis: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM split_import_question
        WHERE job_id = :jobId AND selected = 1 AND confirm_state = 'PENDING'
        """,
    )
    suspend fun countSelectedPending(jobId: String): Int
}