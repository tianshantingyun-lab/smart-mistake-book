package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.AppendTutorAssistantMessageCommand
import com.tingyun.smartmistakebook.core.domain.AppendTutorStudentMessageCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ClearTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.DeleteTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.PauseTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorConversation
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorConversationStatus
import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.TutorMessageRole
import com.tingyun.smartmistakebook.core.domain.TutorMessageStatus
import com.tingyun.smartmistakebook.core.domain.UpdateTutorMessageStatusCommand
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D6 回归：大厅里的回答到达后，画面要停在最新一条上。
 *
 * 缺陷现场（真机截图确认）：大厅此前用 `RootPageLazyColumn` 自己拼列表，没有任何贴尾逻辑——
 * 学生发完消息、模型回答落地，列表仍停在旧位置，新回复在屏幕外。P1-c 把大厅换成共用屏幕组件
 * `TutorConversationFrame`，贴尾逻辑随之落地（`autoScrollVersion` 覆盖消息条数 / 最后一条的
 * 状态 / 在途实时流）；本轮再修掉它的落点偏差（估算高度，见 `followTutorTail`）。
 * 这两条用例钉住：新回答必须进视野；学生自己滚上去看旧内容时，新回答不许把他拽回底部
 * （出口是「回到最新」按钮，且它真的到得了底）。
 *
 * **用 v1 规则（`createComposeRule`，UnconfinedTestDispatcher）而不是 v2**：v2 用
 * StandardTestDispatcher，组合里排队的贴尾效果会晚于测试的手势/内容变更执行，
 * 于是"学生滚上去"这一步会被抢回底部 —— 那是测试调度器的时序，不是产品行为；
 * 本模块里依赖滚动的既有用例（`CapturedTutorSessionTestBase`）同样是 v1。
 */
@RunWith(AndroidJUnit4::class)
class TutorLobbyScrollInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aReplyArrivingInTheLobbyBringsTheNewestMessageIntoView() {
        val conversations = LobbyConversationRows().apply { publishConversation(existingMessages()) }
        setLobby(conversations)

        composeRule.onNodeWithText(LAST_EXISTING_MARKER, substring = true).assertIsDisplayed()

        composeRule.runOnIdle { conversations.publishAssistantReply(ANSWER_MARKER) }

