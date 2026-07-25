package com.tingyun.smartmistakebook.core.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspace
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceCodec
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceFingerprint
import com.tingyun.smartmistakebook.core.model.CaptureFinalConfirmationRequestIdentity
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProblemDraftEditWorkspaceDatabaseInstrumentedTest {
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
    fun oneHundredSavesDoNotAdvanceDraftHeadAndSamePayloadReplayIsIdempotent() = runBlocking {
        val draft = store.createProblemDraft(createCommand()).draft
        var expectedVersion = 0L
        var expectedFingerprint: String? = null
        var lastCommand: SaveProblemDraftEditWorkspaceCommand? = null

        repeat(100) { index ->
            val command = saveCommand(
                draft = draft,
                workspace = workspace(draft, title = "校对版本 ${index + 1}"),
                expectedVersion = expectedVersion,
                expectedFingerprint = expectedFingerprint,
                updatedAtEpochMillis = 2_000L + index,
            )
            val saved = store.saveProblemDraftEditWorkspace(command)
            expectedVersion = saved.workspace.workspaceVersion
            expectedFingerprint = saved.workspace.workspaceFingerprint
            lastCommand = command
        }

        val persistedDraft = checkNotNull(store.readProblemDraft(DRAFT_ID))
        assertEquals(1, persistedDraft.currentRevision.revisionNumber)
        assertEquals(draft.currentRevision.documentFingerprint, persistedDraft.currentRevision.documentFingerprint)
        assertEquals(100L, store.readProblemDraftEditWorkspace(DRAFT_ID)?.workspaceVersion)

        val replay = store.saveProblemDraftEditWorkspace(
            checkNotNull(lastCommand).copy(
                expectedWorkspaceVersion = 0,
                expectedWorkspaceFingerprint = null,
                updatedAtEpochMillis = 9_999,
            ),
        )
        assertFalse(replay.created)
        assertEquals(100L, replay.workspace.workspaceVersion)
        assertEquals(2_099L, replay.workspace.updatedAtEpochMillis)
    }

    @Test
    fun concurrentDifferentPayloadsConflictWhileWinnerReplayIsIdempotent() = runBlocking {
        val draft = store.createProblemDraft(createCommand()).draft
        val initial = store.saveProblemDraftEditWorkspace(
            saveCommand(draft, workspace(draft, "初稿"), 0, null, 2_000),
        ).workspace
        val commands = listOf("写者甲", "写者乙").mapIndexed { index, title ->
            saveCommand(
                draft = draft,
                workspace = workspace(draft, title),
                expectedVersion = initial.workspaceVersion,
                expectedFingerprint = initial.workspaceFingerprint,
                updatedAtEpochMillis = 3_000L + index,
            )
        }

        val outcomes = coroutineScope {
            commands.map { command -> async { runCatching { store.saveProblemDraftEditWorkspace(command) } } }
                .map { it.await() }
        }

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })
        assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is
            ProblemDraftEditWorkspaceConflictException)
        val winner = checkNotNull(store.readProblemDraftEditWorkspace(DRAFT_ID))
        val winnerCommand = commands.single { it.workspaceFingerprint == winner.workspaceFingerprint }
        val replay = store.saveProblemDraftEditWorkspace(winnerCommand)
        assertFalse(replay.created)
        assertEquals(winner, replay.workspace)
    }

    @Test
    fun migrationFromFivePreservesDraftAndWorkspaceSurvivesRestart() = runBlocking {
        store.close()
        val databaseName = "workspace-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 5)
            insertVersionFiveDraft(context, databaseName)
            var reopened = StudyDatabaseFactory.open(context, databaseName)
            val migratedDraft = checkNotNull(reopened.readProblemDraft(DRAFT_ID))
            assertNull(migratedDraft.requestFingerprint)
            assertNull(reopened.readProblemDraftEditWorkspace(DRAFT_ID))
            val expectedWorkspace = workspace(migratedDraft, "重启后仍在")
            val saved = reopened.saveProblemDraftEditWorkspace(
                saveCommand(migratedDraft, expectedWorkspace, 0, null, 2_000),
            ).workspace
            reopened.close()

            reopened = StudyDatabaseFactory.open(context, databaseName)
            val restored = checkNotNull(reopened.readProblemDraftEditWorkspace(DRAFT_ID))
            assertEquals(saved, restored)
            assertEquals(
                expectedWorkspace,
                CaptureDraftWorkspaceCodec.decode(restored.workspaceSnapshot),
            )
            reopened.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    @Test
    fun corruptPayloadAndStaleBasisFailClosed() = runBlocking {
        val draft = store.createProblemDraft(createCommand()).draft
        val edit = workspace(draft, "最终题面")
        val encoded = CaptureDraftWorkspaceCodec.encode(edit)
        assertTrue(
            runCatching {
                store.saveProblemDraftEditWorkspace(
                    saveCommand(draft, edit, 0, null, 2_000).copy(
                        workspaceFingerprint = "b".repeat(64),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                val corrupt = "{not-json}"
                store.saveProblemDraftEditWorkspace(
                    saveCommand(draft, edit, 0, null, 2_000).copy(
                        workspaceSnapshot = corrupt,
                        workspaceFingerprint = CaptureDraftWorkspaceFingerprint.ofEncoded(corrupt),
                    ),
                )
            }.isFailure,
        )
        store.saveProblemDraftEditWorkspace(
            saveCommand(draft, edit, 0, null, 2_000),
        ).workspace
        val confirmedRevision = confirmedRevision(draft, edit, 3_000)
        store.reviseProblemDraft(
            ReviseProblemDraftCommand(DRAFT_ID, 1, confirmedRevision),
        )
        assertTrue(runCatching { store.readProblemDraftEditWorkspace(DRAFT_ID) }.isFailure)
    }

    @Test
    fun olderWorkspaceIdentityCannotConfirmAReplacementWorkspace() = runBlocking {
        val draft = store.createProblemDraft(createCommand()).draft
        val first = store.saveProblemDraftEditWorkspace(
            saveCommand(draft, workspace(draft, "版本 A"), 0, null, 2_000),
        ).workspace
        val second = store.saveProblemDraftEditWorkspace(
            saveCommand(
                draft = draft,
                workspace = workspace(draft, "版本 B"),
                expectedVersion = first.workspaceVersion,
                expectedFingerprint = first.workspaceFingerprint,
                updatedAtEpochMillis = 2_500,
            ),
        ).workspace

        assertTrue(
            runCatching {
                store.confirmAndCommitProblemDraftFromWorkspace(
                    confirmAndCommitCommand(first.toExpectedWorkspace()),
                )
            }.exceptionOrNull() is ProblemDraftEditWorkspaceConflictException,
        )
        assertEquals(1, checkNotNull(store.readProblemDraft(DRAFT_ID)).currentRevision.revisionNumber)
        assertEquals(second, store.readProblemDraftEditWorkspace(DRAFT_ID))
    }

    @Test
    fun libraryConfirmationIsAtomicAndExactReceiptReplaysWithoutWorkspace() = runBlocking {
        val draft = store.createProblemDraft(createCommand()).draft
        val saved = store.saveProblemDraftEditWorkspace(
            saveCommand(draft, workspace(draft, "最终题面"), 0, null, 2_000),
        ).workspace
        val command = confirmAndCommitCommand(saved.toExpectedWorkspace())

        val created = store.confirmAndCommitProblemDraftFromWorkspace(command)

        assertTrue(created.created)
        assertNull(store.readProblemDraftEditWorkspace(DRAFT_ID))
        val committedDraft = checkNotNull(store.readProblemDraft(DRAFT_ID))
        assertEquals(2, committedDraft.currentRevision.revisionNumber)
        assertEquals("最终题面", committedDraft.currentRevision.title)
        val replay = store.confirmAndCommitProblemDraftFromWorkspace(command)
        assertFalse(replay.created)
        assertEquals(created.receipt, replay.receipt)
        assertTrue(
            runCatching {
                store.confirmAndCommitProblemDraftFromWorkspace(
                    command.copy(commit = command.commit.copy(estimatedSeconds = 181)),
                )
            }.exceptionOrNull() is ImmutablePayloadConflictException,
        )
    }

    @Test
    fun libraryConfirmationRollsBackRevisionWhenCommitFails() = runBlocking {
        val target = store.createProblemDraft(createCommand()).draft
        val saved = store.saveProblemDraftEditWorkspace(
            saveCommand(target, workspace(target, "不可半提交"), 0, null, 2_000),
        ).workspace
        val expected = saved.toExpectedWorkspace()
        val atomic = confirmAndCommitCommand(expected)
        val blockerDraftId = "draft-workspace-blocker"
        val blocker = store.createProblemDraft(
            createCommand(
                draftId = blockerDraftId,
                assetId = "asset-workspace-blocker",
                assetHash = "d".repeat(64),
            ),
        ).draft
        val blockerRevision = confirmedRevision(blocker, workspace(blocker, "占位题面"), 2_000)
        store.reviseProblemDraft(
            ReviseProblemDraftCommand(blockerDraftId, 1, blockerRevision),
        )
        store.commitProblemDraft(
            CommitProblemDraftCommand(
                commandId = "commit-workspace-blocker",
                draftId = blockerDraftId,
                expectedRevisionNumber = blockerRevision.revisionNumber,
                problemId = atomic.commit.problemId,
                problemRevisionId = atomic.commit.problemRevisionId,
                practiceUnitId = atomic.commit.practiceUnitId,
                errorBookEntryId = atomic.commit.errorBookEntryId,
                estimatedSeconds = 180,
                committedAtEpochMillis = 2_500,
            ),
        )

        assertTrue(
            runCatching { store.confirmAndCommitProblemDraftFromWorkspace(atomic) }.isFailure,
        )
        assertEquals(1, checkNotNull(store.readProblemDraft(DRAFT_ID)).currentRevision.revisionNumber)
        assertEquals(saved, store.readProblemDraftEditWorkspace(DRAFT_ID))
    }

    @Test
    fun tutorConfirmationRejectsOldIdentityAndExactSessionReplaysWithoutWorkspace() = runBlocking {

        val tutorDraftId = "draft-tutor-workspace"
        val tutorDraft = store.createProblemDraft(
            createCommand(
                draftId = tutorDraftId,
                assetId = "asset-tutor-workspace",
                assetHash = "c".repeat(64),
                origin = StudyDbValue.CaptureOrigin.TUTOR,
            ),
        ).draft
        val first = store.saveProblemDraftEditWorkspace(
            saveCommand(tutorDraft, workspace(tutorDraft, "讲题版本 A"), 0, null, 2_000),
        ).workspace
        val second = store.saveProblemDraftEditWorkspace(
            saveCommand(
                tutorDraft,
                workspace(tutorDraft, "讲题版本 B"),
                first.workspaceVersion,
                first.workspaceFingerprint,
                2_500,
            ),
        ).workspace
        assertTrue(
            runCatching {
                store.confirmTutorSessionFromWorkspace(
                    confirmTutorCommand(first.toExpectedWorkspace()),
                )
            }.exceptionOrNull() is ProblemDraftEditWorkspaceConflictException,
        )
        val command = confirmTutorCommand(second.toExpectedWorkspace())
        val created = store.confirmTutorSessionFromWorkspace(command)
        assertTrue(created.created)
        assertNull(store.readProblemDraftEditWorkspace(tutorDraftId))
        assertEquals("讲题版本 B", created.session.confirmedRevision.title)
        val replay = store.confirmTutorSessionFromWorkspace(command)
        assertFalse(replay.created)
        assertEquals(created.session, replay.session)
        assertTrue(
            runCatching {
                val changed = second.toExpectedWorkspace().copy(finalRequestId = "confirm-different")
                store.confirmTutorSessionFromWorkspace(confirmTutorCommand(changed))
            }.isFailure,
        )
    }

    @Test
    fun pendingCaptureFlowRefreshesWhenWorkspaceChanges() = runBlocking {
        val draft = store.createProblemDraft(createCommand()).draft
        val editedWorkspace = workspace(draft, title = "列表立即看到的校对版本")
        val refreshed = async(start = CoroutineStart.UNDISPATCHED) {
            store.observePendingCaptureDrafts().first { pending ->
                pending.singleOrNull()?.editWorkspace?.workspaceVersion == 1L
            }
        }

        store.saveProblemDraftEditWorkspace(
            saveCommand(
                draft = draft,
                workspace = editedWorkspace,
                expectedVersion = 0,
                expectedFingerprint = null,
                updatedAtEpochMillis = 2_000,
            ),
        )

        val pending = refreshed.await().single()
        assertEquals(1L, pending.editWorkspace?.workspaceVersion)
        assertEquals(
            "列表立即看到的校对版本",
            pending.editWorkspace?.let {
                CaptureDraftWorkspaceCodec.decode(it.workspaceSnapshot).workingDocument.document.title
            },
        )
    }

    private fun saveCommand(
        draft: ProblemDraftRecord,
        workspace: CaptureDraftWorkspace,
        expectedVersion: Long,
        expectedFingerprint: String?,
        updatedAtEpochMillis: Long,
    ): SaveProblemDraftEditWorkspaceCommand {
        val encoded = CaptureDraftWorkspaceCodec.encode(workspace)
        return SaveProblemDraftEditWorkspaceCommand(
            draftId = draft.draftId,
            basisRevisionNumber = draft.currentRevision.revisionNumber,
            expectedWorkspaceVersion = expectedVersion,
            expectedWorkspaceFingerprint = expectedFingerprint,
            snapshotSchemaVersion = workspace.schemaVersion,
            workspaceSnapshot = encoded,
            workspaceFingerprint = CaptureDraftWorkspaceFingerprint.ofEncoded(encoded),
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun workspace(draft: ProblemDraftRecord, title: String): CaptureDraftWorkspace {
        val confirmed = draft.currentRevision.questionDocument.copy(
            document = draft.currentRevision.questionDocument.document.copy(title = title),
            blockEvidence = draft.currentRevision.questionDocument.blockEvidence.map {
                it.copy(
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    producerVersion = null,
                )
            },
        )
        return CaptureDraftWorkspace(
            subject = "MATH",
            workingDocument = confirmed,
            editorMode = CaptureDraftEditorMode.STRUCTURED_DOCUMENT,
            userEditedFields = setOf(
                CaptureDraftEditedField.SUBJECT,
                CaptureDraftEditedField.TITLE,
            ),
            userEditedBlockIds = confirmed.document.blocks.map(ContentBlock::id).toSet(),
            baseCandidateFingerprint = draft.currentRevision.documentFingerprint,
            finalConfirmationRequest = CaptureFinalConfirmationRequestIdentity(
                requestId = "confirm-${draft.draftId}",
                occurredAtEpochMillis = 3_000,
            ),
        )
    }

    private fun confirmedRevision(
        draft: ProblemDraftRecord,
        workspace: CaptureDraftWorkspace,
        createdAtEpochMillis: Long,
    ) = ProblemDraftRevisionRecord(
        draftId = draft.draftId,
        revisionNumber = draft.currentRevision.revisionNumber + 1,
        basisRevisionNumber = draft.currentRevision.revisionNumber,
        subject = checkNotNull(workspace.subject),
        title = checkNotNull(workspace.workingDocument.document.title),
        questionDocument = workspace.workingDocument,
        documentFingerprint = CapturedQuestionDocumentFingerprint.of(workspace.workingDocument),
        author = StudyDbValue.ProblemDraftAuthor.USER,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    private fun ProblemDraftEditWorkspaceRecord.toExpectedWorkspace():
        ExpectedProblemDraftEditWorkspace {
        val workspace = CaptureDraftWorkspaceCodec.decode(workspaceSnapshot)
        val finalRequest = checkNotNull(workspace.finalConfirmationRequest)
        return ExpectedProblemDraftEditWorkspace(
            draftId = draftId,
            basisRevisionNumber = basisRevisionNumber,
            workspaceVersion = workspaceVersion,
            workspaceFingerprint = workspaceFingerprint,
            finalRequestId = finalRequest.requestId,
            finalOccurredAtEpochMillis = finalRequest.occurredAtEpochMillis,
        )
    }

    private fun confirmAndCommitCommand(
        expected: ExpectedProblemDraftEditWorkspace,
    ): ConfirmAndCommitProblemDraftFromWorkspaceCommand {
        val suffix = confirmationStableSuffix(expected)
        return ConfirmAndCommitProblemDraftFromWorkspaceCommand(
            workspace = expected,
            commit = CommitProblemDraftCommand(
                commandId = "commit-$suffix",
                draftId = expected.draftId,
                expectedRevisionNumber = expected.basisRevisionNumber + 1,
                problemId = "problem-$suffix",
                problemRevisionId = "revision-$suffix",
                practiceUnitId = "practice-$suffix",
                errorBookEntryId = "entry-$suffix",
                estimatedSeconds = 180,
                committedAtEpochMillis = expected.finalOccurredAtEpochMillis,
            ),
        )
    }

    private fun confirmTutorCommand(
        expected: ExpectedProblemDraftEditWorkspace,
    ) = ConfirmTutorSessionFromWorkspaceCommand(
        workspace = expected,
        sessionId = "tutor-session-${confirmationStableSuffix(expected)}",
    )

    private fun confirmationStableSuffix(expected: ExpectedProblemDraftEditWorkspace): String {
        val canonical = listOf(
            expected.draftId,
            expected.basisRevisionNumber.toString(),
            expected.workspaceVersion.toString(),
            expected.workspaceFingerprint,
            expected.finalRequestId,
            expected.finalOccurredAtEpochMillis.toString(),
        ).joinToString(separator = "\u001F")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .take(16)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun createCommand(
        draftId: String = DRAFT_ID,
        assetId: String = ASSET_ID,
        assetHash: String = "a".repeat(64),
        origin: String = StudyDbValue.CaptureOrigin.LIBRARY,
    ): CreateProblemDraftCommand {
        val document = candidateDocument(draftId, assetId)
        return CreateProblemDraftCommand(
            sourceAsset = CanonicalSourceAssetRecord(
                sourceAssetId = assetId,
                contentSha256 = assetHash,
                relativePath = "source-assets/$assetHash.jpg",
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
                createdAtEpochMillis = 1_000,
            ),
        )
    }

    private fun candidateDocument(draftId: String, assetId: String) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "document-$draftId",
            title = "待校对题目",
            blocks = listOf(ContentBlock.Paragraph("stem", "已知函数，求单调区间。")),
        ),
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = assetId,
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = WritingLayer.UNKNOWN,
                provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
                reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                producerVersion = "capture-import-v1",
            ),
        ),
    )

    private fun commitCommand(draftId: String, revision: Int, time: Long) =
        CommitProblemDraftCommand(
            commandId = "commit-$draftId",
            draftId = draftId,
            expectedRevisionNumber = revision,
            problemId = "problem-$draftId",
            problemRevisionId = "revision-$draftId",
            practiceUnitId = "practice-$draftId",
            errorBookEntryId = "entry-$draftId",
            estimatedSeconds = 180,
            committedAtEpochMillis = time,
        )

    private fun insertVersionFiveDraft(context: Context, databaseName: String) {
        val document = candidateDocument(DRAFT_ID, ASSET_ID)
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.insertOrThrow(
                "canonical_source_asset",
                null,
                ContentValues().apply {
                    put("source_asset_id", ASSET_ID)
                    put("content_sha256", "a".repeat(64))
                    put("relative_path", "source-assets/${"a".repeat(64)}.jpg")
                    put("mime_type", "image/jpeg")
                    put("byte_size", 4_096L)
                    put("width", 1_200)
                    put("height", 1_600)
                    put("source_type", StudyDbValue.SourceAssetType.PHOTO_PICKER)
                    put("created_at_epoch_millis", 1_000L)
                },
            )
            database.insertOrThrow(
                "problem_draft",
                null,
                ContentValues().apply {
                    put("draft_id", DRAFT_ID)
                    put("source_asset_id", ASSET_ID)
                    put("origin", StudyDbValue.CaptureOrigin.LIBRARY)
                    put("status", StudyDbValue.ProblemDraftStatus.EDITING)
                    put("current_revision_number", 1)
                    put("created_at_epoch_millis", 1_000L)
                    put("updated_at_epoch_millis", 1_000L)
                },
            )
            database.insertOrThrow(
                "problem_draft_revision",
                null,
                ContentValues().apply {
                    put("draft_id", DRAFT_ID)
                    put("revision_number", 1)
                    putNull("basis_revision_number")
                    putNull("subject")
                    put("title", "待校对题目")
                    put("question_document_snapshot", CapturedQuestionDocumentCodec.encode(document))
                    put("document_fingerprint", CapturedQuestionDocumentFingerprint.of(document))
                    put("author", StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT)
                    put("created_at_epoch_millis", 1_000L)
                },
            )
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DRAFT_ID = "draft-workspace"
        const val ASSET_ID = "asset-workspace"
    }
}
