package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.currentCapabilityVerification
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.MODEL_EGRESS_MAX_ASSET_BYTES
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationException
import com.tingyun.smartmistakebook.core.model.ModelEgressDataClass
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelExecutionPermit
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelRequestPayloadBudget
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorOpenResponseEvaluationInput
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.toByteString

internal fun ModelConfigurationSnapshot.toCapabilities(): ProviderCapabilitySnapshot {
    if (!isConfigured) return UNCONFIGURED_CAPABILITIES
    val fingerprint = configurationFingerprint()
    val verification = currentCapabilityVerification()
    val supportedTasks = buildSet {
        if (verification?.supportsStructuredOutput == true) {
            add(ModelTaskKind.TUTOR_PLAN)
            add(ModelTaskKind.TUTOR_RESPOND)
            add(ModelTaskKind.TUTOR_LOBBY)
            add(ModelTaskKind.TUTOR_EVALUATE)
            add(ModelTaskKind.PROBLEM_CLASSIFY)
            if (verification.supportsImageInput) {
                add(ModelTaskKind.CAPTURE_ASSESS)
                add(ModelTaskKind.CAPTURE_PARSE)
                add(ModelTaskKind.TUTOR_VISUAL_GENERATE)
                add(ModelTaskKind.TUTOR_VISUAL_REVIEW)
            }
        }
    }
    return ProviderCapabilitySnapshot(
        providerId = "configured-${fingerprint.take(16)}",
        providerDisplayName = provider,
        modelId = modelId,
        supportedTasks = supportedTasks,
        supportsImageInput = verification?.supportsImageInput == true,
        supportsStructuredOutput = verification?.supportsStructuredOutput == true,
        supportsStreaming = false,
        executionLocation = if (supportedTasks.isEmpty()) {
            ModelExecutionLocation.UNAVAILABLE
        } else {
            ModelExecutionLocation.EXTERNAL_PROVIDER
        },
        providerConfigurationVersion = "openai-compatible-v1-${fingerprint.take(32)}",
    )
}

internal fun ModelConfigurationSnapshot.configurationFingerprint(): String {
    val canonical = listOf(
        "openai-compatible-v1",
        provider,
        baseUrl,
        modelId,
        configurationVersion,
        updatedAtEpochMillis.toString(),
    ).joinToString("\n")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun ModelGatewayExecution.requireAuthorizedDisclosures(): Set<ModelEgressDataClass> {
    val permittedManifest = (permit as? ModelExecutionPermit.External)?.manifest
        ?: throw invalidPreEnqueueAuthorization()
    if (request.egressManifest != permittedManifest) throw invalidPreEnqueueAuthorization()
    return permittedManifest.disclosedData
}

internal fun java.io.InputStream.readExactlyBounded(expectedBytes: Long): ByteArray {
    require(expectedBytes in 1..MODEL_EGRESS_MAX_ASSET_BYTES)
    val output = ByteArrayOutputStream(expectedBytes.toInt())
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    try {
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > expectedBytes) throw SecurityException("Approved model asset grew while reading")
            output.write(buffer, 0, read)
        }
        if (total != expectedBytes) throw SecurityException("Approved model asset size changed")
        return output.toByteArray()
    } finally {
        Arrays.fill(buffer, 0.toByte())
    }
}

internal fun parseObject(value: String): JsonObject = try {
    Json.parseToJsonElement(value).jsonObject
} catch (_: Exception) {
    throw InvalidModelResponseException()
}

internal fun JsonElement?.extractTextContent(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull { element ->
        element.objectValue().takeIf { it.optionalString("type") == "text" }?.optionalString("text")
    }.joinToString("").ifBlank { null }
    else -> null
}

internal fun String.unwrapJsonFence(): String {
    val trimmed = trim()
    if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) return trimmed
    return trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}

