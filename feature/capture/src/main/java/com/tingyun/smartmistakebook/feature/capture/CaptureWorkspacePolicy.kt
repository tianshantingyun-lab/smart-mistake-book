package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft
import com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspace
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceFingerprint
import com.tingyun.smartmistakebook.core.model.CaptureFinalConfirmationRequestIdentity
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.RestrictedFormulaText
import com.tingyun.smartmistakebook.core.model.SafeInlineMarkdown
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val CAPTURE_WORKSPACE_DEBOUNCE_MILLIS = 400L

internal data class CaptureWorkspaceUiState(
    val draftId: String,
    val basisRevisionNumber: Int,
    val baseCandidateFingerprint: String,
    val subject: String,
    val workingDocument: CapturedQuestionDocument,
    val editorMode: CaptureDraftEditorMode,
    val userEditedFields: Set<CaptureDraftEditedField>,
    val userEditedBlockIds: Set<String>,
    val finalConfirmationRequest: CaptureFinalConfirmationRequestIdentity?,
) {
    val title: String
        get() = workingDocument.document.title.orEmpty()

    val transcription: String
        get() = QuestionDocumentMarkdownProjection.project(
            workingDocument.document.copy(title = null),
        ).trim()

    fun toDomain(): CaptureDraftWorkspace = CaptureDraftWorkspace(
        subject = subject.ifBlank { null },
        workingDocument = workingDocument,
        editorMode = editorMode,
        userEditedFields = userEditedFields,
        userEditedBlockIds = userEditedBlockIds,
        baseCandidateFingerprint = baseCandidateFingerprint,
        finalConfirmationRequest = finalConfirmationRequest,
    )
}

internal data class CaptureWorkspaceLocalSnapshot(
    val state: CaptureWorkspaceUiState,
    val identity: CaptureDraftWorkspaceIdentity?,
    val updatedAtEpochMillis: Long,
)

internal fun restoreCaptureWorkspace(draft: ResumableCaptureDraft): CaptureWorkspaceLocalSnapshot {
    val persisted = draft.workspace
    val workspace = persisted?.workspace
    val candidateDocument = workspace?.workingDocument ?: draft.questionDocument
    val workingDocument = (if (
        workspace == null && candidateDocument.document.title.isNullOrBlank()
    ) {
        candidateDocument.copy(document = candidateDocument.document.copy(title = draft.title))
    } else {
        candidateDocument
    }).withDerivedCaptureTitle()
    return CaptureWorkspaceLocalSnapshot(
        state = CaptureWorkspaceUiState(
            draftId = draft.draftId,
            basisRevisionNumber = draft.currentRevisionNumber,
            baseCandidateFingerprint = draft.currentRevisionDocumentFingerprint,
            subject = workspace?.subject ?: draft.subject.orEmpty(),
            workingDocument = workingDocument,
            editorMode = workspace?.editorMode ?: CaptureDraftEditorMode.STRUCTURED_DOCUMENT,
            userEditedFields = workspace?.userEditedFields.orEmpty(),
            userEditedBlockIds = workspace?.userEditedBlockIds.orEmpty(),
            finalConfirmationRequest = workspace?.finalConfirmationRequest,
        ),
        identity = persisted?.identity,
        updatedAtEpochMillis = persisted?.updatedAtEpochMillis ?: 0,
    )
}

/** A late model candidate may only seed a pristine structured workspace. */
internal fun CaptureWorkspaceUiState.adoptModelCandidateIfPristine(
    candidate: CapturedQuestionDocument,
): CaptureWorkspaceUiState {
    if (
        editorMode != CaptureDraftEditorMode.STRUCTURED_DOCUMENT ||
        userEditedFields.isNotEmpty() ||
        userEditedBlockIds.isNotEmpty() ||
        finalConfirmationRequest != null
    ) {
        return this
    }
    if (CapturedQuestionDocumentValidator.validateDraft(candidate).isNotEmpty()) return this
    return copy(workingDocument = candidate.withDerivedCaptureTitle())
}

internal fun CaptureWorkspaceUiState.editSubject(value: String): CaptureWorkspaceUiState = copy(
    subject = value,
    userEditedFields = userEditedFields + CaptureDraftEditedField.SUBJECT,
)

internal fun CaptureWorkspaceUiState.editTitle(value: String): CaptureWorkspaceUiState = copy(
    workingDocument = workingDocument.copy(
        document = workingDocument.document.copy(
            title = value.take(StructuredContentLimits.MAX_TITLE_CHARS),
        ),
    ),
    userEditedFields = userEditedFields + CaptureDraftEditedField.TITLE,
)

