package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BatchImportRepositoryTest {
    @Test
    fun completedBatchMayContainIndependentFailuresButNoActivePages() {
        val job = BatchImportJob(
            jobId = "batch-1",
            status = BatchImportStatus.COMPLETED,
            pages = listOf(
                page(0, BatchImportPageStatus.READY, draftId = "draft-1"),
                page(1, BatchImportPageStatus.SKIPPED),
                page(2, BatchImportPageStatus.FAILED, failureCode = "IMPORT_FAILED"),
            ),
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )

        assertEquals(1, job.readyCount)
        assertEquals(1, job.failedCount)
        assertEquals(1, job.skippedCount)
        assertEquals(0, job.remainingCount)
    }

    @Test
    fun completedBatchRejectsAQueuedPage() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchImportJob(
                jobId = "batch-1",
                status = BatchImportStatus.COMPLETED,
                pages = listOf(
                    page(0, BatchImportPageStatus.READY, draftId = "draft-1"),
                    page(1, BatchImportPageStatus.QUEUED),
                ),
                createdAtEpochMillis = 10,
                updatedAtEpochMillis = 20,
            )
        }
    }

    @Test
    fun requestRejectsDuplicateOrSinglePhotoSelections() {
        assertThrows(IllegalArgumentException::class.java) {
            CreateBatchImportRequest("request", listOf("content://one"), 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CreateBatchImportRequest(
                "request",
                listOf("content://same", "content://same"),
                10,
            )
        }
    }

    @Test
    fun pdfRequestRequiresOneLocalDocumentReference() {
        val request = CreatePdfImportRequest("pdf-request", "content://document/exam", 10)

        assertEquals("content://document/exam", request.localUri)
        assertThrows(IllegalArgumentException::class.java) {
            CreatePdfImportRequest("pdf-request", "", 10)
        }
    }

    private fun page(
        index: Int,
        status: BatchImportPageStatus,
        draftId: String? = null,
        failureCode: String? = null,
    ) = BatchImportPage(
        pageIndex = index,
        status = status,
        draftId = draftId,
        failureCode = failureCode,
        attemptCount = 1,
        updatedAtEpochMillis = 20,
    )
}
