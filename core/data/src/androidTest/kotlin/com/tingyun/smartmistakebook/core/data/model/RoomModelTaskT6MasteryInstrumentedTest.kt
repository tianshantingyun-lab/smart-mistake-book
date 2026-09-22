package com.tingyun.smartmistakebook.core.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorDifficultyTier
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeCode
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeCodeRole
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorToolCall
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorToolRequestsOutput
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T6 MASTERY_UPDATE 工具环端到端（spec model-intent-routing §5；2026-09-21 裁定
 * ADR 0001 / D5-D6 改写口径）。
 *
 * 旧口径（审计 P2 断链二被钉成"期望行为"的那套）：模型只能传原始 id，空库编造 id →
 * `rejected:KNOWLEDGE_NODE_NOT_ANCHORED`。新口径（单一代号通道）：
 * - 合法代号（本会话已披露，K1）→ 服务端解析代号→id → 走统一本地门；空库无节点锚定 →
 *   仍然 `rejected:KNOWLEDGE_NODE_NOT_ANCHORED`（gate 拒，而非 not_authorized /
 *   invalid_knowledge_code——三层拒因互不混淆）；
 * - 编造代号（K9，不在已披露集合）→ **结构性拒**（invalid_knowledge_code），不进执行器、
 *   不落任何证据行（"编造 term 期望拒写"的语义保留，口径变真）。
 * - 无题轮（声明与请求侧已知锚都缺）不再结构性拒写：写调用照旧放行到执行器，由统一门裁决。
 */
@RunWith(AndroidJUnit4::class)
class RoomModelTaskT6MasteryInstrumentedTest {

    private val provider = ProviderCapabilitySnapshot(
        providerId = "t6-provider",
        providerDisplayName = "T6 测试模型",
        modelId = "t6-model-v1",
        supportedTasks = setOf(ModelTaskKind.TUTOR_RESPOND),
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

    /** 本会话预披露：K1 = 当前题确认绑定的 kc-peifang（空库：节点行不存在）。 */
    private fun request(requestId: String): ModelTaskRequest {
        val input = TutorRespondInput(
            sessionId = "t6-session",
            draftRevisionNumber = 1,
            subject = "数学",
            questionDocument = QuestionDocument(
                id = "question-1",
                blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
            ),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "我现在理解配方法这一步了。",
            priorMessages = emptyList(),
            requestedMove = null,
            // 候选菜单保留（轮次绑定基底不回滚）；knownRoundQuestion = null → 请求侧
            // 没有已知题锚 → 真的无题轮（D6 之后写工具不再因此被拒）。
            boundQuestionCandidates = listOf(boundCandidate()),
            knowledgeCodes = listOf(
                TutorKnowledgeCode(
                    knowledgeNodeId = "kc-peifang",
                    displayName = "配方法",
                    role = TutorKnowledgeCodeRole.CONFIRMED_BINDING,
                ),
            ),
        )
        return ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = 1_000L,
            egressManifest = null,
        )
    }

    /** 菜单里那一条候选：锚词 "配方法" 在标题与题干里都有。 */
    private fun boundCandidate() = RelatedProblemCandidate(
        problemId = "problem-peifang",
        problemRevisionId = "revision-peifang",
        subject = SubjectKind.MATH,
        title = "配方法解一元二次方程",
        questionDocument = QuestionDocument(
            id = "question-peifang",
            blocks = listOf(ContentBlock.Paragraph("stem-peifang", "用配方法求函数的单调区间。")),
        ),
    )

    private fun masteryUpdateToolRequest(code: String) = TutorToolRequestsOutput(
        intentDecision = TutorIntentDecision(
            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
            confidence = 0.95,
            explicitActionRequest = false,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.NONE,
        ),
        calls = listOf(
            TutorToolCall(
                tool = TutorToolName.MASTERY_UPDATE,
                rationale = "学生明确说现在理解了配方法。",
                terms = listOf(code),
                direction = TutorEvidenceDirection.POSITIVE,
                understanding = TutorUnderstandingTier.CONFIDENT,
                difficultyTier = TutorDifficultyTier.MEDIUM,
                confidence = 0.85,
                // 无题轮（D6）：不再带逐次题锚；轮次绑定只走最终回答信封。
                boundQuestion = null,
            ),
        ),
        modelVersion = "t6-model-v1",
    )

    private fun finalAnswer() = TutorRespondOutput(
        sessionId = "t6-session",
        draftRevisionNumber = 1,
        questionDocumentId = "question-1",
        responseOrdinal = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        messageMarkdown = "很好，配方法的关键是把二次项系数化为 1 后再配方。",
        solutionRevealed = false,
        intentDecision = TutorIntentDecision(
            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
            confidence = 0.95,
            explicitActionRequest = false,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.NONE,
        ),
        modelVersion = "t6-model-v1",
    )

