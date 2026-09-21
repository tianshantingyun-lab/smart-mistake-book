package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
    KNOWLEDGE_QUIZ,
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
    const val MAX_DISPATCHES: Int = 6

    fun canSchedule(attemptCount: Int): Boolean {
        require(attemptCount >= 0) { "Model task attempt count must not be negative" }
        return attemptCount < MAX_DISPATCHES
    }
}

/** Hard backstop used by every external model HTTP transport. */
const val MODEL_EXTERNAL_TRANSPORT_REQUEST_LIMIT_BYTES: Long = 36L * 1_024L * 1_024L

/**
 * Upper bound for a model task's user-facing status message, which the streaming path reuses as
 * the running reply prefix shown while the answer is still arriving. The gateway must truncate
 * its prefix to this budget: real tutor answers pass it quickly, and an unbounded prefix used to
 * abort the entire task on the database contract that enforces this bound.
 */
const val MODEL_TASK_STATUS_MESSAGE_MAX_CHARS: Int = 500

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
    /**
     * Whether the provider endpoint accepts native OpenAI `tools` requests
     * (Route A tool-loop wire). Probed separately from structured output —
     * a json_object endpoint is not necessarily tools-capable. Defaults to
     * false; Route A stays off until a probe proves tools support.
     */
    val supportsFunctionCalling: Boolean = false,
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
     * True for rounds whose on-device content (photos or the tutor question/visual)
     * may egress to the configured provider under global Settings consent, without a
     * per-item egress manifest. Deliberately false for TUTOR_LOBBY (its own bounded
     * disclosure route) and the confirmed-document organization/summarize routes.
     * Computed, never serialized.
     */
    val isAgentConsentEligible: Boolean
        get() = false

    /**
     * True when the input discloses image bytes (a source photo, page, or region
     * crop) that require an image-capable provider. Distinct from
     * [isAgentConsentEligible]: eligibility says a round may egress under global
     * consent, image-bearing says it needs image capability. A text-only PLAN/RESPOND
     * is eligible but not image-bearing. Computed, never serialized.
     */
    val requestsImageBytes: Boolean
        get() = false
}

