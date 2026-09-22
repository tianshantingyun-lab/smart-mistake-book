package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.database.CreateModelTaskCommand
import com.tingyun.smartmistakebook.core.database.ReserveModelTaskRemoteDispatchCommand
import com.tingyun.smartmistakebook.core.domain.TUTOR_TOOL_DECLARATIONS
import com.tingyun.smartmistakebook.core.model.disclosesQuestionCandidates
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeCode
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import com.tingyun.smartmistakebook.core.data.study.TutorKnowledgeCodeRegistry
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.TransitionModelTaskCommand
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.TutorConversationIds
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
import com.tingyun.smartmistakebook.core.model.ModelLiveKind
import com.tingyun.smartmistakebook.core.model.ModelLiveText
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
import com.tingyun.smartmistakebook.core.model.tutorToolRoundResult
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
import com.tingyun.smartmistakebook.core.model.TutorToolCall
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout

class RoomModelTaskRepository internal constructor(
    private val database: StudyDatabasePort,
    private val gateway: ModelGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelTaskRepository {
    internal val toolRunner = RoomTutorToolRunner(database)

    /**
     * 会话级知识点代号注册表（单一代号通道，ADR 0001 / D5）：sessionId → 注册表，进程内
     * 内存。Plan/Respond 的 execute() 入口先对输入赋码（持久化形状即赋码形状），工具环的
     * MASTERY_UPDATE 白名单与 runner 的代号解析都读同一份。大厅没有科目上下文与预披露节点，
     * 不建注册表（其 MASTERY_UPDATE 白名单为空 → 任何代号结构性拒）。
     */
    private val knowledgeCodeRegistries = ConcurrentHashMap<String, TutorKnowledgeCodeRegistry>()

    /**
     * 生成中的逐 token 实时文本，只存在内存里：它是"此刻屏幕上该显示什么"，不是事实来源，
     * 所以既不落库也不写审计行（那两样是持久化进度帧的代价，也是实时文本此前必须稀疏的原因）。
     */
    private val liveTexts = MutableStateFlow<Map<String, ModelLiveText>>(emptyMap())

    override suspend fun capabilities(): ProviderCapabilitySnapshot = gateway.capabilities()

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> =
        database.observeModelTask(requestId)

    override fun observeLiveText(requestId: String): Flow<ModelLiveText?> =
        liveTexts.map { texts -> texts[requestId] }.distinctUntilChanged()

    private fun publishLiveText(requestId: String, text: ModelLiveText) {
        liveTexts.update { current -> current + (requestId to text) }
    }

    private fun clearLiveText(requestId: String) {
        liveTexts.update { current -> current - requestId }
    }

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
        // 单一代号通道（D5）：Plan/Respond 输入的预披露条目（派发方给的未赋码条目，或重试
        // 时存库行带回的已赋码条目）在此统一过会话注册表——首现顺序分配 K1..Kn、会话内
        // 稳定，教学参考的代号字段一并填上。赋码后的形状才是持久化与指纹的形状。
        val request = request.withSessionKnowledgeCodes()
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
            // 每轮派遣独立 reserve（预算记账，MAX_DISPATCHES=6 = 5 轮工具 + 1 轮终答，
            // 见 a4109e1 与 D-001 2026-09-09 增补）；本地工具执行不占派遣预算。
            // 工具轮配额用尽（MAX_TOOL_ROUNDS=5）后不再声明工具，模型必须直接作答。
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
                is ModelExecutionPermit.External,
                ModelExecutionPermit.ProviderConsented,
                -> {
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
                        // 逐 token 实时文本不计入事件预算：它不落库、不写审计行，正是为了让
                        // "每个增量都能到屏幕"成为可能；计数只约束真正要落盘的进度与终态。
                        if (event !is ModelGatewayEvent.LiveProgress) {
                            eventCount += 1
                            if (eventCount > MAX_GATEWAY_EVENTS) {
                                throw InvalidProviderProtocol("模型返回了过多状态事件")
                            }
                        } else {
                            // 实时文本不算状态变化：发布到内存通道后直接返回，不发快照也不落库。
                            // 发快照的代价很具体——一帧一次 UI 重组，几百帧就是几百次白重组，
                            // 而屏幕上要显示什么由 liveTexts 那条通道单独驱动。
                            if (!providerStarted) {
                                throw InvalidProviderProtocol("模型在开始任务前返回了内容")
                            }
                            publishLiveText(
                                current.request.requestId,
                                ModelLiveText(event.kind, event.text),
                            )
                            return@onEach
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
                            is ModelGatewayEvent.LiveProgress,
                            is ModelGatewayEvent.Failed,
                            -> Unit
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
                // 工具调用要看得见：学生此前完全看不到"正在查阅错题本"这类过程（工具环只把
                // 结果塞进下一轮提示词，界面上一片安静），于是工具调用看起来"没有接进来"。
                val toolLabels = requests.calls.map { call -> call.tool.liveLabel() }.distinct()
                publishLiveText(
                    request.requestId,
                    ModelLiveText(
                        kind = ModelLiveKind.TOOL,
                        text = "正在查阅${toolLabels.joinToString("、")}…",
                    ),
                )
                // 扩展结果预算每轮只放一次：一次读工具的结果最多 6k 字符，三个并发请求会在下一轮
                // prompt 里堆到 18k。模型仍可对每次查询表达"需要更大预算"（语义），但放大几次由本地
                // 定——与本项目"模型给语义、本地给数值"的划分一致。
                var extendedResultUsed = false
                // 单一代号通道（D5）：MASTERY_UPDATE 的 enum 白名单 = 本会话已披露代号集。
                // Route A 的 schema 约束解码是前哨，这里是 Route B（json_object 信封）与
                // 越界复述的背底：非法/编造/未披露代号结构性拒，不进执行器、不进门。
                val disclosedKnowledgeCodes = sessionKnowledgeCodeRegistry(roundRequest.input)
                    ?.disclosedCodes()
                    .orEmpty()
                val outcomes = tutorToolRoundOutcomes(
                    calls = requests.calls,
                    authorizedTools = authorization.allowedTools,
                    disclosedKnowledgeCodes = disclosedKnowledgeCodes,
                    runTool = { call, allowsExtendedResult ->
                        toolRunner.run(
                            call,
                            toolContext(
                                roundRequest.input,
                                roundRequest.requestId,
                                allowsExtendedResult,
                            ),
                        )
                    },
                    consumeExtendedResult = { extendedResultUsed = true },
                )
                toolRoundResults = toolRoundResults + tutorToolRoundResult(
                    roundOrdinal = toolRoundsUsed,
                    outcomes = outcomes,
                    extendedResultUsed = extendedResultUsed,
                )
                publishLiveText(
                    request.requestId,
                    ModelLiveText(
                        kind = ModelLiveKind.TOOL,
                        text = "已查阅${toolLabels.joinToString("、")}" +
                            "（${outcomes.count { outcome -> outcome.ok }} 条结果）",
                    ),
                )
                // 收敛声明集：保留本轮已声明且仍允许的工具（非空），配额由轮次守卫保证。
                val converged = toolDeclarationsFor(roundRequest.input).toList()
                // KNOWLEDGE_READ 本轮追加披露的节点已进注册表：下一轮输入带**全会话**披露集
                // （只增不减），映射表才能在前缀区保持稳定、模型引用的 K6 在下一轮仍可见。
                val sessionKnowledgeCodes = sessionKnowledgeCodeRegistry(roundRequest.input)?.disclosed()
                roundRequest = roundRequest.copy(
                    input = roundRequest.input.withToolRoundProgress(
                        newRounds = toolRoundResults,
                        convergedDeclarations = converged,
                        sessionKnowledgeCodes = sessionKnowledgeCodes,
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
        is ModelGatewayEvent.LiveProgress -> {
            // 实时文本不改变任务状态：发布与"不发快照"都在 onEach 里完成，这里只保证状态机
            // 对这类事件是显式的无变化（走到这里说明它被当成了状态事件，状态本身仍不动）。
            current
        }
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
                    is com.tingyun.smartmistakebook.core.model.KnowledgeQuizInput -> "复习题已准备好"
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
        // 终态（含可重试失败）到了：内存里的实时文本已由落库正文接管，留着只会在下一次
        // 派发前闪出上一条的残留。
        if (nextStatus.isTerminal || nextStatus == ModelTaskStatus.RETRYABLE_FAILURE) {
            clearLiveText(current.request.requestId)
        }
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
        val lobbyInput = request.input as? TutorLobbyInput
        if (
            lobbyInput != null &&
            lobbyInput.sourceImageAssetRefs.isNotEmpty() &&
            !provider.supportsImageInput
        ) {
            return ModelTaskFailure(
                code = ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
                message = "当前模型不支持看图，请先更换支持图片的模型。",
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

    /**
     * 同页声明**全量五个**工具（`docs/tutor-surface-unification.md` §5.6；2026-09-21 裁定
     * D7/D8 把 Plan 也纳入）：智能体页所有模型调用（Plan / Respond / 大厅）同一工具面，
     * 声明集按页面给，**代码零场景分叉**。
     *
     * 声明全量不等于放行全量——逐次裁决只剩两条，都取自模型自己这一轮的语义输出：意图授权
     * 矩阵（意图 × 置信度 × 声明集）与 MASTERY_UPDATE 的代号白名单（本会话已披露集合）。
     * 无题轮不再结构性拒写（D6）：写不写由模型语义判定，低置信/无引文写入由统一本地门挡。
     */
    internal fun toolDeclarationsFor(input: ModelTaskInput): Set<TutorToolName> = when (input) {
        is TutorPlanInput, is TutorRespondInput, is TutorLobbyInput -> TUTOR_TOOL_DECLARATIONS
        else -> emptySet()
    }

    /**
     * 把已执行的工具轮结果、收敛声明集与会话代号披露集写回输入，供下一轮派遣携带（spec §3.4）。
     *
     * [sessionKnowledgeCodes] 为 null = 该输入种类没有代号通道（大厅）；非 null 时以注册表的
     * 全会话披露集**整体替换**输入的 knowledgeCodes（只增不减：工具发现的新节点也带着它们的
     * 代号进下一轮，模型引用的代号永远查得到映射表）。
     */
    private fun ModelTaskInput.withToolRoundProgress(
        newRounds: List<TutorToolRoundResult>,
        convergedDeclarations: List<TutorToolName>,
        sessionKnowledgeCodes: List<TutorKnowledgeCode>?,
    ): ModelTaskInput = when (this) {
        is TutorLobbyInput -> copy(
            toolRoundResults = newRounds,
            toolDeclarations = convergedDeclarations,
        )
        is TutorPlanInput -> copy(
            toolRoundResults = newRounds,
            toolDeclarations = convergedDeclarations,
            knowledgeCodes = sessionKnowledgeCodes ?: this.knowledgeCodes,
        )
        is TutorRespondInput -> copy(
            toolRoundResults = newRounds,
            toolDeclarations = convergedDeclarations,
            knowledgeCodes = sessionKnowledgeCodes ?: this.knowledgeCodes,
        )
        else -> this
    }

    /** 该输入的会话代号注册表；大厅没有科目上下文与预披露节点，不建（白名单为空）。 */
    private fun sessionKnowledgeCodeRegistry(input: ModelTaskInput): TutorKnowledgeCodeRegistry? = when (input) {
        is TutorPlanInput -> knowledgeCodeRegistries[input.sessionId]
        is TutorRespondInput -> knowledgeCodeRegistries[input.sessionId]
        else -> null
    }

    /**
     * 派生前对 Plan/Respond 输入做代号赋码（[TutorKnowledgeCodeRegistry.adopt]：已赋码条目
     * 按原码登记、未赋码条目按列表顺序首次分配），并把教学参考的代号字段填上（材料绑定多个
     * 节点时取其中已披露的第一个）。返回的才是持久化 / 指纹 / 提示词用的形状。
     */
    private fun ModelTaskRequest.withSessionKnowledgeCodes(): ModelTaskRequest {
        val input = this.input
        val sessionId = when (input) {
            is TutorPlanInput -> input.sessionId
            is TutorRespondInput -> input.sessionId
            else -> return this
        }
        val registry = knowledgeCodeRegistries.getOrPut(sessionId) { TutorKnowledgeCodeRegistry() }
        return when (input) {
            is TutorPlanInput -> copy(
                input = input.copy(
                    knowledgeCodes = registry.adopt(input.knowledgeCodes),
                    reviewedTeachingReferences = input.reviewedTeachingReferences
                        .withSessionCodes(registry),
                ),
            )
            is TutorRespondInput -> copy(
                input = input.copy(
                    knowledgeCodes = registry.adopt(input.knowledgeCodes),
                    reviewedTeachingReferences = input.reviewedTeachingReferences
                        .withSessionCodes(registry),
                ),
            )
            else -> this
        }
    }

    private fun List<TutorTeachingReference>.withSessionCodes(
        registry: TutorKnowledgeCodeRegistry,
    ): List<TutorTeachingReference> = map { reference ->
        val code = reference.code ?: reference.knowledgeNodeIds
            .firstNotNullOfOrNull { nodeId -> registry.codeFor(nodeId) }
        if (code == null) reference else reference.copy(code = code)
    }


    private fun toolContext(
        input: ModelTaskInput,
        requestId: String,
        allowsExtendedResult: Boolean,
    ): RoomTutorToolRunner.Context {
        // Plan 与 Respond 同一工具环（D8）：会话锚、科目上下文按同一规则取；大厅没有
        // sessionId/subject，相关上下文保持 null（runner 按 no_subject / no_conversation
        // 的既有边界失败关闭）。
        val respond = input as? TutorRespondInput
        val plan = input as? TutorPlanInput
        val sessionId = respond?.sessionId ?: plan?.sessionId
        return RoomTutorToolRunner.Context(
            subject = respond?.subject ?: plan?.subject,
            // 扩展结果预算的轮内裁决：见 execute 里每轮只放一次的守卫。
            allowsExtendedResult = allowsExtendedResult,
            // 会话锚：讲题会话的 conversationId 由 sessionId 确定性推导
            // （CapturedTutorSessionRoute 的 CreateTutorConversationCommand 同规则），
            // 供 MASTERY_UPDATE 的冷却/配额/审计按会话粒度工作。
            conversationId = sessionId?.let(TutorConversationIds::captured),
            // 裸 sessionId：NOTEBOOK_WRITE 用它 resolve 对应的 capture draft。
            // sessionId（"tutor-session-..."）≠ draftId（"draft-..."），写路径需
            // readTutorSession(sessionId) 拿 draftId 再 readProblemDraft(draftId)。
            // MASTERY_UPDATE 的客观交叉核对也用它回读本轮检查题作答。
            tutorSessionId = sessionId,
            // 客观交叉核对只数当前轮：新一轮重教时上一轮的答错不该永久作废正向判断。
            cycleOrdinal = respond?.cycleOrdinal ?: plan?.cycleOrdinal ?: 1,
            // 幂等命名空间：同一 model-task request 的重试/多轮共享同一 evidenceId 命名空间，
            // 让 MASTERY_UPDATE 的 evidence_id 确定性派生（重试不重复落库）。
            evidenceIdNamespace = requestId,
            // 本轮披露集合是否覆盖候选菜单：NOTEBOOK_READ 的产出形态由它决定——覆盖了才允许
            // 逐条点名别的题（那属于已披露的 RELATED_QUESTION_CANDIDATES），否则只给条数与检索词。
            // 判据取自请求本身（与清单侧核对 includesQuestionCandidates 用的是同一条），
            // 不看解析路由、不看执行位置。
            roundDisclosesQuestionCandidates = input.disclosesQuestionCandidates(),
            // 会话代号注册表：KNOWLEDGE_READ 的追加披露与 MASTERY_UPDATE 的代号解析都走它。
            knowledgeCodeRegistry = sessionKnowledgeCodeRegistry(input),
        )
    }
}

object ModelTaskRepositoryFactory {
    fun create(
        database: StudyDatabasePort,
        gateway: ModelGateway,
    ): ModelTaskRepository = RoomModelTaskRepository(
        database = database,
        gateway = gateway,
    )
}

/**
 * 工具环里**一轮工具调用**的判定与执行，整体抽出来是为了可测：这一段长在 `execute()` 里
 * 只有仪器化用例够得着，而"被拒的调用不触达执行器"的接线此前没有任何本机可跑的用例钉住
 * （复核意见二），所以判定与执行一起抽成具名函数。
 *
 * 2026-09-21 裁定（ADR 0001 / D6/D7）之后，这里**没有场景维度**——不判"这一轮来自哪个入口"，
 * 也不判"这一轮有没有题"（无题轮不再结构性拒写；写不写由模型语义判定，系统提示词教会，
 * 低置信/无引文的写入由统一本地门 MasteryWriteGate 挡）。逐次裁决只剩两条，都取自
 * **调用本身**：
 * - 意图授权矩阵：[authorizedTools]（模型这一轮自己的意图 × 置信度 × 声明集，
 *   [com.tingyun.smartmistakebook.core.model.tutorToolAuthorization] 的产物）；
 * - 单一代号通道（D5）：MASTERY_UPDATE 的 `terms[0]` 必须在本会话已披露代号集合
 *   （[disclosedKnowledgeCodes]）内——非法/编造/未披露代号结构性拒，走协议错误路径
 *   （[INVALID_KNOWLEDGE_CODE]），不进执行器、不进门。Route A 的 schema enum 约束解码
 *   是前哨，这里是 Route B（json_object 信封）与越界复述的背底。
 *
 * 其余读工具（含 MASTERY_READ / KNOWLEDGE_READ）不受限（D7：MASTERY_READ 无场景分支，
 * 输出形态/轮预算按旧裁定不变）；没有科目上下文的边界由 runner 自己失败关闭
 * （no_subject），不在轮次层分叉。被拒的调用**不会**触达 [runTool]。
 *
 * @param consumeExtendedResult 调用方在"一次扩展结果预算被用掉"时调用；同一轮只放一次。
 */
internal suspend fun tutorToolRoundOutcomes(
    calls: List<TutorToolCall>,
    authorizedTools: Set<TutorToolName>,
    disclosedKnowledgeCodes: Set<String>,
    runTool: suspend (TutorToolCall, Boolean) -> TutorToolOutcome,
    consumeExtendedResult: () -> Unit,
): List<TutorToolOutcome> {
    var extendedResultUsed = false
    return calls.map { call ->
        when {
            call.tool !in authorizedTools -> TutorToolOutcome(
                tool = call.tool,
                ok = false,
                summaryMarkdown = "该意图下未授权此查询。",
                errorKind = "not_authorized",
            )
            call.tool == TutorToolName.MASTERY_UPDATE &&
                call.terms.firstOrNull() !in disclosedKnowledgeCodes -> TutorToolOutcome(
                tool = call.tool,
                ok = false,
                summaryMarkdown = "该代号不在本会话已披露的知识点中，未执行。",
                errorKind = INVALID_KNOWLEDGE_CODE,
            )
            else -> {
                val allowsExtendedResult = !extendedResultUsed
                val outcome = runTool(call, allowsExtendedResult)
                if (call.extendedResult && allowsExtendedResult) {
                    extendedResultUsed = true
                    consumeExtendedResult()
                }
                outcome
            }
        }
    }
}

/** MASTERY_UPDATE 的代号不在本会话已披露集合：协议层结构性拒（不是门控语义拒）。 */
internal const val INVALID_KNOWLEDGE_CODE = "invalid_knowledge_code"

private class ConcurrentModelTaskTransition : RuntimeException()

/** 工具在实时状态里的短标签（学生看得懂的说法，不是内部工具名）。 */
private fun TutorToolName.liveLabel(): String = when (this) {
    TutorToolName.KNOWLEDGE_READ -> "知识点"
    TutorToolName.NOTEBOOK_READ -> "错题本"
    TutorToolName.MASTERY_READ -> "掌握情况"
    TutorToolName.NOTEBOOK_WRITE -> "错题本"
    TutorToolName.MASTERY_UPDATE -> "掌握记录"
}

private class InvalidProviderProtocol(val userMessage: String) : RuntimeException(userMessage)

private const val MODEL_TASK_TIMEOUT_MILLIS = 120_000L
private const val MAX_GATEWAY_EVENTS = 64
private const val UNCONFIGURED_PROVIDER_ID = "unconfigured"
private const val DISPATCH_LIMIT_USER_MESSAGE = "这次处理未能完成，请重新开始"
private val processActiveOperations = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
