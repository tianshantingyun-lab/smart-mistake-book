package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.CreateModelTaskCommand
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.LearningLedgerIntegrityException
import com.tingyun.smartmistakebook.core.database.ModelTaskDispatchReservationResult
import com.tingyun.smartmistakebook.core.database.ModelTaskWriteResult
import com.tingyun.smartmistakebook.core.database.ReserveModelTaskRemoteDispatchCommand
import com.tingyun.smartmistakebook.core.database.TransitionModelTaskCommand
import com.tingyun.smartmistakebook.core.database.entity.ModelTaskEntity
import com.tingyun.smartmistakebook.core.database.entity.ModelTaskEventEntity
import com.tingyun.smartmistakebook.core.database.entity.ModelTaskOperationEntity
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskLogicalOperationFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRemoteDispatchPolicy
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
internal abstract class ModelTaskTransactionDao {
    @Query("SELECT * FROM model_task WHERE request_id = :requestId LIMIT 1")
    protected abstract fun observeEntity(requestId: String): Flow<ModelTaskEntity?>

    @Query(
        "SELECT * FROM model_task WHERE subject_id = :subjectId AND task_kind = :taskKind " +
            "ORDER BY created_at_epoch_millis ASC, request_id ASC",
    )
    protected abstract fun observeEntities(
        subjectId: String,
        taskKind: String,
    ): Flow<List<ModelTaskEntity>>

    @Query(
        "SELECT * FROM model_task WHERE subject_id = :subjectId AND task_kind = :taskKind " +
            "ORDER BY created_at_epoch_millis DESC, request_id DESC LIMIT :limit",
    )
    protected abstract fun observeRecentEntities(
        subjectId: String,
        taskKind: String,
        limit: Int,
    ): Flow<List<ModelTaskEntity>>

    @Query("SELECT * FROM model_task WHERE request_id = :requestId LIMIT 1")
    protected abstract suspend fun findByRequestId(requestId: String): ModelTaskEntity?

    @Query("SELECT * FROM model_task WHERE task_id = :taskId LIMIT 1")
    protected abstract suspend fun findByTaskId(taskId: String): ModelTaskEntity?

    @Query(
        "SELECT operation_fingerprint FROM model_task " +
            "WHERE subject_id = :subjectId AND task_kind = :taskKind " +
            "AND tutor_response_ordinal = :responseOrdinal",
    )
    protected abstract suspend fun findOperationFingerprintsByTutorResponseSlot(
        subjectId: String,
        taskKind: String,
        responseOrdinal: Int,
    ): List<String>

