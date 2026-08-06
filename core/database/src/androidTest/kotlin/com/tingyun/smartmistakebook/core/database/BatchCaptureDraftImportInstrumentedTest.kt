package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.entity.CaptureDraftBatchImportReceiptEntity
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchCaptureDraftImportInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: RoomStudyDatabase

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
    fun exactPageReplayReturnsItsImmutableReceiptAndDifferentPayloadConflicts(): Unit =
        runBlocking {
            val command = importCommand(jobId = "batch-replay", pageIndex = 0, draftId = "draft-a")

            val created = store.importOrReuseBatchDraft(command)
            val replay = store.importOrReuseBatchDraft(command)

            assertEquals(CaptureDraftBatchImportDisposition.CREATED, created.disposition)
            assertEquals(created, replay)
            assertEquals(
                created,
                store.readBatchCaptureDraftReceipt("batch-replay", 0),
            )
            assertThrows(ImmutablePayloadConflictException::class.java) {
                runBlocking {
                    store.importOrReuseBatchDraft(
                        importCommand(
                            jobId = "batch-replay",
                            pageIndex = 0,
                            draftId = "draft-different",
                        ),
                    )
                }
            }
    }

    @Test
    fun opaqueBatchNamespaceDoesNotRequireCoordinatorRows(): Unit = runBlocking {
        val namespace = "capture-owned-before-coordinator"
        assertNull(store.readBatchImportJob(namespace))

        val receipt =
            store.importOrReuseBatchDraft(
                importCommand(jobId = namespace, pageIndex = 499, draftId = "draft-opaque"),
            )

        assertEquals(namespace, receipt.batchJobId)
        assertEquals(499, receipt.batchPageIndex)
        assertEquals(CaptureDraftBatchImportDisposition.CREATED, receipt.disposition)
        assertNull(store.readBatchImportJob(namespace))
    }

    @Test
    fun sameContentWithinOneBatchReusesOnlyTheWinningDraft(): Unit = runBlocking {
        val first =
            store.importOrReuseBatchDraft(
                importCommand(jobId = "batch-same", pageIndex = 0, draftId = "draft-first"),
            )
        val second =
            store.importOrReuseBatchDraft(
                importCommand(jobId = "batch-same", pageIndex = 1, draftId = "draft-second"),
            )

        assertEquals(CaptureDraftBatchImportDisposition.CREATED, first.disposition)
        assertEquals(CaptureDraftBatchImportDisposition.REUSED_EXACT_CONTENT, second.disposition)
        assertEquals(first.originalDraftId, second.originalDraftId)
        assertEquals(first.sourceAssetId, second.sourceAssetId)
        assertNull(store.readProblemDraft("draft-second"))
    }

    @Test
    fun identicalContentInDifferentBatchesCreatesDifferentDrafts(): Unit = runBlocking {
        val first =
            store.importOrReuseBatchDraft(
                importCommand(jobId = "batch-a", pageIndex = 0, draftId = "draft-a"),
            )
        val second =
            store.importOrReuseBatchDraft(
                importCommand(jobId = "batch-b", pageIndex = 0, draftId = "draft-b"),
            )

        assertEquals(CaptureDraftBatchImportDisposition.CREATED, first.disposition)
        assertEquals(CaptureDraftBatchImportDisposition.CREATED, second.disposition)
        assertNotEquals(first.originalDraftId, second.originalDraftId)
        assertEquals(first.sourceAssetId, second.sourceAssetId)
    }

    @Test
    fun concurrentExactContentImportsCreateOneWinner(): Unit = runBlocking {
        val commands =
            (0 until 12).map { pageIndex ->
                importCommand(
                    jobId = "batch-concurrent",
                    pageIndex = pageIndex,
                    draftId = "draft-concurrent-$pageIndex",
                )
            }

        val receipts =
            coroutineScope {
                commands.map { command ->
                    async(Dispatchers.Default) {
                        store.importOrReuseBatchDraft(command)
                    }
                }.awaitAll()
            }

        assertEquals(
            1,
            receipts.count { it.disposition == CaptureDraftBatchImportDisposition.CREATED },
        )
        assertEquals(1, receipts.map { it.originalDraftId }.distinct().size)
        assertEquals(
            1,
            commands.count { store.readProblemDraft(it.request.draftId) != null },
        )
    }

    @Test
    fun failuresInsideTheTransactionCanBeReplayedWithoutPartialState(): Unit = runBlocking {
        val failurePoints =
            listOf<(StudyDatabase) -> RoomBatchCaptureDraftImportStore>(
                { database ->
                    RoomBatchCaptureDraftImportStore(
                        database = database,
                        afterBindingInserted = { throw InjectedImportFailure },
                    )
                },
                { database ->
                    RoomBatchCaptureDraftImportStore(
                        database = database,
                        beforeReceiptInserted = { throw InjectedImportFailure },
                    )
                },
            )
        failurePoints.forEachIndexed { index, failingStoreFactory ->
            val command =
                importCommand(
                    jobId = "batch-failure-$index",
                    pageIndex = 0,
                    draftId = "draft-failure-$index",
                    contentHashCharacter = ('c'.code + index).toChar(),
                )
            assertThrows(InjectedImportFailure::class.java) {
                runBlocking {
                    failingStoreFactory(store.database).importOrReuse(command)
                }
            }
            assertNull(store.readBatchCaptureDraftReceipt(command.batchJobId, 0))
            assertNull(store.readProblemDraft(command.request.draftId))

            val replay = store.importOrReuseBatchDraft(command)
            assertEquals(CaptureDraftBatchImportDisposition.CREATED, replay.disposition)
        }
    }

    @Test
    fun fiveHundredReceiptsAreReadOnceInStablePages(): Unit = runBlocking {
        store.database.withWriteTransaction {
            val dao = store.database.batchCaptureDraftImportDao()
            repeat(500) { pageIndex ->
                dao.insertReceipt(paginationReceipt(pageIndex))
            }
        }

        val seen = mutableListOf<CaptureDraftBatchImportReceipt>()
        var cursor = 0L
        while (true) {
            val page =
                store.readBatchCaptureDraftReceipts(
                    batchJobId = PAGINATION_JOB_ID,
                    afterReceiptSequenceExclusive = cursor,
                    limit = 37,
                )
            seen += page.receipts
            cursor = page.nextReceiptSequenceExclusive ?: break
        }

        assertEquals(500, seen.size)
        assertEquals((0 until 500).toList(), seen.map { it.batchPageIndex })
        assertEquals(
            seen[321],
            store.readBatchCaptureDraftReceipt(PAGINATION_JOB_ID, 321),
        )
    }

    @Test
    fun receiptSequenceCursorDoesNotMissLateLowerPageIndexes(): Unit = runBlocking {
        store.database.withWriteTransaction {
            val dao = store.database.batchCaptureDraftImportDao()
            (50 until 150).forEach { pageIndex ->
                dao.insertReceipt(paginationReceipt(pageIndex))
            }
        }
        val first =
            store.readBatchCaptureDraftReceipts(
                batchJobId = PAGINATION_JOB_ID,
                afterReceiptSequenceExclusive = 0,
                limit = 50,
            )
        store.database.withWriteTransaction {
            val dao = store.database.batchCaptureDraftImportDao()
            (0 until 50).forEach { pageIndex ->
                dao.insertReceipt(paginationReceipt(pageIndex))
            }
        }

        val seen = first.receipts.toMutableList()
        var cursor = checkNotNull(first.nextReceiptSequenceExclusive)
        while (true) {
            val page =
                store.readBatchCaptureDraftReceipts(
                    batchJobId = PAGINATION_JOB_ID,
                    afterReceiptSequenceExclusive = cursor,
                    limit = 50,
                )
            seen += page.receipts
            cursor = page.nextReceiptSequenceExclusive ?: break
        }

        assertEquals(150, seen.size)
        assertEquals((0 until 150).toSet(), seen.map { it.batchPageIndex }.toSet())
    }

    private fun importCommand(
        jobId: String,
        pageIndex: Int,
        draftId: String,
        contentHashCharacter: Char = 'a',
    ): ImportOrReuseBatchCaptureDraftCommand {
        val sourceAssetId = "asset-${contentHashCharacter}"
        val document =
            CapturedQuestionDocument(
                document =
                    QuestionDocument(
                        id = "document-$draftId",
                        title = "待校对题目",
                        blocks = listOf(ContentBlock.Paragraph("stem", "等待转写")),
                    ),
                blockEvidence =
                    listOf(
                        QuestionBlockEvidence(
                            blockId = "stem",
                            sourceAssetId = sourceAssetId,
                            sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                            writingLayer = WritingLayer.UNKNOWN,
                            provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
                            confidence = null,
                            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                            producerVersion = "batch-capture-import-test-v1",
                        ),
                    ),
            )
        val contentSha256 = contentHashCharacter.toString().repeat(64)
        val revision =
            ProblemDraftRevisionRecord(
                draftId = draftId,
                revisionNumber = 1,
                basisRevisionNumber = null,
                subject = null,
                title = "待校对题目",
                questionDocument = document,
                documentFingerprint = CapturedQuestionDocumentFingerprint.of(document),
                author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
                createdAtEpochMillis = 1_000L + pageIndex,
            )
        return ImportOrReuseBatchCaptureDraftCommand(
            batchJobId = jobId,
            batchPageIndex = pageIndex,
            request =
                CreateProblemDraftCommand(
                    sourceAsset =
                        CanonicalSourceAssetRecord(
                            sourceAssetId = sourceAssetId,
                            contentSha256 = contentSha256,
                            relativePath = "source-assets/$contentSha256.jpg",
                            mimeType = "image/jpeg",
                            byteSize = 1_024,
                            width = 800,
                            height = 1_200,
                            sourceType = StudyDbValue.SourceAssetType.PHOTO_PICKER,
                            createdAtEpochMillis = 1_000,
                        ),
                    draftId = draftId,
                    origin = StudyDbValue.CaptureOrigin.LIBRARY,
                    initialRevision = revision,
                ),
        )
    }

    private fun paginationReceipt(pageIndex: Int): CaptureDraftBatchImportReceiptEntity {
        val receiptReference =
            batchCaptureDraftReceiptReference(
                batchJobId = PAGINATION_JOB_ID,
                batchPageIndex = pageIndex,
            )
        val requestFingerprint =
            CanonicalSha256("batch-capture-pagination-request-v1")
                .field("pageIndex", pageIndex)
                .finish()
        val disposition =
            if (pageIndex == 0) {
                CaptureDraftBatchImportDisposition.CREATED
            } else {
                CaptureDraftBatchImportDisposition.REUSED_EXACT_CONTENT
            }
        val receiptFingerprint =
            batchCaptureDraftReceiptFingerprint(
                receiptReference = receiptReference,
                batchJobId = PAGINATION_JOB_ID,
                batchPageIndex = pageIndex,
                requestFingerprint = requestFingerprint,
                originalDraftId = PAGINATION_DRAFT_ID,
                sourceAssetId = PAGINATION_ASSET_ID,
                disposition = disposition,
                importedAtEpochMillis = pageIndex.toLong(),
            )
        return CaptureDraftBatchImportReceiptEntity(
            receiptReference = receiptReference,
            batchJobId = PAGINATION_JOB_ID,
            batchPageIndex = pageIndex,
            requestFingerprint = requestFingerprint,
            originalDraftId = PAGINATION_DRAFT_ID,
            sourceAssetId = PAGINATION_ASSET_ID,
            disposition = disposition.name,
            importedAtEpochMillis = pageIndex.toLong(),
            receiptFingerprint = receiptFingerprint,
        )
    }

    private object InjectedImportFailure : RuntimeException()

    private companion object {
        const val PAGINATION_JOB_ID = "batch-pagination"
        const val PAGINATION_DRAFT_ID = "draft-pagination"
        const val PAGINATION_ASSET_ID = "asset-pagination"
    }
}