internal fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.optionalArray(name: String): JsonArray {
    val value = this[name] ?: return JsonArray(emptyList())
    return value as? JsonArray ?: throw InvalidModelResponseException()
}
internal fun JsonObject.optionalObject(name: String): JsonObject? {
    val value = this[name] ?: return null
    if (value is JsonPrimitive && value.contentOrNull == null) return null
    return value as? JsonObject ?: throw InvalidModelResponseException()
}
internal fun JsonObject.objectValue(name: String): JsonObject = this[name].objectValue()
internal fun JsonElement?.objectValue(): JsonObject = this as? JsonObject ?: throw InvalidModelResponseException()
internal fun JsonObject.requiredString(name: String): String = optionalString(name)
    ?.takeIf(String::isNotBlank) ?: throw InvalidModelResponseException()
internal val CAPTURE_ASSESSMENT_WIRE_KEYS =
    setOf("decision", "issues", "suggestedActions", "questionRegions", "followingPageRelations")
internal val CAPTURE_ASSESSMENT_ISSUE_WIRE_KEYS =
    setOf("code", "severity", "region", "message")

internal fun failure(value: ModelTaskFailure): ModelGatewayEvent = ModelGatewayEvent.Failed(value)

internal fun invalidPreEnqueueAuthorization() = ModelEgressAuthorizationException(
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
    "Model credential or external authorization changed before enqueue",
)

internal class RetryableTutorStreamTerminalException : IllegalArgumentException()

internal fun throwInvalidTutorStreamTerminal(previewEmitted: Boolean): Nothing {
    if (previewEmitted) throw RetryableTutorStreamTerminalException()
    throw InvalidModelResponseException()
}

internal val UNCONFIGURED_CAPABILITIES = ProviderCapabilitySnapshot(
    providerId = "unconfigured",
    providerDisplayName = "尚未配置模型",
    modelId = "unconfigured",
    supportedTasks = emptySet(),
    supportsImageInput = false,
    supportsStructuredOutput = false,
    supportsStreaming = false,
    executionLocation = ModelExecutionLocation.UNAVAILABLE,
    providerConfigurationVersion = "unconfigured-v1",
)

internal val MODEL_NOT_CONFIGURED = ModelTaskFailure(
    ModelFailureCode.MODEL_NOT_CONFIGURED,
    "请先在“我的”中配置兼容的多模态模型",
    true,
)
internal val CONFIGURATION_UNAVAILABLE = ModelTaskFailure(
    ModelFailureCode.MODEL_NOT_CONFIGURED,
    "模型配置暂时无法安全读取，请重新保存配置",
    false,
)
internal val CONFIGURATION_CHANGED = ModelTaskFailure(
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
    "模型配置已变化，请重新确认本次发送范围",
    false,
)
internal val EGRESS_AUTHORIZATION_INVALID = ModelTaskFailure(
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
    "本次发送授权已过期或范围已变化，请重新确认本次发送范围",
    false,
)
internal val TIMEOUT = ModelTaskFailure(
    ModelFailureCode.TIMEOUT,
    "模型响应超时，任务已保留，可以稍后重试",
    true,
)
internal val NETWORK_UNAVAILABLE = ModelTaskFailure(
    ModelFailureCode.NETWORK_UNAVAILABLE,
    "暂时无法连接模型服务，任务已保留",
    true,
)
internal val INVALID_RESPONSE = ModelTaskFailure(
    ModelFailureCode.INVALID_RESPONSE,
    "这次没有准备好题面，请重新处理",
    false,
)
internal val RETRYABLE_STREAM_INVALID_RESPONSE = ModelTaskFailure(
    ModelFailureCode.INVALID_RESPONSE,
    "这次回复没有完整生成，已保留可安全显示的内容",
    true,
)

internal val REQUEST_TOO_LARGE = ModelTaskFailure(
    ModelFailureCode.PROVIDER_REJECTED_INPUT,
    "本次题图总量超过单次发送上限，请减少图片后重试",
    false,
)

internal const val REQUEST_BUDGET_MIME_TYPE = "image/jpeg"
internal const val MAX_STREAMED_CONTENT_CHARS = 2 * 1_024 * 1_024
internal val STREAM_UNSUPPORTED_STATUS_CODES = setOf(400, 415, 422)
internal val APPROVED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png")

