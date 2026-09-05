package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.PendingCaptureDraftRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftRevisionRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.PendingCaptureStage
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentAction
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssue
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssueCode
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentSeverity
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct unit coverage for the stateless capture mappings extracted from
 * RoomCaptureWorkflowRepository: task-validation branches, fingerprints, and
 * writing-layer classification. A broken evidence match here silently flips
 * the capture flow into the wrong stage, so the branches are pinned.
 */
class RoomCaptureWorkflowMappingsTest {

    @Test
    fun requestFingerprintIsDeterministicAndFieldOrderSensitive() {
        val first = requestFingerprint("capture-import-v2", "request-1", "a", "b")
        val again = requestFingerprint("capture-import-v2", "request-1", "a", "b")
        assertEquals(first, again)

        val reordered = requestFingerprint("capture-import-v2", "a", "request-1", "b")
        assertFalse(first == reordered)

        // The unit separator keeps concatenated fields from colliding.
        val merged = requestFingerprint("ab", "c")
        val split = requestFingerprint("a", "bc")
        assertFalse(merged == split)
    }

    @Test
    fun stableIdUsesSixteenHexCharacterSuffix() {
        val id = stableId("split-draft", "request-1")
        assertTrue(id.startsWith("split-draft-"))
        val suffix = id.removePrefix("split-draft-")
        assertEquals(32, suffix.length)
        assertTrue(suffix.all { it.isDigit() || it in 'a'..'f' })
        assertEquals(stableId("split-draft", "request-1"), id)
    }

    @Test
    fun boundingRegionTakesTheExtremesOfEveryRegion() {
        val bounding = listOf(
            NormalizedSourceRegion(left = 0.2, top = 0.3, right = 0.4, bottom = 0.5),
            NormalizedSourceRegion(left = 0.1, top = 0.4, right = 0.6, bottom = 0.7),
        ).boundingRegion()
        assertEquals(NormalizedSourceRegion(left = 0.1, top = 0.3, right = 0.6, bottom = 0.7), bounding)
    }

    @Test
    fun captureWritingLayerClassifiesEveryEvidenceCombination() {
        assertEquals(
            CaptureWritingLayer.PRINTED,
            documentWithLayers(WritingLayer.PRINTED).captureWritingLayer(),
        )
        assertEquals(
            CaptureWritingLayer.HANDWRITTEN,
            documentWithLayers(WritingLayer.HANDWRITTEN).captureWritingLayer(),
        )
        assertEquals(
            CaptureWritingLayer.MIXED,
            documentWithLayers(WritingLayer.PRINTED, WritingLayer.HANDWRITTEN).captureWritingLayer(),
        )
        assertEquals(
            CaptureWritingLayer.UNKNOWN,
            documentWithLayers(WritingLayer.DIAGRAM).captureWritingLayer(),
        )
        assertEquals(
            CaptureWritingLayer.UNKNOWN,
            documentWithLayers().captureWritingLayer(),
        )
    }

    @Test
    fun corruptedWorkspaceSnapshotIsRejectedAsIntegrityFailure() {
        val record = ProblemDraftEditWorkspaceRecord(
            draftId = "d1",
            basisRevisionNumber = 1,
            workspaceVersion = 1,
            snapshotSchemaVersion = 1,
            workspaceSnapshot = "{not json",
            workspaceFingerprint = "fp-1",
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 1_000L,
        )
        try {
            record.toDomainWorkspaceSnapshot()
            throw AssertionError("Expected IllegalStateException for a corrupted workspace")
        } catch (expected: IllegalStateException) {
            assertEquals("Persisted capture workspace is invalid", expected.message)
        }
    }