internal fun CaptureWorkspaceUiState.editWritingLayer(
    value: CaptureWritingLayer,
): CaptureWorkspaceUiState {
    val layer = value.toModelWritingLayer()
    return copy(
        workingDocument = workingDocument.copy(
            blockEvidence = workingDocument.blockEvidence.map { evidence ->
                if (evidence.writingLayer == WritingLayer.DIAGRAM) evidence
                else evidence.copy(writingLayer = layer)
            },
        ),
        userEditedFields = userEditedFields + CaptureDraftEditedField.WRITING_LAYER,
    )
}

internal fun CaptureWorkspaceUiState.editBlock(updated: ContentBlock): CaptureWorkspaceUiState {
    val index = workingDocument.document.blocks.indexOfFirst { it.id == updated.id }
    if (index < 0) return this
    val original = workingDocument.document.blocks[index]
    if (original::class != updated::class) return this
    val blocks = workingDocument.document.blocks.toMutableList().also { it[index] = updated }
    val candidate = workingDocument.copy(
        document = workingDocument.document.copy(blocks = blocks),
        blockEvidence = workingDocument.blockEvidence.map { evidence ->
            if (evidence.blockId != updated.id) evidence else evidence.copy(
                provenance = QuestionBlockProvenance.USER_CORRECTION,
                confidence = null,
                reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                producerVersion = null,
            )
        },
    ).withDerivedCaptureTitle()
    if (CapturedQuestionDocumentValidator.validateDraft(candidate).isNotEmpty()) return this
    return copy(
        workingDocument = candidate,
        userEditedFields = userEditedFields + CaptureDraftEditedField.STRUCTURE,
        userEditedBlockIds = userEditedBlockIds + updated.id,
    )
}

internal fun CaptureWorkspaceUiState.hasResolvedWritingLayers(): Boolean =
    workingDocument.blockEvidence.isNotEmpty() &&
        workingDocument.blockEvidence.all { it.writingLayer != WritingLayer.UNKNOWN }

internal fun CaptureDraftWorkspaceIdentity.matchesPersistedFinalState(
    state: CaptureWorkspaceUiState,
): Boolean = draftId == state.draftId &&
    basisRevisionNumber == state.basisRevisionNumber &&
    finalConfirmationRequest == state.finalConfirmationRequest &&
    workspaceFingerprint == CaptureDraftWorkspaceFingerprint.of(state.toDomain())

internal fun safeParagraphEdit(value: String): String {
    val bounded = value.take(StructuredContentLimits.MAX_TEXT_CHARS)
    return if (SafeInlineMarkdown.requiresPlainTextFallback(bounded)) {
        SafeInlineMarkdown.literal(bounded)
    } else {
        bounded
    }
}

internal fun safeFormulaEdit(value: String): String = RestrictedFormulaText.sanitize(
    value.take(StructuredContentLimits.MAX_FORMULA_CHARS),
)

internal fun CaptureWorkspaceUiState.withFinalConfirmation(
    identity: CaptureFinalConfirmationRequestIdentity,
): CaptureWorkspaceUiState = copy(finalConfirmationRequest = finalConfirmationRequest ?: identity)

internal fun CaptureWorkspaceUiState.replaceFinalConfirmation(
    identity: CaptureFinalConfirmationRequestIdentity,
): CaptureWorkspaceUiState = copy(finalConfirmationRequest = identity)

internal fun CaptureWorkspaceUiState.ensureFinalConfirmation(
    requestIdFactory: () -> String,
    occurredAtEpochMillis: () -> Long,
): CaptureWorkspaceUiState {
    if (finalConfirmationRequest != null) return this
    return withFinalConfirmation(
        CaptureFinalConfirmationRequestIdentity(
            requestId = requestIdFactory(),
            occurredAtEpochMillis = occurredAtEpochMillis(),
        ),
    )
}

internal fun CaptureWorkspaceUiState.canEditAsOneTextField(): Boolean =
    (workingDocument.document.blocks.singleOrNull() as? ContentBlock.Paragraph)
        ?.markdown
        ?.isNotBlank() == true

internal fun CaptureWorkspaceUiState.captureWritingLayer(): CaptureWritingLayer {
    val layers = workingDocument.blockEvidence
        .map { it.writingLayer }
        .filterNot { it == WritingLayer.UNKNOWN || it == WritingLayer.DIAGRAM }
        .distinct()
    if (WritingLayer.MIXED in layers || layers.size > 1) return CaptureWritingLayer.MIXED
    return when (layers.singleOrNull()) {
        WritingLayer.PRINTED -> CaptureWritingLayer.PRINTED
        WritingLayer.HANDWRITTEN -> CaptureWritingLayer.HANDWRITTEN
        WritingLayer.MIXED -> CaptureWritingLayer.MIXED
        else -> CaptureWritingLayer.UNKNOWN
    }
}

