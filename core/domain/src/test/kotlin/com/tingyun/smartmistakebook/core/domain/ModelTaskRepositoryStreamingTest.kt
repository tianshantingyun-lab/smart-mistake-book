package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorStreamEvent
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTaskRepositoryStreamingTest {
    @Test
    fun defaultStreamCompletesFromTheDurableValidatedSnapshot() = runBlocking {
        val request = request()
        val output = TutorLobbyOutput(
            conversationId = "conversation",
            messageOrdinal = 1,
            messageMarkdown = "这是最终回复。",
            modelVersion = "model-v1",
        )
        val repository = SnapshotRepository(
            request = request,
            snapshots = listOf(
                snapshot(request, ModelTaskStatus.RUNNING),
                snapshot(request, ModelTaskStatus.SUCCEEDED, output),
            ),
        )
        val identity = TutorStreamIdentity(
            requestId = request.requestId,
            ownerVersion = 2,
            turnVersion = 3,
            modeVersion = 4,
        )

        val events = repository.executeTutorStream(request, identity).toList()

        assertEquals(
            listOf(
                TutorStreamEvent.Started(identity),
                TutorStreamEvent.Completed(
                    identity,
                    TutorMarkdownSnapshot(
                        stableMarkdown = "这是最终回复。",
                        provisionalMarkdown = "",
                    ),
                ),
            ),
            events,
        )
        assertEquals(1, repository.executionCount)
    }

    @Test
    fun defaultStreamLiteralizesActiveMarkdownFromAValidLobbySnapshot() = runBlocking {
        val request = request()
        val output = TutorLobbyOutput(
            conversationId = "conversation",
            messageOrdinal = 1,
            messageMarkdown = "![资料](https://example.com/a.png)",
            modelVersion = "model-v1",
        )
        val repository = SnapshotRepository(
            request = request,
            snapshots = listOf(snapshot(request, ModelTaskStatus.SUCCEEDED, output)),
        )
        val identity = TutorStreamIdentity(
            requestId = request.requestId,
            ownerVersion = 2,
            turnVersion = 3,
            modeVersion = 4,
        )

        val completed = repository.executeTutorStream(request, identity).toList().last()
            as TutorStreamEvent.Completed

        assertTrue(completed.snapshot.visibleMarkdown.contains("资料"))
        assertFalse(completed.snapshot.visibleMarkdown.contains("!["))
        assertEquals("", completed.snapshot.provisionalMarkdown)
    }

    private class SnapshotRepository(
        private val request: ModelTaskRequest,
        private val snapshots: List<ModelTaskSnapshot>,
    ) : ModelTaskRepository {
        var executionCount = 0
            private set

        override suspend fun capabilities(): ProviderCapabilitySnapshot = CAPABILITIES

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> {
            assertEquals(this.request, request)
            executionCount += 1
            return flowOf(*snapshots.toTypedArray())
        }
    }

    private fun request() = ModelTaskRequest(
        requestId = "lobby-stream-request",
        input = TutorLobbyInput(
            conversationId = "conversation",
            messageOrdinal = 1,
            studentMessage = "帮我看一道题",
        ),
        occurredAtEpochMillis = 1_000,
    )

    private fun snapshot(
        request: ModelTaskRequest,
        status: ModelTaskStatus,
        output: TutorLobbyOutput? = null,
    ) = ModelTaskSnapshot(
        taskId = "task",
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = status,
        stateVersion = if (status == ModelTaskStatus.SUCCEEDED) 2 else 1,
        stage = if (status == ModelTaskStatus.SUCCEEDED) {
            ModelTaskStage.COMPLETE
        } else {
            ModelTaskStage.PREPARING
        },
        userMessage = "",
        attemptCount = 0,
        provider = CAPABILITIES,
        output = output,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_001,
    )

    private companion object {
        val CAPABILITIES = ProviderCapabilitySnapshot(
            providerId = "provider",
            providerDisplayName = "模型",
            modelId = "model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_LOBBY),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = false,
            executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
        )
    }
}
