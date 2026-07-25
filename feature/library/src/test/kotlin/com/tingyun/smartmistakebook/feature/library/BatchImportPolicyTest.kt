package com.tingyun.smartmistakebook.feature.library

import com.tingyun.smartmistakebook.core.domain.BatchImportJob
import com.tingyun.smartmistakebook.core.domain.BatchImportPage
import com.tingyun.smartmistakebook.core.domain.BatchImportPageStatus
import com.tingyun.smartmistakebook.core.domain.BatchImportStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchImportPolicyTest {
    @Test
    fun completedPartialBatchUsesRecoverableHumanLanguage() {
        val job = BatchImportJob(
            jobId = "batch-1",
            status = BatchImportStatus.COMPLETED,
            pages = listOf(
                page(0, BatchImportPageStatus.READY, "draft-1"),
                page(1, BatchImportPageStatus.FAILED),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        assertEquals("其余页面已保存", batchImportTitle(job))
        assertEquals("这一页没有保存", batchImportPageLabel(job.pages.last().status))
    }

    @Test
    fun skippedPagesCountAsFinishedProgress() {
        val job = BatchImportJob(
            jobId = "batch-progress",
            status = BatchImportStatus.PROCESSING,
            pages = listOf(
                page(0, BatchImportPageStatus.READY, "draft-1"),
                page(1, BatchImportPageStatus.SKIPPED),
                page(2, BatchImportPageStatus.FAILED),
                page(3, BatchImportPageStatus.QUEUED),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        assertEquals(3, batchImportCompletedCount(job))
    }

    private fun page(
        index: Int,
        status: BatchImportPageStatus,
        draftId: String? = null,
    ) = BatchImportPage(
        pageIndex = index,
        status = status,
        draftId = draftId,
        failureCode = if (status == BatchImportPageStatus.FAILED) "IMPORT_FAILED" else null,
        attemptCount = 1,
        updatedAtEpochMillis = 2,
    )
}
