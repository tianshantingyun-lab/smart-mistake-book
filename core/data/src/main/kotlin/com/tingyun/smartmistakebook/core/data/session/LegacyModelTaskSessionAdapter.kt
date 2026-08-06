package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.database.CreateModelTaskCommand
import com.tingyun.smartmistakebook.core.database.ModelTaskDatabasePort
import com.tingyun.smartmistakebook.core.database.ReserveModelTaskRemoteDispatchCommand
import com.tingyun.smartmistakebook.core.database.TransitionModelTaskCommand
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal class LegacyModelTaskSessionAdapter(
    private val boundScope: SessionScope,
    private val legacy: ModelTaskDatabasePort,
) : ModelTaskSessionPort {
    override fun observe(
        query: ModelTaskSessionReadQuery,
    ): Flow<ModelTaskSessionSnapshot?> {
        query.scope.requireBoundTo(boundScope)
        return legacy.observeModelTask(query.requestId).map { it?.toSession(boundScope) }
    }

    override fun observeRecent(
        query: RecentModelTaskSessionQuery,
    ): Flow<List<ModelTaskSessionSnapshot>> {
        query.scope.requireBoundTo(boundScope)
        return legacy.observeRecentModelTasks(query.subjectId, query.kind, query.limit)
            .map { records -> records.map { it.toSession(boundScope) } }
    }

    override suspend fun read(
        query: ModelTaskSessionReadQuery,
    ): ModelTaskSessionSnapshot? {
        query.scope.requireBoundTo(boundScope)
        return legacy.readModelTask(query.requestId)?.toSession(boundScope)
    }

    override suspend fun create(
        command: CreateModelTaskSessionCommand,
    ): ModelTaskSessionMutationResult {
        command.scope.requireBoundTo(boundScope)
        val request = ModelTaskSessionPayloadCodec.decodeRequest(command.requestPayload)
        require(request.requestId == command.operation.requestId) {
            "Model task request identity changed before session persistence"
        }
        require(request.input.subjectId == command.subjectId && request.input.kind == command.kind) {
            "Model task routing metadata does not match its opaque request payload"
        }
        val requestFingerprint = ModelTaskFingerprint.of(request)
        require(requestFingerprint == command.operation.payloadFingerprint) {
            "Model task semantic fingerprint changed before session persistence"
        }
        val result =
            legacy.createModelTask(
                CreateModelTaskCommand(
                    taskId = command.taskId,
                    request = request,
                    requestFingerprint = requestFingerprint,
                    occurredAtEpochMillis = command.occurredAtEpochMillis,
                ),
            )
        val snapshot = result.snapshot.toSession(boundScope)
        return ModelTaskSessionMutationResult(
            receipt =
                SessionMutationReceipt(
                    operation = command.operation,
                    disposition =
                        if (result.applied) {
                            SessionMutationDisposition.APPLIED
                        } else {
                            SessionMutationDisposition.DUPLICATE
                        },
                    currentVersion = snapshot.version,
                    recordedAtEpochMillis = command.occurredAtEpochMillis,
                ),
            snapshot = snapshot,
        )
    }

    override suspend fun mutate(
        command: ModelTaskSessionMutation,
    ): ModelTaskSessionMutationResult {
        command.scope.requireBoundTo(boundScope)
        command.taskId.requireSessionIdentifier("Model task id")
        require(command.occurredAtEpochMillis >= 0) {
            "Model task mutation time must not be negative"
        }
        val current =
            legacy.readModelTask(command.operation.requestId)
                ?: return missingModelTaskResult(command)
        val currentSession = current.toSession(boundScope)
        if (
            current.taskId != command.taskId ||
            current.status != command.expectedStatus ||
            currentSession.version != command.expectedVersion
        ) {
            return reloadModelTaskResult(command, currentSession)
        }

        return when (command) {
            is ModelTaskSessionMutation.ReserveExternalDispatch -> {
                val provider =
                    ModelTaskSessionPayloadCodec.decodeProvider(command.providerPayload)
                val result =
                    legacy.reserveModelTaskRemoteDispatch(
                        ReserveModelTaskRemoteDispatchCommand(
                            taskId = command.taskId,
                            expectedStateVersion = current.stateVersion,
                            expectedStatus = command.expectedStatus,
                            provider = provider,
                            occurredAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    )
                val snapshot = result.snapshot.toSession(boundScope)
                ModelTaskSessionMutationResult(
                    receipt =
                        SessionMutationReceipt(
                            operation = command.operation,
                            disposition =
                                if (result.applied) {
                                    SessionMutationDisposition.APPLIED
                                } else {
                                    SessionMutationDisposition.RELOAD_REQUIRED
                                },
                            currentVersion = snapshot.version,
                            recordedAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    snapshot = snapshot,
                    dispatchBudgetExhausted = result.budgetExhausted,
                    logicalDispatchCount = result.logicalDispatchCount,
                )
            }

            is ModelTaskSessionMutation.Transition -> {
                val result =
                    legacy.transitionModelTask(
                        TransitionModelTaskCommand(
                            taskId = command.taskId,
                            expectedStateVersion = current.stateVersion,
                            expectedStatus = command.expectedStatus,
                            nextStatus = command.nextStatus,
                            stage = command.stage,
                            userMessage = command.userMessage,
                            attemptCount = command.attemptCount,
                            provider =
                                command.providerPayload?.let(
                                    ModelTaskSessionPayloadCodec::decodeProvider,
                                ),
                            output =
                                command.outputPayload?.let(
                                    ModelTaskSessionPayloadCodec::decodeOutput,
                                ),
                            failure =
                                command.failure?.let {
                                    ModelTaskFailure(
                                        code =
                                            com.tingyun.smartmistakebook.core.model.ModelFailureCode
                                                .valueOf(it.code),
                                        message = it.message,
                                        retryable = it.retryable,
                                    )
                                },
                            occurredAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    )
                val snapshot = result.snapshot.toSession(boundScope)
                ModelTaskSessionMutationResult(
                    receipt =
                        SessionMutationReceipt(
                            operation = command.operation,
                            disposition =
                                if (result.applied) {
                                    SessionMutationDisposition.APPLIED
                                } else if (
                                    snapshot.status == command.nextStatus &&
                                    snapshot.stage == command.stage &&
                                    snapshot.attemptCount == command.attemptCount
                                ) {
                                    SessionMutationDisposition.DUPLICATE
                                } else {
                                    SessionMutationDisposition.RELOAD_REQUIRED
                                },
                            currentVersion = snapshot.version,
                            recordedAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    snapshot = snapshot,
                )
            }
        }
    }
}

/**
 * Canonical JSON bridge for transient model payloads.
 *
 * The capability contract exposes only [SessionOpaquePayload]; no typed model input or output is
 * part of [ModelTaskSessionPort].
 */
internal object ModelTaskSessionPayloadCodec {
    private const val REQUEST_SCHEMA = "model-task-request-v1"
    private const val PROVIDER_SCHEMA = "model-task-provider-v1"
    private const val OUTPUT_SCHEMA = "model-task-output-v1"

    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            classDiscriminator = "type"
        }

    fun encodeRequest(request: ModelTaskRequest): SessionOpaquePayload =
        SessionOpaquePayload(
            schema = REQUEST_SCHEMA,
            content = json.encodeToString(ModelTaskRequest.serializer(), request),
        )

    fun decodeRequest(payload: SessionOpaquePayload): ModelTaskRequest {
        require(payload.schema == REQUEST_SCHEMA) { "Unexpected model request payload schema" }
        return json.decodeFromString(ModelTaskRequest.serializer(), payload.content)
    }

    fun encodeProvider(provider: ProviderCapabilitySnapshot): SessionOpaquePayload =
        SessionOpaquePayload(
            schema = PROVIDER_SCHEMA,
            content = json.encodeToString(ProviderCapabilitySnapshot.serializer(), provider),
        )

    fun decodeProvider(payload: SessionOpaquePayload): ProviderCapabilitySnapshot {
        require(payload.schema == PROVIDER_SCHEMA) { "Unexpected model provider payload schema" }
        return json.decodeFromString(ProviderCapabilitySnapshot.serializer(), payload.content)
    }

    fun encodeOutput(output: ModelTaskOutput): SessionOpaquePayload =
        SessionOpaquePayload(
            schema = OUTPUT_SCHEMA,
            content = json.encodeToString(ModelTaskOutput.serializer(), output),
        )

    fun decodeOutput(payload: SessionOpaquePayload): ModelTaskOutput {
        require(payload.schema == OUTPUT_SCHEMA) { "Unexpected model output payload schema" }
        return json.decodeFromString(ModelTaskOutput.serializer(), payload.content)
    }
}

private fun ModelTaskSnapshot.toSession(scope: SessionScope): ModelTaskSessionSnapshot {
    val versionFingerprint =
        CanonicalSha256("model-task-session-state-v1")
            .field("taskId", taskId)
            .field("requestId", request.requestId)
            .field("requestFingerprint", requestFingerprint)
            .field("stateVersion", stateVersion)
            .field("status", status.name)
            .field("stage", stage.name)
            .field("attemptCount", attemptCount)
            .field("updatedAtEpochMillis", updatedAtEpochMillis)
            .finish()
    return ModelTaskSessionSnapshot(
        scope = scope,
        taskId = taskId,
        operation =
            SessionOperationIdentity(
                requestId = request.requestId,
                idempotencyKey = taskId,
                requestVersion = request.schemaVersion.toLong(),
                payloadFingerprint = requestFingerprint,
            ),
        version = SessionVersion(stateVersion, versionFingerprint),
        subjectId = request.input.subjectId,
        kind = request.input.kind,
        status = status,
        stage = stage,
        userMessage = userMessage,
        attemptCount = attemptCount,
        requestPayload = ModelTaskSessionPayloadCodec.encodeRequest(request),
        providerPayload = provider?.let(ModelTaskSessionPayloadCodec::encodeProvider),
        outputPayload = output?.let(ModelTaskSessionPayloadCodec::encodeOutput),
        failure =
            failure?.let {
                ModelTaskSessionFailure(
                    code = it.code.name,
                    message = it.message,
                    retryable = it.retryable,
                )
            },
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private fun missingModelTaskResult(
    command: ModelTaskSessionMutation,
) = ModelTaskSessionMutationResult(
    receipt =
        SessionMutationReceipt(
            operation = command.operation,
            disposition = SessionMutationDisposition.NOT_FOUND,
            currentVersion = null,
            recordedAtEpochMillis = command.occurredAtEpochMillis,
        ),
    snapshot = null,
)

private fun reloadModelTaskResult(
    command: ModelTaskSessionMutation,
    snapshot: ModelTaskSessionSnapshot,
) = ModelTaskSessionMutationResult(
    receipt =
        SessionMutationReceipt(
            operation = command.operation,
            disposition = SessionMutationDisposition.RELOAD_REQUIRED,
            currentVersion = snapshot.version,
            recordedAtEpochMillis = command.occurredAtEpochMillis,
        ),
    snapshot = snapshot,
)