internal fun CaptureWorkspaceUiState.editSingleParagraph(value: String): CaptureWorkspaceUiState {
    val paragraph = workingDocument.document.blocks.singleOrNull() as? ContentBlock.Paragraph
        ?: return this
    if (paragraph.markdown.isBlank()) return this
    return editBlock(paragraph.copy(markdown = safeParagraphEdit(value))).copy(
        editorMode = CaptureDraftEditorMode.STRUCTURED_DOCUMENT,
        userEditedFields = userEditedFields + CaptureDraftEditedField.TRANSCRIPTION,
    )
}

internal fun captureCandidateIsUsable(candidate: CapturedQuestionDocument?): Boolean {
    if (candidate == null) return false
    if (QuestionDocumentMarkdownProjection.project(candidate.document).isBlank()) return false
    val readyDocument = candidate
        .withDerivedCaptureTitle()
        .withFinalEvidenceDefaults(userEditedBlockIds = emptySet())
    return readyDocument.document.title?.isNotBlank() == true &&
        CapturedQuestionDocumentValidator.validateForCommit(readyDocument).isEmpty()
}

/**
 * The final CTA accepts a complete candidate without adding a second verification wall. Only
 * blocks the student actually changed become USER_CONFIRMED; untouched evidence remains traceable
 * to its original producer and is marked as accepted by deterministic local policy.
 */
internal fun CaptureWorkspaceUiState.prepareForFinalCommit(): CaptureWorkspaceUiState = copy(
    subject = subject.ifBlank { DEFAULT_CAPTURE_SUBJECT },
    workingDocument = workingDocument
        .withDerivedCaptureTitle()
        .withFinalEvidenceDefaults(userEditedBlockIds),
)

internal sealed interface CaptureWorkspaceWriteResult {
    data class Saved(val snapshot: CaptureDraftWorkspaceSnapshot) : CaptureWorkspaceWriteResult
    data class Failed(val cause: Throwable) : CaptureWorkspaceWriteResult
}

internal fun interface CaptureWorkspaceSaveGateway {
    suspend fun save(request: SaveCaptureDraftWorkspaceRequest): CaptureDraftWorkspaceSnapshot
}

/** Serializes every autosave and explicit flush through one CAS writer. */
internal class CaptureWorkspaceWriter(
    private val gateway: CaptureWorkspaceSaveGateway,
) {
    private val mutex = Mutex()

    suspend fun save(
        state: CaptureWorkspaceUiState,
        currentIdentity: () -> CaptureDraftWorkspaceIdentity?,
        occurredAtEpochMillis: Long,
    ): CaptureWorkspaceWriteResult = mutex.withLock {
        val expected = currentIdentity()
        try {
            CaptureWorkspaceWriteResult.Saved(
                gateway.save(
                    SaveCaptureDraftWorkspaceRequest(
                        draftId = state.draftId,
                        basisRevisionNumber = state.basisRevisionNumber,
                        expectedWorkspaceVersion = expected?.workspaceVersion ?: 0,
                        expectedWorkspaceFingerprint = expected?.workspaceFingerprint,
                        workspace = state.toDomain(),
                        occurredAtEpochMillis = occurredAtEpochMillis,
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            CaptureWorkspaceWriteResult.Failed(failure)
        }
    }
}

private fun CaptureWritingLayer.toModelWritingLayer(): WritingLayer = when (this) {
    CaptureWritingLayer.PRINTED -> WritingLayer.PRINTED
    CaptureWritingLayer.HANDWRITTEN -> WritingLayer.HANDWRITTEN
    CaptureWritingLayer.MIXED -> WritingLayer.MIXED
    CaptureWritingLayer.UNKNOWN -> WritingLayer.UNKNOWN
}

private fun CapturedQuestionDocument.withDerivedCaptureTitle(): CapturedQuestionDocument {
    val currentTitle = document.title?.trim().orEmpty()
    if (currentTitle.isNotBlank() && currentTitle !in CAPTURE_PLACEHOLDER_TITLES) return this
    val projection = QuestionDocumentMarkdownProjection.project(document.copy(title = null)).trim()
    if (projection.isBlank()) return this
    return copy(document = document.copy(title = suggestCaptureTitle(projection)))
}

private fun CapturedQuestionDocument.withFinalEvidenceDefaults(
    userEditedBlockIds: Set<String>,
): CapturedQuestionDocument {
    return copy(
        blockEvidence = blockEvidence.map { evidence ->
            evidence.copy(
                reviewStatus = when {
                    evidence.blockId in userEditedBlockIds ->
                        QuestionBlockReviewStatus.USER_CONFIRMED
                    evidence.reviewStatus == QuestionBlockReviewStatus.USER_CONFIRMED ->
                        QuestionBlockReviewStatus.USER_CONFIRMED
                    else -> QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED
                },
            )
        },
    )
}

private const val DEFAULT_CAPTURE_SUBJECT = "GENERAL"
private val CAPTURE_PLACEHOLDER_TITLES = setOf("新拍题目", "待校对题目")
