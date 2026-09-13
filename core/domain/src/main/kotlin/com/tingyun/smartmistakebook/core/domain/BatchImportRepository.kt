package com.tingyun.smartmistakebook.core.domain

import kotlinx.coroutines.flow.Flow

enum class BatchImportStatus {
    PROCESSING,
    PAUSED,
    COMPLETED,
}

enum class BatchImportPageStatus {
    QUEUED,
    IMPORTING,
    READY,
    FAILED,
    SKIPPED,
}

enum class BatchImportBoundaryStatus {
    PENDING,
    CHECKING,
    SAME_QUESTION,
    NEXT_QUESTION,
    KEPT_SEPARATE,
    FAILED,
}

data class BatchImportPage(
    val pageIndex: Int,
    val status: BatchImportPageStatus,
    val draftId: String?,
    val failureCode: String?,
    val attemptCount: Int,
    val updatedAtEpochMillis: Long,
    val boundaryAfterStatus: BatchImportBoundaryStatus = BatchImportBoundaryStatus.PENDING,
) {
    init {
        require(pageIndex >= 0) { "Batch page index must not be negative" }
        require(draftId == null || draftId.isNotBlank()) { "Batch draft id must be non-blank" }
        require(failureCode == null || failureCode.isNotBlank()) {
            "Batch failure code must be non-blank"
        }
        require(attemptCount >= 0) { "Batch attempt count must not be negative" }
        require(updatedAtEpochMillis >= 0) { "Batch page update time must not be negative" }
        require((status == BatchImportPageStatus.READY) == (draftId != null)) {
            "Only a ready batch page may expose a draft"
        }
        require((status == BatchImportPageStatus.FAILED) == (failureCode != null)) {
            "Only a failed batch page may expose a failure code"
        }
    }
}

data class BatchImportJob(
    val jobId: String,
    val status: BatchImportStatus,
    val pages: List<BatchImportPage>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(jobId.isNotBlank()) { "Batch job id must not be blank" }
        require(pages.size in 2..MAX_BATCH_IMPORT_PAGES) {
            "Batch import must contain between 2 and $MAX_BATCH_IMPORT_PAGES pages"
        }
        require(pages.map { it.pageIndex } == pages.indices.toList()) {
            "Batch import pages must be ordered and contiguous"
        }
        require(createdAtEpochMillis >= 0) { "Batch creation time must not be negative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Batch update time cannot precede creation"
        }
        if (status == BatchImportStatus.COMPLETED) {
            require(pages.none { it.status in ACTIVE_PAGE_STATES }) {
                "A completed batch cannot contain active pages"
            }
        }
    }

    val readyCount: Int get() = pages.count { it.status == BatchImportPageStatus.READY }
    val failedCount: Int get() = pages.count { it.status == BatchImportPageStatus.FAILED }
    val skippedCount: Int get() = pages.count { it.status == BatchImportPageStatus.SKIPPED }
    val remainingCount: Int get() = pages.count { it.status in ACTIVE_PAGE_STATES }
    val remainingBoundaryCount: Int
        get() = pages.zipWithNext().count { (page, following) ->
            page.status == BatchImportPageStatus.READY &&
                following.status == BatchImportPageStatus.READY &&
                (
                    page.boundaryAfterStatus == BatchImportBoundaryStatus.PENDING ||
                        page.boundaryAfterStatus == BatchImportBoundaryStatus.CHECKING ||
                        page.boundaryAfterStatus == BatchImportBoundaryStatus.FAILED
                    )
        }
    val questionCount: Int
        get() = pages.mapNotNull(BatchImportPage::draftId).distinct().size

    private companion object {
        val ACTIVE_PAGE_STATES = setOf(
            BatchImportPageStatus.QUEUED,
            BatchImportPageStatus.IMPORTING,
        )
    }
}

data class CreateBatchImportRequest(
    val requestId: String,
    val localUris: List<String>,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "Batch request id must not be blank" }
        require(localUris.size in 2..MAX_BATCH_IMPORT_PAGES) {
            "Batch import must contain between 2 and $MAX_BATCH_IMPORT_PAGES pages"
        }
        require(localUris.all(String::isNotBlank)) { "Batch import URI must not be blank" }
        require(localUris.distinct().size == localUris.size) {
            "Batch import cannot contain duplicate URI entries"
        }
        require(occurredAtEpochMillis >= 0) { "Batch import time must not be negative" }
    }
}

data class CreatePdfImportRequest(
    val requestId: String,
    val localUri: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "PDF import request id must not be blank" }
        require(localUri.isNotBlank()) { "PDF import URI must not be blank" }
        require(occurredAtEpochMillis >= 0) { "PDF import time must not be negative" }
    }
}

interface BatchImportRepository {
    fun observeBatchImports(): Flow<List<BatchImportJob>>

    suspend fun createBatchImport(request: CreateBatchImportRequest): BatchImportJob

    suspend fun createPdfImport(request: CreatePdfImportRequest): BatchImportJob

    suspend fun pauseBatchImport(jobId: String)

    suspend fun resumeBatchImport(jobId: String)

    suspend fun retryBatchImportPage(jobId: String, pageIndex: Int)

    suspend fun skipBatchImportPage(jobId: String, pageIndex: Int)

    /**
     * Organizes adjacent saved page boundaries with the configured model. A configured,
     * image-capable, structured-output external provider dispatches immediately; with no model
     * configured it fails closed (the caller surfaces a settings CTA). There is no separate
     * approval step: configuring the model is itself the grant.
     */
    suspend fun organizeBatch(jobId: String)
}

/** Thrown when batch organization needs a model and none is configured. */
class BatchOrganizationUnavailableException : IllegalStateException(
    "Batch page organization requires a configured model provider",
)

const val MAX_BATCH_IMPORT_PAGES = 30
