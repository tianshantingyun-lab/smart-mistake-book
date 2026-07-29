package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspace
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceFingerprint
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspaceValidator
import com.tingyun.smartmistakebook.core.model.CaptureFinalConfirmationRequestIdentity
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import kotlinx.coroutines.flow.Flow

enum class CaptureInputSource {
    CAMERA,
    PHOTO_PICKER,
}

enum class CaptureEntryOrigin {
    LIBRARY,
    TUTOR,
}

enum class CaptureWritingLayer {
    PRINTED,
    HANDWRITTEN,
    MIXED,
    UNKNOWN,
}

enum class CaptureRecognitionState {
    NOT_ATTEMPTED,
    CANDIDATE_AVAILABLE,
    NO_TEXT,
    FAILED,
}

enum class PendingCaptureStage {
    TUTOR_SESSION_READY,
    SOURCE_UNAVAILABLE,
    RECAPTURE_REQUIRED,
    MODEL_WORKING,
    RETRY_OR_MANUAL,
    MANUAL_REVIEW_REQUIRED,
    READY_TO_REVIEW,
    READY_TO_CONTINUE,
}

data class PendingCaptureItem(
    val draftId: String,
    val origin: CaptureEntryOrigin,
    val subject: String?,
    val title: String,
    val currentRevisionNumber: Int,
    val updatedAtEpochMillis: Long,
    val stage: PendingCaptureStage,
    val tutorSessionId: String?,
) {
    init {
        require(draftId.isNotBlank()) { "Pending capture draft id must not be blank" }
        require(subject == null || subject.isNotBlank()) {
            "Pending capture subject must be null or non-blank"
        }
        require(title.isNotBlank()) { "Pending capture title must not be blank" }
        require(currentRevisionNumber > 0) { "Pending capture revision must be positive" }
        require(updatedAtEpochMillis >= 0) { "Pending capture update time must not be negative" }
        require(tutorSessionId == null || tutorSessionId.isNotBlank()) {
            "Pending tutor session id must be null or non-blank"
        }
        require(stage == PendingCaptureStage.TUTOR_SESSION_READY || tutorSessionId == null) {
            "Only a ready tutor session may be exposed from the pending list"
        }
    }
}

data class CaptureDraftWorkspaceIdentity(
    val draftId: String,
    val basisRevisionNumber: Int,
    val workspaceVersion: Long,
    val workspaceFingerprint: String,
    val finalConfirmationRequest: CaptureFinalConfirmationRequestIdentity?,
) {
    init {
        require(draftId.isNotBlank()) { "Workspace draft id must not be blank" }
        require(basisRevisionNumber > 0) { "Workspace basis revision must be positive" }
        require(workspaceVersion > 0) { "Workspace version must be positive" }
        require(SHA_256.matches(workspaceFingerprint)) { "Workspace fingerprint is invalid" }
    }

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

data class CaptureDraftWorkspaceSnapshot(
    val identity: CaptureDraftWorkspaceIdentity,
    val workspace: CaptureDraftWorkspace,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(CaptureDraftWorkspaceValidator.validate(workspace).isEmpty()) {
            "Capture workspace snapshot is invalid"
        }
        require(
            identity.workspaceFingerprint == CaptureDraftWorkspaceFingerprint.of(workspace),
        ) { "Capture workspace snapshot fingerprint mismatch" }
        require(identity.finalConfirmationRequest == workspace.finalConfirmationRequest) {
            "Capture workspace confirmation identity mismatch"
        }
        require(updatedAtEpochMillis >= 0) { "Workspace update time must not be negative" }
    }
}

data class SaveCaptureDraftWorkspaceRequest(
    val draftId: String,
    val basisRevisionNumber: Int,
    val expectedWorkspaceVersion: Long,
    val expectedWorkspaceFingerprint: String?,
    val workspace: CaptureDraftWorkspace,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(draftId.isNotBlank()) { "Workspace draft id must not be blank" }
        require(basisRevisionNumber > 0) { "Workspace basis revision must be positive" }
        require(expectedWorkspaceVersion >= 0) { "Expected workspace version must not be negative" }
        require(
            (expectedWorkspaceVersion == 0L && expectedWorkspaceFingerprint == null) ||
                (expectedWorkspaceVersion > 0L &&
                    expectedWorkspaceFingerprint != null &&
                    SHA_256.matches(expectedWorkspaceFingerprint)),
        ) { "Expected workspace version and fingerprint must describe the same snapshot" }
        require(CaptureDraftWorkspaceValidator.validate(workspace).isEmpty()) {
            "Capture workspace is invalid"
        }
        require(occurredAtEpochMillis >= 0) { "Workspace update time must not be negative" }
    }

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

