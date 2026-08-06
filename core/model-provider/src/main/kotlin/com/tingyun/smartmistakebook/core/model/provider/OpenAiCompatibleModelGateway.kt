package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationReadCapability
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.BudgetedTutorContext
import com.tingyun.smartmistakebook.core.model.BudgetedTutorTeachingReference
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.FigureAxis
import com.tingyun.smartmistakebook.core.model.FigureCoordinate
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.CurrentTutorInteractionPolicyWire
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationException
import com.tingyun.smartmistakebook.core.model.ModelEgressDataClass
import com.tingyun.smartmistakebook.core.model.ModelEgressPolicy
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelRequestBudgetExceededException
import com.tingyun.smartmistakebook.core.model.ModelRequestPayloadBudget
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationModelTaskProtocol
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StreamingMarkdownAssembler
import com.tingyun.smartmistakebook.core.model.StreamingMarkdownCompletion
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorOpenResponseEvaluationInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorStreamTarget
import com.tingyun.smartmistakebook.core.model.TutorStructuredPreviewCompletion
import com.tingyun.smartmistakebook.core.model.TutorStructuredPreviewDecoder
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeGuidance
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceFact
import com.tingyun.smartmistakebook.core.model.authorizesSolutionExposure
import com.tingyun.smartmistakebook.core.model.toCurrentTutorInteractionPolicy
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Real, consent-gated multimodal adapter for OpenAI-compatible chat-completions endpoints.
 * It receives no Room handle and can read image bytes only through [RestrictedModelAssetSource].
 */
