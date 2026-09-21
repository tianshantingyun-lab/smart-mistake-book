package com.tingyun.smartmistakebook.core.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.ErrorBookEntrySeedRecord
import com.tingyun.smartmistakebook.core.database.PracticeUnitSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemRevisionSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.model.ContentBlock
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
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorToolCall
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorToolRequestsOutput
import com.tingyun.smartmistakebook.core.model.TutorToolRoundResult
import com.tingyun.smartmistakebook.core.model.TRUNCATED_OUTCOME_NOTE
import com.tingyun.smartmistakebook.core.data.study.RoomTutorToolRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 工具环协议行为测试（spec model-intent-routing §3.1）：假网关第 1 轮返回
 * toolRequests、本地执行后第 2 轮返回最终回答——断言执行器被调用、派遣两次、
 * 终态正确；以及模型在工具轮配额（MAX_TOOL_ROUNDS=5）用尽后仍不作答时
 * 必须快速失败，绝不允许违规输出变成 SUCCEEDED。
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

    /**
     * 同一个模型服务题轮派遣。轮预算那条用例必须在题轮上跑（见该用例的 KDoc）：
     * 只有题轮的披露集合可能覆盖错题本条目标题所属的那一类。
     */
    private val respondProvider = provider.copy(
        supportedTasks = setOf(ModelTaskKind.TUTOR_RESPOND),
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
        val input = com.tingyun.smartmistakebook.core.model.TutorLobbyInput(
            conversationId = "tool-loop-conversation",
            messageOrdinal = 1,
            studentMessage = "帮我看看错题本里有没有二次函数的题",
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

    /**
     * 题轮派遣，且本轮**带候选菜单**：披露集合因此覆盖 `RELATED_QUESTION_CANDIDATES`，
     * `NOTEBOOK_READ` 才会逐条列出别的题的标题（F5 的两档之一）。
     */
    private fun questionRoundRequest(): ModelTaskRequest {
        val input = TutorRespondInput(
            sessionId = "tool-loop-session",
            draftRevisionNumber = 1,
            subject = "MATH",
            questionDocument = QuestionDocument(
                id = "question-tool-loop",
                blocks = listOf(ContentBlock.Paragraph("stem-tool-loop", "求函数的最值。")),
            ),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "帮我看看错题本里有没有二次函数的题",
            priorMessages = emptyList(),
            requestedMove = null,
            boundQuestionCandidates = listOf(boundCandidate()),
        )
        return ModelTaskRequest(
            requestId = "tutor-respond:tool-loop-budget",
            input = input,
            occurredAtEpochMillis = 1_000L,
            egressManifest = null,
        )
    }

    private fun boundCandidate() = RelatedProblemCandidate(
        problemId = "problem-menu",
        problemRevisionId = "revision-menu",
        subject = SubjectKind.MATH,
        title = "二次函数综合题",
        questionDocument = QuestionDocument(
            id = "question-menu",
            blocks = listOf(ContentBlock.Paragraph("stem-menu", "求二次函数的最值。")),
        ),
    )

    private fun respondFinalAnswerOutput() = TutorRespondOutput(
        sessionId = "tool-loop-session",
        draftRevisionNumber = 1,
        questionDocumentId = "question-tool-loop",
        responseOrdinal = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        messageMarkdown = "错题本里有 3 道二次函数相关错题。",
        solutionRevealed = false,
        intentDecision = TutorIntentDecision(
            intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
            confidence = 0.9,
            explicitActionRequest = true,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
        ),
        modelVersion = "tool-loop-model-v1",
    )

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

    /**
     * 轮内总预算的**接线**证明（spec model-intent-routing §3.1）。
     *
     * 单工具上限管不到"一轮加起来"，所以这条测试让一个工具产出超过轮预算的结果：
     * 三条长标题的错题让 `NOTEBOOK_READ` 的摘要超过 4k。若仓库层不再走
     * `tutorToolRoundResult`（即再原样塞回 outcomes），这条测试会因为第二轮的
     * 结果超预算且没有截断说明而变红——纯函数单测抓不到这个缺口。
     *
     * **车为什么要换到题轮**（F5）：错题本条目标题属于 `RELATED_QUESTION_CANDIDATES`，
     * 只有本轮披露集合覆盖它时 `NOTEBOOK_READ` 才逐条列出标题。大厅轮的披露集合把它列为
     * 禁止，产出形态只剩"条数 + 检索词"——再长的标题也撑不出一个超预算的结果，用大厅轮
     * 就测不到这条接线了。三条断言逐字未动，换的只是承载体：本轮带候选菜单的题轮。
     */
    @Test
    fun aRoundWhoseResultsExceedTheBudgetIsTrimmedBeforeItIsCarriedForward() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tool-loop-round-budget-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = StudyDatabaseFactory.open(context, databaseName)
        try {
            database.seedFixture(longTitledSeed())
            val gateway = ScriptedGateway(
                respondProvider,
                listOf(toolRequestOutput(), respondFinalAnswerOutput()),
            )
            val repository = com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository(
                database = database,
                gateway = gateway,
                clock = { 2_000L },
            )
            repository.execute(questionRoundRequest()).toList()

            val second = gateway.dispatchLog[1].input as TutorRespondInput
            val outcomes = second.toolRoundResults.single().outcomes
            val total = outcomes.sumOf { it.summaryMarkdown.length } + outcomes.size - 1
            assertTrue(
                "携带给下一轮的结果超预算：$total",
                total <= TutorToolRoundResult.MAX_TOOL_ROUND_RESULT_CHARS,
            )
            assertTrue(
                "超预算的部分必须说明自己被截断了，否则模型会当成完整结果读",
                outcomes.any { it.summaryMarkdown.contains(TRUNCATED_OUTCOME_NOTE) },
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * 无题轮的错题本读取不点名任何一条目（F5 的**仓库接线**证明）。
     *
     * 大厅的披露集合把 `RELATED_QUESTION_CANDIDATES` / `CONFIRMED_QUESTION_DOCUMENT` 列为禁止，
     * 逐条列出别的题的标题就是清单少报。这条用例走完整接线（请求 → 仓库 → 工具环 → runner）：
     * 判据若没从请求传到执行器（或传反），下面的断言就会转红。同时钉住"放行但收紧"——无题轮
     * 仍然放行 NOTEBOOK_READ（学生问"错题本里有没有"要有工具可用），只是产出只剩条数与检索词。
     */
    @Test
    fun aLobbyRoundNotebookReadNamesNoEntry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tool-loop-lobby-notebook-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = StudyDatabaseFactory.open(context, databaseName)
        try {
            database.seedFixture(longTitledSeed())
            val gateway =
                ScriptedGateway(provider, listOf(toolRequestOutput(), finalAnswerOutput()))
            val repository = com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository(
                database = database,
                gateway = gateway,
                clock = { 2_000L },
            )
            repository.execute(request()).toList()

            val second = gateway.dispatchLog[1].input as TutorLobbyInput
            val outcome = second.toolRoundResults.single().outcomes.single()
            assertEquals(TutorToolName.NOTEBOOK_READ, outcome.tool)
            assertTrue("无题轮仍要放行这个读工具：${outcome.errorKind}", outcome.ok)
            val text = outcome.summaryMarkdown
            assertTrue("命中了三条就得说三条，模型才知道有没有命中: $text", text.contains("3 条"))
            assertFalse(
                "别的题的标题不得随请求下发（大厅清单把它列为禁止）: $text",
                text.contains("二次函数综合题"),
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    /** 三条长标题错题：足以让 `NOTEBOOK_READ`（单次最多 6 条）的摘要超过轮预算。 */
    private fun longTitledSeed(): StudySeedBundle {
        val longTitle = "二次函数综合题".repeat(200)
        return StudySeedBundle(
            problems = (1..3).map { index ->
                ProblemSeedRecord("budget-problem-$index", "budget-fingerprint-$index", "MATH", 1_000)
            },
            revisions = (1..3).map { index ->
                ProblemRevisionSeedRecord(
                    revisionId = "budget-revision-$index",
                    problemId = "budget-problem-$index",
                    revisionNumber = 1,
                    title = longTitle,
                    problemMarkdown = "求函数的最值。",
                    answerSpecId = "budget-answer-$index",
                    answerSpecSnapshot = "最值",
                    answerVerificationStatus = StudyDbValue.VerificationStatus.VERIFIED,
                    sourceType = "IMPORT",
                    sourceReference = null,
                    contentFingerprint = "budget-revision-fingerprint-$index",
                    createdAtEpochMillis = 2_000,
                )
            },
            practiceUnits = (1..3).map { index ->
                PracticeUnitSeedRecord(
                    practiceUnitId = "budget-unit-$index",
                    problemId = "budget-problem-$index",
                    problemRevisionId = "budget-revision-$index",
                    unitKey = "whole",
                    unitKind = "WHOLE",
                    title = longTitle,
                    promptMarkdown = "求最值。",
                    estimatedSeconds = 120,
                    createdAtEpochMillis = 3_000,
                )
            },
            errorBookEntries = (1..3).map { index ->
                ErrorBookEntrySeedRecord(
                    entryId = "budget-entry-$index",
                    practiceUnitId = "budget-unit-$index",
                    problemId = "budget-problem-$index",
                    currentRevisionId = "budget-revision-$index",
                    sourceKey = "budget-source-$index",
                    acceptedAtEpochMillis = 4_000,
                    updatedAtEpochMillis = 4_000,
                )
            },
        )
    }

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
            val snapshots = repository.execute(request())
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
    fun secondDispatchCarriesRoundResultsAndNonEmptyDeclarations() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tool-loop-carrier-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = StudyDatabaseFactory.open(context, databaseName)
        try {
            val gateway = ScriptedGateway(provider, listOf(toolRequestOutput(), finalAnswerOutput()))
            val repository = com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository(
                database = database,
                gateway = gateway,
                clock = { 2_000L },
            )
            repository.execute(request()).toList()

            assertEquals("应派遣两轮", 2, gateway.dispatchLog.size)
            val second = gateway.dispatchLog[1].input as TutorLobbyInput
            assertTrue("第二轮应携带首轮结果", second.toolRoundResults.isNotEmpty())
            assertEquals(1, second.toolRoundResults[0].roundOrdinal)
            assertEquals(TutorToolName.NOTEBOOK_READ, second.toolRoundResults[0].outcomes[0].tool)
            assertTrue("第二轮声明集非空", second.toolDeclarations.isNotEmpty())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun toolLoopBeyondRoundBudgetFailsFastWithoutSuccess() = runBlocking {
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
            val snapshots = repository.execute(request()).toList()

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
