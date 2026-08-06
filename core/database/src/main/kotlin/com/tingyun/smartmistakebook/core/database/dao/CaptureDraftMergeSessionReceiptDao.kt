package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.database.entity.CaptureDraftMergeSessionReceiptEntity

@Dao
internal interface CaptureDraftMergeSessionReceiptDao {
    @Query(
        """
        SELECT *
        FROM capture_draft_merge_session_receipt
        WHERE batch_job_id = :batchJobId
          AND batch_page_index = :batchPageIndex
        """,
    )
    suspend fun read(
        batchJobId: String,
        batchPageIndex: Int,
    ): CaptureDraftMergeSessionReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(
        receipt: CaptureDraftMergeSessionReceiptEntity,
    ): Long
}
