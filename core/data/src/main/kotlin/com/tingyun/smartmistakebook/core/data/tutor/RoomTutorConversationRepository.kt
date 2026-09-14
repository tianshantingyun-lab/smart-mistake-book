package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.AppendTutorAssistantMessageDatabaseCommand
import com.tingyun.smartmistakebook.core.database.AppendTutorStudentMessageDatabaseCommand
import com.tingyun.smartmistakebook.core.database.CreateTutorConversationDatabaseCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorConversationRecord
import com.tingyun.smartmistakebook.core.database.TutorMessageRecord
import com.tingyun.smartmistakebook.core.database.UpdateTutorMessageStatusDatabaseCommand
import com.tingyun.smartmistakebook.core.domain.AppendTutorAssistantMessageCommand
import com.tingyun.smartmistakebook.core.domain.AppendTutorStudentMessageCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ClearTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.DeleteTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.PauseTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.TutorConversation
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorConversationStatus
import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.TutorMessageRole
import com.tingyun.smartmistakebook.core.domain.TutorMessageStatus
import com.tingyun.smartmistakebook.core.domain.UpdateTutorMessageStatusCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class RoomTutorConversationRepository(
    private val database: StudyDatabasePort,
) : TutorConversationRepository {
    override fun observeRecent(limit: Int): Flow<List<TutorConversation>> {
        require(limit > 0) { "Tutor conversation limit must be positive" }
        return database.observeRecentTutorConversations(limit)
            .map { records -> records.map(TutorConversationRecord::toDomain) }
    }

    override fun observeConversation(
        conversationId: String,
    ): Flow<TutorConversationSnapshot?> {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        return combine(
            database.observeTutorConversation(conversationId)
                .map { it?.toDomain() },
            database.observeTutorMessages(conversationId)
                .map { records ->
                    val assetIdsByMessage = if (records.isEmpty()) {
                        emptyMap()
                    } else {
                        database.readTutorMessageSourceAssets(records.map { it.messageId })
                            .groupBy(
                                keySelector = { link -> link.messageId },
                                valueTransform = { link -> link.sourceAssetId },
                            )
                    }
                    records.map { record ->
                        record.toDomain(assetIdsByMessage[record.messageId].orEmpty())
                    }
                },
        ) { conversation, messages ->
            conversation?.let { TutorConversationSnapshot(conversation = it, messages = messages) }
        }
    }

    override suspend fun createConversation(
        command: CreateTutorConversationCommand,
    ): TutorConversation = withContext(Dispatchers.IO) {
        database.createTutorConversation(command.toDatabase()).toDomain()
    }

    override suspend fun appendStudentMessage(
        command: AppendTutorStudentMessageCommand,
    ): TutorMessage = withContext(Dispatchers.IO) {
        database.appendTutorStudentMessage(command.toDatabase()).toDomain()
    }

    override suspend fun appendAssistantMessage(
        command: AppendTutorAssistantMessageCommand,
    ): TutorMessage = withContext(Dispatchers.IO) {
        database.appendTutorAssistantMessage(command.toDatabase()).toDomain()
    }

    override suspend fun updateMessageStatus(
        command: UpdateTutorMessageStatusCommand,
    ): TutorMessage = withContext(Dispatchers.IO) {
        database.updateTutorMessageStatus(command.toDatabase()).toDomain()
    }

    override suspend fun pauseConversation(
        command: PauseTutorConversationCommand,
    ): TutorConversation = withContext(Dispatchers.IO) {
        database.pauseTutorConversation(
            conversationId = command.conversationId,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        ).toDomain()
    }

    override suspend fun archiveConversation(
        command: ArchiveTutorConversationCommand,
    ): TutorConversation = withContext(Dispatchers.IO) {
        database.archiveTutorConversation(
            conversationId = command.conversationId,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        ).toDomain()
    }

    override suspend fun deleteConversation(
        command: DeleteTutorConversationCommand,
    ) {
        withContext(Dispatchers.IO) {
            database.deleteTutorConversation(command.conversationId)
        }
    }

    override suspend fun saveDraft(
        command: SaveTutorConversationDraftCommand,
    ) {
        withContext(Dispatchers.IO) {
            database.saveTutorConversationDraft(
                conversationId = command.conversationId,
                draft = command.draft,
                updatedAtEpochMillis = command.occurredAtEpochMillis,
            )
        }
    }

    override suspend fun clearDraft(
        command: ClearTutorConversationDraftCommand,
    ) {
        withContext(Dispatchers.IO) {
            database.clearTutorConversationDraft(
                conversationId = command.conversationId,
                updatedAtEpochMillis = command.occurredAtEpochMillis,
            )
        }
    }
}

object TutorConversationRepositoryFactory {
    fun create(database: StudyDatabasePort): TutorConversationRepository =
        RoomTutorConversationRepository(database)
}

private fun CreateTutorConversationCommand.toDatabase() = CreateTutorConversationDatabaseCommand(
    conversationId = conversationId,
    anchorKind = anchorKind.name,
    anchorId = anchorId,
    anchorRevisionId = anchorRevisionId,
    title = title,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun AppendTutorStudentMessageCommand.toDatabase() =
    AppendTutorStudentMessageDatabaseCommand(
        conversationId = conversationId,
        messageId = messageId,
        ordinal = ordinal,
        bodyMarkdown = bodyMarkdown,
        logicalOperationId = logicalOperationId,
        createdAtEpochMillis = createdAtEpochMillis,
        sourceImageAssetIds = sourceImageAssetIds,
    )

private fun AppendTutorAssistantMessageCommand.toDatabase() =
    AppendTutorAssistantMessageDatabaseCommand(
        conversationId = conversationId,
        messageId = messageId,
        ordinal = ordinal,
        replyToMessageId = replyToMessageId,
        bodyMarkdown = bodyMarkdown,
        logicalOperationId = logicalOperationId,
        status = status.name,
        createdAtEpochMillis = createdAtEpochMillis,
        completedAtEpochMillis = completedAtEpochMillis,
        errorCode = errorCode,
    )

private fun UpdateTutorMessageStatusCommand.toDatabase() =
    UpdateTutorMessageStatusDatabaseCommand(
        messageId = messageId,
        expectedStatus = expectedStatus.name,
        nextStatus = nextStatus.name,
        bodyMarkdown = bodyMarkdown,
        completedAtEpochMillis = completedAtEpochMillis,
        errorCode = errorCode,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

private fun TutorConversationRecord.toDomain() = TutorConversation(
    conversationId = conversationId,
    anchorKind = TutorConversationAnchorKind.valueOf(anchorKind),
    anchorId = anchorId,
    anchorRevisionId = anchorRevisionId,
    status = TutorConversationStatus.valueOf(status),
    title = title,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    lastTurnOrdinal = lastTurnOrdinal,
    studentDraft = studentDraft,
)

private fun TutorMessageRecord.toDomain(
    sourceImageAssetIds: List<String> = emptyList(),
) = TutorMessage(
    messageId = messageId,
    conversationId = conversationId,
    ordinal = ordinal,
    role = TutorMessageRole.valueOf(role),
    bodyMarkdown = bodyMarkdown,
    status = TutorMessageStatus.valueOf(status),
    logicalOperationId = logicalOperationId,
    replyToMessageId = replyToMessageId,
    createdAtEpochMillis = createdAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    errorCode = errorCode,
    sourceImageAssetIds = sourceImageAssetIds,
)
