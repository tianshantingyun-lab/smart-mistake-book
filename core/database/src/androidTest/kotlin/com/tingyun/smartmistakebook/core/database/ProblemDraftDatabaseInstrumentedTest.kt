package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProblemDraftDatabaseInstrumentedTest {
    private lateinit var store: StudyDatabasePort

    @Before
    fun setUp() {
        store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun captureRequestFingerprintPersistsAndRejectsAChangedReplayBinding() = runBlocking {
        val command = createCommand().copy(requestFingerprint = "f".repeat(64))

        val created = store.createProblemDraft(command)
        val replay = store.createProblemDraft(command)

        assertTrue(created.created)
        assertFalse(replay.created)
        assertEquals(command.requestFingerprint, replay.draft.requestFingerprint)
        assertTrue(
            runCatching {
                store.createProblemDraft(command.copy(requestFingerprint = "e".repeat(64)))
            }.isFailure,
        )
    }

    @Test
    fun sourceBundleAppendIsOrderedIdempotentAndCommittedWithEveryPage() = runBlocking {
        val created = store.createProblemDraft(createCommand()).draft
        val secondAsset = CanonicalSourceAssetRecord(
            sourceAssetId = "asset-captured-2",
            contentSha256 = "b".repeat(64),
            relativePath = "source-assets/${"b".repeat(64)}.jpg",
            mimeType = "image/jpeg",
            byteSize = 3_072,
            width = 1_100,
            height = 1_500,
            sourceType = StudyDbValue.SourceAssetType.CAMERA,
            createdAtEpochMillis = 1_500,
        )
        val append = AppendProblemDraftSourceAssetCommand(
            draftId = created.draftId,
            expectedRevisionNumber = created.currentRevision.revisionNumber,
            expectedSourceAssetCount = 1,
            sourceAsset = secondAsset,
            appendedAtEpochMillis = 1_500,
        )

        val firstWrite = store.appendProblemDraftSourceAsset(append)
        val replay = store.appendProblemDraftSourceAsset(append)

        assertTrue(firstWrite.created)
        assertFalse(replay.created)
        assertEquals(listOf(0, 1), replay.draft.sourceAssets.map { it.pageIndex })
        assertEquals(
            listOf(ASSET_ID, secondAsset.sourceAssetId),
            replay.draft.sourceAssets.map { it.sourceAsset.sourceAssetId },
        )
        assertEquals(
            listOf(ASSET_ID, secondAsset.sourceAssetId),
            store.observePendingCaptureDrafts()
                .first()
                .single()
                .draft
                .sourceAssets
                .map { it.sourceAsset.sourceAssetId },
        )
        val confirmed = capturedDocument(
            markdown = "第二页包含完整的作答条件。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
            sourceAssetId = secondAsset.sourceAssetId,
        )
        store.reviseProblemDraft(
            ReviseProblemDraftCommand(
                draftId = created.draftId,
                expectedRevisionNumber = 1,
                revision = ProblemDraftRevisionRecord(
                    draftId = created.draftId,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "跨页函数题",
                    questionDocument = confirmed,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(confirmed),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 2_000,
                ),
            ),
        )
        store.commitProblemDraft(commitCommand())

        val detail = checkNotNull(store.readMistakeDetail("entry-captured-1"))
        assertEquals(
            setOf(ASSET_ID, secondAsset.sourceAssetId),
            detail.sourceAssets.map { it.sourceAsset.sourceAssetId }.toSet(),
        )
    }

    @Test
    fun exactLegacyCaptureReadRequiresAReceiptBoundHandoffAndReplayCanClaimOldReceipt() =
        runBlocking {
            createConfirmedLibraryDraft()
            val unclaimed = store.commitProblemDraft(commitCommand())
            val exactQuery = unclaimed.receipt.toExactLegacyQuery()

            assertEquals(null, store.readExactLegacyCaptureStudentDocument(exactQuery))
            assertTrue(
                store.readPendingCaptureStudentSaveHandoffs(
                    ReadPendingCaptureStudentSaveHandoffsQuery("learner:local"),
                ).isEmpty(),
            )

            val replay =
                store.commitProblemDraft(
                    commitCommand().copy(
                        legacyStudentSaveClaim =
                            LegacyCaptureStudentSaveClaim("learner:local"),
                    ),
                )

            assertFalse(replay.created)
            val pending =
                store.readPendingCaptureStudentSaveHandoffs(
                    ReadPendingCaptureStudentSaveHandoffsQuery("learner:local"),
                ).single()
            assertEquals(unclaimed.receipt.commandId, pending.intentId)
            assertEquals(unclaimed.receipt.payloadFingerprint, pending.intentCanonicalFingerprint)
            assertEquals(unclaimed.receipt.problemId, pending.targetProblemRef.problemId)
            assertEquals(
                unclaimed.receipt.problemRevisionId,
                pending.targetProblemRevisionRef.revisionId,
            )
            val preparedReplayQuery =
                ExactLegacyPreparedHandoffReplayQuery(
                    learnerId = pending.learnerId,
                    intentId = pending.intentId,
                    intentCanonicalFingerprint = pending.intentCanonicalFingerprint,
                    draftId = pending.draftId,
                    draftRevisionNumber = pending.draftRevisionNumber,
                    tutorSessionId = pending.sessionId,
                    subject = pending.targetProblemRef.subject.name,
                    problemId = pending.targetProblemRef.problemId,
                    problemRevisionId = pending.targetProblemRevisionRef.revisionId,
                    problemRevisionNumber = pending.targetProblemRevisionRef.revisionNumber,
                    practiceUnitId = pending.targetProblemRef.practiceUnitId,
                    documentCanonicalFingerprint =
                        pending.targetProblemRevisionRef.documentCanonicalFingerprint,
                )
            val preparedRecord =
                store.readExactLegacyCaptureStudentDocument(preparedReplayQuery)
            assertNotNull(preparedRecord)
            assertEquals(
                preparedRecord,
                store.readExactLegacyCaptureStudentDocument(exactQuery),
            )
            assertEquals(
                null,
                store.readExactLegacyCaptureStudentDocument(
                    exactQuery.copy(errorBookEntryId = "entry:wrong"),
                ),
            )
            listOf(
                exactQuery.copy(intentId = "intent:wrong"),
                exactQuery.copy(intentCanonicalFingerprint = "0".repeat(64)),
                exactQuery.copy(draftId = "draft:wrong"),
                exactQuery.copy(draftRevisionNumber = exactQuery.draftRevisionNumber + 1),
                exactQuery.copy(problemId = "problem:wrong"),
                exactQuery.copy(problemRevisionId = "revision:wrong"),
                exactQuery.copy(practiceUnitId = "practice-unit:wrong"),
            ).forEach { mismatched ->
                assertEquals(
                    null,
                    store.readExactLegacyCaptureStudentDocument(mismatched),
                )
            }
            assertEquals(
                null,
                store.readExactLegacyCaptureStudentDocument(
                    preparedReplayQuery.copy(subject = "PHYSICS"),
                ),
            )
            listOf(
                preparedReplayQuery.copy(intentId = "intent:wrong"),
                preparedReplayQuery.copy(intentCanonicalFingerprint = "0".repeat(64)),
                preparedReplayQuery.copy(draftId = "draft:wrong"),
                preparedReplayQuery.copy(
                    draftRevisionNumber = preparedReplayQuery.draftRevisionNumber + 1,
                ),
                preparedReplayQuery.copy(problemId = "problem:wrong"),
                preparedReplayQuery.copy(problemRevisionId = "revision:wrong"),
                preparedReplayQuery.copy(
                    problemRevisionNumber = preparedReplayQuery.problemRevisionNumber + 1,
                ),
                preparedReplayQuery.copy(practiceUnitId = "practice-unit:wrong"),
                preparedReplayQuery.copy(
                    documentCanonicalFingerprint = "0".repeat(64),
                ),
            ).forEach { mismatched ->
                assertEquals(
                    null,
                    store.readExactLegacyCaptureStudentDocument(mismatched),
                )
            }
            assertEquals(
                null,
                store.readExactLegacyCaptureStudentDocument(
                    preparedReplayQuery.copy(tutorSessionId = "session:wrong"),
                ),
            )

            store.finalizeCaptureStudentSaveHandoff(
                FinalizeCaptureStudentSaveHandoffCommand(
                    intentId = pending.intentId,
                    intentCanonicalFingerprint = pending.intentCanonicalFingerprint,
                    learnerId = pending.learnerId,
                    targetSaveReceiptFingerprint = "f".repeat(64),
                    finalizedAtEpochMillis = 5_000,
                ),
            )
            assertFalse(
                store.commitProblemDraft(
                    commitCommand().copy(
                        legacyStudentSaveClaim =
                            LegacyCaptureStudentSaveClaim("learner:local"),
                    ),
                ).created,
            )
            assertTrue(
                store.readPendingCaptureStudentSaveHandoffs(
                    ReadPendingCaptureStudentSaveHandoffsQuery("learner:local"),
                ).isEmpty(),
            )
            assertNotNull(store.readExactLegacyCaptureStudentDocument(exactQuery))
            assertEquals(
                preparedRecord,
                store.readExactLegacyCaptureStudentDocument(preparedReplayQuery),
            )
        }

    @Test
    fun invalidSessionClaimRollsBackTheLegacyCommitInsteadOfLeavingAnUnclaimedSave() =
        runBlocking {
            createConfirmedLibraryDraft()
            val invalid =
                commitCommand().copy(
                    legacyStudentSaveClaim =
                        LegacyCaptureStudentSaveClaim(
                            learnerId = "learner:local",
                            tutorSessionId = "tutor-session:not-this-draft",
                        ),
                )

            val failure = runCatching { store.commitProblemDraft(invalid) }.exceptionOrNull()

            assertTrue(failure is CaptureStudentSaveHandoffIntegrityException)
            assertEquals(0, store.countMistakes())
            assertEquals(
                StudyDbValue.ProblemDraftStatus.EDITING,
                checkNotNull(store.readProblemDraft(DRAFT_ID)).status,
            )
            assertTrue(
                store.readPendingCaptureStudentSaveHandoffs(
                    ReadPendingCaptureStudentSaveHandoffsQuery("learner:local"),
                ).isEmpty(),
            )

            val valid =
                store.commitProblemDraft(
                    invalid.copy(
                        legacyStudentSaveClaim =
                            LegacyCaptureStudentSaveClaim("learner:local"),
                    ),
                )
            assertTrue(valid.created)
            assertEquals(
                valid.receipt.commandId,
                store.readPendingCaptureStudentSaveHandoffs(
                    ReadPendingCaptureStudentSaveHandoffsQuery("learner:local"),
                ).single().intentId,
            )
        }

    @Test
    fun legacyStudentMigrationFailsClosedForABrokenSourceAssetLink() =
        runBlocking {
            withCorruptedLegacyStudentSource(
                mutation = { database ->
                    database.execSQL("PRAGMA foreign_keys=OFF")
                    database.execSQL(
                        "DELETE FROM canonical_source_asset WHERE source_asset_id = ?",
                        arrayOf(ASSET_ID),
                    )
                },
            ) { legacySource, receipt ->
                val snapshot =
                    legacySource.readLegacyAuthorityMigrationSnapshot("learner:local")
                assertEquals(1L, snapshot.studentSourceAssetLinkCount)
                assertEquals(1L, snapshot.studentBrokenSourceAssetLinkCount)
                assertEquals(
                    null,
                    legacySource.readExactLegacyCaptureStudentDocument(
                        receipt.toExactLegacyQuery(),
                    ),
                )
                assertTrue(
                    runCatching {
                        legacySource.readLegacyStudentDocumentMigrationPage(
                            afterExclusive = null,
                            limit = 1,
                        )
                    }.exceptionOrNull() is IllegalStateException,
                )
            }
        }

    @Test
    fun legacyStudentMigrationFailsClosedWhenSourceAssetsExceedTheByteBudget() =
        runBlocking {
            withCorruptedLegacyStudentSource(
                mutation = { database ->
                    database.execSQL(
                        "UPDATE canonical_source_asset SET byte_size = ? WHERE source_asset_id = ?",
                        arrayOf<Any>(
                            MAX_LEGACY_STUDENT_SOURCE_ASSET_BYTES_PER_RECORD + 1L,
                            ASSET_ID,
                        ),
                    )
                },
            ) { legacySource, receipt ->
                val snapshot =
                    legacySource.readLegacyAuthorityMigrationSnapshot("learner:local")
                assertEquals(
                    MAX_LEGACY_STUDENT_SOURCE_ASSET_BYTES_PER_RECORD + 1L,
                    snapshot.studentSourceAssetByteCount,
                )
                assertEquals(
                    null,
                    legacySource.readExactLegacyCaptureStudentDocument(
                        receipt.toExactLegacyQuery(),
                    ),
                )
                assertTrue(
                    runCatching {
                        legacySource.readLegacyStudentDocumentMigrationPage(
                            afterExclusive = null,
                            limit = 1,
                        )
                    }.exceptionOrNull() is IllegalStateException,
                )
            }
        }

    @Test
    fun exactLegacyCaptureReadFailsClosedWithoutItsQuestionSourceLink() =
        runBlocking {
            withCorruptedLegacyStudentSource(
                mutation = { database ->
                    database.delete(
                        "problem_revision_source_asset",
                        "problem_revision_id = ? AND source_asset_id = ? AND role = ?",
                        arrayOf(
                            commitCommand().problemRevisionId,
                            ASSET_ID,
                            "QUESTION_SOURCE",
                        ),
                    )
                },
            ) { legacySource, receipt ->
                assertEquals(
                    null,
                    legacySource.readExactLegacyCaptureStudentDocument(
                        receipt.toExactLegacyQuery(),
                    ),
                )
            }
        }

    @Test
    fun userRevisionWinsOverLateOcrAndCommitIsAtomicAndIdempotent() = runBlocking {
        val created = store.createProblemDraft(createCommand())
        assertTrue(created.created)
        assertEquals(1, created.draft.currentRevision.revisionNumber)

        val userDocument = capturedDocument(
            markdown = "已知函数 \$f(x)=x^2-2x\$，求单调区间。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
        )
        val userRevision = ProblemDraftRevisionRecord(
            draftId = DRAFT_ID,
            revisionNumber = 2,
            basisRevisionNumber = 1,
            subject = "MATH",
            title = "函数单调性",
            questionDocument = userDocument,
            documentFingerprint = CapturedQuestionDocumentFingerprint.of(userDocument),
            author = StudyDbValue.ProblemDraftAuthor.USER,
            createdAtEpochMillis = 2_000,
        )
        assertTrue(
            store.reviseProblemDraft(
                ReviseProblemDraftCommand(DRAFT_ID, 1, userRevision),
            ).created,
        )

        val lateOcr = capturedDocument(
            markdown = "迟到且错误的 OCR",
            provenance = QuestionBlockProvenance.LOCAL_OCR,
            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
            producerVersion = "ocr-test-v1",
        )
        val staleWrite = ReviseProblemDraftCommand(
            draftId = DRAFT_ID,
            expectedRevisionNumber = 1,
            revision = ProblemDraftRevisionRecord(
                draftId = DRAFT_ID,
                revisionNumber = 2,
                basisRevisionNumber = 1,
                subject = "MATH",
                title = "OCR candidate",
                questionDocument = lateOcr,
                documentFingerprint = CapturedQuestionDocumentFingerprint.of(lateOcr),
                author = StudyDbValue.ProblemDraftAuthor.LOCAL_OCR,
                createdAtEpochMillis = 3_000,
            ),
        )
        assertTrue(runCatching { store.reviseProblemDraft(staleWrite) }.isFailure)
        assertEquals(userDocument, store.readProblemDraft(DRAFT_ID)?.currentRevision?.questionDocument)

        val command = commitCommand()
        val committed = store.commitProblemDraft(command)
        assertTrue(committed.created)
        assertEquals(StudyDbValue.ProblemDraftStatus.COMMITTED, store.readProblemDraft(DRAFT_ID)?.status)
        assertEquals(1, store.countMistakes())
        val mistake = store.observeMistakes().first().single()
        assertEquals("函数单调性", mistake.title)
        assertTrue(mistake.problemMarkdown.contains("求单调区间"))
        assertNotNull(store.findMistakeBySourceKey("capture:$DRAFT_ID"))

        val replay = store.commitProblemDraft(command)
        assertFalse(replay.created)
        assertEquals(committed.receipt, replay.receipt)
        assertEquals(1, store.countMistakes())

        assertTrue(
            runCatching {
                store.commitProblemDraft(command.copy(errorBookEntryId = "entry-different"))
            }.isFailure,
        )
        assertEquals(1, store.countMistakes())
    }

    @Test
    fun repeatedCaptureKeepsEveryImageWithoutInventingCrossDraftPageOrder() = runBlocking {
        val firstDocument = capturedDocument(
            markdown = "已知函数 \$f(x)=x^2-2x\$，求单调区间。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
        )
        store.createProblemDraft(createCommand())
        store.reviseProblemDraft(
            ReviseProblemDraftCommand(
                draftId = DRAFT_ID,
                expectedRevisionNumber = 1,
                revision = ProblemDraftRevisionRecord(
                    draftId = DRAFT_ID,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "函数单调性",
                    questionDocument = firstDocument,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(firstDocument),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 2_000,
                ),
            ),
        )
        val firstCommit = store.commitProblemDraft(commitCommand())

        val secondDraftId = "draft-captured-repeat"
        val secondAssetId = "asset-$secondDraftId"
        store.createProblemDraft(
            splitCreateCommand(
                draftId = secondDraftId,
                hashCharacter = "b",
                createdAtEpochMillis = 5_000,
            ),
        )
        val secondDocument = capturedDocument(
            markdown = "已知函数 \$f(x)=x^2-2x\$，求单调区间。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
            sourceAssetId = secondAssetId,
        )
        store.reviseProblemDraft(
            ReviseProblemDraftCommand(
                draftId = secondDraftId,
                expectedRevisionNumber = 1,
                revision = ProblemDraftRevisionRecord(
                    draftId = secondDraftId,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "同一道函数题",
                    questionDocument = secondDocument,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(secondDocument),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 6_000,
                ),
            ),
        )
        val secondCommand = commitCommand().copy(
            commandId = "commit-repeat",
            draftId = secondDraftId,
            problemId = "problem-repeat",
            problemRevisionId = "problem-revision-repeat",
            practiceUnitId = "practice-repeat",
            errorBookEntryId = "entry-repeat",
            committedAtEpochMillis = 7_000,
            legacyStudentSaveClaim = LegacyCaptureStudentSaveClaim("learner:local"),
        )

        val repeatedCommit = store.commitProblemDraft(secondCommand)
        val replay = store.commitProblemDraft(secondCommand)

        assertTrue(repeatedCommit.created)
        assertFalse(replay.created)
        assertEquals(firstCommit.receipt.problemId, repeatedCommit.receipt.problemId)
        assertEquals(firstCommit.receipt.problemRevisionId, repeatedCommit.receipt.problemRevisionId)
        assertEquals(firstCommit.receipt.practiceUnitId, repeatedCommit.receipt.practiceUnitId)
        assertEquals(firstCommit.receipt.errorBookEntryId, repeatedCommit.receipt.errorBookEntryId)
        assertEquals(repeatedCommit.receipt, replay.receipt)
        assertEquals(1, store.countMistakes())
        assertEquals(2, store.observeMistakes().first().single().captureOccurrenceCount)
        val detail = checkNotNull(store.readMistakeDetail(firstCommit.receipt.errorBookEntryId))
        assertEquals(
            setOf(ASSET_ID, secondAssetId),
            detail.sourceAssets.map { it.sourceAsset.sourceAssetId }.toSet(),
        )
        val exact =
            checkNotNull(
                store.readExactLegacyCaptureStudentDocument(
                    repeatedCommit.receipt.toExactLegacyQuery(),
                ),
            )
        assertEquals(
            setOf(ASSET_ID, secondAssetId),
            exact.sourceAssets.map { it.sourceAsset.sourceAssetId }.toSet(),
        )
        assertEquals(
            null,
            exact.sourceAssets.single { it.sourceAsset.sourceAssetId == ASSET_ID }.pageIndex,
        )
        assertEquals(
            0,
            exact.sourceAssets.single { it.sourceAsset.sourceAssetId == secondAssetId }.pageIndex,
        )
        val bulkRecord =
            store.readLegacyStudentDocumentMigrationPage(
                afterExclusive = null,
                limit = 10,
            ).records.single()
        assertEquals(
            setOf(ASSET_ID, secondAssetId),
            bulkRecord.sourceAssets.map { it.sourceAsset.sourceAssetId }.toSet(),
        )
        assertEquals(
            null,
            bulkRecord.sourceAssets
                .single { it.sourceAsset.sourceAssetId == ASSET_ID }
                .pageIndex,
        )
        assertEquals(
            0,
            bulkRecord.sourceAssets
                .single { it.sourceAsset.sourceAssetId == secondAssetId }
                .pageIndex,
        )
    }

    @Test
    fun exactCaptureReusesLegacyMistakeWhoseOldFingerprintIncludedImageEvidence() = runBlocking {
        val document = capturedDocument(
            markdown = "已知函数 \$f(x)=x^2-2x\$，求单调区间。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
        )
        store.seedFixture(
            StudySeedBundle(
                problems = listOf(
                    ProblemSeedRecord(
                        problemId = "legacy-problem",
                        canonicalFingerprint = "c".repeat(64),
                        subject = "MATH",
                        createdAtEpochMillis = 100,
                    ),
                ),
                revisions = listOf(
                    ProblemRevisionSeedRecord(
                        revisionId = "legacy-revision",
                        problemId = "legacy-problem",
                        revisionNumber = 1,
                        title = "旧记录",
                        problemMarkdown = QuestionDocumentMarkdownProjection.project(document.document),
                        questionDocumentSnapshot = null,
                        answerSpecId = null,
                        answerSpecSnapshot = null,
                        answerVerificationStatus = StudyDbValue.VerificationStatus.UNKNOWN,
                        sourceType = "LEGACY_CAPTURE",
                        sourceReference = null,
                        contentFingerprint = "d".repeat(64),
                        createdAtEpochMillis = 100,
                    ),
                ),
                practiceUnits = listOf(
                    PracticeUnitSeedRecord(
                        practiceUnitId = "legacy-practice",
                        problemId = "legacy-problem",
                        problemRevisionId = "legacy-revision",
                        unitKey = "whole-problem",
                        unitKind = "WHOLE_PROBLEM",
                        title = "旧记录",
                        promptMarkdown = QuestionDocumentMarkdownProjection.project(document.document),
                        estimatedSeconds = 180,
                        createdAtEpochMillis = 100,
                    ),
                ),
                errorBookEntries = listOf(
                    ErrorBookEntrySeedRecord(
                        entryId = "legacy-entry",
                        practiceUnitId = "legacy-practice",
                        problemId = "legacy-problem",
                        currentRevisionId = "legacy-revision",
                        sourceKey = null,
                        status = StudyDbValue.ErrorBookStatus.ACTIVE,
                        acceptedAtEpochMillis = 100,
                        updatedAtEpochMillis = 100,
                    ),
                ),
            ),
        )
        store.createProblemDraft(createCommand())
        store.reviseProblemDraft(
            ReviseProblemDraftCommand(
                draftId = DRAFT_ID,
                expectedRevisionNumber = 1,
                revision = ProblemDraftRevisionRecord(
                    draftId = DRAFT_ID,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "新照片",
                    questionDocument = document,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(document),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 2_000,
                ),
            ),
        )

        val committed = store.commitProblemDraft(commitCommand())

        assertEquals("legacy-problem", committed.receipt.problemId)
        assertEquals("legacy-revision", committed.receipt.problemRevisionId)
        assertEquals("legacy-practice", committed.receipt.practiceUnitId)
        assertEquals("legacy-entry", committed.receipt.errorBookEntryId)
        assertEquals(1, store.countMistakes())
        assertEquals(1, store.observeMistakes().first().single().captureOccurrenceCount)
    }

    @Test
    fun mistakeDetailReadsCurrentMultiBlockSnapshotAndCanonicalSourceMetadata() = runBlocking {
        store.createProblemDraft(createCommand())
        val document = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-$DRAFT_ID",
                title = "函数单调性",
                blocks = listOf(
                    ContentBlock.Paragraph("stem", "已知函数："),
                    ContentBlock.Formula(
                        id = "formula",
                        latex = "f(x)=x^2-2x",
                        alternativeText = "f(x) 等于 x 平方减 2x",
                    ),
                ),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = ASSET_ID,
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 0.4),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
                QuestionBlockEvidence(
                    blockId = "formula",
                    sourceAssetId = ASSET_ID,
                    sourceRegion = NormalizedSourceRegion(0.0, 0.4, 1.0, 1.0),
                    writingLayer = WritingLayer.HANDWRITTEN,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        )
        store.reviseProblemDraft(
            ReviseProblemDraftCommand(
                draftId = DRAFT_ID,
                expectedRevisionNumber = 1,
                revision = ProblemDraftRevisionRecord(
                    draftId = DRAFT_ID,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "函数单调性",
                    questionDocument = document,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(document),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 2_000,
                ),
            ),
        )
        store.commitProblemDraft(commitCommand())

        val detail = checkNotNull(store.readMistakeDetail("entry-captured-1"))
        val snapshot = checkNotNull(detail.questionDocumentSnapshot)

        assertEquals("problem-revision-captured-1", detail.problemRevisionId)
        assertEquals(1, detail.revisionNumber)
        assertEquals("MATH", detail.subject)
        assertEquals(document, CapturedQuestionDocumentCodec.decode(snapshot))
        assertEquals(2, CapturedQuestionDocumentCodec.decode(snapshot).document.blocks.size)
        assertEquals("QUESTION_SOURCE", detail.sourceAssets.single().role)
        assertEquals(ASSET_ID, detail.sourceAssets.single().sourceAsset.sourceAssetId)
        assertEquals("a".repeat(64), detail.sourceAssets.single().sourceAsset.contentSha256)
        assertEquals(null, store.readMistakeDetail("missing-entry"))
    }

    @Test
    fun persistentDraftAndCommitReceiptSurviveReopen() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "capture-draft-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            var persistent = StudyDatabaseFactory.openPreCutoverForTest(context, databaseName)
            persistent.createProblemDraft(createCommand())
            val confirmed = capturedDocument(
                markdown = "化学反应速率与浓度关系。",
                provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                writingLayer = WritingLayer.PRINTED,
            )
            persistent.reviseProblemDraft(
                ReviseProblemDraftCommand(
                    draftId = DRAFT_ID,
                    expectedRevisionNumber = 1,
                    revision = ProblemDraftRevisionRecord(
                        draftId = DRAFT_ID,
                        revisionNumber = 2,
                        basisRevisionNumber = 1,
                        subject = "CHEMISTRY",
                        title = "反应速率",
                        questionDocument = confirmed,
                        documentFingerprint = CapturedQuestionDocumentFingerprint.of(confirmed),
                        author = StudyDbValue.ProblemDraftAuthor.USER,
                        createdAtEpochMillis = 2_000,
                    ),
                ),
            )
            val command = commitCommand()
            val before = persistent.commitProblemDraft(command)
            persistent.close()

            persistent = StudyDatabaseFactory.openPreCutoverForTest(context, databaseName)
            assertEquals(StudyDbValue.ProblemDraftStatus.COMMITTED, persistent.readProblemDraft(DRAFT_ID)?.status)
            assertEquals(before.receipt, persistent.commitProblemDraft(command).receipt)
            assertEquals(1, persistent.countMistakes())
            persistent.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    @Test
    fun tutorSessionPinsConfirmedRevisionUntilExplicitIdempotentCommit() = runBlocking {
        store.createProblemDraft(createCommand(origin = StudyDbValue.CaptureOrigin.TUTOR))
        val confirmed = capturedDocument(
            markdown = "已知函数 f(x)=x²，判断它的单调性。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
        )
        val revision = ProblemDraftRevisionRecord(
            draftId = DRAFT_ID,
            revisionNumber = 2,
            basisRevisionNumber = 1,
            subject = "MATH",
            title = "函数单调性讲解",
            questionDocument = confirmed,
            documentFingerprint = CapturedQuestionDocumentFingerprint.of(confirmed),
            author = StudyDbValue.ProblemDraftAuthor.USER,
            createdAtEpochMillis = 2_000,
        )
        val command = ConfirmTutorSessionCommand(
            sessionId = "tutor-session-1",
            draftId = DRAFT_ID,
            expectedRevisionNumber = 1,
            confirmedRevision = revision,
            createdAtEpochMillis = 2_000,
        )

        val created = store.confirmTutorSession(command)
        assertTrue(created.created)
        assertEquals(0, store.countMistakes())
        assertEquals(2, created.session.draftRevisionNumber)
        assertEquals(StudyDbValue.ProblemDraftStatus.EDITING, created.session.draftStatus)
        assertEquals(null, created.session.commitReceipt)

        val replay = store.confirmTutorSession(command)
        assertFalse(replay.created)
        assertEquals(created.session.sessionId, replay.session.sessionId)
        assertEquals(created.session, checkNotNull(store.readTutorSession("tutor-session-1")))
        assertTrue(
            runCatching {
                store.confirmTutorSession(
                    command.copy(
                        confirmedRevision = revision.copy(title = "冲突标题"),
                    ),
                )
            }.isFailure,
        )
        assertTrue(runCatching { store.commitProblemDraft(commitCommand()) }.isFailure)
        assertEquals(0, store.countMistakes())

        val firstSave = store.commitTutorSession(
            CommitTutorSessionCommand("tutor-session-1", commitCommand()),
        )
        assertTrue(firstSave.created)
        assertEquals(1, store.countMistakes())
        val secondSave = store.commitTutorSession(
            CommitTutorSessionCommand(
                "tutor-session-1",
                commitCommand().copy(
                    commandId = "commit-retry-with-new-key",
                    problemId = "ignored-problem-id",
                    problemRevisionId = "ignored-revision-id",
                    practiceUnitId = "ignored-practice-id",
                    errorBookEntryId = "ignored-entry-id",
                    committedAtEpochMillis = 9_000,
                ),
            ),
        )
        assertFalse(secondSave.created)
        assertEquals(firstSave.receipt, secondSave.receipt)
        assertEquals(1, store.countMistakes())
        val savedSession = checkNotNull(store.readTutorSession("tutor-session-1"))
        assertEquals(firstSave.receipt.errorBookEntryId, savedSession.commitReceipt?.errorBookEntryId)
        val savedDetail = checkNotNull(
            store.readExactMistakeDetail(
                entryId = firstSave.receipt.errorBookEntryId,
                problemId = firstSave.receipt.problemId,
                problemRevisionId = firstSave.receipt.problemRevisionId,
            ),
        )
        assertEquals("tutor-session-1", savedDetail.tutorSessionId)
        assertEquals(2, savedDetail.tutorQuestionRevisionNumber)
    }

    @Test
    fun captureReplacementIsAtomicIdempotentAndRejectsConflicts() = runBlocking {
        store.createProblemDraft(createCommand())
        val replacement = createCommand(
            draftId = "draft-captured-replacement",
            createdAtEpochMillis = 2_000,
        )
        val command = ReplaceProblemDraftCommand(
            replacedDraftId = DRAFT_ID,
            expectedReplacedRevisionNumber = 1,
            replacement = replacement,
            replacedAtEpochMillis = 2_000,
        )

        val first = store.replaceProblemDraft(command)

        assertTrue(first.created)
        assertEquals(
            StudyDbValue.ProblemDraftStatus.ABANDONED,
            store.readProblemDraft(DRAFT_ID)?.status,
        )
        assertEquals(
            StudyDbValue.ProblemDraftStatus.EDITING,
            store.readProblemDraft(replacement.draftId)?.status,
        )
        assertEquals(1, store.observePendingProblemDraftCount().first())

        val replay = store.replaceProblemDraft(command)
        assertFalse(replay.created)
        assertEquals(first.replacement, replay.replacement)
        assertTrue(
            runCatching {
                store.replaceProblemDraft(
                    command.copy(
                        replacement = replacement.copy(
                            initialRevision = replacement.initialRevision.copy(
                                title = "同一替换请求的冲突题面",
                            ),
                        ),
                    ),
                )
            }.isFailure,
        )
        assertEquals(1, store.observePendingProblemDraftCount().first())

        val competingOriginal = "draft-competing-original"
        val competingReplacement = "draft-competing-replacement"
        store.createProblemDraft(createCommand(draftId = competingOriginal))
        val userDocument = capturedDocument(
            markdown = "用户已校对，旧版本号不能再被替换。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
        )
        store.reviseProblemDraft(
            ReviseProblemDraftCommand(
                draftId = competingOriginal,
                expectedRevisionNumber = 1,
                revision = ProblemDraftRevisionRecord(
                    draftId = competingOriginal,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "已校对",
                    questionDocument = userDocument,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(userDocument),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 3_000,
                ),
            ),
        )
        assertTrue(
            runCatching {
                store.replaceProblemDraft(
                    ReplaceProblemDraftCommand(
                        replacedDraftId = competingOriginal,
                        expectedReplacedRevisionNumber = 1,
                        replacement = createCommand(
                            draftId = competingReplacement,
                            createdAtEpochMillis = 4_000,
                        ),
                        replacedAtEpochMillis = 4_000,
                    ),
                )
            }.isFailure,
        )
        assertEquals(
            StudyDbValue.ProblemDraftStatus.EDITING,
            store.readProblemDraft(competingOriginal)?.status,
        )
        assertEquals(null, store.readProblemDraft(competingReplacement))
    }

    @Test
    fun captureSplitIsAtomicIdempotentAndKeepsIndependentDrafts() = runBlocking {
        store.createProblemDraft(createCommand())
        val replacements = listOf(
            splitCreateCommand("draft-split-1", "b", 2_000),
            splitCreateCommand("draft-split-2", "c", 2_000),
        )
        val command = SplitProblemDraftCommand(
            replacedDraftId = DRAFT_ID,
            expectedReplacedRevisionNumber = 1,
            replacements = replacements,
            splitAtEpochMillis = 2_000,
        )

        val first = store.splitProblemDraft(command)

        assertTrue(first.created)
        assertEquals(replacements.map { it.draftId }, first.replacements.map { it.draftId })
        assertEquals(
            StudyDbValue.ProblemDraftStatus.ABANDONED,
            store.readProblemDraft(DRAFT_ID)?.status,
        )
        assertTrue(first.replacements.all { it.status == StudyDbValue.ProblemDraftStatus.EDITING })
        assertEquals(2, store.observePendingProblemDraftCount().first())

        val replay = store.splitProblemDraft(command)
        assertFalse(replay.created)
        assertEquals(first.replacements, replay.replacements)

        val conflicting = command.copy(
            replacements = replacements.mapIndexed { index, replacement ->
                if (index == 0) {
                    replacement.copy(requestFingerprint = "d".repeat(64))
                } else {
                    replacement
                }
            },
        )
        assertTrue(runCatching { store.splitProblemDraft(conflicting) }.isFailure)
        assertEquals(2, store.observePendingProblemDraftCount().first())
    }

    @Test
    fun captureSplitRollsBackEveryChildWhenOneCanonicalAssetConflicts() = runBlocking {
        val originalId = "draft-split-rollback-original"
        store.createProblemDraft(createCommand(draftId = originalId, createdAtEpochMillis = 2_000))
        val blocker = splitCreateCommand("draft-split-blocker", "d", 2_100)
        store.createProblemDraft(blocker)
        val firstChild = splitCreateCommand("draft-split-rollback-1", "e", 3_000)
        val conflictingBase = splitCreateCommand("draft-split-rollback-2", "f", 3_000)
        val conflictingDocument = conflictingBase.initialRevision.questionDocument.copy(
            blockEvidence = conflictingBase.initialRevision.questionDocument.blockEvidence.map {
                it.copy(sourceAssetId = blocker.sourceAsset.sourceAssetId)
            },
        )
        val conflictingChild = conflictingBase.copy(
            sourceAsset = conflictingBase.sourceAsset.copy(
                sourceAssetId = blocker.sourceAsset.sourceAssetId,
            ),
            initialRevision = conflictingBase.initialRevision.copy(
                questionDocument = conflictingDocument,
                documentFingerprint = CapturedQuestionDocumentFingerprint.of(conflictingDocument),
            ),
        )

        val failed = runCatching {
            store.splitProblemDraft(
                SplitProblemDraftCommand(
                    replacedDraftId = originalId,
                    expectedReplacedRevisionNumber = 1,
                    replacements = listOf(firstChild, conflictingChild),
                    splitAtEpochMillis = 3_000,
                ),
            )
        }

        assertTrue(failed.isFailure)
        assertEquals(StudyDbValue.ProblemDraftStatus.EDITING, store.readProblemDraft(originalId)?.status)
        assertEquals(null, store.readProblemDraft(firstChild.draftId))
        assertEquals(null, store.readProblemDraft(conflictingChild.draftId))
        assertEquals(2, store.observePendingProblemDraftCount().first())
    }

    @Test
    fun endingTutorSessionIsIdempotentAndMutuallyExclusiveWithSaving() = runBlocking {
        store.createProblemDraft(createCommand(origin = StudyDbValue.CaptureOrigin.TUTOR))
        val confirmed = capturedDocument(
            markdown = "临时讲题结束后不进入错题本。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
        )
        store.confirmTutorSession(
            ConfirmTutorSessionCommand(
                sessionId = "tutor-session-end",
                draftId = DRAFT_ID,
                expectedRevisionNumber = 1,
                confirmedRevision = ProblemDraftRevisionRecord(
                    draftId = DRAFT_ID,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "临时讲题",
                    questionDocument = confirmed,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(confirmed),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 2_000,
                ),
                createdAtEpochMillis = 2_000,
            ),
        )

        val ended = store.endTutorSession(EndTutorSessionCommand("tutor-session-end", 3_000))
        assertTrue(ended.created)
        assertEquals(3_000, ended.endedAtEpochMillis)
        assertEquals(
            StudyDbValue.ProblemDraftStatus.ABANDONED,
            store.readTutorSession("tutor-session-end")?.draftStatus,
        )
        assertEquals(0, store.observePendingProblemDraftCount().first())
        assertEquals(0, store.countMistakes())

        val replay = store.endTutorSession(EndTutorSessionCommand("tutor-session-end", 9_000))
        assertFalse(replay.created)
        assertEquals(3_000, replay.endedAtEpochMillis)
        assertTrue(
            runCatching {
                store.commitTutorSession(
                    CommitTutorSessionCommand("tutor-session-end", commitCommand()),
                )
            }.isFailure,
        )
        assertEquals(0, store.countMistakes())

        val savedDraftId = "draft-saved-before-end"
        store.createProblemDraft(
            createCommand(
                origin = StudyDbValue.CaptureOrigin.TUTOR,
                draftId = savedDraftId,
            ),
        )
        val savedDocument = confirmed.copy(
            blockEvidence = confirmed.blockEvidence.map { it.copy(sourceAssetId = ASSET_ID) },
        )
        store.confirmTutorSession(
            ConfirmTutorSessionCommand(
                sessionId = "tutor-session-saved-before-end",
                draftId = savedDraftId,
                expectedRevisionNumber = 1,
                confirmedRevision = ProblemDraftRevisionRecord(
                    draftId = savedDraftId,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "先保存",
                    questionDocument = savedDocument,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(savedDocument),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 4_000,
                ),
                createdAtEpochMillis = 4_000,
            ),
        )
        store.commitTutorSession(
            CommitTutorSessionCommand(
                "tutor-session-saved-before-end",
                commitCommand().copy(
                    commandId = "commit-saved-before-end",
                    draftId = savedDraftId,
                    problemId = "problem-saved-before-end",
                    problemRevisionId = "revision-saved-before-end",
                    practiceUnitId = "practice-saved-before-end",
                    errorBookEntryId = "entry-saved-before-end",
                    committedAtEpochMillis = 5_000,
                ),
            ),
        )
        assertTrue(
            runCatching {
                store.endTutorSession(
                    EndTutorSessionCommand("tutor-session-saved-before-end", 6_000),
                )
            }.isFailure,
        )
        assertEquals(1, store.countMistakes())
    }

    @Test
    fun savingAndEndingTutorSessionShareOneTransactionalBarrier() = runBlocking {
        store.createProblemDraft(createCommand(origin = StudyDbValue.CaptureOrigin.TUTOR))
        val confirmed = capturedDocument(
            markdown = "并发保存与结束只能有一个终态。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
        )
        store.confirmTutorSession(
            ConfirmTutorSessionCommand(
                sessionId = "tutor-session-barrier",
                draftId = DRAFT_ID,
                expectedRevisionNumber = 1,
                confirmedRevision = ProblemDraftRevisionRecord(
                    draftId = DRAFT_ID,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "并发终态",
                    questionDocument = confirmed,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(confirmed),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 2_000,
                ),
                createdAtEpochMillis = 2_000,
            ),
        )
        val start = CompletableDeferred<Unit>()
        val save = async {
            start.await()
            runCatching {
                store.commitTutorSession(
                    CommitTutorSessionCommand("tutor-session-barrier", commitCommand()),
                )
            }
        }
        val end = async {
            start.await()
            runCatching {
                store.endTutorSession(EndTutorSessionCommand("tutor-session-barrier", 3_000))
            }
        }

        start.complete(Unit)
        val outcomes = listOf(save.await(), end.await())

        assertEquals(1, outcomes.count { it.isSuccess })
        val terminal = checkNotNull(store.readTutorSession("tutor-session-barrier"))
        when (terminal.draftStatus) {
            StudyDbValue.ProblemDraftStatus.COMMITTED -> {
                assertNotNull(terminal.commitReceipt)
                assertEquals(1, store.countMistakes())
            }
            StudyDbValue.ProblemDraftStatus.ABANDONED -> {
                assertEquals(null, terminal.commitReceipt)
                assertEquals(0, store.countMistakes())
            }
            else -> throw AssertionError("Tutor session remained non-terminal")
        }
    }

    @Test
    fun pendingCaptureQuerySortsNewestJoinsTutorSessionAndDropsCommittedDraft() = runBlocking {
        val olderDraftId = "draft-pending-older"
        val tutorDraftId = "draft-pending-tutor"
        store.createProblemDraft(
            createCommand(
                draftId = olderDraftId,
                createdAtEpochMillis = 1_000,
            ),
        )
        store.createProblemDraft(
            createCommand(
                origin = StudyDbValue.CaptureOrigin.TUTOR,
                draftId = tutorDraftId,
                createdAtEpochMillis = 3_000,
            ),
        )
        val confirmed = capturedDocument(
            markdown = "讲题会话等待继续。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
        )
        store.confirmTutorSession(
            ConfirmTutorSessionCommand(
                sessionId = "session-pending-tutor",
                draftId = tutorDraftId,
                expectedRevisionNumber = 1,
                confirmedRevision = ProblemDraftRevisionRecord(
                    draftId = tutorDraftId,
                    revisionNumber = 2,
                    basisRevisionNumber = 1,
                    subject = "MATH",
                    title = "待继续讲题",
                    questionDocument = confirmed,
                    documentFingerprint = CapturedQuestionDocumentFingerprint.of(confirmed),
                    author = StudyDbValue.ProblemDraftAuthor.USER,
                    createdAtEpochMillis = 4_000,
                ),
                createdAtEpochMillis = 4_000,
            ),
        )

        val pending = store.observePendingCaptureDrafts().first()
        assertEquals(listOf(tutorDraftId, olderDraftId), pending.map { it.draft.draftId })
        assertEquals("session-pending-tutor", pending.first().tutorSessionId)
        assertEquals(2, pending.first().tutorSessionDraftRevisionNumber)
        assertEquals(null, pending.last().tutorSessionId)
        assertEquals(
            pending.first(),
            store.readPendingCaptureDraft(tutorDraftId),
        )

        val firstPendingSeen = CompletableDeferred<Unit>()
        val afterCommit = async {
            store.observePendingCaptureDrafts()
                .onEach { rows ->
                    if (rows.any { it.draft.draftId == tutorDraftId }) {
                        firstPendingSeen.complete(Unit)
                    }
                }
                .first { rows -> rows.none { it.draft.draftId == tutorDraftId } }
        }
        firstPendingSeen.await()
        store.commitTutorSession(
            CommitTutorSessionCommand(
                sessionId = "session-pending-tutor",
                commit = commitCommand().copy(
                    commandId = "commit-pending-tutor",
                    draftId = tutorDraftId,
                    problemId = "problem-pending-tutor",
                    problemRevisionId = "revision-pending-tutor",
                    practiceUnitId = "practice-pending-tutor",
                    errorBookEntryId = "entry-pending-tutor",
                    committedAtEpochMillis = 5_000,
                ),
            ),
        )
        assertEquals(
            listOf(olderDraftId),
            afterCommit.await().map { it.draft.draftId },
        )
        assertEquals(null, store.readPendingCaptureDraft(tutorDraftId))
    }

    @Test
    fun pendingCaptureBatchKeepsDraftTasksIsolatedAndUsesBoundedCompleteOrdering() = runBlocking {
        val firstDraftId = "draft-pending-batch-a"
        val secondDraftId = "draft-pending-batch-b"
        store.createProblemDraft(createCommand(draftId = firstDraftId, createdAtEpochMillis = 1_000))
        store.createProblemDraft(createCommand(draftId = secondDraftId, createdAtEpochMillis = 2_000))

        repeat(66) { index ->
            store.createModelTask(
                assessmentCreateCommand(
                    draftId = firstDraftId,
                    suffix = "base-$index",
                    occurredAtEpochMillis = 1_000L + index,
                ),
            )
        }
        repeat(2) { index ->
            store.createModelTask(
                assessmentCreateCommand(
                    draftId = secondDraftId,
                    suffix = "second-$index",
                    occurredAtEpochMillis = 2_000L + index,
                ),
            )
        }

        val olderStateTask = store.createModelTask(
            assessmentCreateCommand(firstDraftId, "state-older", 2_500),
        ).snapshot
        val newerStateTask = store.createModelTask(
            assessmentCreateCommand(firstDraftId, "state-newer", 3_000),
        ).snapshot
        store.transitionModelTask(olderStateTask.toQueuedTransition(5_000))
        store.transitionModelTask(newerStateTask.toQueuedTransition(5_000))
        store.createModelTask(assessmentCreateCommand(firstDraftId, "tie-a", 5_000))
        store.createModelTask(assessmentCreateCommand(firstDraftId, "tie-b", 5_000))

        store.createModelTask(parseCreateCommand(firstDraftId, "older", 6_000))
        store.createModelTask(parseCreateCommand(secondDraftId, "only", 6_500))
        store.createModelTask(parseCreateCommand(firstDraftId, "newer", 7_000))

        val dao = (store as RoomStudyDatabase).database.pendingCaptureDao()
        val assessmentsByDraft = dao.readRecentPendingAssessmentTasks().groupBy { it.subjectId }
        val firstAssessments = assessmentsByDraft.getValue(firstDraftId)
        assertEquals(64, firstAssessments.size)
        assertEquals(
            listOf(
                "task-$firstDraftId-state-newer",
                "task-$firstDraftId-state-older",
                "task-$firstDraftId-tie-b",
                "task-$firstDraftId-tie-a",
            ),
            firstAssessments.take(4).map { it.taskId },
        )
        assertTrue(firstAssessments.none { it.taskId.endsWith("base-0") })
        assertTrue(firstAssessments.none { it.taskId.endsWith("base-1") })
        assertEquals(2, assessmentsByDraft.getValue(secondDraftId).size)
        assertTrue(
            assessmentsByDraft.getValue(secondDraftId).all { it.subjectId == secondDraftId },
        )

        val parsesByDraft = dao.readLatestPendingParseTasks().associateBy { it.subjectId }
        assertEquals("task-$firstDraftId-parse-newer", parsesByDraft[firstDraftId]?.taskId)
        assertEquals("task-$secondDraftId-parse-only", parsesByDraft[secondDraftId]?.taskId)

        val flowRequestId = "assessment-$firstDraftId-flow-refresh"
        val refreshed = async(start = CoroutineStart.UNDISPATCHED) {
            store.observePendingCaptureDrafts().first { pending ->
                pending.firstOrNull { it.draft.draftId == firstDraftId }
                    ?.assessmentTasks
                    ?.any { it.request.requestId == flowRequestId } == true
            }
        }
        store.createModelTask(
            assessmentCreateCommand(firstDraftId, "flow-refresh", 8_000),
        )
        val refreshedDraft = refreshed.await().first { it.draft.draftId == firstDraftId }
        assertEquals(64, refreshedDraft.assessmentTasks.size)
        assertTrue(refreshedDraft.assessmentTasks.all { it.request.input.subjectId == firstDraftId })
    }

    @Test
    fun exportedVersionTwoSchemaMigratesToCaptureSchemaWithoutDestructiveFallback() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "capture-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 2)
            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val created = migrated.createProblemDraft(createCommand())
            assertTrue(created.created)
            assertEquals(1, migrated.observePendingProblemDraftCount().first())
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    @Test
    fun exportedVersionFourSchemaMigratesToTutorSessions() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-session-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 4)
            val migrated = StudyDatabaseFactory.open(context, databaseName)
            migrated.createProblemDraft(createCommand(origin = StudyDbValue.CaptureOrigin.TUTOR))
            val confirmed = capturedDocument(
                markdown = "迁移后创建讲题会话。",
                provenance = QuestionBlockProvenance.USER_CORRECTION,
                reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                writingLayer = WritingLayer.PRINTED,
            )
            val session = migrated.confirmTutorSession(
                ConfirmTutorSessionCommand(
                    sessionId = "tutor-session-after-migration",
                    draftId = DRAFT_ID,
                    expectedRevisionNumber = 1,
                    confirmedRevision = ProblemDraftRevisionRecord(
                        draftId = DRAFT_ID,
                        revisionNumber = 2,
                        basisRevisionNumber = 1,
                        subject = "MATH",
                        title = "迁移验证",
                        questionDocument = confirmed,
                        documentFingerprint = CapturedQuestionDocumentFingerprint.of(confirmed),
                        author = StudyDbValue.ProblemDraftAuthor.USER,
                        createdAtEpochMillis = 2_000,
                    ),
                    createdAtEpochMillis = 2_000,
                ),
            )
            assertTrue(session.created)
            assertEquals(0, migrated.countMistakes())
            assertNotNull(migrated.readTutorSession("tutor-session-after-migration"))
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    private fun createCommand(
        origin: String = StudyDbValue.CaptureOrigin.LIBRARY,
        draftId: String = DRAFT_ID,
        createdAtEpochMillis: Long = 1_000,
    ): CreateProblemDraftCommand {
        val document = capturedDocument(
            markdown = "图片已保存，等待人工转写。",
            provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
            producerVersion = "capture-import-v1",
        )
        return CreateProblemDraftCommand(
            sourceAsset = CanonicalSourceAssetRecord(
                sourceAssetId = ASSET_ID,
                contentSha256 = "a".repeat(64),
                relativePath = "source-assets/${"a".repeat(64)}.jpg",
                mimeType = "image/jpeg",
                byteSize = 4_096,
                width = 1_200,
                height = 1_600,
                sourceType = StudyDbValue.SourceAssetType.PHOTO_PICKER,
                createdAtEpochMillis = 1_000,
            ),
            draftId = draftId,
            origin = origin,
            initialRevision = ProblemDraftRevisionRecord(
                draftId = draftId,
                revisionNumber = 1,
                basisRevisionNumber = null,
                subject = null,
                title = "待校对题目",
                questionDocument = document,
                documentFingerprint = CapturedQuestionDocumentFingerprint.of(document),
                author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
                createdAtEpochMillis = createdAtEpochMillis,
            ),
        )
    }

    private suspend fun createConfirmedLibraryDraft() {
        store.createProblemDraft(createCommand())
        val confirmed =
            capturedDocument(
                markdown = "已知函数 \$f(x)=x^2-2x\$，求单调区间。",
                provenance = QuestionBlockProvenance.USER_CORRECTION,
                reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                writingLayer = WritingLayer.PRINTED,
            )
        store.reviseProblemDraft(
            ReviseProblemDraftCommand(
                draftId = DRAFT_ID,
                expectedRevisionNumber = 1,
                revision =
                    ProblemDraftRevisionRecord(
                        draftId = DRAFT_ID,
                        revisionNumber = 2,
                        basisRevisionNumber = 1,
                        subject = "MATH",
                        title = "函数单调性",
                        questionDocument = confirmed,
                        documentFingerprint = CapturedQuestionDocumentFingerprint.of(confirmed),
                        author = StudyDbValue.ProblemDraftAuthor.USER,
                        createdAtEpochMillis = 2_000,
                    ),
            ),
        )
    }

    private suspend fun withCorruptedLegacyStudentSource(
        mutation: (SQLiteDatabase) -> Unit,
        assertion: suspend (StudyDatabasePort, ProblemDraftCommitReceipt) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "legacy-student-source-guard-${System.nanoTime()}.db"
        store.close()
        context.deleteDatabase(databaseName)
        try {
            store = StudyDatabaseFactory.openPreCutoverForTest(context, databaseName)
            createConfirmedLibraryDraft()
            val receipt =
                store.commitProblemDraft(
                    commitCommand().copy(
                        legacyStudentSaveClaim =
                            LegacyCaptureStudentSaveClaim("learner:local"),
                    ),
                ).receipt
            store.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use(mutation)

            store = StudyDatabaseFactory.openPreCutoverForTest(context, databaseName)
            assertion(store, receipt)
        } finally {
            runCatching { store.close() }
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    private fun ProblemDraftCommitReceipt.toExactLegacyQuery() =
        ExactLegacyCaptureReceiptReplayQuery(
            learnerId = "learner:local",
            intentId = commandId,
            intentCanonicalFingerprint = payloadFingerprint,
            draftId = draftId,
            draftRevisionNumber = draftRevisionNumber,
            tutorSessionId = null,
            errorBookEntryId = errorBookEntryId,
            problemId = problemId,
            problemRevisionId = problemRevisionId,
            practiceUnitId = practiceUnitId,
        )

    private fun splitCreateCommand(
        draftId: String,
        hashCharacter: String,
        createdAtEpochMillis: Long,
    ): CreateProblemDraftCommand {
        val command = createCommand(
            draftId = draftId,
            createdAtEpochMillis = createdAtEpochMillis,
        )
        val hash = hashCharacter.repeat(64)
        val sourceAssetId = "asset-$draftId"
        val questionDocument = command.initialRevision.questionDocument.copy(
            blockEvidence = command.initialRevision.questionDocument.blockEvidence.map { evidence ->
                evidence.copy(sourceAssetId = sourceAssetId)
            },
        )
        return command.copy(
            sourceAsset = command.sourceAsset.copy(
                sourceAssetId = sourceAssetId,
                contentSha256 = hash,
                relativePath = "source-assets/$hash.jpg",
                createdAtEpochMillis = createdAtEpochMillis,
            ),
            initialRevision = command.initialRevision.copy(
                questionDocument = questionDocument,
                documentFingerprint = CapturedQuestionDocumentFingerprint.of(questionDocument),
            ),
            requestFingerprint = hash,
        )
    }

    private fun commitCommand() = CommitProblemDraftCommand(
        commandId = "commit-1",
        draftId = DRAFT_ID,
        expectedRevisionNumber = 2,
        problemId = "problem-captured-1",
        problemRevisionId = "problem-revision-captured-1",
        practiceUnitId = "practice-captured-1",
        errorBookEntryId = "entry-captured-1",
        estimatedSeconds = 180,
        committedAtEpochMillis = 4_000,
    )

    private fun assessmentCreateCommand(
        draftId: String,
        suffix: String,
        occurredAtEpochMillis: Long,
    ): CreateModelTaskCommand {
        val request = ModelTaskRequest(
            requestId = "assessment-$draftId-$suffix",
            input = CaptureAssessmentInput(
                draftId = draftId,
                sourceAssetId = ASSET_ID,
                origin = CaptureAssessmentOrigin.LIBRARY,
                imageWidth = 1_200,
                imageHeight = 1_600,
            ),
            occurredAtEpochMillis = occurredAtEpochMillis,
        )
        return CreateModelTaskCommand(
            taskId = "task-$draftId-$suffix",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            occurredAtEpochMillis = occurredAtEpochMillis,
        )
    }

    private fun parseCreateCommand(
        draftId: String,
        suffix: String,
        occurredAtEpochMillis: Long,
    ): CreateModelTaskCommand {
        val request = ModelTaskRequest(
            requestId = "parse-$draftId-$suffix",
            input = CaptureParseInput(
                draftId = draftId,
                origin = CaptureAssessmentOrigin.LIBRARY,
                basisRevisionNumber = 1,
                sourceAssets = listOf(
                    CaptureSourceAssetRef(
                        assetId = ASSET_ID,
                        sha256 = "a".repeat(64),
                        width = 1_200,
                        height = 1_600,
                        pageIndex = 0,
                    ),
                ),
                assessmentRequestId = "assessment-$draftId-base-65",
            ),
            occurredAtEpochMillis = occurredAtEpochMillis,
        )
        return CreateModelTaskCommand(
            taskId = "task-$draftId-parse-$suffix",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            occurredAtEpochMillis = occurredAtEpochMillis,
        )
    }

    private fun com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot.toQueuedTransition(
        occurredAtEpochMillis: Long,
    ) = TransitionModelTaskCommand(
        taskId = taskId,
        expectedStateVersion = stateVersion,
        expectedStatus = status,
        nextStatus = ModelTaskStatus.QUEUED,
        stage = ModelTaskStage.PREPARING,
        userMessage = "QUEUED",
        attemptCount = attemptCount,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private fun capturedDocument(
        markdown: String,
        provenance: QuestionBlockProvenance,
        reviewStatus: QuestionBlockReviewStatus,
        producerVersion: String? = null,
        writingLayer: WritingLayer = WritingLayer.UNKNOWN,
        sourceAssetId: String = ASSET_ID,
    ) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "document-$DRAFT_ID",
            title = "题目转写",
            blocks = listOf(ContentBlock.Paragraph("stem", markdown)),
        ),
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = sourceAssetId,
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = writingLayer,
                provenance = provenance,
                confidence = null,
                reviewStatus = reviewStatus,
                producerVersion = producerVersion,
            ),
        ),
    )

    private companion object {
        const val ASSET_ID = "asset-captured-1"
        const val DRAFT_ID = "draft-captured-1"
    }
}
