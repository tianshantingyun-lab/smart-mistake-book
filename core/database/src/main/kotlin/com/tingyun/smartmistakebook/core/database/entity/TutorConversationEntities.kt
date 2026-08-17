package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "tutor_conversation",
    indices = [
        Index(value = ["updated_at_epoch_millis"]),
        Index(value = ["status"]),
        Index(
            value = [
                "anchor_kind",
                "anchor_id",
                "anchor_revision_id",
            ],
        ),
    ],
)
internal data class TutorConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "anchor_kind")
    val anchorKind: String,
    @ColumnInfo(name = "anchor_id")
    val anchorId: String?,
    @ColumnInfo(name = "anchor_revision_id")
    val anchorRevisionId: String?,
    val status: String,
    val title: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "last_turn_ordinal")
    val lastTurnOrdinal: Int,
    @ColumnInfo(name = "student_draft")
    val studentDraft: String?,
)

@Entity(
    tableName = "tutor_message",
    foreignKeys = [
        ForeignKey(
            entity = TutorConversationEntity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "ordinal"], unique = true),
        Index(value = ["logical_operation_id"]),
        Index(value = ["status"]),
    ],
)
internal data class TutorMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    val ordinal: Int,
    val role: String,
    @ColumnInfo(name = "body_markdown")
    val bodyMarkdown: String,
    val status: String,
    @ColumnInfo(name = "logical_operation_id")
    val logicalOperationId: String?,
    @ColumnInfo(name = "reply_to_message_id")
    val replyToMessageId: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "error_code")
    val errorCode: String?,
)
