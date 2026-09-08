package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorSendAction
import com.tingyun.smartmistakebook.core.domain.TutorSendState
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorTurnSendStateMachine
import com.tingyun.smartmistakebook.core.model.ActionType
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.AppFailure
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.AppFailureCode
import com.tingyun.smartmistakebook.core.model.Retryability
import com.tingyun.smartmistakebook.core.model.appFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun tutorRespondInProgressError(): AppFailure = appFailure(
    code = AppFailureCode.DISPATCH_BUDGET_EXHAUSTED,
    title = TUTOR_RESPOND_IN_PROGRESS_TITLE,
    message = TUTOR_RESPOND_IN_PROGRESS_MESSAGE,
    dataPreserved = true,
    retryability = Retryability.RETRYABLE,
    primaryAction = ActionType.RETRY,
)

internal fun tutorRespondLimitError(): AppFailure = appFailure(
    code = AppFailureCode.DISPATCH_BUDGET_EXHAUSTED,
    title = TUTOR_RESPOND_LIMIT_TITLE,
    message = TUTOR_RESPOND_LIMIT_MESSAGE,
    dataPreserved = true,
    retryability = Retryability.RETRYABLE,
    primaryAction = ActionType.RETRY,
)

internal fun tutorRespondValidationError(): AppFailure = appFailure(
    code = AppFailureCode.VALIDATION_FAILED,
    title = TUTOR_RESPOND_VALIDATION_TITLE,
    message = TUTOR_RESPOND_VALIDATION_MESSAGE,
    dataPreserved = true,
)

internal fun tutorRespondNetworkError(): AppFailure = appFailure(
    code = AppFailureCode.NETWORK_UNAVAILABLE,
    title = TUTOR_RESPOND_NETWORK_TITLE,
    message = TUTOR_RESPOND_NETWORK_MESSAGE,
    dataPreserved = true,
    retryability = Retryability.RETRYABLE,
    primaryAction = ActionType.RETRY,
)

