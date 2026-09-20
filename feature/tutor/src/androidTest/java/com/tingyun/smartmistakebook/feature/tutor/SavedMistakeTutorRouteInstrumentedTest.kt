package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationOptions
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationPreparation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionSummary
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationConfirmation
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationSelection
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.RevealTutorSolutionCommand
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorConversationReference
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.AttachedImageKind
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
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D1 / D2 回归：错题讲题页（`SavedMistakeTutorRoute`）的内部接线。
 *
 * 两处症状都是"这条路自己没把东西传下去"，所以断言必须落在**路由自己**身上——渲染
 * `SavedMistakeTutorRoute`，由它内部装配，而不是直接渲染下层组件：
 * - D1 模型要求的配图在会话页没有 `attachedImageResolver`：`TutorChatExchange` 里
 *   `attachedImageResolver?.let { ... }` 整段被跳过，图既不解析也不渲染（只有拍照会话
 *   传了它）。
 * - D2 意图确认按钮：`TutorSessionPanel` 的 `onRequestSave` / `onRequestEnd` 默认 `{}`，
 *   而本路由从未传过——学生点「确认加入错题本」「确认结束且不保存」毫无反应。
 */
@RunWith(AndroidJUnit4::class)
class SavedMistakeTutorRouteInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun modelRequestedFigureIsResolvedAndRenderedOnTheSavedMistakePage() {
        val resolvedImageIds = mutableListOf<String>()
        val fixture = SavedMistakeReplyFixture(attachedImages = listOf(FIGURE))
        setSavedMistakeScreen(
            modelTasks = SavedMistakeModelTasks(fixture),
            attachedImageResolver = { image ->
                resolvedImageIds += image.imageId
                FIGURE_LOCAL_URI
            },
        )

