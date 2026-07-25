package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskLogicalOperationFingerprint
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class CreateModelTaskCommand(
    val taskId: String,
    val request: ModelTaskRequest,
    val requestFingerprint: String,
    val operationFingerprint: String = ModelTaskLogicalOperationFingerprint.of(request),
    val occurredAtEpochMillis: Long,
) {
    init {
        require(taskId.isNotBlank()) { "Model task id must not be blank" }
        require(requestFingerprint.length == 64) { "Model task fingerprint must be SHA-256" }
        require(requestFingerprint == ModelTaskFingerprint.of(request)) {
            "Model task fingerprint does not match its request"
        }
        require(operationFingerprint.length == 64) {
            "Model task operation fingerprint must be SHA-256"
        }
        require(operationFingerprint == ModelTaskLogicalOperationFingerprint.of(request)) {
            "Model task operation fingerprint does not match its immutable input"
        }
        require(occurredAtEpochMillis >= 0) { "Model task creation time must not be negative" }
    }
}

data class ReserveModelTaskRemoteDispatchCommand(
    val taskId: String,
    val expectedStateVersion: Long,
    val expectedStatus: ModelTaskStatus,
    val provider: ProviderCapabilitySnapshot,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(taskId.isNotBlank()) { "Model task id must not be blank" }
        require(expectedStateVersion >= 0) { "Expected model task version must not be negative" }
        require(expectedStatus == ModelTaskStatus.QUEUED) {
            "Only a queued model task may reserve a remote dispatch"
        }
        require(provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
            "Only an external provider may reserve a remote dispatch"
        }
        require(occurredAtEpochMillis >= 0) { "Model task reservation time must not be negative" }
    }
}

data class TransitionModelTaskCommand(
    val taskId: String,
    val expectedStateVersion: Long,
    val expectedStatus: ModelTaskStatus,
    val nextStatus: ModelTaskStatus,
    val stage: ModelTaskStage,
    val userMessage: String,
    val attemptCount: Int,
    val provider: ProviderCapabilitySnapshot? = null,
    val output: ModelTaskOutput? = null,
    val failure: ModelTaskFailure? = null,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(taskId.isNotBlank()) { "Model task id must not be blank" }
        require(expectedStateVersion >= 0) { "Expected model task version must not be negative" }
        require(expectedStatus.canTransitionTo(nextStatus)) {
            "Illegal model task transition: $expectedStatus -> $nextStatus"
        }
        require(userMessage.length <= 500) { "Model task message exceeds budget" }
        require(attemptCount >= 0) { "Model task attempt count must not be negative" }
        require(nextStatus != ModelTaskStatus.SUCCEEDED || output != null) {
            "A successful model task transition must include output"
        }
        require(
            nextStatus != ModelTaskStatus.RETRYABLE_FAILURE &&
                nextStatus != ModelTaskStatus.PERMANENT_FAILURE || failure != null,
        ) { "A failed model task transition must include failure details" }
        require(occurredAtEpochMillis >= 0) { "Model task transition time must not be negative" }
    }
}

data class ModelTaskWriteResult(
    val applied: Boolean,
    val snapshot: ModelTaskSnapshot,
)

data class ModelTaskDispatchReservationResult(
    val applied: Boolean,
    val budgetExhausted: Boolean,
    val logicalDispatchCount: Int,
    val snapshot: ModelTaskSnapshot,
) {
    init {
        require(logicalDispatchCount >= 0) {
            "Logical model operation dispatch count must not be negative"
        }
        require(!applied || !budgetExhausted) {
            "An applied model dispatch reservation cannot be exhausted"
        }
        require(
            !applied ||
                (snapshot.status == ModelTaskStatus.RUNNING &&
                    snapshot.attemptCount == logicalDispatchCount),
        ) { "An applied model dispatch reservation must return its durable running snapshot" }
    }
}

interface ModelTaskDatabasePort {
    fun observeModelTask(requestId: String): Flow<ModelTaskSnapshot?>

    fun observeModelTasks(
        subjectId: String,
        kind: ModelTaskKind,
    ): Flow<List<ModelTaskSnapshot>> = flowOf(emptyList())

    fun observeRecentModelTasks(
        subjectId: String,
        kind: ModelTaskKind,
        limit: Int,
    ): Flow<List<ModelTaskSnapshot>> {
        require(limit > 0) { "Recent model-task limit must be positive" }
        return observeModelTasks(subjectId, kind).map { snapshots -> snapshots.takeLast(limit) }
    }

    suspend fun readModelTask(requestId: String): ModelTaskSnapshot?

    suspend fun createModelTask(command: CreateModelTaskCommand): ModelTaskWriteResult

    suspend fun reserveModelTaskRemoteDispatch(
        command: ReserveModelTaskRemoteDispatchCommand,
    ): ModelTaskDispatchReservationResult = throw UnsupportedOperationException(
        "Atomic model task dispatch reservations are not available",
    )

    suspend fun transitionModelTask(command: TransitionModelTaskCommand): ModelTaskWriteResult
}