data class ConsumeCaptureDraftWorkspaceRequest(
    val identity: CaptureDraftWorkspaceIdentity,
)

data class ResumableCaptureDraft(
    val draftId: String,
    val origin: CaptureEntryOrigin,
    val sourceImageUri: String,
    val sourceAssetId: String,
    val sourceAssetSha256: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceByteSize: Long,
    val draftCreatedAtEpochMillis: Long,
    val sourcePages: List<CaptureSourcePage> = listOf(
        CaptureSourcePage(
            pageIndex = 0,
            imageUri = sourceImageUri,
            sourceAssetId = sourceAssetId,
            sourceAssetSha256 = sourceAssetSha256,
            width = sourceWidth,
            height = sourceHeight,
            byteSize = sourceByteSize,
            createdAtEpochMillis = draftCreatedAtEpochMillis,
        ),
    ),
    val currentRevisionNumber: Int,
    val currentRevisionDocumentFingerprint: String,
    val currentRevisionCreatedAtEpochMillis: Long,
    val subject: String?,
    val title: String,
    val questionDocument: CapturedQuestionDocument,
    val transcription: String,
    val writingLayer: CaptureWritingLayer,
    val latestAssessmentTask: ModelTaskSnapshot?,
    val sourcePageAssessmentTasks: List<ModelTaskSnapshot?> = listOf(latestAssessmentTask),
    val latestParseTask: ModelTaskSnapshot?,
    val tutorSessionId: String?,
    val workspace: CaptureDraftWorkspaceSnapshot?,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(draftId.isNotBlank()) { "Resumable capture draft id must not be blank" }
        require(sourceImageUri.isNotBlank()) { "Resumable capture source URI must not be blank" }
        require(sourceAssetId.isNotBlank()) { "Resumable capture source id must not be blank" }
        require(SHA_256.matches(sourceAssetSha256)) { "Resumable capture source hash is invalid" }
        require(sourceWidth > 0 && sourceHeight > 0) {
            "Resumable capture source dimensions must be positive"
        }
        require(sourceByteSize > 0) { "Resumable capture source bytes must be positive" }
        require(sourcePages.isNotEmpty()) { "Resumable capture requires at least one source page" }
        require(sourcePages.map { it.pageIndex } == sourcePages.indices.toList()) {
            "Resumable capture source pages must be ordered and contiguous"
        }
        require(sourcePages.first().sourceAssetId == sourceAssetId) {
            "Resumable capture primary source must be page zero"
        }
        require(draftCreatedAtEpochMillis >= 0) { "Draft creation time must not be negative" }
        require(currentRevisionNumber > 0) { "Current draft revision must be positive" }
        require(SHA_256.matches(currentRevisionDocumentFingerprint)) {
            "Current draft revision fingerprint is invalid"
        }
        require(currentRevisionCreatedAtEpochMillis >= draftCreatedAtEpochMillis) {
            "Current draft revision cannot predate its draft"
        }
        require(subject == null || subject.isNotBlank()) {
            "Resumable capture subject must be null or non-blank"
        }
        require(title.isNotBlank()) { "Resumable capture title must not be blank" }
        require(transcription.length <= StructuredContentLimits.MAX_TEXT_CHARS) {
            "Resumable capture transcription exceeds the structured-content budget"
        }
        require(CapturedQuestionDocumentValidator.validateDraft(questionDocument).isEmpty()) {
            "Resumable capture question document is invalid"
        }
        require(updatedAtEpochMillis >= currentRevisionCreatedAtEpochMillis) {
            "Draft update time cannot precede its current revision"
        }
        require(tutorSessionId == null || origin == CaptureEntryOrigin.TUTOR) {
            "Only tutor captures may expose a tutor session"
        }
        require(sourcePageAssessmentTasks.size == sourcePages.size) {
            "Every capture source page requires one assessment task slot"
        }
        workspace?.let { snapshot ->
            require(snapshot.identity.draftId == draftId) {
                "Capture workspace belongs to another draft"
            }
            require(snapshot.identity.basisRevisionNumber == currentRevisionNumber) {
                "Capture workspace is based on a stale draft revision"
            }
            require(
                snapshot.workspace.baseCandidateFingerprint == currentRevisionDocumentFingerprint,
            ) {
                "Capture workspace is based on a different candidate"
            }
        }
    }

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

