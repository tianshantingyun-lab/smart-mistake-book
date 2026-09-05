package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ModelTaskKind {
    CAPTURE_ASSESS,
    CAPTURE_PARSE,
    PROBLEM_CLASSIFY,
    PROBLEM_RELATE,
    TUTOR_PLAN,
    TUTOR_RESPOND,
    TUTOR_VISUAL_GENERATE,
    TUTOR_VISUAL_REVIEW,
    TUTOR_LOBBY,
    TUTOR_EVALUATE,
    REVIEW_RERANK,
    LEARNING_SUMMARIZE,
    IMAGE_PIPELINE_CLASSIFY,
}

@Serializable
enum class ModelTaskStatus {
    WAITING_FOR_MODEL,
    QUEUED,
    RUNNING,
    STREAMING,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    CANCELLED,
    ;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == PERMANENT_FAILURE || this == CANCELLED

    fun canTransitionTo(next: ModelTaskStatus): Boolean = when (this) {
        WAITING_FOR_MODEL -> next == QUEUED || next == CANCELLED
        QUEUED -> next == RUNNING || next == RETRYABLE_FAILURE || next == PERMANENT_FAILURE ||
            next == CANCELLED
        RUNNING -> next == QUEUED || next == STREAMING || next == SUCCEEDED ||
            next == RETRYABLE_FAILURE || next == PERMANENT_FAILURE || next == CANCELLED
        STREAMING -> next == QUEUED || next == STREAMING || next == SUCCEEDED ||
            next == RETRYABLE_FAILURE || next == PERMANENT_FAILURE || next == CANCELLED
        RETRYABLE_FAILURE -> next == QUEUED || next == CANCELLED
        SUCCEEDED, PERMANENT_FAILURE, CANCELLED -> false
    }
}

/**
 * [ModelTaskSnapshot.attemptCount] records the logical operation's durable dispatch count observed
 * by that envelope when it was created or last reserved. Moving a persisted task back into a
 * locally runnable state does not consume this budget; the operation record remains authoritative.
 */
object ModelTaskRemoteDispatchPolicy {
    const val MAX_DISPATCHES: Int = 3

    fun canSchedule(attemptCount: Int): Boolean {
        require(attemptCount >= 0) { "Model task attempt count must not be negative" }
        return attemptCount < MAX_DISPATCHES
    }
}

/** Hard backstop used by every external model HTTP transport. */
const val MODEL_EXTERNAL_TRANSPORT_REQUEST_LIMIT_BYTES: Long = 36L * 1_024L * 1_024L

@Serializable
enum class ModelTaskStage {
    WAITING,
    PREPARING,
    READING_IMAGE,
    VALIDATING_OUTPUT,
    COMPLETE,
}

@Serializable
enum class ModelFailureCode {
    MODEL_NOT_CONFIGURED,
    EGRESS_AUTHORIZATION_REQUIRED,
    EGRESS_AUTHORIZATION_INVALID,
    PROVIDER_CAPABILITY_MISSING,
    NETWORK_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    RATE_LIMITED,
    TIMEOUT,
    INVALID_RESPONSE,
    PROVIDER_REJECTED_INPUT,
    /** The provider accepted the connection but returned a 5xx service fault. */
    SERVICE_UNAVAILABLE,
    UNKNOWN,
}

@Serializable
data class ProviderCapabilitySnapshot(
    val providerId: String,
    val providerDisplayName: String,
    val modelId: String,
    val supportedTasks: Set<ModelTaskKind>,
    val supportsImageInput: Boolean,
    val supportsStructuredOutput: Boolean,
    val supportsStreaming: Boolean,
    val isDemo: Boolean = false,
    val executionLocation: ModelExecutionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
    val providerConfigurationVersion: String = "unspecified-v1",
) {
    init {
        providerId.requireSafeModelText(
            label = "Provider id",
            maxChars = MAX_PROVIDER_ID_CHARS,
            allowLineBreaks = false,
        )
        providerDisplayName.requireSafeModelText(
            label = "Provider display name",
            maxChars = MAX_PROVIDER_DISPLAY_NAME_CHARS,
            allowLineBreaks = false,
        )
        modelId.requireSafeModelText(
            label = "Provider model id",
            maxChars = MAX_MODEL_VERSION_CHARS,
            allowLineBreaks = false,
        )
        providerConfigurationVersion.requireSafeModelText(
            label = "Provider configuration version",
            maxChars = MAX_MODEL_VERSION_CHARS,
            allowLineBreaks = false,
        )
    }

    fun supports(kind: ModelTaskKind): Boolean = kind in supportedTasks
}

@Serializable
sealed interface ModelTaskInput {
    val kind: ModelTaskKind
    val subjectId: String

    /**
     * True for the image-pipeline rounds (assess/parse/classify) whose photos may
     * egress to the configured image-capable provider under global Settings consent,
     * without a per-photo egress manifest. Computed, never serialized.
     */
    val isCapturePipelineKind: Boolean
        get() = false
}

@Serializable
@SerialName("capture_assessment")
data class CaptureAssessmentInput(
    val draftId: String,
    val sourceAssetId: String,
    val origin: CaptureAssessmentOrigin,
    val imageWidth: Int,
    val imageHeight: Int,
    val followingSourceAssets: List<CaptureSourceAssetRef> = emptyList(),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.CAPTURE_ASSESS

    override val isCapturePipelineKind: Boolean
        get() = true

    override val subjectId: String
        get() = draftId

    init {
        require(draftId.isNotBlank()) { "Capture assessment draft id must not be blank" }
        require(sourceAssetId.isNotBlank()) { "Capture assessment asset id must not be blank" }
        require(imageWidth > 0 && imageHeight > 0) { "Capture assessment dimensions must be positive" }
        require(followingSourceAssets.size < MAX_CAPTURE_SOURCE_ASSETS) {
            "Capture page comparison has too many following pages"
        }
        require(followingSourceAssets.map { it.pageIndex } == (1..followingSourceAssets.size).toList()) {
            "Capture page comparison pages must be ordered and contiguous"
        }
        require(
            followingSourceAssets.map { it.assetId }.toSet().size == followingSourceAssets.size &&
                followingSourceAssets.none { it.assetId == sourceAssetId },
        ) {
            "Capture page comparison assets must be unique"
        }
        require(
            imageWidth.toLong() * imageHeight +
                followingSourceAssets.sumOf { it.width.toLong() * it.height } <=
                MAX_CAPTURE_TOTAL_PIXELS,
        ) {
            "Capture page comparison exceeds the total pixel budget"
        }
    }
}

