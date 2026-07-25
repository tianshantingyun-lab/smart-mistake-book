package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
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
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorConversationTimelineTest {
    private val question = question()

    @Test
    fun planRetriesKeepOnlyTheNewestAttemptForEachCycleAndTurn() {
        val oldAttempt = planTask(
            requestId = "plan-1-old",
            occurredAtEpochMillis = 100,
            createdAtEpochMillis = 100,
        )
        val newAttempt = planTask(
            requestId = "plan-1-new",
            occurredAtEpochMillis = 200,
            createdAtEpochMillis = 200,
        )
        val secondTurn = planTask(
            requestId = "plan-2",
            occurredAtEpochMillis = 300,
            createdAtEpochMillis = 300,
            turnOrdinal = 2,
        )

        val latest = latestTutorPlanTasks(question, listOf(secondTurn, oldAttempt, newAttempt))

        assertEquals(listOf("plan-1-new", "plan-2"), latest.map { it.request.requestId })
    }

    @Test
    fun planChoiceAndReplyUseStableOccurredAtOrderWithoutUpdateTimeReordering() {
        val plan = planTask(
            requestId = "plan",
            occurredAtEpochMillis = 100,
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 9_000,
        )
        val reply = respondTask(
            requestId = "reply",
            occurredAtEpochMillis = 200,
            updatedAtEpochMillis = 8_000,
        )
        val choice = choiceResponse(
            submittedAtEpochMillis = 150,
            choiceSubmittedAtEpochMillis = 300,
            updatedAtEpochMillis = 7_000,
        )

        val timeline = buildTutorConversationTimeline(
            question = question,
            planTasks = listOf(plan),
            respondTasks = listOf(reply),
            responses = listOf(choice),
        )

        assertEquals(
            listOf(
                TutorConversationTimelineItem.Plan::class,
                TutorConversationTimelineItem.Reply::class,
                TutorConversationTimelineItem.ChoiceFeedback::class,
            ),
            timeline.map { it::class },
        )
        assertEquals(listOf(100L, 200L, 300L), timeline.map { it.occurredAtEpochMillis })
    }

    @Test
    fun choicePositionUsesChoiceSubmittedAtEvenWhenAnEarlierActionCreatedTheRow() {
        val response = choiceResponse(
            submittedAtEpochMillis = 50,
            choiceSubmittedAtEpochMillis = 400,
            updatedAtEpochMillis = 900,
        )

        val item = buildTutorConversationTimeline(
            question = question,
            planTasks = listOf(planTask("plan", 100)),
            respondTasks = emptyList(),
            responses = listOf(response),
        ).filterIsInstance<TutorConversationTimelineItem.ChoiceFeedback>().single()

        assertEquals(400L, item.occurredAtEpochMillis)
    }

    @Test
    fun exactQuestionIdentityFiltersPlanReplyAndChoiceTogether() {
        val otherQuestion = question(
            sessionId = question.sessionId,
            revisionNumber = question.revisionNumber + 1,
            documentId = "other-document",
        )
        val ownPlan = planTask("own-plan", 100)
        val otherPlan = planTask("other-plan", 110, target = otherQuestion)
        val ownReply = respondTask("own-reply", 200)
        val otherReply = respondTask("other-reply", 210, target = otherQuestion)
        val ownChoice = choiceResponse(choiceSubmittedAtEpochMillis = 300)
        val otherChoice = choiceResponse(
            choiceSubmittedAtEpochMillis = 310,
            target = otherQuestion,
        )

        val timeline = buildTutorConversationTimeline(
            question = question,
            planTasks = listOf(otherPlan, ownPlan),
            respondTasks = listOf(otherReply, ownReply),
            responses = listOf(otherChoice, ownChoice),
        )

        assertEquals(3, timeline.size)
        assertEquals(
            listOf("plan:1:1:own-plan", "reply:own-reply", "choice:1:1"),
            timeline.map { it.stableId },
        )
    }

    @Test
    fun projectionPrecomputesExactQuestionListsAndTurnLookup() {
        val otherQuestion = question(
            sessionId = "other-session",
            revisionNumber = question.revisionNumber,
            documentId = question.questionDocument.document.id,
        )
        val ownPlan = planTask("own-plan", 100)
        val ownReply = respondTask("own-reply", 200)
        val ownResponse = choiceResponse(choiceSubmittedAtEpochMillis = 300)

        val projection = buildTutorConversationProjection(
            question = question,
            planTasks = listOf(planTask("other-plan", 90, target = otherQuestion), ownPlan),
            respondTasks = listOf(respondTask("other-reply", 190, target = otherQuestion), ownReply),
            responses = listOf(
                choiceResponse(
                    submittedAtEpochMillis = 290,
                    choiceSubmittedAtEpochMillis = 290,
                    target = otherQuestion,
                ),
                ownResponse,
            ),
        )

        assertEquals(listOf(ownPlan), projection.planTasks)
        assertEquals(listOf(ownReply), projection.respondTasks)
        assertEquals(listOf(ownResponse), projection.responses)
        assertEquals(listOf(ownPlan), projection.latestPlanTasks)
        assertEquals(listOf(ownReply), projection.latestRespondTasks)
        assertEquals(1, projection.currentCycle)
        assertSame(ownPlan, projection.observedPlanTask)
        assertSame(ownResponse, projection.responsesByTurn[TutorTurnKey(1, 1)])
        assertEquals(
            listOf("plan:1:1:own-plan", "reply:own-reply", "choice:1:1"),
            projection.timeline.map(TutorConversationTimelineItem::stableId),
        )
    }

    @Test
    fun replyRetriesReuseTheExistingExchangeMergeAndKeepItsNewestAttempt() {
        val oldAttempt = respondTask(
            requestId = "reply-old",
            occurredAtEpochMillis = 200,
            studentMessage = "同一个问题",
        )
        val newAttempt = respondTask(
            requestId = "reply-new",
            occurredAtEpochMillis = 250,
            studentMessage = "同一个问题",
        )

        val reply = buildTutorConversationTimeline(
            question = question,
            planTasks = emptyList(),
            respondTasks = listOf(oldAttempt, newAttempt),
            responses = emptyList(),
        ).single() as TutorConversationTimelineItem.Reply

        assertSame(newAttempt, reply.task)
    }

    @Test
    fun sameMillisecondItemsHaveDeterministicPlanChoiceReplyOrder() {
        val timestamp = 500L
        val timeline = buildTutorConversationTimeline(
            question = question,
            planTasks = listOf(planTask("plan", timestamp)),
            respondTasks = listOf(respondTask("reply", timestamp)),
            responses = listOf(choiceResponse(choiceSubmittedAtEpochMillis = timestamp)),
        )

        assertEquals(
            listOf("plan:1:1:plan", "choice:1:1", "reply:reply"),
            timeline.map { it.stableId },
        )
    }

    @Test
    fun explicitSessionWriteBlockGatesCandidateLookupAndVisibleExposureTogether() {
        val task = respondTask(
            requestId = "blocked-reply",
            occurredAtEpochMillis = 500,
            studentMessage = "这次不要记录，请告诉我答案。",
            solutionRevealed = true,
            intentDecision = blockLongTermWritesDecision(),
        )
        val timeline = listOf(TutorConversationTimelineItem.Reply(task))

        assertTrue(listOf(task).blocksTutorLongTermWrites())
        assertTrue(
            tutorSolutionExposureCandidateKeys(
                timeline = timeline,
                responses = emptyList(),
                longTermWritesBlocked = true,
            ).isEmpty(),
        )
        assertTrue(
            buildTutorSolutionExposureTargets(
                timeline = timeline,
                responses = emptyList(),
                previewKeys = emptySet(),
                longTermWritesBlocked = true,
            ).isEmpty(),
        )
    }

    @Test
    fun replyExposureKeepsExactTaskIdentityAndUsesTheLatestKnownTimestamp() {
        val task = respondTask(
            requestId = "answer-reply",
            occurredAtEpochMillis = 500,
            updatedAtEpochMillis = 800,
            studentMessage = "请告诉我答案。",
            solutionRevealed = true,
        )
        val response = actionResponse(updatedAtEpochMillis = 900, solutionRevealed = true)
        val timeline = listOf(TutorConversationTimelineItem.Reply(task))

        val candidates = tutorSolutionExposureCandidateKeys(
            timeline = timeline,
            responses = listOf(response),
            longTermWritesBlocked = false,
        )
        val target = buildTutorSolutionExposureTargets(
            timeline = timeline,
            responses = listOf(response),
            previewKeys = emptySet(),
            longTermWritesBlocked = false,
        ).single()

        assertEquals(task.toRespondAnswerExposureKey(), candidates.single())
        assertEquals("answer-reply", target.exposureCommand.modelTaskRequestId)
        assertEquals(TutorAnswerExposureSurfaceKind.RESPOND_REPLY, target.exposureCommand.surfaceKind)
        assertEquals(900L, target.notBeforeEpochMillis)
        assertNull(target.pendingRevealCommand)
    }

    @Test
    fun previewedExplanationOnlyPlanCreatesOneDeferredRevealTarget() {
        val task = planTask(
            requestId = "previewed-plan",
            occurredAtEpochMillis = 500,
            updatedAtEpochMillis = 800,
        )
        val timeline = listOf(TutorConversationTimelineItem.Plan(task))
        val previewKey = requireNotNull(task.toPlanSolutionPreviewKey())

        val candidates = tutorSolutionExposureCandidateKeys(
            timeline = timeline,
            responses = emptyList(),
            longTermWritesBlocked = false,
        )
        val target = buildTutorSolutionExposureTargets(
            timeline = timeline,
            responses = emptyList(),
            previewKeys = setOf(previewKey),
            longTermWritesBlocked = false,
        ).single()

        assertTrue(candidates.isEmpty())
        assertEquals(TutorAnswerExposureSurfaceKind.PLAN_SOLUTION, target.exposureCommand.surfaceKind)
        assertEquals("previewed-plan", target.exposureCommand.modelTaskRequestId)
        assertEquals(800L, target.notBeforeEpochMillis)
        assertEquals(1, target.pendingRevealCommand?.turnOrdinal)
    }

    @Test
    fun revealedChoiceUsesItsPlanIdentityAndLatestDurableTimestamp() {
        val task = planTask(
            requestId = "choice-plan",
            occurredAtEpochMillis = 500,
            updatedAtEpochMillis = 800,
            diagnosticItem = diagnosticItem(),
        )
        val response = choiceResponse(
            submittedAtEpochMillis = 700,
            choiceSubmittedAtEpochMillis = 700,
            updatedAtEpochMillis = 900,
            solutionRevealed = true,
        )
        val timeline = buildTutorConversationTimeline(
            question = question,
            planTasks = listOf(task),
            respondTasks = emptyList(),
            responses = listOf(response),
        )

        val candidates = tutorSolutionExposureCandidateKeys(
            timeline = timeline,
            responses = listOf(response),
            longTermWritesBlocked = false,
        )
        val target = buildTutorSolutionExposureTargets(
            timeline = timeline,
            responses = listOf(response),
            previewKeys = emptySet(),
            longTermWritesBlocked = false,
        ).single()

        assertEquals(task.toPlanAnswerExposureKey(), candidates.single())
        assertEquals(TutorAnswerExposureSurfaceKind.PLAN_SOLUTION, target.exposureCommand.surfaceKind)
        assertEquals("choice-plan", target.exposureCommand.modelTaskRequestId)
        assertEquals(900L, target.notBeforeEpochMillis)
        assertNull(target.pendingRevealCommand)
    }

    @Test
    fun taskSnapshotRejectsMemoryBlockAndAnswerExposureWithoutMatchingStudentWords() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            respondTask(
                requestId = "unbound-reply",
                occurredAtEpochMillis = 500,
                studentMessage = "我不记得这一步，而且我觉得这个答案不对。",
                solutionRevealed = true,
                intentDecision = blockLongTermWritesDecision(),
            )
        }

        assertTrue(failure.message.orEmpty().contains("TUTOR_INTENT_BOUNDARY_VIOLATION"))
    }

    private fun planTask(
        requestId: String,
        occurredAtEpochMillis: Long,
        createdAtEpochMillis: Long = occurredAtEpochMillis,
        updatedAtEpochMillis: Long = createdAtEpochMillis,
        turnOrdinal: Int = 1,
        target: TutorQuestionContext = question,
        diagnosticItem: TutorAssessmentItem? = null,
    ): ModelTaskSnapshot {
        val request = buildTutorPlanRequest(
            question = target,
            profile = StudyProfileOverview(),
            provider = provider(),
            requestId = requestId,
            occurredAtEpochMillis = occurredAtEpochMillis,
            approvedAtEpochMillis = occurredAtEpochMillis,
            cycleOrdinal = 1,
            priorConversationMemory = null,
            priorTurns = if (turnOrdinal == 1) emptyList() else listOf(historyEntry()),
        )
        val input = request.input as TutorPlanInput
        val output = TutorPlanOutput(
            sessionId = input.sessionId,
            draftRevisionNumber = input.draftRevisionNumber,
            questionDocumentId = input.questionDocument.id,
            plan = TutorTurnPlan(
                openingMarkdown = "讲解 $requestId",
                diagnosticItem = diagnosticItem,
                solutionMarkdown = "解答",
                alternateMethodMarkdown = "另一种方法",
                difficultyReasonMarkdown = "根据当前题说明。",
                targetedEvidenceLabels = emptyList(),
                inferredKnowledgeLabels = listOf("函数与导数"),
            ),
            modelVersion = "model-v1",
            cycleOrdinal = input.cycleOrdinal,
            turnOrdinal = input.turnOrdinal,
        )
        return ModelTaskSnapshot(
            taskId = "task-$requestId",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "讲解已准备",
            attemptCount = 1,
            provider = provider(),
            output = output,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun respondTask(
        requestId: String,
        occurredAtEpochMillis: Long,
        updatedAtEpochMillis: Long = occurredAtEpochMillis,
        studentMessage: String = "为什么这样做？",
        target: TutorQuestionContext = question,
        solutionRevealed: Boolean = false,
        intentDecision: TutorIntentDecision = TutorIntentDecision.currentQuestionDefault(),
    ): ModelTaskSnapshot {
        val request = buildTutorRespondRequest(
            question = target,
            profile = StudyProfileOverview(),
            provider = provider(),
            requestId = requestId,
            occurredAtEpochMillis = occurredAtEpochMillis,
            approvedAtEpochMillis = occurredAtEpochMillis,
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = studentMessage,
            visibleTutorContextMarkdown = null,
            priorMessages = emptyList(),
        )
        val input = request.input as TutorRespondInput
        return ModelTaskSnapshot(
            taskId = "task-$requestId",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "回复已准备",
            attemptCount = 1,
            provider = provider(),
            output = TutorRespondOutput(
                sessionId = input.sessionId,
                draftRevisionNumber = input.draftRevisionNumber,
                questionDocumentId = input.questionDocument.id,
                responseOrdinal = input.responseOrdinal,
                cycleOrdinal = input.cycleOrdinal,
                turnOrdinal = input.turnOrdinal,
                messageMarkdown = if (solutionRevealed) {
                    "完整解答与最终答案。"
                } else {
                    "因为符号在这里改变。"
                },
                solutionRevealed = solutionRevealed,
                intentDecision = intentDecision,
                modelVersion = "model-v1",
            ),
            createdAtEpochMillis = occurredAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun choiceResponse(
        submittedAtEpochMillis: Long = 300,
        choiceSubmittedAtEpochMillis: Long = 300,
        updatedAtEpochMillis: Long = choiceSubmittedAtEpochMillis,
        target: TutorQuestionContext = question,
        solutionRevealed: Boolean = false,
    ) = TutorTurnResponse(
        sessionId = target.sessionId,
        questionDocumentId = target.questionDocument.document.id,
        revisionNumber = target.revisionNumber,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        diagnosticStemMarkdown = "关键一步是什么？",
        selectedChoiceId = "choice-1",
        selectedChoiceMarkdown = "先判断符号",
        selectionWasCorrect = true,
        feedbackMarkdown = "这个判断正确。",
        submittedAtEpochMillis = submittedAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        choiceSubmittedAtEpochMillis = choiceSubmittedAtEpochMillis,
        solutionRevealed = solutionRevealed,
    )

    private fun actionResponse(
        updatedAtEpochMillis: Long,
        solutionRevealed: Boolean,
    ) = TutorTurnResponse(
        sessionId = question.sessionId,
        questionDocumentId = question.questionDocument.document.id,
        revisionNumber = question.revisionNumber,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        diagnosticStemMarkdown = null,
        selectedChoiceId = null,
        selectedChoiceMarkdown = null,
        selectionWasCorrect = null,
        feedbackMarkdown = null,
        submittedAtEpochMillis = updatedAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        solutionRevealed = solutionRevealed,
    )

    private fun blockLongTermWritesDecision() = TutorIntentDecision(
        intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
        confidence = 1.0,
        explicitActionRequest = true,
        memoryPreference = TutorMemoryPreference.BLOCK_LONG_TERM_WRITES_FOR_SESSION,
        requestedLocalCapability = TutorRequestedLocalCapability.NONE,
    )

    private fun diagnosticItem() = TutorAssessmentItem(
        id = "diagnostic-1",
        stemMarkdown = "关键一步是什么？",
        choices = listOf(
            TutorChoice(
                id = "choice-1",
                markdown = "先判断符号",
                feedbackMarkdown = "这个判断正确。",
            ),
            TutorChoice(
                id = "choice-2",
                markdown = "直接代入计算",
                feedbackMarkdown = "先检查符号会更稳妥。",
            ),
        ),
        correctChoiceId = "choice-1",
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "Compatible model",
        modelId = "model",
        supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN, ModelTaskKind.TUTOR_RESPOND),
        supportsImageInput = false,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "configuration-v1",
    )

    private fun question(
        sessionId: String = "session-1",
        revisionNumber: Int = 2,
        documentId: String = "document-1",
    ) = ConfirmedTutorSession(
        sessionId = sessionId,
        draftId = "draft-$documentId",
        draftRevisionNumber = revisionNumber,
        subject = "MATH",
        title = "当前题目",
        questionDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = documentId,
                blocks = listOf(ContentBlock.Paragraph("stem", "求解当前题目")),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "asset-$documentId",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        ),
        sourceImageUri = "file:///private/$documentId.jpg",
        createdAtEpochMillis = 1,
        isSaved = false,
        errorBookEntryId = null,
    ).toTutorQuestionContext()

    private fun historyEntry() = com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry(
        turnOrdinal = 1,
        diagnosticStemMarkdown = "第一步是什么？",
        selectedChoiceMarkdown = "判断符号",
        selectionWasCorrect = true,
        feedbackMarkdown = "判断正确。",
        requestedMove = com.tingyun.smartmistakebook.core.model.TutorMoveType.CHANGE_REPRESENTATION,
    )
}
