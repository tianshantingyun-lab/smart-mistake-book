package com.tingyun.smartmistakebook.core.data.model

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
import kotlinx.coroutines.CancellationException
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
                        val requestBody = OpenAiModelProtocol.requestBody(
                            modelId = provider.modelId,
                            input = execution.request.input,
                            images = images,
                            stream = stream,
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
                        val response = transport.post(
                            baseUrl = credential.configuration.baseUrl,
                            apiKey = keyChars,
                            requestBody = requestBody,
                            beforeEnqueue = {
                                requireCurrentAuthorizationBeforeEnqueue(
                                    execution = execution,
                                    expectedConfiguration = credential.configuration,
                                )
                            },
                        )
                        emitStreamingThenCompletion(
                            response = response,
                            execution = execution,
                            modelId = currentProvider.modelId,
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
                    emit(failure(TIMEOUT))
                } catch (network: IOException) {
                    emit(failure(NETWORK_UNAVAILABLE))
                } catch (invalid: InvalidModelResponseException) {
                    emit(failure(INVALID_RESPONSE))
                } catch (_: IllegalArgumentException) {
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
            // Under ProviderConsented the plans carry byteSize == 0 (resolved at open);
            // accumulate real sizes and enforce the budget once all are known.
            val consented = execution.permit == ModelExecutionPermit.ProviderConsented
            val consentedSizes = mutableListOf<Long>()
            try {
                imageReadPlan.forEach { planned ->
                    assetSource.open(execution, planned.assetId).use { asset ->
                        require(asset.mimeType in APPROVED_IMAGE_MIME_TYPES) {
                            "Approved model asset is not a canonical image"
                        }
                        val actualSize = if (consented) {
                            consentedSizes += asset.byteSize
                            asset.byteSize
                        } else {
                            if (asset.byteSize != planned.byteSize) {
                                throw SecurityException("Approved model asset size changed after preflight")
                            }
                            planned.byteSize
                        }
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
            OpenAiModelProtocol.parseResponse(body, execution.request.input, modelVersion),
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
    emit: suspend (ModelGatewayEvent) -> Unit,
) {
    val terminal = response.toGatewayEvent(execution, modelId)
    val chunks = response.streamChunks
    if (chunks == null || response.statusCode !in 200..299) {
        emit(terminal)
        return
    }
    // Emit a running prefix every few SSE frames so the UI can render the reply as it arrives
    // without flooding the event stream or writing the database on every single delta. The number
    // of frames is bounded by the transcript length, so this stays well under the repository's
    // gateway-event cap.
    var lastEmitted = 0
    for (index in chunks.indices) {
        if (index + 1 - lastEmitted >= STREAM_EMIT_CHUNK_INTERVAL) {
            emit(
                ModelGatewayEvent.Progress(
                    stage = ModelTaskStage.VALIDATING_OUTPUT,
                    userMessage = chunks.take(index + 1).joinToString(""),
                ),
            )
            lastEmitted = index + 1
        }
    }
    // Guarantee a final progressive frame even for a short reply (or when evenly-spaced frames
    // happened to land just before the end), so the UI always shows the typed body while streaming.
    if (lastEmitted != chunks.size) {
        emit(
            ModelGatewayEvent.Progress(
                stage = ModelTaskStage.VALIDATING_OUTPUT,
                userMessage = chunks.joinToString(""),
            ),
        )
    }
    emit(terminal)
}

private const val STREAM_EMIT_CHUNK_INTERVAL = 8

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
        // Streaming is only advertised once the provider passed a structured-output probe. A
        // stream=true tutor request against an unverified endpoint would otherwise fail closed on a
        // provider that was never confirmed to speak SSE, burning a dispatch.
        supportsStreaming = verification?.supportsStructuredOutput == true,
        executionLocation = if (supportedTasks.isEmpty()) {
            ModelExecutionLocation.UNAVAILABLE
        } else {
            ModelExecutionLocation.EXTERNAL_PROVIDER
        },
        providerConfigurationVersion = "openai-compatible-v1-${fingerprint.take(32)}",
    )
}

private fun ModelConfigurationSnapshot.configurationFingerprint(): String {
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

private fun ModelGatewayExecution.isReadyForNetwork(
    provider: ProviderCapabilitySnapshot,
): Boolean {
    val requiresImageInput = request.input is CaptureAssessmentInput ||
        request.input is CaptureParseInput ||
        request.input is ImagePipelineClassifyInput ||
        request.input is TutorVisualGenerateInput ||
        request.input is TutorVisualReviewInput
    // Under global consent a capture-pipeline request may egress without a manifest.
    val consentedCapture = permit == ModelExecutionPermit.ProviderConsented &&
        request.captureEgressConsentGranted &&
        (request.input is CaptureAssessmentInput ||
            request.input is CaptureParseInput ||
            request.input is ImagePipelineClassifyInput)
    if (!consentedCapture && request.egressManifest == null) return false
    return provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
        provider.supportsStructuredOutput &&
        provider.supports(request.input.kind) &&
        (!requiresImageInput || provider.supportsImageInput) &&
        (consentedCapture ||
            (request.egressManifest?.providerId == provider.providerId &&
                request.egressManifest?.modelId == provider.modelId &&
                request.egressManifest?.providerConfigurationVersion ==
                provider.providerConfigurationVersion))
}

private fun ModelGatewayExecution.requireImageRequestFits(
    modelId: String,
): List<ApprovedImageReadPlan> {
    val assetIds = when (val input = request.input) {
        is CaptureAssessmentInput -> buildList {
            add(input.sourceAssetId)
            input.followingSourceAssets.forEach { add(it.assetId) }
        }
        is CaptureParseInput -> input.sourceAssets.sortedBy { it.pageIndex }.map { it.assetId }
        is ImagePipelineClassifyInput -> listOf(input.sourceAssetId)
        is TutorVisualGenerateInput -> input.sourceAssets.sortedBy { it.pageIndex }.map { it.assetId }
        is TutorVisualReviewInput -> input.sourceAssets.sortedBy { it.pageIndex }.map { it.assetId }
        is TutorDebriefInput -> emptyList()
        is TutorPlanInput,
        is TutorLobbyInput,
        is TutorRespondInput,
        is ProblemOrganizationInput,
        -> emptyList()
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
                assetByteSizes = imageReadPlan.map(ApprovedImageReadPlan::byteSize),
            )
            return imageReadPlan
        }
        ModelExecutionPermit.ProviderConsented -> {
            // Global-consent read: byte sizes are resolved when each asset is opened
            // (the restricted asset source verifies consent + reads the canonical
            // record). Return plans without a preflight size; readApprovedImages
            // enforces the budget from the real opened sizes.
            check(request.captureEgressConsentGranted) {
                "Consented image request requires the consent flag"
            }
            return assetIds.map { assetId -> ApprovedImageReadPlan(assetId = assetId, byteSize = 0L) }
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