    @Test
    fun aLegalCodeWriteRunsThroughTheGateAndIsRejectedAsUnanchoredNotUnAuthorized() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "t6-gate-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = StudyDatabaseFactory.open(context, databaseName)
        try {
            val gateway = ScriptedGateway(provider, listOf(masteryUpdateToolRequest("K1"), finalAnswer()))
            val repository = com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository(
                database = database,
                gateway = gateway,
                clock = { 2_000L },
            )
            val snapshots = repository.execute(request("tutor-respond:t6-legal-code")).toList()

            val final = snapshots.last()
            assertEquals(ModelTaskStatus.SUCCEEDED, final.status)
            assertTrue(final.output is TutorRespondOutput)
            assertEquals("应派遣两轮", 2, gateway.dispatchCount)

            // 工具轮 outcome 走 gate 拒（代号合法 → 已解析为 kc-peifang → 空库无节点锚定），
            // 而非授权层 not_authorized、也非白名单 invalid_knowledge_code。
            val second = gateway.dispatchLog[1].input as TutorRespondInput
            assertTrue("第二轮应携带工具轮结果", second.toolRoundResults.isNotEmpty())
            val outcome = second.toolRoundResults[0].outcomes.single()
            assertEquals(TutorToolName.MASTERY_UPDATE, outcome.tool)
            assertEquals(false, outcome.ok)
            assertTrue(
                "gate 拒因应表明未锚定而非未授权：${outcome.errorKind}",
                outcome.errorKind == "rejected:KNOWLEDGE_NODE_NOT_ANCHORED",
            )

            // rejected 观察行已落库（可审计、不进投影），anchor_class 按代号角色机械确立。
            val rows = database.readChatEvidenceByConversation("tutor-conv:captured:t6-session")
            assertEquals(1, rows.size)
            assertTrue(rows.single().isRejected)
            assertEquals("KNOWLEDGE_NODE_NOT_ANCHORED", rows.single().rejected_reason)
            assertEquals("CONFIRMED", rows.single().anchor_class)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * 轮次门控的**仓库接线**（F2 用例的 D6 改写）：无题轮的写调用不再在仓库这一层被拒——
     * 它放行到 runner，由统一本地门裁决（本库空 → gate 拒 + 观察行）。断言的是
     * "仓库真的把它放行了"：`executedCallCount == 1` 与证据行存在这两处，在旧的
     * "无题轮拒写"接线下一定会同时转红。
     */
    @Test
    fun aNoQuestionRoundWriteReachesTheRunnerAndTheGateDecides() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "t6-noquestion-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = StudyDatabaseFactory.open(context, databaseName)
        try {
            val gateway = ScriptedGateway(provider, listOf(masteryUpdateToolRequest("K1"), finalAnswer()))
            val repository = com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository(
                database = database,
                gateway = gateway,
                clock = { 2_000L },
            )
            val snapshots = repository.execute(request("tutor-respond:t6-noquestion")).toList()

            val final = snapshots.last()
            assertEquals(ModelTaskStatus.SUCCEEDED, final.status)
            assertEquals("应派遣两轮", 2, gateway.dispatchCount)

            val second = gateway.dispatchLog[1].input as TutorRespondInput
            val outcome = second.toolRoundResults[0].outcomes.single()
            assertEquals(TutorToolName.MASTERY_UPDATE, outcome.tool)
            assertEquals(
                "无题轮的写调用放行到执行器，由统一门裁决（不再轮次层拒）：${outcome.errorKind}",
                "rejected:KNOWLEDGE_NODE_NOT_ANCHORED",
                outcome.errorKind,
            )
            assertEquals(
                "写调用确实触达了执行器（仓库接线在放行方向）",
                1,
                repository.toolRunner.executedCallCount,
            )
            assertEquals(
                "gate 拒写落 rejected 观察行（可审计）",
                1,
                database.readChatEvidenceByConversation("tutor-conv:captured:t6-session").size,
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * 编造代号的结构性拒（原"编造 term 期望拒写"的口径翻转）：K9 不在本会话已披露集合 →
     * 白名单拒（invalid_knowledge_code），**不触达执行器**、不落任何证据行。
     */
    @Test
    fun aFabricatedCodeIsStructurallyRefusedBeforeItReachesTheRunner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "t6-fabricated-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = StudyDatabaseFactory.open(context, databaseName)
        try {
            val gateway = ScriptedGateway(provider, listOf(masteryUpdateToolRequest("K9"), finalAnswer()))
            val repository = com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository(
                database = database,
                gateway = gateway,
                clock = { 2_000L },
            )
            val snapshots = repository.execute(request("tutor-respond:t6-fabricated")).toList()

            val final = snapshots.last()
            assertEquals(ModelTaskStatus.SUCCEEDED, final.status)
            assertEquals("应派遣两轮", 2, gateway.dispatchCount)

            val second = gateway.dispatchLog[1].input as TutorRespondInput
            val outcome = second.toolRoundResults[0].outcomes.single()
            assertEquals(TutorToolName.MASTERY_UPDATE, outcome.tool)
            assertEquals(
                "编造代号走协议错误路径（白名单结构性拒），不是 gate 语义拒：${outcome.errorKind}",
                "invalid_knowledge_code",
                outcome.errorKind,
            )
            assertEquals(
                "结构性拒的调用不得触达执行器",
                0,
                repository.toolRunner.executedCallCount,
            )
            assertTrue(
                "结构性拒不得留下任何证据行",
                database.readChatEvidenceByConversation("tutor-conv:captured:t6-session").isEmpty(),
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
