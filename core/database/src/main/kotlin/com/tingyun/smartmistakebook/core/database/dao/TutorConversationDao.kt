package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.AppendTutorAssistantMessageDatabaseCommand
import com.tingyun.smartmistakebook.core.database.AppendTutorStudentMessageDatabaseCommand
import com.tingyun.smartmistakebook.core.database.ClearTutorConversationDraftDatabaseCommand
import com.tingyun.smartmistakebook.core.database.CreateTutorConversationDatabaseCommand
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.SaveTutorConversationDraftDatabaseCommand
import com.tingyun.smartmistakebook.core.database.TutorConversationRecord
import com.tingyun.smartmistakebook.core.database.TutorMessageRecord
import com.tingyun.smartmistakebook.core.database.UpdateTutorMessageStatusDatabaseCommand
import com.tingyun.smartmistakebook.core.database.entity.TutorConversationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorMessageEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorMessageSourceAssetEntity
import com.tingyun.smartmistakebook.core.database.TutorMessageSourceAssetRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 学生消息附图上限（与 Lobby 契约一致）。 */
internal const val MAX_STUDENT_MESSAGE_IMAGES = 9

@Dao
internal abstract class TutorConversationDao {
    @Query(
        "SELECT * FROM tutor_conversation ORDER BY updated_at_epoch_millis DESC, conversation_id DESC " +
            "LIMIT :limit",
    )
    protected abstract fun observeRecentEntities(limit: Int): Flow<List<TutorConversationEntity>>

    @Query(
        "SELECT * FROM tutor_message WHERE conversation_id = :conversationId " +
            "ORDER BY ordinal ASC, message_id ASC",
    )
    protected abstract fun observeMessageEntities(conversationId: String): Flow<List<TutorMessageEntity>>

    @Query(
        "SELECT * FROM tutor_conversation WHERE conversation_id = :conversationId LIMIT 1",
    )
    protected abstract fun observeConversationEntity(
        conversationId: String,
    ): Flow<TutorConversationEntity?>

    @Query("SELECT * FROM tutor_conversation WHERE conversation_id = :conversationId LIMIT 1")
    protected abstract suspend fun findConversation(
        conversationId: String,
    ): TutorConversationEntity?

    @Query("SELECT COUNT(*) FROM tutor_message WHERE conversation_id = :conversationId")
    protected abstract suspend fun countMessageRows(conversationId: String): Int

