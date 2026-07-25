package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchImportDatabaseInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: StudyDatabasePort

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = StudyDatabaseFactory.openInMemory(context)
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun batchPagesPauseRecoverAndSettleIndependently() = runBlocking {
        val created = store.createBatchImportJob(command())
        assertEquals(created, store.createBatchImportJob(command()))
        assertTrue(
            store.updateBatchImportJobStatus(
                created.jobId,
                StudyDbValue.BatchImportStatus.PROCESSING,
                StudyDbValue.BatchImportStatus.PAUSED,
                2_000,
            ),
        )
        assertNull(store.claimNextBatchImportPage(created.jobId, 2_100))
        assertTrue(
            store.updateBatchImportJobStatus(
                created.jobId,
                StudyDbValue.BatchImportStatus.PAUSED,
                StudyDbValue.BatchImportStatus.PROCESSING,
                2_200,
            ),
        )

        val interrupted = checkNotNull(store.claimNextBatchImportPage(created.jobId, 2_300))
        assertEquals(0, interrupted.pageIndex)
        assertEquals(1, store.requeueInterruptedBatchImportPages(created.jobId, 2_400))
        val reclaimed = checkNotNull(store.claimNextBatchImportPage(created.jobId, 2_500))
        assertEquals(2, reclaimed.attemptCount)
        assertTrue(store.failBatchImportPage(created.jobId, 0, "SOURCE_NOT_READABLE", 2_600))
        assertTrue(store.skipBatchImportPage(created.jobId, 0, 2_700))

        for (pageIndex in 1..2) {
            assertEquals(pageIndex, store.claimNextBatchImportPage(created.jobId, 3_000L + pageIndex)?.pageIndex)
            assertTrue(
                store.failBatchImportPage(
                    created.jobId,
                    pageIndex,
                    "IMPORT_FAILED",
                    4_000L + pageIndex,
                ),
            )
        }
        assertTrue(store.finishBatchImportIfSettled(created.jobId, 5_000))
        assertFalse(store.finishBatchImportIfSettled(created.jobId, 5_100))

        val completed = store.observeBatchImportJobs().first().single()
        assertEquals(StudyDbValue.BatchImportStatus.COMPLETED, completed.status)
        assertEquals(3, completed.pages.size)
        assertEquals(StudyDbValue.BatchImportPageStatus.SKIPPED, completed.pages.first().status)
        assertTrue(completed.pages.drop(1).all {
            it.status == StudyDbValue.BatchImportPageStatus.FAILED
        })
    }

    @Test
    fun pauseDuringTheLastPageStillSettlesAsCompleted() = runBlocking {
        val created = store.createBatchImportJob(
            command().copy(
                jobId = "batch-last-page",
                requestId = "request-last-page",
                requestFingerprint = "b".repeat(64),
                sourceUris = listOf("content://last/1", "content://last/2"),
            ),
        )
        checkNotNull(store.claimNextBatchImportPage(created.jobId, 2_000))
        assertTrue(store.failBatchImportPage(created.jobId, 0, "IMPORT_FAILED", 2_100))
        checkNotNull(store.claimNextBatchImportPage(created.jobId, 2_200))
        assertTrue(
            store.updateBatchImportJobStatus(
                created.jobId,
                StudyDbValue.BatchImportStatus.PROCESSING,
                StudyDbValue.BatchImportStatus.PAUSED,
                2_300,
            ),
        )
        assertTrue(store.failBatchImportPage(created.jobId, 1, "IMPORT_FAILED", 2_400))

        assertTrue(store.finishBatchImportIfSettled(created.jobId, 2_500))
        assertEquals(
            StudyDbValue.BatchImportStatus.COMPLETED,
            checkNotNull(store.readBatchImportJob(created.jobId)).status,
        )
    }

    @Test
    fun failedPageRetryResumesCompletedAndPausedJobsInTheSameWrite() = runBlocking {
        val completed = createFailedJob("completed-retry", StudyDbValue.BatchImportStatus.COMPLETED)
        assertTrue(store.retryBatchImportPage(completed.jobId, 0, 3_000))
        assertProcessingWithQueuedPage(completed.jobId, 0)

        val paused = createFailedJob("paused-retry", StudyDbValue.BatchImportStatus.PAUSED)
        assertTrue(store.retryBatchImportPage(paused.jobId, 0, 4_000))
        assertProcessingWithQueuedPage(paused.jobId, 0)
    }

    @Test
    fun continuousCollectorNeverSeesACompletedJobWithAnActivePage() = runBlocking {
        val invalidSnapshots = Collections.synchronizedList(mutableListOf<BatchImportJobRecord>())
        val collector = launch {
            store.observeBatchImportJobs().collect { jobs ->
                invalidSnapshots += jobs.filter { job ->
                    job.status == StudyDbValue.BatchImportStatus.COMPLETED &&
                        job.pages.any { page ->
                            page.status == StudyDbValue.BatchImportPageStatus.QUEUED ||
                                page.status == StudyDbValue.BatchImportPageStatus.IMPORTING
                        }
                }
            }
        }
        try {
            yield()
            val completed = createFailedJob(
                "continuous-collector",
                StudyDbValue.BatchImportStatus.COMPLETED,
            )
            assertTrue(store.retryBatchImportPage(completed.jobId, 0, 5_000))
            withTimeout(5_000) {
                store.observeBatchImportJobs().first { jobs ->
                    jobs.any { job ->
                        job.jobId == completed.jobId &&
                            job.status == StudyDbValue.BatchImportStatus.PROCESSING &&
                            job.pages.first().status == StudyDbValue.BatchImportPageStatus.QUEUED
                    }
                }
            }
            yield()
        } finally {
            collector.cancelAndJoin()
        }

        assertTrue(invalidSnapshots.isEmpty())
    }

    @Test
    fun retryAndSettlementRaceCannotLeaveCompletedWithQueuedWork() = runBlocking {
        repeat(20) { iteration ->
            val completed = createFailedJob(
                "retry-race-$iteration",
                StudyDbValue.BatchImportStatus.COMPLETED,
            )
            val retry = launch {
                store.retryBatchImportPage(completed.jobId, 0, 7_000L + iteration)
            }
            val settle = launch {
                store.finishBatchImportIfSettled(completed.jobId, 8_000L + iteration)
            }
            retry.join()
            settle.join()

            assertProcessingWithQueuedPage(completed.jobId, 0)
        }
    }

    @Test
    fun versionTenDatabaseMigratesAndAcceptsBatchJobs() = runBlocking {
        store.close()
        val databaseName = "batch-import-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 10)
            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val created = migrated.createBatchImportJob(command())
            assertEquals(created, migrated.observeBatchImportJobs().first().single())
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    private fun command() = CreateBatchImportJobCommand(
        jobId = "batch-1",
        requestId = "request-1",
        requestFingerprint = "a".repeat(64),
        sourceUris = listOf("content://page/1", "content://page/2", "content://page/3"),
        occurredAtEpochMillis = 1_000,
    )

    private suspend fun createFailedJob(
        suffix: String,
        finalStatus: String,
    ): BatchImportJobRecord {
        val created = store.createBatchImportJob(
            command().copy(
                jobId = "batch-$suffix",
                requestId = "request-$suffix",
                requestFingerprint = suffix.hashCode().toUInt().toString(16).padStart(64, '0'),
                sourceUris = listOf("content://$suffix/1", "content://$suffix/2"),
            ),
        )
        repeat(2) { pageIndex ->
            assertEquals(
                pageIndex,
                store.claimNextBatchImportPage(created.jobId, 2_000L + pageIndex)?.pageIndex,
            )
            assertTrue(
                store.failBatchImportPage(
                    created.jobId,
                    pageIndex,
                    "IMPORT_FAILED",
                    2_100L + pageIndex,
                ),
            )
        }
        when (finalStatus) {
            StudyDbValue.BatchImportStatus.COMPLETED ->
                assertTrue(store.finishBatchImportIfSettled(created.jobId, 2_500))
            StudyDbValue.BatchImportStatus.PAUSED ->
                assertTrue(
                    store.updateBatchImportJobStatus(
                        created.jobId,
                        StudyDbValue.BatchImportStatus.PROCESSING,
                        StudyDbValue.BatchImportStatus.PAUSED,
                        2_500,
                    ),
                )
            else -> error("Unsupported fixture status")
        }
        return checkNotNull(store.readBatchImportJob(created.jobId))
    }

    private suspend fun assertProcessingWithQueuedPage(jobId: String, pageIndex: Int) {
        val retried = checkNotNull(store.readBatchImportJob(jobId))
        assertEquals(StudyDbValue.BatchImportStatus.PROCESSING, retried.status)
        assertEquals(
            StudyDbValue.BatchImportPageStatus.QUEUED,
            retried.pages.single { it.pageIndex == pageIndex }.status,
        )
    }
}