@Serializable
@SerialName("capture_assessment")
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class CaptureAssessmentInput(
    val draftId: String,
    val sourceAssetId: String,
    val origin: CaptureAssessmentOrigin,
    val imageWidth: Int,
    val imageHeight: Int,
    val followingSourceAssets: List<CaptureSourceAssetRef> = emptyList(),
    /** 简短指向性说明（如"只要第2、3题"），只界定录入范围，不改其他规则。
     *  NEVER 编码默认值：null 时必须省略字段，保住落库请求的指纹形状稳定。 */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val userHint: String? = null,
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.CAPTURE_ASSESS

    override val isAgentConsentEligible: Boolean
        get() = true

    override val requestsImageBytes: Boolean
        get() = true

    override val subjectId: String
        get() = draftId

    init {
        require(draftId.isNotBlank()) { "Capture assessment draft id must not be blank" }
        require(sourceAssetId.isNotBlank()) { "Capture assessment asset id must not be blank" }
        require(imageWidth > 0 && imageHeight > 0) { "Capture assessment dimensions must be positive" }
        require(userHint == null || userHint.length <= MAX_CAPTURE_USER_HINT_CHARS) {
            "Capture user hint must stay within $MAX_CAPTURE_USER_HINT_CHARS characters"
        }
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

    override val isAgentConsentEligible: Boolean
        get() = true

    override val requestsImageBytes: Boolean
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

    override val isAgentConsentEligible: Boolean
        get() = true

    override val requestsImageBytes: Boolean
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
     * True when the user has enabled global agent-model consent in Settings, so an
     * agent-eligible round (capture assess/parse/classify or tutor plan/respond/visual)
     * may egress to the configured provider without a per-item egress manifest. Only
     * meaningful at schemaVersion >= [AGENT_CONSENT_SCHEMA_VERSION].
     */
    val agentConsentGranted: Boolean = false,
) {
    init {
        require(schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            "Unsupported model task schema"
        }
        require(schemaVersion >= EGRESS_SCHEMA_VERSION || egressManifest == null) {
            "Legacy model task requests cannot contain an egress manifest"
        }
        require(
            schemaVersion >= CONSENT_INTRODUCED_SCHEMA_VERSION || !agentConsentGranted,
        ) { "Legacy model task requests cannot carry agent-consent" }
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
        require(
            schemaVersion >= TUTOR_ROUND_BINDING_SCHEMA_VERSION ||
                (input as? TutorRespondInput)?.boundQuestionCandidates.isNullOrEmpty(),
        ) { "Legacy tutor requests cannot carry a bound-question candidate menu" }
        require(
            schemaVersion >= TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION ||
                (input as? TutorRespondInput)?.knownRoundQuestion == null,
        ) { "Legacy tutor requests cannot carry a known round question" }
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
        /** Schema at which the consent flag was introduced (as `captureEgressConsentGranted`). */
        const val CONSENT_INTRODUCED_SCHEMA_VERSION = 7
        /** Schema at which the consent field was renamed to `agentConsentGranted`. */
        const val AGENT_CONSENT_SCHEMA_VERSION = 8
        /** Schema at which lobby messages may carry student-selected images. */
        const val LOBBY_IMAGE_SCHEMA_VERSION = 9
        /** Schema at which lobby messages may also carry earlier images and a history digest. */
        const val LOBBY_CONTEXT_SCHEMA_VERSION = 10
        /** Schema at which a tutor reply may declare which round question it is answering. */
        const val TUTOR_ROUND_BINDING_SCHEMA_VERSION = 11
        /**
         * Schema at which a Respond round may also carry the request side's **already known**
         * question anchor (`knownRoundQuestion`) — the fallback the write gate uses when the
         * model did not restate an anchor in a native `tool_calls` round.
         */
        const val TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION = 12
        const val CURRENT_SCHEMA_VERSION = TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION
        const val MAX_ID_CHARS = 256
    }
}

/**
 * 本轮所在的行是否受"每轮绑定"约束：schema 11（[ModelTaskRequest.TUTOR_ROUND_BINDING_SCHEMA_VERSION]）
 * 起，Respond 轮次可能没有题（声明缺失或核不过），所以写门控与答案暴露都要按绑定判；更早的行里
 * 这一维不存在，必须按当年的语义读（见 `canExposeSolutionFor`）。
 */
val ModelTaskRequest.requiresRoundQuestionBinding: Boolean
    get() = schemaVersion >= ModelTaskRequest.TUTOR_ROUND_BINDING_SCHEMA_VERSION

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
                maxChars = MAX_PROGRESS_MESSAGE_CHARS,
                allowLineBreaks = true,
            )
        }

        companion object {
            /**
             * Builds a progress event from a raw model-output prefix. The prefix is a
             * *progressive preview*, not the finished reply, so it may legitimately exceed
             * the snapshot status-message budget. Truncating here (instead of rejecting)
             * keeps a long reply streaming instead of failing the whole task; the terminal
             * [ModelGatewayEvent.Completed] always carries the full body.
             */
            fun of(text: String): Progress = Progress(
                stage = ModelTaskStage.VALIDATING_OUTPUT,
                userMessage = text.take(MAX_PROGRESS_MESSAGE_CHARS),
            )
        }
    }

    data class Completed(val output: ModelTaskOutput) : ModelGatewayEvent

    /**
     * 逐 token 的实时文本：思考链、回答正文、工具调用进度在生成中逐段增长的样子。
     *
     * 它**不落库、不写审计行、不计入单任务的事件上限**——那三样是 [Progress] 每次都要付的
     * 代价，也是此前实时文本只能"每 8 个分片发一帧、整条任务最多 24 帧、正文截到 500 字"
     * 的原因。代价降到零之后，瓶颈只剩渲染，于是可以按读取节奏直接推送。
     * 终态仍以 [Completed] 为准：这条通道只负责"还在生成时看到什么"。
     */
    data class LiveProgress(
        val kind: ModelLiveKind,
        val text: String,
    ) : ModelGatewayEvent

    data class Failed(val failure: ModelTaskFailure) : ModelGatewayEvent
}

