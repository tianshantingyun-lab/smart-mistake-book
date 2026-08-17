package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLobbyModelTaskPolicyTest {
    @Test
    fun externalRequestBindsExactMessageAndLeastDisclosure() {
        val request = buildTutorLobbyRequest(
            provider = provider(ModelExecutionLocation.EXTERNAL_PROVIDER),
            conversationId = "conversation-test",
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
            conversationId = "conversation-test",
            messageOrdinal = 1,
            studentMessage = "你好",
            priorMessages = emptyList(),
            occurredAtEpochMillis = 2_000,
        )

        assertNull(request.egressManifest)
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