/**
 * Reads a photographed problem and classifies whether it is figure-bearing
 * or text-only, and for text-only problems extracts the structured content
 * (text + formulas). This is the routing entry of the image pipeline: the
 * multimodal model judges the problem, then the pipeline routes the figure
 * to MCP image-to-image (if present) or the text to the local typesetter.
 */
@Serializable
@SerialName("image_pipeline_classify")
data class ImagePipelineClassifyInput(
    val sourceAssetId: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val subjectIdOverride: String? = null,
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.IMAGE_PIPELINE_CLASSIFY

    override val isCapturePipelineKind: Boolean
        get() = true

    override val subjectId: String
        get() = subjectIdOverride ?: sourceAssetId

    init {
        require(sourceAssetId.isNotBlank()) { "Image pipeline classify asset id must not be blank" }
        require(imageWidth > 0 && imageHeight > 0) {
            "Image pipeline classify dimensions must be positive"
        }
    }
}

@Serializable
@SerialName("capture_parse")
data class CaptureParseInput(
    val draftId: String,
    val origin: CaptureAssessmentOrigin,
    val basisRevisionNumber: Int,
    val sourceAssets: List<CaptureSourceAssetRef>,
    val assessmentRequestId: String,
    val assessmentRequestIds: List<String> = listOf(assessmentRequestId),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.CAPTURE_PARSE

    override val isCapturePipelineKind: Boolean
        get() = true

    override val subjectId: String
        get() = draftId

    init {
        require(draftId.isNotBlank()) { "Capture parse draft id must not be blank" }
        require(basisRevisionNumber > 0) { "Capture parse basis revision must be positive" }
        require(sourceAssets.isNotEmpty()) { "Capture parse requires at least one source asset" }
        require(sourceAssets.size <= MAX_CAPTURE_SOURCE_ASSETS) {
            "Capture parse has too many source assets"
        }
        require(sourceAssets.map(CaptureSourceAssetRef::assetId).distinct().size == sourceAssets.size) {
            "Capture parse source asset ids must be unique"
        }
        require(sourceAssets.map(CaptureSourceAssetRef::pageIndex).distinct().size == sourceAssets.size) {
            "Capture parse source page indexes must be unique"
        }
        require(sourceAssets.map(CaptureSourceAssetRef::pageIndex).sorted() == sourceAssets.indices.toList()) {
            "Capture parse source page indexes must be contiguous from zero"
        }
        require(sourceAssets.sumOf { it.width.toLong() * it.height } <= MAX_CAPTURE_TOTAL_PIXELS) {
            "Capture parse source assets exceed the total pixel budget"
        }
        require(assessmentRequestId.isNotBlank()) {
            "Capture parse assessment request id must not be blank"
        }
        require(assessmentRequestId.length <= ModelTaskRequest.MAX_ID_CHARS) {
            "Capture parse assessment request id exceeds budget"
        }
        require(assessmentRequestIds.size == sourceAssets.size) {
            "Capture parse requires one assessment request for every source page"
        }
        require(assessmentRequestIds.firstOrNull() == assessmentRequestId) {
            "Capture parse primary assessment must belong to page zero"
        }
        require(assessmentRequestIds.distinct().size == assessmentRequestIds.size) {
            "Capture parse assessment request ids must be unique"
        }
        require(assessmentRequestIds.all { it.isNotBlank() && it.length <= ModelTaskRequest.MAX_ID_CHARS }) {
            "Capture parse assessment request id is invalid"
        }
    }
}

@Serializable
data class CaptureSourceAssetRef(
    val assetId: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val pageIndex: Int,
    val selectedRegion: NormalizedSourceRegion? = null,
) {
    init {
        require(assetId.isNotBlank()) { "Capture source asset id must not be blank" }
        require(assetId.length <= ModelTaskRequest.MAX_ID_CHARS) {
            "Capture source asset id exceeds budget"
        }
        require(sha256.length == SHA_256_HEX_CHARS && sha256.all(Char::isLowerHexDigit)) {
            "Capture source asset hash must be a lowercase SHA-256 value"
        }
        require(width in 1..MAX_CAPTURE_SOURCE_DIMENSION) {
            "Capture source asset width is outside the supported range"
        }
        require(height in 1..MAX_CAPTURE_SOURCE_DIMENSION) {
            "Capture source asset height is outside the supported range"
        }
        require(width.toLong() * height <= MAX_CAPTURE_SOURCE_PIXELS) {
            "Capture source asset exceeds the pixel budget"
        }
        require(pageIndex >= 0) { "Capture source asset page index must not be negative" }
        require(selectedRegion == null || selectedRegion.isValidModelRegion()) {
            "Capture source asset selected region is invalid"
        }
    }
}

@Serializable
enum class CaptureAssessmentOrigin {
    TUTOR,
    LIBRARY,
}

