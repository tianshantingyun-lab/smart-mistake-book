package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import kotlinx.coroutines.flow.Flow

internal data class ModelTaskSessionFailure(
    val code: String,
    val message: String,
    val retryable: Boolean,
) {
    init {
        code.requireSessionIdentifier("Model task failure code")
        require(message.length <= MAX_MODEL_SESSION_MESSAGE_CHARS) {
            "Model task failure message exceeds its session budget"
        }
    }
}

internal data class ModelTaskSessionSnapshot(
    val scope: SessionScope,
    val taskId: String,
    val operation: SessionOperationIdentity,
    val version: SessionVersion,
    val subjectId: String,
    val kind: ModelTaskKind,
    val status: ModelTaskStatus,
    val stage: ModelTaskStage,
    val userMessage: String,
    val attemptCount: Int,
    val requestPayload: SessionOpaquePayload,
    val providerPayload: SessionOpaquePayload?,
    val outputPayload: SessionOpaquePayload?,
    val failure: ModelTaskSessionFailure?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        taskId.requireSessionIdentifier("Model task id")
        subjectId.requireSessionIdentifier("Model task subject id")
        require(userMessage.length <= MAX_MODEL_SESSION_MESSAGE_CHARS) {
            "Model task user message exceeds its session budget"
        }
        require(attemptCount >= 0) { "Model task attempt count must not be negative" }
        require(createdAtEpochMillis >= 0 && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Model task times are invalid"
        }
        require(status != ModelTaskStatus.SUCCEEDED || outputPayload != null) {
            "A successful model task session requires an opaque output payload"
        }
        require(
            status != ModelTaskStatus.RETRYABLE_FAILURE &&
                status != ModelTaskStatus.PERMANENT_FAILURE || failure != null,
        ) { "A failed model task session requires failure state" }
    }
}

internal data class ModelTaskSessionReadQuery(
    val scope: SessionScope,
    val requestId: String,
) {
    init {
        requestId.requireSessionIdentifier("Model task request id")
    }
}

internal data class RecentModelTaskSessionQuery(
    val scope: SessionScope,
    val subjectId: String,
    val kind: ModelTaskKind,
    val limit: Int,
) {
    init {
        subjectId.requireSessionIdentifier("Model task subject id")
        require(limit in 1..MAX_RECENT_MODEL_TASKS) {
            "Recent model task limit is outside its session budget"
        }
    }
}

internal data class CreateModelTaskSessionCommand(
    val scope: SessionScope,
    val operation: SessionOperationIdentity,
    val taskId: String,
    val subjectId: String,
    val kind: ModelTaskKind,
    val requestPayload: SessionOpaquePayload,
    val occurredAtEpochMillis: Long,
) {
    init {
        taskId.requireSessionIdentifier("Model task id")
        subjectId.requireSessionIdentifier("Model task subject id")
        require(occurredAtEpochMillis >= 0) { "Model task creation time must not be negative" }
    }
}

internal sealed interface ModelTaskSessionMutation {
    val scope: SessionScope
    val operation: SessionOperationIdentity
    val taskId: String
    val expectedVersion: SessionVersion
    val expectedStatus: ModelTaskStatus
    val occurredAtEpochMillis: Long

    data class ReserveExternalDispatch(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val taskId: String,
        override val expectedVersion: SessionVersion,
        override val expectedStatus: ModelTaskStatus,
        override val occurredAtEpochMillis: Long,
        val providerPayload: SessionOpaquePayload,
    ) : ModelTaskSessionMutation

    data class Transition(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val taskId: String,
        override val expectedVersion: SessionVersion,
        override val expectedStatus: ModelTaskStatus,
        override val occurredAtEpochMillis: Long,
        val nextStatus: ModelTaskStatus,
        val stage: ModelTaskStage,
        val userMessage: String,
        val attemptCount: Int,
        val providerPayload: SessionOpaquePayload? = null,
        val outputPayload: SessionOpaquePayload? = null,
        val failure: ModelTaskSessionFailure? = null,
    ) : ModelTaskSessionMutation {
        init {
            require(userMessage.length <= MAX_MODEL_SESSION_MESSAGE_CHARS) {
                "Model task user message exceeds its session budget"
            }
            require(attemptCount >= 0) { "Model task attempt count must not be negative" }
            require(nextStatus != ModelTaskStatus.SUCCEEDED || outputPayload != null) {
                "A successful model task transition requires an opaque output payload"
            }
            require(
                nextStatus != ModelTaskStatus.RETRYABLE_FAILURE &&
                    nextStatus != ModelTaskStatus.PERMANENT_FAILURE || failure != null,
            ) { "A failed model task transition requires failure state" }
        }
    }
}

internal data class ModelTaskSessionMutationResult(
    val receipt: SessionMutationReceipt,
    val snapshot: ModelTaskSessionSnapshot?,
    val dispatchBudgetExhausted: Boolean = false,
    val logicalDispatchCount: Int = 0,
) {
    init {
        require(logicalDispatchCount >= 0) {
            "Logical model dispatch count must not be negative"
        }
        require(!dispatchBudgetExhausted || receipt.disposition != SessionMutationDisposition.APPLIED) {
            "An applied dispatch cannot also be budget-exhausted"
        }
    }
}

internal interface ModelTaskSessionPort {
    fun observe(query: ModelTaskSessionReadQuery): Flow<ModelTaskSessionSnapshot?>

    fun observeRecent(
        query: RecentModelTaskSessionQuery,
    ): Flow<List<ModelTaskSessionSnapshot>>

    suspend fun read(query: ModelTaskSessionReadQuery): ModelTaskSessionSnapshot?

    suspend fun create(
        command: CreateModelTaskSessionCommand,
    ): ModelTaskSessionMutationResult

    suspend fun mutate(
        command: ModelTaskSessionMutation,
    ): ModelTaskSessionMutationResult
}

internal const val MAX_MODEL_SESSION_MESSAGE_CHARS = 500
private const val MAX_RECENT_MODEL_TASKS = 100
