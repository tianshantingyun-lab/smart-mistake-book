package com.tingyun.smartmistakebook.feature.tutor

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.domain.AppendTutorAssistantMessageCommand
import com.tingyun.smartmistakebook.core.domain.AppendTutorStudentMessageCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ClearTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.DeleteTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.PauseTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.RevealTutorSolutionCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorConversation
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorConversationStatus
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.TutorMessageRole
import com.tingyun.smartmistakebook.core.domain.TutorMessageStatus
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.UpdateTutorMessageStatusCommand
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRoundQuestionDeclaration
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * 替身输出里的本轮绑定：答案暴露要求"本轮确实有绑定题"（暴露记录是"学生看过这道题的答案"
 * 的证据，锚不到题就没有主人）。
 */
internal val EXPOSURE_BOUND_QUESTION = TutorRoundQuestionDeclaration(
    problemId = "bound-problem-1",
    problemRevisionId = "bound-revision-1",
    anchorTerms = listOf("答案"),
)

@RunWith(AndroidJUnit4::class)
abstract class CapturedTutorSessionTestBase {
    @get:Rule
    val composeRule = createComposeRule()

    protected fun captureCurrentTutorScreen(displayName: String = "tutor-active-current.png") {
        val resolver = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .contentResolver
        val collection = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val relativePath = "Pictures/SmartMistakeBookQA/"
        resolver.delete(
            collection,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName),
        )
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val saved = checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        check(saved) { "Tutor screenshot could not be encoded" }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }

    protected fun longSession(): ConfirmedTutorSession {
        val baseSession = session()
        return baseSession.copy(
            questionDocument = baseSession.questionDocument.copy(
                document = baseSession.questionDocument.document.copy(
                    blocks = listOf(
                        ContentBlock.Paragraph(
                            id = "stem",
                            markdown = (1..80).joinToString("\n") { line ->
                                "第 $line 行：继续分析这道题的已知条件。"
                            },
                        ),
                    ),
                ),
            ),
        )
    }

    protected fun session(): ConfirmedTutorSession {
        val sourceAssetId = "asset-1"
        return ConfirmedTutorSession(
            sessionId = "session-1",
            draftId = "draft-1",
            draftRevisionNumber = 2,
            subject = "MATH",
            title = "求函数的单调区间",
            questionDocument = CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "document-1",
                    title = "求函数的单调区间",
                    blocks = listOf(
                        ContentBlock.Paragraph(
                            id = "stem",
                            markdown = "已知 f(x)=x³-3x，求它的单调区间。",
                        ),
                    ),
                ),
                blockEvidence = listOf(
                    QuestionBlockEvidence(
                        blockId = "stem",
                        sourceAssetId = sourceAssetId,
                        sourceRegion = NormalizedSourceRegion(
                            left = 0.0,
                            top = 0.0,
                            right = 1.0,
                            bottom = 1.0,
                        ),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_CORRECTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
            ),
            sourceImageUri = "file:///data/user/0/example/files/source.jpg",
            createdAtEpochMillis = 1_000,
            isSaved = false,
            errorBookEntryId = null,
        )
    }

    protected fun unavailableModelTasks(): ModelTaskRepository = object : ModelTaskRepository {
        override suspend fun capabilities() = ProviderCapabilitySnapshot(
            providerId = "unconfigured",
            providerDisplayName = "尚未配置模型",
            modelId = "unconfigured",
            supportedTasks = emptySet(),
            supportsImageInput = false,
            supportsStructuredOutput = false,
            supportsStreaming = false,
            executionLocation = ModelExecutionLocation.UNAVAILABLE,
        )

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = flowOf(emptyList())

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
            error("Unavailable provider must not execute")
    }

    protected fun succeededExplanationOnlyModelTasks(
        session: ConfirmedTutorSession,
        solutionMarkdown: String = tutorOutput().plan.solutionMarkdown,
    ): RecordingModelTaskRepository {
        val provider = ProviderCapabilitySnapshot(
            providerId = "configured-provider",
            providerDisplayName = "已配置模型",
            modelId = "tutor-model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = true,
        )
        val request = buildTutorPlanRequest(
            session = session,
            profile = StudyProfileOverview(),
            provider = provider,
            requestId = "tutor-plan-action-only",
            occurredAtEpochMillis = 100,
        )
        val output = tutorOutput().copy(
            plan = tutorOutput().plan.copy(
                diagnosticItem = null,
                solutionMarkdown = solutionMarkdown,
            ),
        )
        return RecordingModelTaskRepository(
            provider = provider,
            snapshot = ModelTaskSnapshot(
                taskId = "task-action-only",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                status = com.tingyun.smartmistakebook.core.model.ModelTaskStatus.SUCCEEDED,
                stateVersion = 1,
                stage = ModelTaskStage.COMPLETE,
                userMessage = "当前题讲解已准备",
                attemptCount = 1,
                provider = provider,
                output = output,
                createdAtEpochMillis = 100,
                updatedAtEpochMillis = 200,
            ),
        )
    }

    protected fun emptyInteractions(): TutorInteractionRepository = object : TutorInteractionRepository {
        override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> = flowOf(emptyList())

        override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse =
            error("No interaction write expected")

        override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse =
            error("No interaction write expected")

        override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse =
            error("No interaction write expected")

        override suspend fun recordSolutionExposure(
            command: RecordTutorSolutionExposureCommand,
        ) = error("No exposure write expected")
    }

    /**
     * @param recordedStudentMessages 可选：把每次 `appendStudentMessage` 的命令记下来，
     *   供"学生文字必须落库"的用例断言（写侧门控的引文核对依赖这些行）。
     */
    protected fun emptyConversations(
        recordedStudentMessages: MutableList<AppendTutorStudentMessageCommand> = mutableListOf(),
    ): TutorConversationRepository =
        object : TutorConversationRepository {
            private val snapshot = MutableStateFlow<TutorConversationSnapshot?>(null)

            override fun observeRecent(limit: Int): Flow<List<TutorConversation>> =
                snapshot.map { it?.conversation?.let(::listOf).orEmpty() }

            override fun observeConversation(
                conversationId: String,
            ): Flow<TutorConversationSnapshot?> = snapshot

            override suspend fun createConversation(
                command: CreateTutorConversationCommand,
            ): TutorConversation {
                val conversation = TutorConversation(
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
                snapshot.value = TutorConversationSnapshot(conversation, emptyList())
                return conversation
            }

            override suspend fun appendStudentMessage(
                command: AppendTutorStudentMessageCommand,
            ): TutorMessage {
                recordedStudentMessages += command
                val message = TutorMessage(
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
                snapshot.value = snapshot.value?.let { current ->
                    current.copy(
                        conversation = current.conversation.copy(
                            updatedAtEpochMillis = command.createdAtEpochMillis,
                            lastTurnOrdinal = command.ordinal,
                        ),
                        messages = current.messages + message,
                    )
                }
                return message
            }

            override suspend fun appendAssistantMessage(
                command: AppendTutorAssistantMessageCommand,
            ): TutorMessage {
                val message = TutorMessage(
                    messageId = command.messageId,
                    conversationId = command.conversationId,
                    ordinal = command.ordinal,
                    role = TutorMessageRole.ASSISTANT,
                    bodyMarkdown = command.bodyMarkdown,
                    status = command.status,
                    logicalOperationId = command.logicalOperationId,
                    replyToMessageId = command.replyToMessageId,
                    createdAtEpochMillis = command.createdAtEpochMillis,
                    completedAtEpochMillis = command.completedAtEpochMillis,
                    errorCode = command.errorCode,
                )
                snapshot.value = snapshot.value?.let { current ->
                    current.copy(
                        conversation = current.conversation.copy(
                            updatedAtEpochMillis = command.completedAtEpochMillis
                                ?: command.createdAtEpochMillis,
                            lastTurnOrdinal = command.ordinal,
                        ),
                        messages = current.messages + message,
                    )
                }
                return message
            }

            override suspend fun updateMessageStatus(
                command: UpdateTutorMessageStatusCommand,
            ): TutorMessage {
                val current = snapshot.value ?: error("No tutor conversation")
                val existing = current.messages.first { it.messageId == command.messageId }
                val updated = existing.copy(
                    status = command.nextStatus,
                    bodyMarkdown = command.bodyMarkdown ?: existing.bodyMarkdown,
                    completedAtEpochMillis = command.completedAtEpochMillis,
                    errorCode = command.errorCode,
                )
                snapshot.value = current.copy(
                    messages = current.messages.map { message ->
                        if (message.messageId == command.messageId) updated else message
                    },
                )
                return updated
            }

            override suspend fun pauseConversation(
                command: PauseTutorConversationCommand,
            ): TutorConversation {
                val current = snapshot.value ?: error("No tutor conversation")
                val conversation = current.conversation.copy(
                    status = TutorConversationStatus.PAUSED,
                    updatedAtEpochMillis = command.occurredAtEpochMillis,
                )
                snapshot.value = current.copy(conversation = conversation)
                return conversation
            }

            override suspend fun archiveConversation(
                command: ArchiveTutorConversationCommand,
            ): TutorConversation {
                val current = snapshot.value ?: error("No tutor conversation")
                val conversation = current.conversation.copy(
                    status = TutorConversationStatus.ARCHIVED,
                    updatedAtEpochMillis = command.occurredAtEpochMillis,
                )
                snapshot.value = current.copy(conversation = conversation)
                return conversation
            }

            override suspend fun deleteConversation(
                command: DeleteTutorConversationCommand,
            ) {
                snapshot.value = null
            }

            override suspend fun saveDraft(
                command: SaveTutorConversationDraftCommand,
            ) {
                snapshot.value = snapshot.value?.let { current ->
                    current.copy(
                        conversation = current.conversation.copy(
                            studentDraft = command.draft,
                            updatedAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    )
                }
            }

            override suspend fun clearDraft(
                command: ClearTutorConversationDraftCommand,
            ) {
                snapshot.value = snapshot.value?.let { current ->
                    current.copy(
                        conversation = current.conversation.copy(
                            studentDraft = null,
                            updatedAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                    )
                }
            }
        }

    protected fun tutorResponse(choiceId: String): TutorTurnResponse {
        val output = tutorOutput()
        val item = requireNotNull(output.plan.diagnosticItem)
        val evaluation = item.evaluateChoice(choiceId)
        return TutorTurnResponse(
            sessionId = output.sessionId,
            questionDocumentId = output.questionDocumentId,
            revisionNumber = output.draftRevisionNumber,
            cycleOrdinal = output.cycleOrdinal,
            turnOrdinal = output.turnOrdinal,
            diagnosticStemMarkdown = item.stemMarkdown,
            selectedChoiceId = evaluation.choice.id,
            selectedChoiceMarkdown = evaluation.choice.markdown,
            selectionWasCorrect = evaluation.isCorrect,
            feedbackMarkdown = requireNotNull(evaluation.choice.feedbackMarkdown),
            submittedAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
        )
    }

    protected fun actionResponse(
        requestedMove: TutorMoveType? = null,
        solutionRevealed: Boolean = false,
    ): TutorTurnResponse {
        val output = tutorOutput()
        return TutorTurnResponse(
            sessionId = output.sessionId,
            questionDocumentId = output.questionDocumentId,
            revisionNumber = output.draftRevisionNumber,
            cycleOrdinal = output.cycleOrdinal,
            turnOrdinal = output.turnOrdinal,
            diagnosticStemMarkdown = null,
            selectedChoiceId = null,
            selectedChoiceMarkdown = null,
            selectionWasCorrect = null,
            feedbackMarkdown = null,
            requestedMove = requestedMove,
            solutionRevealed = solutionRevealed,
            submittedAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
        )
    }

    protected fun tutorOutput() = TutorPlanOutput(
        sessionId = "session-1",
        draftRevisionNumber = 2,
        questionDocumentId = "document-1",
        plan = TutorTurnPlan(
            openingMarkdown = "先判断导数的正负变化。",
            diagnosticItem = TutorAssessmentItem(
                id = "diagnostic-1",
                stemMarkdown = "导数先正后负时，原函数怎样变化？",
                choices = listOf(
                    TutorChoice("choice-1", "先增后减", "这个对应关系是正确的。"),
                    TutorChoice("choice-2", "先减后增", "你把正负关系反过来了。"),
                    TutorChoice("choice-3", "始终递增", "这里忽略了导数变号。"),
                ),
                correctChoiceId = "choice-1",
            ),
            solutionMarkdown = "完整主解法内容",
            alternateMethodMarkdown = "符号表替代解法内容",
            difficultyReasonMarkdown = "用于区分符号对应和变号遗漏。",
            targetedEvidenceLabels = emptyList(),
            inferredKnowledgeLabels = listOf("导数", "函数单调性"),
            suggestedMoves = listOf(
                TutorSuggestedMove(
                    id = "change",
                    label = "换一种思路",
                    type = TutorMoveType.CHANGE_REPRESENTATION,
                ),
                TutorSuggestedMove(
                    id = "solution",
                    label = "查看完整讲解",
                    type = TutorMoveType.REVEAL_SOLUTION,
                ),
            ),
        ),
        modelVersion = "model-v1",
    )

    protected inner class RestartCycleModelTaskRepository(
        session: ConfirmedTutorSession,
        exactStudentMessage: String,
    ) : ModelTaskRepository {
        private val provider = ProviderCapabilitySnapshot(
            providerId = "configured-provider",
            providerDisplayName = "已配置模型",
            modelId = "tutor-model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN, ModelTaskKind.TUTOR_RESPOND),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = true,
        )
        private val priorTurns = (1 until TutorPlanInput.MAX_TURNS).map { turnOrdinal ->
            TutorTurnHistoryEntry(
                turnOrdinal = turnOrdinal,
                diagnosticStemMarkdown = "第 $turnOrdinal 步应怎样继续？",
                selectedChoiceMarkdown = "先减后增",
                selectionWasCorrect = false,
                feedbackMarkdown = "继续围绕当前题修正这一步。",
                requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
            )
        }
        private val planRequest = buildTutorPlanRequest(
            session = session,
            profile = StudyProfileOverview(),
            provider = provider,
            requestId = "tutor-plan-restart-source",
            occurredAtEpochMillis = 100,
            priorTurns = priorTurns,
        )
        private val planSnapshot = ModelTaskSnapshot(
            taskId = "task-tutor-plan-restart-source",
            request = planRequest,
            requestFingerprint = ModelTaskFingerprint.of(planRequest),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "讲解已准备好",
            attemptCount = 1,
            provider = provider,
            output = tutorOutput().copy(turnOrdinal = TutorPlanInput.MAX_TURNS),
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 101,
        )
        private val respondRequest = buildTutorRespondRequest(
            question = session.toTutorQuestionContext(),
            profile = StudyProfileOverview(),
            provider = provider,
            requestId = "tutor-respond-stuck-step",
            occurredAtEpochMillis = 50,
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = TutorPlanInput.MAX_TURNS,
            studentMessage = exactStudentMessage,
            visibleTutorContextMarkdown = "正在讲配方法。",
            priorMessages = emptyList(),
        )
        private val respondSnapshot = ModelTaskSnapshot(
            taskId = "task-tutor-respond-stuck-step",
            request = respondRequest,
            requestFingerprint = ModelTaskFingerprint.of(respondRequest),
            status = ModelTaskStatus.CANCELLED,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "回复未完成",
            attemptCount = 1,
            provider = provider,
            output = null,
            createdAtEpochMillis = 50,
            updatedAtEpochMillis = 51,
        )
        val planRequests = mutableListOf<ModelTaskRequest>()

        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(
            listOf(planSnapshot, respondSnapshot).firstOrNull {
                it.request.requestId == requestId
            },
        )

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = when (kind) {
            ModelTaskKind.TUTOR_PLAN -> flowOf(listOf(planSnapshot))
            ModelTaskKind.TUTOR_RESPOND -> flowOf(listOf(respondSnapshot))
            else -> flowOf(emptyList())
        }

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            planRequests += request
            emit(
                ModelTaskSnapshot(
                    taskId = "task:${request.requestId}",
                    request = request,
                    requestFingerprint = ModelTaskFingerprint.of(request),
                    status = ModelTaskStatus.RUNNING,
                    stateVersion = 1,
                    stage = ModelTaskStage.PREPARING,
                    userMessage = "正在继续讲解",
                    attemptCount = 1,
                    provider = provider,
                    createdAtEpochMillis = request.occurredAtEpochMillis,
                    updatedAtEpochMillis = request.occurredAtEpochMillis,
                ),
            )
        }
    }

    protected class FixedTutorInteractions(
        private val responses: List<TutorTurnResponse>,
    ) : TutorInteractionRepository {
        override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> = flowOf(responses)

        override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse =
            error("No choice write expected")

        override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse =
            error("No move write expected")

        override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse =
            error("No reveal write expected")

        override suspend fun recordSolutionExposure(
            command: RecordTutorSolutionExposureCommand,
        ) = error("No exposure write expected")
    }

    protected class SuspendingExposureTutorInteractions : TutorInteractionRepository {
        val exposureCommands = mutableListOf<RecordTutorSolutionExposureCommand>()
        var cancellationCount: Int = 0

        override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> = flowOf(emptyList())

        override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse =
            error("No choice write expected")

        override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse =
            error("No move write expected")

        override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse =
            TutorTurnResponse(
                sessionId = command.sessionId,
                questionDocumentId = command.questionDocumentId,
                revisionNumber = command.revisionNumber,
                cycleOrdinal = command.cycleOrdinal,
                turnOrdinal = command.turnOrdinal,
                diagnosticStemMarkdown = null,
                selectedChoiceId = null,
                selectedChoiceMarkdown = null,
                selectionWasCorrect = null,
                feedbackMarkdown = null,
                requestedMove = null,
                solutionRevealed = true,
                submittedAtEpochMillis = command.occurredAtEpochMillis,
                updatedAtEpochMillis = command.occurredAtEpochMillis,
            )

        override suspend fun recordSolutionExposure(command: RecordTutorSolutionExposureCommand) {
            exposureCommands += command
            try {
                awaitCancellation()
            } finally {
                cancellationCount += 1
            }
        }
    }

    protected class RecordingModelTaskRepository(
        private val provider: ProviderCapabilitySnapshot,
        private val snapshot: ModelTaskSnapshot,
    ) : ModelTaskRepository {
        var executeCalls: Int = 0

        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(
            snapshot.takeIf { it.request.requestId == requestId },
        )

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = flowOf(
            listOf(snapshot).filter {
                it.request.input.subjectId == subjectId && it.request.input.kind == kind
            },
        )

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            executeCalls += 1
            error("An explanation-only action must not manufacture another tutor turn")
        }
    }

    protected inner class ChatModelTaskRepository(
        private val session: ConfirmedTutorSession,
        restoredPendingMessage: String? = null,
        restoredSucceededMessage: String? = null,
        private val restoredSucceededRevealsSolution: Boolean = false,
        private val restoredSucceededIntentDecision: TutorIntentDecision =
            TutorIntentDecision.currentQuestionDefault(),
        private val restoredSucceededSuggestedMoves: List<TutorSuggestedMove> = emptyList(),
        private val restoredCycleOrdinal: Int = 1,
        private val restoredTurnOrdinal: Int = 1,
        private val restoredFailureStatus: ModelTaskStatus? = null,
        private val restoredFailureCode: ModelFailureCode? = null,
        private val restoredFailureMessage: String = "请解释为什么要分区间",
        private val restoredRequestedMove: TutorMoveType? = null,
        private val restoredPlanStatus: ModelTaskStatus = ModelTaskStatus.SUCCEEDED,
        private val includeInitialPlan: Boolean = true,
        externalProvider: Boolean = false,
        currentCapabilities: ProviderCapabilitySnapshot? = null,
        private val holdRespondExecution: Boolean = false,
    ) : ModelTaskRepository {
        private val provider = ProviderCapabilitySnapshot(
            providerId = "configured-provider",
            providerDisplayName = "已配置模型",
            modelId = "tutor-model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN, ModelTaskKind.TUTOR_RESPOND),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = true,
            executionLocation = if (externalProvider) {
                ModelExecutionLocation.EXTERNAL_PROVIDER
            } else {
                ModelExecutionLocation.LOCAL_NO_EGRESS
            },
        )
        private var currentCapabilitiesOverride = currentCapabilities
        private val planRequest = buildTutorPlanRequest(
            session = session,
            profile = StudyProfileOverview(),
            provider = provider,
            requestId = "tutor-plan-chat",
            occurredAtEpochMillis = 100,
        )
        private val planSnapshot = ModelTaskSnapshot(
            taskId = "task-plan-chat",
            request = planRequest,
            requestFingerprint = ModelTaskFingerprint.of(planRequest),
            status = restoredPlanStatus,
            stateVersion = 1,
            stage = if (restoredPlanStatus == ModelTaskStatus.SUCCEEDED) {
                ModelTaskStage.COMPLETE
            } else {
                ModelTaskStage.PREPARING
            },
            userMessage = if (restoredPlanStatus == ModelTaskStatus.SUCCEEDED) {
                "讲解已准备好"
            } else {
                "正在准备讲解"
            },
            attemptCount = 1,
            provider = provider,
            output = tutorOutput().takeIf {
                restoredPlanStatus == ModelTaskStatus.SUCCEEDED
            },
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 200,
        )
        val planTasks = MutableStateFlow(
            if (includeInitialPlan) listOf(planSnapshot) else emptyList(),
        )
        val respondTasks = MutableStateFlow<List<ModelTaskSnapshot>>(emptyList())
        val planRequests = mutableListOf<ModelTaskRequest>()
        val respondRequests = mutableListOf<ModelTaskRequest>()
        var executePlanCalls: Int = 0
        var executeRespondCalls: Int = 0
        val capabilitySnapshot: ProviderCapabilitySnapshot
            get() = currentCapabilitiesOverride ?: provider

        fun useCapabilities(capabilities: ProviderCapabilitySnapshot) {
            currentCapabilitiesOverride = capabilities
        }

        init {
            require(
                listOf(
                    restoredPendingMessage != null,
                    restoredSucceededMessage != null,
                    restoredFailureStatus != null,
                ).count { it } <= 1,
            )
            require(
                restoredFailureStatus == null || restoredFailureStatus in setOf(
                    ModelTaskStatus.RETRYABLE_FAILURE,
                    ModelTaskStatus.PERMANENT_FAILURE,
                    ModelTaskStatus.CANCELLED,
                ),
            )
            require(restoredFailureCode == null || restoredFailureStatus != null)
            val restoredMessage = restoredPendingMessage ?: restoredSucceededMessage
                ?: restoredFailureStatus?.let { restoredFailureMessage }
            if (restoredMessage != null) {
                val restoredRequest = buildTutorRespondRequest(
                    question = session.toTutorQuestionContext(),
                    profile = StudyProfileOverview(),
                    provider = provider,
                    requestId = "tutor-respond-restored",
                    occurredAtEpochMillis = 300,
                    responseOrdinal = 1,
                    cycleOrdinal = restoredCycleOrdinal,
                    turnOrdinal = restoredTurnOrdinal,
                    studentMessage = restoredMessage,
                    visibleTutorContextMarkdown = "先判断导数的正负变化。",
                    priorMessages = emptyList(),
                    requestedMove = restoredRequestedMove,
                )
                val restoredInput = restoredRequest.input as TutorRespondInput
                val restoredSucceeded = restoredSucceededMessage != null
                val restoredStatus = when {
                    restoredSucceeded -> ModelTaskStatus.SUCCEEDED
                    restoredFailureStatus != null -> restoredFailureStatus
                    else -> ModelTaskStatus.WAITING_FOR_MODEL
                }
                respondTasks.value = listOf(
                    responseSnapshot(
                        request = restoredRequest,
                        status = restoredStatus,
                        output = if (restoredSucceeded) {
                            TutorRespondOutput(
                                sessionId = restoredInput.sessionId,
                                draftRevisionNumber = restoredInput.draftRevisionNumber,
                                questionDocumentId = restoredInput.questionDocument.id,
                                responseOrdinal = restoredInput.responseOrdinal,
                                cycleOrdinal = restoredInput.cycleOrdinal,
                                turnOrdinal = restoredInput.turnOrdinal,
                                messageMarkdown = "先看导数在临界点两侧的符号。",
                                solutionRevealed = restoredSucceededRevealsSolution,
                                // 答案暴露按绑定：暴露记录要求本轮确实有绑定题。替身直接构造
                                // 输出，所以在这里显式声明（真实路径由解析层写入校验过的绑定）。
                                boundQuestion = EXPOSURE_BOUND_QUESTION,
                                suggestedMoves = restoredSucceededSuggestedMoves,
                                intentDecision = restoredSucceededIntentDecision,
                                modelVersion = "model-v1",
                            )
                        } else {
                            null
                        },
                        stateVersion = if (restoredSucceeded) 3 else 1,
                    ),
                )
            }
        }

        override suspend fun capabilities(): ProviderCapabilitySnapshot =
            currentCapabilitiesOverride ?: provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(
            (planTasks.value + respondTasks.value)
                .firstOrNull { it.request.requestId == requestId },
        )

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = when (kind) {
            ModelTaskKind.TUTOR_PLAN -> planTasks
            ModelTaskKind.TUTOR_RESPOND -> respondTasks
            else -> flowOf(emptyList())
        }

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            when (val input = request.input) {
                is TutorPlanInput -> {
                    executePlanCalls += 1
                    planRequests += request
                    val running = planSnapshot.copy(
                        request = request,
                        requestFingerprint = ModelTaskFingerprint.of(request),
                        status = ModelTaskStatus.RUNNING,
                        stateVersion = 2,
                        stage = ModelTaskStage.PREPARING,
                        userMessage = "正在准备讲解",
                        output = null,
                    )
                    planTasks.value = listOf(running)
                    emit(running)
                    val succeeded = running.copy(
                        status = ModelTaskStatus.SUCCEEDED,
                        stateVersion = 3,
                        stage = ModelTaskStage.COMPLETE,
                        userMessage = "讲解已准备好",
                        output = tutorOutput().copy(
                            sessionId = input.sessionId,
                            draftRevisionNumber = input.draftRevisionNumber,
                            questionDocumentId = input.questionDocument.id,
                            cycleOrdinal = input.cycleOrdinal,
                            turnOrdinal = input.turnOrdinal,
                        ),
                    )
                    planTasks.value = listOf(succeeded)
                    emit(succeeded)
                }
                is TutorRespondInput -> {
                    executeRespondCalls += 1
                    respondRequests += request
                    val running = responseSnapshot(
                        request = request,
                        status = ModelTaskStatus.RUNNING,
                        output = null,
                        stateVersion = 2,
                    )
                    upsert(running)
                    emit(running)
                    if (holdRespondExecution) awaitCancellation()
                    val succeeded = responseSnapshot(
                        request = request,
                        status = ModelTaskStatus.SUCCEEDED,
                        output = TutorRespondOutput(
                            sessionId = input.sessionId,
                            draftRevisionNumber = input.draftRevisionNumber,
                            questionDocumentId = input.questionDocument.id,
                            responseOrdinal = input.responseOrdinal,
                            cycleOrdinal = input.cycleOrdinal,
                            turnOrdinal = input.turnOrdinal,
                            messageMarkdown = "先看导数在临界点两侧的符号。",
                            intentDecision = TutorIntentDecision.currentQuestionDefault(),
                            modelVersion = "model-v1",
                        ),
                        stateVersion = 3,
                    )
                    upsert(succeeded)
                    emit(succeeded)
                }
                else -> error("Chat repository only executes tutor tasks")
            }
        }

        fun publishRestoredSolutionReply(messageMarkdown: String = "先看完整推导。") {
            val current = respondTasks.value.last()
            val input = current.request.input as TutorRespondInput
            upsert(
                responseSnapshot(
                    request = current.request,
                    status = ModelTaskStatus.SUCCEEDED,
                    output = TutorRespondOutput(
                        sessionId = input.sessionId,
                        draftRevisionNumber = input.draftRevisionNumber,
                        questionDocumentId = input.questionDocument.id,
                        responseOrdinal = input.responseOrdinal,
                        cycleOrdinal = input.cycleOrdinal,
                        turnOrdinal = input.turnOrdinal,
                        messageMarkdown = messageMarkdown,
                        solutionRevealed = true,
                        boundQuestion = EXPOSURE_BOUND_QUESTION,
                        intentDecision = TutorIntentDecision.currentQuestionDefault(),
                        modelVersion = "model-v1",
                    ),
                    stateVersion = current.stateVersion + 1,
                ),
            )
        }

        fun publishLatestResponseFailure() {
            val current = respondTasks.value.last()
            upsert(
                responseSnapshot(
                    request = current.request,
                    status = ModelTaskStatus.RETRYABLE_FAILURE,
                    output = null,
                    stateVersion = current.stateVersion + 1,
                ),
            )
        }

        private fun upsert(snapshot: ModelTaskSnapshot) {
            respondTasks.value = respondTasks.value
                .filterNot { it.request.requestId == snapshot.request.requestId }
                .plus(snapshot)
        }

        private fun responseSnapshot(
            request: ModelTaskRequest,
            status: ModelTaskStatus,
            output: TutorRespondOutput?,
            stateVersion: Long,
        ) = ModelTaskSnapshot(
            taskId = "task:${request.requestId}",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = status,
            stateVersion = stateVersion,
            stage = if (status == ModelTaskStatus.SUCCEEDED) {
                ModelTaskStage.COMPLETE
            } else {
                ModelTaskStage.PREPARING
            },
            userMessage = if (status == ModelTaskStatus.SUCCEEDED) {
                "回复已准备好"
            } else {
                "正在回复"
            },
            attemptCount = 1,
            provider = request.egressManifest?.let { manifest ->
                currentCapabilitiesOverride?.takeIf { candidate ->
                    manifest.providerId == candidate.providerId &&
                        manifest.modelId == candidate.modelId &&
                        manifest.providerConfigurationVersion ==
                        candidate.providerConfigurationVersion
                }
            } ?: currentCapabilitiesOverride ?: provider,
            output = output,
            failure = when (status) {
                ModelTaskStatus.RETRYABLE_FAILURE -> ModelTaskFailure(
                    code = restoredFailureCode ?: ModelFailureCode.TIMEOUT,
                    message = "暂时没有完成",
                    retryable = true,
                )
                ModelTaskStatus.PERMANENT_FAILURE -> ModelTaskFailure(
                    code = restoredFailureCode ?: ModelFailureCode.PROVIDER_REJECTED_INPUT,
                    message = "这次无法完成",
                    retryable = false,
                )
                else -> null
            },
            createdAtEpochMillis = request.occurredAtEpochMillis,
            updatedAtEpochMillis = request.occurredAtEpochMillis + stateVersion,
        )
    }

    protected class RecordingTutorInteractions : TutorInteractionRepository {
        val responses = MutableStateFlow<List<TutorTurnResponse>>(emptyList())
        var moveCommand: RecordTutorMoveCommand? = null
        var revealCommand: RevealTutorSolutionCommand? = null
        val revealCommands = mutableListOf<RevealTutorSolutionCommand>()
        val exposureCommands = mutableListOf<RecordTutorSolutionExposureCommand>()
        val recordedExposureKeys = linkedSetOf<String>()
        val writeOrder = mutableListOf<String>()

        override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> = responses

        override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse =
            TutorTurnResponse(
                sessionId = command.sessionId,
                questionDocumentId = command.questionDocumentId,
                revisionNumber = command.revisionNumber,
                cycleOrdinal = command.cycleOrdinal,
                turnOrdinal = command.turnOrdinal,
                diagnosticStemMarkdown = command.diagnosticStemMarkdown,
                selectedChoiceId = command.selectedChoiceId,
                selectedChoiceMarkdown = command.selectedChoiceMarkdown,
                selectionWasCorrect = command.selectionWasCorrect,
                feedbackMarkdown = command.feedbackMarkdown,
                requestedMove = null,
                solutionRevealed = false,
                submittedAtEpochMillis = command.occurredAtEpochMillis,
                updatedAtEpochMillis = command.occurredAtEpochMillis,
            ).also { response ->
                responses.value = listOf(response)
            }

        override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse {
            moveCommand = command
            val current = responses.value.singleOrNull { response ->
                response.sessionId == command.sessionId &&
                    response.questionDocumentId == command.questionDocumentId &&
                    response.revisionNumber == command.revisionNumber &&
                    response.cycleOrdinal == command.cycleOrdinal &&
                    response.turnOrdinal == command.turnOrdinal
            }
            return (
                current?.copy(
                    requestedMove = command.requestedMove,
                    updatedAtEpochMillis = maxOf(
                        current.updatedAtEpochMillis,
                        command.occurredAtEpochMillis,
                    ),
                ) ?: action(command)
                ).also { response -> responses.value = listOf(response) }
        }

        override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse {
            revealCommand = command
            revealCommands += command
            writeOrder += "reveal"
            val revealed = revealResponse(
                sessionId = command.sessionId,
                questionDocumentId = command.questionDocumentId,
                revisionNumber = command.revisionNumber,
                cycleOrdinal = command.cycleOrdinal,
                turnOrdinal = command.turnOrdinal,
                occurredAtEpochMillis = command.occurredAtEpochMillis,
            )
            responses.value = listOf(revealed)
            return revealed
        }

        override suspend fun recordSolutionExposure(command: RecordTutorSolutionExposureCommand) {
            if (command.surfaceKind == TutorAnswerExposureSurfaceKind.PLAN_SOLUTION) {
                require(
                    responses.value.any { response ->
                        response.sessionId == command.sessionId &&
                            response.questionDocumentId == command.questionDocumentId &&
                            response.revisionNumber == command.revisionNumber &&
                            response.cycleOrdinal == command.cycleOrdinal &&
                            response.turnOrdinal == command.turnOrdinal &&
                            response.solutionRevealed
                    },
                ) { "A plan exposure requires the exact solution reveal first" }
            }
            exposureCommands += command
            writeOrder += "exposure"
            recordedExposureKeys += "${command.sessionId}:${command.cycleOrdinal}:${command.turnOrdinal}"
        }

        private fun revealResponse(
            sessionId: String,
            questionDocumentId: String,
            revisionNumber: Int,
            cycleOrdinal: Int,
            turnOrdinal: Int,
            occurredAtEpochMillis: Long,
        ): TutorTurnResponse {
            val current = responses.value.singleOrNull()
            return current?.copy(
                solutionRevealed = true,
                updatedAtEpochMillis = maxOf(current.updatedAtEpochMillis, occurredAtEpochMillis),
            ) ?: TutorTurnResponse(
                sessionId = sessionId,
                questionDocumentId = questionDocumentId,
                revisionNumber = revisionNumber,
                cycleOrdinal = cycleOrdinal,
                turnOrdinal = turnOrdinal,
                diagnosticStemMarkdown = null,
                selectedChoiceId = null,
                selectedChoiceMarkdown = null,
                selectionWasCorrect = null,
                feedbackMarkdown = null,
                solutionRevealed = true,
                submittedAtEpochMillis = occurredAtEpochMillis,
                updatedAtEpochMillis = occurredAtEpochMillis,
            )
        }

        private fun action(command: RecordTutorMoveCommand) = TutorTurnResponse(
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            diagnosticStemMarkdown = null,
            selectedChoiceId = null,
            selectedChoiceMarkdown = null,
            selectionWasCorrect = null,
            feedbackMarkdown = null,
            requestedMove = command.requestedMove,
            submittedAtEpochMillis = command.occurredAtEpochMillis,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        )
    }
}
