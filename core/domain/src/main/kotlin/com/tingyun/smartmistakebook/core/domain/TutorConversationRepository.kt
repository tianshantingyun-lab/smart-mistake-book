package com.tingyun.smartmistakebook.core.domain

import kotlinx.coroutines.flow.Flow

enum class TutorConversationAnchorKind {
    TEXT_ONLY,
    EPHEMERAL_DRAFT,
    PROBLEM_REVISION,
}

enum class TutorConversationStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    ARCHIVED,
}

enum class TutorMessageRole {
    STUDENT,
    ASSISTANT,
    LOCAL_EVENT,
}

enum class TutorMessageStatus {
    PERSISTED,
    WAITING,
    STREAMING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class TutorConversation(
    val conversationId: String,
    val anchorKind: TutorConversationAnchorKind,
    val anchorId: String?,
    val anchorRevisionId: String?,
    val status: TutorConversationStatus,
    val title: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastTurnOrdinal: Int,
    val studentDraft: String? = null,
) {
    init {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        require(createdAtEpochMillis >= 0L) { "Tutor conversation creation time must not be negative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Tutor conversation update time cannot precede creation"
        }
        require(lastTurnOrdinal >= 0) { "Tutor conversation turn ordinal must not be negative" }
        when (anchorKind) {
            TutorConversationAnchorKind.TEXT_ONLY -> {
                require(anchorId == null && anchorRevisionId == null) {
                    "A text-only tutor conversation cannot carry an anchor"
                }
            }
            TutorConversationAnchorKind.EPHEMERAL_DRAFT,
            TutorConversationAnchorKind.PROBLEM_REVISION,
            -> {
                require(anchorId != null && anchorId.isNotBlank()) {
                    "Anchored tutor conversations require an anchor id"
                }
                require(anchorRevisionId != null && anchorRevisionId.isNotBlank()) {
                    "Anchored tutor conversations require an anchor revision id"
                }
            }
        }
    }
}

data class TutorMessage(
    val messageId: String,
    val conversationId: String,
    val ordinal: Int,
    val role: TutorMessageRole,
    val bodyMarkdown: String,
    val status: TutorMessageStatus,
    val logicalOperationId: String?,
    val replyToMessageId: String?,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val errorCode: String?,
) {
    init {
        require(messageId.isNotBlank()) { "Tutor message id must not be blank" }
        require(conversationId.isNotBlank()) { "Tutor message conversation id must not be blank" }
        require(ordinal > 0) { "Tutor message ordinal must be positive" }
        require(bodyMarkdown.isNotBlank()) { "Tutor message body must not be blank" }
        require(createdAtEpochMillis >= 0L) { "Tutor message creation time must not be negative" }
        require(completedAtEpochMillis == null || completedAtEpochMillis >= createdAtEpochMillis) {
            "Tutor message completion time cannot precede creation"
        }
        require(
            status != TutorMessageStatus.SUCCEEDED ||
                role != TutorMessageRole.STUDENT ||
                completedAtEpochMillis != null,
        ) { "A completed student message requires a completion time" }
        require(
            status == TutorMessageStatus.PERSISTED ||
                status == TutorMessageStatus.WAITING ||
                status == TutorMessageStatus.STREAMING ||
                logicalOperationId != null,
        ) { "Only in-flight assistant messages may omit a logical operation id" }
    }
}

data class TutorConversationSnapshot(
    val conversation: TutorConversation,
    val messages: List<TutorMessage>,
)

data class CreateTutorConversationCommand(
    val conversationId: String,
    val anchorKind: TutorConversationAnchorKind,
    val anchorId: String?,
    val anchorRevisionId: String?,
    val title: String?,
    val createdAtEpochMillis: Long,
) {
    init {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        require(createdAtEpochMillis >= 0L) { "Tutor conversation creation time must not be negative" }
        require(title == null || title.isNotBlank()) {
            "Tutor conversation title must be null or non-blank"
        }
        when (anchorKind) {
            TutorConversationAnchorKind.TEXT_ONLY -> {
                require(anchorId == null && anchorRevisionId == null) {
                    "A text-only tutor conversation cannot carry an anchor"
                }
            }
            TutorConversationAnchorKind.EPHEMERAL_DRAFT,
            TutorConversationAnchorKind.PROBLEM_REVISION,
            -> {
                require(anchorId != null && anchorId.isNotBlank()) {
                    "Anchored tutor conversations require an anchor id"
                }
                require(anchorRevisionId != null && anchorRevisionId.isNotBlank()) {
                    "Anchored tutor conversations require an anchor revision id"
                }
            }
        }
    }
}

data class AppendTutorStudentMessageCommand(
    val conversationId: String,
    val messageId: String,
    val ordinal: Int,
    val bodyMarkdown: String,
    val logicalOperationId: String,
    val createdAtEpochMillis: Long,
) {
    init {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        require(messageId.isNotBlank()) { "Tutor message id must not be blank" }
        require(ordinal > 0) { "Tutor message ordinal must be positive" }
        require(bodyMarkdown.isNotBlank()) { "Tutor message body must not be blank" }
        require(logicalOperationId.isNotBlank()) { "Tutor logical operation id must not be blank" }
        require(createdAtEpochMillis >= 0L) { "Tutor message creation time must not be negative" }
    }
}

data class AppendTutorAssistantMessageCommand(
    val conversationId: String,
    val messageId: String,
    val ordinal: Int,
    val replyToMessageId: String?,
    val bodyMarkdown: String,
    val logicalOperationId: String?,
    val status: TutorMessageStatus = TutorMessageStatus.SUCCEEDED,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val errorCode: String? = null,
) {
    init {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        require(messageId.isNotBlank()) { "Tutor message id must not be blank" }
        require(ordinal > 0) { "Tutor message ordinal must be positive" }
        require(bodyMarkdown.isNotBlank()) { "Tutor message body must not be blank" }
        require(
            logicalOperationId == null || logicalOperationId.isNotBlank(),
        ) { "Tutor logical operation id must be null or non-blank" }
        require(replyToMessageId == null || replyToMessageId.isNotBlank()) {
            "Tutor reply-to id must be null or non-blank"
        }
        require(createdAtEpochMillis >= 0L) { "Tutor message creation time must not be negative" }
        require(completedAtEpochMillis == null || completedAtEpochMillis >= createdAtEpochMillis) {
            "Tutor message completion time cannot precede creation"
        }
        require(
            status in setOf(
                TutorMessageStatus.STREAMING,
                TutorMessageStatus.SUCCEEDED,
                TutorMessageStatus.FAILED,
                TutorMessageStatus.CANCELLED,
            ),
        ) { "Tutor assistant messages use a terminal or streaming status" }
        require(status != TutorMessageStatus.SUCCEEDED || completedAtEpochMillis != null) {
            "A succeeded assistant message requires a completion time"
        }
    }
}

data class UpdateTutorMessageStatusCommand(
    val messageId: String,
    val expectedStatus: TutorMessageStatus,
    val nextStatus: TutorMessageStatus,
    val bodyMarkdown: String?,
    val completedAtEpochMillis: Long?,
    val errorCode: String?,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(messageId.isNotBlank()) { "Tutor message id must not be blank" }
        require(updatedAtEpochMillis >= 0L) { "Tutor message update time must not be negative" }
        require(completedAtEpochMillis == null || completedAtEpochMillis >= updatedAtEpochMillis) {
            "Tutor message completion time cannot precede its update"
        }
    }
}

