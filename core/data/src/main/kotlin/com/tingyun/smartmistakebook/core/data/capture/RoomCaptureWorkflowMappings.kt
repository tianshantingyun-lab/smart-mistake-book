package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.ExpectedProblemDraftEditWorkspace
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.PendingCaptureDraftRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionSummary
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.PendingCaptureStage
import com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceCodec
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Stateless row→domain mappings and request fingerprints for
 * [RoomCaptureWorkflowRepository]. Mappers that need the asset vault stay on
 * the repository; everything here depends only on its receiver, so a signature
 * or field change surfaces as a compile error in this one file.
 */

internal data class ValidatedCaptureTasks(
    val assessments: List<ModelTaskSnapshot?>,
    val parse: ModelTaskSnapshot?,
) {
    val assessment: ModelTaskSnapshot?
        get() = assessments.filterNotNull().maxByOrNull { it.updatedAtEpochMillis }
}

internal fun ProblemDraftEditWorkspaceRecord.toDomainWorkspaceSnapshot():
    CaptureDraftWorkspaceSnapshot {
    val workspace = try {
        CaptureDraftWorkspaceCodec.decode(workspaceSnapshot)
    } catch (failure: Exception) {
        throw IllegalStateException("Persisted capture workspace is invalid", failure)
    }
    require(snapshotSchemaVersion == workspace.schemaVersion) {
        "Persisted capture workspace schema mismatch"
    }
    return CaptureDraftWorkspaceSnapshot(
        identity = CaptureDraftWorkspaceIdentity(
            draftId = draftId,
            basisRevisionNumber = basisRevisionNumber,
            workspaceVersion = workspaceVersion,
            workspaceFingerprint = workspaceFingerprint,
            finalConfirmationRequest = workspace.finalConfirmationRequest,
        ),
        workspace = workspace,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun PendingCaptureDraftRecord.validTutorSessionId(): String? {
    val sessionId = tutorSessionId ?: return null
    return sessionId.takeIf {
        draft.origin == StudyDbValue.CaptureOrigin.TUTOR &&
            tutorSessionDraftRevisionNumber == draft.currentRevision.revisionNumber
    }
}

internal fun PendingCaptureDraftRecord.validatedTasks(): ValidatedCaptureTasks {
    val assessments = draft.sourceAssets.map { source ->
        assessmentTasks.firstOrNull { task ->
            (task.request.input as? CaptureAssessmentInput)?.sourceAssetId ==
                source.sourceAsset.sourceAssetId
        }?.takeIf { it.matchesAssessment(draft, source) }
    }
    val parse = latestParseTask?.takeIf { it.matchesParse(draft, assessments) }
    return ValidatedCaptureTasks(assessments = assessments, parse = parse)
}

internal fun ModelTaskSnapshot.matchesAssessment(
    draft: ProblemDraftRecord,
    sourcePage: ProblemDraftSourceAssetRecord,
): Boolean {
    val input = request.input as? CaptureAssessmentInput ?: return false
    val source = sourcePage.sourceAsset
    return input.draftId == draft.draftId &&
        draft.currentRevision.author != StudyDbValue.ProblemDraftAuthor.USER &&
        input.sourceAssetId == source.sourceAssetId &&
        input.origin == draft.origin.toAssessmentOrigin() &&
        input.imageWidth == source.width &&
        input.imageHeight == source.height &&
        request.occurredAtEpochMillis == source.createdAtEpochMillis
}

internal fun ModelTaskSnapshot.matchesParse(
    draft: ProblemDraftRecord,
    assessments: List<ModelTaskSnapshot?>,
): Boolean {
    val input = request.input as? CaptureParseInput ?: return false
    if (assessments.size != draft.sourceAssets.size || assessments.any { it == null }) return false
    val validatedAssessments = assessments.filterNotNull()
    val requestedSources = input.sourceAssets.sortedBy { it.pageIndex }
    val draftSources = draft.sourceAssets.sortedBy { it.pageIndex }
    if (requestedSources.size != draftSources.size) return false
    val parseProvider = provider
    val providerBindingMatches = parseProvider == null ||
        validatedAssessments.all { it.provider?.isDemo == parseProvider.isDemo }
    return input.draftId == draft.draftId &&
        input.origin == draft.origin.toAssessmentOrigin() &&
        input.basisRevisionNumber == draft.currentRevision.revisionNumber &&
        input.assessmentRequestIds == validatedAssessments.map { it.request.requestId } &&
        validatedAssessments.zip(draftSources).all { (assessment, sourcePage) ->
            val decision = (assessment.output as? CaptureAssessmentOutput)?.assessment?.decision
            assessment.status == ModelTaskStatus.SUCCEEDED &&
                (decision == CaptureAssessmentDecision.PASS ||
                    (decision == CaptureAssessmentDecision.NEED_MORE_IMAGE &&
                        sourcePage.pageIndex < draftSources.lastIndex))
        } &&
        requestedSources.zip(draftSources).all { (requested, sourcePage) ->
            val source = sourcePage.sourceAsset
            requested.pageIndex == sourcePage.pageIndex &&
                requested.assetId == source.sourceAssetId &&
                requested.sha256 == source.contentSha256 &&
                requested.width == source.width &&
                requested.height == source.height
        } &&
        request.occurredAtEpochMillis >= draftSources.maxOf { it.sourceAsset.createdAtEpochMillis } &&
        providerBindingMatches &&
        (status != ModelTaskStatus.SUCCEEDED || parseProvider != null)
}

internal fun PendingCaptureDraftRecord.stageFor(
    tasks: ValidatedCaptureTasks,
): PendingCaptureStage {
    val requiresMoreCapture = tasks.assessments.lastOrNull()?.let { assessment ->
        assessment.status == ModelTaskStatus.SUCCEEDED &&
            (assessment.output as? CaptureAssessmentOutput)?.assessment?.decision in setOf(
            CaptureAssessmentDecision.RECAPTURE,
            CaptureAssessmentDecision.NEED_MORE_IMAGE,
        )
    } == true
    if (requiresMoreCapture) {
        return PendingCaptureStage.RECAPTURE_REQUIRED
    }
    if (tasks.assessments.any { it.isWorking() } || tasks.parse.isWorking()) {
        return PendingCaptureStage.MODEL_WORKING
    }
    if (tasks.assessments.any { it.needsRetryOrManual() } || tasks.parse.needsRetryOrManual()) {
        return PendingCaptureStage.RETRY_OR_MANUAL
    }
    if (tasks.parse?.status == ModelTaskStatus.SUCCEEDED) {
        return if (tasks.parse.provider?.isDemo == false) {
            PendingCaptureStage.READY_TO_REVIEW
        } else {
            PendingCaptureStage.MANUAL_REVIEW_REQUIRED
        }
    }
    if (
        draft.currentRevision.author in setOf(
            StudyDbValue.ProblemDraftAuthor.LOCAL_OCR,
            StudyDbValue.ProblemDraftAuthor.OPTIONAL_REMOTE_OCR,
        ) &&
        QuestionDocumentMarkdownProjection.project(
            draft.currentRevision.questionDocument.document,
        ).isNotBlank()
    ) {
        return PendingCaptureStage.MANUAL_REVIEW_REQUIRED
    }
    return PendingCaptureStage.READY_TO_CONTINUE
}

internal fun ModelTaskSnapshot?.isWorking(): Boolean = this?.status in setOf(
    ModelTaskStatus.WAITING_FOR_MODEL,
    ModelTaskStatus.QUEUED,
    ModelTaskStatus.RUNNING,
    ModelTaskStatus.STREAMING,
)

internal fun ModelTaskSnapshot?.needsRetryOrManual(): Boolean = this?.status in setOf(
    ModelTaskStatus.RETRYABLE_FAILURE,
    ModelTaskStatus.PERMANENT_FAILURE,
    ModelTaskStatus.CANCELLED,
)

internal fun CapturedQuestionDocument.captureWritingLayer(): CaptureWritingLayer {
    val layers = blockEvidence.map(QuestionBlockEvidence::writingLayer)
        .filterNot { it == WritingLayer.UNKNOWN || it == WritingLayer.DIAGRAM }
        .distinct()
    if (WritingLayer.MIXED in layers || layers.size > 1) return CaptureWritingLayer.MIXED
    return when (layers.singleOrNull()) {
        WritingLayer.PRINTED -> CaptureWritingLayer.PRINTED
        WritingLayer.HANDWRITTEN -> CaptureWritingLayer.HANDWRITTEN
        WritingLayer.MIXED -> CaptureWritingLayer.MIXED
        WritingLayer.DIAGRAM -> CaptureWritingLayer.UNKNOWN
        WritingLayer.UNKNOWN, null -> CaptureWritingLayer.UNKNOWN
    }
}

internal fun String.toDomainOrigin(): CaptureEntryOrigin = when (this) {
    StudyDbValue.CaptureOrigin.LIBRARY -> CaptureEntryOrigin.LIBRARY
    StudyDbValue.CaptureOrigin.TUTOR -> CaptureEntryOrigin.TUTOR
    else -> error("Unknown pending capture origin")
}

internal fun String.toAssessmentOrigin(): CaptureAssessmentOrigin = when (this) {
    StudyDbValue.CaptureOrigin.LIBRARY -> CaptureAssessmentOrigin.LIBRARY
    StudyDbValue.CaptureOrigin.TUTOR -> CaptureAssessmentOrigin.TUTOR
    else -> error("Unknown pending capture origin")
}

internal fun validateImportReplay(
    draft: ProblemDraftRecord,
    request: CaptureDraftImportRequest,
    imported: CanonicalSourceAssetRecord,
) {
    val matchesPersistedBinding =
        draft.requestFingerprint == importRequestFingerprint(request, imported) &&
            draft.sourceAsset.hasSameCanonicalContent(imported)
    if (!matchesPersistedBinding) {
        throw ImmutablePayloadConflictException("capture_import_request", request.requestId)
    }
}

internal fun validateCompletedReplacement(
    replaced: ProblemDraftRecord,
    replacement: ProblemDraftRecord,
    request: ReplaceCaptureDraftRequest,
) {
    val matchesDurableResult =
        replaced.status == StudyDbValue.ProblemDraftStatus.ABANDONED &&
            replaced.currentRevision.revisionNumber ==
                request.expectedReplacedRevisionNumber &&
            replaced.updatedAtEpochMillis == request.occurredAtEpochMillis &&
            replacement.origin == replaced.origin &&
            replacement.createdAtEpochMillis == request.occurredAtEpochMillis &&
            replacement.requestFingerprint == replacementRequestFingerprint(replaced, request)
    if (!matchesDurableResult) {
        throw ImmutablePayloadConflictException(
            "capture_replacement_request",
            request.requestId,
        )
    }
}

internal fun CanonicalSourceAssetRecord.hasSameCanonicalContent(
    other: CanonicalSourceAssetRecord,
): Boolean = sourceAssetId == other.sourceAssetId &&
    contentSha256 == other.contentSha256 &&
    relativePath == other.relativePath &&
    mimeType == other.mimeType &&
    byteSize == other.byteSize &&
    width == other.width &&
    height == other.height

internal fun importRequestFingerprint(
    request: CaptureDraftImportRequest,
    imported: CanonicalSourceAssetRecord,
): String = requestFingerprint(
    "capture-import-v2",
    request.requestId,
    request.source.toDbValue(),
    request.origin.toDbValue(),
    request.occurredAtEpochMillis.toString(),
    imported.sourceAssetId,
    imported.contentSha256,
    imported.relativePath,
    imported.mimeType,
    imported.byteSize.toString(),
    imported.width.toString(),
    imported.height.toString(),
)

internal fun replacementRequestFingerprint(
    replaced: ProblemDraftRecord,
    request: ReplaceCaptureDraftRequest,
): String = requestFingerprint(
    "capture-replacement-v2",
    request.requestId,
    request.replacedDraftId,
    request.expectedReplacedRevisionNumber.toString(),
    request.source.toDbValue(),
    request.occurredAtEpochMillis.toString(),
    replaced.origin,
)

internal fun splitDraftId(
    request: SplitCaptureDraftRequest,
    regionIndex: Int,
    region: NormalizedSourceRegion,
): String = stableId(
    "split-draft",
    listOf(
        request.requestId,
        request.draftId,
        request.assessmentRequestId,
        regionIndex.toString(),
        region.fingerprintValue(),
    ).joinToString(separator = ":"),
)

internal fun splitRequestFingerprint(
    replaced: ProblemDraftRecord,
    request: SplitCaptureDraftRequest,
    region: NormalizedSourceRegion,
    regionIndex: Int,
    cropped: CanonicalSourceAssetRecord,
): String = requestFingerprint(
    "capture-split-v1",
    request.requestId,
    request.draftId,
    request.expectedRevisionNumber.toString(),
    request.assessmentRequestId,
    request.sourceAssetId,
    replaced.sourceAsset.contentSha256,
    regionIndex.toString(),
    region.fingerprintValue(),
    cropped.contentSha256,
    request.occurredAtEpochMillis.toString(),
)

internal fun NormalizedSourceRegion.fingerprintValue(): String =
    listOf(left, top, right, bottom).joinToString(separator = ",") { coordinate ->
        coordinate.toString()
    }

internal fun List<NormalizedSourceRegion>.boundingRegion(): NormalizedSourceRegion =
    NormalizedSourceRegion(
        left = minOf { region -> region.left },
        top = minOf { region -> region.top },
        right = maxOf { region -> region.right },
        bottom = maxOf { region -> region.bottom },
    )

internal fun requestFingerprint(vararg fields: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(fields.joinToString(separator = "\u001F").toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun ProblemDraftRecord.recognitionSummary(
    recognitionFailed: Boolean,
): CaptureRecognitionSummary {
    if (recognitionFailed) {
        return CaptureRecognitionSummary(state = CaptureRecognitionState.FAILED)
    }
    if (currentRevision.author != StudyDbValue.ProblemDraftAuthor.LOCAL_OCR) {
        return CaptureRecognitionSummary()
    }
    val candidateText = QuestionDocumentMarkdownProjection.project(
        currentRevision.questionDocument.document,
    ).trim()
    val evidence = currentRevision.questionDocument.blockEvidence
    if (candidateText.isBlank()) {
        return CaptureRecognitionSummary(
            state = CaptureRecognitionState.NO_TEXT,
            producerVersion = evidence.mapNotNull(QuestionBlockEvidence::producerVersion)
                .distinct()
                .singleOrNull(),
        )
    }
    return CaptureRecognitionSummary(
        state = CaptureRecognitionState.CANDIDATE_AVAILABLE,
        candidateText = candidateText.take(StructuredContentLimits.MAX_TEXT_CHARS),
        confidence = evidence.mapNotNull(QuestionBlockEvidence::confidence)
            .takeIf(List<Double>::isNotEmpty)
            ?.average(),
        candidateBlockCount = currentRevision.questionDocument.document.blocks.size,
        producerVersion = evidence.mapNotNull(QuestionBlockEvidence::producerVersion)
            .distinct()
            .singleOrNull(),
    )
}

internal fun CaptureInputSource.toDbValue(): String = when (this) {
    CaptureInputSource.CAMERA -> StudyDbValue.SourceAssetType.CAMERA
    CaptureInputSource.PHOTO_PICKER -> StudyDbValue.SourceAssetType.PHOTO_PICKER
}

internal fun CaptureEntryOrigin.toDbValue(): String = when (this) {
    CaptureEntryOrigin.LIBRARY -> StudyDbValue.CaptureOrigin.LIBRARY
    CaptureEntryOrigin.TUTOR -> StudyDbValue.CaptureOrigin.TUTOR
}

internal fun stableId(prefix: String, requestId: String): String =
    "$prefix-${stableSuffix(requestId)}"

internal fun CaptureDraftWorkspaceIdentity.toDatabaseExpectation():
    ExpectedProblemDraftEditWorkspace {
    val finalRequest = checkNotNull(finalConfirmationRequest) {
        "Capture workspace has no final confirmation identity"
    }
    return ExpectedProblemDraftEditWorkspace(
        draftId = draftId,
        basisRevisionNumber = basisRevisionNumber,
        workspaceVersion = workspaceVersion,
        workspaceFingerprint = workspaceFingerprint,
        finalRequestId = finalRequest.requestId,
        finalOccurredAtEpochMillis = finalRequest.occurredAtEpochMillis,
    )
}

internal fun confirmationStableSuffix(identity: CaptureDraftWorkspaceIdentity): String {
    val finalRequest = checkNotNull(identity.finalConfirmationRequest)
    val canonical = listOf(
        identity.draftId,
        identity.basisRevisionNumber.toString(),
        identity.workspaceVersion.toString(),
        identity.workspaceFingerprint,
        finalRequest.requestId,
        finalRequest.occurredAtEpochMillis.toString(),
    ).joinToString(separator = "\u001F")
    return stableSuffix(canonical)
}

internal fun stableSuffix(requestId: String): String = MessageDigest.getInstance("SHA-256")
    .digest(requestId.toByteArray(StandardCharsets.UTF_8))
    .take(16)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