data class CaptureSourcePage(
    val pageIndex: Int,
    val imageUri: String,
    val sourceAssetId: String,
    val sourceAssetSha256: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val createdAtEpochMillis: Long = 0,
) {
    init {
        require(pageIndex >= 0) { "Capture source page index must not be negative" }
        require(imageUri.isNotBlank()) { "Capture source page URI must not be blank" }
        require(sourceAssetId.isNotBlank()) { "Capture source page id must not be blank" }
        require(SHA_256.matches(sourceAssetSha256)) { "Capture source page hash is invalid" }
        require(width > 0 && height > 0) { "Capture source page dimensions must be positive" }
        require(byteSize > 0) { "Capture source page bytes must be positive" }
        require(createdAtEpochMillis >= 0) { "Capture source page time must not be negative" }
    }

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

enum class CaptureTranscriptionReview {
    MANUAL_ENTRY,
    OCR_CANDIDATE_EXPLICITLY_CONFIRMED,
    MODEL_DOCUMENT_EXPLICITLY_CONFIRMED,
}

data class CaptureRecognitionSummary(
    val state: CaptureRecognitionState = CaptureRecognitionState.NOT_ATTEMPTED,
    val candidateText: String = "",
    val confidence: Double? = null,
    val candidateBlockCount: Int = 0,
    val producerVersion: String? = null,
) {
    init {
        require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0) {
            "Recognition confidence must be between zero and one"
        }
        require(candidateBlockCount >= 0) { "Recognition block count must not be negative" }
        require(
            state != CaptureRecognitionState.CANDIDATE_AVAILABLE || candidateText.isNotBlank(),
        ) { "An available recognition candidate must contain text" }
        require(
            state == CaptureRecognitionState.CANDIDATE_AVAILABLE || candidateText.isBlank(),
        ) { "Only an available recognition candidate may expose text" }
    }
}

data class CaptureDraftImportRequest(
    val requestId: String,
    val localUri: String,
    val source: CaptureInputSource,
    val origin: CaptureEntryOrigin,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "Capture import request id must not be blank" }
        require(localUri.isNotBlank()) { "Capture import URI must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Capture import time must not be negative" }
    }
}

data class ReplaceCaptureDraftRequest(
    val requestId: String,
    val replacedDraftId: String,
    val expectedReplacedRevisionNumber: Int,
    val localUri: String,
    val source: CaptureInputSource,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "Capture replacement request id must not be blank" }
        require(replacedDraftId.isNotBlank()) { "Replaced draft id must not be blank" }
        require(expectedReplacedRevisionNumber > 0) {
            "Expected replaced draft revision must be positive"
        }
        require(localUri.isNotBlank()) { "Capture replacement URI must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Capture replacement time must not be negative" }
    }
}

data class SplitCaptureDraftRequest(
    val requestId: String,
    val draftId: String,
    val expectedRevisionNumber: Int,
    val assessmentRequestId: String,
    val sourceAssetId: String,
    val regions: List<NormalizedSourceRegion>,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "Capture split request id must not be blank" }
        require(draftId.isNotBlank()) { "Capture split draft id must not be blank" }
        require(expectedRevisionNumber > 0) { "Capture split revision must be positive" }
        require(assessmentRequestId.isNotBlank()) {
            "Capture split assessment request id must not be blank"
        }
        require(sourceAssetId.isNotBlank()) { "Capture split source asset id must not be blank" }
        require(regions.size in 2..12) { "Capture split must contain two to twelve regions" }
        require(regions.distinct().size == regions.size) { "Capture split regions must be unique" }
        require(regions.all { region ->
            region.left.isFinite() && region.top.isFinite() &&
                region.right.isFinite() && region.bottom.isFinite() &&
                region.left in 0.0..1.0 && region.top in 0.0..1.0 &&
                region.right in 0.0..1.0 && region.bottom in 0.0..1.0 &&
                region.right - region.left >= MIN_SPLIT_REGION_WIDTH &&
                region.bottom - region.top >= MIN_SPLIT_REGION_HEIGHT &&
                (region.right - region.left) * (region.bottom - region.top) >=
                MIN_SPLIT_REGION_AREA
        }) { "Capture split region is invalid" }
        require(regions.indices.none { firstIndex ->
            regions.indices.any { secondIndex ->
                secondIndex > firstIndex &&
                    overlapRatioOfSmaller(
                        regions[firstIndex],
                        regions[secondIndex],
                    ) > MAX_SPLIT_REGION_OVERLAP
            }
        }) { "Capture split regions overlap too much" }
        require(occurredAtEpochMillis >= 0) { "Capture split time must not be negative" }
    }

    private companion object {
        const val MIN_SPLIT_REGION_WIDTH = 0.08
        const val MIN_SPLIT_REGION_HEIGHT = 0.04
        const val MIN_SPLIT_REGION_AREA = 0.006
        const val MAX_SPLIT_REGION_OVERLAP = 0.8

        fun overlapRatioOfSmaller(
            first: NormalizedSourceRegion,
            second: NormalizedSourceRegion,
        ): Double {
            val width = (minOf(first.right, second.right) -
                maxOf(first.left, second.left)).coerceAtLeast(0.0)
            val height = (minOf(first.bottom, second.bottom) -
                maxOf(first.top, second.top)).coerceAtLeast(0.0)
            val intersection = width * height
            val smallerArea = minOf(
                (first.right - first.left) * (first.bottom - first.top),
                (second.right - second.left) * (second.bottom - second.top),
            )
            return if (smallerArea == 0.0) 0.0 else intersection / smallerArea
        }
    }
}

