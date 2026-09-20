package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.AppendTutorAssistantMessageCommand
import com.tingyun.smartmistakebook.core.domain.AppendTutorStudentMessageCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ClearTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.DeleteTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.PauseTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorConversation
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorConversationStatus
import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.TutorMessageRole
import com.tingyun.smartmistakebook.core.domain.TutorMessageStatus
import com.tingyun.smartmistakebook.core.domain.TutorSendState
import com.tingyun.smartmistakebook.core.domain.UpdateTutorMessageStatusCommand
import com.tingyun.smartmistakebook.core.model.AppFailure
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3 回归：讲题页的学生轮次必须真的落进 `tutor_message`。
 *
 * 缺陷现场：错题讲题页（`SavedMistakeTutorRoute`）从头到尾没有一方创建 `tutor_conversation`
 * 行——`TutorSessionViewModel` 只为拍照会话创建——而 `tutor_message.conversation_id` 上有外键。
 * 于是每一次 `appendStudentMessage` 都抛（SQLite 外键 787），异常被 `runCatching` 吞掉：
 * 学生打了字、模型也回了，`tutor_message` 里一行 STUDENT 都没有，写侧门控的引文核对
 * 永远是空语料，纯文字作答的正向判定机械不可达。
 *
 * 这里的替身按 Room 的真实形状工作：`appendStudentMessage` 对不存在的会话**抛错**（不是
 * 静默丢弃），`createConversation` 对同锚点幂等、对异锚点抛冲突（`ImmutablePayloadConflict
 * Exception` 的语义）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TutorRespondStudentTurnPersistenceTest {
    @Test
    fun studentTurnIsPersistedWhenTheConversationRowWasNeverCreated() = runTest {
        val conversations = TutorConversationRows()
        val modelTasks = ExecutingModelTasks()
        val sink = sink(conversations, modelTasks)
        val commands = TutorRespondCommands(scope = this, sink = sink)

        commands.collect(request = request(), clearDraftOnPersist = true)
        advanceUntilIdle()

        // 会话按需创建：锚点按本轮题面派生（讲题会话同规则）。
        val created = conversations.createCommands.single()
        assertEquals("tutor-conv:captured:$SESSION_ID", created.conversationId)
        assertEquals(TutorConversationAnchorKind.EPHEMERAL_DRAFT, created.anchorKind)
        assertEquals(SESSION_ID, created.anchorId)
        assertEquals("document-1:2", created.anchorRevisionId)
        // 学生这一轮的文字真的进了会话。
        val studentMessage = conversations.studentMessages.single()
        assertEquals("tutor-conv:captured:$SESSION_ID", studentMessage.conversationId)
        assertEquals(STUDENT_MESSAGE, studentMessage.bodyMarkdown)
        assertEquals(1, studentMessage.ordinal)
        assertEquals(request().requestId, studentMessage.logicalOperationId)
        // 落库失败不该拖住派发。
        assertEquals(1, modelTasks.executedRequests.size)
        assertNull(chatStartError)
    }

    @Test
    fun existingConversationIsReusedInsteadOfBeingRecreated() = runTest {
        val conversations = TutorConversationRows()
        // 拍照会话 / 大厅建的会话先于学生发言存在（锚点由各自的入口决定）。
        conversations.seed(
            TutorConversation(
                conversationId = "tutor-conv:captured:$SESSION_ID",
                anchorKind = TutorConversationAnchorKind.EPHEMERAL_DRAFT,
                anchorId = SESSION_ID,
                anchorRevisionId = "draft-1:2",
                status = TutorConversationStatus.ACTIVE,
                title = "求函数的单调区间",
                createdAtEpochMillis = 10,
                updatedAtEpochMillis = 10,
                lastTurnOrdinal = 0,
            ),
        )
        val modelTasks = ExecutingModelTasks()
        val sink = sink(conversations, modelTasks)
        val commands = TutorRespondCommands(scope = this, sink = sink)

        commands.collect(request = request(), clearDraftOnPersist = true)
        advanceUntilIdle()

        assertTrue(conversations.createCommands.isEmpty())
        assertEquals(STUDENT_MESSAGE, conversations.studentMessages.single().bodyMarkdown)
    }

    @Test
    fun aFailingLedgerStillLetsTheTurnReachTheModel() = runTest {
        val conversations = TutorConversationRows().apply {
            appendFailure = IllegalStateException("tutor_message cannot be written")
        }
        val modelTasks = ExecutingModelTasks()
        val sink = sink(conversations, modelTasks)
        val commands = TutorRespondCommands(scope = this, sink = sink)

        commands.collect(request = request(), clearDraftOnPersist = true)
        advanceUntilIdle()

        // 簿记失败不阻断发信：模型仍然收到这一轮，学生也不会看到一条与模型无关的报错。
        assertEquals(1, modelTasks.executedRequests.size)
        assertNull(chatStartError)
    }

    private fun sink(
        conversations: TutorConversationRepository,
        modelTasks: ModelTaskRepository,
    ) = TutorRespondSink(
        currentProvider = { provider },
        question = { question() },
        profile = { StudyProfileOverview() },
        clock = { 100 },
        chatSubmitPending = { chatSubmitPending },
        setChatSubmitPending = { chatSubmitPending = it },
        tutorSendState = { tutorSendState },
        setTutorSendState = { tutorSendState = it },
        setChatStartError = { chatStartError = it },
        setLocallyStartedRespondRequestId = { locallyStartedRespondRequestId = it },
        setChatDraft = { chatDraft = it },
        currentPlanOutput = { error("No plan output expected") },
        currentResponse = { null },
        currentInput = { error("No plan input expected") },
        observedTask = { error("No observed task expected") },
        tutorRespondTasks = { emptyList() },
        answerExposureKeys = { emptySet() },
        chatSending = { false },
        modelTasks = modelTasks,
        conversations = conversations,
    )

    private fun request(): ModelTaskRequest = buildTutorRespondRequest(
        question = question(),
        profile = StudyProfileOverview(),
        provider = provider,
        requestId = "tutor-respond:persistence-1",
        occurredAtEpochMillis = 100,
        responseOrdinal = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        studentMessage = STUDENT_MESSAGE,
        visibleTutorContextMarkdown = "先判断导数的正负变化。",
        priorMessages = emptyList(),
    )

    private fun question() = TutorQuestionContext(
        sessionId = SESSION_ID,
        revisionNumber = 2,
        subject = "MATH",
        title = "求函数的单调区间",
        questionDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-1",
                title = "求函数的单调区间",
                blocks = listOf(
                    ContentBlock.Paragraph(id = "stem", markdown = "已知 f(x)=x³-3x，求单调区间。"),
                ),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "asset-1",
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        ),
    )

    private class ExecutingModelTasks : ModelTaskRepository {
        val executedRequests = mutableListOf<ModelTaskRequest>()

        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = flowOf(emptyList())

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            executedRequests += request
            val input = request.input as TutorRespondInput
            emit(
                ModelTaskSnapshot(
                    taskId = "task:${request.requestId}",
                    request = request,
                    requestFingerprint = ModelTaskFingerprint.of(request),
                    status = ModelTaskStatus.SUCCEEDED,
                    stateVersion = 2,
                    stage = ModelTaskStage.COMPLETE,
                    userMessage = "回复已准备好",
                    attemptCount = 1,
                    provider = provider,
                    output = TutorRespondOutput(
                        sessionId = input.sessionId,
                        draftRevisionNumber = input.draftRevisionNumber,
                        questionDocumentId = input.questionDocument.id,
                        responseOrdinal = input.responseOrdinal,
                        messageMarkdown = "先看临界点两侧的符号。",
                        modelVersion = "model-v1",
                    ),
                    createdAtEpochMillis = request.occurredAtEpochMillis,
                    updatedAtEpochMillis = request.occurredAtEpochMillis,
                ),
            )
        }
    }

    /**
     * 按 Room 的真实形状工作的会话替身：不存在的会话上 append **抛外键错**，
     * `createConversation` 对同锚点幂等、对异锚点抛冲突。
     */
    private class TutorConversationRows : TutorConversationRepository {
        private val conversations = MutableStateFlow<Map<String, TutorConversation>>(emptyMap())
        val createCommands = mutableListOf<CreateTutorConversationCommand>()
        val studentMessages = mutableListOf<AppendTutorStudentMessageCommand>()
        var appendFailure: Throwable? = null

        suspend fun seed(conversation: TutorConversation) {
            conversations.update { rows -> rows + (conversation.conversationId to conversation) }
        }

        override fun observeRecent(limit: Int): Flow<List<TutorConversation>> =
            conversations.map { rows ->
                rows.values.sortedByDescending(TutorConversation::updatedAtEpochMillis).take(limit)
            }

        override fun observeConversation(
            conversationId: String,
        ): Flow<TutorConversationSnapshot?> = conversations.map { rows ->
            rows[conversationId]?.let { conversation ->
                TutorConversationSnapshot(conversation, emptyList())
            }
        }

        override suspend fun createConversation(
            command: CreateTutorConversationCommand,
        ): TutorConversation {
            createCommands += command
            conversations.value[command.conversationId]?.let { existing ->
                check(
                    existing.anchorKind == command.anchorKind &&
                        existing.anchorId == command.anchorId &&
                        existing.anchorRevisionId == command.anchorRevisionId,
                ) { "ImmutablePayloadConflictException(tutor_conversation, ${command.conversationId})" }
                return existing
            }
            val created = TutorConversation(
                conversationId = command.conversationId,
                anchorKind = command.anchorKind,
                anchorId = command.anchorId,
                anchorRevisionId = command.anchorRevisionId,
                status = TutorConversationStatus.ACTIVE,
                title = command.title,
                createdAtEpochMillis = command.createdAtEpochMillis,
                updatedAtEpochMillis = command.createdAtEpochMillis,
                lastTurnOrdinal = 0,
            )
            conversations.update { rows -> rows + (created.conversationId to created) }
            return created
        }

        override suspend fun appendStudentMessage(
            command: AppendTutorStudentMessageCommand,
        ): TutorMessage {
            appendFailure?.let { failure -> throw failure }
            check(command.conversationId in conversations.value) {
                "FOREIGN KEY constraint failed (code 787)"
            }
            studentMessages += command
            val existing = conversations.value.getValue(command.conversationId)
            conversations.update { rows ->
                rows + (
                    command.conversationId to existing.copy(
                        updatedAtEpochMillis = command.createdAtEpochMillis,
                        lastTurnOrdinal = command.ordinal,
                    )
                    )
            }
            return TutorMessage(
                messageId = command.messageId,
                conversationId = command.conversationId,
                ordinal = command.ordinal,
                role = TutorMessageRole.STUDENT,
                bodyMarkdown = command.bodyMarkdown,
                status = TutorMessageStatus.PERSISTED,
                logicalOperationId = command.logicalOperationId,
                replyToMessageId = null,
                createdAtEpochMillis = command.createdAtEpochMillis,
                completedAtEpochMillis = command.createdAtEpochMillis,
                errorCode = null,
            )
        }

        override suspend fun appendAssistantMessage(
            command: AppendTutorAssistantMessageCommand,
        ): TutorMessage = error("No assistant write expected")

        override suspend fun updateMessageStatus(
            command: UpdateTutorMessageStatusCommand,
        ): TutorMessage = error("No message status write expected")

        override suspend fun pauseConversation(
            command: PauseTutorConversationCommand,
        ): TutorConversation = error("No pause expected")

        override suspend fun archiveConversation(
            command: ArchiveTutorConversationCommand,
        ): TutorConversation = error("No archive expected")

        override suspend fun deleteConversation(command: DeleteTutorConversationCommand) =
            error("No delete expected")

        override suspend fun saveDraft(command: SaveTutorConversationDraftCommand) =
            error("No draft write expected")

        override suspend fun clearDraft(command: ClearTutorConversationDraftCommand) =
            error("No draft clear expected")
    }

    private var chatSubmitPending = false
    private var tutorSendState = TutorSendState()
    private var chatStartError: AppFailure? = null
    private var locallyStartedRespondRequestId: String? = null
    private var chatDraft = ""

    private companion object {
        const val SESSION_ID = "mistake-tutor-1"
        const val STUDENT_MESSAGE = "我先两边同时除以 2"

        val provider = ProviderCapabilitySnapshot(
            providerId = "configured-provider",
            providerDisplayName = "已配置模型",
            modelId = "tutor-model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN, ModelTaskKind.TUTOR_RESPOND),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = true,
            executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
        )
    }
}