        composeRule.onNodeWithText(ANSWER_MARKER, substring = true).assertIsDisplayed()
    }

    @Test
    fun aStudentWhoScrolledUpIsNotYankedBackByANewReply() {
        val conversations = LobbyConversationRows().apply { publishConversation(existingMessages()) }
        setLobby(conversations)

        // 学生用手势往回滚：手势会被 `isScrollInProgress` 看见，而"学生自己在看旧内容"这条
        // 判定正是产品据它做的（程序化滚动不一定留下这个信号，所以这里用手势）。
        repeat(2) {
            composeRule.onNodeWithTag("tutor_conversation_list")
                .performTouchInput { swipeDown() }
        }
        composeRule.onNodeWithTag("tutor_scroll_to_bottom").assertExists()

        composeRule.runOnIdle { conversations.publishAssistantReply(ANSWER_MARKER) }

        // 位置不被抢走：仍然不在最新处，出口是「回到最新」按钮而不是自动跳转。
        composeRule.onNodeWithTag("tutor_scroll_to_bottom").assertExists()
        composeRule.onNodeWithTag("tutor_scroll_to_bottom").performClick()
        composeRule.onNodeWithText(ANSWER_MARKER, substring = true).assertIsDisplayed()
    }

    private fun setLobby(conversations: TutorConversationRepository) {
        composeRule.setContent {
            SmartMistakeBookTheme {
                TutorLobbyRoute(
                    onCapture = {},
                    onOpenCapabilitySettings = {},
                    onOpenMistakeNotebook = {},
                    onOpenProfile = {},
                    onOpenHistory = {},
                    conversations = conversations,
                    modelTasks = inertModelTasks(),
                    catalogEntries = emptyList(),
                    profile = StudyProfileOverview(),
                    initialConversationId = LobbyConversationRows.CONVERSATION_ID,
                )
            }
        }
    }

    private fun inertModelTasks() = object : ModelTaskRepository {
        override suspend fun capabilities(): ProviderCapabilitySnapshot =
            ProviderCapabilitySnapshot(
                providerId = "unconfigured",
                providerDisplayName = "尚未配置模型",
                modelId = "unconfigured",
                supportedTasks = emptySet(),
                supportsImageInput = false,
                supportsStructuredOutput = false,
                supportsStreaming = false,
                executionLocation = ModelExecutionLocation.UNAVAILABLE,
            )

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = flowOf(emptyList())

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
            error("An unconfigured lobby must not dispatch")
    }

    /** 12 条长消息：列表一定超出一屏，最后一条之外还有空间放新回答。 */
    private fun existingMessages(): List<TutorMessage> = (1..12).map { index ->
        val body = buildString {
            append(if (index % 2 == 0) "老师：第 $index 步这样看。" else "学生：第 $index 步我不太懂。")
            repeat(3) { append("\n这一行用来把消息撑长，让列表真的能滚起来。") }
        }
        TutorMessage(
            messageId = "lobby-message-$index",
            conversationId = LobbyConversationRows.CONVERSATION_ID,
            ordinal = index,
            role = if (index % 2 == 0) TutorMessageRole.ASSISTANT else TutorMessageRole.STUDENT,
            bodyMarkdown = body,
            status = TutorMessageStatus.SUCCEEDED,
            logicalOperationId = "lobby-op-$index",
            replyToMessageId = null,
            createdAtEpochMillis = index.toLong(),
            completedAtEpochMillis = index.toLong(),
            errorCode = null,
        )
    }

    private class LobbyConversationRows : TutorConversationRepository {
        private val snapshot = MutableStateFlow<TutorConversationSnapshot?>(null)

        fun publishConversation(messages: List<TutorMessage>) {
            snapshot.value = TutorConversationSnapshot(
                conversation = conversation(lastTurnOrdinal = messages.size),
                messages = messages,
            )
        }

        fun publishAssistantReply(markdown: String) {
            val current = checkNotNull(snapshot.value) { "The lobby conversation must exist first" }
            val ordinal = current.messages.size + 1
            snapshot.value = current.copy(
                conversation = conversation(lastTurnOrdinal = ordinal),
                messages = current.messages + TutorMessage(
                    messageId = "lobby-message-$ordinal",
                    conversationId = CONVERSATION_ID,
                    ordinal = ordinal,
                    role = TutorMessageRole.ASSISTANT,
                    bodyMarkdown = markdown,
                    status = TutorMessageStatus.SUCCEEDED,
                    logicalOperationId = "lobby-op-$ordinal",
                    replyToMessageId = null,
                    createdAtEpochMillis = 100L + ordinal,
                    completedAtEpochMillis = 100L + ordinal,
                    errorCode = null,
                ),
            )
        }

        private fun conversation(lastTurnOrdinal: Int) = TutorConversation(
            conversationId = CONVERSATION_ID,
            anchorKind = TutorConversationAnchorKind.TEXT_ONLY,
            anchorId = null,
            anchorRevisionId = null,
            status = TutorConversationStatus.ACTIVE,
            title = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 100,
            lastTurnOrdinal = lastTurnOrdinal,
        )

        override fun observeRecent(limit: Int): Flow<List<TutorConversation>> =
            snapshot.map { current -> current?.conversation?.let(::listOf).orEmpty() }

        override fun observeConversation(
            conversationId: String,
        ): Flow<TutorConversationSnapshot?> = snapshot

        override suspend fun createConversation(
            command: CreateTutorConversationCommand,
        ): TutorConversation = error("No conversation create expected")

        override suspend fun appendStudentMessage(
            command: AppendTutorStudentMessageCommand,
        ): TutorMessage = error("No student write expected")

        override suspend fun appendAssistantMessage(
            command: AppendTutorAssistantMessageCommand,
        ): TutorMessage = error("No assistant write expected")

        override suspend fun updateMessageStatus(
            command: UpdateTutorMessageStatusCommand,
        ): TutorMessage = error("No status write expected")

        override suspend fun pauseConversation(
            command: PauseTutorConversationCommand,
        ): TutorConversation = error("No pause expected")

        override suspend fun archiveConversation(
            command: ArchiveTutorConversationCommand,
        ): TutorConversation = error("No archive expected")

        override suspend fun deleteConversation(command: DeleteTutorConversationCommand) =
            error("No delete expected")

        override suspend fun saveDraft(command: SaveTutorConversationDraftCommand) =
            error("No draft write expected")

        override suspend fun clearDraft(command: ClearTutorConversationDraftCommand) =
            error("No draft clear expected")

        companion object {
            const val CONVERSATION_ID = "tutor-conv:lobby-scroll"
        }
    }

    private companion object {
        const val LAST_EXISTING_MARKER = "老师：第 12 步这样看。"
        const val ANSWER_MARKER = "先看临界点两侧的符号。"
    }
}