data class CaptureDraftSplitResult(
    val created: Boolean,
    val replacedDraftId: String,
    val splitDrafts: List<CaptureDraftSummary>,
) {
    init {
        require(replacedDraftId.isNotBlank()) { "Replaced split draft id must not be blank" }
        require(splitDrafts.size in 2..12) { "A capture split must return two to twelve drafts" }
        require(splitDrafts.map { it.draftId }.distinct().size == splitDrafts.size) {
            "Capture split result draft ids must be unique"
        }
    }
}

data class AppendCaptureDraftPageRequest(
    val requestId: String,
    val draftId: String,
    val expectedRevisionNumber: Int,
    val expectedPageCount: Int,
    val localUri: String,
    val source: CaptureInputSource,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "Capture page request id must not be blank" }
        require(draftId.isNotBlank()) { "Capture page draft id must not be blank" }
        require(expectedRevisionNumber > 0) { "Capture page revision must be positive" }
        require(expectedPageCount in 1 until MAX_CAPTURE_SOURCE_PAGES) {
            "Capture page count must stay within the source bundle limit"
        }
        require(localUri.isNotBlank()) { "Capture page URI must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Capture page time must not be negative" }
    }

    private companion object {
        const val MAX_CAPTURE_SOURCE_PAGES = 8
    }
}

