package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.AppendTutorAssistantMessageCommand
import com.tingyun.smartmistakebook.core.domain.AppendTutorStudentMessageCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.DeleteTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.PauseTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.TutorConversation
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorConversationStatus
import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.UpdateTutorMessageStatusCommand
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorHistoryInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deleteAsksForConfirmationAndOnlyRemovesTheSelectedConversation() {
        val repository = FakeHistoryRepository(
            listOf(
                conversation("conversation-delete-1", "拍题讲题一", 1),
                conversation("conversation-delete-2", "错题讲题二", 3),
            ),
        )
        val deleted = mutableListOf<String>()
        composeRule.setContent {
            SmartMistakeBookTheme {
                TutorHistoryRoute(
                    conversations = repository,
                    onOpenTextConversation = {},
                    onOpenCapturedSession = {},
                    onArchive = {},
                    onDelete = { conversationId ->
                        deleted += conversationId
                        runBlocking {
                            repository.deleteConversation(
                                DeleteTutorConversationCommand(
                                    conversationId = conversationId,
                                    occurredAtEpochMillis = 2_000,
                                ),
                            )
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("tutor_history_delete_conversation-delete-1").performClick()
        composeRule.onNodeWithText("删除这条讲题记录？").assertExists()
        composeRule.onNodeWithText("删除").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("conversation-delete-1"), deleted)
            assertEquals(
                listOf("conversation-delete-2"),
                repository.recent().map { it.conversationId },
            )
        }
        composeRule.onNodeWithTag("tutor_history_row_conversation-delete-1")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("tutor_history_row_conversation-delete-2").assertExists()
    }

    @Test
    fun cancelKeepsTheConversationInHistory() {
        val repository = FakeHistoryRepository(
            listOf(conversation("conversation-cancel", "文字讲题", 2)),
        )
        val deleted = mutableListOf<String>()
        composeRule.setContent {
            SmartMistakeBookTheme {
                TutorHistoryRoute(
                    conversations = repository,
                    onOpenTextConversation = {},
                    onOpenCapturedSession = {},
                    onArchive = {},
                    onDelete = { conversationId -> deleted += conversationId },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("tutor_history_delete_conversation-cancel").performClick()
        composeRule.onNodeWithText("取消").performClick()

        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), deleted)
            assertEquals(listOf("conversation-cancel"), repository.recent().map { it.conversationId })
        }
        composeRule.onNodeWithTag("tutor_history_row_conversation-cancel").assertExists()
    }

    private fun conversation(
        conversationId: String,
        title: String,
        lastTurnOrdinal: Int,
    ) = TutorConversation(
        conversationId = conversationId,
        anchorKind = TutorConversationAnchorKind.TEXT_ONLY,
        anchorId = null,
        anchorRevisionId = null,
        status = TutorConversationStatus.ACTIVE,
        title = title,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 2_000,
        lastTurnOrdinal = lastTurnOrdinal,
    )

    private class FakeHistoryRepository(
        initial: List<TutorConversation>,
    ) : TutorConversationRepository {
        private val state = MutableStateFlow(initial)

        fun recent(): List<TutorConversation> = state.value

        override fun observeRecent(limit: Int): Flow<List<TutorConversation>> = state

        override fun observeConversation(
            conversationId: String,
        ): Flow<TutorConversationSnapshot?> = state.map { list ->
            list.firstOrNull { it.conversationId == conversationId }
                ?.let { TutorConversationSnapshot(it, emptyList()) }
        }

        override suspend fun createConversation(
            command: CreateTutorConversationCommand,
        ): TutorConversation = error("not used")

        override suspend fun appendStudentMessage(
            command: AppendTutorStudentMessageCommand,
        ): TutorMessage = error("not used")

        override suspend fun appendAssistantMessage(
            command: AppendTutorAssistantMessageCommand,
        ): TutorMessage = error("not used")

        override suspend fun updateMessageStatus(
            command: UpdateTutorMessageStatusCommand,
        ): TutorMessage = error("not used")

        override suspend fun pauseConversation(
            command: PauseTutorConversationCommand,
        ): TutorConversation = error("not used")

        override suspend fun archiveConversation(
            command: ArchiveTutorConversationCommand,
        ): TutorConversation = error("not used")

        override suspend fun deleteConversation(
            command: DeleteTutorConversationCommand,
        ) {
            state.value = state.value.filterNot { it.conversationId == command.conversationId }
        }

        override suspend fun saveDraft(
            command: com.tingyun.smartmistakebook.core.domain.SaveTutorConversationDraftCommand,
        ) {
            state.value = state.value.map { conversation ->
                if (conversation.conversationId == command.conversationId) {
                    conversation.copy(studentDraft = command.draft)
                } else {
                    conversation
                }
            }
        }

        override suspend fun clearDraft(
            command: com.tingyun.smartmistakebook.core.domain.ClearTutorConversationDraftCommand,
        ) {
            state.value = state.value.map { conversation ->
                if (conversation.conversationId == command.conversationId) {
                    conversation.copy(studentDraft = null)
                } else {
                    conversation
                }
            }
        }
    }
}
