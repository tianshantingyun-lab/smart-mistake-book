package com.tingyun.smartmistakebook.core.data.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.data.model.FakeModelGateway
import com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository
import com.tingyun.smartmistakebook.core.database.CreateModelTaskCommand
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureTranscriptionReview
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.PendingCaptureStage
import com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspace
import com.tingyun.smartmistakebook.core.model.CaptureFinalConfirmationRequestIdentity
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.CapturedQuestionIssueCode
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureWorkflowRecoveryInstrumentedTest : CaptureWorkflowTestBase() {
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
        // The split-review ledger must carry the job with per-question draft
        // links, or the review page has nothing to confirm and the drafts
        // strand (P0-1 in the 2026-09-13 QA audit).
        val splitJobId = requireNotNull(first.splitJobId) {
            "A capture split must register a review job"
        }
        val reviewJob = database.readSplitImportJob(splitJobId)
        checkNotNull(reviewJob)
        assertEquals(
            StudyDbValue.SplitImportStatus.READY,
            reviewJob.status,
        )
        assertEquals(
            first.splitDrafts.map { it.draftId },
            reviewJob.questions.map { it.splitDraftId },
        )
        val assetFilesAfterFirstSplit = File(context.filesDir, "source-assets")
            .listFiles()
            .orEmpty()
            .map { it.name }
            .toSet()

        val replay = repository.splitDraft(request)

        assertFalse(replay.created)
        assertEquals(first.splitDrafts, replay.splitDrafts)
        assertEquals(splitJobId, replay.splitJobId)
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
}