data class PauseTutorConversationCommand(
    val conversationId: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        require(occurredAtEpochMillis >= 0L) { "Tutor conversation pause time must not be negative" }
    }
}

data class ArchiveTutorConversationCommand(
    val conversationId: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        require(occurredAtEpochMillis >= 0L) {
            "Tutor conversation archive time must not be negative"
        }
    }
}

data class DeleteTutorConversationCommand(
    val conversationId: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        require(occurredAtEpochMillis >= 0L) {
            "Tutor conversation deletion time must not be negative"
        }
    }
}

data class SaveTutorConversationDraftCommand(
    val conversationId: String,
    val draft: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        require(draft.isNotBlank()) { "Tutor conversation draft must not be blank" }
        require(occurredAtEpochMillis >= 0L) {
            "Tutor conversation draft save time must not be negative"
        }
    }
}

data class ClearTutorConversationDraftCommand(
    val conversationId: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        require(occurredAtEpochMillis >= 0L) {
            "Tutor conversation draft clear time must not be negative"
        }
    }
}

interface TutorConversationRepository {
    fun observeRecent(limit: Int = 20): Flow<List<TutorConversation>>

    fun observeConversation(conversationId: String): Flow<TutorConversationSnapshot?>

    suspend fun createConversation(command: CreateTutorConversationCommand): TutorConversation

    suspend fun appendStudentMessage(command: AppendTutorStudentMessageCommand): TutorMessage

    suspend fun appendAssistantMessage(command: AppendTutorAssistantMessageCommand): TutorMessage

    suspend fun updateMessageStatus(command: UpdateTutorMessageStatusCommand): TutorMessage

    suspend fun pauseConversation(command: PauseTutorConversationCommand): TutorConversation

    suspend fun archiveConversation(command: ArchiveTutorConversationCommand): TutorConversation

    suspend fun deleteConversation(command: DeleteTutorConversationCommand)

    suspend fun saveDraft(command: SaveTutorConversationDraftCommand)

    suspend fun clearDraft(command: ClearTutorConversationDraftCommand)
}
