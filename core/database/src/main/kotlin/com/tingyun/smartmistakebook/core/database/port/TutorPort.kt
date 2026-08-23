package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.CommitProblemDraftResult
import com.tingyun.smartmistakebook.core.database.CommitTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.ConfirmTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.ConfirmTutorSessionFromWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.EndTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.EndTutorSessionResult
import com.tingyun.smartmistakebook.core.database.PersistTutorAnswerExposureCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorChoiceCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorMoveCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorRevealCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorSessionAnchorCommand
import com.tingyun.smartmistakebook.core.database.TutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.database.TutorSessionProblemAnchorRecord
import com.tingyun.smartmistakebook.core.database.TutorSessionRecord
import com.tingyun.smartmistakebook.core.database.TutorSessionWriteResult
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

/**
 * Port for tutor session lifecycle and turn writes.
 */
interface TutorSessionPort {
    suspend fun confirmTutorSession(
        command: ConfirmTutorSessionCommand,
    ): TutorSessionWriteResult

    suspend fun confirmTutorSessionFromWorkspace(
        command: ConfirmTutorSessionFromWorkspaceCommand,
    ): TutorSessionWriteResult

    suspend fun readTutorSession(sessionId: String): TutorSessionRecord?

    suspend fun commitTutorSession(
        command: CommitTutorSessionCommand,
    ): CommitProblemDraftResult

    suspend fun endTutorSession(
        command: EndTutorSessionCommand,
    ): EndTutorSessionResult

    suspend fun recordTutorChoice(command: PersistTutorChoiceCommand): TutorTurnResponseRecord

    suspend fun recordTutorMove(command: PersistTutorMoveCommand): TutorTurnResponseRecord

    suspend fun revealTutorSolution(command: PersistTutorRevealCommand): TutorTurnResponseRecord

    suspend fun recordTutorSolutionExposure(
        command: PersistTutorAnswerExposureCommand,
    ): TutorAnswerExposureRecord

    suspend fun bindTutorSessionProblemAnchor(
        command: PersistTutorSessionAnchorCommand,
    ): TutorSessionProblemAnchorRecord
}

/**
 * Port for tutor answer exposure reconciliation and reads.
 */
interface TutorAnswerExposurePort {
    suspend fun reconcileTutorAnswerExposures(learnerId: String, limit: Int = 100): Int = 0

    suspend fun readTutorAnswerExposure(
        modelTaskRequestId: String,
    ): TutorAnswerExposureRecord? = null

    suspend fun readTutorAnswerExposures(
        modelTaskRequestIds: Set<String>,
    ): List<TutorAnswerExposureRecord> = modelTaskRequestIds.mapNotNull { requestId ->
        readTutorAnswerExposure(requestId)
    }
}