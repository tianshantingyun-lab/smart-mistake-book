package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorLobbyVisualKind
import com.tingyun.smartmistakebook.core.model.TutorLobbyVisualRequest
import com.tingyun.smartmistakebook.core.model.TutorResponseIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLobbyModelTaskPolicyTest {
    @Test
    fun requestIdentityPersistentlyBindsModeVersionAndExplicitVisualIntent() {
        val provider = provider(ModelExecutionLocation.LOCAL_NO_EGRESS)
        val direct = buildTutorLobbyRequest(
            provider = provider,
            messageOrdinal = 1,
            studentMessage = "请用动画解释这个过程",
            priorMessages = emptyList(),
            occurredAtEpochMillis = 1,
        )
        val guided = buildTutorLobbyRequest(
            provider = provider,
            messageOrdinal = 1,
            studentMessage = "请用动画解释这个过程",
            priorMessages = emptyList(),
            occurredAtEpochMillis = 1,
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 3,
            explicitVisualRequest = TutorLobbyVisualRequest(
                TutorLobbyVisualKind.ANIMATION,
                "请用动画解释这个过程",
            ),
        )
        val input = guided.input as TutorLobbyInput

        assertEquals(TutorExplanationMode.GUIDED, input.explanationMode)
        assertEquals(3L, input.modeVersion)
        assertEquals(TutorLobbyVisualKind.ANIMATION, input.explicitVisualRequest?.kind)
        assertTrue(direct.requestId != guided.requestId)
    }

    @Test
    fun explicitVisualRequestCannotDisappearWhenTextTaskOmitsVisualOutput() {
        val visual = TutorLobbyVisualRequest(
            TutorLobbyVisualKind.THREE_DIMENSIONAL,
            "请用3D展示空间关系",
        )
        val pending = lobbyTask(
            messageOrdinal = 1,
            status = ModelTaskStatus.STREAMING,
            visualRequest = visual,
        )
        val completed = lobbyTask(
            messageOrdinal = 1,
            status = ModelTaskStatus.SUCCEEDED,
            visualRequest = visual,
        )

        assertEquals(TutorLobbyVisualPresentation.Preparing, pending.tutorLobbyVisualPresentation())
        assertEquals(
            TutorLobbyVisualPresentation.SourceRequired,
            completed.tutorLobbyVisualPresentation(),
        )
    }

    @Test
    fun modeChangeSuppressesLateGuidedDirective() {
        val input = TutorLobbyInput(
            conversationId = TUTOR_LOBBY_CONVERSATION_ID,
            messageOrdinal = 1,
            studentMessage = "这一步为什么这样处理？",
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 2,
        )
        val output = TutorLobbyOutput(
            conversationId = input.conversationId,
            messageOrdinal = input.messageOrdinal,
            messageMarkdown = "先判断关键关系。",
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 2,
            responseIntent = TutorResponseIntent.ASK,
            interactionDirective = TutorInteractionDirective.FreeResponse("哪个关系最关键？"),
            modelVersion = "model-v1",
        )

        assertTrue(
            visibleTutorLobbyDirective(input, output, TutorExplanationMode.GUIDED, 2) != null,
        )
        assertNull(visibleTutorLobbyDirective(input, output, TutorExplanationMode.DIRECT, 3))
    }

    @Test
    fun externalRequestBindsExactMessageAndLeastDisclosure() {
        val request = buildTutorLobbyRequest(
            provider = provider(ModelExecutionLocation.EXTERNAL_PROVIDER),
            messageOrdinal = 4,
            studentMessage = "帮我看看最近的函数错题",
            priorMessages = List(10) { index ->
                TutorChatHistoryEntry("消息$index", "回复$index")
            },
            occurredAtEpochMillis = 2_000,
            approvedAtEpochMillis = 2_000,
        )
        val input = request.input as TutorLobbyInput

        assertEquals(4, input.messageOrdinal)
        assertEquals("帮我看看最近的函数错题", input.studentMessage)
        assertEquals(8, input.priorMessages.size)
        assertEquals(setOf(ModelTaskKind.TUTOR_LOBBY), request.egressManifest?.authorizedTaskKinds)
        assertEquals(
            ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE,
            request.egressManifest?.disclosedData,
        )
        assertTrue(request.requestId.startsWith("tutor-lobby:4:"))
    }

    @Test
    fun localProviderNeverCreatesAnEgressManifest() {
        val request = buildTutorLobbyRequest(
            provider = provider(ModelExecutionLocation.LOCAL_NO_EGRESS),
            messageOrdinal = 1,
            studentMessage = "你好",
            priorMessages = emptyList(),
            occurredAtEpochMillis = 2_000,
        )

        assertNull(request.egressManifest)
    }

    @Test
    fun dynamicConversationIdScopesInputManifestHashAndRequestIdentity() {
        val first = buildTutorLobbyRequest(
            provider = provider(ModelExecutionLocation.EXTERNAL_PROVIDER),
            conversationId = "tutor-lobby:first",
            messageOrdinal = 1,
            studentMessage = "同一条消息",
            priorMessages = emptyList(),
            occurredAtEpochMillis = 2_000,
        )
        val second = buildTutorLobbyRequest(
            provider = provider(ModelExecutionLocation.EXTERNAL_PROVIDER),
            conversationId = "tutor-lobby:second",
            messageOrdinal = 1,
            studentMessage = "同一条消息",
            priorMessages = emptyList(),
            occurredAtEpochMillis = 2_000,
        )

        assertEquals("tutor-lobby:first", (first.input as TutorLobbyInput).conversationId)
        assertEquals("tutor-lobby:first", first.egressManifest?.subjectId)
        assertTrue(first.requestId != second.requestId)
    }

    @Test
    fun navigationRecoverySelectsTheLatestPendingLobbyTask() {
        val olderPending = lobbyTask(messageOrdinal = 1, status = ModelTaskStatus.QUEUED)
        val latestPending = lobbyTask(messageOrdinal = 2, status = ModelTaskStatus.STREAMING)
        val cancelled = lobbyTask(messageOrdinal = 3, status = ModelTaskStatus.CANCELLED)

        assertEquals(
            latestPending.request.requestId,
            latestPendingTutorLobbyTask(
                listOf(olderPending, latestPending, cancelled),
            )?.request?.requestId,
        )
    }

    @Test
    fun navigationRecoveryResumesPendingWorkWithoutTreatingItAsARetry() {
        val pending = lobbyTask(messageOrdinal = 1, status = ModelTaskStatus.STREAMING)
        val failed = lobbyTask(
            messageOrdinal = 1,
            status = ModelTaskStatus.RETRYABLE_FAILURE,
        )

        assertTrue(pending.canResumeTutorLobby())
        assertFalse(failed.canResumeTutorLobby())
    }

    @Test
    fun routeProjectionDoesNotCarryHistoryOrPendingWorkAcrossConversationIds() {
        val oldConversation = lobbyTask(
            messageOrdinal = 1,
            status = ModelTaskStatus.STREAMING,
            conversationId = "tutor-lobby:old",
        )
        val newConversation = lobbyTask(
            messageOrdinal = 1,
            status = ModelTaskStatus.QUEUED,
            conversationId = "tutor-lobby:new",
        )

        val projected = latestTutorLobbyConversationTasks(
            tasks = listOf(oldConversation, newConversation),
            conversationId = "tutor-lobby:new",
        )

        assertEquals(listOf(newConversation.request.requestId), projected.map { it.request.requestId })
        assertEquals("tutor-lobby:new", (projected.single().request.input as TutorLobbyInput).conversationId)
    }

    @Test
    fun cancelledLobbyMessageIsATombstoneAndDoesNotAdvanceTheNextOrdinal() {
        val visible = latestTutorLobbyConversationTasks(
            listOf(
                lobbyTask(messageOrdinal = 1, status = ModelTaskStatus.QUEUED),
                lobbyTask(messageOrdinal = 2, status = ModelTaskStatus.CANCELLED),
            ),
        )

        assertEquals(
            listOf(1),
            visible.map { (it.request.input as TutorLobbyInput).messageOrdinal },
        )
        val nextOrdinal = visible
            .maxOfOrNull { (it.request.input as TutorLobbyInput).messageOrdinal }
            ?.plus(1)
            ?: 1
        assertEquals(2, nextOrdinal)
    }

    @Test
    fun aLobbyMessageCanCreateOnlyOneDurableRetryEnvelope() {
        val initialFailure = lobbyTask(
            messageOrdinal = 1,
            status = ModelTaskStatus.RETRYABLE_FAILURE,
            attempt = 0,
        )
        val retryFailure = lobbyTask(
            messageOrdinal = 1,
            status = ModelTaskStatus.RETRYABLE_FAILURE,
            attempt = 1,
        )

        assertEquals(1, initialFailure.nextTutorLobbyRetryAttempt())
        assertNull(retryFailure.nextTutorLobbyRetryAttempt())
        assertFalse(retryFailure.canRetryTutorLobby())
    }

    private fun lobbyTask(
        messageOrdinal: Int,
        status: ModelTaskStatus,
        attempt: Int = 0,
        conversationId: String = TUTOR_LOBBY_CONVERSATION_ID,
        visualRequest: TutorLobbyVisualRequest? = null,
    ): ModelTaskSnapshot {
        val provider = provider(ModelExecutionLocation.LOCAL_NO_EGRESS)
        val request = buildTutorLobbyRequest(
            provider = provider,
            messageOrdinal = messageOrdinal,
            studentMessage = "消息$messageOrdinal",
            priorMessages = emptyList(),
            occurredAtEpochMillis = messageOrdinal.toLong(),
            attempt = attempt,
            conversationId = conversationId,
            explicitVisualRequest = visualRequest,
        )
        val input = request.input as TutorLobbyInput
        return ModelTaskSnapshot(
            taskId = "task-$messageOrdinal",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = status,
            stateVersion = 1,
            stage = ModelTaskStage.PREPARING,
            userMessage = "处理中",
            attemptCount = 1,
            provider = provider,
            output = if (status == ModelTaskStatus.SUCCEEDED) {
                TutorLobbyOutput(
                    conversationId = input.conversationId,
                    messageOrdinal = input.messageOrdinal,
                    messageMarkdown = "已完成当前回复。",
                    explanationMode = input.explanationMode,
                    modeVersion = input.modeVersion,
                    modelVersion = "model-v1",
                )
            } else {
                null
            },
            failure = if (status == ModelTaskStatus.RETRYABLE_FAILURE) {
                ModelTaskFailure(
                    code = ModelFailureCode.TIMEOUT,
                    message = "暂时没有完成",
                    retryable = true,
                )
            } else {
                null
            },
            createdAtEpochMillis = messageOrdinal.toLong(),
            updatedAtEpochMillis = messageOrdinal.toLong(),
        )
    }

    private fun provider(location: ModelExecutionLocation) = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "模型",
        modelId = "model",
        supportedTasks = setOf(ModelTaskKind.TUTOR_LOBBY),
        supportsImageInput = false,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = location,
        providerConfigurationVersion = "configuration-v1",
    )
}