internal class OpenAiCompatibleModelGateway(
    private val configurationStore: ModelConfigurationReadCapability,
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
                    ModelEgressPolicy.requireCurrentExternalAuthorization(
                        execution = execution,
                        provider = provider,
                        nowEpochMillis = clock(),
                    )
                    val authorizedDisclosures = execution.requireAuthorizedDisclosures()
                    val streamingTransport = transport as? StreamingModelHttpTransport
                    val useStreaming = streamingTransport != null &&
                        execution.request.input.isTutorStreamInput()
                    val imageReadPlan = execution.requireImageRequestFits(
                        modelId = provider.modelId,
                        stream = useStreaming,
                    )
                    emit(ModelGatewayEvent.Started(provider))
                    emit(
                        ModelGatewayEvent.Progress(
                            stage = if (execution.request.input is TutorPlanInput ||
                                execution.request.input is TutorLobbyInput ||
                                execution.request.input is TutorRespondInput ||
                                execution.request.input is TutorOpenResponseEvaluationInput ||
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
                                execution.request.input is TutorOpenResponseEvaluationInput ||
                                execution.request.input is ProblemOrganizationInput
                            ) {
                                "正在读取当前题目"
                            } else {
                                "正在读取你刚刚授权的题图"
                            },
                        ),
                    )
                    val images = readApprovedImages(execution, imageReadPlan)
                    try {
                        (execution.request.input as? TutorOpenResponseEvaluationInput)
                            ?.requireCurrentHostAuthorization()
                        val requestBody = OpenAiModelProtocol.requestBody(
                            modelId = provider.modelId,
                            input = execution.request.input,
                            images = images,
                            authorizedDisclosures = authorizedDisclosures,
                            stream = useStreaming,
                        )
                        val textRequestBody = OpenAiModelProtocol.textOnlyRequestBody(
                            modelId = provider.modelId,
                            input = execution.request.input,
                            imageMimeTypes = images.map(ApprovedImage::mimeType),
                            authorizedDisclosures = authorizedDisclosures,
                            stream = useStreaming,
                        )
                        OpenAiTextRequestBudget.requireFits(
                            serializedJson = textRequestBody,
                            input = execution.request.input,
                        )
                        ModelRequestPayloadBudget.requirePreparedRequestFits(
                            nonImageJsonUtf8Bytes =
                                requestBody.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                            assetByteSizes = emptyList(),
                        )
                        emit(
                            ModelGatewayEvent.Progress(
                                stage = ModelTaskStage.VALIDATING_OUTPUT,
                                userMessage = when (execution.request.input) {
                                    is CaptureAssessmentInput -> "模型正在判断题目是否拍全"
                                    is CaptureParseInput -> "模型正在整理可核对的题面"
                                    is TutorPlanInput -> "模型正在准备当前题的讲解"
                                    is TutorLobbyInput -> "模型正在理解你的消息"
                                    is TutorRespondInput -> "模型正在回应你对当前题的追问"
                                    is TutorOpenResponseEvaluationInput -> "模型正在核对当前回答"
                                    is TutorVisualGenerateInput -> "正在核对题图并组织直观讲解"
                                    is TutorVisualReviewInput -> "正在复核图中的关键关系"
                                    is ProblemOrganizationInput -> "模型正在提出待确认的分类和题目联系"
                                    is ProblemOrganizationV3Input -> "模型正在核对题图中的步骤和错误证据"
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
                        if (streamingTransport == null || !useStreaming) {
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
                            currentCoroutineContext().ensureActive()
                            val event = response.toGatewayEvent(execution, currentProvider.modelId)
                            currentCoroutineContext().ensureActive()
                            emit(event)
                        } else {
                            streamTutorResponse(
                                transport = streamingTransport,
                                configuration = credential.configuration,
                                apiKey = keyChars,
                                execution = execution,
                                modelVersion = currentProvider.modelId,
                                requestBody = requestBody,
                            ).collect { event -> emit(event) }
                        }
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
                } catch (_: RetryableTutorStreamTerminalException) {
                    emit(failure(RETRYABLE_STREAM_INVALID_RESPONSE))
                } catch (invalid: InvalidModelResponseException) {
                    emit(failure(INVALID_RESPONSE))
                } catch (_: IllegalArgumentException) {
                    emit(failure(INVALID_RESPONSE))
                } finally {
                    Arrays.fill(keyChars, '\u0000')
                }
            }
        }
    }.flowOn(Dispatchers.IO)

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
        imageReadPlan: List<ApprovedImageReadPlan>,
    ): List<ApprovedImage> {
        return buildList {
            try {
                imageReadPlan.forEach { planned ->
                    assetSource.open(execution, planned.assetId).use { asset ->
                        require(asset.mimeType in APPROVED_IMAGE_MIME_TYPES) {
                            "Approved model asset is not a canonical image"
                        }
                        if (asset.byteSize != planned.byteSize) {
                            throw SecurityException("Approved model asset size changed after preflight")
                        }
                        val bytes = asset.stream.readExactlyBounded(planned.byteSize)
                        add(ApprovedImage(asset.mimeType, bytes))
                    }
                }
            } catch (failure: Throwable) {
                forEach(ApprovedImage::close)
                throw failure
            }
        }
    }

    private fun streamTutorResponse(
        transport: StreamingModelHttpTransport,
        configuration: ModelConfigurationSnapshot,
        apiKey: CharArray,
        execution: ModelGatewayExecution,
        modelVersion: String,
        requestBody: String,
    ): Flow<ModelGatewayEvent> = flow {
        val input = execution.request.input
        val target = when (input) {
            is TutorRespondInput -> TutorStreamTarget.RESPOND
            is TutorLobbyInput -> TutorStreamTarget.LOBBY
            else -> throw InvalidModelResponseException()
        }
        val solutionPreviewAllowed =
            input is TutorRespondInput && input.authorizesSolutionExposure()
        val decoder = TutorStructuredPreviewDecoder(
            target = target,
            solutionPreviewAllowed = solutionPreviewAllowed,
        )
        val assembler = StreamingMarkdownAssembler()
        val structuredContent = StringBuilder()
        var lastSnapshot = com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot.EMPTY
        var previewEmitted = false
        var fallbackResponse: ModelHttpResponse? = null

        try {
            transport.stream(
                baseUrl = configuration.baseUrl,
                apiKey = apiKey,
                requestBody = requestBody,
                beforeEnqueue = {
                    requireCurrentAuthorizationBeforeEnqueue(
                        execution = execution,
                        expectedConfiguration = configuration,
                    )
                },
            ).collect { event ->
                when (event) {
                    is ModelHttpStreamEvent.Data -> {
                        if (fallbackResponse != null) throw InvalidModelResponseException()
                        val fragment = OpenAiModelProtocol.streamContentDelta(event.value)
                        if (fragment.isEmpty()) return@collect
                        if (structuredContent.length + fragment.length >
                            MAX_STREAMED_CONTENT_CHARS
                        ) {
                            throw InvalidModelResponseException()
                        }
                        structuredContent.append(fragment)
                        val decodedDelta = decoder.append(fragment)
                        if (decodedDelta.isNotEmpty()) {
                            val snapshot = assembler.append(decodedDelta)
                            if (target == TutorStreamTarget.RESPOND && snapshot !== lastSnapshot) {
                                lastSnapshot = snapshot
                                previewEmitted = true
                                emit(ModelGatewayEvent.TutorPreview(snapshot))
                            }
                        }
                    }

                    is ModelHttpStreamEvent.Fallback -> {
                        if (fallbackResponse != null || structuredContent.isNotEmpty()) {
                            throw InvalidModelResponseException()
                        }
                        fallbackResponse = event.response
                    }
                }
            }
        } catch (invalid: InvalidModelResponseException) {
            if (previewEmitted) throw RetryableTutorStreamTerminalException()
            throw invalid
        }

        fallbackResponse?.let { response ->
            if (response.statusCode in STREAM_UNSUPPORTED_STATUS_CODES && !previewEmitted) {
                val legacyBody = OpenAiModelProtocol.requestBody(
                    modelId = modelVersion,
                    input = input,
                    images = emptyList(),
                    authorizedDisclosures = execution.requireAuthorizedDisclosures(),
                    stream = false,
                )
                OpenAiTextRequestBudget.requireFits(legacyBody, input)
                ModelRequestPayloadBudget.requirePreparedRequestFits(
                    nonImageJsonUtf8Bytes =
                        legacyBody.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                    assetByteSizes = emptyList(),
                )
                val retried = this@OpenAiCompatibleModelGateway.transport.post(
                    baseUrl = configuration.baseUrl,
                    apiKey = apiKey,
                    requestBody = legacyBody,
                    beforeEnqueue = {
                        requireCurrentAuthorizationBeforeEnqueue(
                            execution = execution,
                            expectedConfiguration = configuration,
                        )
                    },
                )
                emit(retried.toGatewayEvent(execution, modelVersion))
            } else {
                emit(response.toGatewayEvent(execution, modelVersion))
            }
            return@flow
        }

        val decoded = decoder.complete()
        val assembled = assembler.complete()
        if (decoded !is TutorStructuredPreviewCompletion.Accepted) {
            throwInvalidTutorStreamTerminal(previewEmitted)
        }
        val completedSnapshot =
            (assembled as? StreamingMarkdownCompletion.Accepted)?.snapshot
                ?: throwInvalidTutorStreamTerminal(previewEmitted)
        if (target == TutorStreamTarget.RESPOND && completedSnapshot != lastSnapshot) {
            lastSnapshot = completedSnapshot
            previewEmitted = true
            emit(ModelGatewayEvent.TutorPreview(completedSnapshot))
        }
        val output = try {
            OpenAiModelProtocol.parseResponse(
                OpenAiModelProtocol.responseEnvelope(structuredContent.toString()),
                input,
                modelVersion,
            ).applyLocalTutorResponseBoundary(input)
        } catch (_: IllegalArgumentException) {
            throwInvalidTutorStreamTerminal(previewEmitted)
        }
        if (target == TutorStreamTarget.LOBBY) {
            val lobbyOutput = output as? TutorLobbyOutput
                ?: throwInvalidTutorStreamTerminal(previewEmitted)
            val authorizedAssembler = StreamingMarkdownAssembler()
            authorizedAssembler.append(lobbyOutput.messageMarkdown)
            val authorizedSnapshot =
                (authorizedAssembler.complete() as? StreamingMarkdownCompletion.Accepted)?.snapshot
                    ?: throwInvalidTutorStreamTerminal(previewEmitted)
            emit(ModelGatewayEvent.TutorPreview(authorizedSnapshot))
        }
        emit(
            ModelGatewayEvent.Completed(
                output,
            ),
        )
    }
}

object ConfiguredModelGatewayFactory {
    fun create(
        configurationStore: ModelConfigurationReadCapability,
        assetSource: RestrictedModelAssetSource,
    ): ModelGateway = OpenAiCompatibleModelGateway(configurationStore, assetSource)
}
