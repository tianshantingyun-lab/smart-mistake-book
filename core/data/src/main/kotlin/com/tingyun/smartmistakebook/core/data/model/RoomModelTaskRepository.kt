package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.database.CreateModelTaskCommand
import com.tingyun.smartmistakebook.core.database.ReserveModelTaskRemoteDispatchCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.TransitionModelTaskCommand
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyInput
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationException
import com.tingyun.smartmistakebook.core.model.ModelEgressPolicy
import com.tingyun.smartmistakebook.core.model.ModelExecutionPermit
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelTaskCompletionValidator
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskLogicalOperationFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRemoteDispatchPolicy
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorToolOutcome
import com.tingyun.smartmistakebook.core.model.TutorToolRequestsOutput
import com.tingyun.smartmistakebook.core.model.TutorToolRoundResult
import com.tingyun.smartmistakebook.core.model.tutorToolAuthorization
import com.tingyun.smartmistakebook.core.data.study.RoomTutorToolRunner
import com.tingyun.smartmistakebook.core.model.ModelTaskInput
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout

class RoomModelTaskRepository internal constructor(
    private val database: StudyDatabasePort,
    private val gateway: ModelGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelTaskRepository {
    internal val toolRunner = RoomTutorToolRunner(database)

    override suspend fun capabilities(): ProviderCapabilitySnapshot = gateway.capabilities()

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> =
        database.observeModelTask(requestId)

    override fun observeBySubject(
        subjectId: String,
        kind: ModelTaskKind,
    ): Flow<List<ModelTaskSnapshot>> = database.observeModelTasks(subjectId, kind)

    override fun observeRecentBySubject(
        subjectId: String,
        kind: ModelTaskKind,
        limit: Int,
    ): Flow<List<ModelTaskSnapshot>> = database.observeRecentModelTasks(subjectId, kind, limit)

    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
        val requestFingerprint = ModelTaskFingerprint.of(request)
        val operationFingerprint = ModelTaskLogicalOperationFingerprint.of(request)
        val initial = database.createModelTask(
            CreateModelTaskCommand(
                taskId = stableTaskId(request.requestId),
                request = request,
                requestFingerprint = requestFingerprint,
                operationFingerprint = operationFingerprint,
                occurredAtEpochMillis = request.occurredAtEpochMillis,
            ),
        ).snapshot
        emit(initial)
        if (initial.status.isTerminal) return@flow

        var executionStart = initial
        var ownerCompletion = CompletableDeferred<Unit>()
        while (true) {
            val activeOwnerCompletion =
                processActiveOperations.putIfAbsent(operationFingerprint, ownerCompletion)
            if (activeOwnerCompletion == null) break

            activeOwnerCompletion.await()
            val latest = database.readModelTask(request.requestId) ?: return@flow
            emit(latest)
            if (latest.status.isTerminal || latest.status == ModelTaskStatus.RETRYABLE_FAILURE) {
                return@flow
            }
            executionStart = latest
            ownerCompletion = CompletableDeferred()
        }

        var remoteDispatchWasReserved = false
        try {
            var current = prepareForExecution(executionStart)
            emit(current)
            val declaredProvider = gateway.capabilities()
            capabilityFailure(request, declaredProvider)?.let { failure ->
                emit(
                    transition(
                        current = current,
                        nextStatus = ModelTaskStatus.PERMANENT_FAILURE,
                        stage = ModelTaskStage.PREPARING,
                        userMessage = failure.message,
                        failure = failure,
                    ),
                )
                return@flow
            }
            dependencyFailure(request, declaredProvider)?.let { failure ->
                emit(
                    transition(
                        current = current,
                        nextStatus = ModelTaskStatus.PERMANENT_FAILURE,
                        stage = ModelTaskStage.VALIDATING_OUTPUT,
                        userMessage = failure.message,
                        failure = failure,
                    ),
                )
                return@flow
            }
            // 工具环（spec model-intent-routing §3.1）：模型可在作答前申请本地只读查询。
            // 每轮派遣独立 reserve（预算记账，MAX_DISPATCHES=3 = 2 轮查询 + 1 轮作答）；
            // 本地工具执行不占派遣预算。第 3 轮起不再声明工具，模型必须直接作答。
            var roundRequest = request
            var toolRoundsUsed = 0
            var toolRoundResults = emptyList<TutorToolRoundResult>()
            val declaredTools = toolDeclarationsFor(roundRequest.input)
            var answered = false
            while (!answered) {
            val execution = try {
                ModelEgressPolicy.authorize(
                    request = roundRequest,
                    provider = declaredProvider,
                    nowEpochMillis = clock(),
                )
            } catch (denied: ModelEgressAuthorizationException) {
                emit(
                    transition(
                        current = current,
                        nextStatus = ModelTaskStatus.PERMANENT_FAILURE,
                        stage = ModelTaskStage.PREPARING,
                        userMessage = denied.message,
                        failure = ModelTaskFailure(
                            code = denied.failureCode,
                            message = denied.message,
                            retryable = false,
                        ),
                    ),
                )
                return@flow
            }
            current = when (execution.permit) {
                is ModelExecutionPermit.External -> {
                    val reservation = database.reserveModelTaskRemoteDispatch(
                        ReserveModelTaskRemoteDispatchCommand(
                            taskId = current.taskId,
                            expectedStateVersion = current.stateVersion,
                            expectedStatus = current.status,
                            provider = declaredProvider,
                            occurredAtEpochMillis = maxOf(clock(), current.updatedAtEpochMillis),
                        ),
                    )
                    if (!reservation.applied) {
                        if (!reservation.budgetExhausted) throw ConcurrentModelTaskTransition()
                        emit(
                            transition(
                                current = reservation.snapshot,
                                nextStatus = ModelTaskStatus.PERMANENT_FAILURE,
                                stage = ModelTaskStage.PREPARING,
                                userMessage = DISPATCH_LIMIT_USER_MESSAGE,
                                attemptCount = reservation.logicalDispatchCount,
                                failure = exhaustedDispatchFailure(executionStart.failure),
                            ),
                        )
                        return@flow
                    }
                    remoteDispatchWasReserved = true
                    reservation.snapshot
                }
                ModelExecutionPermit.LocalOnly -> transition(
                    current = current,
                    nextStatus = ModelTaskStatus.RUNNING,
                    stage = ModelTaskStage.PREPARING,
                    userMessage = "正在准备",
                    provider = declaredProvider,
                )
            }
            emit(current)
            var eventCount = 0
            var terminalEventSeen = false
            var providerStarted = false
            var toolRound: TutorToolRequestsOutput? = null
            withTimeout(MODEL_TASK_TIMEOUT_MILLIS) {
                gateway.execute(execution)
                    .onEach { event ->
                        eventCount += 1
                        if (eventCount > MAX_GATEWAY_EVENTS) {
                            throw InvalidProviderProtocol("模型返回了过多状态事件")
                        }
                        when (event) {
                            is ModelGatewayEvent.Started -> {
                                if (providerStarted) {
                                    throw InvalidProviderProtocol("模型返回了重复或乱序的开始事件")
                                }
                                providerStarted = true
                            }
                            is ModelGatewayEvent.Progress,
                            is ModelGatewayEvent.Completed,
                            -> if (!providerStarted) {
                                throw InvalidProviderProtocol("模型在开始任务前返回了内容")
                            }
                            is ModelGatewayEvent.Failed -> Unit
                        }
                        if (event is ModelGatewayEvent.Completed && event.output is TutorToolRequestsOutput) {
                            // 工具申请轮：不落终态，转回 QUEUED（RUNNING→QUEUED 合法，
                            // RUNNING→RUNNING 被 canTransitionTo 拒绝）后进入下一轮
                            toolRound = event.output as TutorToolRequestsOutput
                            current = transition(
                                current = current,
                                nextStatus = ModelTaskStatus.QUEUED,
                                stage = ModelTaskStage.PREPARING,
                                userMessage = "正在查阅资料",
                                provider = declaredProvider,
                            )
                            emit(current)
                            terminalEventSeen = true
                            return@onEach
                        }
                        current = applyGatewayEvent(
                            current = current,
                            event = event,
                            declaredProvider = declaredProvider,
                            enforceRemoteDispatchBudget = remoteDispatchWasReserved,
                        )
                        emit(current)
                        terminalEventSeen = event is ModelGatewayEvent.Completed ||
                            event is ModelGatewayEvent.Failed
                    }
                    .takeWhile { !terminalEventSeen }
                    .collect {}
            }
            if (!terminalEventSeen) {
                throw InvalidProviderProtocol("模型没有返回完成状态")
            }
            val requests = toolRound
            if (requests == null) {
                answered = true
            } else {
                toolRoundsUsed += 1
                if (toolRoundsUsed > TutorToolRoundResult.MAX_TOOL_ROUNDS) {
                    throw InvalidProviderProtocol("模型在工具配额用尽后仍未作答")
                }
                val authorization = tutorToolAuthorization(requests.intentDecision, declaredTools)
                val outcomes = requests.calls.map { call ->
                    if (call.tool !in authorization.allowedTools) {
                        TutorToolOutcome(
                            tool = call.tool,
                            ok = false,
                            summaryMarkdown = "该意图下未授权此查询。",
                            errorKind = "not_authorized",
                        )
                    } else {
                        toolRunner.run(call, toolContext(roundRequest.input, roundRequest.requestId))
                    }
                }
                toolRoundResults = toolRoundResults + TutorToolRoundResult(
                    roundOrdinal = toolRoundsUsed,
                    outcomes = outcomes,
                )
                // 收敛声明集：保留本轮已声明且仍允许的工具（非空），配额由轮次守卫保证。
                val converged = toolDeclarationsFor(roundRequest.input).toList()
                roundRequest = roundRequest.copy(
                    input = roundRequest.input.withToolRoundProgress(
                        newRounds = toolRoundResults,
                        convergedDeclarations = converged,
                    ),
                    egressManifest = roundRequest.egressManifest,
                )
            }
            }
        } catch (concurrent: ConcurrentModelTaskTransition) {
            database.readModelTask(request.requestId)?.let { latest -> emit(latest) }
        } catch (timeout: TimeoutCancellationException) {
            val latest = database.readModelTask(request.requestId) ?: throw timeout
            if (!latest.status.isTerminal && latest.status != ModelTaskStatus.RETRYABLE_FAILURE) {
                emit(
                    transitionRemoteFailure(
                        current = latest,
                        stage = latest.stage,
                        userMessage = "模型响应超时，任务已保留",
                        enforceRemoteDispatchBudget = remoteDispatchWasReserved,
                        failure = ModelTaskFailure(
                            code = ModelFailureCode.TIMEOUT,
                            message = "模型响应超时，可以稍后继续",
                            retryable = true,
                        ),
                    ),
                )
            }
        } catch (invalid: InvalidProviderProtocol) {
            val latest = database.readModelTask(request.requestId) ?: throw invalid
            if (!latest.status.isTerminal) {
                emit(
                    transition(
                        current = latest,
                        nextStatus = ModelTaskStatus.PERMANENT_FAILURE,
                        stage = ModelTaskStage.VALIDATING_OUTPUT,
                        userMessage = "这次内容无法使用，请重试",
                        failure = ModelTaskFailure(
                            code = ModelFailureCode.INVALID_RESPONSE,
                            message = invalid.userMessage,
                            retryable = false,
                        ),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val latest = database.readModelTask(request.requestId) ?: throw failure
            if (!latest.status.isTerminal && latest.status != ModelTaskStatus.RETRYABLE_FAILURE) {
                emit(
                    transitionRemoteFailure(
                        current = latest,
                        stage = latest.stage,
                        userMessage = "这次处理已保存，可以稍后重试",
                        enforceRemoteDispatchBudget = remoteDispatchWasReserved,
                        failure = ModelTaskFailure(
                            code = ModelFailureCode.UNKNOWN,
                            message = "模型暂时没有完成这项任务",
                            retryable = true,
                        ),
                    ),
                )
            }
        } finally {
            processActiveOperations.remove(operationFingerprint, ownerCompletion)
            ownerCompletion.complete(Unit)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun prepareForExecution(initial: ModelTaskSnapshot): ModelTaskSnapshot =
        when (initial.status) {
            ModelTaskStatus.WAITING_FOR_MODEL,
            ModelTaskStatus.RETRYABLE_FAILURE,
            ModelTaskStatus.RUNNING,
            ModelTaskStatus.STREAMING,
            -> transition(
                current = initial,
                nextStatus = ModelTaskStatus.QUEUED,
                stage = ModelTaskStage.PREPARING,
                userMessage = if (initial.attemptCount == 0) {
                    "正在准备"
                } else {
                    "正在从上次位置继续"
                },
            )
            ModelTaskStatus.QUEUED -> initial
            ModelTaskStatus.SUCCEEDED,
            ModelTaskStatus.PERMANENT_FAILURE,
            ModelTaskStatus.CANCELLED,
            -> initial
        }

    private suspend fun applyGatewayEvent(
        current: ModelTaskSnapshot,
        event: ModelGatewayEvent,
        declaredProvider: ProviderCapabilitySnapshot,
        enforceRemoteDispatchBudget: Boolean,
    ): ModelTaskSnapshot = when (event) {
        is ModelGatewayEvent.Started -> {
            if (current.status != ModelTaskStatus.RUNNING) {
                throw InvalidProviderProtocol("模型返回了重复或乱序的开始事件")
            }
            if (event.provider != declaredProvider) {
                throw InvalidProviderProtocol("模型身份或能力在任务开始后发生变化")
            }
            if (!event.provider.supports(current.request.input.kind)) {
                throw InvalidProviderProtocol("当前模型不支持这项任务")
            }
            if (
                current.request.input is CaptureAssessmentInput ||
                current.request.input is CaptureParseInput
            ) {
                if (!event.provider.supportsImageInput || !event.provider.supportsStructuredOutput) {
                    throw InvalidProviderProtocol("当前配置暂时无法处理这张题图")
                }
            }
            current
        }
        is ModelGatewayEvent.Progress -> {
            if (current.status !in setOf(ModelTaskStatus.RUNNING, ModelTaskStatus.STREAMING)) {
                throw InvalidProviderProtocol("模型在开始任务前返回了进度")
            }
            transition(
                current = current,
                nextStatus = ModelTaskStatus.STREAMING,
                stage = event.stage,
                userMessage = event.userMessage,
                provider = current.provider,
            )
        }
        is ModelGatewayEvent.Completed -> {
            if (current.status !in setOf(ModelTaskStatus.RUNNING, ModelTaskStatus.STREAMING)) {
                throw InvalidProviderProtocol("模型在开始任务前返回了完成内容")
            }
            val validationIssues = ModelTaskCompletionValidator.validate(
                request = current.request,
                output = event.output,
            )
            if (validationIssues.isNotEmpty()) {
                throw InvalidProviderProtocol("模型输出格式不符合当前题目任务")
            }
            transition(
                current = current,
                nextStatus = ModelTaskStatus.SUCCEEDED,
                stage = ModelTaskStage.COMPLETE,
                userMessage = when (current.request.input) {
                    is CaptureAssessmentInput -> "图片检查已完成，可以继续转写题面"
                    is CaptureParseInput -> "题面已准备好"
                    is ImagePipelineClassifyInput -> "题面分类已完成"
                    is TutorPlanInput -> "讲解已准备好"
                    is TutorLobbyInput -> "回复已准备好"
                    is com.tingyun.smartmistakebook.core.model.TutorDebriefInput -> "讲题要点已整理"
                    is TutorRespondInput -> "回复已准备好"
                    is com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput ->
                        "图形讲解已准备好"
                    is com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput ->
                        "图形讲解已复核"
                    is ProblemOrganizationInput -> "分类和题目联系建议已生成，请确认后再保存"
                },
                provider = current.provider,
                output = event.output,
            )
        }
        is ModelGatewayEvent.Failed -> {
            if (current.status !in setOf(
                    ModelTaskStatus.RUNNING,
                    ModelTaskStatus.STREAMING,
                )
            ) {
                throw InvalidProviderProtocol("模型返回了乱序的失败事件")
            }
            transitionRemoteFailure(
                current = current,
                stage = current.stage,
                userMessage = event.failure.message,
                provider = current.provider,
                enforceRemoteDispatchBudget = enforceRemoteDispatchBudget,
                failure = event.failure,
            )
        }
    }

    private suspend fun transitionRemoteFailure(
        current: ModelTaskSnapshot,
        stage: ModelTaskStage,
        userMessage: String,
        provider: ProviderCapabilitySnapshot? = current.provider,
        enforceRemoteDispatchBudget: Boolean,
        failure: ModelTaskFailure,
    ): ModelTaskSnapshot {
        val dispatchBudgetExhausted = failure.retryable && enforceRemoteDispatchBudget &&
            !ModelTaskRemoteDispatchPolicy.canSchedule(current.attemptCount)
        val mayRetry = failure.retryable && !dispatchBudgetExhausted
        return transition(
            current = current,
            nextStatus = if (mayRetry) {
                ModelTaskStatus.RETRYABLE_FAILURE
            } else {
                ModelTaskStatus.PERMANENT_FAILURE
            },
            stage = stage,
            userMessage = if (dispatchBudgetExhausted) {
                DISPATCH_LIMIT_USER_MESSAGE
            } else {
                userMessage
            },
            provider = provider,
            failure = if (mayRetry || !failure.retryable) {
                failure
            } else {
                failure.copy(retryable = false)
            },
        )
    }

    private suspend fun transition(
        current: ModelTaskSnapshot,
        nextStatus: ModelTaskStatus,
        stage: ModelTaskStage,
        userMessage: String,
        attemptCount: Int = current.attemptCount,
        provider: com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot? = null,
        output: com.tingyun.smartmistakebook.core.model.ModelTaskOutput? = null,
        failure: ModelTaskFailure? = null,
    ): ModelTaskSnapshot = database.transitionModelTask(
        TransitionModelTaskCommand(
            taskId = current.taskId,
            expectedStateVersion = current.stateVersion,
            expectedStatus = current.status,
            nextStatus = nextStatus,
            stage = stage,
            userMessage = userMessage,
            attemptCount = attemptCount,
            provider = provider,
            output = output,
            failure = failure,
            occurredAtEpochMillis = maxOf(clock(), current.updatedAtEpochMillis),
        ),
    ).let { result ->
        if (!result.applied) throw ConcurrentModelTaskTransition()
        result.snapshot
    }

    private suspend fun dependencyFailure(
        request: ModelTaskRequest,
        declaredProvider: ProviderCapabilitySnapshot,
    ): ModelTaskFailure? {
        val input = request.input as? CaptureParseInput ?: return null
        val draft = database.readProblemDraft(input.draftId)
            ?: return invalidDependency("原题草稿已不存在，请重新录入")
        if (draft.currentRevision.revisionNumber != input.basisRevisionNumber) {
            return invalidDependency("题面已更新，请基于最新版本重新转写")
        }
        val draftSources = draft.sourceAssets.sortedBy { it.pageIndex }
        val requestedSources = input.sourceAssets.sortedBy { it.pageIndex }
        if (
            draftSources.size != requestedSources.size ||
            draftSources.zip(requestedSources).any { (draftPage, requestedPage) ->
                val source = draftPage.sourceAsset
                draftPage.pageIndex != requestedPage.pageIndex ||
                    source.sourceAssetId != requestedPage.assetId ||
                    source.contentSha256 != requestedPage.sha256 ||
                    source.width != requestedPage.width ||
                    source.height != requestedPage.height
            }
        ) {
            return invalidDependency("题图版本与转写任务不一致，请重新开始")
        }
        input.assessmentRequestIds.zip(requestedSources).forEach { (requestId, source) ->
            val draftSource = draftSources[source.pageIndex].sourceAsset
            val assessment = database.readModelTask(requestId)
                ?: return invalidDependency("请先完成每一页题图的范围检查")
            val assessmentInput = assessment.request.input as? CaptureAssessmentInput
                ?: return invalidDependency("题图检查任务类型不匹配")
            val assessmentOutput = assessment.output as? CaptureAssessmentOutput
                ?: return invalidDependency("仍有题图页面尚未检查完成")
            if (assessment.provider?.isDemo != declaredProvider.isDemo) {
                return invalidDependency("演示任务不能授权真实题图处理，请重新检查题图")
            }
            val decision = assessmentOutput.assessment.decision
            val pageAccepted = decision == CaptureAssessmentDecision.PASS ||
                (decision == CaptureAssessmentDecision.NEED_MORE_IMAGE &&
                    source.pageIndex < requestedSources.lastIndex)
            if (
                assessment.status != ModelTaskStatus.SUCCEEDED ||
                !pageAccepted ||
                assessmentInput.draftId != input.draftId ||
                assessmentInput.sourceAssetId != source.assetId ||
                assessmentInput.imageWidth != source.width ||
                assessmentInput.imageHeight != source.height ||
                assessment.request.occurredAtEpochMillis != draftSource.createdAtEpochMillis
            ) {
                return invalidDependency("题图检查未通过，暂不开始结构化转写")
            }
        }
        return null
    }

    private fun capabilityFailure(
        request: ModelTaskRequest,
        provider: ProviderCapabilitySnapshot,
    ): ModelTaskFailure? {
        if (provider.providerId == UNCONFIGURED_PROVIDER_ID) return null
        if (!provider.supports(request.input.kind)) {
            return ModelTaskFailure(
                code = ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
                message = "当前模型不支持这项任务",
                retryable = false,
            )
        }
        if (
            (request.input is CaptureAssessmentInput || request.input is CaptureParseInput) &&
            (!provider.supportsImageInput || !provider.supportsStructuredOutput)
        ) {
            return ModelTaskFailure(
                code = ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
                message = "当前配置暂时无法处理这张题图",
                retryable = false,
            )
        }
        return null
    }

    private fun invalidDependency(message: String) = ModelTaskFailure(
        code = ModelFailureCode.PROVIDER_REJECTED_INPUT,
        message = message,
        retryable = false,
    )

    private fun exhaustedDispatchFailure(previous: ModelTaskFailure?): ModelTaskFailure =
        previous?.copy(retryable = false) ?: ModelTaskFailure(
            code = ModelFailureCode.UNKNOWN,
            message = "这次处理已达到重试次数上限，未再次连接模型",
            retryable = false,
        )

    private fun stableTaskId(requestId: String): String = "model-task-" +
        MessageDigest.getInstance("SHA-256")
            .digest(requestId.toByteArray(StandardCharsets.UTF_8))
            .take(16)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /** Per-kind tool declarations (spec §2): respond 全套；lobby 无科目上下文，不含 knowledge_read。 */
    private fun toolDeclarationsFor(input: ModelTaskInput): Set<TutorToolName> = when (input) {
        is TutorRespondInput -> setOf(
            TutorToolName.KNOWLEDGE_READ,
            TutorToolName.NOTEBOOK_READ,
            TutorToolName.MASTERY_READ,
            TutorToolName.MASTERY_UPDATE,
        )
        is TutorLobbyInput -> setOf(TutorToolName.NOTEBOOK_READ, TutorToolName.MASTERY_READ)
        else -> emptySet()
    }

    /** 把已执行的工具轮结果与收敛声明集写回输入，供下一轮派遣携带（spec §3.4）。 */
    private fun ModelTaskInput.withToolRoundProgress(
        newRounds: List<TutorToolRoundResult>,
        convergedDeclarations: List<TutorToolName>,
    ): ModelTaskInput = when (this) {
        is TutorLobbyInput -> copy(
            toolRoundResults = newRounds,
            toolDeclarations = convergedDeclarations,
        )
        is TutorRespondInput -> copy(
            toolRoundResults = newRounds,
            toolDeclarations = convergedDeclarations,
        )
        else -> this
    }


    private fun toolContext(input: ModelTaskInput, requestId: String): RoomTutorToolRunner.Context =
        RoomTutorToolRunner.Context(
            subject = (input as? TutorRespondInput)?.subject,
            // 会话锚：讲题会话的 conversationId 由 sessionId 确定性推导
            // （CapturedTutorSessionRoute 的 CreateTutorConversationCommand 同规则），
            // 供 MASTERY_UPDATE 的冷却/配额/审计按会话粒度工作。
            conversationId = (input as? TutorRespondInput)?.sessionId
                ?.let { "tutor-conv:captured:$it" },
            // 幂等命名空间：同一 model-task request 的重试/多轮共享同一 evidenceId 命名空间，
            // 让 MASTERY_UPDATE 的 evidence_id 确定性派生（重试不重复落库）。
            evidenceIdNamespace = requestId,
        )
}

object ModelTaskRepositoryFactory {
    fun create(
        database: StudyDatabasePort,
        gateway: ModelGateway,
    ): ModelTaskRepository = RoomModelTaskRepository(database = database, gateway = gateway)
}

private class ConcurrentModelTaskTransition : RuntimeException()

private class InvalidProviderProtocol(val userMessage: String) : RuntimeException(userMessage)

private const val MODEL_TASK_TIMEOUT_MILLIS = 120_000L
private const val MAX_GATEWAY_EVENTS = 64
private const val UNCONFIGURED_PROVIDER_ID = "unconfigured"
private const val DISPATCH_LIMIT_USER_MESSAGE = "这次处理未能完成，请重新开始"
private val processActiveOperations = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