internal class ApprovedImage(
    val mimeType: String,
    private val bytes: ByteArray,
) : AutoCloseable {
    fun base64(): String = bytes.toByteString().base64()

    override fun close() = Arrays.fill(bytes, 0.toByte())
}

internal data class ApprovedImageReadPlan(
    val assetId: String,
    val byteSize: Long,
)

internal fun ModelGatewayExecution.isReadyForNetwork(
    provider: ProviderCapabilitySnapshot,
): Boolean {
    val permittedManifest = (permit as? ModelExecutionPermit.External)?.manifest ?: return false
    val manifest = request.egressManifest ?: return false
    val requiresImageInput = request.input is CaptureAssessmentInput ||
        request.input is CaptureParseInput ||
        request.input is TutorVisualGenerateInput ||
        request.input is TutorVisualReviewInput ||
        request.input is ProblemOrganizationV3Input
    return manifest == permittedManifest &&
        provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
        provider.supportsStructuredOutput &&
        provider.supports(request.input.kind) &&
        (!requiresImageInput || provider.supportsImageInput) &&
        manifest.providerId == provider.providerId &&
        manifest.modelId == provider.modelId &&
        manifest.providerConfigurationVersion == provider.providerConfigurationVersion
}

internal fun ModelGatewayExecution.requireImageRequestFits(
    modelId: String,
    stream: Boolean,
): List<ApprovedImageReadPlan> {
    val assetIds = when (val input = request.input) {
        is CaptureAssessmentInput -> buildList {
            add(input.sourceAssetId)
            input.followingSourceAssets.forEach { add(it.assetId) }
        }
        is CaptureParseInput -> input.sourceAssets.sortedBy { it.pageIndex }.map { it.assetId }
        is TutorVisualGenerateInput -> input.sourceAssets.sortedBy { it.pageIndex }.map { it.assetId }
        is TutorVisualReviewInput -> input.sourceAssets.sortedBy { it.pageIndex }.map { it.assetId }
        is ProblemOrganizationV3Input ->
            input.sourceAssets.sortedBy { it.pageIndex }.map { it.assetId }
        is TutorPlanInput,
        is TutorLobbyInput,
        is TutorRespondInput,
        is TutorOpenResponseEvaluationInput,
        is ProblemOrganizationInput,
        -> emptyList()
    }
    val permittedManifest = (permit as? ModelExecutionPermit.External)?.manifest
        ?: throw SecurityException("External model request has no current grant")
    if (request.egressManifest != permittedManifest) {
        throw SecurityException("External model request does not match its image grant")
    }
    val imageReadPlan = assetIds.map { assetId ->
        val byteSize = permittedManifest.assets
            .singleOrNull { grant -> grant.assetId == assetId }
            ?.byteSize
            ?: throw SecurityException("External model image grant is incomplete")
        ApprovedImageReadPlan(assetId = assetId, byteSize = byteSize)
    }
    val nonImageJsonUtf8Bytes = OpenAiModelProtocol.nonImageJsonUtf8Bytes(
        modelId = modelId,
        input = request.input,
        imageCount = assetIds.size,
        authorizedDisclosures = permittedManifest.disclosedData,
        stream = stream,
    )
    OpenAiTextRequestBudget.requireFits(
        serializedJson = OpenAiModelProtocol.textOnlyRequestBody(
            modelId = modelId,
            input = request.input,
            imageMimeTypes = List(assetIds.size) { REQUEST_BUDGET_MIME_TYPE },
            authorizedDisclosures = permittedManifest.disclosedData,
            stream = stream,
        ),
        input = request.input,
    )
    ModelRequestPayloadBudget.requirePreparedRequestFits(
        nonImageJsonUtf8Bytes = nonImageJsonUtf8Bytes,
        assetByteSizes = imageReadPlan.map(ApprovedImageReadPlan::byteSize),
    )
    return imageReadPlan
}
