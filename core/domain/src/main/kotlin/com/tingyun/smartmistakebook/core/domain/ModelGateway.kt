package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.StreamingMarkdownAssembler
import com.tingyun.smartmistakebook.core.model.StreamingMarkdownCompletion
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorStreamEvent
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Provider adapter. Implementations cannot access Room or mutate learning facts. */
interface ModelGateway {
    suspend fun capabilities(): ProviderCapabilitySnapshot

    fun execute(execution: ModelGatewayExecution): Flow<ModelGatewayEvent>
}

/**
 * A model-task view already narrowed to one feature-owned subject scope.
 *
 * Production UI receives this interface, never [ModelTaskRepository]. The production scope owner
 * rechecks the task kind, subject and request identity for every call. Test repositories may
 * implement the wider repository below and are therefore still valid substitutes.
 */
interface ScopedModelTaskPort {
    suspend fun capabilities(): ProviderCapabilitySnapshot

    fun observe(requestId: String): Flow<ModelTaskSnapshot?>

    /** Durable conversation recovery, bounded to one subject and task kind. */
    fun observeBySubject(
        subjectId: String,
        kind: ModelTaskKind,
    ): Flow<List<ModelTaskSnapshot>> = flowOf(emptyList())

    /** Recent durable recovery, oldest-to-newest, without loading an unbounded conversation. */
    fun observeRecentBySubject(
        subjectId: String,
        kind: ModelTaskKind,
        limit: Int,
    ): Flow<List<ModelTaskSnapshot>> {
        require(limit > 0) { "Recent model-task limit must be positive" }
        return observeBySubject(subjectId, kind).map { snapshots -> snapshots.takeLast(limit) }
    }

    fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot>

    /**
     * Durably supersedes an in-flight task when the owning turn or mode changes.
     *
     * Navigation alone must not call this boundary because pending work remains recoverable.
     */
    suspend fun cancel(requestId: String) = Unit

    /**
     * Ephemeral Tutor preview boundary. Implementations that do not support transport streaming
     * still complete through the same contract from their durable, validated task snapshot.
     */
    fun executeTutorStream(
        request: ModelTaskRequest,
        identity: TutorStreamIdentity,
    ): Flow<TutorStreamEvent> = flow {
        require(identity.requestId == request.requestId) {
            "Tutor stream identity must match its model request"
        }
        require(request.input is TutorRespondInput || request.input is TutorLobbyInput) {
            "Only Tutor response tasks can use the Tutor stream contract"
        }

        var startedEmitted = false
        var terminalEmitted = false
        execute(request).collect { snapshot ->
            if (terminalEmitted) return@collect
            if (!startedEmitted) {
                emit(TutorStreamEvent.Started(identity))
                startedEmitted = true
            }
            when (snapshot.status) {
                ModelTaskStatus.SUCCEEDED -> {
                    val markdown = when (val output = snapshot.output) {
                        is TutorRespondOutput -> output.messageMarkdown
                        is TutorLobbyOutput -> output.messageMarkdown
                        else -> null
                    }
                    val completion = markdown?.let { value ->
                        try {
                            StreamingMarkdownAssembler().run {
                                append(value)
                                complete()
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (markdown == null) {
                        emit(
                            TutorStreamEvent.Failed(
                                identity = identity,
                                snapshot = TutorMarkdownSnapshot.EMPTY,
                                retryable = false,
                            ),
                        )
                    } else {
                        val completedSnapshot = when (completion) {
                            is StreamingMarkdownCompletion.Accepted -> completion.snapshot
                            is StreamingMarkdownCompletion.Rejected,
                            null,
                            -> TutorMarkdownSnapshot.completedLiteral(markdown)
                        }
                        emit(
                            TutorStreamEvent.Completed(
                                identity = identity,
                                snapshot = completedSnapshot,
                            ),
                        )
                    }
                    terminalEmitted = true
                }
                ModelTaskStatus.RETRYABLE_FAILURE,
                ModelTaskStatus.PERMANENT_FAILURE,
                ModelTaskStatus.CANCELLED,
                -> {
                    emit(
                        TutorStreamEvent.Failed(
                            identity = identity,
                            snapshot = TutorMarkdownSnapshot.EMPTY,
                            retryable = snapshot.failure?.retryable == true,
                        ),
                    )
                    terminalEmitted = true
                }
                ModelTaskStatus.WAITING_FOR_MODEL,
                ModelTaskStatus.QUEUED,
                ModelTaskStatus.RUNNING,
                ModelTaskStatus.STREAMING,
                -> Unit
            }
        }
        if (!terminalEmitted) {
            emit(
                TutorStreamEvent.Failed(
                    identity = identity,
                    snapshot = TutorMarkdownSnapshot.EMPTY,
                    retryable = true,
                ),
            )
        }
    }
}

/**
 * Process-wide durable queue owner. This capability is reserved for trusted production
 * coordinators which mint [ScopedModelTaskPort] views; feature modules must not receive it.
 */
interface ModelTaskRepository : ScopedModelTaskPort {
    /**
     * Executes a raw-answer task without writing its request to the ordinary model-task database.
     * Production implementations must keep the request process-local; durability belongs to the
     * encrypted owner outbox and downstream idempotent candidate owner.
     */
    fun executeSensitiveEphemeral(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
        flow {
            error("Sensitive model execution is unavailable for this repository")
        }
}
