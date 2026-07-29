package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorStreamEvent
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorLobbyRouteInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun consecutiveSendsUseCommittedDatabaseOrdinals() {
        val memory = TestLobbyMemoryRepository()
        val modelTasks = TestLobbyModelTasks()
        mount(memory, modelTasks)

        send("第一条")
        composeRule.waitUntil(5_000) { modelTasks.requests.size == 1 }
        send("第二条")
        composeRule.waitUntil(5_000) { modelTasks.requests.size == 2 }

        composeRule.runOnIdle {
            assertEquals(listOf(1, 2), memory.allocations.map { it.expectedTurnOrdinal })
            assertEquals(listOf(1, 2), modelTasks.requests.map { input(it).messageOrdinal })
        }
    }

    @Test
    fun newConversationDoesNotDisplayOldDraftHistoryOrActiveReply() {
        val memory = TestLobbyMemoryRepository()
        val modelTasks = TestLobbyModelTasks()
        mount(memory, modelTasks)

        send("旧消息")
        composeRule.waitUntil(5_000) { modelTasks.requests.size == 1 }
        composeRule.onNodeWithText("答：旧消息").assertIsDisplayed()
        composeRule.onNodeWithTag("tutor_draft_input").performTextInput("旧草稿")

        composeRule.onNodeWithTag("tutor_lobby_new_conversation").performClick()
        composeRule.waitUntil(5_000) { memory.createdConversationIds.size == 2 }

        composeRule.onNodeWithText("答：旧消息").assertDoesNotExist()
        composeRule.onNodeWithText("旧草稿").assertDoesNotExist()
        composeRule.onNodeWithTag("tutor_stream_activity").assertDoesNotExist()
        composeRule.onNodeWithTag("tutor_empty_state").assertIsDisplayed()
    }

    @Test
    fun oldPreparationThatCompletesAfterSwitchCannotStartInTheNewConversation() {
        val memory = TestLobbyMemoryRepository(blockFirstAllocation = true)
        val modelTasks = TestLobbyModelTasks()
        mount(memory, modelTasks)

        send("旧准备")
        runBlocking { memory.firstAllocationStarted.await() }
        composeRule.onNodeWithTag("tutor_lobby_new_conversation").performClick()
        composeRule.waitUntil(5_000) { memory.createdConversationIds.size == 2 }
        memory.releaseFirstAllocation.complete(Unit)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(1, memory.allocations.size)
            val newConversationId = memory.createdConversationIds.last()
            assertTrue(modelTasks.requests.none { input(it).conversationId == newConversationId })
        }
        composeRule.onNodeWithText("旧准备").assertDoesNotExist()
        composeRule.onNodeWithTag("tutor_empty_state").assertIsDisplayed()
    }

    @Test
    fun preparationRetryReplaysTheCommittedTurnWithoutASecondAllocation() {
        val memory = TestLobbyMemoryRepository(failAfterFirstCommit = true)
        val modelTasks = TestLobbyModelTasks()
        mount(memory, modelTasks)

        send("重试消息")
        composeRule.onNodeWithTag("tutor_chat_retry").performClick()
        composeRule.waitUntil(5_000) { modelTasks.requests.size == 1 }

        composeRule.runOnIdle {
            assertEquals(1, memory.allocations.size)
            assertEquals(1, input(modelTasks.requests.single()).messageOrdinal)
        }
    }

    private fun mount(memory: TestLobbyMemoryRepository, modelTasks: TestLobbyModelTasks) {
        composeRule.setContent {
            MaterialTheme {
                TutorLobbyRoute(
                    onCapture = {},
                    onChooseExisting = {},
                    onOpenCapabilitySettings = {},
                    onOpenMistakeNotebook = {},
                    onOpenProfile = {},
                    modelTasks = modelTasks,
                    learningMemory = memory,
                    learnerScopeId = TEST_LEARNER,
                    catalogEntries = emptyList(),
                    profile = StudyProfileOverview(),
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("tutor_empty_state").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun send(message: String) {
        composeRule.onNodeWithTag("tutor_draft_input").performTextInput(message)
        composeRule.onNodeWithTag("tutor_send_button").performClick()
    }
}

internal class TestLobbyMemoryRepository(
    private val failAfterFirstCommit: Boolean = false,
    private val blockFirstAllocation: Boolean = false,
) : TutorLearningMemoryRepository {
    private val conversations = linkedMapOf<String, TutorConversation>()
    private val receipts = mutableMapOf<String, Pair<TutorConversation, TutorTurnReceipt>>()
    val allocations = mutableListOf<AllocateTutorTurnCommand>()
    val createdConversationIds = mutableListOf<String>()
    val firstAllocationStarted = CompletableDeferred<Unit>()
    val releaseFirstAllocation = CompletableDeferred<Unit>()
    private var shouldFailAfterCommit = failAfterFirstCommit

    override suspend fun createConversation(command: CreateTutorConversationCommand): CreateTutorConversationResult =
        synchronized(this) {
            val conversation = TutorConversation(
                conversationId = command.conversationId,
                learnerScopeId = command.learnerScopeId,
                generation = command.conversationGeneration,
                status = TutorConversationStatus.ACTIVE,
                createdAtEpochMillis = command.occurredAtEpochMillis,
                archivedAtEpochMillis = null,
                stateVersion = 0,
            )
            conversations[conversation.conversationId] = conversation
            createdConversationIds += conversation.conversationId
            CreateTutorConversationResult.Created(conversation)
        }

    override suspend fun openConversation(command: OpenTutorConversationCommand): OpenTutorConversationResult =
        synchronized(this) {
            conversations[command.conversationId]
                ?.takeIf {
                    it.learnerScopeId == command.learnerScopeId &&
                        it.generation == command.conversationGeneration
                }
                ?.let(OpenTutorConversationResult::Opened)
                ?: OpenTutorConversationResult.NotFound
        }

    override suspend fun latestActiveConversation(learnerScopeId: String): TutorConversation? =
        synchronized(this) {
            conversations.values
                .filter { it.learnerScopeId == learnerScopeId && it.status == TutorConversationStatus.ACTIVE }
                .maxByOrNull(TutorConversation::createdAtEpochMillis)
        }

    override suspend fun archiveConversation(command: ArchiveTutorConversationCommand): ArchiveTutorConversationResult =
        synchronized(this) {
            val current = requireNotNull(conversations[command.conversationId])
            check(current.stateVersion == command.expectedConversationStateVersion)
            val archived = current.copy(
                status = TutorConversationStatus.ARCHIVED,
                archivedAtEpochMillis = command.occurredAtEpochMillis,
            )
            conversations[current.conversationId] = archived
            ArchiveTutorConversationResult.Archived(archived)
        }

    override suspend fun allocateTurn(command: AllocateTutorTurnCommand): AllocateTutorTurnResult {
        val result = synchronized(this) {
            receipts[command.clientIdempotencyKey]?.let { (conversation, receipt) ->
                return@synchronized AllocateTutorTurnResult.Replayed(conversation, receipt)
            }
            val current = requireNotNull(conversations[command.conversationId])
            check(current.status == TutorConversationStatus.ACTIVE)
            check(current.stateVersion == command.expectedConversationStateVersion)
            check(command.expectedTurnOrdinal == current.stateVersion.toInt() + 1)
            val updated = current.copy(stateVersion = current.stateVersion + 1)
            val receipt = TutorTurnReceipt(
                turnReceiptId = command.turnReceiptId,
                conversationId = current.conversationId,
                conversationGeneration = current.generation,
                conversationStateVersion = updated.stateVersion,
                turnOrdinal = command.expectedTurnOrdinal,
                subject = SubjectKind.GENERAL,
                problemAnchorId = null,
                requestVersion = command.requestVersion,
                modeVersion = command.modeVersion,
                explanationMode = command.mode,
                directiveFingerprint = command.directiveFingerprint,
                studentMessageFingerprint = command.studentMessageFingerprint,
                studentMessageSummary = command.studentMessageSummary,
                occurredAtEpochMillis = command.occurredAtEpochMillis,
            )
            conversations[current.conversationId] = updated
            receipts[command.clientIdempotencyKey] = updated to receipt
            allocations += command
            AllocateTutorTurnResult.Created(updated, receipt)
        }
        if (allocations.size == 1) firstAllocationStarted.complete(Unit)
        if (blockFirstAllocation && allocations.size == 1) {
            withContext(NonCancellable) { releaseFirstAllocation.await() }
        }
        if (shouldFailAfterCommit) {
            shouldFailAfterCommit = false
            error("caller did not receive committed turn")
        }
        return result
    }

    override suspend fun prepareEvidenceRequest(command: PrepareTutorEvidenceCommand): PrepareTutorEvidenceResult =
        error("Lobby does not prepare evidence")

    override suspend fun finalizeEvidence(command: FinalizeTutorEvidenceCommand): FinalizeTutorEvidenceResult =
        error("Lobby does not finalize evidence")
}

private class TestLobbyModelTasks : ModelTaskRepository {
    private val tasksBySubject = MutableStateFlow<Map<String, List<ModelTaskSnapshot>>>(emptyMap())
    val requests = mutableListOf<ModelTaskRequest>()

    override suspend fun capabilities(): ProviderCapabilitySnapshot = TEST_PROVIDER

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

    override fun observeBySubject(subjectId: String, kind: ModelTaskKind): Flow<List<ModelTaskSnapshot>> =
        tasksBySubject.map { it[subjectId].orEmpty().filter { task -> task.request.input.kind == kind } }

    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow { }

    override fun executeTutorStream(
        request: ModelTaskRequest,
        identity: TutorStreamIdentity,
    ): Flow<TutorStreamEvent> = flow {
        requests += request
        val input = input(request)
        tasksBySubject.value = tasksBySubject.value + (
            input.conversationId to tasksBySubject.value[input.conversationId].orEmpty().plus(
                succeededTask(request, input),
            )
        )
        emit(TutorStreamEvent.Started(identity))
        emit(
            TutorStreamEvent.Completed(
                identity,
                TutorMarkdownSnapshot.completedLiteral("答：${input.studentMessage}"),
            ),
        )
    }
}

private fun succeededTask(request: ModelTaskRequest, input: TutorLobbyInput) = ModelTaskSnapshot(
    taskId = "task:${request.requestId}",
    request = request,
    requestFingerprint = ModelTaskFingerprint.of(request),
    status = ModelTaskStatus.SUCCEEDED,
    stateVersion = 1,
    stage = ModelTaskStage.PREPARING,
    userMessage = "已完成",
    attemptCount = 1,
    provider = TEST_PROVIDER,
    output = TutorLobbyOutput(
        conversationId = input.conversationId,
        messageOrdinal = input.messageOrdinal,
        messageMarkdown = "答：${input.studentMessage}",
        intentDecision = TutorIntentDecision.ambiguousDefault(),
        modelVersion = "test-v1",
    ),
    createdAtEpochMillis = request.occurredAtEpochMillis,
    updatedAtEpochMillis = request.occurredAtEpochMillis,
)

private fun input(request: ModelTaskRequest): TutorLobbyInput = request.input as TutorLobbyInput

private const val TEST_LEARNER = "instrumented-learner"

private val TEST_PROVIDER = ProviderCapabilitySnapshot(
    providerId = "local-test",
    providerDisplayName = "本地测试模型",
    modelId = "local-test-model",
    supportedTasks = setOf(ModelTaskKind.TUTOR_LOBBY),
    supportsImageInput = false,
    supportsStructuredOutput = true,
    supportsStreaming = true,
    executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
    providerConfigurationVersion = "local-test-v1",
)
