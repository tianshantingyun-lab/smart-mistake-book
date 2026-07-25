package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspace
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceFingerprint
import com.tingyun.smartmistakebook.core.model.CaptureFinalConfirmationRequestIdentity
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureWorkspacePolicyTest {
    @Test
    fun `restore prefers the complete persisted workspace`() {
        val base = structuredDocument("候选标题")
        val edited = base.copy(document = base.document.copy(title = "我改过的标题"))
        val confirmation = CaptureFinalConfirmationRequestIdentity("confirm-once", 9_000)
        val workspace = CaptureDraftWorkspace(
            subject = "PHYSICS",
            workingDocument = edited,
            editorMode = CaptureDraftEditorMode.STRUCTURED_DOCUMENT,
            userEditedFields = setOf(CaptureDraftEditedField.SUBJECT, CaptureDraftEditedField.TITLE),
            userEditedBlockIds = setOf("stem"),
            baseCandidateFingerprint = SHA,
            finalConfirmationRequest = confirmation,
        )
        val fingerprint = CaptureDraftWorkspaceFingerprint.of(workspace)
        val draft = resumable(base).copy(
            workspace = CaptureDraftWorkspaceSnapshot(
                identity = CaptureDraftWorkspaceIdentity(
                    draftId = "draft-1",
                    basisRevisionNumber = 1,
                    workspaceVersion = 4,
                    workspaceFingerprint = fingerprint,
                    finalConfirmationRequest = confirmation,
                ),
                workspace = workspace,
                updatedAtEpochMillis = 9_100,
            ),
        )

        val restored = restoreCaptureWorkspace(draft)

        assertEquals("PHYSICS", restored.state.subject)
        assertEquals(edited, restored.state.workingDocument)
        assertEquals(workspace.userEditedFields, restored.state.userEditedFields)
        assertEquals(workspace.userEditedBlockIds, restored.state.userEditedBlockIds)
        assertEquals(confirmation, restored.state.finalConfirmationRequest)
        assertEquals(4L, restored.identity?.workspaceVersion)
        assertEquals(9_100, restored.updatedAtEpochMillis)
    }

    @Test
    fun `late model candidate never overwrites a dirty workspace`() {
        val original = restoreCaptureWorkspace(resumable(structuredDocument("原题"))).state
        val model = structuredDocument("迟到模型")

        assertEquals(model, original.adoptModelCandidateIfPristine(model).workingDocument)

        val dirty = original.editTitle("学生标题")
        val afterLateResult = dirty.adoptModelCandidateIfPristine(model)
        assertEquals("学生标题", afterLateResult.title)
        assertNotEquals(model, afterLateResult.workingDocument)
    }

    @Test
    fun `usable structured candidate derives a title without student typing`() {
        val original = restoreCaptureWorkspace(resumable(structuredDocument("原题"))).state
        val untitled = structuredDocument("").copy(
            document = structuredDocument("").document.copy(title = null),
        )

        val adopted = original.adoptModelCandidateIfPristine(untitled)

        assertEquals("求函数的单调区间", adopted.title)
        assertEquals("求函数的单调区间", adopted.transcription.lineSequence().first())
    }

    @Test
    fun `blank candidate cannot be turned into a manual transcription`() {
        val base = structuredDocument("新拍题目")
        val paragraph = (base.document.blocks.first() as ContentBlock.Paragraph).copy(markdown = "")
        val state = restoreCaptureWorkspace(
            resumable(
                base.copy(
                    document = base.document.copy(blocks = listOf(paragraph)),
                    blockEvidence = base.blockEvidence.take(1),
                ),
            ),
        ).state

        val supplemented = state.editSingleParagraph("求函数的单调区间")

        assertEquals(state, supplemented)
        assertFalse(supplemented.canEditAsOneTextField())
        assertFalse(captureCandidateIsUsable(supplemented.workingDocument))
    }

    @Test
    fun `existing meaningful paragraph remains a structured correction`() {
        val base = structuredDocument("新拍题目")
        val state = restoreCaptureWorkspace(
            resumable(
                base.copy(
                    document = base.document.copy(blocks = base.document.blocks.take(1)),
                    blockEvidence = base.blockEvidence.take(1),
                ),
            ),
        ).state

        val corrected = state.editSingleParagraph("修正后的题干")

        assertTrue(state.canEditAsOneTextField())
        assertEquals(CaptureDraftEditorMode.STRUCTURED_DOCUMENT, corrected.editorMode)
        assertEquals("修正后的题干", corrected.transcription)
        assertTrue(CaptureDraftEditedField.TRANSCRIPTION in corrected.userEditedFields)
    }

    @Test
    fun `blank candidate stays unusable`() {
        val base = structuredDocument("新拍题目")
        val paragraph = (base.document.blocks.first() as ContentBlock.Paragraph).copy(markdown = "")
        val blank = base.copy(
            document = base.document.copy(blocks = listOf(paragraph)),
            blockEvidence = base.blockEvidence.take(1),
        )

        assertFalse(captureCandidateIsUsable(blank))
    }

    @Test
    fun `final action fills optional metadata while keeping commit validation intact`() {
        val candidate = structuredDocument("待校对题目").copy(
            blockEvidence = structuredDocument("待校对题目").blockEvidence.map {
                it.copy(
                    writingLayer = WritingLayer.UNKNOWN,
                    reviewStatus = QuestionBlockReviewStatus.CANDIDATE,
                )
            },
        )
        val state = restoreCaptureWorkspace(resumable(candidate).copy(subject = null)).state

        val ready = state.prepareForFinalCommit()

        assertEquals("GENERAL", ready.subject)
        assertEquals("求函数的单调区间", ready.title)
        assertTrue(
            ready.workingDocument.blockEvidence.all {
                it.writingLayer == WritingLayer.UNKNOWN
            },
        )
        assertTrue(
            ready.workingDocument.blockEvidence.all {
                it.reviewStatus == QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED
            },
        )
        assertTrue(
            ready.workingDocument.blockEvidence.none {
                it.reviewStatus == QuestionBlockReviewStatus.USER_CONFIRMED
            },
        )
        assertTrue(CapturedQuestionDocumentValidator.validateForCommit(ready.workingDocument).isEmpty())
    }

    @Test
    fun `block edit preserves formulas choices figures and evidence`() {
        val initial = restoreCaptureWorkspace(resumable(structuredDocument("原题"))).state
        val originalBlocks = initial.workingDocument.document.blocks
        val paragraph = originalBlocks.first() as ContentBlock.Paragraph

        val edited = initial.editBlock(paragraph.copy(markdown = "修正后的题干"))

        assertEquals("修正后的题干", (edited.workingDocument.document.blocks[0] as ContentBlock.Paragraph).markdown)
        assertEquals(originalBlocks.drop(1), edited.workingDocument.document.blocks.drop(1))
        assertEquals(
            initial.workingDocument.blockEvidence.drop(1),
            edited.workingDocument.blockEvidence.drop(1),
        )
        assertTrue("stem" in edited.userEditedBlockIds)
        assertTrue(CaptureDraftEditedField.STRUCTURE in edited.userEditedFields)
        assertFalse(edited.canEditAsOneTextField())
    }

    @Test
    fun `confirmation identity is created once and reused`() {
        val state = restoreCaptureWorkspace(resumable(structuredDocument("原题"))).state
        var idCalls = 0
        var timeCalls = 0

        val first = state.ensureFinalConfirmation(
            requestIdFactory = { idCalls += 1; "first" },
            occurredAtEpochMillis = { timeCalls += 1; 10 },
        )
        val retried = first.ensureFinalConfirmation(
            requestIdFactory = { idCalls += 1; "second" },
            occurredAtEpochMillis = { timeCalls += 1; 20 },
        )

        assertEquals(CaptureFinalConfirmationRequestIdentity("first", 10), retried.finalConfirmationRequest)
        assertEquals(1, idCalls)
        assertEquals(1, timeCalls)
    }

    @Test
    fun `commit retry replays the persisted confirmation without new user evidence`() {
        val state = restoreCaptureWorkspace(resumable(structuredDocument("原题"))).state
        var idCalls = 0
        var timeCalls = 0
        val first = prepareCaptureCommitAttempt(
            workspace = state,
            workspaceUpdatedAtEpochMillis = 100,
            requestIdFactory = { idCalls += 1; "confirm-once" },
            nowEpochMillis = { timeCalls += 1; 200 },
        )

        val retried = prepareCaptureCommitAttempt(
            workspace = first,
            workspaceUpdatedAtEpochMillis = 900,
            requestIdFactory = { idCalls += 1; "must-not-be-used" },
            nowEpochMillis = { timeCalls += 1; 1_000 },
        )

        assertEquals(first.finalConfirmationRequest, retried.finalConfirmationRequest)
        assertEquals(first.workingDocument.blockEvidence, retried.workingDocument.blockEvidence)
        assertEquals(1, idCalls)
        assertEquals(1, timeCalls)
    }

    @Test
    fun `a stale final identity can be explicitly renewed before migration save`() {
        val state = restoreCaptureWorkspace(resumable(structuredDocument("原题"))).state
            .withFinalConfirmation(CaptureFinalConfirmationRequestIdentity("old", 10))

        val renewed = state.replaceFinalConfirmation(
            CaptureFinalConfirmationRequestIdentity("new", 20),
        )

        assertEquals(CaptureFinalConfirmationRequestIdentity("new", 20), renewed.finalConfirmationRequest)
    }

    @Test
    fun `writer advances CAS identity from each returned snapshot`() = runBlocking {
        val requests = mutableListOf<com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest>()
        val writer = CaptureWorkspaceWriter { request ->
            requests += request
            val fingerprint = CaptureDraftWorkspaceFingerprint.of(request.workspace)
            CaptureDraftWorkspaceSnapshot(
                identity = CaptureDraftWorkspaceIdentity(
                    draftId = request.draftId,
                    basisRevisionNumber = request.basisRevisionNumber,
                    workspaceVersion = request.expectedWorkspaceVersion + 1,
                    workspaceFingerprint = fingerprint,
                    finalConfirmationRequest = request.workspace.finalConfirmationRequest,
                ),
                workspace = request.workspace,
                updatedAtEpochMillis = request.occurredAtEpochMillis,
            )
        }
        var identity: CaptureDraftWorkspaceIdentity? = null
        val state = restoreCaptureWorkspace(resumable(structuredDocument("原题"))).state

        val first = writer.save(state, { identity }, 100) as CaptureWorkspaceWriteResult.Saved
        identity = first.snapshot.identity
        val finalRequest = CaptureFinalConfirmationRequestIdentity("confirm-once", 150)
        val changed = state.editSubject("MATH").withFinalConfirmation(finalRequest)
        val second = writer.save(changed, { identity }, 200) as CaptureWorkspaceWriteResult.Saved
        identity = second.snapshot.identity

        assertEquals(0, requests[0].expectedWorkspaceVersion)
        assertNull(requests[0].expectedWorkspaceFingerprint)
        assertEquals(1, requests[1].expectedWorkspaceVersion)
        assertEquals(first.snapshot.identity.workspaceFingerprint, requests[1].expectedWorkspaceFingerprint)
        assertEquals(2, identity.workspaceVersion)
        assertEquals(finalRequest, identity.finalConfirmationRequest)
    }

    @Test
    fun `writing layer edit stays inside evidence without flattening the document`() {
        val state = restoreCaptureWorkspace(resumable(structuredDocument("原题"))).state
        val edited = state.editWritingLayer(CaptureWritingLayer.MIXED)

        assertEquals(state.workingDocument.document, edited.workingDocument.document)
        assertTrue(edited.workingDocument.blockEvidence.all { it.writingLayer == WritingLayer.MIXED })
    }

    @Test
    fun `final acceptance only calls blocks actually edited by the student confirmed`() {
        val localOcr = structuredDocument("原题").let { document ->
            document.copy(
                blockEvidence = document.blockEvidence.map { evidence ->
                    evidence.copy(
                        provenance = QuestionBlockProvenance.LOCAL_OCR,
                        confidence = 0.82,
                        producerVersion = "local-ocr-v1",
                    )
                },
            )
        }
        val state = restoreCaptureWorkspace(resumable(localOcr)).state
        val paragraph = state.workingDocument.document.blocks.first() as ContentBlock.Paragraph
        val edited = state.editBlock(paragraph.copy(markdown = "修正后的题干"))

        val accepted = edited.prepareForFinalCommit()

        assertEquals(
            QuestionBlockReviewStatus.USER_CONFIRMED,
            accepted.workingDocument.blockEvidence.first { it.blockId == "stem" }.reviewStatus,
        )
        assertEquals(
            QuestionBlockProvenance.USER_CORRECTION,
            accepted.workingDocument.blockEvidence.first { it.blockId == "stem" }.provenance,
        )
        assertTrue(
            accepted.workingDocument.blockEvidence
                .filterNot { it.blockId == "stem" }
                .all {
                    it.reviewStatus == QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED &&
                        it.provenance == QuestionBlockProvenance.LOCAL_OCR &&
                        it.confidence == 0.82
                },
        )
        assertTrue(CapturedQuestionDocumentValidator.validateForCommit(accepted.workingDocument).isEmpty())
    }

    @Test
    fun `only the fingerprint-matching final workspace identity may bypass another save`() {
        val finalRequest = CaptureFinalConfirmationRequestIdentity("confirm-once", 200)
        val finalState = restoreCaptureWorkspace(resumable(structuredDocument("原题"))).state
            .prepareForFinalCommit()
            .withFinalConfirmation(finalRequest)
        val matching = CaptureDraftWorkspaceIdentity(
            draftId = finalState.draftId,
            basisRevisionNumber = finalState.basisRevisionNumber,
            workspaceVersion = 2,
            workspaceFingerprint = CaptureDraftWorkspaceFingerprint.of(finalState.toDomain()),
            finalConfirmationRequest = finalRequest,
        )

        assertTrue(matching.matchesPersistedFinalState(finalState))
        assertFalse(matching.matchesPersistedFinalState(finalState.editTitle("另一份题面")))
    }

    @Test
    fun `writer never turns coroutine cancellation into a save error`() = runBlocking {
        val state = restoreCaptureWorkspace(resumable(structuredDocument("原题"))).state
        val writer = CaptureWorkspaceWriter { throw CancellationException("screen left") }

        val failure = runCatching { writer.save(state, { null }, 100) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    private fun resumable(document: CapturedQuestionDocument) = ResumableCaptureDraft(
        draftId = "draft-1",
        origin = CaptureEntryOrigin.LIBRARY,
        sourceImageUri = "file:///private/question.jpg",
        sourceAssetId = "asset-1",
        sourceAssetSha256 = SHA,
        sourceWidth = 1200,
        sourceHeight = 1600,
        sourceByteSize = 100_000,
        draftCreatedAtEpochMillis = 1_000,
        currentRevisionNumber = 1,
        currentRevisionDocumentFingerprint = SHA,
        currentRevisionCreatedAtEpochMillis = 1_100,
        subject = "MATH",
        title = document.document.title ?: "待校对题目",
        questionDocument = document,
        transcription = "旧投影",
        writingLayer = CaptureWritingLayer.PRINTED,
        latestAssessmentTask = null,
        latestParseTask = null,
        tutorSessionId = null,
        workspace = null,
        updatedAtEpochMillis = 1_200,
    )

    private fun structuredDocument(title: String) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "question-1",
            title = title,
            blocks = listOf(
                ContentBlock.Paragraph("stem", "求函数的单调区间"),
                ContentBlock.Formula("formula", "f(x)=x^2", "函数 f(x) 等于 x 平方"),
                ContentBlock.ChoiceGroup(
                    id = "choices",
                    promptMarkdown = "请选择正确结论",
                    choices = listOf(
                        StructuredChoice("a", "在实数上递增"),
                        StructuredChoice("b", "先减后增"),
                    ),
                ),
                ContentBlock.Figure(
                    id = "figure",
                    title = "函数表",
                    alternativeText = "函数值表",
                    schema = FigureSchema.SymbolTable(
                        headers = listOf("x", "f(x)"),
                        rows = listOf(listOf("0", "0")),
                    ),
                ),
            ),
        ),
        blockEvidence = listOf("stem", "formula", "choices", "figure").map { blockId ->
            QuestionBlockEvidence(
                blockId = blockId,
                sourceAssetId = "asset-1",
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = WritingLayer.PRINTED,
                provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                reviewStatus = QuestionBlockReviewStatus.CANDIDATE,
            )
        },
    )

    private companion object {
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