data class CaptureDraftSummary(
    val draftId: String,
    val sourceAssetId: String,
    val sourceAssetSha256: String,
    val revisionNumber: Int,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val status: String,
    val recognition: CaptureRecognitionSummary = CaptureRecognitionSummary(),
    val sourcePages: List<CaptureSourcePage> = emptyList(),
) {
    init {
        require(SHA_256.matches(sourceAssetSha256)) { "Source asset SHA-256 is invalid" }
        require(sourcePages.isEmpty() || sourcePages.map { it.pageIndex } == sourcePages.indices.toList()) {
            "Capture summary source pages must be ordered and contiguous"
        }
    }

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

data class ConfirmCapturedProblemRequest(
    val draftId: String,
    val workspaceIdentity: CaptureDraftWorkspaceIdentity,
    val problemOrganizationAuthorization: ProblemOrganizationAuthorizationGrant? = null,
) {
    init {
        require(draftId.isNotBlank()) { "Draft id must not be blank" }
        require(workspaceIdentity.draftId == draftId) {
            "Capture confirmation workspace belongs to another draft"
        }
        require(workspaceIdentity.finalConfirmationRequest != null) {
            "Capture confirmation requires a persisted final request identity"
        }
        problemOrganizationAuthorization?.let {
            require(it.sourceDraftId == draftId) {
                "Problem organization authorization belongs to another draft"
            }
        }
    }
}

data class CapturedProblemCommitSummary(
    val draftId: String,
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val errorBookEntryId: String,
    val created: Boolean,
)

data class ConfirmedTutorSession(
    val sessionId: String,
    val draftId: String,
    val draftRevisionNumber: Int,
    val subject: String,
    val title: String,
    val questionDocument: CapturedQuestionDocument,
    val sourceImageUri: String,
    val createdAtEpochMillis: Long,
    val isSaved: Boolean,
    val isEndedWithoutSave: Boolean = false,
    val errorBookEntryId: String?,
) {
    init {
        require(sessionId.isNotBlank()) { "Tutor session id must not be blank" }
        require(draftId.isNotBlank()) { "Tutor session draft id must not be blank" }
        require(draftRevisionNumber > 0) { "Tutor session draft revision must be positive" }
        require(subject.isNotBlank()) { "Tutor session subject must not be blank" }
        require(title.isNotBlank()) { "Tutor session title must not be blank" }
        require(sourceImageUri.isNotBlank()) { "Tutor session source image URI must not be blank" }
        require(createdAtEpochMillis >= 0) { "Tutor session creation time must not be negative" }
        require(CapturedQuestionDocumentValidator.validateForCommit(questionDocument).isEmpty()) {
            "Tutor session question document must be user-confirmed"
        }
        require(!isSaved || !isEndedWithoutSave) {
            "A tutor session cannot be saved and ended without saving"
        }
        require(isSaved == (errorBookEntryId != null)) {
            "Tutor session save state and error-book entry must agree"
        }
    }

    val disposition: TutorSessionDisposition
        get() = when {
            isSaved -> TutorSessionDisposition.SAVED
            isEndedWithoutSave -> TutorSessionDisposition.ENDED_WITHOUT_SAVE
            else -> TutorSessionDisposition.ACTIVE
        }
}

/** Exact canonical image scope available for one independent tutor-visual request. */
data class TutorVisualSourceAssetScope(
    val pageIndex: Int,
    val assetId: String,
    val sha256: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val selectedRegion: NormalizedSourceRegion? = null,
) {
    init {
        require(pageIndex >= 0)
        require(assetId.isNotBlank())
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(byteSize > 0)
        require(width > 0 && height > 0)
    }

    fun toSourceRef() = CaptureSourceAssetRef(
        assetId = assetId,
        sha256 = sha256,
        width = width,
        height = height,
        pageIndex = pageIndex,
        selectedRegion = selectedRegion,
    )

    fun toEgressGrant() = ModelEgressAssetGrant(
        assetId = assetId,
        sha256 = sha256,
        byteSize = byteSize,
        width = width,
        height = height,
        selectedRegion = selectedRegion,
    )
}

enum class TutorSessionDisposition {
    ACTIVE,
    SAVED,
    ENDED_WITHOUT_SAVE,
}

data class SaveTutorSessionRequest(
    val requestId: String,
    val sessionId: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "Tutor-session save request id must not be blank" }
        require(sessionId.isNotBlank()) { "Tutor session id must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Tutor-session save time must not be negative" }
    }
}

data class EndTutorSessionWithoutSaveRequest(
    val sessionId: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(sessionId.isNotBlank()) { "Tutor session id must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Tutor-session end time must not be negative" }
    }
}

data class EndTutorSessionWithoutSaveResult(
    val sessionId: String,
    val draftId: String,
    val endedAtEpochMillis: Long,
    val created: Boolean,
)

/**
 * The application boundary from a private image URI to a recoverable draft. Library confirmation
 * formalizes one immutable error-book entry; tutor confirmation first creates a resumable session.
 */
interface CaptureWorkflowRepository {
    fun observePendingCaptures(): Flow<List<PendingCaptureItem>>

    suspend fun readPendingCapture(draftId: String): ResumableCaptureDraft?

    suspend fun readDraftWorkspace(draftId: String): CaptureDraftWorkspaceSnapshot?

    suspend fun saveDraftWorkspace(
        request: SaveCaptureDraftWorkspaceRequest,
    ): CaptureDraftWorkspaceSnapshot

    suspend fun consumeDraftWorkspace(request: ConsumeCaptureDraftWorkspaceRequest): Boolean

    suspend fun importDraft(request: CaptureDraftImportRequest): CaptureDraftSummary

    suspend fun appendDraftPage(request: AppendCaptureDraftPageRequest): CaptureDraftSummary

    suspend fun replaceDraft(request: ReplaceCaptureDraftRequest): CaptureDraftSummary

    suspend fun splitDraft(request: SplitCaptureDraftRequest): CaptureDraftSplitResult =
        throw UnsupportedOperationException("Capture splitting is not implemented")

    suspend fun confirmForTutoring(
        request: ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession

    suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession?

    suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<TutorVisualSourceAssetScope> = emptyList()

    suspend fun saveTutorSession(
        request: SaveTutorSessionRequest,
    ): CapturedProblemCommitSummary

    suspend fun endTutorSessionWithoutSaving(
        request: EndTutorSessionWithoutSaveRequest,
    ): EndTutorSessionWithoutSaveResult

    suspend fun confirmAndCommit(
        request: ConfirmCapturedProblemRequest,
    ): CapturedProblemCommitSummary
}