@Serializable
data class ModelTaskRequest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val requestId: String,
    val input: ModelTaskInput,
    val occurredAtEpochMillis: Long,
    val egressManifest: ModelEgressManifest? = null,
    /**
     * True when the user has enabled global model-image consent in Settings, so a
     * capture-pipeline round (assess/parse/classify) may egress to the configured
     * image-capable provider without a per-photo manifest. Only meaningful at
     * schemaVersion >= [CAPTURE_CONSENT_SCHEMA_VERSION].
     */
    val captureEgressConsentGranted: Boolean = false,
) {
    init {
        require(schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            "Unsupported model task schema"
        }
        require(schemaVersion >= EGRESS_SCHEMA_VERSION || egressManifest == null) {
            "Legacy model task requests cannot contain an egress manifest"
        }
        require(
            schemaVersion >= CAPTURE_CONSENT_SCHEMA_VERSION || !captureEgressConsentGranted,
        ) { "Legacy model task requests cannot carry capture-consent" }
        require(
            schemaVersion >= TUTOR_STUDENT_CONTEXT_SCHEMA_VERSION ||
                (input as? TutorPlanInput)?.priorCycleStudentMessages.isNullOrEmpty(),
        ) { "Legacy tutor requests cannot contain prior-cycle student messages" }
        require(
            schemaVersion >= CAPTURE_PAGE_RELATION_SCHEMA_VERSION ||
                (input as? CaptureAssessmentInput)?.followingSourceAssets.isNullOrEmpty(),
        ) { "Legacy capture requests cannot compare adjacent pages" }
        require(
            schemaVersion >= TUTOR_VISUAL_SCHEMA_VERSION ||
                input !is TutorVisualGenerateInput && input !is TutorVisualReviewInput,
        ) { "Legacy model task requests cannot contain tutor visual work" }
        require(requestId.isNotBlank()) { "Model task request id must not be blank" }
        require(requestId.length <= MAX_ID_CHARS) { "Model task request id exceeds budget" }
        require(input.subjectId.isNotBlank()) { "Model task subject id must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Model task time must not be negative" }
    }

    companion object {
        const val MIN_SUPPORTED_SCHEMA_VERSION = 1
        const val EGRESS_SCHEMA_VERSION = 2
        const val TUTOR_STUDENT_CONTEXT_SCHEMA_VERSION = 3
        const val CAPTURE_PAGE_RELATION_SCHEMA_VERSION = 4
        const val TUTOR_VISUAL_SCHEMA_VERSION = 5
        const val TUTOR_TOOL_CARRIER_SCHEMA_VERSION = 6
        const val CAPTURE_CONSENT_SCHEMA_VERSION = 7
        const val CURRENT_SCHEMA_VERSION = CAPTURE_CONSENT_SCHEMA_VERSION
        const val MAX_ID_CHARS = 256
    }
}

@Serializable
enum class CaptureAssessmentDecision {
    PASS,
    RECAPTURE,
    NEED_MORE_IMAGE,
    SPLIT,
}

@Serializable
enum class CaptureAssessmentIssueCode {
    MISSING_OPTIONS,
    KEY_TEXT_UNREADABLE,
    GLARE_COVERS_FORMULA,
    OCCLUDED,
    MULTIPLE_QUESTIONS,
}

@Serializable
enum class CaptureAssessmentSeverity {
    BLOCKING,
    REVIEW,
}

@Serializable
enum class CaptureAssessmentAction {
    RECAPTURE,
    ADD_IMAGE,
    CONTINUE_ANYWAY,
}

@Serializable
enum class CapturePageRelation {
    SAME_QUESTION,
    NEXT_QUESTION,
    UNSURE,
}

@Serializable
data class CaptureAssessmentIssue(
    val code: CaptureAssessmentIssueCode,
    val severity: CaptureAssessmentSeverity,
    val region: NormalizedSourceRegion? = null,
    val message: String,
) {
    init {
        message.requireSafeModelText(
            label = "Capture assessment issue message",
            maxChars = MAX_MESSAGE_CHARS,
            allowLineBreaks = true,
        )
        require(region == null || region.isValidModelRegion()) {
            "Capture assessment issue region is invalid"
        }
    }
}