    @Query(
        "SELECT * FROM tutor_message WHERE message_id = :messageId LIMIT 1",
    )
    protected abstract suspend fun findMessage(messageId: String): TutorMessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertConversation(entity: TutorConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMessage(entity: TutorMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMessageSourceAssetRows(
        rows: List<TutorMessageSourceAssetEntity>,
    ): List<Long>

    @Query(
        """
        SELECT * FROM tutor_message_source_asset
        WHERE message_id IN (:messageIds)
        ORDER BY message_id ASC, ordinal ASC
        """,
    )
    protected abstract suspend fun findMessageSourceAssets(
        messageIds: List<String>,
    ): List<TutorMessageSourceAssetEntity>

    open suspend fun readMessageSourceAssets(
        messageIds: List<String>,
    ): List<TutorMessageSourceAssetRecord> =
        if (messageIds.isEmpty()) {
            emptyList()
        } else {
            findMessageSourceAssets(messageIds).map { row ->
                TutorMessageSourceAssetRecord(
                    messageId = row.messageId,
                    sourceAssetId = row.sourceAssetId,
                    ordinal = row.ordinal,
                )
            }
        }

    @Query(
        """
        UPDATE tutor_conversation
        SET updated_at_epoch_millis = MAX(updated_at_epoch_millis, :updatedAtEpochMillis),
            last_turn_ordinal = MAX(last_turn_ordinal, :ordinal)
        WHERE conversation_id = :conversationId
        """,
    )
    protected abstract suspend fun touchConversation(
        conversationId: String,
        ordinal: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_conversation
        SET status = :status,
            updated_at_epoch_millis = MAX(updated_at_epoch_millis, :updatedAtEpochMillis)
        WHERE conversation_id = :conversationId
        """,
    )
    protected abstract suspend fun updateConversationStatus(
        conversationId: String,
        status: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_conversation
        SET student_draft = :draft,
            updated_at_epoch_millis = MAX(updated_at_epoch_millis, :updatedAtEpochMillis)
        WHERE conversation_id = :conversationId
        """,
    )
    protected abstract suspend fun updateStudentDraft(
        conversationId: String,
        draft: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_conversation
        SET student_draft = NULL,
            updated_at_epoch_millis = MAX(updated_at_epoch_millis, :updatedAtEpochMillis)
        WHERE conversation_id = :conversationId
        """,
    )
    protected abstract suspend fun clearStudentDraft(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_message
        SET status = :nextStatus,
            body_markdown = COALESCE(:bodyMarkdown, body_markdown),
            completed_at_epoch_millis = COALESCE(:completedAtEpochMillis, completed_at_epoch_millis),
            error_code = :errorCode
        WHERE message_id = :messageId
          AND status = :expectedStatus
        """,
    )
    protected abstract suspend fun updateMessage(
        messageId: String,
        expectedStatus: String,
        nextStatus: String,
        bodyMarkdown: String?,
        completedAtEpochMillis: Long?,
        errorCode: String?,
    ): Int

    fun observeRecent(limit: Int): Flow<List<TutorConversationRecord>> {
        require(limit > 0) { "Tutor conversation limit must be positive" }
        return observeRecentEntities(limit).map { rows ->
            rows.map { entity ->
                entity.toRecord(messageCount = countMessages(entity.conversationId))
            }
        }
    }

    /** 会话的真实消息行数：last_turn_ordinal 是序号语义（讲题会话有空洞），不能当条数用。 */
    open suspend fun countMessages(conversationId: String): Int {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        return countMessageRows(conversationId)
    }

    fun observeMessages(conversationId: String): Flow<List<TutorMessageRecord>> {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        return observeMessageEntities(conversationId).map { rows -> rows.map(TutorMessageEntity::toRecord) }
    }

    fun observeConversation(conversationId: String): Flow<TutorConversationRecord?> {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        return observeConversationEntity(conversationId).map { it?.toRecord() }
    }

    @Transaction
    open suspend fun saveStudentDraft(
        command: SaveTutorConversationDraftDatabaseCommand,
    ) {
        require(command.conversationId.isNotBlank())
        require(command.draft.isNotBlank())
        require(command.updatedAtEpochMillis >= 0L)
        val updated = updateStudentDraft(
            conversationId = command.conversationId,
            draft = command.draft,
            updatedAtEpochMillis = command.updatedAtEpochMillis,
        )
        if (updated != 1) {
            throw ImmutablePayloadConflictException("tutor_conversation_draft", command.conversationId)
        }
    }

    @Transaction
    open suspend fun clearStudentDraft(
        command: ClearTutorConversationDraftDatabaseCommand,
    ) {
        require(command.conversationId.isNotBlank())
        require(command.updatedAtEpochMillis >= 0L)
        val updated = clearStudentDraft(
            conversationId = command.conversationId,
            updatedAtEpochMillis = command.updatedAtEpochMillis,
        )
        if (updated != 1) {
            throw ImmutablePayloadConflictException("tutor_conversation_draft", command.conversationId)
        }
    }

    @Transaction
    open suspend fun createConversation(
        command: CreateTutorConversationDatabaseCommand,
    ): TutorConversationRecord {
        require(command.conversationId.isNotBlank())
        require(command.createdAtEpochMillis >= 0L)
        val entity = command.toEntity()
        if (insertConversation(entity) != -1L) return entity.toRecord()
        val existing = checkNotNull(findConversation(command.conversationId)) {
            "Tutor conversation insert was not readable"
        }
        if (
            existing.anchorKind != entity.anchorKind ||
            existing.anchorId != entity.anchorId ||
            existing.anchorRevisionId != entity.anchorRevisionId
        ) {
            throw ImmutablePayloadConflictException("tutor_conversation", command.conversationId)
        }
        return existing.toRecord()
    }

    @Transaction
    open suspend fun appendStudentMessage(
        command: AppendTutorStudentMessageDatabaseCommand,
    ): TutorMessageRecord {
        require(command.conversationId.isNotBlank())
        require(command.messageId.isNotBlank())
        require(command.ordinal > 0)
        require(command.bodyMarkdown.isNotBlank())
        require(command.logicalOperationId.isNotBlank())
        require(command.createdAtEpochMillis >= 0L)
        require(command.sourceImageAssetIds.size <= MAX_STUDENT_MESSAGE_IMAGES) {
            "Tutor student message carries too many images"
        }
        val entity = command.toEntity()
        if (insertMessage(entity) != -1L) {
            if (command.sourceImageAssetIds.isNotEmpty()) {
                insertMessageSourceAssetRows(
                    command.sourceImageAssetIds.mapIndexed { index, assetId ->
                        TutorMessageSourceAssetEntity(
                            messageId = command.messageId,
                            sourceAssetId = assetId,
                            ordinal = index,
                        )
                    },
                )
            }
            touchConversation(
                conversationId = command.conversationId,
                ordinal = command.ordinal,
                updatedAtEpochMillis = command.createdAtEpochMillis,
            )
            return entity.toRecord()
        }
        val existing = checkNotNull(findMessage(command.messageId)) {
            "Tutor message insert was not readable"
        }
        if (
            existing.conversationId != entity.conversationId ||
            existing.ordinal != entity.ordinal ||
            existing.bodyMarkdown != entity.bodyMarkdown ||
            existing.logicalOperationId != entity.logicalOperationId
        ) {
            throw ImmutablePayloadConflictException("tutor_message", command.messageId)
        }
        return existing.toRecord()
    }

    @Transaction
    open suspend fun appendAssistantMessage(
        command: AppendTutorAssistantMessageDatabaseCommand,
    ): TutorMessageRecord {
        require(command.conversationId.isNotBlank())
        require(command.messageId.isNotBlank())
        require(command.ordinal > 0)
        require(command.bodyMarkdown.isNotBlank())
        require(command.createdAtEpochMillis >= 0L)
        val entity = command.toEntity()
        if (insertMessage(entity) != -1L) {
            touchConversation(
                conversationId = command.conversationId,
                ordinal = command.ordinal,
                updatedAtEpochMillis = command.createdAtEpochMillis,
            )
            return entity.toRecord()
        }
        val existing = checkNotNull(findMessage(command.messageId)) {
            "Tutor message insert was not readable"
        }
        if (
            existing.conversationId != entity.conversationId ||
            existing.ordinal != entity.ordinal ||
            existing.bodyMarkdown != entity.bodyMarkdown ||
            existing.logicalOperationId != entity.logicalOperationId
        ) {
            throw ImmutablePayloadConflictException("tutor_message", command.messageId)
        }
        return existing.toRecord()
    }

    @Transaction
    open suspend fun updateMessageStatus(
        command: UpdateTutorMessageStatusDatabaseCommand,
    ): TutorMessageRecord {
        require(command.messageId.isNotBlank())
        require(command.updatedAtEpochMillis >= 0L)
        val updated = updateMessage(
            messageId = command.messageId,
            expectedStatus = command.expectedStatus,
            nextStatus = command.nextStatus,
            bodyMarkdown = command.bodyMarkdown,
            completedAtEpochMillis = command.completedAtEpochMillis,
            errorCode = command.errorCode,
        )
        if (updated != 1) {
            val raced = checkNotNull(findMessage(command.messageId)) {
                "Tutor message status update target is missing"
            }
            if (
                raced.status == command.nextStatus &&
                (command.bodyMarkdown == null || raced.bodyMarkdown == command.bodyMarkdown)
            ) {
                return raced.toRecord()
            }
            throw ImmutablePayloadConflictException("tutor_message_status", command.messageId)
        }
        val persisted = checkNotNull(findMessage(command.messageId))
        if (persisted.status != command.nextStatus) {
            throw ImmutablePayloadConflictException("tutor_message_status", command.messageId)
        }
        return persisted.toRecord()
    }

    @Transaction
    open suspend fun pauseConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): TutorConversationRecord {
        require(conversationId.isNotBlank())
        require(updatedAtEpochMillis >= 0L)
        val updated = updateConversationStatus(
            conversationId = conversationId,
            status = "PAUSED",
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
        if (updated != 1) {
            val existing = checkNotNull(findConversation(conversationId)) {
                "Tutor conversation pause target is missing"
            }
            if (existing.status == "PAUSED") return existing.toRecord()
            throw ImmutablePayloadConflictException("tutor_conversation_status", conversationId)
        }
        return checkNotNull(findConversation(conversationId)).toRecord()
    }

    @Transaction
    open suspend fun archiveConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): TutorConversationRecord {
        require(conversationId.isNotBlank())
        require(updatedAtEpochMillis >= 0L)
        val updated = updateConversationStatus(
            conversationId = conversationId,
            status = "ARCHIVED",
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
        if (updated != 1) {
            val existing = checkNotNull(findConversation(conversationId)) {
                "Tutor conversation archive target is missing"
            }
            if (existing.status == "ARCHIVED") return existing.toRecord()
            throw ImmutablePayloadConflictException("tutor_conversation_status", conversationId)
        }
        return checkNotNull(findConversation(conversationId)).toRecord()
    }

    @Transaction
    open suspend fun deleteConversation(conversationId: String) {
        require(conversationId.isNotBlank())
        deleteMessages(conversationId)
        deleteConversationRow(conversationId)
    }

    @Query("DELETE FROM tutor_message WHERE conversation_id = :conversationId")
    protected abstract suspend fun deleteMessages(conversationId: String): Int

    @Query("DELETE FROM tutor_conversation WHERE conversation_id = :conversationId")
    protected abstract suspend fun deleteConversationRow(conversationId: String): Int
}

private fun CreateTutorConversationDatabaseCommand.toEntity() = TutorConversationEntity(
    conversationId = conversationId,
    anchorKind = anchorKind,
    anchorId = anchorId,
    anchorRevisionId = anchorRevisionId,
    status = "ACTIVE",
    title = title,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = createdAtEpochMillis,
    lastTurnOrdinal = 0,
    studentDraft = null,
)

private fun AppendTutorStudentMessageDatabaseCommand.toEntity() = TutorMessageEntity(
    messageId = messageId,
    conversationId = conversationId,
    ordinal = ordinal,
    role = "STUDENT",
    bodyMarkdown = bodyMarkdown,
    status = "PERSISTED",
    logicalOperationId = logicalOperationId,
    replyToMessageId = null,
    createdAtEpochMillis = createdAtEpochMillis,
    completedAtEpochMillis = createdAtEpochMillis,
    errorCode = null,
)

private fun AppendTutorAssistantMessageDatabaseCommand.toEntity() = TutorMessageEntity(
    messageId = messageId,
    conversationId = conversationId,
    ordinal = ordinal,
    role = "ASSISTANT",
    bodyMarkdown = bodyMarkdown,
    status = status,
    logicalOperationId = logicalOperationId,
    replyToMessageId = replyToMessageId,
    createdAtEpochMillis = createdAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    errorCode = errorCode,
)

internal fun TutorConversationEntity.toRecord(
    messageCount: Int = 0,
) = TutorConversationRecord(
    conversationId = conversationId,
    anchorKind = anchorKind,
    anchorId = anchorId,
    anchorRevisionId = anchorRevisionId,
    status = status,
    title = title,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    lastTurnOrdinal = lastTurnOrdinal,
    studentDraft = studentDraft,
    messageCount = messageCount,
)

internal fun TutorMessageEntity.toRecord() = TutorMessageRecord(
    messageId = messageId,
    conversationId = conversationId,
    ordinal = ordinal,
    role = role,
    bodyMarkdown = bodyMarkdown,
    status = status,
    logicalOperationId = logicalOperationId,
    replyToMessageId = replyToMessageId,
    createdAtEpochMillis = createdAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    errorCode = errorCode,
)