/** 实时文本属于哪一类：思考链 / 回答正文 / 工具调用进度。 */
enum class ModelLiveKind {
    THINKING,
    ANSWER,
    TOOL,
}

/** 生成中的实时文本快照。 */
data class ModelLiveText(
    val kind: ModelLiveKind,
    val text: String,
)

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

    fun decodeRequest(value: String): ModelTaskRequest {
        val bounded = value.bounded()
        return try {
            json.decodeFromString(ModelTaskRequest.serializer(), bounded)
        } catch (failure: SerializationException) {
            // Schema 7→8 renamed captureEgressConsentGranted→agentConsentGranted. A legacy v7
            // row still carries the old key; under ignoreUnknownKeys=false the v8 decoder rejects
            // it. Translate the old key to the new field and decode once more. A v8 row with the
            // old key is genuinely malformed and still throws.
            if (bounded.contains("\"schemaVersion\":7") &&
                bounded.contains("\"captureEgressConsentGranted\"")
            ) {
                json.decodeFromString(
                    ModelTaskRequest.serializer(),
                    bounded.replace("\"captureEgressConsentGranted\"", "\"agentConsentGranted\""),
                )
            } else {
                throw failure
            }
        }
    }

    fun encodeOutput(value: ModelTaskOutput): String =
        json.encodeToString(ModelTaskOutput.serializer(), value).bounded()

    fun decodeInput(value: String): ModelTaskInput =
        json.decodeFromString(ModelTaskInput.serializer(), value.bounded())

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
                    .withoutEmptyToolCarrier(input)
                    .withoutEmptyLobbyImageRefs(input)
                    .withoutEmptyLobbyContext(input)
                    .withoutEmptyBoundQuestionCandidates(input)
                    .withoutEmptyKnownRoundQuestion(input),
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
                    if (schemaVersion < ModelTaskRequest.AGENT_CONSENT_SCHEMA_VERSION) {
                        it.withoutAgentConsent()
                    } else {
                        it
                    }
                }
                .let {
                    if (schemaVersion < ModelTaskRequest.LOBBY_IMAGE_SCHEMA_VERSION) {
                        it.withoutEmptyLobbyImageRefs(input)
                    } else {
                        it
                    }
                }
                .let {
                    if (schemaVersion < ModelTaskRequest.LOBBY_CONTEXT_SCHEMA_VERSION) {
                        it.withoutEmptyLobbyContext(input)
                    } else {
                        it
                    }
                }
                .let {
                    if (schemaVersion < ModelTaskRequest.TUTOR_ROUND_BINDING_SCHEMA_VERSION) {
                        it.withoutEmptyBoundQuestionCandidates(input)
                    } else {
                        it
                    }
                }
                .let {
                    if (schemaVersion < ModelTaskRequest.TUTOR_KNOWN_ROUND_QUESTION_SCHEMA_VERSION) {
                        it.withoutEmptyKnownRoundQuestion(input)
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
 * 从 schema<8 行的指纹中排除 consent 字段（schema 7→8 改名平移）。consent 是传输层属性
 * （该轮是否在同意下外发），不参与"同一语义操作"的指纹。v7 编码器写旧键
 * "captureEgressConsentGranted"，v8 编码器写新键 "agentConsentGranted"；两者 true/false
 * 都需抹平，使旧 v7 行读回（无论原值为 true 还是默认 false）重算指纹与存库一致。
 */
private fun String.withoutAgentConsent(): String =
    replace(",\"captureEgressConsentGranted\":false", "")
        .replace(",\"captureEgressConsentGranted\":true", "")
        .replace(",\"agentConsentGranted\":false", "")
        .replace(",\"agentConsentGranted\":true", "")

/**
 * 从 schema<9 行的指纹中排除 Lobby 消息图片键（schema 9 引入 `sourceImageAssetRefs`）。
 * 旧 v8 行编码不含该键；strip 只影响空列表的补位（非空列表仅出现在 v9 行）。
 */
private fun String.withoutEmptyLobbyImageRefs(input: ModelTaskInput): String =
    if (input is TutorLobbyInput) {
        replace(",\"sourceImageAssetRefs\":[]", "")
    } else {
        this
    }

/**
 * 去掉 Lobby/Respond 的"上文图片 + 早期摘要"空载体键（schema 10 引入）。
 *
 * 旧 v9 行编码不含这两键，而两个指纹路径都以 `encodeDefaults = true` 编码当前输入——不抹平
 * 空载体，升级后读回旧行就会算出与存库不同的哈希，`toSnapshot` 直接抛
 * `LearningLedgerIntegrityException`（实测：升级后进智能体页即刻崩溃，因为首页要读最近的
 * Lobby 任务行）。非空值只可能出现在 v10 行，所以 strip 不会削弱新行的指纹区分度。
 */
private fun String.withoutEmptyLobbyContext(input: ModelTaskInput): String = when (input) {
    is TutorLobbyInput -> replace(",\"contextImageAssetRefs\":[]", "")
        .replace(",\"priorDigest\":null", "")

    is TutorRespondInput -> replace(",\"priorDigest\":null", "")
    else -> this
}

/**
 * 去掉 Respond 的"本轮候选菜单"空载体键（schema 11 引入）。
 *
 * 与 [withoutEmptyLobbyContext] 同一条教训（提交 bf8be888）：两个指纹路径都以
 * `encodeDefaults = true` 编码当前输入，旧 v10 行存的是不含该键的编码——不抹平空载体，
 * 升级后读回任意一条旧 Respond 行都会算出与存库不同的哈希，`toSnapshot` 直接抛
 * `LearningLedgerIntegrityException`。非空菜单只可能出现在 v11 行，所以 strip 不会削弱
 * 新行的指纹区分度。
 */
private fun String.withoutEmptyBoundQuestionCandidates(input: ModelTaskInput): String =
    if (input is TutorRespondInput) {
        replace(",\"boundQuestionCandidates\":[]", "")
    } else {
        this
    }

/**
 * 去掉 Respond 的"本轮请求侧已知题锚"空载体键（schema 12 引入）。
 *
 * 与 [withoutEmptyBoundQuestionCandidates] 同一条教训（提交 bf8be888）：两个指纹路径都以
 * `encodeDefaults = true` 编码当前输入，旧 v11 行存的是不含该键的编码——不抹平空载体，
 * 升级后读回任意一条旧 Respond 行都会算出与存库不同的哈希，`toSnapshot` 直接抛
 * `LearningLedgerIntegrityException`。非空已知锚只可能出现在 v12 行（构造契约里有
 * `require`），所以 strip 不会削弱新行的指纹区分度。
 */
private fun String.withoutEmptyKnownRoundQuestion(input: ModelTaskInput): String =
    if (input is TutorRespondInput) {
        replace(",\"knownRoundQuestion\":null", "")
    } else {
        this
    }

internal fun NormalizedSourceRegion.isValidModelRegion(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0 &&
        left < right && top < bottom

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
/** 评估补充说明的长度上限（跨模块 UI 也要用它做输入限制）。 */
const val MAX_CAPTURE_USER_HINT_CHARS = 120
internal const val MAX_MESSAGE_CHARS = 500

/**
 * Per-frame budget for a streaming [ModelGatewayEvent.Progress] preview (snapshot status message).
 * A raw model-output prefix is truncated to this before entering the snapshot; keeping the full
 * body here would conflate a short status message with arbitrary-length reply content.
 */
const val MAX_PROGRESS_MESSAGE_CHARS = 2000
internal const val MAX_MODEL_VERSION_CHARS = 256
internal const val MAX_PROVIDER_ID_CHARS = 128
private const val MAX_PROVIDER_DISPLAY_NAME_CHARS = 128
internal const val SHA_256_HEX_CHARS = 64
