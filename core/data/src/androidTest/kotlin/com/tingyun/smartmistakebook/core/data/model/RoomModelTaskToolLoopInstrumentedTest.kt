package com.tingyun.smartmistakebook.core.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import kotlinx.coroutines.flow.flow
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.TutorToolCall
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorToolRequestsOutput
import com.tingyun.smartmistakebook.core.data.study.RoomTutorToolRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 工具环协议行为测试（spec model-intent-routing §3.1）：假网关第 1 轮返回
 * toolRequests、本地执行后第 2 轮返回最终回答——断言执行器被调用、派遣两次、
 * 终态正确；以及未声明工具的任务上协议违规必须快速失败。
 */
@RunWith(AndroidJUnit4::class)
class RoomModelTaskToolLoopInstrumentedTest {

    private val provider = ProviderCapabilitySnapshot(
        providerId = "tool-loop-provider",
        providerDisplayName = "工具环测试模型",
        modelId = "tool-loop-model-v1",
        supportedTasks = setOf(ModelTaskKind.TUTOR_LOBBY),
        supportsImageInput = false,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
    )

    private class ScriptedGateway(
        private val provider: ProviderCapabilitySnapshot,
        private val script: List<ModelTaskOutput>,
    ) : ModelGateway {
        val dispatchCount: Int
            get() = dispatchLog.size
        val dispatchLog = mutableListOf<ModelTaskRequest>()

        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

        override fun execute(execution: ModelGatewayExecution): Flow<ModelGatewayEvent> = flow {
            val index = dispatchLog.size
            dispatchLog += execution.request
            emit(ModelGatewayEvent.Started(provider))
            emit(ModelGatewayEvent.Completed(script[index]))
        }
    }

    private fun request(declarations: List<TutorToolName>): ModelTaskRequest {
        val input = com.tingyun.smartmistakebook.core.model.TutorLobbyInput(
            conversationId = "tool-loop-conversation",
            messageOrdinal = 1,
            studentMessage = "帮我看看错题本里有没有二次函数的题",
            toolDeclarations = declarations,
        )
        val manifest = if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
            ModelEgressManifest(
                authorizationId = "authorization:tool-loop-test",
                subjectId = input.conversationId,
                purpose = ModelEgressPurpose.TUTORING,
                authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_LOBBY),
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_LOBBY,
                approvedAtEpochMillis = 1_000L,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE,
                prohibitedData = ModelEgressManifest.TUTOR_LOBBY_PROHIBITED_DATA,
            )
        } else {
            null
        }
        return ModelTaskRequest(
            requestId = "tutor-lobby:tool-loop-test",
            input = input,
            occurredAtEpochMillis = 1_000L,
            egressManifest = manifest,
        )
    }

    private fun toolRequestOutput() = TutorToolRequestsOutput(
        intentDecision = TutorIntentDecision(
            intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
            confidence = 0.9,
            explicitActionRequest = true,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
            lookupTerms = listOf("二次函数"),
        ),
        calls = listOf(
            TutorToolCall(
                tool = TutorToolName.NOTEBOOK_READ,
                rationale = "学生想找二次函数相关错题",
                terms = listOf("二次函数"),
            ),
        ),
        modelVersion = "tool-loop-model-v1",
    )

    private fun finalAnswerOutput() = TutorLobbyOutput(
        conversationId = "tool-loop-conversation",
        messageOrdinal = 1,
        messageMarkdown = "错题本里有 2 道二次函数相关错题。",
        intentDecision = TutorIntentDecision(
            intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
            confidence = 0.9,
            explicitActionRequest = true,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
        ),
        modelVersion = "tool-loop-model-v1",
    )

    @Test
    fun toolRequestRoundExecutesLocalToolsThenAnswers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tool-loop-protocol-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = StudyDatabaseFactory.open(context, databaseName)
        try {
            val gateway = ScriptedGateway(provider, listOf(toolRequestOutput(), finalAnswerOutput()))
            val repository = com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository(
                database = database,
                gateway = gateway,
                clock = { 2_000L },
            )
            val snapshots = repository.execute(request(listOf(TutorToolName.NOTEBOOK_READ, TutorToolName.MASTERY_READ)))
                .toList()

            val final = snapshots.last()
            assertEquals(ModelTaskStatus.SUCCEEDED, final.status)
            assertTrue(final.output is TutorLobbyOutput)
            assertEquals(2, gateway.dispatchCount)
            assertEquals(1, repository.toolRunner.executedCallCount)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun protocolViolationOnUndeclaredToolTaskFailsFast() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tool-loop-violation-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = StudyDatabaseFactory.open(context, databaseName)
        try {
            val gateway = ScriptedGateway(provider, listOf(toolRequestOutput(), toolRequestOutput(), toolRequestOutput()))
            val repository = com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository(
                database = database,
                gateway = gateway,
                clock = { 2_000L },
            )
            val snapshots = repository.execute(request(emptyList())).toList()

            val final = snapshots.last()
            // 协议违规的合法结局：任务进入拒绝态（永久或可重试均可），
            // 但绝不允许违规输出变成 SUCCEEDED。
            assertTrue(
                "协议违规任务不应成功：${final.status}",
                final.status == ModelTaskStatus.RETRYABLE_FAILURE ||
                    final.status == ModelTaskStatus.PERMANENT_FAILURE,
            )
            assertTrue(final.output !is TutorLobbyOutput)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