@Serializable
data class CaptureAssessment(
    val decision: CaptureAssessmentDecision,
    val issues: List<CaptureAssessmentIssue>,
    val suggestedActions: List<CaptureAssessmentAction>,
    val modelVersion: String,
    val questionRegions: List<NormalizedSourceRegion> = emptyList(),
    val followingPageRelations: List<CapturePageRelation> = emptyList(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    init {
        require(schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            "Unsupported capture assessment schema"
        }
        require(issues.size <= MAX_ISSUES) { "Capture assessment has too many issues" }
        require(suggestedActions.size <= MAX_ACTIONS) {
            "Capture assessment has too many actions"
        }
        require(suggestedActions.distinct().size == suggestedActions.size) {
            "Capture assessment actions must be unique"
        }
        modelVersion.requireSafeModelText(
            label = "Capture assessment model version",
            maxChars = MAX_MODEL_VERSION_CHARS,
            allowLineBreaks = false,
        )
        require(
            decision == CaptureAssessmentDecision.PASS || issues.isNotEmpty(),
        ) { "A blocked capture assessment must explain at least one issue" }
        require(
            decision != CaptureAssessmentDecision.PASS ||
                issues.none { it.severity == CaptureAssessmentSeverity.BLOCKING },
        ) { "A passing capture assessment cannot contain blocking issues" }
        require(
            decision != CaptureAssessmentDecision.PASS ||
                CaptureAssessmentAction.RECAPTURE !in suggestedActions &&
                CaptureAssessmentAction.ADD_IMAGE !in suggestedActions,
        ) { "A passing capture assessment cannot request another image" }
        require(
            decision != CaptureAssessmentDecision.RECAPTURE ||
                CaptureAssessmentAction.RECAPTURE in suggestedActions,
        ) { "A recapture decision must suggest recapturing" }
        require(
            decision != CaptureAssessmentDecision.NEED_MORE_IMAGE ||
                CaptureAssessmentAction.ADD_IMAGE in suggestedActions,
        ) { "A missing-image decision must suggest adding an image" }
        val multipleQuestionIssues = issues.filter {
            it.code == CaptureAssessmentIssueCode.MULTIPLE_QUESTIONS
        }
        if (schemaVersion >= MULTIPLE_QUESTION_SPLIT_SCHEMA_VERSION) {
            require(multipleQuestionIssues.all { it.severity == CaptureAssessmentSeverity.BLOCKING }) {
                "Multiple independent questions must be treated as blocking"
            }
            require(
                multipleQuestionIssues.isEmpty() || decision == CaptureAssessmentDecision.SPLIT,
            ) { "Multiple independent questions must enter the local split flow" }
            require(
                decision != CaptureAssessmentDecision.SPLIT || multipleQuestionIssues.isNotEmpty(),
            ) { "A split decision must identify multiple independent questions" }
            require(
                if (decision == CaptureAssessmentDecision.SPLIT) {
                    questionRegions.size in MIN_SPLIT_REGION_COUNT..MAX_SPLIT_REGION_COUNT
                } else {
                    questionRegions.isEmpty()
                },
            ) { "Only a split decision may contain two to twelve question regions" }
            require(questionRegions.distinct().size == questionRegions.size) {
                "Split question regions must be unique"
            }
            require(questionRegions.all(NormalizedSourceRegion::isUsableQuestionRegion)) {
                "Split question regions are too small or invalid"
            }
            questionRegions.forEachIndexed { index, region ->
                questionRegions.drop(index + 1).forEach { other ->
                    require(region.overlapRatioOfSmaller(other) <= MAX_SPLIT_REGION_OVERLAP_RATIO) {
                        "Split question regions overlap too heavily"
                    }
                }
            }
        } else if (schemaVersion >= MULTIPLE_QUESTION_GATE_SCHEMA_VERSION) {
            require(decision != CaptureAssessmentDecision.SPLIT && questionRegions.isEmpty()) {
                "Legacy capture assessments cannot request local splitting"
            }
            require(multipleQuestionIssues.all { it.severity == CaptureAssessmentSeverity.BLOCKING }) {
                "Multiple independent questions must be treated as blocking"
            }
            require(
                multipleQuestionIssues.isEmpty() || decision == CaptureAssessmentDecision.RECAPTURE,
            ) { "A legacy single-question capture cannot continue with multiple questions" }
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
        private const val MIN_SUPPORTED_SCHEMA_VERSION = 1
        private const val MULTIPLE_QUESTION_GATE_SCHEMA_VERSION = 2
        private const val MULTIPLE_QUESTION_SPLIT_SCHEMA_VERSION = 3
        const val MIN_SPLIT_REGION_COUNT = 2
        const val MAX_SPLIT_REGION_COUNT = 12
        private const val MAX_SPLIT_REGION_OVERLAP_RATIO = 0.8
        const val MAX_ISSUES = 12
        const val MAX_ACTIONS = 3
    }
}

private fun NormalizedSourceRegion.isUsableQuestionRegion(): Boolean {
    if (!isValidModelRegion()) return false
    val width = right - left
    val height = bottom - top
    return width >= 0.08 && height >= 0.04 && width * height >= 0.006
}

private fun NormalizedSourceRegion.overlapRatioOfSmaller(other: NormalizedSourceRegion): Double {
    val overlapWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0.0)
    val overlapHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0.0)
    val overlap = overlapWidth * overlapHeight
    val smallerArea = minOf(
        (right - left) * (bottom - top),
        (other.right - other.left) * (other.bottom - other.top),
    )
    return if (smallerArea == 0.0) 1.0 else overlap / smallerArea
}

@Serializable
sealed interface ModelTaskOutput

/**
 * Whether the photographed problem contains a figure (which routes to MCP
 * image-to-image) or is text-only (which routes to the local typesetter).
 */
@Serializable
enum class ImagePipelineProblemKind {
    WITH_FIGURE,
    TEXT_ONLY,
}

/**
 * Structured content extracted for a text-only problem: the normalized text
 * and its formulas (LaTeX). For WITH_FIGURE problems the model does not need
 * to transcribe the figure; the original photo is passed to MCP.
 */
@Serializable
@SerialName("image_pipeline_classify_output")
data class ImagePipelineClassifyOutput(
    val problemKind: ImagePipelineProblemKind,
    val textMarkdown: String = "",
    val formulas: List<String> = emptyList(),
    val modelVersion: String = "",
) : ModelTaskOutput

/**
 * The clean, handwriting-free problem sheet produced by the image pipeline.
 *
 * For WITH_FIGURE problems [cleanImageBytes] holds the MCP-redrawn figure and
 * [cleanImageMimeType] its type; for TEXT_ONLY problems [textMarkdown] and
 * [formulas] carry the structured content the local typesetter lays out. Only
 * one branch is populated — the pipeline routes to exactly one producer.
 */
@Serializable
data class ImageCleanSheet(
    val problemKind: ImagePipelineProblemKind,
    val cleanImageBytes: ByteArray? = null,
    val cleanImageMimeType: String? = null,
    val textMarkdown: String = "",
    val formulas: List<String> = emptyList(),
    val modelVersion: String = "",
) {
    init {
        when (problemKind) {
            ImagePipelineProblemKind.WITH_FIGURE ->
                require(cleanImageBytes != null && !cleanImageMimeType.isNullOrBlank()) {
                    "A WITH_FIGURE clean sheet must carry the redrawn image"
                }
            ImagePipelineProblemKind.TEXT_ONLY ->
                require(textMarkdown.isNotBlank()) {
                    "A TEXT_ONLY clean sheet must carry the structured text"
                }
        }
    }
}

@Serializable
@SerialName("capture_assessment_output")
data class CaptureAssessmentOutput(
    val assessment: CaptureAssessment,
) : ModelTaskOutput

@Serializable
@SerialName("capture_parse_output")
data class CaptureParseOutput(
    val capturedDocument: CapturedQuestionDocument,
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        modelVersion.requireSafeModelText(
            label = "Capture parse model version",
            maxChars = MAX_MODEL_VERSION_CHARS,
            allowLineBreaks = false,
        )
    }
}

