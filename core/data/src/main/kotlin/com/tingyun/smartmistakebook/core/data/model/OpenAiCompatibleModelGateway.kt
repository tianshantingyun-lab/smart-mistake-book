package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.data.model.wire.ModelWireProtocol
import com.tingyun.smartmistakebook.core.data.model.wire.protocolFor
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.domain.currentCapabilityVerification
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentAction
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssue
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssueCode
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentSeverity
import com.tingyun.smartmistakebook.core.model.CapturePageRelation
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyInput
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.FigureAxis
import com.tingyun.smartmistakebook.core.model.FigureCoordinate
import com.tingyun.smartmistakebook.core.model.FigureLabel
import com.tingyun.smartmistakebook.core.model.FigurePoint
import com.tingyun.smartmistakebook.core.model.FigurePolyline
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.FigureSeriesStyle
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationException
import com.tingyun.smartmistakebook.core.model.MODEL_EGRESS_MAX_ASSET_BYTES
import com.tingyun.smartmistakebook.core.model.ModelEgressPolicy
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelExecutionPermit
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelRequestBudgetExceededException
import com.tingyun.smartmistakebook.core.model.ModelRequestPayloadBudget
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.MODEL_TASK_STATUS_MESSAGE_MAX_CHARS
import com.tingyun.smartmistakebook.core.model.agentConsentMatches
import com.tingyun.smartmistakebook.core.model.requiresImageInput
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import com.tingyun.smartmistakebook.core.model.StructuredContentSanitizer
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.TutorComparisonRow
import com.tingyun.smartmistakebook.core.model.TutorComparisonScene
import com.tingyun.smartmistakebook.core.model.TutorConceptMapScene
import com.tingyun.smartmistakebook.core.model.TutorConceptRelation
import com.tingyun.smartmistakebook.core.model.TutorCircularMotionScene
import com.tingyun.smartmistakebook.core.model.TutorDiagramAnchor
import com.tingyun.smartmistakebook.core.model.TutorDiagramEdge
import com.tingyun.smartmistakebook.core.model.TutorDiagramEdgeStyle
import com.tingyun.smartmistakebook.core.model.TutorDiagramNode
import com.tingyun.smartmistakebook.core.model.TutorDiagramNodeShape
import com.tingyun.smartmistakebook.core.model.TutorEvidenceChainScene
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorDebriefInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorEvidencePoint
import com.tingyun.smartmistakebook.core.model.TutorEvidencePointKind
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationScene
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationStep
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorLinearMotionScene
import com.tingyun.smartmistakebook.core.model.TutorOscillationMotionScene
import com.tingyun.smartmistakebook.core.model.TutorProcessStage
import com.tingyun.smartmistakebook.core.model.TutorProcessTimelineScene
import com.tingyun.smartmistakebook.core.model.TutorProjectileMotionScene
import com.tingyun.smartmistakebook.core.model.TutorSceneEmphasis
import com.tingyun.smartmistakebook.core.model.TutorSceneStep
import com.tingyun.smartmistakebook.core.model.TutorSpatialDiagramScene
import com.tingyun.smartmistakebook.core.model.TutorStepFlowScene
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationRequest
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityShape
import com.tingyun.smartmistakebook.core.model.TutorVisualExpression
import com.tingyun.smartmistakebook.core.model.TutorVisualExpressionOperation
import com.tingyun.smartmistakebook.core.model.TutorVisualFormulaCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualLineStyle
import com.tingyun.smartmistakebook.core.model.TutorVisualLinkCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualMetricCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualNoteCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualParameter
import com.tingyun.smartmistakebook.core.model.TutorVisualPathCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualProgramScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTableCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualVectorCommand
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.toByteString

/**
 * Real, consent-gated multimodal adapter for OpenAI-compatible chat-completions endpoints.
 * It receives no Room handle and can read image bytes only through [RestrictedModelAssetSource].
 */