internal class TutorRespondCommands(
    private val scope: CoroutineScope,
    private val sink: TutorRespondSink,
) {
    fun collect(
        request: ModelTaskRequest,
        clearDraftOnPersist: Boolean,
        allowExternalEnvelopeForLocalRecovery: Boolean = false,
        isRetry: Boolean = false,
    ) {
        val provider = sink.currentProvider() ?: return
        if (
            !tutorRespondCollectCanStart(
                provider = provider,
                consentEnabled = sink.consentEnabled(),
                requestHasEgressManifest = request.egressManifest != null,
                allowExternalEnvelopeForLocalRecovery = allowExternalEnvelopeForLocalRecovery,
                chatSubmitPending = sink.chatSubmitPending(),
            )
        ) {
            return
        }
        val logicalOperationId = request.requestId
        val messageId = request.requestId
        when (
            val advance = tutorRespondSendAdvance(
                sendState = sink.tutorSendState(),
                logicalOperationId = logicalOperationId,
                messageId = messageId,
                isRetry = isRetry,
            )
        ) {
            TutorRespondSendAdvance.InProgressBudgetExhausted -> {
                sink.setChatStartError(tutorRespondInProgressError())
                return
            }
            TutorRespondSendAdvance.DispatchLimitReached -> {
                sink.setChatStartError(tutorRespondLimitError())
                return
            }
            is TutorRespondSendAdvance.Ready -> sink.setTutorSendState(advance.nextState)
        }
        sink.setChatSubmitPending(true)
        sink.setLocallyStartedRespondRequestId(request.requestId)
        sink.setChatStartError(null)
        scope.launch {
            var persisted = false
            try {
                sink.modelTasks.execute(request).collect { snapshot ->
                    if (!persisted) {
                        persisted = true
                        if (clearDraftOnPersist) sink.setChatDraft("")
                    }
                    when (snapshot.status) {
                        ModelTaskStatus.SUCCEEDED -> sink.setTutorSendState(
                            TutorTurnSendStateMachine.reduce(
                                sink.tutorSendState(),
                                TutorSendAction.DispatchSucceeded(logicalOperationId, messageId),
                            ),
                        )
                        ModelTaskStatus.RETRYABLE_FAILURE -> sink.setTutorSendState(
                            TutorTurnSendStateMachine.reduce(
                                sink.tutorSendState(),
                                TutorSendAction.DispatchFailed(
                                    logicalOperationId,
                                    messageId,
                                    errorCode = snapshot.failure?.code?.name ?: "UNKNOWN",
                                    retryable = true,
                                ),
                            ),
                        )
                        ModelTaskStatus.PERMANENT_FAILURE,
                        ModelTaskStatus.CANCELLED,
                        -> sink.setTutorSendState(
                            TutorTurnSendStateMachine.reduce(
                                sink.tutorSendState(),
                                TutorSendAction.DispatchFailed(
                                    logicalOperationId,
                                    messageId,
                                    errorCode = snapshot.failure?.code?.name ?: "UNKNOWN",
                                    retryable = false,
                                ),
                            ),
                        )
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                sink.setChatStartError(tutorRespondNetworkError())
            } finally {
                sink.setChatSubmitPending(false)
            }
        }
    }

    fun execute(
        message: String,
        requestedMove: TutorMoveType? = null,
        clearDraftOnPersist: Boolean = false,
    ) {
        if (
            !tutorRespondExecuteCanStart(
                hasPlanOutput = sink.currentPlanOutput() != null,
                providerCanExecute = tutorRespondProviderCanExecute(sink.currentProvider()),
                messageBlank = message.isBlank(),
                chatSending = sink.chatSending(),
            )
        ) {
            return
        }
        val visiblePlan = sink.currentPlanOutput() ?: return
        val provider = sink.currentProvider() ?: return
        val question = sink.question()
        val respondTasks = sink.tutorRespondTasks()
        val lastResponseOrdinal = respondTasks.maxOfOrNull { task ->
            (task.request.input as? TutorRespondInput)?.responseOrdinal ?: 0
        } ?: 0
        val responseOrdinal = lastResponseOrdinal + 1
        val priorMessages = tutorChatHistory(
            respondTasks,
            answerExposureKeys = sink.answerExposureKeys(),
        )
        val currentInput = sink.currentInput()
        val visibleContext = visibleTutorContextMarkdown(
            visiblePlan,
            sink.currentResponse(),
            answerWasExposed = sink.observedTask().toPlanAnswerExposureKey() in sink.answerExposureKeys(),
        )
        val attempt = respondTasks.count { task ->
            (task.request.input as? TutorRespondInput)?.responseOrdinal == responseOrdinal
        }
        val requestId = tutorRespondRequestId(
            question = question,
            provider = provider,
            responseOrdinal = responseOrdinal,
            cycleOrdinal = currentInput.cycleOrdinal,
            turnOrdinal = currentInput.turnOrdinal,
            studentMessage = message,
            visibleTutorContextMarkdown = visibleContext,
            priorMessages = priorMessages,
            requestedMove = requestedMove,
            attempt = attempt,
        )
        val occurredAt = maxOf(
            sink.clock(),
            respondTasks.maxOfOrNull { it.createdAtEpochMillis + 1 } ?: 0L,
        )
        val request = try {
            buildTutorRespondRequest(
                question = question,
                profile = sink.profile(),
                provider = provider,
                requestId = requestId,
                occurredAtEpochMillis = occurredAt,
                responseOrdinal = responseOrdinal,
                cycleOrdinal = currentInput.cycleOrdinal,
                turnOrdinal = currentInput.turnOrdinal,
                studentMessage = message,
                visibleTutorContextMarkdown = visibleContext,
                priorMessages = priorMessages,
                requestedMove = requestedMove,
            )
        } catch (_: IllegalArgumentException) {
            sink.setChatStartError(tutorRespondValidationError())
            return
        }
        collect(
            request = request,
            clearDraftOnPersist = clearDraftOnPersist,
        )
    }

    fun retry(task: ModelTaskSnapshot) {
        if (!task.canRetryTutorResponse()) return
        val provider = sink.currentProvider()?.takeIf(::tutorRespondProviderCanExecute) ?: return
        val request = when (provider.executionLocation) {
            // 全局同意下，持久化的任务可直接重新 collect；gate 在 collect() 内再查一次。
            ModelExecutionLocation.EXTERNAL_PROVIDER -> task.request
            ModelExecutionLocation.LOCAL_NO_EGRESS -> if (
                task.request.egressManifest == null && task.matchesTutorProvider(provider)
            ) {
                task.request
            } else {
                return
            }
            ModelExecutionLocation.UNAVAILABLE -> return
        }
        if (sink.chatSubmitPending()) return
        collect(
            request = request,
            clearDraftOnPersist = false,
            isRetry = true,
        )
    }
}

internal class TutorRespondSink(
    val currentProvider: () -> ProviderCapabilitySnapshot?,
    val consentEnabled: () -> Boolean,
    val question: () -> TutorQuestionContext,
    val profile: () -> StudyProfileOverview,
    val clock: () -> Long,
    val chatSubmitPending: () -> Boolean,
    val setChatSubmitPending: (Boolean) -> Unit,
    val tutorSendState: () -> TutorSendState,
    val setTutorSendState: (TutorSendState) -> Unit,
    val setChatStartError: (AppFailure?) -> Unit,
    val setLocallyStartedRespondRequestId: (String?) -> Unit,
    val setChatDraft: (String) -> Unit,
    val currentPlanOutput: () -> TutorPlanOutput?,
    val currentResponse: () -> TutorTurnResponse?,
    val currentInput: () -> TutorPlanInput,
    val observedTask: () -> ModelTaskSnapshot,
    val tutorRespondTasks: () -> List<ModelTaskSnapshot>,
    val answerExposureKeys: () -> Set<TutorAnswerExposureKey>,
    val chatSending: () -> Boolean,
    val modelTasks: ModelTaskRepository,
)