enum class ModelTaskCompletionIssueCode {
    REQUEST_OUTPUT_TYPE_MISMATCH,
    INVALID_CAPTURE_DOCUMENT,
    DRAFT_DOCUMENT_MISMATCH,
    SOURCE_ASSET_MISMATCH,
    INVALID_CAPTURE_PROVENANCE,
    MODEL_CANNOT_CONFIRM_USER_REVIEW,
    MODEL_CANNOT_APPLY_LOCAL_POLICY,
    MISSING_PRODUCER_VERSION,
    PRODUCER_VERSION_MISMATCH,
    MISSING_SOURCE_REGION,
    SOURCE_REGION_OUTSIDE_REQUEST,
    UNRESOLVED_WRITING_LAYER,
    PRESELECTED_ANSWER,
    PAGE_RELATION_MISMATCH,
    TUTOR_CONTEXT_MISMATCH,
    TUTOR_INTENT_BOUNDARY_VIOLATION,
    MODEL_CLAIMED_UNDISCLOSED_EVIDENCE,
    MODEL_TARGETED_MASTERED_EVIDENCE,
    MODEL_ASSIGNED_TRUSTED_KNOWLEDGE_IDS,
    ORGANIZATION_CONTEXT_MISMATCH,
    ORGANIZATION_ATOMIC_DECOMPOSITION_REQUIRED,
    ORGANIZATION_UNKNOWN_RELATION_TARGET,
    ORGANIZATION_UNKNOWN_KNOWLEDGE_PREREQUISITE,
}

data class ModelTaskCompletionIssue(
    val code: ModelTaskCompletionIssueCode,
    val blockId: String? = null,
)

/**
 * Trust boundary between a model adapter and durable task state.
 *
 * Adapters may deserialize untrusted output, but repositories must call [requireValid] before
 * recording a successful task. In particular, a model can only produce review candidates; it
 * cannot claim that a student confirmed its transcription.
 */
object ModelTaskCompletionValidator {
    fun validate(
        request: ModelTaskRequest,
        output: ModelTaskOutput,
    ): List<ModelTaskCompletionIssue> = when (val input = request.input) {
        is TutorDebriefInput -> if (output is TutorDebriefOutput && output.sessionId == input.sessionId) {
            emptyList()
        } else {
            listOf(typeMismatch())
        }
        is CaptureAssessmentInput -> if (output is CaptureAssessmentOutput) {
            if (
                input.followingSourceAssets.size ==
                output.assessment.followingPageRelations.size
            ) {
                emptyList()
            } else {
                listOf(
                    ModelTaskCompletionIssue(
                        ModelTaskCompletionIssueCode.PAGE_RELATION_MISMATCH,
                    ),
                )
            }
        } else {
            listOf(typeMismatch())
        }
        is ImagePipelineClassifyInput -> if (output is ImagePipelineClassifyOutput) {
            emptyList()
        } else {
            listOf(typeMismatch())
        }
        is CaptureParseInput -> if (output is CaptureParseOutput) {
            validateCaptureParse(input, output)
        } else {
            listOf(typeMismatch())
        }
        is TutorPlanInput -> if (output is TutorPlanOutput) {
            validateTutorPlan(input, output)
        } else {
            listOf(typeMismatch())
        }
        is TutorRespondInput -> if (output is TutorRespondOutput) {
            validateTutorRespond(input, output)
        } else {
            listOf(typeMismatch())
        }
        is TutorVisualGenerateInput -> if (output is TutorVisualGenerateOutput) {
            validateTutorVisualGenerate(input, output)
        } else {
            listOf(typeMismatch())
        }
        is TutorVisualReviewInput -> if (output is TutorVisualReviewOutput) {
            validateTutorVisualReview(input, output)
        } else {
            listOf(typeMismatch())
        }
        is TutorLobbyInput -> if (output is TutorLobbyOutput) {
            validateTutorLobby(input, output)
        } else {
            listOf(typeMismatch())
        }
        is ProblemOrganizationInput -> if (output is ProblemOrganizationOutput) {
            validateProblemOrganization(input, output)
        } else {
            listOf(typeMismatch())
        }
    }

    fun requireValid(request: ModelTaskRequest, output: ModelTaskOutput) {
        val issues = validate(request, output)
        require(issues.isEmpty()) {
            "Invalid model task completion: ${issues.joinToString { issue -> issue.code.name }}"
        }
    }

