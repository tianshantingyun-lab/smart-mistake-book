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
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLobbyModelTaskPolicyTest {
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
    ): ModelTaskSnapshot {
        val provider = provider(ModelExecutionLocation.LOCAL_NO_EGRESS)
        val request = buildTutorLobbyRequest(
            provider = provider,
            messageOrdinal = messageOrdinal,
            studentMessage = "消息$messageOrdinal",
            priorMessages = emptyList(),
            occurredAtEpochMillis = messageOrdinal.toLong(),
            attempt = attempt,
        )
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
