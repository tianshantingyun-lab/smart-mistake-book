package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.TutorConversationRecord
import com.tingyun.smartmistakebook.core.database.TutorMessageRecord
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for tutor conversation operations.
 */
interface TutorReadPort {
    fun observeTutorTurnResponses(sessionId: String): Flow<List<TutorTurnResponseRecord>>
    fun observeRecentTutorConversations(limit: Int): Flow<List<TutorConversationRecord>>
    fun observeTutorMessages(conversationId: String): Flow<List<TutorMessageRecord>>
    fun observeTutorConversation(conversationId: String): Flow<TutorConversationRecord?>
}

/**
 * Write port for tutor conversation operations.
 */
interface TutorWritePort {
    suspend fun createTutorConversation(
        conversationId: String,
        anchorKind: String,
        anchorId: String?,
        anchorRevisionId: String?,
        title: String?,
        createdAtEpochMillis: Long,
    )

    suspend fun appendTutorStudentMessage(
        conversationId: String,
        messageId: String,
        ordinal: Int,
        bodyMarkdown: String,
        createdAtEpochMillis: Long,
    )

    suspend fun appendTutorAssistantMessage(
        conversationId: String,
        messageId: String,
        ordinal: Int,
        bodyMarkdown: String,
        logicalOperationId: String?,
        createdAtEpochMillis: Long,
    )

    suspend fun updateTutorMessageStatus(
        messageId: String,
        status: String,
        completedAtEpochMillis: Long?,
        errorCode: String?,
    )

    suspend fun pauseTutorConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    )

    suspend fun archiveTutorConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    )

    suspend fun deleteTutorConversation(conversationId: String)

    suspend fun saveTutorConversationDraft(
        conversationId: String,
        draft: String?,
        updatedAtEpochMillis: Long,
    )

    suspend fun clearTutorConversationDraft(
        conversationId: String,
        updatedAtEpochMillis: Long,
    )
}