    @Test
    fun validTutorSessionIdRequiresMatchingTutorRevision() {
        val draft = problemDraft(origin = StudyDbValue.CaptureOrigin.TUTOR, author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT)
        val matching = pendingDraft(
            draft,
            tutorSessionId = "session-1",
            tutorSessionDraftRevisionNumber = 1,
        )
        assertEquals("session-1", matching.validTutorSessionId())

        val stale = pendingDraft(
            draft,
            tutorSessionId = "session-1",
            tutorSessionDraftRevisionNumber = 2,
        )
        assertNull(stale.validTutorSessionId())

        val nonTutor = pendingDraft(
            problemDraft(origin = StudyDbValue.CaptureOrigin.LIBRARY, author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT),
            tutorSessionId = "session-1",
            tutorSessionDraftRevisionNumber = 1,
        )
        assertNull(nonTutor.validTutorSessionId())
    }

    @Test
    fun matchesAssessmentAcceptsExactBindingAndRejectsDrift() {
        val draft = problemDraft(
            origin = StudyDbValue.CaptureOrigin.TUTOR,
            author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
        )
        val task = assessmentTask(draft = draft, assetId = "a1", occurredAt = 1_000L)
        assertTrue(task.matchesAssessment(draft, draft.sourceAssets.first()))

        // A user-authored revision must never be claimed by a model task.
        val userDraft = problemDraft(
            origin = StudyDbValue.CaptureOrigin.TUTOR,
            author = StudyDbValue.ProblemDraftAuthor.USER,
        )
        val userTask = assessmentTask(draft = userDraft, assetId = "a1", occurredAt = 1_000L)
        assertFalse(userTask.matchesAssessment(userDraft, userDraft.sourceAssets.first()))

        // A captured-image dimension change invalidates the binding.
        val resizedDraft = problemDraft(
            origin = StudyDbValue.CaptureOrigin.TUTOR,
            author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
            sourceWidth = 300,
        )
        assertFalse(task.matchesAssessment(resizedDraft, resizedDraft.sourceAssets.first()))
    }

    @Test
    fun matchesParseRequiresEveryAssessmentToPassAndPagesToAlign() {
        val draft = problemDraft(
            origin = StudyDbValue.CaptureOrigin.TUTOR,
            author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
        )
        val assessment = assessmentTask(draft = draft, assetId = "a1", occurredAt = 1_000L)
        val parse = parseTask(draft, status = ModelTaskStatus.RUNNING, assessmentRequestIds = listOf(assessment.request.requestId))
        assertTrue(parse.matchesParse(draft, listOf(assessment)))

        // A missing page assessment blocks the parse.
        assertFalse(parse.matchesParse(draft, listOf(null)))

        // NEED_MORE_IMAGE on the last page must not satisfy the parse gate.
        val needMore = assessmentTask(
            draft = draft,
            assetId = "a1",
            occurredAt = 1_000L,
            decision = CaptureAssessmentDecision.NEED_MORE_IMAGE,
        )
        assertFalse(parse.matchesParse(draft, listOf(needMore)))

        // A page count drift between parse request and draft blocks it.
        val mismatchedParse = parseTask(
            draft,
            status = ModelTaskStatus.RUNNING,
            sourceAssetCount = 2,
            assessmentRequestIds = listOf(assessment.request.requestId, "a-req-2"),
        )
        assertFalse(mismatchedParse.matchesParse(draft, listOf(assessment)))
    }