        composeRule.onNodeWithTag("saved_mistake_tutor_screen").assertExists()
        composeRule.onNodeWithTag("tutor_conversation_list")
            .performScrollToNode(hasTestTag("tutor_chat_assistant_1"))
        // 模型要的配图真的被解析、真的画出来了（而不是整段被跳过）。
        composeRule.onNodeWithText(FIGURE_COLLAPSED_TITLE).assertExists()
        assertEquals(listOf(FIGURE.imageId), resolvedImageIds)
    }

    @Test
    fun confirmingTheSaveOfferRunsARealLocalActionOnTheSavedMistakePage() {
        var saveRequests = 0
        val fixture = SavedMistakeReplyFixture(
            studentMessage = "帮我把这道题保存进错题本",
            intentDecision = TutorIntentDecision(
                intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
                confidence = 0.95,
                explicitActionRequest = true,
                memoryPreference = TutorMemoryPreference.UNCHANGED,
                requestedLocalCapability =
                TutorRequestedLocalCapability.OFFER_SAVE_CURRENT_QUESTION,
            ),
        )
        setSavedMistakeScreen(
            modelTasks = SavedMistakeModelTasks(fixture),
            onRequestSave = { saveRequests += 1 },
        )

        composeRule.onNodeWithTag("tutor_conversation_list")
            .performScrollToNode(hasTestTag("tutor_intent_confirm_save"))
        composeRule.onNodeWithTag("tutor_intent_confirm_save").performClick()

        composeRule.runOnIdle { assertEquals(1, saveRequests) }
    }

    @Test
    fun confirmingTheEndOfferRunsARealLocalActionOnTheSavedMistakePage() {
        var endRequests = 0
        val fixture = SavedMistakeReplyFixture(
            studentMessage = "先到这里，结束吧",
            intentDecision = TutorIntentDecision(
                intent = TutorMessageIntent.END_OR_PAUSE,
                confidence = 0.95,
                explicitActionRequest = true,
                memoryPreference = TutorMemoryPreference.UNCHANGED,
                requestedLocalCapability = TutorRequestedLocalCapability.OFFER_END_WITHOUT_SAVE,
            ),
        )
        setSavedMistakeScreen(
            modelTasks = SavedMistakeModelTasks(fixture),
            onRequestEnd = { endRequests += 1 },
        )

        composeRule.onNodeWithTag("tutor_conversation_list")
            .performScrollToNode(hasTestTag("tutor_intent_confirm_end"))
        composeRule.onNodeWithTag("tutor_intent_confirm_end").performClick()

        composeRule.runOnIdle { assertEquals(1, endRequests) }
    }

    private fun setSavedMistakeScreen(
        modelTasks: ModelTaskRepository,
        attachedImageResolver: (suspend (AttachedImage) -> String?)? = null,
        onRequestSave: () -> Unit = {},
        onRequestEnd: () -> Unit = {},
    ) {
        composeRule.setContent {
            SmartMistakeBookTheme {
                SavedMistakeTutorRoute(
                    key = KEY,
                    repository = savedMistakeRepository(),
                    organizationRepository = confirmedOrganizationRepository(),
                    teachingReferenceRepository = TutorTeachingReferenceRepository { _, _ ->
                        emptyList()
                    },
                    modelTasks = modelTasks,
                    interactions = inertInteractions(),
                    profile = StudyProfileOverview(),
                    attachedImageResolver = attachedImageResolver,
                    onRequestSave = onRequestSave,
                    onRequestEnd = onRequestEnd,
                    onOpenModelSettings = {},
                    onBack = {},
                )
            }
        }
    }

    /** 错题讲题页的题面：会话 id 与保存前那次讲题一致（会话页同一来源）。 */
    private fun savedMistakeRepository() = object : MistakeDetailRepository {
        override fun observe(errorBookEntryId: String): Flow<MistakeDetailState> =
            flowOf(readyState())

        override fun observeExact(key: MistakeRevisionKey): Flow<MistakeDetailState> =
            flowOf(readyState())

        override suspend fun readExact(key: MistakeRevisionKey): MistakeDetailState = readyState()

        override fun observeRevisionHistory(
            errorBookEntryId: String,
        ): Flow<List<MistakeRevisionSummary>> = flowOf(emptyList())
    }

    private fun readyState() = MistakeDetailState.Ready(
        detail = MistakeDetail(
            identity = MistakeDetailIdentity(
                errorBookEntryId = KEY.entryId,
                problemId = KEY.problemId,
                problemRevisionId = KEY.problemRevisionId,
                revisionNumber = REVISION,
                title = TITLE,
                subject = SUBJECT,
            ),
            fallbackMarkdown = "备用题面",
            source = MistakeSourceSet.Missing,
            tutorConversation = TutorConversationReference(
                sessionId = SESSION_ID,
                questionRevisionNumber = REVISION,
            ),
        ),
        questionDocument = questionDocument(),
    )

    private fun confirmedOrganizationRepository() = object : MistakeOrganizationRepository {
        override suspend fun prepare(
            key: MistakeRevisionKey,
            profile: StudyProfileOverview,
            provider: ProviderCapabilitySnapshot,
            attempt: Int,
            occurredAtEpochMillis: Long,
            approvedAtEpochMillis: Long,
        ): MistakeOrganizationPreparation = error("No organization round expected")

        override fun observeConfirmed(
            key: MistakeRevisionKey,
        ): Flow<ConfirmedMistakeOrganization> = flowOf(ConfirmedMistakeOrganization())

        override suspend fun applySuccessfulOrganization(
            requestId: String,
        ): ProblemOrganizationConfirmation = error("No organization write expected")

        override suspend fun confirm(
            requestId: String,
            selection: ProblemOrganizationSelection,
            acceptedAtEpochMillis: Long,
        ): ProblemOrganizationConfirmation = error("No organization write expected")

        override suspend fun correctConfirmedOrganization(
            key: MistakeRevisionKey,
            selection: ProblemOrganizationSelection,
            correctedAtEpochMillis: Long,
        ): ProblemOrganizationConfirmation = error("No organization write expected")

        override fun observeOrganizationOptions(
            key: MistakeRevisionKey,
        ): Flow<MistakeOrganizationOptions> = flowOf(
            MistakeOrganizationOptions(
                subject = SUBJECT,
                chapters = emptyList(),
                knowledgeNodes = emptyList(),
            ),
        )
    }

    private fun inertInteractions() = object : TutorInteractionRepository {
        override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> = flowOf(emptyList())

        override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse =
            error("No choice write expected")

        override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse =
            error("No move write expected")

        override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse =
            error("No reveal expected")

        override suspend fun recordSolutionExposure(command: RecordTutorSolutionExposureCommand) =
            error("No exposure write expected")
    }

    private class SavedMistakeModelTasks(fixture: SavedMistakeReplyFixture) : ModelTaskRepository {
        private val planTask = planSnapshot()
        private val replyTask = replySnapshot(fixture)

        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(
            listOf(planTask, replyTask).firstOrNull { it.request.requestId == requestId },
        )

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = when (kind) {
            ModelTaskKind.TUTOR_PLAN -> flowOf(listOf(planTask))
            ModelTaskKind.TUTOR_RESPOND -> flowOf(listOf(replyTask))
            else -> flowOf(emptyList())
        }

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
            error("The saved-mistake page must not dispatch a new turn on its own")
    }

    private companion object {
        const val SESSION_ID = "tutor-session-before-save"
        const val REVISION = 2
        const val SUBJECT = "MATH"
        const val TITLE = "求函数的单调区间"
        const val FIGURE_LOCAL_URI = "file:///tmp/saved-mistake-figure.png"
        const val FIGURE_DESCRIPTION = "数轴上的符号变化"
        // 折叠标题 = "过程图 · " + description.take(18)。
        const val FIGURE_COLLAPSED_TITLE = "过程图 · 数轴上的符号变化"

        val KEY = MistakeRevisionKey(
            entryId = "entry-1",
            problemId = "problem-1",
            problemRevisionId = "revision-1",
        )

        val FIGURE = AttachedImage(
            imageId = "figure-1",
            kind = AttachedImageKind.GENERATE_PROCESS,
            description = FIGURE_DESCRIPTION,
            accessibilityText = "解题过程图",
        )

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

        fun questionDocument() = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-1",
                title = TITLE,
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
        )

        fun questionContext() = TutorQuestionContext(
            sessionId = SESSION_ID,
            revisionNumber = REVISION,
            subject = SUBJECT,
            title = TITLE,
            questionDocument = questionDocument(),
        )

        fun planSnapshot(): ModelTaskSnapshot {
            val request = buildTutorPlanRequest(
                question = questionContext(),
                profile = StudyProfileOverview(),
                provider = provider,
                requestId = "tutor-plan-before-save",
                occurredAtEpochMillis = 100,
            )
            return ModelTaskSnapshot(
                taskId = "task:${request.requestId}",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                status = ModelTaskStatus.SUCCEEDED,
                stateVersion = 1,
                stage = ModelTaskStage.COMPLETE,
                userMessage = "讲解已准备好",
                attemptCount = 1,
                provider = provider,
                output = TutorPlanOutput(
                    sessionId = SESSION_ID,
                    draftRevisionNumber = REVISION,
                    questionDocumentId = "document-1",
                    plan = TutorTurnPlan(
                        openingMarkdown = "先判断导数的正负变化。",
                        solutionMarkdown = "完整主解法内容",
                        alternateMethodMarkdown = "符号表替代解法内容",
                        difficultyReasonMarkdown = "用于区分符号对应和变号遗漏。",
                        targetedEvidenceLabels = emptyList(),
                        inferredKnowledgeLabels = listOf("导数"),
                    ),
                    modelVersion = "model-v1",
                ),
                createdAtEpochMillis = 100,
                updatedAtEpochMillis = 100,
            )
        }

        fun replySnapshot(fixture: SavedMistakeReplyFixture): ModelTaskSnapshot {
            val request = buildTutorRespondRequest(
                question = questionContext(),
                profile = StudyProfileOverview(),
                provider = provider,
                requestId = "tutor-respond-before-save",
                occurredAtEpochMillis = 200,
                responseOrdinal = 1,
                cycleOrdinal = 1,
                turnOrdinal = 1,
                studentMessage = fixture.studentMessage,
                visibleTutorContextMarkdown = "先判断导数的正负变化。",
                priorMessages = emptyList(),
            )
            return ModelTaskSnapshot(
                taskId = "task:${request.requestId}",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                status = ModelTaskStatus.SUCCEEDED,
                stateVersion = 2,
                stage = ModelTaskStage.COMPLETE,
                userMessage = "回复已准备好",
                attemptCount = 1,
                provider = provider,
                output = fixture.output,
                createdAtEpochMillis = 200,
                updatedAtEpochMillis = 300,
            )
        }
    }
}

/** 一轮模型回复：正文 + 学生原话 + 意图判定 + 模型要求的配图。 */
private class SavedMistakeReplyFixture(
    val studentMessage: String = "这题我看懂了",
    intentDecision: TutorIntentDecision = TutorIntentDecision.ambiguousDefault(),
    attachedImages: List<AttachedImage> = emptyList(),
) {
    val output = TutorRespondOutput(
        sessionId = "tutor-session-before-save",
        draftRevisionNumber = 2,
        questionDocumentId = "document-1",
        responseOrdinal = 1,
        messageMarkdown = "先看临界点两侧的符号。",
        intentDecision = intentDecision,
        attachedImages = attachedImages,
        modelVersion = "model-v1",
    )
}