internal class OpenAiCompatibleModelGateway(
    private val configurationStore: ModelConfigurationStore,
    private val assetSource: RestrictedModelAssetSource,
    private val transport: ModelHttpTransport = OkHttpModelTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelGateway {
    override suspend fun capabilities(): ProviderCapabilitySnapshot =
        configurationStore.configuration.first().toCapabilities()

    override fun execute(execution: ModelGatewayExecution): Flow<ModelGatewayEvent> = flow {
        when (val credential = configurationStore.readCredential()) {
            ModelCredentialReadResult.Missing -> emit(failure(MODEL_NOT_CONFIGURED))
            ModelCredentialReadResult.Unavailable -> emit(failure(CONFIGURATION_UNAVAILABLE))
            is ModelCredentialReadResult.Available -> credential.apiKey.use { apiKey ->
                val provider = credential.configuration.toCapabilities()
                if (!execution.isReadyForNetwork(provider)) {
                    emit(failure(CONFIGURATION_CHANGED))
                    return@use
                }

                val keyChars = apiKey.copyChars()
                try {
                    val imageReadPlan = execution.requireImageRequestFits(provider.modelId)
                    emit(ModelGatewayEvent.Started(provider))
                    emit(
                        ModelGatewayEvent.Progress(
                            stage = if (execution.request.input is TutorPlanInput ||
                                execution.request.input is TutorLobbyInput ||
                                execution.request.input is TutorRespondInput ||
                                execution.request.input is ProblemOrganizationInput
                            ) {
                                ModelTaskStage.PREPARING
                            } else {
                                ModelTaskStage.READING_IMAGE
                            },
                            userMessage = if (
                                execution.request.input is TutorPlanInput ||
                                execution.request.input is TutorLobbyInput ||
                                execution.request.input is TutorRespondInput ||
                                execution.request.input is ProblemOrganizationInput
                            ) {
                                "正在读取当前题目"
                            } else {
                                "正在读取你刚刚授权的题图"
                            },
                        ),
                    )
                    val images = readApprovedImages(execution, provider.modelId, imageReadPlan)
                    try {
                        val stream = provider.supportsStreaming &&
                            execution.request.input.usesOpenAiSse()
                        // 协议由配置驱动（spec §3.2 显式协议选择）；未实现的协议在此 fail fast，
                        // 不会静默降级成别的协议去发请求。
                        val protocol = protocolFor(credential.configuration.protocol)
                        val baseUrl = credential.configuration.baseUrl.toHttpUrlOrNull()
                            ?: throw UnsafeModelEndpointException()
                        val requestBody = protocol.requestBody(
                            modelId = provider.modelId,
                            input = execution.request.input,
                            images = images,
                            stream = stream,
                            // Route A（原生 tools）：仅当探测证明端点支持原生工具往返、
                            // 且协议族支持原生工具时才启用。nativeToolSchemas 对未声明工具
                            // 的输入/空声明返回 null → 无 tools 字段，回落 Route B 信封；
                            // 故此处可安全地按能力位宽放，不会污染非工具环 dispatch。
                            enableNativeTools = provider.supportsFunctionCalling &&
                                protocol.supportsNativeTools,
                        )
                        emit(
                            ModelGatewayEvent.Progress(
                                stage = ModelTaskStage.VALIDATING_OUTPUT,
                                userMessage = when (execution.request.input) {
                                    is CaptureAssessmentInput -> "模型正在判断题目是否拍全"
                                    is CaptureParseInput -> "模型正在整理可核对的题面"
                                    is ImagePipelineClassifyInput -> "模型正在判断题目是否需要配图"
                                    is TutorPlanInput -> "模型正在准备当前题的讲解"
                                    is TutorLobbyInput -> "模型正在理解你的消息"
                                    is TutorRespondInput -> "模型正在回应你对当前题的追问"
                                    is TutorVisualGenerateInput -> "正在核对题图并组织直观讲解"
                                    is TutorVisualReviewInput -> "正在复核图中的关键关系"
                                    is TutorDebriefInput -> "正在安静地整理这次讲题的要点"
                                    is com.tingyun.smartmistakebook.core.model.KnowledgeQuizInput -> "模型正在按知识点出复习题"
                                    is ProblemOrganizationInput -> "模型正在提出待确认的分类和题目联系"
                                },
                            ),
                        )
                        val currentProvider = try {
                            configurationStore.configuration.first().toCapabilities()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emit(failure(CONFIGURATION_UNAVAILABLE))
                            return@use
                        }
                        if (!execution.isReadyForNetwork(currentProvider)) {
                            emit(failure(CONFIGURATION_CHANGED))
                            return@use
                        }
                        ModelEgressPolicy.requireCurrentExternalAuthorization(
                            execution = execution,
                            provider = currentProvider,
                            nowEpochMillis = clock(),
                        )
                        val response = awaitWithLiveReasoning(
                            stream = stream,
                            post = {
                                transport.post(
                                    WireRequest(
                                        url = protocol.endpoint(baseUrl, provider.modelId, stream),
                                        headers = protocol.headers(keyChars, stream),
                                        body = requestBody,
                                        stream = stream,
                                        protocol = protocol,
                                        onReasoningDelta = it,
                                    ),
                                    beforeEnqueue = {
                                        requireCurrentAuthorizationBeforeEnqueue(
                                            execution = execution,
                                            expectedConfiguration = credential.configuration,
                                        )
                                    },
                                )
                            },
                            emit = ::emit,
                        )
                        emitStreamingThenCompletion(
                            response = response,
                            execution = execution,
                            modelId = currentProvider.modelId,
                            protocol = protocol,
                            emit = ::emit,
                        )
                    } finally {
                        images.forEach(ApprovedImage::close)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ModelRequestBudgetExceededException) {
                    emit(failure(REQUEST_TOO_LARGE))
                } catch (_: ModelEgressAuthorizationException) {
                    emit(failure(EGRESS_AUTHORIZATION_INVALID))
                } catch (unsafe: UnsafeModelEndpointException) {
                    emit(
                        failure(
                            ModelTaskFailure(
                                code = ModelFailureCode.PROVIDER_REJECTED_INPUT,
                                message = "模型地址不能指向本机、局域网或保留网络，请检查服务地址",
                                retryable = false,
                            ),
                        ),
                    )
                } catch (_: SecurityException) {
                    emit(failure(CONFIGURATION_CHANGED))
                } catch (timeout: SocketTimeoutException) {
                    logModelCallFailure(timeout)
                    emit(failure(TIMEOUT))
                } catch (network: IOException) {
                    logModelCallFailure(network)
                    emit(failure(NETWORK_UNAVAILABLE))
                } catch (invalid: InvalidModelResponseException) {
                    logModelCallFailure(invalid)
                    emit(failure(INVALID_RESPONSE))
                } catch (invalid: IllegalArgumentException) {
                    logModelCallFailure(invalid)
                    emit(failure(INVALID_RESPONSE))
                } finally {
                    Arrays.fill(keyChars, '\u0000')
                }
            }
        }
    }

    private suspend fun requireCurrentAuthorizationBeforeEnqueue(
        execution: ModelGatewayExecution,
        expectedConfiguration: ModelConfigurationSnapshot,
    ) {
        val currentCredential = when (val credential = configurationStore.readCredential()) {
            is ModelCredentialReadResult.Available -> credential
            ModelCredentialReadResult.Missing,
            ModelCredentialReadResult.Unavailable,
            -> throw invalidPreEnqueueAuthorization()
        }
        currentCredential.apiKey.use {
            val currentConfiguration = currentCredential.configuration
            if (
                currentConfiguration.configurationVersion !=
                expectedConfiguration.configurationVersion ||
                currentConfiguration.configurationFingerprint() !=
                expectedConfiguration.configurationFingerprint()
            ) {
                throw invalidPreEnqueueAuthorization()
            }
            val currentProvider = currentConfiguration.toCapabilities()
            if (!execution.isReadyForNetwork(currentProvider)) {
                throw invalidPreEnqueueAuthorization()
            }
            ModelEgressPolicy.requireCurrentExternalAuthorization(
                execution = execution,
                provider = currentProvider,
                nowEpochMillis = clock(),
            )
        }
    }

    private suspend fun readApprovedImages(
        execution: ModelGatewayExecution,
        modelId: String,
        imageReadPlan: List<ApprovedImageReadPlan>,
    ): List<ApprovedImage> {
        return buildList {
            // Under ProviderConsented the plans carry no preflight size (resolved at
            // open); accumulate real sizes and enforce the budget once all are known.
            val consented = execution.permit == ModelExecutionPermit.ProviderConsented
            val consentedSizes = mutableListOf<Long>()
            try {
                imageReadPlan.forEach { planned ->
                    assetSource.open(execution, planned.assetId).use { asset ->
                        require(asset.mimeType in APPROVED_IMAGE_MIME_TYPES) {
                            "Approved model asset is not a canonical image"
                        }
                        val actualSize = asset.byteSize
                        planned.byteSize?.let { preflight ->
                            if (actualSize != preflight) {
                                throw SecurityException("Approved model asset size changed after preflight")
                            }
                        }
                        if (consented) consentedSizes += actualSize
                        val bytes = asset.stream.readExactlyBounded(actualSize)
                        add(ApprovedImage(asset.mimeType, bytes))
                    }
                }
                if (consented) {
                    val nonImageJsonUtf8Bytes = OpenAiModelProtocol.nonImageJsonUtf8Bytes(
                        modelId = modelId,
                        input = execution.request.input,
                        imageCount = imageReadPlan.size,
                    )
                    ModelRequestPayloadBudget.requirePreparedRequestFits(
                        nonImageJsonUtf8Bytes = nonImageJsonUtf8Bytes,
                        assetByteSizes = consentedSizes,
                    )
                }
            } catch (failure: Throwable) {
                forEach(ApprovedImage::close)
                throw failure
            }
        }
    }
}

object ConfiguredModelGatewayFactory {
    fun create(
        configurationStore: ModelConfigurationStore,
        assetSource: RestrictedModelAssetSource,
    ): ModelGateway = OpenAiCompatibleModelGateway(configurationStore, assetSource)
}

private fun ModelHttpResponse.toGatewayEvent(
    execution: ModelGatewayExecution,
    protocol: ModelWireProtocol,
    modelVersion: String,
): ModelGatewayEvent {
    if (statusCode !in 200..299) {
        return failure(
            when (statusCode) {
                401, 403 -> ModelTaskFailure(
                    ModelFailureCode.AUTHENTICATION_FAILED,
                    "模型认证失败，请在“我的”中检查 API Key",
                    false,
                )
                429 -> ModelTaskFailure(
                    ModelFailureCode.RATE_LIMITED,
                    "模型请求较多，任务已保留，可以稍后重试",
                    true,
                )
                in 500..599 -> ModelTaskFailure(
                    ModelFailureCode.SERVICE_UNAVAILABLE,
                    "模型服务暂时不可用，任务已保留",
                    true,
                )
                else -> ModelTaskFailure(
                    ModelFailureCode.PROVIDER_REJECTED_INPUT,
                    "模型服务拒绝了这次请求，请检查模型兼容性",
                    false,
                )
            },
        )
    }
    return try {
        ModelGatewayEvent.Completed(
            protocol.parseCompletion(body, execution.request.input, modelVersion),
        )
    } catch (_: Exception) {
        failure(INVALID_RESPONSE)
    }
}

/**
 * For a streaming tutor response, replay the provider's incremental delta bodies as progressive
 * [ModelGatewayEvent.Progress] events before the terminal [Completed]. This keeps the persisted task
 * in [ModelTaskStatus.STREAMING] so the tutor UI can render the reply as it arrives. Only *tutor text
 * calls* arrive with [ModelHttpResponse.streamChunks]; every other path falls through to the single
 * terminal event so non-streaming behaviour is unchanged.
 */
private suspend fun emitStreamingThenCompletion(
    response: ModelHttpResponse,
    execution: ModelGatewayExecution,
    modelId: String,
    protocol: ModelWireProtocol,
    emit: suspend (ModelGatewayEvent) -> Unit,
) {
    val terminal = response.toGatewayEvent(execution, protocol, modelId)
    val chunks = response.streamChunks
    if (chunks == null || response.statusCode !in 200..299) {
        emit(terminal)
        return
    }
    // Emit a running prefix every few SSE frames so the UI can render the reply as it arrives.
    // Providers stream one delta per token, so a long answer can carry thousands of frames: the
    // stride adapts to the transcript length to keep the TOTAL number of progress frames bounded
    // (the repository rejects a task that produces too many gateway events — a long answer used to
    // kill its own reply at that cap).
    val stride = maxOf(
        STREAM_EMIT_CHUNK_INTERVAL,
        (chunks.size + MAX_STREAM_PROGRESS_FRAMES - 1) / MAX_STREAM_PROGRESS_FRAMES,
    )
    var lastEmitted = 0
    for (index in chunks.indices) {
        if (index + 1 - lastEmitted >= stride) {
            emit(
                ModelGatewayEvent.Progress.of(
                    streamingPrefix(chunks.take(index + 1).joinToString("")),
                ),
            )
            lastEmitted = index + 1
        }
    }
    // Guarantee a final progressive frame even for a short reply (or when evenly-spaced frames
    // happened to land just before the end), so the UI always shows the typed body while streaming.
    if (lastEmitted != chunks.size) {
        emit(
            ModelGatewayEvent.Progress.of(streamingPrefix(chunks.joinToString(""))),
        )
    }
    emit(terminal)
}

/**
 * Bounds the running reply prefix to the task status budget. Real answers pass 500 characters
 * quickly, and the persistence contract rejects a longer status message — which used to abort
 * the whole task mid-stream (a tutor answer never arrived). The complete reply still travels in
 * the terminal event.
 */
private fun streamingPrefix(body: String): String =
    body.take(MODEL_TASK_STATUS_MESSAGE_MAX_CHARS)

private const val STREAM_EMIT_CHUNK_INTERVAL = 8

/** 单任务进度帧的硬上限（思考帧 + 正文前缀帧合计口径见各自的常量）。 */
private const val MAX_STREAM_PROGRESS_FRAMES = 24

private fun ModelConfigurationSnapshot.toCapabilities(): ProviderCapabilitySnapshot {
    if (!isConfigured) return UNCONFIGURED_CAPABILITIES
    val fingerprint = configurationFingerprint()
    val verification = currentCapabilityVerification()
    val supportedTasks = buildSet {
        if (verification?.supportsStructuredOutput == true) {
            add(ModelTaskKind.TUTOR_PLAN)
            add(ModelTaskKind.TUTOR_RESPOND)
            add(ModelTaskKind.TUTOR_LOBBY)
            add(ModelTaskKind.PROBLEM_CLASSIFY)
            add(ModelTaskKind.KNOWLEDGE_QUIZ)
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
        // Route A 原生 tools 能力：仅当探测证明端点接受原生 tools 请求才置真。
        supportsFunctionCalling = verification?.supportsFunctionCalling == true,
        // Streaming is only advertised once the provider passed a structured-output probe. A
        // stream=true tutor request against an unverified endpoint would otherwise fail closed on a
        // provider that was never confirmed to speak SSE, burning a dispatch.
        supportsStreaming = verification?.supportsStructuredOutput == true,
        executionLocation = if (supportedTasks.isEmpty()) {
            ModelExecutionLocation.UNAVAILABLE
        } else {
            ModelExecutionLocation.EXTERNAL_PROVIDER
        },
        providerConfigurationVersion = "${protocol.wireId}-${fingerprint.take(32)}",
    )
}

private fun ModelConfigurationSnapshot.configurationFingerprint(): String {
    val canonical = listOf(
        // 协议进指纹：切换协议必须让旧的能力验证与 egress 授权失效（spec §3.2）。
        protocol.wireId,
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

private fun ModelGatewayExecution.isReadyForNetwork(
    provider: ProviderCapabilitySnapshot,
): Boolean {
    // Under global agent consent an agent-eligible request may egress without a manifest.
    val consentedAgent = permit == ModelExecutionPermit.ProviderConsented &&
        request.agentConsentMatches(provider)
    if (!consentedAgent && request.egressManifest == null) return false
    return provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
        provider.supportsStructuredOutput &&
        provider.supports(request.input.kind) &&
        (!request.input.requiresImageInput() || provider.supportsImageInput) &&
        (consentedAgent ||
            (request.egressManifest?.providerId == provider.providerId &&
                request.egressManifest?.modelId == provider.modelId &&
                request.egressManifest?.providerConfigurationVersion ==
                provider.providerConfigurationVersion))
}

private fun ModelGatewayExecution.requireImageRequestFits(
    modelId: String,
): List<ApprovedImageReadPlan> {
    if (!request.input.requestsImageBytes) return emptyList()
    val assetIds = when (val input = request.input) {
        is CaptureAssessmentInput -> buildList {
            add(input.sourceAssetId)
            input.followingSourceAssets.forEach { add(it.assetId) }
        }
        is CaptureParseInput -> input.sourceAssets.sortedBy { it.pageIndex }.map { it.assetId }
        is ImagePipelineClassifyInput -> listOf(input.sourceAssetId)
        is TutorVisualGenerateInput -> input.sourceAssets.sortedBy { it.pageIndex }.map { it.assetId }
        is TutorVisualReviewInput -> input.sourceAssets.sortedBy { it.pageIndex }.map { it.assetId }
        is TutorLobbyInput -> input.sourceImageAssetRefs.sortedBy { it.pageIndex }.map { it.assetId }
        else -> emptyList()
    }
    if (assetIds.isEmpty()) return emptyList()

    when (val permit = permit) {
        is ModelExecutionPermit.External -> {
            val permittedManifest = permit.manifest
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
            )
            ModelRequestPayloadBudget.requirePreparedRequestFits(
                nonImageJsonUtf8Bytes = nonImageJsonUtf8Bytes,
                assetByteSizes = imageReadPlan.map { it.byteSize!! },
            )
            return imageReadPlan
        }
        ModelExecutionPermit.ProviderConsented -> {
            // Global-consent read: byte sizes are resolved when each asset is opened
            // (the restricted asset source verifies consent + reads the canonical
            // record). Plans carry no preflight size; readApprovedImages enforces
            // the budget from the real opened sizes.
            check(request.agentConsentGranted) {
                "Consented image request requires the consent flag"
            }
            return assetIds.map { assetId -> ApprovedImageReadPlan(assetId = assetId, byteSize = null) }
        }
        ModelExecutionPermit.LocalOnly -> throw SecurityException(
            "External model request has no current image grant",
        )
    }
}

private fun java.io.InputStream.readExactlyBounded(expectedBytes: Long): ByteArray {
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

private fun failure(value: ModelTaskFailure): ModelGatewayEvent = ModelGatewayEvent.Failed(value)

/**
 * 发送请求，并在流式读取期间**实时**把思考链逐步转发成进度事件（推理模型先吐很久思考、
 * 最后才给答案；学生该在等待时就看见它在想什么）。思考增量由传输层在读取线程回调进一个
 * 同步缓冲区，主协程轮询转发——发射始终发生在 flow 的收集协程里，不跨协程 emit。
 */
private suspend fun awaitWithLiveReasoning(
    stream: Boolean,
    post: suspend (onReasoningDelta: ((String) -> Unit)?) -> ModelHttpResponse,
    emit: suspend (ModelGatewayEvent) -> Unit,
): ModelHttpResponse {
    if (!stream) return post(null)
    val reasoning = Collections.synchronizedList(ArrayList<String>())
    return coroutineScope {
        val call = async { post { delta -> reasoning.add(delta) } }
        var emittedFrames = 0
        var observedDeltas = 0
        var nextEmitAtMillis = 0L
        var backoffMillis = LIVE_REASONING_FIRST_EMIT_MILLIS
        while (!call.isCompleted) {
            delay(LIVE_REASONING_POLL_MILLIS)
            val snapshot = synchronized(reasoning) { ArrayList(reasoning) }
            if (snapshot.size == observedDeltas) continue
            observedDeltas = snapshot.size
            // The repository caps how many gateway events one task may produce, so the live
            // thinking is deliberately sparse: a few frames with a growing interval, never a
            // frame per poll.
            if (emittedFrames >= LIVE_REASONING_MAX_FRAMES) continue
            val now = System.currentTimeMillis()
            if (now < nextEmitAtMillis) continue
            emittedFrames += 1
            nextEmitAtMillis = now + backoffMillis
            backoffMillis = (backoffMillis * 2).coerceAtMost(LIVE_REASONING_MAX_INTERVAL_MILLIS)
            emit(ModelGatewayEvent.Progress.of(streamingPrefix(snapshot.joinToString(""))))
        }
        call.await()
    }
}

private const val LIVE_REASONING_POLL_MILLIS = 400L
private const val LIVE_REASONING_FIRST_EMIT_MILLIS = 1_000L
private const val LIVE_REASONING_MAX_INTERVAL_MILLIS = 8_000L
private const val LIVE_REASONING_MAX_FRAMES = 8

/**
 * Diagnostics for a failed model call: the exception class and message only. Request and
 * response bodies are never logged — they carry student photos and text — and the credential
 * never reaches the log either.
 */
private fun logModelCallFailure(error: Throwable) {
    android.util.Log.e(
        MODEL_CALL_LOG_TAG,
        "Model call failed: ${error.javaClass.name}: ${error.message}",
    )
}

private const val MODEL_CALL_LOG_TAG = "SmartMistakeBook"

private fun invalidPreEnqueueAuthorization() = ModelEgressAuthorizationException(
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
    "Model credential or external authorization changed before enqueue",
)

private val UNCONFIGURED_CAPABILITIES = ProviderCapabilitySnapshot(
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

private val MODEL_NOT_CONFIGURED = ModelTaskFailure(
    ModelFailureCode.MODEL_NOT_CONFIGURED,
    "请先在“我的”中配置兼容的多模态模型",
    true,
)
private val CONFIGURATION_UNAVAILABLE = ModelTaskFailure(
    ModelFailureCode.MODEL_NOT_CONFIGURED,
    "模型配置暂时无法安全读取，请重新保存配置",
    false,
)
private val CONFIGURATION_CHANGED = ModelTaskFailure(
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
    "模型配置已变化，请重新确认本次发送范围",
    false,
)
private val EGRESS_AUTHORIZATION_INVALID = ModelTaskFailure(
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
    "本次发送授权已过期或范围已变化，请重新确认本次发送范围",
    false,
)
private val TIMEOUT = ModelTaskFailure(
    ModelFailureCode.TIMEOUT,
    "模型响应超时，任务已保留，可以稍后重试",
    true,
)
private val NETWORK_UNAVAILABLE = ModelTaskFailure(
    ModelFailureCode.NETWORK_UNAVAILABLE,
    "暂时无法连接模型服务，任务已保留",
    true,
)
private val INVALID_RESPONSE = ModelTaskFailure(
    ModelFailureCode.INVALID_RESPONSE,
    "这次没有准备好题面，请重新处理",
    false,
)

private val REQUEST_TOO_LARGE = ModelTaskFailure(
    ModelFailureCode.PROVIDER_REJECTED_INPUT,
    "本次题图总量超过单次发送上限，请减少图片后重试",
    false,
)

private val APPROVED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png")

private fun com.tingyun.smartmistakebook.core.model.ModelTaskInput.usesOpenAiSse(): Boolean =
    this is TutorPlanInput || this is TutorLobbyInput || this is TutorRespondInput
