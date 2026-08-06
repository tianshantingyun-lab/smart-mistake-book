package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorLobbyAllocatedTurn
import com.tingyun.smartmistakebook.core.domain.TutorLobbyConversation
import com.tingyun.smartmistakebook.core.domain.TutorLobbyTurnRequest
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorStreamEvent
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.SmartDarkColors
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
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
    fun consecutiveSendsUseCommittedLobbyPortOrdinals() {
        val memory = TestTutorConversationLobbyPort()
        val modelTasks = TestLobbyModelTasks()
        mount(memory, modelTasks)

        send("第一条")
        composeRule.waitUntil(5_000) { modelTasks.requests.size == 1 }
        send("第二条")
        composeRule.waitUntil(5_000) { modelTasks.requests.size == 2 }

        composeRule.runOnIdle {
            assertEquals(listOf(1, 2), memory.allocatedOrdinals)
            assertEquals(listOf(1, 2), modelTasks.requests.map { input(it).messageOrdinal })
        }
    }

    @Test
    fun newConversationDoesNotDisplayOldDraftHistoryOrActiveReply() {
        val memory = TestTutorConversationLobbyPort()
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
        val memory = TestTutorConversationLobbyPort(blockFirstAllocation = true)
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
        val memory = TestTutorConversationLobbyPort(failAfterFirstCommit = true)
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

    @Test
    fun darkThemeRendersTutorLobbyRootAndCapturesScreenshot() {
        val memory = TestTutorConversationLobbyPort()
        val modelTasks = TestLobbyModelTasks()
        var darkBackground = Color.Unspecified
        var darkPaper = Color.Unspecified

        composeRule.setContent {
            SmartMistakeBookTheme(darkTheme = true) {
                darkBackground = MaterialTheme.colorScheme.background
                darkPaper = Paper
                TutorLobbyRoute(
                    onCapture = {},
                    onChooseExisting = {},
                    onOpenCapabilitySettings = {},
                    onOpenMistakeNotebook = {},
                    onOpenProfile = {},
                    modelTasks = modelTasks,
                    conversationLobby = memory,
                    catalogEntries = emptyList(),
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("tutor_empty_state").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tutor_empty_state").assertIsDisplayed()
        assertEquals(SmartDarkColors.Paper, darkBackground)
        assertEquals(SmartDarkColors.Paper, darkPaper)
        saveAuditScreenshot("tutor-home-dark.png")
    }

    private fun mount(memory: TestTutorConversationLobbyPort, modelTasks: TestLobbyModelTasks) {
        composeRule.setContent {
            MaterialTheme {
                TutorLobbyRoute(
                    onCapture = {},
                    onChooseExisting = {},
                    onOpenCapabilitySettings = {},
                    onOpenMistakeNotebook = {},
                    onOpenProfile = {},
                    modelTasks = modelTasks,
                    conversationLobby = memory,
                    catalogEntries = emptyList(),
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

    private fun saveAuditScreenshot(fileName: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$fileName")
            .close()
    }
}

internal class TestTutorConversationLobbyPort(
    private val failAfterFirstCommit: Boolean = false,
    private val blockFirstAllocation: Boolean = false,
) : TutorConversationLobbyPort {
    private val tokenBindings = mutableMapOf<String, String>()
    private val replays = mutableMapOf<TutorLobbyTurnRequest, TutorLobbyAllocatedTurn>()
    private val nextOrdinalByConversation = mutableMapOf<String, Int>()
    private var currentConversation: TutorLobbyConversation? = null
    private var nextConversationNumber = 0
    val allocations = mutableListOf<TutorLobbyTurnRequest>()
    val allocatedOrdinals = mutableListOf<Int>()
    val createdConversationIds = mutableListOf<String>()
    val firstAllocationStarted = CompletableDeferred<Unit>()
    val releaseFirstAllocation = CompletableDeferred<Unit>()
    private var shouldFailAfterCommit = failAfterFirstCommit

    override suspend fun openCurrent(): TutorLobbyConversation = synchronized(this) {
        currentConversation ?: createConversation(TUTOR_LOBBY_CONVERSATION_ID)
    }

    override suspend fun startNew(currentConversationToken: String): TutorLobbyConversation =
        synchronized(this) {
            check(currentConversationToken in tokenBindings)
            createConversation("tutor-lobby:new-${++nextConversationNumber}")
        }

    override suspend fun allocateTurn(request: TutorLobbyTurnRequest): TutorLobbyAllocatedTurn {
        val result = synchronized(this) {
            replays[request]?.let { return@synchronized it }
            val conversationId = requireNotNull(tokenBindings[request.conversationToken])
            val ordinal = nextOrdinalByConversation.getOrDefault(conversationId, 0) + 1
            nextOrdinalByConversation[conversationId] = ordinal
            val updated = TutorLobbyConversation(
                conversationId = conversationId,
                conversationToken = testToken(createdConversationIds.size + ordinal + 1),
            )
            tokenBindings[updated.conversationToken] = updated.conversationId
            if (currentConversation?.conversationId == conversationId) currentConversation = updated
            TutorLobbyAllocatedTurn(updated, ordinal).also { allocation ->
                replays[request] = allocation
                allocations += request
                allocatedOrdinals += ordinal
            }
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

    private fun createConversation(conversationId: String): TutorLobbyConversation {
        val conversation = TutorLobbyConversation(
            conversationId = conversationId,
            conversationToken = testToken(createdConversationIds.size + 1),
        )
        currentConversation = conversation
        tokenBindings[conversation.conversationToken] = conversationId
        createdConversationIds += conversationId
        return conversation
    }
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

private fun testToken(seed: Int): String = (seed % 16).toString(16).repeat(64)
