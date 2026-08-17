package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TutorConversationContractTest {
    @Test
    fun `text only conversation rejects anchors`() {
        assertThrows(IllegalArgumentException::class.java) {
            CreateTutorConversationCommand(
                conversationId = "conversation-1",
                anchorKind = TutorConversationAnchorKind.TEXT_ONLY,
                anchorId = "anchor-1",
                anchorRevisionId = "revision-1",
                title = null,
                createdAtEpochMillis = 1_000,
            )
        }
    }

    @Test
    fun `anchored conversation requires revision identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            CreateTutorConversationCommand(
                conversationId = "conversation-2",
                anchorKind = TutorConversationAnchorKind.PROBLEM_REVISION,
                anchorId = "problem-1",
                anchorRevisionId = null,
                title = null,
                createdAtEpochMillis = 1_000,
            )
        }
    }

    @Test
    fun `succeeded assistant message requires completion time`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppendTutorAssistantMessageCommand(
                conversationId = "conversation-3",
                messageId = "message-3",
                ordinal = 2,
                replyToMessageId = "message-2",
                bodyMarkdown = "好的",
                logicalOperationId = "operation-3",
                status = TutorMessageStatus.SUCCEEDED,
                createdAtEpochMillis = 2_000,
                completedAtEpochMillis = null,
            )
        }
    }

    @Test
    fun `status update enforces message id and non-negative time`() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateTutorMessageStatusCommand(
                messageId = "",
                expectedStatus = TutorMessageStatus.STREAMING,
                nextStatus = TutorMessageStatus.SUCCEEDED,
                bodyMarkdown = null,
                completedAtEpochMillis = 2_000,
                errorCode = null,
                updatedAtEpochMillis = 1_000,
            )
        }
    }

    @Test
    fun `student append uses stable identity and body`() {
        val command = AppendTutorStudentMessageCommand(
            conversationId = "conversation-4",
            messageId = "message-4",
            ordinal = 1,
            bodyMarkdown = "这道题为什么选 C？",
            logicalOperationId = "operation-4",
            createdAtEpochMillis = 1_000,
        )

        assertEquals("message-4", command.messageId)
        assertEquals("operation-4", command.logicalOperationId)
        assertEquals(1, command.ordinal)
    }

    @Test
    fun `delete command requires stable conversation identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeleteTutorConversationCommand(
                conversationId = "",
                occurredAtEpochMillis = 1_000,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeleteTutorConversationCommand(
                conversationId = "conversation-5",
                occurredAtEpochMillis = -1,
            )
        }
        val command = DeleteTutorConversationCommand(
            conversationId = "conversation-5",
            occurredAtEpochMillis = 2_000,
        )
        assertEquals("conversation-5", command.conversationId)
    }

    @Test
    fun `draft save requires non blank draft and stable conversation`() {
        assertThrows(IllegalArgumentException::class.java) {
            SaveTutorConversationDraftCommand(
                conversationId = "",
                draft = "草稿",
                occurredAtEpochMillis = 1_000,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SaveTutorConversationDraftCommand(
                conversationId = "conversation-6",
                draft = "   ",
                occurredAtEpochMillis = 1_000,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClearTutorConversationDraftCommand(
                conversationId = "",
                occurredAtEpochMillis = 1_000,
            )
        }
        val save = SaveTutorConversationDraftCommand(
            conversationId = "conversation-6",
            draft = "草稿",
            occurredAtEpochMillis = 2_000,
        )
        assertEquals("conversation-6", save.conversationId)
        assertEquals("草稿", save.draft)
    }
}
