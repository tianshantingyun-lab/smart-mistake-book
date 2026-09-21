package com.tingyun.smartmistakebook.core.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorDifficultyTier
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorRoundQuestionDeclaration
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
 * T6 MASTERY_UPDATE 工具环端到端（spec model-intent-routing §5）：
 * Respond 会话中模型申请 MASTERY_UPDATE → 授权放行（声明集含 T6）→
 * runner 走 MasteryWriteGate → 空库无知识节点锚定 → 被拒但落 rejected 观察行
 * （errorKind = rejected:… 而非 not_authorized——区分"授权层挡"与"gate 拒"）。
 *
 * 关键断言：修复断点前（feature 声明集缺 T6），授权交集裁掉 MASTERY_UPDATE，
 * outcome errorKind = not_authorized；修复后 gate 实际运行，errorKind =
 * rejected:KNOWLEDGE_NODE_NOT_ANCHORED——证明 T6 真被放行到 gate。
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

    private fun request(): ModelTaskRequest {
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
            // 本轮有绑定题：写工具（MASTERY_UPDATE）的准入是本轮**确实**绑定了题，而不是
            // "输入类型是 Respond"。菜单里那一条的锚词（"配方法"）同时出现在学生消息与题干里，
            // 两条本地校验都过。
            boundQuestionCandidates = listOf(boundCandidate()),
        )
        return ModelTaskRequest(
            requestId = "tutor-respond:t6-test",
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

    private fun roundBinding() = TutorRoundQuestionDeclaration(
        problemId = "problem-peifang",
        problemRevisionId = "revision-peifang",
        anchorTerms = listOf("配方法"),
    )

    private fun masteryUpdateToolRequest(
        anchor: TutorRoundQuestionDeclaration? = roundBinding(),
    ) = TutorToolRequestsOutput(
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
                terms = listOf("knowledge-node-peifang"),
                direction = TutorEvidenceDirection.POSITIVE,
                understanding = TutorUnderstandingTier.CONFIDENT,
                difficultyTier = TutorDifficultyTier.MEDIUM,
                confidence = 0.85,
                // 逐次题锚：写工具在**工具轮**执行，而"本轮在说哪道题"的声明在原生
                // tool_calls 路由上无处可放，所以准入落在每一次调用自己身上。
                boundQuestion = anchor,
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
        boundQuestion = roundBinding(),
        modelVersion = "t6-model-v1",
    )

    @Test
    fun masteryUpdateRunsThroughGateAndIsRejectedAsUnanchoredNotUnAuthorized() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "t6-gate-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = StudyDatabaseFactory.open(context, databaseName)
        try {
            val gateway = ScriptedGateway(provider, listOf(masteryUpdateToolRequest(), finalAnswer()))
            val repository = com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository(
                database = database,
                gateway = gateway,
                clock = { 2_000L },
            )
            val snapshots = repository.execute(request()).toList()

            val final = snapshots.last()
            assertEquals(ModelTaskStatus.SUCCEEDED, final.status)
            assertTrue(final.output is TutorRespondOutput)
            assertEquals("应派遣两轮", 2, gateway.dispatchCount)

            // 工具轮 outcome 走 gate 拒（未锚定知识节点），而非授权层 not_authorized。
            val second = gateway.dispatchLog[1].input as TutorRespondInput
            assertTrue("第二轮应携带工具轮结果", second.toolRoundResults.isNotEmpty())
            val outcome = second.toolRoundResults[0].outcomes.single()
            assertEquals(TutorToolName.MASTERY_UPDATE, outcome.tool)
            assertEquals(false, outcome.ok)
            assertTrue(
                "gate 拒因应表明未锚定而非未授权：${outcome.errorKind}",
                outcome.errorKind == "rejected:KNOWLEDGE_NODE_NOT_ANCHORED",
            )

            // rejected 观察行已落库（可审计、不进投影）。
            val rows = database.readChatEvidenceByConversation("tutor-conv:captured:t6-session")
            assertEquals(1, rows.size)
            assertTrue(rows.single().isRejected)
            assertEquals("KNOWLEDGE_NODE_NOT_ANCHORED", rows.single().rejected_reason)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * 轮次门控的**仓库接线**（F2）：无题轮的写工具在仓库这一层就被拒，且不触达 runner。
     *
     * 与相邻用例只差题锚：菜单里有候选（不是"无菜单"），但这一次调用没有复述锚、本轮请求侧
     * 也没有已知题锚——按语义就是无题轮。判定本身（`tutorToolRoundOutcomes`）已有本机可跑的
     * 红绿用例（`TutorToolRoundGateTest`），但"仓库是否把它接上"此前只有正方向的仪器用例覆盖：
     * 删掉仓库那条接线后 MASTERY_UPDATE 会被直接执行，下面三处断言（outcome 拒因、
     * `executedCallCount`、证据簿记行数）会同时转红。
     *
     * 本用例需要设备（Room + AndroidX Test），由设备阶段实跑。
     */
    @Test
    fun anUnanchoredWriteCallIsRefusedBeforeItReachesTheRunner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "t6-unanchored-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = StudyDatabaseFactory.open(context, databaseName)
        try {
            val gateway = ScriptedGateway(
                provider,
                listOf(masteryUpdateToolRequest(anchor = null), finalAnswer()),
            )
            val repository = com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository(
                database = database,
                gateway = gateway,
                clock = { 2_000L },
            )
            val snapshots = repository.execute(request()).toList()

            val final = snapshots.last()
            assertEquals(ModelTaskStatus.SUCCEEDED, final.status)
            assertEquals("应派遣两轮", 2, gateway.dispatchCount)

            val second = gateway.dispatchLog[1].input as TutorRespondInput
            val outcome = second.toolRoundResults[0].outcomes.single()
            assertEquals(TutorToolName.MASTERY_UPDATE, outcome.tool)
            assertEquals("无题轮的写调用必须停在授权/门控层", false, outcome.ok)
            assertEquals(
                "拒因应是「未授权」而不是 runner 的 gate 拒：${outcome.errorKind}",
                "not_authorized",
                outcome.errorKind,
            )
            assertEquals(
                "被拒的写调用不得触达执行器",
                0,
                repository.toolRunner.executedCallCount,
            )
            assertTrue(
                "被拒的写调用不得留下任何证据行",
                database.readChatEvidenceByConversation("tutor-conv:captured:t6-session").isEmpty(),
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