    @Test
    fun stageForCoversEveryDecisionBranch() {
        val draft = problemDraft(
            origin = StudyDbValue.CaptureOrigin.TUTOR,
            author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
        )
        val workingAssessment = assessmentTask(
            draft = draft,
            assetId = "a1",
            occurredAt = 1_000L,
            status = ModelTaskStatus.RUNNING,
        )
        assertEquals(
            PendingCaptureStage.MODEL_WORKING,
            stageFor(draft, ValidatedCaptureTasks(assessments = listOf(workingAssessment), parse = null)),
        )

        val recaptured = assessmentTask(
            draft = draft,
            assetId = "a1",
            occurredAt = 1_000L,
            decision = CaptureAssessmentDecision.RECAPTURE,
        )
        assertEquals(
            PendingCaptureStage.RECAPTURE_REQUIRED,
            stageFor(draft, ValidatedCaptureTasks(assessments = listOf(recaptured), parse = null)),
        )

        val failed = assessmentTask(
            draft = draft,
            assetId = "a1",
            occurredAt = 1_000L,
            status = ModelTaskStatus.RETRYABLE_FAILURE,
        )
        assertEquals(
            PendingCaptureStage.RETRY_OR_MANUAL,
            stageFor(draft, ValidatedCaptureTasks(assessments = listOf(failed), parse = null)),
        )

        // A locally-recognized draft that produced text still waits for manual review.
        val ocrDraft = problemDraft(
            origin = StudyDbValue.CaptureOrigin.TUTOR,
            author = StudyDbValue.ProblemDraftAuthor.LOCAL_OCR,
            revisionMarkdown = "1+1=2",
        )
        assertEquals(
            PendingCaptureStage.MANUAL_REVIEW_REQUIRED,
            stageFor(ocrDraft, ValidatedCaptureTasks(assessments = emptyList(), parse = null)),
        )

        // A plain capture import with no tasks continues to review.
        assertEquals(
            PendingCaptureStage.READY_TO_CONTINUE,
            stageFor(draft, ValidatedCaptureTasks(assessments = emptyList(), parse = null)),
        )
    }

    // ---- fixtures -------------------------------------------------------

    // 64 lowercase hex chars: CaptureSourceAssetRef validates the hash format.
    private fun sha(id: String) = "a".repeat(24) + id.padEnd(8, '0').take(8) + "b".repeat(32)

    private fun sourceAsset(id: String, width: Int = 100) = CanonicalSourceAssetRecord(
        sourceAssetId = id,
        contentSha256 = sha(id),
        relativePath = "assets/$id",
        mimeType = "image/jpeg",
        byteSize = 10L,
        width = width,
        height = 200,
        sourceType = "CAMERA",
        createdAtEpochMillis = 1_000L,
    )

