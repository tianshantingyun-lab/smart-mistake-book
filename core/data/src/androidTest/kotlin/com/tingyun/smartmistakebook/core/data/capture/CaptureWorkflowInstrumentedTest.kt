package com.tingyun.smartmistakebook.core.data.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.CreateModelTaskCommand
import com.tingyun.smartmistakebook.core.data.model.FakeModelGateway
import com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest
import com.tingyun.smartmistakebook.core.domain.BatchImportPageStatus
import com.tingyun.smartmistakebook.core.domain.BatchImportStatus
import com.tingyun.smartmistakebook.core.domain.ConsumeCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureTranscriptionReview
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest
import com.tingyun.smartmistakebook.core.domain.CreateBatchImportRequest
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.PendingCaptureStage
import com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.CapturedQuestionIssueCode
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspace
import com.tingyun.smartmistakebook.core.model.CaptureFinalConfirmationRequestIdentity
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureWorkflowInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: StudyDatabasePort
    private lateinit var repository: RoomCaptureWorkflowRepository
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "capture-workflow-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        clearOwnedFlatDirectory(context.cacheDir, "captured_images")
        clearOwnedFlatDirectory(context.filesDir, "source-assets")
        clearOwnedDirectoryTree(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY)
        database = StudyDatabaseFactory.open(context, databaseName)
        repository = RoomCaptureWorkflowRepository(
            database,
            AndroidCanonicalAssetVault(context),
            noTextRecognizer(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        clearOwnedFlatDirectory(context.cacheDir, "captured_images")
        clearOwnedFlatDirectory(context.filesDir, "source-assets")
        clearOwnedDirectoryTree(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY)
    }

    @Test
    fun attachCleanRedrawAddsCleanRoleAssetToCommittedRevision() = runBlocking {
        val source = createPng(64, 64)
        val imported = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "attach-clean-import",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 1_000,
            ),
        )
        val committed = repository.confirmAndCommit(
            ConfirmCapturedProblemRequest(
                requestId = "attach-clean-confirm",
                draftId = imported.draftId,
                expectedRevisionNumber = imported.revisionNumber,
                subject = "MATH",
                title = "含图函数题",
                transcription = "求函数 f(x)=x^2 的单调区间。",
                writingLayer = CaptureWritingLayer.PRINTED,
                transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                occurredAtEpochMillis = 2_000,
            ),
        )
        assertTrue(committed.created)

        val before = checkNotNull(database.readMistakeDetail(committed.errorBookEntryId))
        assertEquals(1, before.sourceAssets.size)
        assertEquals("QUESTION_SOURCE", before.sourceAssets.single().role)

        assertTrue(
            repository.attachCleanRedrawImage(
                problemRevisionId = committed.problemRevisionId,
                cleanImageBytes = createPng(32, 32).readBytes(),
                cleanImageMimeType = "image/png",
            ),
        )

        val after = checkNotNull(database.readMistakeDetail(committed.errorBookEntryId))
        val roles = after.sourceAssets.map { it.role }.toSet()
        assertTrue("expected QUESTION_SOURCE role", roles.contains("QUESTION_SOURCE"))
        assertTrue("expected CLEAN_IMAGE role", roles.contains("CLEAN_IMAGE"))
    }

    @Test
    fun committingWithGeneratorAttachesCleanRedrawAutomatically() = runBlocking {
        val generatingRepository = RoomCaptureWorkflowRepository(
            database,
            AndroidCanonicalAssetVault(context),
            noTextRecognizer(),
            cleanRedraw = com.tingyun.smartmistakebook.core.domain.CleanImageGenerator { original, mime ->
                com.tingyun.smartmistakebook.core.domain.CleanImageResult(
                    createPng(24, 24).readBytes(),
                    "image/png",
                )
            },        )
        val source = createPng(48, 48)
        val imported = generatingRepository.importDraft(
            CaptureDraftImportRequest(
                requestId = "auto-redraw-import",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 1_000,
            ),
        )
        val committed = generatingRepository.confirmAndCommit(
            ConfirmCapturedProblemRequest(
                requestId = "auto-redraw-confirm",
                draftId = imported.draftId,
                expectedRevisionNumber = imported.revisionNumber,
                subject = "MATH",
                title = "自动重绘函数题",
                transcription = "求函数 f(x)=x^2 的单调区间。",
                writingLayer = CaptureWritingLayer.PRINTED,
                transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                occurredAtEpochMillis = 2_000,
            ),
        )
        assertTrue(committed.created)

        val detail = checkNotNull(database.readMistakeDetail(committed.errorBookEntryId))
        assertTrue(
            "expected automatic CLEAN_IMAGE attach",
            detail.sourceAssets.map { it.role }.contains("CLEAN_IMAGE"),
        )
    }

    @Test
    fun batchImportKeepsSuccessfulPagesAndRetriesOnlyTheFailedPage() = runBlocking {
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val batchRepository = RoomBatchImportRepository(
            database = database,
            capture = repository,
            processingScope = processingScope,
            sourceStaging = AndroidBatchImportSourceStaging(context),
        )
        try {
            val first = createPng(72, 96)
            val second = createPng(80, 104)
            val recoverable = createInvalidImage()

            val created = batchRepository.createBatchImport(
                CreateBatchImportRequest(
                    requestId = "batch-partial-retry",
                    localUris = listOf(
                        privateUri(first).toString(),
                        privateUri(second).toString(),
                        privateUri(recoverable).toString(),
                    ),
                    occurredAtEpochMillis = 1_000,
                ),
            )
            val partiallyCompleted = withTimeout(15_000) {
                batchRepository.observeBatchImports().first { jobs ->
                    jobs.firstOrNull()?.status == BatchImportStatus.COMPLETED
                }.first()
            }

            assertEquals(created.jobId, partiallyCompleted.jobId)
            assertEquals(2, partiallyCompleted.readyCount)
            assertEquals(1, partiallyCompleted.failedCount)
            assertEquals(
                setOf(BatchImportPageStatus.READY, BatchImportPageStatus.FAILED),
                partiallyCompleted.pages.map { it.status }.toSet(),
            )

            val failedPage = partiallyCompleted.pages.single {
                it.status == BatchImportPageStatus.FAILED
            }
            val failedSourceUri = checkNotNull(database.readBatchImportJob(created.jobId))
                .pages.single { it.pageIndex == failedPage.pageIndex }
                .sourceUri
            writePng(stagedSourceFile(failedSourceUri), 88, 112)
            batchRepository.retryBatchImportPage(created.jobId, failedPage.pageIndex)
            val completed = withTimeout(15_000) {
                batchRepository.observeBatchImports().first { jobs ->
                    jobs.firstOrNull()?.let {
                        it.readyCount == 3 && it.status == BatchImportStatus.COMPLETED
                    } == true
                }.first()
            }

            assertEquals(BatchImportStatus.COMPLETED, completed.status)
            assertEquals(3, completed.readyCount)
            assertEquals(0, completed.failedCount)
            assertEquals(3, completed.pages.mapNotNull { it.draftId }.distinct().size)
        } finally {
            processingScope.cancel()
        }
    }

    @Test
    fun additionalPageSurvivesResumeWithStableOrderAndIdempotentReplay() = runBlocking {
        val firstSource = createPng(width = 96, height = 128)
        val imported = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "source-bundle-first",
                localUri = privateUri(firstSource).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 1_000,
            ),
        )
        val secondSource = createPng(width = 80, height = 120)
        val append = AppendCaptureDraftPageRequest(
            requestId = "source-bundle-second",
            draftId = imported.draftId,
            expectedRevisionNumber = imported.revisionNumber,
            expectedPageCount = 1,
            localUri = privateUri(secondSource).toString(),
            source = CaptureInputSource.CAMERA,
            occurredAtEpochMillis = 2_000,
        )

        val appended = repository.appendDraftPage(append)
        val replayed = repository.appendDraftPage(append)
        val resumed = checkNotNull(repository.readPendingCapture(imported.draftId))

        assertEquals(listOf(0, 1), appended.sourcePages.map { it.pageIndex })
        assertEquals(appended.sourcePages, replayed.sourcePages)
        assertEquals(appended.sourcePages, resumed.sourcePages)
        assertEquals(listOf(null, null), resumed.sourcePageAssessmentTasks)
        assertTrue(resumed.sourcePages.all { File(Uri.parse(it.imageUri).path.orEmpty()).isFile })
    }

    @Test
    fun editWorkspaceIsRecoverablePreferredByPendingReadsAndExplicitlyConsumable() = runBlocking {
        val source = createPng(width = 96, height = 128)
        val imported = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "workspace-recovery",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 1_000,
            ),
        )
        val base = checkNotNull(repository.readPendingCapture(imported.draftId))
        val workingDocument = base.questionDocument.copy(
            document = base.questionDocument.document.copy(title = "用户校对后的函数题"),
            blockEvidence = base.questionDocument.blockEvidence.map {
                it.copy(
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    producerVersion = null,
                )
            },
        )
        val saved = repository.saveDraftWorkspace(
            SaveCaptureDraftWorkspaceRequest(
                draftId = imported.draftId,
                basisRevisionNumber = base.currentRevisionNumber,
                expectedWorkspaceVersion = 0,
                expectedWorkspaceFingerprint = null,
                workspace = CaptureDraftWorkspace(
                    subject = "MATH",
                    workingDocument = workingDocument,
                    editorMode = CaptureDraftEditorMode.STRUCTURED_DOCUMENT,
                    userEditedFields = setOf(
                        CaptureDraftEditedField.SUBJECT,
                        CaptureDraftEditedField.TITLE,
                    ),
                    userEditedBlockIds = workingDocument.document.blocks.map(ContentBlock::id).toSet(),
                    baseCandidateFingerprint = base.currentRevisionDocumentFingerprint,
                    finalConfirmationRequest = CaptureFinalConfirmationRequestIdentity(
                        requestId = "confirm-workspace-recovery",
                        occurredAtEpochMillis = 3_000,
                    ),
                ),
                occurredAtEpochMillis = 2_000,
            ),
        )

        val resumed = checkNotNull(repository.readPendingCapture(imported.draftId))
        assertEquals("MATH", resumed.subject)
        assertEquals("用户校对后的函数题", resumed.title)
        assertEquals(workingDocument, resumed.questionDocument)
        assertEquals(saved, resumed.workspace)
        val pending = repository.observePendingCaptures().first()
            .single { it.draftId == imported.draftId }
        assertEquals("MATH", pending.subject)
        assertEquals("用户校对后的函数题", pending.title)

        assertTrue(
            repository.consumeDraftWorkspace(
                ConsumeCaptureDraftWorkspaceRequest(saved.identity),
            ),
        )
        assertNull(repository.readDraftWorkspace(imported.draftId))
        assertFalse(
            repository.consumeDraftWorkspace(
                ConsumeCaptureDraftWorkspaceRequest(saved.identity),
            ),
        )
    }

    @Test
    fun importCorrectsOrientationStripsExifAndCommitsOneVisibleMistake() = runBlocking {
        val source = createJpeg(width = 80, height = 40, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val requestId = "import-orientation"
        val (draft, secondDraft) = coroutineScope {
            val first = async {
                repository.importDraft(
                    CaptureDraftImportRequest(
                        requestId = requestId,
                        localUri = privateUri(source).toString(),
                        source = CaptureInputSource.PHOTO_PICKER,
                        origin = CaptureEntryOrigin.LIBRARY,
                        occurredAtEpochMillis = 1_000,
                    ),
                )
            }
            val secondRepository = RoomCaptureWorkflowRepository(
                database,
                AndroidCanonicalAssetVault(context),
                noTextRecognizer(),
            )
            val second = async {
                secondRepository.importDraft(
                    CaptureDraftImportRequest(
                        requestId = "same-pixels-new-capture",
                        localUri = privateUri(source).toString(),
                        source = CaptureInputSource.CAMERA,
                        origin = CaptureEntryOrigin.TUTOR,
                        occurredAtEpochMillis = 9_000,
                    ),
                )
            }
            first.await() to second.await()
        }

        assertEquals(40, draft.width)
        assertEquals(80, draft.height)
        val canonicalFiles = File(context.filesDir, "source-assets")
            .listFiles()
            .orEmpty()
            .filter { !it.name.startsWith('.') }
        assertEquals(1, canonicalFiles.size)
        assertEquals(
            ExifInterface.ORIENTATION_UNDEFINED,
            ExifInterface(canonicalFiles.single()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            ),
        )

        assertFalse(secondDraft.draftId == draft.draftId)
        assertEquals(draft.sourceAssetId, secondDraft.sourceAssetId)
        assertEquals(
            1,
            File(context.filesDir, "source-assets").listFiles().orEmpty()
                .count { !it.name.startsWith('.') },
        )
        val firstReplay = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = requestId,
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 1_000,
            ),
        )
        val secondReplay = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "same-pixels-new-capture",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.TUTOR,
                occurredAtEpochMillis = 9_000,
            ),
        )
        assertEquals(draft.draftId, firstReplay.draftId)
        assertEquals(secondDraft.draftId, secondReplay.draftId)

        val confirmation = ConfirmCapturedProblemRequest(
            requestId = "confirm-orientation",
            draftId = draft.draftId,
            expectedRevisionNumber = draft.revisionNumber,
            subject = "MATH",
            title = "函数图像",
            transcription = "观察函数图像并判断单调区间。",
            writingLayer = CaptureWritingLayer.MIXED,
            transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
            occurredAtEpochMillis = 2_000,
        )
        val committed = repository.confirmAndCommit(confirmation)
        assertTrue(committed.created)
        assertEquals(1, database.countMistakes())
        assertEquals("函数图像", database.findMistakeBySourceKey("capture:${draft.draftId}")?.title)

        val replay = repository.confirmAndCommit(confirmation)
        assertFalse(replay.created)
        assertEquals(1, database.countMistakes())
    }

    @Test
    fun importReplayRejectsPersistedBindingChangesBeforeReadingCandidateImage() = runBlocking {
        val source = createPng(width = 72, height = 96)
        val original = CaptureDraftImportRequest(
            requestId = "payload-bound-import",
            localUri = privateUri(source).toString(),
            source = CaptureInputSource.CAMERA,
            origin = CaptureEntryOrigin.TUTOR,
            occurredAtEpochMillis = 9_500,
        )
        val imported = repository.importDraft(original)
        val storedRevision = checkNotNull(database.readProblemDraft(imported.draftId))
            .currentRevision
            .revisionNumber

        listOf(
            original.copy(source = CaptureInputSource.PHOTO_PICKER),
            original.copy(origin = CaptureEntryOrigin.LIBRARY),
            original.copy(occurredAtEpochMillis = original.occurredAtEpochMillis + 1),
        ).forEach { conflictingReplay ->
            assertTrue(runCatching { repository.importDraft(conflictingReplay) }.isFailure)
        }

        assertEquals(
            storedRevision,
            checkNotNull(database.readProblemDraft(imported.draftId)).currentRevision.revisionNumber,
        )
        assertEquals(
            1,
            File(context.filesDir, "source-assets").listFiles().orEmpty()
                .count { !it.name.startsWith('.') },
        )
    }

    @Test
    fun importReplayRejectsDifferentCanonicalImageAndLeavesSafeContentAddressedOrphan() = runBlocking {
        val originalSource = createPng(width = 76, height = 98)
        val differentSource = createPng(width = 118, height = 154)
        val original = CaptureDraftImportRequest(
            requestId = "image-identity-residual",
            localUri = privateUri(originalSource).toString(),
            source = CaptureInputSource.PHOTO_PICKER,
            origin = CaptureEntryOrigin.LIBRARY,
            occurredAtEpochMillis = 9_800,
        )
        val imported = repository.importDraft(original)

        val replay = runCatching {
            repository.importDraft(
                original.copy(localUri = privateUri(differentSource).toString()),
            )
        }

        assertTrue(replay.isFailure)
        assertEquals(imported, repository.importDraft(original))
        assertTrue(originalSource.delete())
        assertTrue(runCatching { repository.importDraft(original) }.isFailure)
        assertEquals(
            2,
            File(context.filesDir, "source-assets").listFiles().orEmpty()
                .count { !it.name.startsWith('.') },
        )
    }

    @Test
    fun sourceVerificationPreventsLibraryCommitAndTutorSessionMutation() = runBlocking {
        val libraryDraft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "tampered-library-import",
                localUri = privateUri(createPng(width = 84, height = 108)).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 16_000,
            ),
        )
        val libraryBefore = checkNotNull(database.readProblemDraft(libraryDraft.draftId))
        val libraryFile = File(context.filesDir, libraryBefore.sourceAsset.relativePath)
        val tamperedBytes = libraryFile.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        libraryFile.writeBytes(tamperedBytes)

        val libraryCommit = runCatching {
            repository.confirmAndCommit(
                ConfirmCapturedProblemRequest(
                    requestId = "tampered-library-confirm",
                    draftId = libraryDraft.draftId,
                    expectedRevisionNumber = libraryDraft.revisionNumber,
                    subject = "MATH",
                    title = "函数定义域",
                    transcription = "求函数的定义域。",
                    writingLayer = CaptureWritingLayer.PRINTED,
                    transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                    occurredAtEpochMillis = 16_100,
                ),
            )
        }
        assertTrue(libraryCommit.isFailure)
        assertEquals(
            libraryBefore.currentRevision.revisionNumber,
            checkNotNull(database.readProblemDraft(libraryDraft.draftId))
                .currentRevision
                .revisionNumber,
        )
        assertEquals(0, database.countMistakes())

        val tutorDraft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "missing-tutor-confirm-import",
                localUri = privateUri(createPng(width = 90, height = 120)).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.TUTOR,
                occurredAtEpochMillis = 16_200,
            ),
        )
        val tutorBefore = checkNotNull(database.readProblemDraft(tutorDraft.draftId))
        assertTrue(File(context.filesDir, tutorBefore.sourceAsset.relativePath).delete())

        val tutorConfirmation = runCatching {
            repository.confirmForTutoring(
                ConfirmCapturedProblemRequest(
                    requestId = "missing-tutor-confirm",
                    draftId = tutorDraft.draftId,
                    expectedRevisionNumber = tutorDraft.revisionNumber,
                    subject = "PHYSICS",
                    title = "匀变速运动",
                    transcription = "判断物体的运动状态。",
                    writingLayer = CaptureWritingLayer.PRINTED,
                    transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                    occurredAtEpochMillis = 16_300,
                ),
            )
        }
        assertTrue(tutorConfirmation.isFailure)
        val tutorAfter = checkNotNull(database.readPendingCaptureDraft(tutorDraft.draftId))
        assertEquals(tutorBefore.currentRevision.revisionNumber, tutorAfter.draft.currentRevision.revisionNumber)
        assertEquals(null, tutorAfter.tutorSessionId)
        assertEquals(0, database.countMistakes())
    }

    @Test
    fun sourceVerificationPreventsTutorSaveAndOverridesReadyPendingStage() = runBlocking {
        val draft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "missing-tutor-save-import",
                localUri = privateUri(createPng(width = 92, height = 124)).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.TUTOR,
                occurredAtEpochMillis = 17_000,
            ),
        )
        val session = repository.confirmForTutoring(
            ConfirmCapturedProblemRequest(
                requestId = "missing-tutor-save-confirm",
                draftId = draft.draftId,
                expectedRevisionNumber = draft.revisionNumber,
                subject = "CHEMISTRY",
                title = "氧化还原反应",
                transcription = "判断反应中的氧化剂。",
                writingLayer = CaptureWritingLayer.PRINTED,
                transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                occurredAtEpochMillis = 17_100,
            ),
        )
        val storedSession = checkNotNull(database.readTutorSession(session.sessionId))
        val sourceFile = File(context.filesDir, storedSession.sourceAsset.relativePath)
        val tamperedBytes = sourceFile.readBytes().also { bytes ->
            bytes[0] = (bytes.first().toInt() xor 0x01).toByte()
        }
        sourceFile.writeBytes(tamperedBytes)

        val save = runCatching {
            repository.saveTutorSession(
                SaveTutorSessionRequest(
                    requestId = "missing-tutor-save",
                    sessionId = session.sessionId,
                    occurredAtEpochMillis = 17_200,
                ),
            )
        }

        assertTrue(save.isFailure)
        assertEquals(null, checkNotNull(database.readTutorSession(session.sessionId)).commitReceipt)
        assertEquals(0, database.countMistakes())
        val pending = repository.observePendingCaptures().first().single()
        assertEquals(PendingCaptureStage.SOURCE_UNAVAILABLE, pending.stage)
        assertEquals(null, pending.tutorSessionId)
    }

    @Test
    fun libraryCommitAttachesCleanRedrawInTheBackgroundAndReturnsImmediately() = runBlocking {
        val cleanProduced = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val asyncRepository = RoomCaptureWorkflowRepository(
            database,
            AndroidCanonicalAssetVault(context),
            noTextRecognizer(),
            cleanRedraw = com.tingyun.smartmistakebook.core.domain.CleanImageGenerator { original, mime ->
                kotlinx.coroutines.delay(250)
                cleanProduced.set(true)
                com.tingyun.smartmistakebook.core.domain.CleanImageResult(
                    createPng(22, 22).readBytes(),
                    "image/png",
                )
            },
            cleanRedrawScope = saveScope,
        )
        try {
            val source = createPng(width = 96, height = 128)
            val draft = asyncRepository.importDraft(
                CaptureDraftImportRequest(
                    requestId = "library-async-commit-import",
                    localUri = privateUri(source).toString(),
                    source = CaptureInputSource.CAMERA,
                    origin = CaptureEntryOrigin.LIBRARY,
                    occurredAtEpochMillis = 70_000,
                ),
            )
            // confirmAndCommit must return promptly even though the redraw is still running.
            val committed = withTimeout(2_000) {
                asyncRepository.confirmAndCommit(
                    ConfirmCapturedProblemRequest(
                        requestId = "library-async-commit-confirm",
                        draftId = draft.draftId,
                        expectedRevisionNumber = draft.revisionNumber,
                        subject = "MATH",
                        title = "异步重绘直存",
                        transcription = "求函数 f(x)=x² 的单调区间。",
                        writingLayer = CaptureWritingLayer.PRINTED,
                        transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                        occurredAtEpochMillis = 71_000,
                    ),
                )
            }
            assertTrue(committed.created)
            assertFalse(cleanProduced.get())

            withTimeout(5_000) {
                while (true) {
                    val detail = checkNotNull(database.readMistakeDetail(committed.errorBookEntryId))
                    if (detail.sourceAssets.any { it.role == "CLEAN_IMAGE" }) break
                    kotlinx.coroutines.delay(50)
                }
            }
            assertTrue(cleanProduced.get())
        } finally {
            saveScope.cancel()
        }
    }

    @Test
    fun tutorSaveAttachesCleanRedrawInTheBackgroundAndReturnsImmediately() = runBlocking {
        val cleanProduced = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val asyncRepository = RoomCaptureWorkflowRepository(
            database,
            AndroidCanonicalAssetVault(context),
            noTextRecognizer(),
            cleanRedraw = com.tingyun.smartmistakebook.core.domain.CleanImageGenerator { original, mime ->
                // Simulate a slow remote edit so the save must return first.
                kotlinx.coroutines.delay(300)
                cleanProduced.set(true)
                com.tingyun.smartmistakebook.core.domain.CleanImageResult(
                    createPng(20, 20).readBytes(),
                    "image/png",
                )
            },
            cleanRedrawScope = saveScope,
        )
        try {
            val source = createPng(width = 96, height = 128)
            val draft = asyncRepository.importDraft(
                CaptureDraftImportRequest(
                    requestId = "tutor-async-save-import",
                    localUri = privateUri(source).toString(),
                    source = CaptureInputSource.CAMERA,
                    origin = CaptureEntryOrigin.TUTOR,
                    occurredAtEpochMillis = 60_000,
                ),
            )
            val session = asyncRepository.confirmForTutoring(
                ConfirmCapturedProblemRequest(
                    requestId = "tutor-async-save-confirm",
                    draftId = draft.draftId,
                    expectedRevisionNumber = draft.revisionNumber,
                    subject = "PHYSICS",
                    title = "异步重绘测试",
                    transcription = "物块在斜面上受力分析。",
                    writingLayer = CaptureWritingLayer.PRINTED,
                    transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                    occurredAtEpochMillis = 61_000,
                ),
            )

            // saveTutorSession must return promptly even though the redraw is still running.
            val saved = withTimeout(2_000) {
                asyncRepository.saveTutorSession(
                    SaveTutorSessionRequest(
                        requestId = "tutor-async-save",
                        sessionId = session.sessionId,
                        occurredAtEpochMillis = 62_000,
                    ),
                )
            }
            assertTrue(saved.created)
            assertFalse(cleanProduced.get())

            // The clean asset lands once the background redraw completes.
            withTimeout(5_000) {
                while (true) {
                    val detail = checkNotNull(database.readMistakeDetail(saved.errorBookEntryId))
                    if (detail.sourceAssets.any { it.role == "CLEAN_IMAGE" }) break
                    kotlinx.coroutines.delay(50)
                }
            }
            assertTrue(cleanProduced.get())
        } finally {
            saveScope.cancel()
        }
    }

    @Test
    fun tutorCaptureResumesWithoutMistakeAndOnlyExplicitSaveFormalizesIt() = runBlocking {
        val source = createPng(width = 120, height = 160)
        val draft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "tutor-session-import",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.TUTOR,
                occurredAtEpochMillis = 10_000,
            ),
        )
        val confirmation = ConfirmCapturedProblemRequest(
            requestId = "tutor-session-confirm",
            draftId = draft.draftId,
            expectedRevisionNumber = draft.revisionNumber,
            subject = "PHYSICS",
            title = "斜面受力分析",
            transcription = "物块静止在粗糙斜面上，分析它受到的力。",
            writingLayer = CaptureWritingLayer.PRINTED,
            transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
            occurredAtEpochMillis = 11_000,
        )

        val session = repository.confirmForTutoring(confirmation)
        assertFalse(session.isSaved)
        assertEquals(null, session.errorBookEntryId)
        assertTrue(session.sourceImageUri.startsWith("file:"))
        assertEquals(0, database.countMistakes())

        val reopenedRepository = RoomCaptureWorkflowRepository(
            database,
            AndroidCanonicalAssetVault(context),
            noTextRecognizer(),
        )
        assertEquals(session, reopenedRepository.readTutorSession(session.sessionId))
        assertEquals(session, reopenedRepository.confirmForTutoring(confirmation))
        assertEquals(0, database.countMistakes())
        assertTrue(
            runCatching {
                reopenedRepository.confirmForTutoring(
                    confirmation.copy(
                        workspaceIdentity = confirmation.workspaceIdentity.copy(
                            workspaceFingerprint = "f".repeat(64),
                        ),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching { reopenedRepository.confirmAndCommit(confirmation) }.isFailure,
        )
        assertEquals(0, database.countMistakes())

        val firstSave = reopenedRepository.saveTutorSession(
            SaveTutorSessionRequest(
                requestId = "save-tutor-session",
                sessionId = session.sessionId,
                occurredAtEpochMillis = 12_000,
            ),
        )
        assertTrue(firstSave.created)
        assertEquals(1, database.countMistakes())
        val secondSave = reopenedRepository.saveTutorSession(
            SaveTutorSessionRequest(
                requestId = "save-tutor-session-again",
                sessionId = session.sessionId,
                occurredAtEpochMillis = 13_000,
            ),
        )
        assertFalse(secondSave.created)
        assertEquals(firstSave.errorBookEntryId, secondSave.errorBookEntryId)
        assertEquals(1, database.countMistakes())
        val saved = checkNotNull(reopenedRepository.readTutorSession(session.sessionId))
        assertTrue(saved.isSaved)
        assertEquals(firstSave.errorBookEntryId, saved.errorBookEntryId)

        val librarySource = createPng(width = 100, height = 140)
        val libraryDraft = reopenedRepository.importDraft(
            CaptureDraftImportRequest(
                requestId = "library-direct-import",
                localUri = privateUri(librarySource).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 14_000,
            ),
        )
        val libraryConfirmation = ConfirmCapturedProblemRequest(
            requestId = "library-direct-confirm",
            draftId = libraryDraft.draftId,
            expectedRevisionNumber = libraryDraft.revisionNumber,
            subject = "PHYSICS",
            title = "错题本直接保存",
            transcription = "物块静止在粗糙斜面上，分析它受到的力。",
            writingLayer = CaptureWritingLayer.PRINTED,
            transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
            occurredAtEpochMillis = 15_000,
        )
        assertTrue(
            runCatching { reopenedRepository.confirmForTutoring(libraryConfirmation) }.isFailure,
        )
        assertTrue(reopenedRepository.confirmAndCommit(libraryConfirmation).created)
        assertEquals(2, database.countMistakes())
    }

    @Test
    fun replacementKeepsOnePendingDraftAndFailedReplacementPreservesOriginal() = runBlocking {
        val originalSource = createPng(width = 96, height = 128)
        val original = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "replace-original",
                localUri = privateUri(originalSource).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 18_000,
            ),
        )
        val replacementSource = createPng(width = 128, height = 160)
        val request = ReplaceCaptureDraftRequest(
            requestId = "replace-original-once",
            replacedDraftId = original.draftId,
            expectedReplacedRevisionNumber = original.revisionNumber,
            localUri = privateUri(replacementSource).toString(),
            source = CaptureInputSource.CAMERA,
            occurredAtEpochMillis = 18_100,
        )

        val replaced = repository.replaceDraft(request)

        assertFalse(replaced.draftId == original.draftId)
        assertEquals(
            StudyDbValue.ProblemDraftStatus.ABANDONED,
            database.readProblemDraft(original.draftId)?.status,
        )
        assertEquals(listOf(replaced.draftId), repository.observePendingCaptures().first().map { it.draftId })
        val changedReplay = runCatching {
            repository.replaceDraft(
                request.copy(
                    localUri = privateUri(createPng(width = 132, height = 164)).toString(),
                ),
            )
        }
        assertTrue(changedReplay.isFailure)
        assertTrue(replacementSource.delete())
        assertEquals(replaced, repository.replaceDraft(request))
        assertEquals(listOf(replaced.draftId), repository.observePendingCaptures().first().map { it.draftId })

        val retained = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "replace-failure-original",
                localUri = privateUri(createPng(width = 80, height = 104)).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.TUTOR,
                occurredAtEpochMillis = 18_200,
            ),
        )
        val failed = runCatching {
            repository.replaceDraft(
                ReplaceCaptureDraftRequest(
                    requestId = "replace-failure",
                    replacedDraftId = retained.draftId,
                    expectedReplacedRevisionNumber = retained.revisionNumber,
                    localUri = privateUri(createJpeg(width = 8_193, height = 1)).toString(),
                    source = CaptureInputSource.CAMERA,
                    occurredAtEpochMillis = 18_300,
                ),
            )
        }
        assertTrue(failed.isFailure)
        assertEquals(
            StudyDbValue.ProblemDraftStatus.EDITING,
            database.readProblemDraft(retained.draftId)?.status,
        )
        assertTrue(repository.observePendingCaptures().first().any { it.draftId == retained.draftId })
    }

    @Test
    fun replacementRemainsSuccessfulWhenPostTransactionOcrFails() = runBlocking {
        val original = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "replace-ocr-failure-original",
                localUri = privateUri(createPng(width = 82, height = 106)).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 18_400,
            ),
        )
        val failingOcrRepository = RoomCaptureWorkflowRepository(
            database,
            AndroidCanonicalAssetVault(context),
            LocalQuestionTextRecognizer { _, _, _ -> error("forced OCR failure") },
        )

        val replacement = failingOcrRepository.replaceDraft(
            ReplaceCaptureDraftRequest(
                requestId = "replace-after-durable-boundary",
                replacedDraftId = original.draftId,
                expectedReplacedRevisionNumber = original.revisionNumber,
                localUri = privateUri(createPng(width = 86, height = 110)).toString(),
                source = CaptureInputSource.CAMERA,
                occurredAtEpochMillis = 18_500,
            ),
        )

        assertEquals(CaptureRecognitionState.FAILED, replacement.recognition.state)
        assertEquals(
            StudyDbValue.ProblemDraftStatus.ABANDONED,
            database.readProblemDraft(original.draftId)?.status,
        )
        assertEquals(
            StudyDbValue.ProblemDraftStatus.EDITING,
            database.readProblemDraft(replacement.draftId)?.status,
        )
        assertEquals(listOf(replacement.draftId), repository.observePendingCaptures().first().map { it.draftId })
    }

    @Test
    fun splitDraftCropsOnePageAtomicallyAndReplaysWithoutNewFiles() = runBlocking {
        val original = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "split-original",
                localUri = privateUri(createPng(width = 200, height = 400)).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 18_600,
            ),
        )
        val request = SplitCaptureDraftRequest(
            requestId = "split-original-once",
            draftId = original.draftId,
            expectedRevisionNumber = original.revisionNumber,
            assessmentRequestId = "capture-assess:split-original",
            sourceAssetId = original.sourceAssetId,
            regions = listOf(
                NormalizedSourceRegion(0.05, 0.02, 0.95, 0.40),
                NormalizedSourceRegion(0.05, 0.48, 0.95, 0.96),
            ),
            occurredAtEpochMillis = 18_700,
        )

        val first = repository.splitDraft(request)

        assertTrue(first.created)
        assertEquals(2, first.splitDrafts.size)
        assertTrue(first.splitDrafts.all { it.width < original.width && it.height < original.height })
        assertEquals(
            StudyDbValue.ProblemDraftStatus.ABANDONED,
            database.readProblemDraft(original.draftId)?.status,
        )
        assertEquals(
            first.splitDrafts.map { it.draftId }.toSet(),
            repository.observePendingCaptures().first().map { it.draftId }.toSet(),
        )
        val assetFilesAfterFirstSplit = File(context.filesDir, "source-assets")
            .listFiles()
            .orEmpty()
            .map { it.name }
            .toSet()

        val replay = repository.splitDraft(request)

        assertFalse(replay.created)
        assertEquals(first.splitDrafts, replay.splitDrafts)
        assertEquals(
            assetFilesAfterFirstSplit,
            File(context.filesDir, "source-assets").listFiles().orEmpty().map { it.name }.toSet(),
        )
    }

    @Test
    fun endingTutorSessionPersistsTerminalDispositionAndCannotRaceWithSave() = runBlocking {
        val draft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "end-tutor-import",
                localUri = privateUri(createPng(width = 112, height = 144)).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.TUTOR,
                occurredAtEpochMillis = 19_000,
            ),
        )
        val session = repository.confirmForTutoring(
            ConfirmCapturedProblemRequest(
                requestId = "end-tutor-confirm",
                draftId = draft.draftId,
                expectedRevisionNumber = draft.revisionNumber,
                subject = "PHYSICS",
                title = "临时受力分析",
                transcription = "分析物体受到的力。",
                writingLayer = CaptureWritingLayer.PRINTED,
                transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                occurredAtEpochMillis = 19_100,
            ),
        )

        val ended = repository.endTutorSessionWithoutSaving(
            EndTutorSessionWithoutSaveRequest(
                sessionId = session.sessionId,
                occurredAtEpochMillis = 19_200,
            ),
        )
        assertTrue(ended.created)
        assertEquals(19_200, ended.endedAtEpochMillis)
        assertEquals(
            TutorSessionDisposition.ENDED_WITHOUT_SAVE,
            repository.readTutorSession(session.sessionId)?.disposition,
        )
        assertTrue(repository.observePendingCaptures().first().isEmpty())
        assertEquals(0, database.countMistakes())

        val replay = repository.endTutorSessionWithoutSaving(
            EndTutorSessionWithoutSaveRequest(
                sessionId = session.sessionId,
                occurredAtEpochMillis = 19_900,
            ),
        )
        assertFalse(replay.created)
        assertEquals(19_200, replay.endedAtEpochMillis)
        assertTrue(
            runCatching {
                repository.saveTutorSession(
                    SaveTutorSessionRequest(
                        requestId = "save-after-end",
                        sessionId = session.sessionId,
                        occurredAtEpochMillis = 20_000,
                    ),
                )
            }.isFailure,
        )
        assertEquals(0, database.countMistakes())
    }

    @Test
    fun pendingCaptureRecoverySurvivesRepositoryRecreationAndSavedTutorDisappears() = runBlocking {
        val source = createPng(width = 132, height = 176)
        val draft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "pending-recovery-import",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.TUTOR,
                occurredAtEpochMillis = 30_000,
            ),
        )
        val reopenedRepository = RoomCaptureWorkflowRepository(
            database,
            AndroidCanonicalAssetVault(context),
            noTextRecognizer(),
        )

        val recovered = checkNotNull(reopenedRepository.readPendingCapture(draft.draftId))
        assertEquals(draft.draftId, recovered.draftId)
        assertEquals(CaptureEntryOrigin.TUTOR, recovered.origin)
        assertEquals(30_000, recovered.draftCreatedAtEpochMillis)
        assertEquals(draft.revisionNumber, recovered.currentRevisionNumber)
        assertEquals(draft.sourceAssetId, recovered.sourceAssetId)
        assertEquals(draft.sourceAssetSha256, recovered.sourceAssetSha256)
        assertTrue(recovered.sourceImageUri.startsWith("file:"))
        assertEquals(null, recovered.latestAssessmentTask)
        assertEquals(null, recovered.latestParseTask)
        assertEquals(
            PendingCaptureStage.READY_TO_CONTINUE,
            reopenedRepository.observePendingCaptures().first().single().stage,
        )

        val session = reopenedRepository.confirmForTutoring(
            ConfirmCapturedProblemRequest(
                requestId = "pending-recovery-confirm",
                draftId = draft.draftId,
                expectedRevisionNumber = draft.revisionNumber,
                subject = "MATH",
                title = "待继续讲解的函数题",
                transcription = "已知二次函数图像，判断开口方向。",
                writingLayer = CaptureWritingLayer.PRINTED,
                transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                occurredAtEpochMillis = 31_000,
            ),
        )
        val ready = repository.observePendingCaptures().first().single()
        assertEquals(PendingCaptureStage.TUTOR_SESSION_READY, ready.stage)
        assertEquals(session.sessionId, ready.tutorSessionId)

        reopenedRepository.saveTutorSession(
            SaveTutorSessionRequest(
                requestId = "pending-recovery-save",
                sessionId = session.sessionId,
                occurredAtEpochMillis = 32_000,
            ),
        )
        assertTrue(repository.observePendingCaptures().first().isEmpty())
        assertEquals(null, repository.readPendingCapture(draft.draftId))
    }

    @Test
    fun pendingCaptureListIsolatesMissingSourceWithoutHidingHealthyDrafts() = runBlocking {
        val missingDraft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "pending-missing-source",
                localUri = privateUri(createPng(width = 92, height = 124)).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 40_000,
            ),
        )
        val healthyDraft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "pending-healthy-source",
                localUri = privateUri(createPng(width = 94, height = 126)).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 41_000,
            ),
        )
        val sourceAsset = checkNotNull(database.readProblemDraft(missingDraft.draftId)).sourceAsset
        assertTrue(File(context.filesDir, sourceAsset.relativePath).delete())

        val pending = repository.observePendingCaptures().first().associateBy { it.draftId }
        assertEquals(2, pending.size)
        assertEquals(
            PendingCaptureStage.SOURCE_UNAVAILABLE,
            pending.getValue(missingDraft.draftId).stage,
        )
        assertFalse(
            pending.getValue(healthyDraft.draftId).stage == PendingCaptureStage.SOURCE_UNAVAILABLE,
        )
        assertTrue(runCatching { repository.readPendingCapture(missingDraft.draftId) }.isFailure)
        assertEquals(healthyDraft.draftId, repository.readPendingCapture(healthyDraft.draftId)?.draftId)
    }

    @Test
    fun pendingCaptureRejectsStaleTasksAndNeverTreatsDemoParseAsReviewReady() = runBlocking {
        val occurredAt = 50_000L
        val draft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "pending-model-import",
                localUri = privateUri(createPng(width = 144, height = 192)).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = occurredAt,
            ),
        )
        var now = occurredAt
        val modelTasks = RoomModelTaskRepository(
            database = database,
            gateway = FakeModelGateway(stepDelayMillis = 0),
            clock = { ++now },
        )
        val assessmentRequest = ModelTaskRequest(
            requestId = "pending-demo-assessment",
            input = CaptureAssessmentInput(
                draftId = draft.draftId,
                sourceAssetId = draft.sourceAssetId,
                origin = CaptureAssessmentOrigin.LIBRARY,
                imageWidth = draft.width,
                imageHeight = draft.height,
            ),
            occurredAtEpochMillis = occurredAt,
        )
        modelTasks.execute(assessmentRequest).toList()
        val parseRequest = ModelTaskRequest(
            requestId = "pending-demo-parse",
            input = CaptureParseInput(
                draftId = draft.draftId,
                origin = CaptureAssessmentOrigin.LIBRARY,
                basisRevisionNumber = draft.revisionNumber,
                sourceAssets = listOf(
                    CaptureSourceAssetRef(
                        assetId = draft.sourceAssetId,
                        sha256 = draft.sourceAssetSha256,
                        width = draft.width,
                        height = draft.height,
                        pageIndex = 0,
                    ),
                ),
                assessmentRequestId = assessmentRequest.requestId,
            ),
            occurredAtEpochMillis = occurredAt,
        )
        modelTasks.execute(parseRequest).toList()

        val demoPending = repository.observePendingCaptures().first().single()
        assertEquals(PendingCaptureStage.MANUAL_REVIEW_REQUIRED, demoPending.stage)
        val demoRecovered = checkNotNull(repository.readPendingCapture(draft.draftId))
        assertTrue(demoRecovered.latestParseTask?.provider?.isDemo == true)

        val staleParseRequest = parseRequest.copy(
            requestId = "pending-stale-parse",
            input = (parseRequest.input as CaptureParseInput).copy(
                sourceAssets = listOf(
                    CaptureSourceAssetRef(
                        assetId = draft.sourceAssetId,
                        sha256 = "b".repeat(64),
                        width = draft.width,
                        height = draft.height,
                        pageIndex = 0,
                    ),
                ),
            ),
        )
        database.createModelTask(
            CreateModelTaskCommand(
                taskId = "model-task-pending-stale-parse",
                request = staleParseRequest,
                requestFingerprint = ModelTaskFingerprint.of(staleParseRequest),
                occurredAtEpochMillis = 60_000,
            ),
        )
        val afterStaleParse = checkNotNull(repository.readPendingCapture(draft.draftId))
        assertEquals(null, afterStaleParse.latestParseTask)
        assertEquals(
            PendingCaptureStage.READY_TO_CONTINUE,
            repository.observePendingCaptures().first().single().stage,
        )

        val staleAssessmentRequest = assessmentRequest.copy(
            requestId = "pending-stale-assessment",
            input = (assessmentRequest.input as CaptureAssessmentInput).copy(
                imageWidth = draft.width + 1,
            ),
        )
        database.createModelTask(
            CreateModelTaskCommand(
                taskId = "model-task-pending-stale-assessment",
                request = staleAssessmentRequest,
                requestFingerprint = ModelTaskFingerprint.of(staleAssessmentRequest),
                occurredAtEpochMillis = 70_000,
            ),
        )
        val afterStaleAssessment = checkNotNull(repository.readPendingCapture(draft.draftId))
        assertEquals(null, afterStaleAssessment.latestAssessmentTask)
        assertEquals(null, afterStaleAssessment.latestParseTask)
    }

    @Test
    fun tutorSessionReadFailsClosedWhenCanonicalSourceIsMissing() = runBlocking {
        val source = createPng(width = 88, height = 112)
        val draft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "missing-source-import",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.CAMERA,
                origin = CaptureEntryOrigin.TUTOR,
                occurredAtEpochMillis = 20_000,
            ),
        )
        val session = repository.confirmForTutoring(
            ConfirmCapturedProblemRequest(
                requestId = "missing-source-confirm",
                draftId = draft.draftId,
                expectedRevisionNumber = draft.revisionNumber,
                subject = "CHEMISTRY",
                title = "离子反应",
                transcription = "判断下列离子方程式是否正确。",
                writingLayer = CaptureWritingLayer.PRINTED,
                transcriptionReview = CaptureTranscriptionReview.MANUAL_ENTRY,
                occurredAtEpochMillis = 21_000,
            ),
        )
        val sourceAsset = checkNotNull(database.readProblemDraft(draft.draftId)).sourceAsset
        val canonicalFile = File(context.filesDir, sourceAsset.relativePath)
        assertTrue(canonicalFile.delete())

        val pending = repository.observePendingCaptures().first().single()
        assertEquals(PendingCaptureStage.SOURCE_UNAVAILABLE, pending.stage)
        assertEquals(null, pending.tutorSessionId)
        assertTrue(runCatching { repository.readTutorSession(session.sessionId) }.isFailure)
        assertTrue(runCatching { repository.readPendingCapture(session.draftId) }.isFailure)
        assertEquals(0, database.countMistakes())
    }

    @Test
    fun opaquePngRemainsLosslessInCanonicalVault() = runBlocking {
        val source = createPng(width = 32, height = 24)
        val draft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "opaque-png",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 4_000,
            ),
        )

        val stored = checkNotNull(database.readProblemDraft(draft.draftId)).sourceAsset
        assertEquals("image/png", stored.mimeType)
        assertTrue(stored.relativePath.endsWith(".png"))
    }

    @Test
    fun dimensionBudgetRejectsImageBeforeCanonicalAssetOrDraftExists() = runBlocking {
        val source = createJpeg(width = 8_193, height = 1)
        val result = runCatching {
            repository.importDraft(
                CaptureDraftImportRequest(
                    requestId = "oversized-dimension",
                    localUri = privateUri(source).toString(),
                    source = CaptureInputSource.CAMERA,
                    origin = CaptureEntryOrigin.TUTOR,
                    occurredAtEpochMillis = 3_000,
                ),
            )
        }

        assertTrue(result.isFailure)
        assertEquals(0, database.countMistakes())
        assertTrue(
            File(context.filesDir, "source-assets")
                .listFiles()
                .orEmpty()
                .none { !it.name.startsWith('.') },
        )
    }

    @Test
    fun localOcrPersistsOnlyAnUnconfirmedCandidateBeforeUserCommit() = runBlocking {
        val source = createPng(width = 96, height = 64)
        val candidateRepository = RoomCaptureWorkflowRepository(
            database,
            AndroidCanonicalAssetVault(context),
            LocalQuestionTextRecognizer { _, _, _ ->
                LocalTextRecognition(
                    blocks = listOf(
                        LocalRecognizedTextBlock(
                            text = "已知函数 f(x)=x²，求单调区间。",
                            sourceRegion = NormalizedSourceRegion(0.1, 0.2, 0.9, 0.7),
                            confidence = 0.62,
                        ),
                    ),
                    producerVersion = "fixture-ocr-v1",
                )
            },
        )

        val draft = candidateRepository.importDraft(
            CaptureDraftImportRequest(
                requestId = "ocr-candidate",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 5_000,
            ),
        )

        assertEquals(2, draft.revisionNumber)
        assertEquals(CaptureRecognitionState.CANDIDATE_AVAILABLE, draft.recognition.state)
        assertEquals("已知函数 f(x)=x²，求单调区间。", draft.recognition.candidateText)
        assertEquals(0.62, checkNotNull(draft.recognition.confidence), 0.0001)
        val candidateRevision = checkNotNull(database.readProblemDraft(draft.draftId)).currentRevision
        assertEquals(StudyDbValue.ProblemDraftAuthor.LOCAL_OCR, candidateRevision.author)
        assertEquals(
            QuestionBlockReviewStatus.CANDIDATE,
            candidateRevision.questionDocument.blockEvidence.single().reviewStatus,
        )
        assertTrue(
            CapturedQuestionDocumentValidator.validateForCommit(candidateRevision.questionDocument)
                .any { it.code == CapturedQuestionIssueCode.UNRESOLVED_BLOCK },
        )

        val pending = checkNotNull(candidateRepository.readPendingCapture(draft.draftId))
        val unconfirmedWorkspace = candidateRepository.saveDraftWorkspace(
            SaveCaptureDraftWorkspaceRequest(
                draftId = draft.draftId,
                basisRevisionNumber = draft.revisionNumber,
                expectedWorkspaceVersion = 0,
                expectedWorkspaceFingerprint = null,
                workspace = CaptureDraftWorkspace(
                    subject = "MATH",
                    workingDocument = candidateRevision.questionDocument.copy(
                        document = candidateRevision.questionDocument.document.copy(
                            title = "函数单调区间",
                        ),
                    ),
                    editorMode = CaptureDraftEditorMode.STRUCTURED_DOCUMENT,
                    userEditedFields = setOf(
                        CaptureDraftEditedField.SUBJECT,
                        CaptureDraftEditedField.TITLE,
                    ),
                    userEditedBlockIds = emptySet(),
                    baseCandidateFingerprint = pending.currentRevisionDocumentFingerprint,
                    finalConfirmationRequest = CaptureFinalConfirmationRequestIdentity(
                        requestId = "bypass-ocr-candidate",
                        occurredAtEpochMillis = 5_500,
                    ),
                ),
                occurredAtEpochMillis = 5_500,
            ),
        )
        val bypass = runCatching {
            candidateRepository.confirmAndCommit(
                ConfirmCapturedProblemRequest(
                    draftId = draft.draftId,
                    workspaceIdentity = unconfirmedWorkspace.identity,
                ),
            )
        }
        assertTrue(bypass.isFailure)

        candidateRepository.confirmAndCommit(
            ConfirmCapturedProblemRequest(
                requestId = "confirm-ocr-candidate",
                draftId = draft.draftId,
                expectedRevisionNumber = draft.revisionNumber,
                subject = "MATH",
                title = "函数单调区间",
                transcription = "已知函数 f(x)=x²，求它的单调区间。",
                writingLayer = CaptureWritingLayer.PRINTED,
                transcriptionReview =
                    CaptureTranscriptionReview.OCR_CANDIDATE_EXPLICITLY_CONFIRMED,
                occurredAtEpochMillis = 6_000,
            ),
        )

        val committedRevision = checkNotNull(database.readProblemDraft(draft.draftId)).currentRevision
        assertEquals(StudyDbValue.ProblemDraftAuthor.USER, committedRevision.author)
        assertTrue(
            CapturedQuestionDocumentValidator.validateForCommit(committedRevision.questionDocument)
                .isEmpty(),
        )
    }

    @Test
    fun bundledChineseRecognizerIsAvailableWithoutADeferredModelDownload() = runBlocking {
        val source = createPng(width = 64, height = 64)

        val result = MlKitChineseQuestionTextRecognizer(context).recognize(source, 64, 64)

        assertEquals("mlkit-chinese-v2-16.0.1", result.producerVersion)
        assertTrue(result.blocks.isEmpty())
    }

    @Test
    fun confirmedModelDocumentKeepsBlocksRegionsAndWritingBoundaries() = runBlocking {
        val source = createPng(width = 800, height = 1_200)
        val draft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "model-structured-candidate",
                localUri = privateUri(source).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 7_000,
            ),
        )
        val candidate = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-${draft.draftId}",
                title = "候选标题",
                blocks = listOf(
                    ContentBlock.Paragraph("stem", "已知函数 f(x)=x²。"),
                    ContentBlock.Formula("formula", "f'(x)=2x", "f 撇等于二 x"),
                ),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = draft.sourceAssetId,
                    sourceRegion = NormalizedSourceRegion(0.1, 0.1, 0.9, 0.45),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.MODEL_DOCUMENT_PARSE,
                    confidence = 0.96,
                    reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                    producerVersion = "fixture/parse-v1",
                ),
                QuestionBlockEvidence(
                    blockId = "formula",
                    sourceAssetId = draft.sourceAssetId,
                    sourceRegion = NormalizedSourceRegion(0.2, 0.5, 0.8, 0.75),
                    writingLayer = WritingLayer.HANDWRITTEN,
                    provenance = QuestionBlockProvenance.MODEL_DOCUMENT_PARSE,
                    confidence = 0.88,
                    reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                    producerVersion = "fixture/parse-v1",
                ),
            ),
        )

        repository.confirmAndCommit(
            ConfirmCapturedProblemRequest(
                requestId = "confirm-model-structured-candidate",
                draftId = draft.draftId,
                expectedRevisionNumber = draft.revisionNumber,
                subject = "MATH",
                title = "函数求导",
                transcription = "已知函数 f(x)=x²。\n\n${'$'}${'$'}f'(x)=2x${'$'}${'$'}",
                writingLayer = CaptureWritingLayer.MIXED,
                transcriptionReview =
                    CaptureTranscriptionReview.MODEL_DOCUMENT_EXPLICITLY_CONFIRMED,
                occurredAtEpochMillis = 8_000,
                confirmedDocument = candidate,
            ),
        )

        val committed = checkNotNull(database.readProblemDraft(draft.draftId)).currentRevision
        assertEquals(2, committed.questionDocument.document.blocks.size)
        assertEquals("函数求导", committed.questionDocument.document.title)
        assertEquals(
            listOf(WritingLayer.PRINTED, WritingLayer.HANDWRITTEN),
            committed.questionDocument.blockEvidence.map { it.writingLayer },
        )
        assertTrue(
            committed.questionDocument.blockEvidence.all {
                it.provenance == QuestionBlockProvenance.MODEL_DOCUMENT_PARSE &&
                    it.reviewStatus == QuestionBlockReviewStatus.USER_CONFIRMED &&
                    it.sourceRegion != null
            },
        )
        assertTrue(
            CapturedQuestionDocumentValidator.validateForCommit(committed.questionDocument)
                .isEmpty(),
        )
    }

    private fun createJpeg(
        width: Int,
        height: Int,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL,
    ): File {
        val directory = File(context.cacheDir, "captured_images").also { it.mkdirs() }
        val file = File.createTempFile("question_", ".jpg", directory)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
            }
        } finally {
            bitmap.recycle()
        }
        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    @Suppress("FunctionName")
    private suspend fun ConfirmCapturedProblemRequest(
        requestId: String,
        draftId: String,
        expectedRevisionNumber: Int,
        subject: String,
        title: String,
        transcription: String,
        writingLayer: CaptureWritingLayer,
        transcriptionReview: CaptureTranscriptionReview,
        occurredAtEpochMillis: Long,
        confirmedDocument: CapturedQuestionDocument? = null,
    ): ConfirmCapturedProblemRequest {
        val pending = checkNotNull(repository.readPendingCapture(draftId))
        require(pending.currentRevisionNumber == expectedRevisionNumber)
        require(
            confirmedDocument == null ||
                transcriptionReview == CaptureTranscriptionReview.MODEL_DOCUMENT_EXPLICITLY_CONFIRMED,
        )
        val modelWritingLayer = when (writingLayer) {
            CaptureWritingLayer.PRINTED -> WritingLayer.PRINTED
            CaptureWritingLayer.HANDWRITTEN -> WritingLayer.HANDWRITTEN
            CaptureWritingLayer.MIXED -> WritingLayer.MIXED
            CaptureWritingLayer.UNKNOWN -> WritingLayer.UNKNOWN
        }
        val document = confirmedDocument?.copy(
            document = confirmedDocument.document.copy(title = title.trim()),
            blockEvidence = confirmedDocument.blockEvidence.map { evidence ->
                evidence.copy(
                    writingLayer = evidence.writingLayer.takeUnless { it == WritingLayer.UNKNOWN }
                        ?: modelWritingLayer,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                )
            },
        ) ?: CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-$draftId",
                title = title.trim(),
                blocks = listOf(ContentBlock.Paragraph("stem", transcription.trim())),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = pending.sourceAssetId,
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = modelWritingLayer,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        )
        val previous = pending.workspace
        val saved = repository.saveDraftWorkspace(
            SaveCaptureDraftWorkspaceRequest(
                draftId = draftId,
                basisRevisionNumber = expectedRevisionNumber,
                expectedWorkspaceVersion = previous?.identity?.workspaceVersion ?: 0,
                expectedWorkspaceFingerprint = previous?.identity?.workspaceFingerprint,
                workspace = CaptureDraftWorkspace(
                    subject = subject,
                    workingDocument = document,
                    editorMode = CaptureDraftEditorMode.STRUCTURED_DOCUMENT,
                    userEditedFields = setOf(
                        CaptureDraftEditedField.SUBJECT,
                        CaptureDraftEditedField.TITLE,
                        CaptureDraftEditedField.TRANSCRIPTION,
                    ),
                    userEditedBlockIds = document.document.blocks.map(ContentBlock::id).toSet(),
                    baseCandidateFingerprint = pending.currentRevisionDocumentFingerprint,
                    finalConfirmationRequest = CaptureFinalConfirmationRequestIdentity(
                        requestId = requestId,
                        occurredAtEpochMillis = occurredAtEpochMillis,
                    ),
                ),
                occurredAtEpochMillis = occurredAtEpochMillis,
            ),
        )
        return ConfirmCapturedProblemRequest(
            draftId = draftId,
            workspaceIdentity = saved.identity,
        )
    }

    private fun createPng(width: Int, height: Int): File {
        val directory = File(context.cacheDir, "captured_images").also { it.mkdirs() }
        val file = File.createTempFile("question_", ".img", directory)
        writePng(file, width, height)
        return file
    }

    private fun createInvalidImage(): File {
        val directory = File(context.cacheDir, "captured_images").also { it.mkdirs() }
        val file = File.createTempFile("question_", ".img", directory)
        FileOutputStream(file).use { stream -> stream.write("not-an-image".toByteArray()) }
        return file
    }

    private fun stagedSourceFile(sourceUri: String): File {
        val uri = Uri.parse(sourceUri)
        assertEquals(batchImportProviderAuthority(context), uri.authority)
        assertEquals("batch_import_staging", uri.pathSegments.first())
        val session = File(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY)
            .resolve(uri.pathSegments[1])
            .canonicalFile
        val root = File(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY).canonicalFile
        check(session.parentFile == root)
        return session.resolve(uri.pathSegments[2]).canonicalFile.also { source ->
            check(source.parentFile == session)
        }
    }

    private fun writePng(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        try {
            FileOutputStream(file).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun privateUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.capture.fileprovider",
        file,
    )

    private fun noTextRecognizer() = LocalQuestionTextRecognizer { _, _, _ ->
        LocalTextRecognition(emptyList(), "fixture-no-text-v1")
    }


    private fun clearOwnedFlatDirectory(
        parent: File,
        childName: String,
    ) {
        val canonicalParent = parent.canonicalFile
        val directory = File(canonicalParent, childName).canonicalFile
        check(directory.parentFile == canonicalParent)
        if (!directory.exists()) return
        check(directory.isDirectory)
        directory.listFiles().orEmpty().forEach { child ->
            val canonicalChild = child.canonicalFile
            check(canonicalChild.parentFile == directory)
            check(canonicalChild.isFile)
            check(canonicalChild.delete())
        }
        check(directory.delete())
    }

    private fun clearOwnedDirectoryTree(parent: File, childName: String) {
        val canonicalParent = parent.canonicalFile
        val directory = File(canonicalParent, childName).canonicalFile
        check(directory.parentFile == canonicalParent)
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { child ->
            check(child.canonicalPath.startsWith(directory.canonicalPath))
            check(child.delete())
        }
    }
}
