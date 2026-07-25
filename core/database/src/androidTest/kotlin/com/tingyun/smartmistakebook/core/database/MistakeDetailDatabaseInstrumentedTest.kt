package com.tingyun.smartmistakebook.core.database

import android.os.SystemClock
import android.util.Log
import androidx.room3.executeSQL
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MistakeDetailDatabaseInstrumentedTest {
    private lateinit var store: RoomStudyDatabase

    @Before
    fun setUp() {
        store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun exactRevisionStaysPinnedAcrossCurrentHeadUpdateAndOwnsItsSources() = runBlocking {
        val revisionOneDocument = confirmedDocument("第一版题面", ASSET_ONE)
        createAndCommitRevisionOne(revisionOneDocument)
        val pinnedBefore = store.readExactMistakeDetail(ENTRY_ID, PROBLEM_ID, REVISION_ONE)

        val revisionTwoDocument = confirmedDocument("第二版题面", ASSET_TWO)
        store.createProblemDraft(createDraftCommand(DRAFT_TWO, ASSET_TWO, "b".repeat(64)))
        store.seedFixture(
            StudySeedBundle(
                problems = listOf(
                    ProblemSeedRecord(
                        problemId = OTHER_PROBLEM_ID,
                        canonicalFingerprint = "c".repeat(64),
                        subject = "MATH",
                        createdAtEpochMillis = 5_000,
                    ),
                ),
                revisions = listOf(
                    revisionSeed(REVISION_TWO, PROBLEM_ID, 2, revisionTwoDocument, "d".repeat(64)),
                    revisionSeed(
                        OTHER_REVISION_ID,
                        OTHER_PROBLEM_ID,
                        1,
                        confirmedDocument("另一道题", ASSET_TWO),
                        "e".repeat(64),
                    ),
                ),
                practiceUnits = emptyList(),
                errorBookEntries = emptyList(),
            ),
        )
        store.database.withWriteTransaction {
            executeSQL(
                """
                INSERT INTO problem_revision_source_asset (
                    problem_revision_id, source_asset_id, role
                ) VALUES ('$REVISION_TWO', '$ASSET_TWO', 'QUESTION_SOURCE')
                """.trimIndent(),
            )
            executeSQL(
                """
                UPDATE error_book_entry
                SET current_revision_id = '$REVISION_TWO',
                    updated_at_epoch_millis = 6000
                WHERE entry_id = '$ENTRY_ID'
                """.trimIndent(),
            )
        }

        val current = store.readMistakeDetail(ENTRY_ID)
        val pinnedAfter = store.readExactMistakeDetail(ENTRY_ID, PROBLEM_ID, REVISION_ONE)
        val history = store.readMistakeRevisionHistory(ENTRY_ID)

        assertEquals(REVISION_TWO, current?.problemRevisionId)
        assertEquals("d".repeat(64), current?.contentFingerprint)
        assertEquals(ASSET_TWO, current?.sourceAssets?.single()?.sourceAsset?.sourceAssetId)
        assertEquals(pinnedBefore, pinnedAfter)
        assertEquals(REVISION_ONE, pinnedAfter?.problemRevisionId)
        assertEquals(
            CapturedQuestionDocumentFingerprint.of(revisionOneDocument),
            pinnedAfter?.contentFingerprint,
        )
        assertEquals(ASSET_ONE, pinnedAfter?.sourceAssets?.single()?.sourceAsset?.sourceAssetId)
        assertEquals(
            revisionOneDocument,
            CapturedQuestionDocumentCodec.decode(pinnedAfter?.questionDocumentSnapshot.orEmpty()),
        )
        assertEquals(listOf(REVISION_TWO, REVISION_ONE), history.map { it.problemRevisionId })
        assertEquals(listOf(2, 1), history.map { it.revisionNumber })
        assertEquals(listOf(true, false), history.map { it.isCurrent })

        assertNull(store.readExactMistakeDetail(ENTRY_ID, OTHER_PROBLEM_ID, OTHER_REVISION_ID))
        assertNull(store.readExactMistakeDetail(ENTRY_ID, PROBLEM_ID, OTHER_REVISION_ID))
        assertNull(store.readExactMistakeDetail(ENTRY_ID, OTHER_PROBLEM_ID, REVISION_ONE))
    }

    @Test
    fun currentBatchReadsOneHundredPrintSnapshotsWithinInteractiveBudget() = runBlocking {
        val count = 100
        val document = confirmedDocument("批量打印题面", "batch-source")
        val snapshot = CapturedQuestionDocumentCodec.encode(document)
        val fingerprint = CapturedQuestionDocumentFingerprint.of(document)
        val entryIds = List(count) { index -> "batch-entry-$index" }
        store.seedFixture(
            StudySeedBundle(
                problems = List(count) { index ->
                    ProblemSeedRecord(
                        problemId = "batch-problem-$index",
                        canonicalFingerprint = index.toString(16).padStart(64, '0'),
                        subject = "MATH",
                        createdAtEpochMillis = index + 1L,
                    )
                },
                revisions = List(count) { index ->
                    ProblemRevisionSeedRecord(
                        revisionId = "batch-revision-$index",
                        problemId = "batch-problem-$index",
                        revisionNumber = 1,
                        title = "批量题目 ${index + 1}",
                        problemMarkdown = "批量打印题面",
                        questionDocumentSnapshot = snapshot,
                        answerSpecId = null,
                        answerSpecSnapshot = null,
                        answerVerificationStatus = StudyDbValue.VerificationStatus.UNKNOWN,
                        sourceType = "CAPTURE_CONFIRMED",
                        sourceReference = null,
                        contentFingerprint = fingerprint,
                        createdAtEpochMillis = index + 1L,
                    )
                },
                practiceUnits = List(count) { index ->
                    PracticeUnitSeedRecord(
                        practiceUnitId = "batch-practice-$index",
                        problemId = "batch-problem-$index",
                        problemRevisionId = "batch-revision-$index",
                        unitKey = "whole-problem",
                        unitKind = "WHOLE_PROBLEM",
                        title = "批量题目 ${index + 1}",
                        promptMarkdown = "批量打印题面",
                        estimatedSeconds = 180,
                        createdAtEpochMillis = index + 1L,
                    )
                },
                errorBookEntries = List(count) { index ->
                    ErrorBookEntrySeedRecord(
                        entryId = entryIds[index],
                        practiceUnitId = "batch-practice-$index",
                        problemId = "batch-problem-$index",
                        currentRevisionId = "batch-revision-$index",
                        sourceKey = null,
                        acceptedAtEpochMillis = index + 1L,
                        updatedAtEpochMillis = index + 1L,
                    )
                },
            ),
        )
        store.readCurrentMistakeDetails(entryIds.take(1))

        val elapsedMillis = List(5) {
            val started = SystemClock.elapsedRealtimeNanos()
            val records = store.readCurrentMistakeDetails(entryIds)
            assertEquals(count, records.size)
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
        }.sorted()
        val medianMillis = elapsedMillis[elapsedMillis.size / 2]
        Log.i("MistakeBatchPerf", "count=$count samplesMs=$elapsedMillis medianMs=$medianMillis")

        assertEquals(
            entryIds.toSet(),
            store.readCurrentMistakeDetails(entryIds).map { it.entryId }.toSet(),
        )
        assertTrue(
            "100-entry batch median was ${medianMillis}ms; samples=$elapsedMillis",
            medianMillis < 500,
        )
    }

    private suspend fun createAndCommitRevisionOne(document: CapturedQuestionDocument) {
        store.createProblemDraft(createDraftCommand(DRAFT_ONE, ASSET_ONE, "a".repeat(64)))
        assertTrue(
            store.reviseProblemDraft(
                ReviseProblemDraftCommand(
                    draftId = DRAFT_ONE,
                    expectedRevisionNumber = 1,
                    revision = ProblemDraftRevisionRecord(
                        draftId = DRAFT_ONE,
                        revisionNumber = 2,
                        basisRevisionNumber = 1,
                        subject = "MATH",
                        title = "精确版本读取",
                        questionDocument = document,
                        documentFingerprint = CapturedQuestionDocumentFingerprint.of(document),
                        author = StudyDbValue.ProblemDraftAuthor.USER,
                        createdAtEpochMillis = 2_000,
                    ),
                ),
            ).created,
        )
        store.commitProblemDraft(
            CommitProblemDraftCommand(
                commandId = "commit-exact-r1",
                draftId = DRAFT_ONE,
                expectedRevisionNumber = 2,
                problemId = PROBLEM_ID,
                problemRevisionId = REVISION_ONE,
                practiceUnitId = "practice-exact",
                errorBookEntryId = ENTRY_ID,
                estimatedSeconds = 180,
                committedAtEpochMillis = 3_000,
            ),
        )
    }

    private fun createDraftCommand(
        draftId: String,
        sourceAssetId: String,
        contentSha256: String,
    ): CreateProblemDraftCommand {
        val document = importedDocument(sourceAssetId)
        return CreateProblemDraftCommand(
            sourceAsset = CanonicalSourceAssetRecord(
                sourceAssetId = sourceAssetId,
                contentSha256 = contentSha256,
                relativePath = "source-assets/$contentSha256.jpg",
                mimeType = "image/jpeg",
                byteSize = 4_096,
                width = 1_200,
                height = 1_600,
                sourceType = StudyDbValue.SourceAssetType.PHOTO_PICKER,
                createdAtEpochMillis = 1_000,
            ),
            draftId = draftId,
            origin = StudyDbValue.CaptureOrigin.LIBRARY,
            initialRevision = ProblemDraftRevisionRecord(
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

    private fun revisionSeed(
        revisionId: String,
        problemId: String,
        revisionNumber: Int,
        document: CapturedQuestionDocument,
        contentFingerprint: String,
    ) = ProblemRevisionSeedRecord(
        revisionId = revisionId,
        problemId = problemId,
        revisionNumber = revisionNumber,
        title = document.document.title ?: "精确版本读取",
        problemMarkdown = document.document.blocks.joinToString("\n") { block ->
            (block as ContentBlock.Paragraph).markdown
        },
        questionDocumentSnapshot = CapturedQuestionDocumentCodec.encode(document),
        answerSpecId = null,
        answerSpecSnapshot = null,
        answerVerificationStatus = StudyDbValue.VerificationStatus.UNKNOWN,
        sourceType = "CAPTURE_CONFIRMED",
        sourceReference = document.blockEvidence.single().sourceAssetId,
        contentFingerprint = contentFingerprint,
        createdAtEpochMillis = 5_000,
    )

    private fun importedDocument(sourceAssetId: String) = document(
        markdown = "图片已保存，等待人工转写。",
        sourceAssetId = sourceAssetId,
        provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
        reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
    )

    private fun confirmedDocument(
        markdown: String,
        sourceAssetId: String,
    ) = document(
        markdown = markdown,
        sourceAssetId = sourceAssetId,
        provenance = QuestionBlockProvenance.USER_CORRECTION,
        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
    )

    private fun document(
        markdown: String,
        sourceAssetId: String,
        provenance: QuestionBlockProvenance,
        reviewStatus: QuestionBlockReviewStatus,
    ) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "document-$sourceAssetId-$markdown",
            title = "精确版本读取",
            blocks = listOf(ContentBlock.Paragraph("stem", markdown)),
        ),
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = sourceAssetId,
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = WritingLayer.PRINTED,
                provenance = provenance,
                reviewStatus = reviewStatus,
                producerVersion = "exact-read-test-v1",
            ),
        ),
    )

    private companion object {
        const val ENTRY_ID = "entry-exact"
        const val PROBLEM_ID = "problem-exact"
        const val REVISION_ONE = "revision-exact-1"
        const val REVISION_TWO = "revision-exact-2"
        const val ASSET_ONE = "asset-exact-1"
        const val ASSET_TWO = "asset-exact-2"
        const val DRAFT_ONE = "draft-exact-1"
        const val DRAFT_TWO = "draft-exact-2"
        const val OTHER_PROBLEM_ID = "problem-other"
        const val OTHER_REVISION_ID = "revision-other-1"
    }
}
