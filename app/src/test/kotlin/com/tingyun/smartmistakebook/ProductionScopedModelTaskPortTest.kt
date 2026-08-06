package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorStreamEvent
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionScopedModelTaskPortTest {
    @Test
    fun exactScopeExecutesOnlyItsFeatureKindsAndSubject() = runBlocking {
        val queue = RecordingModelTaskRepository()
        val owner = ProductionScopedModelTaskPortOwner(queue)
        val port =
            owner.exact(
                feature = ProductionModelTaskFeature.CAPTURE,
                subjectId = "draft-current",
                allowedKinds = CAPTURE_UI_MODEL_TASK_KINDS,
            )

        assertEquals(
            "draft-current",
            port.execute(captureRequest("capture-current", "draft-current", 10)).first()
                .request.input.subjectId,
        )
        assertDenied { port.execute(captureRequest("capture-other", "draft-other", 11)).first() }
        assertDenied { port.execute(lobbyRequest("lobby-wrong-kind", "draft-current", 12)).first() }
        assertEquals(listOf("capture-current"), queue.executedRequestIds)

        owner.close()
    }

    @Test
    fun observationAndCancellationCannotCrossAnExactScope() = runBlocking {
        val queue = RecordingModelTaskRepository()
        queue.seed(captureRequest("capture-foreign", "draft-foreign", 10))
        queue.seed(captureRequest("capture-owned", "draft-current", 11))
        val owner = ProductionScopedModelTaskPortOwner(queue)
        val port =
            owner.exact(
                feature = ProductionModelTaskFeature.CAPTURE,
                subjectId = "draft-current",
                allowedKinds = CAPTURE_UI_MODEL_TASK_KINDS,
            )

        assertNull(port.observe("capture-foreign").first())
        port.cancel("capture-foreign")
        assertTrue(queue.cancelledRequestIds.isEmpty())

        assertEquals("capture-owned", port.observe("capture-owned").first()?.request?.requestId)
        port.cancel("capture-owned")
        assertEquals(listOf("capture-owned"), queue.cancelledRequestIds)

        owner.close()
    }

    @Test
    fun requestIdentityCannotBeClaimedByAnotherFeatureOrSubject() = runBlocking {
        val queue = RecordingModelTaskRepository()
        val owner = ProductionScopedModelTaskPortOwner(queue)
        val first =
            owner.exact(
                ProductionModelTaskFeature.CAPTURE,
                "draft-a",
                CAPTURE_UI_MODEL_TASK_KINDS,
            )
        val second =
            owner.exact(
                ProductionModelTaskFeature.CAPTURE,
                "draft-b",
                CAPTURE_UI_MODEL_TASK_KINDS,
            )

        first.execute(captureRequest("same-request", "draft-a", 10)).first()
        assertDenied { second.execute(captureRequest("same-request", "draft-b", 11)).first() }
        assertEquals(listOf("same-request"), queue.executedRequestIds)

        owner.close()
    }

    @Test
    fun activeRouteOwnsItsRequestsAndAClosedRouteReleasesRecoveryOwnership() = runBlocking {
        val queue = RecordingModelTaskRepository()
        val owner = ProductionScopedModelTaskPortOwner(queue)
        val first =
            owner.exact(
                ProductionModelTaskFeature.CAPTURE,
                "draft-current",
                CAPTURE_UI_MODEL_TASK_KINDS,
            )
        val recovery =
            owner.exact(
                ProductionModelTaskFeature.CAPTURE,
                "draft-current",
                CAPTURE_UI_MODEL_TASK_KINDS,
            )

        first.execute(captureRequest("owned-request", "draft-current", 10)).first()
        assertNull(recovery.observe("owned-request").first())

        (first as AutoCloseable).close()
        assertEquals("owned-request", recovery.observe("owned-request").first()?.request?.requestId)

        owner.close()
    }

    @Test
    fun bindOnceCannotSwitchItsDurableSubject() = runBlocking {
        val queue = RecordingModelTaskRepository()
        val owner = ProductionScopedModelTaskPortOwner(queue)
        val port =
            owner.bindOnce(
                feature = ProductionModelTaskFeature.CAPTURE,
                allowedKinds = CAPTURE_UI_MODEL_TASK_KINDS,
            )

        port.execute(captureRequest("capture-first", "draft-a", 10)).first()
        assertDenied { port.execute(captureRequest("capture-second", "draft-b", 11)).first() }
        assertEquals(listOf("capture-first"), queue.executedRequestIds)

        owner.close()
    }

    @Test
    fun rotatingLobbyRejectsLateWorkFromSupersededConversation() = runBlocking {
        val queue = RecordingModelTaskRepository()
        val owner = ProductionScopedModelTaskPortOwner(queue)
        val port =
            owner.rotating(
                feature = ProductionModelTaskFeature.TUTOR,
                allowedKinds = TUTOR_LOBBY_UI_MODEL_TASK_KINDS,
            )

        port.execute(lobbyRequest("lobby-a", "conversation-a", 100)).first()
        port.execute(lobbyRequest("lobby-b", "conversation-b", 200)).first()
        assertDenied { port.execute(lobbyRequest("lobby-a-late", "conversation-a", 150)).first() }
        assertTrue(
            port.observeBySubject("conversation-a", ModelTaskKind.TUTOR_LOBBY).first().isEmpty(),
        )
        assertEquals(
            listOf("lobby-a", "lobby-b"),
            queue.executedRequestIds,
        )

        owner.close()
    }

    @Test
    fun publishedCapabilitiesContainOnlyTheAllowedTaskFamily() = runBlocking {
        val queue = RecordingModelTaskRepository()
        val owner = ProductionScopedModelTaskPortOwner(queue)
        val port =
            owner.exact(
                feature = ProductionModelTaskFeature.CAPTURE,
                subjectId = "draft-current",
                allowedKinds = CAPTURE_UI_MODEL_TASK_KINDS,
            )

        assertEquals(CAPTURE_UI_MODEL_TASK_KINDS, port.capabilities().supportedTasks)

        owner.close()
    }

    private suspend fun assertDenied(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected scoped model-task access to be denied", failure is SecurityException)
    }

    private class RecordingModelTaskRepository : ModelTaskRepository {
        val executedRequestIds = mutableListOf<String>()
        val cancelledRequestIds = mutableListOf<String>()
        private val snapshots = linkedMapOf<String, ModelTaskSnapshot>()

        override suspend fun capabilities(): ProviderCapabilitySnapshot =
            ProviderCapabilitySnapshot(
                providerId = "provider-test",
                providerDisplayName = "Provider test",
                modelId = "model-test",
                supportedTasks = ModelTaskKind.entries.toSet(),
                supportsImageInput = true,
                supportsStructuredOutput = true,
                supportsStreaming = true,
                providerConfigurationVersion = "configuration-test-v1",
            )

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> =
            flowOf(snapshots[requestId])

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> =
            flowOf(
                snapshots.values.filter { snapshot ->
                    snapshot.request.input.subjectId == subjectId &&
                        snapshot.request.input.kind == kind
                },
            )

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> {
            executedRequestIds += request.requestId
            val snapshot = snapshot(request)
            snapshots[request.requestId] = snapshot
            return flowOf(snapshot)
        }

        override suspend fun cancel(requestId: String) {
            cancelledRequestIds += requestId
        }

        override fun executeTutorStream(
            request: ModelTaskRequest,
            identity: TutorStreamIdentity,
        ): Flow<TutorStreamEvent> = error("Not needed by this capability test")

        fun seed(request: ModelTaskRequest) {
            snapshots[request.requestId] = snapshot(request)
        }

        private fun snapshot(request: ModelTaskRequest) =
            ModelTaskSnapshot(
                taskId = "task:${request.requestId}",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                status = ModelTaskStatus.WAITING_FOR_MODEL,
                stateVersion = 0,
                stage = ModelTaskStage.WAITING,
                userMessage = "",
                attemptCount = 0,
                createdAtEpochMillis = request.occurredAtEpochMillis,
                updatedAtEpochMillis = request.occurredAtEpochMillis,
            )
    }

    private companion object {
        fun captureRequest(
            requestId: String,
            draftId: String,
            occurredAt: Long,
        ) = ModelTaskRequest(
            requestId = requestId,
            input =
                CaptureAssessmentInput(
                    draftId = draftId,
                    sourceAssetId = "source:$requestId",
                    origin = CaptureAssessmentOrigin.TUTOR,
                    imageWidth = 100,
                    imageHeight = 100,
                ),
            occurredAtEpochMillis = occurredAt,
        )

        fun lobbyRequest(
            requestId: String,
            conversationId: String,
            occurredAt: Long,
        ) = ModelTaskRequest(
            requestId = requestId,
            input =
                TutorLobbyInput(
                    conversationId = conversationId,
                    messageOrdinal = 1,
                    studentMessage = "讲一下当前内容",
                    explanationMode = TutorExplanationMode.DIRECT,
                ),
            occurredAtEpochMillis = occurredAt,
        )
    }
}
