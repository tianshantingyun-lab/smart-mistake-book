package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CaptureMergeSessionReceiptReference
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchImportSessionBoundaryInstrumentedTest {
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
    fun sameQuestionResolutionIsNaturallyIdempotent(): Unit = runBlocking {
        prepareBoundary(claimBoundary = true)
        mergeCaptureDrafts()
        val command = resolutionCommand()

        val first = store.recordBatchImportBoundarySessionResolution(command)
        val replay = store.recordBatchImportBoundarySessionResolution(command)

        assertEquals(first, replay)
        assertEquals(null, replay.pages[0].boundaryClaimedAtEpochMillis)
        assertEquals(
            StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION,
            replay.pages[0].boundaryAfterStatus,
        )
        assertEquals(PRIMARY_DRAFT_ID, replay.pages[0].resultDraftId)
        assertEquals(PRIMARY_DRAFT_ID, replay.pages[1].resultDraftId)

        assertThrows(ImmutablePayloadConflictException::class.java) {
            runBlocking {
                store.recordBatchImportBoundarySessionResolution(
                    command.copy(
                        boundaryClaimedAtEpochMillis = command.boundaryClaimedAtEpochMillis + 1,
                    ),
                )
            }
        }
        assertThrows(ImmutablePayloadConflictException::class.java) {
            runBlocking {
                store.recordBatchImportBoundarySessionResolution(
                    command.copy(
                        resolution = StudyDbValue.BatchImportBoundaryStatus.NEXT_QUESTION,
                        captureMergeReceiptRef = null,
                    ),
                )
            }
        }
    }

    @Test
    fun distinctSameQuestionDraftsRejectAMissingOrDifferentReceipt() {
        assertThrows(IllegalArgumentException::class.java) {
            resolutionCommand(captureMergeReceiptRef = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolutionCommand(captureMergeReceiptRef = "different-receipt")
        }
    }

    @Test
    fun invalidBoundaryStateDoesNotRemapBatchPages(): Unit = runBlocking {
        prepareBoundary(claimBoundary = false)
        mergeCaptureDrafts()

        assertThrows(ImmutablePayloadConflictException::class.java) {
            runBlocking {
                store.recordBatchImportBoundarySessionResolution(resolutionCommand())
            }
        }

        val unchanged = checkNotNull(store.readBatchImportJob(JOB_ID))
        assertEquals(
            StudyDbValue.BatchImportBoundaryStatus.PENDING,
            unchanged.pages[0].boundaryAfterStatus,
        )
        assertEquals(FOLLOWING_DRAFT_ID, unchanged.pages[1].resultDraftId)
    }

    @Test
    fun failureAfterRemapRollsBackTheWholeBoundaryTransaction(): Unit = runBlocking {
        prepareBoundary(claimBoundary = true)
        mergeCaptureDrafts()
        val failingStore =
            RoomBatchImportStore(store.database) {
                throw InjectedBoundaryFailure
            }

        assertThrows(InjectedBoundaryFailure::class.java) {
            runBlocking {
                failingStore.recordBoundaryResolution(resolutionCommand())
            }
        }

        val unchanged = checkNotNull(store.readBatchImportJob(JOB_ID))
        assertEquals(
            StudyDbValue.BatchImportBoundaryStatus.CHECKING,
            unchanged.pages[0].boundaryAfterStatus,
        )
        assertEquals(FOLLOWING_DRAFT_ID, unchanged.pages[1].resultDraftId)
    }

    @Test
    fun delayedResolutionUsesThePersistedClaimAndClearsTheLiveLease(): Unit = runBlocking {
        prepareBoundary(claimBoundary = true)
        mergeCaptureDrafts()
        assertEquals(
            BOUNDARY_CLAIMED_AT,
            checkNotNull(store.readBatchImportJob(JOB_ID)).pages[0].updatedAtEpochMillis,
        )

        val resolved =
            store.recordBatchImportBoundarySessionResolution(
                resolutionCommand(
                    boundaryClaimedAtEpochMillis = BOUNDARY_CLAIMED_AT,
                    occurredAtEpochMillis = BOUNDARY_CLAIMED_AT + 60_000,
                ),
            )

        assertEquals(
            StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION,
            resolved.pages[0].boundaryAfterStatus,
        )
        assertEquals(null, resolved.pages[0].boundaryClaimedAtEpochMillis)
    }

    @Test
    fun recoveredBoundaryRejectsTheSupersededClaimTime(): Unit = runBlocking {
        prepareBoundary(claimBoundary = true)
        mergeCaptureDrafts()
        assertEquals(1, store.requeueInterruptedBatchImportBoundaries(JOB_ID, 2_000))
        assertEquals(
            null,
            checkNotNull(store.readBatchImportJob(JOB_ID))
                .pages[0]
                .boundaryClaimedAtEpochMillis,
        )
        check(store.claimBatchImportBoundary(JOB_ID, 0, RECOVERED_BOUNDARY_CLAIMED_AT))

        assertThrows(ImmutablePayloadConflictException::class.java) {
            runBlocking {
                store.recordBatchImportBoundarySessionResolution(
                    resolutionCommand(
                        boundaryClaimedAtEpochMillis = BOUNDARY_CLAIMED_AT,
                        occurredAtEpochMillis = 2_200,
                    ),
                )
            }
        }

        val recovered =
            store.recordBatchImportBoundarySessionResolution(
                resolutionCommand(
                    boundaryClaimedAtEpochMillis = RECOVERED_BOUNDARY_CLAIMED_AT,
                    occurredAtEpochMillis = 2_200,
                ),
            )
        assertEquals(
            StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION,
            recovered.pages[0].boundaryAfterStatus,
        )
        assertEquals(
            null,
            recovered.pages[0].boundaryClaimedAtEpochMillis,
        )
    }

    @Test
    fun remappingADraftDoesNotOverwriteAnotherBoundaryClaim(): Unit = runBlocking {
        store.createProblemDraft(draftCommand(PRIMARY_DRAFT_ID, "a"))
        store.createProblemDraft(draftCommand(FOLLOWING_DRAFT_ID, "b"))
        store.createProblemDraft(draftCommand(THIRD_DRAFT_ID, "c"))
        store.createBatchImportJob(
            CreateBatchImportJobCommand(
                jobId = JOB_ID,
                requestId = "request-three-pages",
                requestFingerprint = "d".repeat(64),
                sourceUris =
                    listOf("content://page/0", "content://page/1", "content://page/2"),
                occurredAtEpochMillis = 1_000,
            ),
        )
        listOf(PRIMARY_DRAFT_ID, FOLLOWING_DRAFT_ID, THIRD_DRAFT_ID)
            .forEachIndexed { index, draftId ->
                checkNotNull(store.claimNextBatchImportPage(JOB_ID, 1_100L + index))
                check(store.completeBatchImportPage(JOB_ID, index, draftId, 1_200L + index))
            }
        check(store.claimBatchImportBoundary(JOB_ID, 1, RECOVERED_BOUNDARY_CLAIMED_AT))

        assertEquals(
            1,
            store.database.batchImportDao().remapDraft(
                jobId = JOB_ID,
                followingDraftId = FOLLOWING_DRAFT_ID,
                primaryDraftId = PRIMARY_DRAFT_ID,
                updatedAtEpochMillis = 2_500,
            ),
        )

        val remappedBoundary = checkNotNull(store.readBatchImportJob(JOB_ID)).pages[1]
        assertEquals(2_500L, remappedBoundary.updatedAtEpochMillis)
        assertEquals(
            RECOVERED_BOUNDARY_CLAIMED_AT,
            remappedBoundary.boundaryClaimedAtEpochMillis,
        )
    }

    private suspend fun prepareBoundary(claimBoundary: Boolean) {
        store.createProblemDraft(draftCommand(PRIMARY_DRAFT_ID, "a"))
        store.createProblemDraft(draftCommand(FOLLOWING_DRAFT_ID, "b"))
        store.createBatchImportJob(
            CreateBatchImportJobCommand(
                jobId = JOB_ID,
                requestId = "request-boundary",
                requestFingerprint = "c".repeat(64),
                sourceUris = listOf("content://page/0", "content://page/1"),
                occurredAtEpochMillis = 1_000,
            ),
        )
        listOf(PRIMARY_DRAFT_ID, FOLLOWING_DRAFT_ID).forEachIndexed { index, draftId ->
            checkNotNull(store.claimNextBatchImportPage(JOB_ID, 1_100L + index))
            check(store.completeBatchImportPage(JOB_ID, index, draftId, 1_200L + index))
        }
        if (claimBoundary) {
            check(store.claimBatchImportBoundary(JOB_ID, 0, BOUNDARY_CLAIMED_AT))
        }
    }

    private suspend fun mergeCaptureDrafts() {
        store.mergeProblemDraftSourceBundle(mergeCommand())
    }

    private fun mergeCommand(): MergeProblemDraftSourceBundleCommand {
        val primaryAssets = listOf("asset-$PRIMARY_DRAFT_ID" to "a".repeat(64))
        val followingAssets = listOf("asset-$FOLLOWING_DRAFT_ID" to "b".repeat(64))
        val primaryFingerprint = assetOrderFingerprint(PRIMARY_DRAFT_ID, primaryAssets)
        val followingFingerprint = assetOrderFingerprint(FOLLOWING_DRAFT_ID, followingAssets)
        val mergedFingerprint =
            assetOrderFingerprint(PRIMARY_DRAFT_ID, primaryAssets + followingAssets)
        val receiptReference =
            CaptureMergeSessionReceiptReference.forBatchBoundary(
                jobId = JOB_ID,
                pageIndex = 0,
                primaryDraftSessionId = PRIMARY_DRAFT_ID,
                followingDraftSessionId = FOLLOWING_DRAFT_ID,
            )
        val requestFingerprint =
            CanonicalSha256("batch-boundary-merge-request-v1")
                .field("receiptReference", receiptReference)
                .field("jobId", JOB_ID)
                .field("pageIndex", 0)
                .field("primaryDraftId", PRIMARY_DRAFT_ID)
                .field("followingDraftId", FOLLOWING_DRAFT_ID)
                .field("primaryAssetOrder", primaryFingerprint)
                .field("followingAssetOrder", followingFingerprint)
                .field("mergedAssetOrder", mergedFingerprint)
                .finish()
        return MergeProblemDraftSourceBundleCommand(
            receiptReference = receiptReference,
            batchJobId = JOB_ID,
            batchPageIndex = 0,
            primaryDraftId = PRIMARY_DRAFT_ID,
            expectedPrimaryRevisionNumber = 1,
            expectedPrimarySessionVersion = captureSessionVersion(revision = 1, assets = 1),
            expectedPrimaryAssetOrderFingerprint = primaryFingerprint,
            expectedPrimarySourceAssetIds = primaryAssets.map { it.first },
            followingDraftId = FOLLOWING_DRAFT_ID,
            expectedFollowingRevisionNumber = 1,
            expectedFollowingSessionVersion = captureSessionVersion(revision = 1, assets = 1),
            expectedFollowingAssetOrderFingerprint = followingFingerprint,
            expectedFollowingSourceAssetIds = followingAssets.map { it.first },
            mergedAssetOrderFingerprint = mergedFingerprint,
            mergedSessionVersion = captureSessionVersion(revision = 1, assets = 2),
            requestCanonicalFingerprint = requestFingerprint,
            mergedAtEpochMillis = 1_400,
        )
    }

    private fun assetOrderFingerprint(
        draftId: String,
        assets: List<Pair<String, String>>,
    ): String {
        val canonical =
            CanonicalSha256("capture-draft-asset-order-v1")
                .field("draftSessionId", draftId)
                .field("assetCount", assets.size)
        assets.forEachIndexed { pageIndex, (assetId, contentSha256) ->
            canonical
                .field("pageIndex", pageIndex)
                .field("assetId", assetId)
                .field("contentSha256", contentSha256)
        }
        return canonical.finish()
    }

    private fun captureSessionVersion(
        revision: Int,
        assets: Int,
    ): Long = (revision.toLong() shl 8) or assets.toLong()

    private fun resolutionCommand(
        captureMergeReceiptRef: String? =
            CaptureMergeSessionReceiptReference.forBatchBoundary(
                jobId = JOB_ID,
                pageIndex = 0,
                primaryDraftSessionId = PRIMARY_DRAFT_ID,
                followingDraftSessionId = FOLLOWING_DRAFT_ID,
            ),
        boundaryClaimedAtEpochMillis: Long = BOUNDARY_CLAIMED_AT,
        occurredAtEpochMillis: Long = 1_500,
    ) = RecordBatchImportBoundarySessionResolutionCommand(
        jobId = JOB_ID,
        pageIndex = 0,
        primaryDraftSessionId = PRIMARY_DRAFT_ID,
        followingDraftSessionId = FOLLOWING_DRAFT_ID,
        resolution = StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION,
        boundaryClaimedAtEpochMillis = boundaryClaimedAtEpochMillis,
        captureMergeReceiptRef = captureMergeReceiptRef,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private fun draftCommand(
        draftId: String,
        hashCharacter: String,
    ): CreateProblemDraftCommand {
        val assetId = "asset-$draftId"
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
                            sourceAssetId = assetId,
                            sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                            writingLayer = WritingLayer.UNKNOWN,
                            provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
                            confidence = null,
                            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                            producerVersion = "batch-boundary-test-v1",
                        ),
                    ),
            )
        val contentSha256 = hashCharacter.repeat(64)
        return CreateProblemDraftCommand(
            sourceAsset =
                CanonicalSourceAssetRecord(
                    sourceAssetId = assetId,
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
            initialRevision =
                ProblemDraftRevisionRecord(
                    draftId = draftId,
                    revisionNumber = 1,
                    basisRevisionNumber = null,
                    subject = null,
                    title = "待校对题目",
                    questionDocument = document,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(document),
                    author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
                    createdAtEpochMillis = 1_000,
                ),
        )
    }

    private object InjectedBoundaryFailure : RuntimeException()

    private companion object {
        const val JOB_ID = "batch-session-boundary"
        const val PRIMARY_DRAFT_ID = "draft-primary"
        const val FOLLOWING_DRAFT_ID = "draft-following"
        const val THIRD_DRAFT_ID = "draft-third"
        const val BOUNDARY_CLAIMED_AT = 1_300L
        const val RECOVERED_BOUNDARY_CLAIMED_AT = 2_100L
    }
}