    @Query(
        "SELECT * FROM model_task_operation WHERE operation_fingerprint = :operationFingerprint " +
            "LIMIT 1",
    )
    protected abstract suspend fun findOperation(
        operationFingerprint: String,
    ): ModelTaskOperationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOperation(entity: ModelTaskOperationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTask(entity: ModelTaskEntity): Long

    @Insert
    protected abstract suspend fun insertEvent(entity: ModelTaskEventEntity)

    @Query(
        """
        UPDATE model_task_operation
        SET dispatch_count = dispatch_count + 1,
            updated_at_epoch_millis = CASE
                WHEN updated_at_epoch_millis < :updatedAtEpochMillis THEN :updatedAtEpochMillis
                ELSE updated_at_epoch_millis
            END
        WHERE operation_fingerprint = :operationFingerprint
          AND dispatch_count < :maxDispatches
        """,
    )
    protected abstract suspend fun incrementOperationDispatchCount(
        operationFingerprint: String,
        maxDispatches: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE model_task
        SET status = :nextStatus,
            state_version = state_version + 1,
            stage = :stage,
            user_message = :userMessage,
            attempt_count = :attemptCount,
            provider_snapshot = :providerSnapshot,
            output_snapshot = :outputSnapshot,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            failure_retryable = :failureRetryable,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE task_id = :taskId
          AND state_version = :expectedStateVersion
          AND status = :expectedStatus
        """,
    )
    protected abstract suspend fun advance(
        taskId: String,
        expectedStateVersion: Long,
        expectedStatus: String,
        nextStatus: String,
        stage: String,
        userMessage: String,
        attemptCount: Int,
        providerSnapshot: String?,
        outputSnapshot: String?,
        failureCode: String?,
        failureMessage: String?,
        failureRetryable: Boolean?,
        updatedAtEpochMillis: Long,
    ): Int

    fun observe(requestId: String): Flow<ModelTaskSnapshot?> {
        require(requestId.isNotBlank()) { "Model task request id must not be blank" }
        return observeEntity(requestId).map { it?.toSnapshot() }
    }

    fun observeBySubject(subjectId: String, kind: ModelTaskKind): Flow<List<ModelTaskSnapshot>> {
        require(subjectId.isNotBlank()) { "Model task subject id must not be blank" }
        return observeEntities(subjectId, kind.name).map { entities -> entities.map { it.toSnapshot() } }
    }

    fun observeRecentBySubject(
        subjectId: String,
        kind: ModelTaskKind,
        limit: Int,
    ): Flow<List<ModelTaskSnapshot>> {
        require(subjectId.isNotBlank()) { "Model task subject id must not be blank" }
        require(limit > 0) { "Recent model-task limit must be positive" }
        return observeRecentEntities(subjectId, kind.name, limit).map { entities ->
            entities.asReversed().map { it.toSnapshot() }
        }
    }

    suspend fun read(requestId: String): ModelTaskSnapshot? {
        require(requestId.isNotBlank()) { "Model task request id must not be blank" }
        return findByRequestId(requestId)?.toSnapshot()
    }

    @Transaction
    open suspend fun create(command: CreateModelTaskCommand): ModelTaskWriteResult {
        val requestSnapshot = ModelTaskCodec.encodeRequest(command.request)
        val operationCandidate = ModelTaskOperationEntity(
            operationFingerprint = command.operationFingerprint,
            subjectId = command.request.input.subjectId,
            taskKind = command.request.input.kind.name,
            dispatchCount = 0,
            createdAtEpochMillis = command.occurredAtEpochMillis,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        )
        insertOperation(operationCandidate)
        val operation = findOperation(command.operationFingerprint)
            ?: throw LearningLedgerIntegrityException("Model task operation insert was not readable")
        if (
            operation.subjectId != operationCandidate.subjectId ||
            operation.taskKind != operationCandidate.taskKind
        ) {
            throw ImmutablePayloadConflictException(
                "model_task_operation",
                command.operationFingerprint,
            )
        }
        val entity = ModelTaskEntity(
            taskId = command.taskId,
            requestId = command.request.requestId,
            requestFingerprint = command.requestFingerprint,
            operationFingerprint = command.operationFingerprint,
            requestSnapshot = requestSnapshot,
            taskKind = command.request.input.kind.name,
            subjectId = command.request.input.subjectId,
            tutorResponseOrdinal = (command.request.input as? TutorRespondInput)?.responseOrdinal,
            status = ModelTaskStatus.WAITING_FOR_MODEL.name,
            stateVersion = 0,
            stage = ModelTaskStage.WAITING.name,
            userMessage = "任务已保存，等待模型处理",
            attemptCount = operation.dispatchCount,
            providerSnapshot = null,
            outputSnapshot = null,
            failureCode = null,
            failureMessage = null,
            failureRetryable = null,
            createdAtEpochMillis = command.occurredAtEpochMillis,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        )
        entity.tutorResponseOrdinal?.let { responseOrdinal ->
            val slotOperations = findOperationFingerprintsByTutorResponseSlot(
                entity.subjectId,
                entity.taskKind,
                responseOrdinal,
            )
            if (slotOperations.any { it != entity.operationFingerprint }) {
                throw ImmutablePayloadConflictException(
                    "model_task_tutor_response_slot",
                    "${entity.subjectId}:$responseOrdinal",
                )
            }
        }
        val created = insertTask(entity) != -1L
        val persisted = findByRequestId(command.request.requestId)
            ?: findByTaskId(command.taskId)
            ?: throw LearningLedgerIntegrityException("Model task insert was not readable")
        if (!persisted.sameImmutableRequest(entity)) {
            throw ImmutablePayloadConflictException("model_task", command.request.requestId)
        }
        if (created) {
            insertEvent(persisted.toEvent(previousStatus = null))
        }
        return ModelTaskWriteResult(applied = created, snapshot = persisted.toSnapshot())
    }

    @Transaction
    open suspend fun reserveRemoteDispatch(
        command: ReserveModelTaskRemoteDispatchCommand,
    ): ModelTaskDispatchReservationResult {
        val current = findByTaskId(command.taskId)
            ?: throw ImmutablePayloadConflictException("model_task", command.taskId)
        val operation = findOperation(current.operationFingerprint)
            ?: throw LearningLedgerIntegrityException("Model task operation is missing")
        if (
            current.stateVersion != command.expectedStateVersion ||
            current.status != command.expectedStatus.name
        ) {
            return ModelTaskDispatchReservationResult(
                applied = false,
                budgetExhausted = false,
                logicalDispatchCount = operation.dispatchCount,
                snapshot = current.toSnapshot(),
            )
        }
        require(command.occurredAtEpochMillis >= current.updatedAtEpochMillis) {
            "Model task reservation time must be monotonic"
        }
        val operationReserved = incrementOperationDispatchCount(
            operationFingerprint = current.operationFingerprint,
            maxDispatches = ModelTaskRemoteDispatchPolicy.MAX_DISPATCHES,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        ) == 1
        val updatedOperation = findOperation(current.operationFingerprint)
            ?: throw LearningLedgerIntegrityException("Model task operation disappeared")
        if (!operationReserved) {
            if (updatedOperation.dispatchCount < ModelTaskRemoteDispatchPolicy.MAX_DISPATCHES) {
                throw LearningLedgerIntegrityException("Model task operation reservation was lost")
            }
            return ModelTaskDispatchReservationResult(
                applied = false,
                budgetExhausted = true,
                logicalDispatchCount = updatedOperation.dispatchCount,
                snapshot = current.toSnapshot(),
            )
        }
        val providerSnapshot = ModelTaskCodec.encodeProvider(command.provider)
        val taskReserved = advance(
            taskId = current.taskId,
            expectedStateVersion = current.stateVersion,
            expectedStatus = current.status,
            nextStatus = ModelTaskStatus.RUNNING.name,
            stage = ModelTaskStage.PREPARING.name,
            userMessage = "正在准备",
            attemptCount = updatedOperation.dispatchCount,
            providerSnapshot = providerSnapshot,
            outputSnapshot = null,
            failureCode = null,
            failureMessage = null,
            failureRetryable = null,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        ) == 1
        if (!taskReserved) {
            throw LearningLedgerIntegrityException(
                "Model task changed during its atomic dispatch reservation",
            )
        }
        val persisted = findByTaskId(current.taskId)
            ?: throw LearningLedgerIntegrityException("Model task disappeared after reservation")
        insertEvent(persisted.toEvent(previousStatus = current.status))
        return ModelTaskDispatchReservationResult(
            applied = true,
            budgetExhausted = false,
            logicalDispatchCount = updatedOperation.dispatchCount,
            snapshot = persisted.toSnapshot(),
        )
    }

    @Transaction
    open suspend fun transition(command: TransitionModelTaskCommand): ModelTaskWriteResult {
        val current = findByTaskId(command.taskId)
            ?: throw ImmutablePayloadConflictException("model_task", command.taskId)
        if (
            current.stateVersion != command.expectedStateVersion ||
            current.status != command.expectedStatus.name
        ) {
            return ModelTaskWriteResult(applied = false, snapshot = current.toSnapshot())
        }
        require(command.occurredAtEpochMillis >= current.updatedAtEpochMillis) {
            "Model task transition time must be monotonic"
        }
        val providerSnapshot = command.provider?.let(ModelTaskCodec::encodeProvider)
        val outputSnapshot = command.output?.let(ModelTaskCodec::encodeOutput)
        val failure = command.failure
        val applied = advance(
            taskId = command.taskId,
            expectedStateVersion = command.expectedStateVersion,
            expectedStatus = command.expectedStatus.name,
            nextStatus = command.nextStatus.name,
            stage = command.stage.name,
            userMessage = command.userMessage,
            attemptCount = command.attemptCount,
            providerSnapshot = providerSnapshot,
            outputSnapshot = outputSnapshot,
            failureCode = failure?.code?.name,
            failureMessage = failure?.message,
            failureRetryable = failure?.retryable,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        ) == 1
        val persisted = findByTaskId(command.taskId)
            ?: throw LearningLedgerIntegrityException("Model task disappeared during transition")
        if (applied) {
            insertEvent(persisted.toEvent(previousStatus = current.status))
        }
        return ModelTaskWriteResult(applied = applied, snapshot = persisted.toSnapshot())
    }
}

private fun ModelTaskEntity.sameImmutableRequest(other: ModelTaskEntity): Boolean =
    if (
        taskKind == ModelTaskKind.TUTOR_RESPOND.name &&
        other.taskKind == ModelTaskKind.TUTOR_RESPOND.name
    ) {
        sameImmutableTutorResponse(other)
    } else {
        sameStrictImmutableRequest(other)
    }

private fun ModelTaskEntity.sameStrictImmutableRequest(other: ModelTaskEntity): Boolean =
    taskId == other.taskId &&
        requestId == other.requestId &&
        requestFingerprint == other.requestFingerprint &&
        operationFingerprint == other.operationFingerprint &&
        requestSnapshot == other.requestSnapshot &&
        taskKind == other.taskKind &&
        subjectId == other.subjectId &&
        tutorResponseOrdinal == other.tutorResponseOrdinal &&
        createdAtEpochMillis == other.createdAtEpochMillis

private fun ModelTaskEntity.sameImmutableTutorResponse(other: ModelTaskEntity): Boolean =
    taskId == other.taskId &&
        requestId == other.requestId &&
        operationFingerprint == other.operationFingerprint &&
        taskKind == other.taskKind &&
        subjectId == other.subjectId &&
        tutorResponseOrdinal != null &&
        tutorResponseOrdinal == other.tutorResponseOrdinal &&
        ModelTaskCodec.decodeRequest(requestSnapshot).withoutTutorResponseTiming() ==
        ModelTaskCodec.decodeRequest(other.requestSnapshot).withoutTutorResponseTiming()

private fun ModelTaskRequest.withoutTutorResponseTiming(): ModelTaskRequest {
    require(input is TutorRespondInput) { "Tutor response task snapshot has the wrong input" }
    return copy(
        occurredAtEpochMillis = 0,
        egressManifest = egressManifest?.copy(approvedAtEpochMillis = 0),
    )
}

internal fun ModelTaskEntity.toSnapshot(): ModelTaskSnapshot {
    val request = ModelTaskCodec.decodeRequest(requestSnapshot)
    val expectedTutorResponseOrdinal = (request.input as? TutorRespondInput)?.responseOrdinal
    if (
        request.input.kind.name != taskKind ||
        request.input.subjectId != subjectId ||
        expectedTutorResponseOrdinal != tutorResponseOrdinal
    ) {
        throw LearningLedgerIntegrityException("Model task request columns disagree with snapshot")
    }
    if (operationFingerprint != ModelTaskLogicalOperationFingerprint.of(request)) {
        throw LearningLedgerIntegrityException(
            "Model task operation fingerprint disagrees with immutable input",
        )
    }
    val failure = when {
        failureCode == null && failureMessage == null && failureRetryable == null -> null
        failureCode != null && failureMessage != null && failureRetryable != null ->
            ModelTaskFailure(
                code = ModelFailureCode.valueOf(failureCode),
                message = failureMessage,
                retryable = failureRetryable,
            )
        else -> throw LearningLedgerIntegrityException("Model task failure columns are incomplete")
    }
    return ModelTaskSnapshot(
        taskId = taskId,
        request = request,
        requestFingerprint = requestFingerprint,
        status = ModelTaskStatus.valueOf(status),
        stateVersion = stateVersion,
        stage = ModelTaskStage.valueOf(stage),
        userMessage = userMessage,
        attemptCount = attemptCount,
        provider = providerSnapshot?.let(ModelTaskCodec::decodeProvider),
        output = outputSnapshot?.let(ModelTaskCodec::decodeOutput),
        failure = failure,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private fun ModelTaskEntity.toEvent(previousStatus: String?) = ModelTaskEventEntity(
    taskId = taskId,
    stateVersion = stateVersion,
    previousStatus = previousStatus,
    nextStatus = status,
    stage = stage,
    userMessage = userMessage,
    attemptCount = attemptCount,
    // The current task row owns the full provider/output payload. Audit events retain only the
    // transition facts so a structured题面 is not duplicated on every state change.
    providerSnapshot = providerSnapshot.takeIf { status == ModelTaskStatus.RUNNING.name },
    outputSnapshot = null,
    failureCode = failureCode,
    failureMessage = failureMessage,
    failureRetryable = failureRetryable,
    createdAtEpochMillis = updatedAtEpochMillis,
)