    private fun validateCaptureParse(
        input: CaptureParseInput,
        output: CaptureParseOutput,
    ): List<ModelTaskCompletionIssue> = buildList {
        val expectedSources = input.sourceAssets.associateBy(CaptureSourceAssetRef::assetId)
        if (output.capturedDocument.document.id != "document-${input.draftId}") {
            add(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.DRAFT_DOCUMENT_MISMATCH))
        }
        CapturedQuestionDocumentValidator.validateDraft(output.capturedDocument).forEach { issue ->
            add(
                ModelTaskCompletionIssue(
                    code = ModelTaskCompletionIssueCode.INVALID_CAPTURE_DOCUMENT,
                    blockId = issue.blockId,
                ),
            )
        }
        output.capturedDocument.blockEvidence.forEach { evidence ->
            val source = expectedSources[evidence.sourceAssetId]
            if (source == null) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.SOURCE_ASSET_MISMATCH,
                        blockId = evidence.blockId,
                    ),
                )
            }
            val sourceRegion = evidence.sourceRegion
            if (sourceRegion == null) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.MISSING_SOURCE_REGION,
                        blockId = evidence.blockId,
                    ),
                )
            } else if (
                source?.selectedRegion != null &&
                !source.selectedRegion.contains(sourceRegion)
            ) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.SOURCE_REGION_OUTSIDE_REQUEST,
                        blockId = evidence.blockId,
                    ),
                )
            }
            if (evidence.writingLayer == WritingLayer.UNKNOWN) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.UNRESOLVED_WRITING_LAYER,
                        blockId = evidence.blockId,
                    ),
                )
            }
            if (evidence.provenance != QuestionBlockProvenance.MODEL_DOCUMENT_PARSE) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.INVALID_CAPTURE_PROVENANCE,
                        blockId = evidence.blockId,
                    ),
                )
            }
            if (evidence.reviewStatus == QuestionBlockReviewStatus.USER_CONFIRMED) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.MODEL_CANNOT_CONFIRM_USER_REVIEW,
                        blockId = evidence.blockId,
                    ),
                )
            }
            if (evidence.reviewStatus == QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.MODEL_CANNOT_APPLY_LOCAL_POLICY,
                        blockId = evidence.blockId,
                    ),
                )
            }
            if (evidence.producerVersion.isNullOrBlank()) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.MISSING_PRODUCER_VERSION,
                        blockId = evidence.blockId,
                    ),
                )
            } else if (evidence.producerVersion != output.modelVersion) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.PRODUCER_VERSION_MISMATCH,
                        blockId = evidence.blockId,
                    ),
                )
            }
        }
        output.capturedDocument.document.blocks
            .filterIsInstance<ContentBlock.ChoiceGroup>()
            .filter { it.selectedChoiceId != null }
            .forEach { block ->
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.PRESELECTED_ANSWER,
                        blockId = block.id,
                    ),
                )
            }
    }.distinct()

    private fun typeMismatch() = ModelTaskCompletionIssue(
        ModelTaskCompletionIssueCode.REQUEST_OUTPUT_TYPE_MISMATCH,
    )

    private fun validateTutorPlan(
        input: TutorPlanInput,
        output: TutorPlanOutput,
    ): List<ModelTaskCompletionIssue> = buildList {
        if (
            output.sessionId != input.sessionId ||
            output.draftRevisionNumber != input.draftRevisionNumber ||
            output.questionDocumentId != input.questionDocument.id ||
            output.cycleOrdinal != input.cycleOrdinal ||
            output.turnOrdinal != input.turnOrdinal
        ) {
            add(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH))
        }
        val disclosedLabels = input.relevantLearningEvidence
            .mapTo(mutableSetOf(), TutorKnowledgeEvidence::displayName)
        if (output.plan.targetedEvidenceLabels.any { it !in disclosedLabels }) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.MODEL_CLAIMED_UNDISCLOSED_EVIDENCE,
                ),
            )
        }
        val masteredLabels = input.relevantLearningEvidence
            .filter { it.level == TutorEvidenceLevel.MASTERED }
            .mapTo(mutableSetOf(), TutorKnowledgeEvidence::displayName)
        if (output.plan.targetedEvidenceLabels.any { it in masteredLabels }) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.MODEL_TARGETED_MASTERED_EVIDENCE,
                ),
            )
        }
        if (output.plan.diagnosticItem?.knowledgeNodeIds?.isNotEmpty() == true) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.MODEL_ASSIGNED_TRUSTED_KNOWLEDGE_IDS,
                ),
            )
        }
    }

    private fun validateTutorRespond(
        input: TutorRespondInput,
        output: TutorRespondOutput,
    ): List<ModelTaskCompletionIssue> = buildList {
        if (
            output.sessionId != input.sessionId ||
            output.draftRevisionNumber != input.draftRevisionNumber ||
            output.questionDocumentId != input.questionDocument.id ||
            output.responseOrdinal != input.responseOrdinal ||
            output.cycleOrdinal != input.cycleOrdinal ||
            output.turnOrdinal != input.turnOrdinal
        ) {
            add(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH))
        }
        if (
            (output.solutionRevealed && !input.studentAuthorizedSolutionRequest()) ||
            (
                output.intentDecision.intent != TutorMessageIntent.CURRENT_QUESTION_HELP &&
                    (
                        output.solutionRevealed ||
                            output.visualScene != null ||
                            output.visualRequest != null ||
                            output.suggestedMoves.isNotEmpty()
                        )
                )
        ) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION,
                ),
            )
        }
    }

    private fun validateTutorVisualGenerate(
        input: TutorVisualGenerateInput,
        output: TutorVisualGenerateOutput,
    ): List<ModelTaskCompletionIssue> =
        if (
            output.sessionId == input.sessionId &&
            output.draftRevisionNumber == input.draftRevisionNumber &&
            output.questionDocumentId == input.questionDocument.id &&
            output.anchor == input.anchor
        ) {
            emptyList()
        } else {
            listOf(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH))
        }

    private fun validateTutorVisualReview(
        input: TutorVisualReviewInput,
        output: TutorVisualReviewOutput,
    ): List<ModelTaskCompletionIssue> =
        if (
            output.sessionId == input.sessionId &&
            output.draftRevisionNumber == input.draftRevisionNumber &&
            output.questionDocumentId == input.questionDocument.id &&
            output.anchor == input.anchor
        ) {
            emptyList()
        } else {
            listOf(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH))
        }

    private fun validateTutorLobby(
        input: TutorLobbyInput,
        output: TutorLobbyOutput,
    ): List<ModelTaskCompletionIssue> = buildList {
        if (
            output.conversationId != input.conversationId ||
            output.messageOrdinal != input.messageOrdinal
        ) {
            add(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH))
        }
        if (
            output.intentDecision.requestedLocalCapability !in
            TutorLobbyOutput.ALLOWED_LOCAL_CAPABILITIES
        ) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION,
                ),
            )
        }
    }

    private fun validateProblemOrganization(
        input: ProblemOrganizationInput,
        output: ProblemOrganizationOutput,
    ): List<ModelTaskCompletionIssue> = buildList {
        if (
            output.problemId != input.problemId ||
            output.problemRevisionId != input.problemRevisionId ||
            output.practiceUnitId != input.practiceUnitId
        ) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.ORGANIZATION_CONTEXT_MISMATCH,
                ),
            )
        }
        if (output.plan.schemaVersion < ProblemOrganizationPlan.SCHEMA_VERSION) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.ORGANIZATION_ATOMIC_DECOMPOSITION_REQUIRED,
                ),
            )
        }
        val disclosedLabels = input.relevantLearningEvidence
            .mapTo(mutableSetOf(), TutorKnowledgeEvidence::displayName)
        if (output.plan.targetedEvidenceLabels.any { it !in disclosedLabels }) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.MODEL_CLAIMED_UNDISCLOSED_EVIDENCE,
                ),
            )
        }
        val contextById = input.knowledgeBaseNodes.associateBy(
            KnowledgeBaseNodeContext::knowledgeNodeId,
        )
        val matchedNodeIdByReference = output.plan.atomicKnowledge.associate { atom ->
            atom.referenceId to atom.matchedKnowledgeNodeId
        }
        val hasInventedPrerequisite = output.plan.atomicKnowledge.any { dependent ->
            val allowed = dependent.matchedKnowledgeNodeId
                ?.let(contextById::get)
                ?.prerequisiteKnowledgeNodeIds
                .orEmpty()
                .toSet()
            dependent.prerequisiteReferenceIds.any { prerequisiteReference ->
                matchedNodeIdByReference[prerequisiteReference] !in allowed
            }
        }
        if (hasInventedPrerequisite) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.ORGANIZATION_UNKNOWN_KNOWLEDGE_PREREQUISITE,
                ),
            )
        }
        // Relations are optional enrichment. Their target allowlist and confidence are evaluated
        // independently by the local acceptance policy so a bad relation cannot discard valid
        // chapter/knowledge classifications from the same response.
    }
}

