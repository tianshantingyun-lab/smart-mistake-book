package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.AppendTutorAssistantMessageDatabaseCommand
import com.tingyun.smartmistakebook.core.database.AppendTutorStudentMessageDatabaseCommand
import com.tingyun.smartmistakebook.core.database.CreateTutorConversationDatabaseCommand
import com.tingyun.smartmistakebook.core.database.TutorConversationRecord
import com.tingyun.smartmistakebook.core.database.TutorMessageRecord
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.database.UpdateTutorMessageStatusDatabaseCommand
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
        command: CreateTutorConversationDatabaseCommand,
    ): TutorConversationRecord

    suspend fun appendTutorStudentMessage(
        command: AppendTutorStudentMessageDatabaseCommand,
    ): TutorMessageRecord

    suspend fun appendTutorAssistantMessage(
        command: AppendTutorAssistantMessageDatabaseCommand,
    ): TutorMessageRecord

    suspend fun updateTutorMessageStatus(
        command: UpdateTutorMessageStatusDatabaseCommand,
    ): TutorMessageRecord

    suspend fun pauseTutorConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): TutorConversationRecord

    suspend fun archiveTutorConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): TutorConversationRecord

    suspend fun deleteTutorConversation(conversationId: String)

    suspend fun saveTutorConversationDraft(
        conversationId: String,
        draft: String,
        updatedAtEpochMillis: Long,
    )

    suspend fun clearTutorConversationDraft(
        conversationId: String,
        updatedAtEpochMillis: Long,
    )
}