    private fun blankDocument(markdown: String = "") = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "document-1",
            blocks = listOf(ContentBlock.Paragraph(id = "stem", markdown = markdown)),
        ),
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = "a1",
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = WritingLayer.UNKNOWN,
                provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
                reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                producerVersion = "capture-import-v1",
            ),
        ),
    )

    private fun documentWithLayers(vararg layers: WritingLayer) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "document-1",
            blocks = layers.mapIndexed { index, _ -> ContentBlock.Paragraph(id = "b$index", markdown = "") },
        ),
        blockEvidence = layers.mapIndexed { index, layer ->
            QuestionBlockEvidence(
                blockId = "b$index",
                sourceAssetId = "a1",
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = layer,
                provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
                reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                producerVersion = "capture-import-v1",
            )
        },
    )

    private fun problemDraft(
        origin: String,
        author: String,
        sourceWidth: Int = 100,
        revisionMarkdown: String = "",
    ) = ProblemDraftRecord(
        draftId = "d1",
        sourceAsset = sourceAsset("a1", width = sourceWidth),
        origin = origin,
        status = StudyDbValue.ProblemDraftStatus.EDITING,
        currentRevision = ProblemDraftRevisionRecord(
            draftId = "d1",
            revisionNumber = 1,
            basisRevisionNumber = null,
            subject = null,
            title = "题目",
            questionDocument = blankDocument(revisionMarkdown),
            documentFingerprint = CapturedQuestionDocumentFingerprint.of(blankDocument()),
            author = author,
            createdAtEpochMillis = 1_000L,
        ),
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
    )

    private fun pendingDraft(
        draft: ProblemDraftRecord,
        tutorSessionId: String?,
        tutorSessionDraftRevisionNumber: Int?,
    ) = PendingCaptureDraftRecord(
        draft = draft,
        editWorkspace = null,
        latestAssessmentTask = null,
        latestParseTask = null,
        tutorSessionId = tutorSessionId,
        tutorSessionDraftRevisionNumber = tutorSessionDraftRevisionNumber,
    )

    private fun assessmentTask(
        draft: ProblemDraftRecord,
        assetId: String,
        occurredAt: Long,
        status: ModelTaskStatus = ModelTaskStatus.SUCCEEDED,
        decision: CaptureAssessmentDecision = CaptureAssessmentDecision.PASS,
    ): ModelTaskSnapshot {
        val request = ModelTaskRequest(
            requestId = "assess-$assetId-${decision.name}-$occurredAt",
            input = CaptureAssessmentInput(
                draftId = draft.draftId,
                sourceAssetId = assetId,
                origin = CaptureAssessmentOrigin.TUTOR,
                imageWidth = draft.sourceAsset.width,
                imageHeight = draft.sourceAsset.height,
            ),
            occurredAtEpochMillis = occurredAt,
        )
        return ModelTaskSnapshot(
            taskId = "task-${request.requestId}",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = status,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "",
            attemptCount = 1,
            failure = if (status == ModelTaskStatus.RETRYABLE_FAILURE || status == ModelTaskStatus.PERMANENT_FAILURE) {
                ModelTaskFailure(
                    code = ModelFailureCode.TIMEOUT,
                    message = "timeout",
                    retryable = status == ModelTaskStatus.RETRYABLE_FAILURE,
                )
            } else {
                null
            },
            output = CaptureAssessmentOutput(                assessment = CaptureAssessment(
                    decision = decision,
                    issues = if (decision == CaptureAssessmentDecision.PASS) {
                        emptyList()
                    } else {
                        listOf(
                            CaptureAssessmentIssue(
                                code = CaptureAssessmentIssueCode.KEY_TEXT_UNREADABLE,
                                severity = CaptureAssessmentSeverity.BLOCKING,
                                region = null,
                                message = "unreadable",
                            ),
                        )
                    },
                    suggestedActions = when (decision) {
                        CaptureAssessmentDecision.NEED_MORE_IMAGE -> listOf(CaptureAssessmentAction.ADD_IMAGE)
                        CaptureAssessmentDecision.RECAPTURE -> listOf(CaptureAssessmentAction.RECAPTURE)
                        else -> emptyList()
                    },
                    modelVersion = "model-1",
                ),
            ),
            createdAtEpochMillis = occurredAt,
            updatedAtEpochMillis = occurredAt,
        )
    }

    private fun parseTask(
        draft: ProblemDraftRecord,
        status: ModelTaskStatus,
        sourceAssetCount: Int = draft.sourceAssets.size,
        assessmentRequestIds: List<String> = listOf("a-req"),
    ): ModelTaskSnapshot {
        val request = ModelTaskRequest(
            requestId = "parse-1",
            input = CaptureParseInput(
                draftId = draft.draftId,
                origin = CaptureAssessmentOrigin.TUTOR,
                basisRevisionNumber = draft.currentRevision.revisionNumber,
                sourceAssets = (0 until sourceAssetCount).map { index ->
                    CaptureSourceAssetRef(
                        assetId = "a${index + 1}",
                        sha256 = sha("a${index + 1}"),
                        width = 100,
                        height = 200,
                        pageIndex = index,
                    )
                },
                assessmentRequestId = assessmentRequestIds.firstOrNull() ?: "a-req",
                assessmentRequestIds = assessmentRequestIds,
            ),
            occurredAtEpochMillis = 1_000L,
        )
        return ModelTaskSnapshot(
            taskId = "task-parse-1",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = status,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "",
            attemptCount = 1,
            output = if (status == ModelTaskStatus.SUCCEEDED) {
                CaptureParseOutput(capturedDocument = blankDocument(), modelVersion = "model-1")
            } else {
                null
            },
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 1_000L,
        )
    }

    private fun stageFor(
        draft: ProblemDraftRecord,
        tasks: ValidatedCaptureTasks,
    ): PendingCaptureStage = pendingDraft(draft, tutorSessionId = null, tutorSessionDraftRevisionNumber = null)
        .stageFor(tasks)
}
