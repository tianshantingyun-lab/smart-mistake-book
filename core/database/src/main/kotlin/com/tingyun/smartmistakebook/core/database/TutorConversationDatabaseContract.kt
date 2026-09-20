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
    /** 真实消息行数（列表展示用；last_turn_ordinal 是序号语义，讲题会话有空洞）。 */
    val messageCount: Int = 0,
)

data class TutorMessageRecord(
    val messageId: String,
    val conversationId: String,
    val ordinal: Int,
    val role: String,
    val bodyMarkdown: String,
    val thinkingMarkdown: String? = null,
    /** 本轮绑定的题（学生消息行）；两列同时为空表示无题轮。 */
    val boundProblemId: String? = null,
    val boundProblemRevisionId: String? = null,
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
    /**
     * 本轮绑定的题（本地校验通过后的声明）；两列同时为空表示无题轮。
     * 两列是一个整体：只给其一无法定位确定题面，因此由 [requireBoundQuestionPair] 统一校验。
     */
    val boundProblemId: String? = null,
    val boundProblemRevisionId: String? = null,
) {
    init {
        requireBoundQuestionPair(boundProblemId, boundProblemRevisionId)
    }
}

/**
 * 题引用的两列必须同进同出。只给 `problemId` 不给 `problemRevisionId` 的"半绑定"会把
 * "哪一道题"退化成"哪一族题"——题面改了就是另一道题，答案与学习证据都不可搬。
 */
internal fun requireBoundQuestionPair(problemId: String?, problemRevisionId: String?) {
    require((problemId == null) == (problemRevisionId == null)) {
        "A bound question reference needs both its problem id and its revision id"
    }
    require(problemId == null || problemId.isNotBlank()) {
        "A bound question problem id must not be blank"
    }
    require(problemRevisionId == null || problemRevisionId.isNotBlank()) {
        "A bound question revision id must not be blank"
    }
}

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
    val thinkingMarkdown: String? = null,
    val logicalOperationId: String?,
    val status: String,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val errorCode: String?,
)

/**
 * 把本轮绑定的题写到学生消息行上。
 *
 * 为什么是一次**后写**而不是随学生消息一起写：绑定 = 模型声明 + 本地两条校验，而两者都要等
 * 模型回复到手；学生消息在派发前就已经落库（写侧门控的引文语料依赖它）。所以顺序只能是
 * "先写学生消息、拿到回复后再补绑定"。
 */
data class BindStudentMessageQuestionDatabaseCommand(
    val messageId: String,
    val boundProblemId: String,
    val boundProblemRevisionId: String,
) {
    init {
        require(messageId.isNotBlank()) { "Tutor message id must not be blank" }
        requireBoundQuestionPair(boundProblemId, boundProblemRevisionId)
        require(boundProblemId.isNotBlank()) { "A binding must name a problem" }
    }
}

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