@Serializable
data class ModelTaskFailure(
    val code: ModelFailureCode,
    val message: String,
    val retryable: Boolean,
) {
    init {
        message.requireSafeModelText(
            label = "Model task failure message",
            maxChars = MAX_MESSAGE_CHARS,
            allowLineBreaks = true,
        )
    }

}

data class ModelTaskSnapshot(
    val taskId: String,
    val request: ModelTaskRequest,
    val requestFingerprint: String,
    val status: ModelTaskStatus,
    val stateVersion: Long,
    val stage: ModelTaskStage,
    val userMessage: String,
    val attemptCount: Int,
    val provider: ProviderCapabilitySnapshot? = null,
    val output: ModelTaskOutput? = null,
    val failure: ModelTaskFailure? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(taskId.isNotBlank()) { "Model task id must not be blank" }
        require(requestFingerprint.length == SHA_256_HEX_CHARS) {
            "Model task request fingerprint must be SHA-256"
        }
        require(requestFingerprint == ModelTaskFingerprint.of(request)) {
            "Model task request fingerprint does not match its request"
        }
        require(stateVersion >= 0) { "Model task state version must not be negative" }
        require(userMessage.length <= MAX_MESSAGE_CHARS) { "Model task message exceeds budget" }
        require(userMessage.none { it.isForbiddenModelTextCharacter(allowLineBreaks = true) }) {
            "Model task message contains unsafe control characters"
        }
        require(attemptCount >= 0) { "Model task attempt count must not be negative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Model task update time must not precede creation"
        }
        require(status != ModelTaskStatus.SUCCEEDED || output != null) {
            "A successful model task must have output"
        }
        if (status == ModelTaskStatus.SUCCEEDED && output != null) {
            ModelTaskCompletionValidator.requireValid(request, output)
        }
        require(
            status != ModelTaskStatus.RETRYABLE_FAILURE &&
                status != ModelTaskStatus.PERMANENT_FAILURE || failure != null,
        ) { "A failed model task must have failure details" }
    }
}

sealed interface ModelGatewayEvent {
    data class Started(val provider: ProviderCapabilitySnapshot) : ModelGatewayEvent

    data class Progress(
        val stage: ModelTaskStage,
        val userMessage: String,
    ) : ModelGatewayEvent {
        init {
            userMessage.requireSafeModelText(
                label = "Model progress message",
                maxChars = MAX_MESSAGE_CHARS,
                allowLineBreaks = true,
            )
        }
    }

    data class Completed(val output: ModelTaskOutput) : ModelGatewayEvent

    data class Failed(val failure: ModelTaskFailure) : ModelGatewayEvent
}

object ModelTaskCodec {
    const val MAX_ENCODED_CHARS = 512_000

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encodeRequest(value: ModelTaskRequest): String =
        json.encodeToString(ModelTaskRequest.serializer(), value).bounded()

    fun decodeRequest(value: String): ModelTaskRequest =
        json.decodeFromString(ModelTaskRequest.serializer(), value.bounded())

    fun encodeOutput(value: ModelTaskOutput): String =
        json.encodeToString(ModelTaskOutput.serializer(), value).bounded()

    fun decodeOutput(value: String): ModelTaskOutput =
        json.decodeFromString(ModelTaskOutput.serializer(), value.bounded())

    fun encodeProvider(value: ProviderCapabilitySnapshot): String =
        json.encodeToString(ProviderCapabilitySnapshot.serializer(), value).bounded()

    fun decodeProvider(value: String): ProviderCapabilitySnapshot =
        json.decodeFromString(ProviderCapabilitySnapshot.serializer(), value.bounded())

    private fun String.bounded(): String = also {
        require(length <= MAX_ENCODED_CHARS) { "Model task snapshot exceeds budget" }
    }
}

