package com.tingyun.smartmistakebook.core.data.capture

import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest
import com.tingyun.smartmistakebook.core.domain.BatchImportPageStatus
import com.tingyun.smartmistakebook.core.domain.BatchImportStatus
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureTranscriptionReview
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest
import com.tingyun.smartmistakebook.core.domain.ConsumeCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.CreateBatchImportRequest
import com.tingyun.smartmistakebook.core.domain.PendingCaptureStage
import com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspace
import com.tingyun.smartmistakebook.core.model.CaptureFinalConfirmationRequestIdentity
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureWorkflowInstrumentedTest : CaptureWorkflowTestBase() {
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
            },
            modelTasks = withFigureClassifyModelTasks(),
            captureEgressAllowed = { true },
        )
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
            modelTasks = withFigureClassifyModelTasks(),
            captureEgressAllowed = { true },
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
            modelTasks = withFigureClassifyModelTasks(),
            captureEgressAllowed = { true },
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
}
