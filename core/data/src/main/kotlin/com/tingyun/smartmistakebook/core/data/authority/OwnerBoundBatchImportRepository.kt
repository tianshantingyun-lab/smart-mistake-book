package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.domain.BatchImportJob
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationApproval
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationOffer
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CreateBatchImportRequest
import com.tingyun.smartmistakebook.core.domain.CreatePdfImportRequest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Revocable repository view whose lifetime is owned by [ProductionBatchImportOwner]. */
private class OwnerBoundBatchImportRepository(
    private val repositoryLease: ProductionBatchImportRepositoryLease,
) : BatchImportRepository {
    override fun observeBatchImports(): Flow<List<BatchImportJob>> =
        channelFlow {
            val access = ProductionBatchImportOwnerRegistry.activeAccess(repositoryLease)
            val upstream =
                launch(start = CoroutineStart.LAZY) {
                    access.operations().observeBatchImports().collect { jobs -> send(jobs) }
                }
            val closeHandle =
                access.invokeOnClose {
                    upstream.cancel(
                        CancellationException("Batch-import owner was closed"),
                    )
                    channel.close()
                }
            upstream.start()
            awaitClose {
                closeHandle.dispose()
                upstream.cancel()
            }
        }

    override suspend fun createBatchImport(request: CreateBatchImportRequest): BatchImportJob =
        withActiveOperation { operations -> operations.createBatchImport(request) }

    override suspend fun createPdfImport(request: CreatePdfImportRequest): BatchImportJob =
        withActiveOperation { operations -> operations.createPdfImport(request) }

    override suspend fun recoverInterruptedBatchImportWork() {
        withActiveOperation { operations -> operations.recoverInterruptedBatchImportWork() }
    }

    override suspend fun pauseBatchImport(jobId: String) {
        withActiveOperation { operations -> operations.pauseBatchImport(jobId) }
    }

    override suspend fun resumeBatchImport(jobId: String) {
        withActiveOperation { operations -> operations.resumeBatchImport(jobId) }
    }

    override suspend fun retryBatchImportPage(
        jobId: String,
        pageIndex: Int,
    ) {
        withActiveOperation { operations -> operations.retryBatchImportPage(jobId, pageIndex) }
    }

    override suspend fun skipBatchImportPage(
        jobId: String,
        pageIndex: Int,
    ) {
        withActiveOperation { operations -> operations.skipBatchImportPage(jobId, pageIndex) }
    }

    override suspend fun prepareOrganization(jobId: String): BatchImportOrganizationOffer =
        withActiveOperation { operations -> operations.prepareOrganization(jobId) }

    override suspend fun organizeBatch(approval: BatchImportOrganizationApproval) {
        withActiveOperation { operations -> operations.organizeBatch(approval) }
    }

    private suspend fun <Value> withActiveOperation(
        operation: suspend (BatchImportRepository) -> Value,
    ): Value =
        coroutineScope {
            val access = ProductionBatchImportOwnerRegistry.activeAccess(repositoryLease)
            val operationJob = currentCoroutineContext().job
            val ownerCloseHandle =
                access.invokeOnClose {
                    operationJob.cancel(
                        CancellationException("Batch-import owner was closed"),
                    )
                }
            try {
                currentCoroutineContext().ensureActive()
                operation(access.operations()).also { requireActive() }
            } finally {
                ownerCloseHandle.dispose()
            }
        }

    private fun requireActive() {
        check(ProductionBatchImportOwnerRegistry.isActive(repositoryLease)) {
            "Batch-import owner is closed"
        }
    }
}
