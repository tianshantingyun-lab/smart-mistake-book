package com.tingyun.smartmistakebook.core.database

data class TutorConversationRecord(
    val conversationId: String,
    val anchorKind: String,
    val anchorId: String?,
    val anchorRevisionId: String?,
    val status: String,
    val title: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastTurnOrdinal: Int,
    val studentDraft: String?,
)

data class TutorMessageRecord(
    val messageId: String,
    val conversationId: String,
    val ordinal: Int,
    val role: String,
    val bodyMarkdown: String,
    val status: String,
    val logicalOperationId: String?,
    val replyToMessageId: String?,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val errorCode: String?,
)

data class CreateTutorConversationDatabaseCommand(
    val conversationId: String,
    val anchorKind: String,
    val anchorId: String?,
    val anchorRevisionId: String?,
    val title: String?,
    val createdAtEpochMillis: Long,
)

data class AppendTutorStudentMessageDatabaseCommand(
    val conversationId: String,
    val messageId: String,
    val ordinal: Int,
    val bodyMarkdown: String,
    val logicalOperationId: String,
    val createdAtEpochMillis: Long,
    /** 本条消息附图的规范资产 id（按选择顺序）；空表示纯文字消息。 */
    val sourceImageAssetIds: List<String> = emptyList(),
)

/** 学生消息附图的引用行（消息删除时级联删除）。 */
data class TutorMessageSourceAssetRecord(
    val messageId: String,
    val sourceAssetId: String,
    val ordinal: Int,
)

data class AppendTutorAssistantMessageDatabaseCommand(
    val conversationId: String,
    val messageId: String,
    val ordinal: Int,
    val replyToMessageId: String?,
    val bodyMarkdown: String,
    val logicalOperationId: String?,
    val status: String,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val errorCode: String?,
)

data class UpdateTutorMessageStatusDatabaseCommand(
    val messageId: String,
    val expectedStatus: String,
    val nextStatus: String,
    val bodyMarkdown: String?,
    val completedAtEpochMillis: Long?,
    val errorCode: String?,
    val updatedAtEpochMillis: Long,
)

data class SaveTutorConversationDraftDatabaseCommand(
    val conversationId: String,
    val draft: String,
    val updatedAtEpochMillis: Long,
)

data class ClearTutorConversationDraftDatabaseCommand(
    val conversationId: String,
    val updatedAtEpochMillis: Long,
)