object ModelTaskFingerprint {
    fun of(request: ModelTaskRequest): String = MessageDigest.getInstance("SHA-256")
        .digest(request.fingerprintPayload().toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * Stable identity for one semantic model operation across transport envelopes.
 *
 * A request id, consent receipt, provider choice, configuration version, scheduling time, or
 * retry timestamp may legitimately change when the student explicitly resumes an operation. None
 * of those changes creates a fresh remote-dispatch budget. Only the immutable typed task input
 * participates in this fingerprint.
 */
object ModelTaskLogicalOperationFingerprint {
    fun of(request: ModelTaskRequest): String = of(request.input)

    fun of(input: ModelTaskInput): String = MessageDigest.getInstance("SHA-256")
        .digest(operationPayload(input).toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun operationPayload(input: ModelTaskInput): String =
        buildString {
            append(input.kind.name)
            append('\n')
            append(
                logicalOperationJson.encodeToString(ModelTaskInput.serializer(), input)
                    .withoutEmptyPageComparison(input)
                    .withoutEmptyToolCarrier(input),
            )
        }
}

private val logicalOperationJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

private val legacyFingerprintJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

@Serializable
private data class LegacyModelTaskRequest(
    val schemaVersion: Int,
    val requestId: String,
    val input: ModelTaskInput,
    val occurredAtEpochMillis: Long,
)

private fun ModelTaskRequest.fingerprintPayload(): String =
    if (schemaVersion == ModelTaskRequest.MIN_SUPPORTED_SCHEMA_VERSION) {
        legacyFingerprintJson.encodeToString(
            LegacyModelTaskRequest.serializer(),
            LegacyModelTaskRequest(schemaVersion, requestId, input, occurredAtEpochMillis),
        )
            .withoutLegacyTutorStudentContext(input)
            .withoutEmptyPageComparison(input)
            .withoutEmptyToolCarrier(input)
    } else {
        ModelTaskCodec.encodeRequest(this).let { encoded ->
            encoded
                .let {
                    if (schemaVersion < ModelTaskRequest.TUTOR_STUDENT_CONTEXT_SCHEMA_VERSION) {
                        it.withoutLegacyTutorStudentContext(input)
                    } else {
                        it
                    }
                }
                .let {
                    if (schemaVersion < ModelTaskRequest.CAPTURE_PAGE_RELATION_SCHEMA_VERSION) {
                        it.withoutEmptyPageComparison(input)
                    } else {
                        it
                    }
                }
                .let {
                    if (schemaVersion < ModelTaskRequest.TUTOR_TOOL_CARRIER_SCHEMA_VERSION) {
                        it.withoutEmptyToolCarrier(input)
                    } else {
                        it
                    }
                }
                .let {
                    if (schemaVersion < ModelTaskRequest.CAPTURE_CONSENT_SCHEMA_VERSION) {
                        it.withoutCaptureConsent()
                    } else {
                        it
                    }
                }
        }
    }

private fun String.withoutLegacyTutorStudentContext(input: ModelTaskInput): String =
    if (input is TutorPlanInput) {
        replace(",\"priorCycleStudentMessages\":[]", "")
    } else {
        this
    }

private fun String.withoutEmptyPageComparison(input: ModelTaskInput): String =
    if (input is CaptureAssessmentInput && input.followingSourceAssets.isEmpty()) {
        replace(",\"followingSourceAssets\":[]", "")
    } else {
        this
    }

/**
 * 去掉工具环空载体键（spec 2026-09-02-tool-loop-wiring §3.1 指纹平移）。
 *
 * schemaVersion 5 的编码器不知道 toolDeclarations/toolRoundResults 字段——旧 v5 行存的是
 * 不含这两键的编码。本 helper 只在 schemaVersion < 6 的 fingerprint 路径调用（request 级），
 * 及逻辑操作指纹的无 schema 路径（抹平空载体键，使同一逻辑输入跨版本哈希一致）。
 *
 * 注意 studentImageAssetRefs 不属于本 helper：它在 schemaVersion 5 期（151b1e3）已存在，
 * 当前 main 的 v5 行已含该空键，strip 会破坏其读回一致性。
 */
private fun String.withoutEmptyToolCarrier(input: ModelTaskInput): String =
    if (input is TutorLobbyInput || input is TutorRespondInput) {
        replace(",\"toolDeclarations\":[]", "")
            .replace(",\"toolRoundResults\":[]", "")
    } else {
        this
    }

/**
 * 去掉 capture-consent 空键（schema 6→7 指纹平移）。encodeDefaults=true 使 v7 编码比 v6
 * 多一个 "captureEgressConsentGranted":false 空键；旧 v6 行没有该键，故 schemaVersion<7 的
 * fingerprint 路径需 strip 它，保证旧行读回时重算指纹一致（同 withoutEmptyToolCarrier 先例）。
 */
private fun String.withoutCaptureConsent(): String =
    replace(",\"captureEgressConsentGranted\":false", "")

internal fun NormalizedSourceRegion.isValidModelRegion(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0 &&
        left < right && top < bottom

private fun NormalizedSourceRegion.contains(other: NormalizedSourceRegion): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

internal fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

internal fun String.requireSafeModelText(
    label: String,
    maxChars: Int,
    allowLineBreaks: Boolean,
) {
    require(isNotBlank()) { "$label must not be blank" }
    require(length <= maxChars) { "$label exceeds budget" }
    require(none { it.isForbiddenModelTextCharacter(allowLineBreaks) }) {
        "$label contains unsafe control characters"
    }
}

private fun Char.isForbiddenModelTextCharacter(allowLineBreaks: Boolean): Boolean {
    val allowedControl = allowLineBreaks && (this == '\n' || this == '\r' || this == '\t')
    return (isISOControl() && !allowedControl) ||
        this == '\u061C' ||
        this == '\u200E' ||
        this == '\u200F' ||
        this in '\u202A'..'\u202E' ||
        this in '\u2066'..'\u2069'
}

internal const val MAX_CAPTURE_SOURCE_DIMENSION = 20_000
internal const val MAX_CAPTURE_SOURCE_PIXELS = 100_000_000L
private const val MAX_CAPTURE_TOTAL_PIXELS = 160_000_000L
internal const val MAX_CAPTURE_SOURCE_ASSETS = 8
private const val MAX_MESSAGE_CHARS = 500
internal const val MAX_MODEL_VERSION_CHARS = 256
internal const val MAX_PROVIDER_ID_CHARS = 128
private const val MAX_PROVIDER_DISPLAY_NAME_CHARS = 128
internal const val SHA_256_HEX_CHARS = 64
