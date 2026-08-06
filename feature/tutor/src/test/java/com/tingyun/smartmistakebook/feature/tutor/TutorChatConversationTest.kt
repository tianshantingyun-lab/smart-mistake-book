package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
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
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.locallyConstrainedFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorChatConversationTest {
    @Test
    fun onlyRetryableFailureCanRetryTheSameTutorResponseRequest() {
        val response = succeededResponse(responseOrdinal = 1)

        assertTrue(
            response.copy(
                status = ModelTaskStatus.RETRYABLE_FAILURE,
                output = null,
                failure = retryableFailure(),
            ).canRetryTutorResponse(),
        )
        assertFalse(
            response.copy(
                status = ModelTaskStatus.PERMANENT_FAILURE,
                output = null,
                failure = retryableFailure().copy(retryable = false),
            ).canRetryTutorResponse(),
        )
        assertFalse(
            response.copy(
                status = ModelTaskStatus.CANCELLED,
                output = null,
            ).canRetryTutorResponse(),
        )
        assertFalse(response.canRetryTutorResponse())
    }

    @Test
    fun retryableResponseCanRetryOnlyInItsCurrentExplanationMode() {
        val directFailure = succeededResponse(
            responseOrdinal = 1,
            explanationMode = TutorExplanationMode.DIRECT,
        ).copy(
            status = ModelTaskStatus.RETRYABLE_FAILURE,
            output = null,
            failure = retryableFailure(),
        )

        assertTrue(directFailure.canRetryTutorResponseFor(TutorExplanationMode.DIRECT))
        assertFalse(directFailure.canRetryTutorResponseFor(TutorExplanationMode.GUIDED))
    }

    @Test
    fun exactLocalDirectIntentMakesOnlyThatGuidedTurnDirect() {
        assertEquals(
            TutorExplanationMode.DIRECT,
            tutorResponseModeFor(
                currentMode = TutorExplanationMode.GUIDED,
                studentMessage = "  直接讲  ",
            ),
        )
        assertEquals(
            TutorExplanationMode.GUIDED,
            tutorResponseModeFor(
                currentMode = TutorExplanationMode.GUIDED,
                studentMessage = "请不要直接讲",
            ),
        )
        assertEquals(
            TutorExplanationMode.GUIDED,
            tutorResponseModeFor(
                currentMode = TutorExplanationMode.GUIDED,
                studentMessage = "“直接讲”是什么意思？",
            ),
        )
        val explicitDirectFailure = succeededResponse(
            responseOrdinal = 1,
            studentMessage = "直接讲",
            explanationMode = TutorExplanationMode.DIRECT,
        ).copy(
            status = ModelTaskStatus.RETRYABLE_FAILURE,
            output = null,
            failure = retryableFailure(),
        )

        assertTrue(
            explicitDirectFailure.canRetryTutorResponseFor(TutorExplanationMode.GUIDED),
        )
    }

    @Test
    fun aTutorResponseOffersOnlyOneDurableRetry() {
        val exhausted = succeededResponse(responseOrdinal = 1).copy(
            status = ModelTaskStatus.RETRYABLE_FAILURE,
            output = null,
            failure = retryableFailure(),
            attemptCount = 2,
        )

        assertFalse(exhausted.canRetryTutorResponse())
        assertFalse(exhausted.canRetryTutorResponseFor(TutorExplanationMode.DIRECT))
    }

    @Test
    fun aLocalTutorResponsePersistsItsOnlyRetryInANewEnvelope() {
        val initial = succeededResponse(
            responseOrdinal = 1,
            requestId = "tutor-respond:fingerprint:1:1:provider:policy:0",
            executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
        ).copy(
            status = ModelTaskStatus.RETRYABLE_FAILURE,
            output = null,
            failure = retryableFailure(),
        )
        val retried = succeededResponse(
            responseOrdinal = 1,
            requestId = "tutor-respond:fingerprint:1:1:provider:policy:1",
            executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
        ).copy(
            status = ModelTaskStatus.RETRYABLE_FAILURE,
            output = null,
            failure = retryableFailure(),
        )

        assertEquals(1, initial.nextLocalTutorResponseRetryAttempt())
        assertFalse(retried.canRetryTutorResponse())
    }

    @Test
    fun activeFailureUsesTheMatchingDurableErrorAndSettingsRecovery() {
        val identity = TutorStreamIdentity(
            requestId = "response-1-attempt-1",
            ownerVersion = 1,
            turnVersion = 1,
            modeVersion = 1,
        )
        val active = TutorActiveStreamMessage(
            studentMessage = "继续",
            ownerVersion = identity.ownerVersion,
            turnVersion = identity.turnVersion,
            modeVersion = identity.modeVersion,
            identity = identity,
            snapshot = TutorMarkdownSnapshot("已保留的安全内容", ""),
            phase = TutorActiveStreamPhase.FAILED,
        )
        val durable = succeededResponse(responseOrdinal = 1).copy(
            status = ModelTaskStatus.PERMANENT_FAILURE,
            output = null,
            failure = ModelTaskFailure(
                code = ModelFailureCode.AUTHENTICATION_FAILED,
                message = "模型认证失败，请检查 API Key",
                retryable = false,
            ),
        )

        val presentation = requireNotNull(
            tutorActiveFailurePresentation(
                message = active,
                durableTask = durable,
                currentMode = TutorExplanationMode.DIRECT,
            ),
        )

        assertEquals("模型认证失败，请检查 API Key", presentation.detail)
        assertEquals(TutorActiveFailureAction.MODEL_SETTINGS, presentation.action)
        assertEquals("已保留的安全内容", active.snapshot?.visibleMarkdown)
    }

    @Test
    fun latestAttemptIsSelectedForEachResponseOrdinal() {
        val olderAttempt = succeededResponse(
            responseOrdinal = 1,
            requestId = "response-1-attempt-1",
            studentMessage = "same exchange",
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 30,
        )
        val newerAttempt = succeededResponse(
            responseOrdinal = 1,
            requestId = "response-1-attempt-2",
            studentMessage = "same exchange",
            createdAtEpochMillis = 20,
            updatedAtEpochMillis = 20,
        )

        val latest = latestTutorRespondTasks(listOf(newerAttempt, olderAttempt))

        assertEquals(listOf("response-1-attempt-2"), latest.map { it.request.requestId })
    }

    @Test
    fun distinctMessagesAtTheSameOrdinalAreNeverCollapsed() {
        val first = succeededResponse(
            responseOrdinal = 1,
            requestId = "response-1-first-message",
            studentMessage = "第一条消息",
            createdAtEpochMillis = 10,
        )
        val second = succeededResponse(
            responseOrdinal = 1,
            requestId = "response-1-second-message",
            studentMessage = "第二条消息",
            createdAtEpochMillis = 20,
        )

        assertEquals(
            listOf("第一条消息", "第二条消息"),
            latestTutorRespondTasks(listOf(second, first)).map {
                (it.request.input as TutorRespondInput).studentMessage
            },
        )
    }

    @Test
    fun distinctExplanationModesAtTheSameOrdinalAreNeverCollapsed() {
        val guided = succeededResponse(
            responseOrdinal = 1,
            requestId = "guided-response",
            explanationMode = TutorExplanationMode.GUIDED,
        )
        val direct = succeededResponse(
            responseOrdinal = 1,
            requestId = "direct-response",
            explanationMode = TutorExplanationMode.DIRECT,
        )

        assertEquals(
            setOf(TutorExplanationMode.GUIDED, TutorExplanationMode.DIRECT),
            latestTutorRespondTasks(listOf(direct, guided)).map {
                (it.request.input as TutorRespondInput).explanationMode
            }.toSet(),
        )
    }

    @Test
    fun pendingResponseRecoveryRequiresTheCurrentExplanationMode() {
        val guided = succeededResponse(
            responseOrdinal = 1,
            requestId = "guided-pending",
            explanationMode = TutorExplanationMode.GUIDED,
        ).copy(
            status = ModelTaskStatus.STREAMING,
            stage = ModelTaskStage.PREPARING,
            output = null,
        )

        assertTrue(guided.isPendingTutorRespondFor(TutorExplanationMode.GUIDED))
        assertFalse(guided.isPendingTutorRespondFor(TutorExplanationMode.DIRECT))
    }

    @Test
    fun latestAttemptsAreOrderedByResponseOrdinal() {
        val tasks = listOf(
            succeededResponse(responseOrdinal = 3),
            succeededResponse(responseOrdinal = 1),
            succeededResponse(responseOrdinal = 2),
        )

        val responseOrdinals = latestTutorRespondTasks(tasks).map {
            (it.request.input as TutorRespondInput).responseOrdinal
        }

        assertEquals(listOf(1, 2, 3), responseOrdinals)
    }

    @Test
    fun historyKeepsOnlyTheMostRecentWholeExchangesWithinTheCountLimit() {
        val taskCount = TutorRespondInput.MAX_PRIOR_MESSAGES + 2
        val tasks = (1..taskCount).map { ordinal ->
            succeededResponse(
                responseOrdinal = ordinal,
                studentMessage = "student-$ordinal",
                assistantMarkdown = completeDirectExplanation("assistant-$ordinal"),
            )
        }

        val history = tutorChatHistory(tasks, answerExposureKeys = answerExposureKeysFor(tasks))

        val firstRetainedOrdinal = taskCount - TutorRespondInput.MAX_PRIOR_MESSAGES + 1
        assertEquals(
            (firstRetainedOrdinal..taskCount).map { ordinal ->
                "student-$ordinal" to completeDirectExplanation("assistant-$ordinal")
            },
            history.map { it.studentMessage to it.assistantMarkdown },
        )
    }

    @Test
    fun historyKeepsMostRecentWholeExchangesWithinTheCharacterLimit() {
        val middleAssistant = completeDirectExplanationOfLength(
            length = TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - 1,
            fill = 'm',
        )
        val newestAssistant = completeDirectExplanationOfLength(
            length = TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - 1,
            fill = 'n',
        )
        val tasks = listOf(
            succeededResponse(
                responseOrdinal = 1,
                studentMessage = "older",
                assistantMarkdown = completeDirectExplanation("older exchange"),
            ),
            succeededResponse(
                responseOrdinal = 2,
                studentMessage = "m",
                assistantMarkdown = middleAssistant,
            ),
            succeededResponse(
                responseOrdinal = 3,
                studentMessage = "n",
                assistantMarkdown = newestAssistant,
            ),
        )

        val history = tutorChatHistory(tasks, answerExposureKeys = answerExposureKeysFor(tasks))

        assertEquals(
            listOf(
                "m" to middleAssistant,
                "n" to newestAssistant,
            ),
            history.map { it.studentMessage to it.assistantMarkdown },
        )
    }

    @Test
    fun historyDoesNotSkipAnOverBudgetExchangeToIncludeAnOlderOne() {
        val almostMaximumAssistant = completeDirectExplanationOfLength(
            length = TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - 1,
            fill = 'n',
        )
        val tasks = listOf(
            succeededResponse(
                responseOrdinal = 1,
                studentMessage = "old",
                assistantMarkdown = completeDirectExplanation("small exchange"),
            ),
            succeededResponse(
                responseOrdinal = 2,
                studentMessage = "mm",
                assistantMarkdown = completeDirectExplanationOfLength(
                    length = TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - 1,
                    fill = 'm',
                ),
            ),
            succeededResponse(
                responseOrdinal = 3,
                studentMessage = "n",
                assistantMarkdown = almostMaximumAssistant,
            ),
        )

        val history = tutorChatHistory(tasks, answerExposureKeys = answerExposureKeysFor(tasks))

        assertEquals(
            listOf("n" to almostMaximumAssistant),
            history.map { it.studentMessage to it.assistantMarkdown },
        )
    }

    @Test
    fun historyPreservesLeadingAndTrailingWhitespaceAndNewlinesExactly() {
        val studentMessage = " \n  Why does this step work?  \n\n"
        val assistantMarkdown =
            "\n  Complete explanation: the sign changes here, so the final result follows.  \n "
        val tasks = listOf(
            succeededResponse(
                responseOrdinal = 1,
                studentMessage = studentMessage,
                assistantMarkdown = assistantMarkdown,
            ),
        )

        val history = tutorChatHistory(
            tasks,
            answerExposureKeys = answerExposureKeysFor(tasks),
        )

        assertEquals(
            listOf(studentMessage to assistantMarkdown),
            history.map { it.studentMessage to it.assistantMarkdown },
        )
    }

    @Test
    fun historyHidesACompleteAnswerUntilItsBottomWasDurablyExposed() {
        val completeAnswer = completeDirectExplanation("逐步计算得到 42")
        val task = succeededResponse(
            responseOrdinal = 1,
            studentMessage = "请告诉我答案",
            assistantMarkdown = completeAnswer,
            solutionRevealed = true,
        )

        val history = tutorChatHistory(listOf(task), answerExposureKeys = emptySet())

        assertEquals("请告诉我答案", history.single().studentMessage)
        assertFalse(history.single().assistantMarkdown.contains("42"))
        assertTrue(history.single().assistantMarkdown.contains("还没有完整看到"))
    }

    @Test
    fun historyKeepsACompleteAnswerAfterItsExactExposureWasRecorded() {
        val completeAnswer = completeDirectExplanation("逐步计算得到 42")
        val task = succeededResponse(
            responseOrdinal = 1,
            studentMessage = "请告诉我答案",
            assistantMarkdown = completeAnswer,
            solutionRevealed = true,
        )
        val exposureKey = requireNotNull(task.toRespondAnswerExposureKey())

        val history = tutorChatHistory(listOf(task), answerExposureKeys = setOf(exposureKey))

        assertEquals(completeAnswer, history.single().assistantMarkdown)
    }

    @Test
    fun exactExposureKeysKeepTwoRevealedRepliesOnTheSameTurnIndependent() {
        val firstCompleteAnswer = completeDirectExplanation("第一个追问已经完整作答")
        val secondCompleteAnswer = completeDirectExplanation("第二个追问已经完整作答")
        val firstReply = succeededResponse(
            responseOrdinal = 1,
            requestId = "same-turn-first-reply",
            studentMessage = "请告诉我答案，先回答第一个追问",
            assistantMarkdown = firstCompleteAnswer,
            solutionRevealed = true,
        )
        val secondReply = succeededResponse(
            responseOrdinal = 2,
            requestId = "same-turn-second-reply",
            studentMessage = "请告诉我答案，再回答第二个追问",
            assistantMarkdown = secondCompleteAnswer,
            solutionRevealed = true,
        )
        val firstExposureKey = requireNotNull(firstReply.toRespondAnswerExposureKey())

        val history = tutorChatHistory(
            tasks = listOf(secondReply, firstReply),
            answerExposureKeys = setOf(firstExposureKey),
        )

        assertEquals(firstCompleteAnswer, history[0].assistantMarkdown)
        assertFalse(history[1].assistantMarkdown.contains(secondCompleteAnswer))
        assertTrue(history[1].assistantMarkdown.contains("还没有完整看到"))
        assertFalse(firstExposureKey == secondReply.toRespondAnswerExposureKey())
    }

    @Test
    fun nextCycleMessagesComeFromPersistedRequestsEvenWhenTheModelOmitsAReply() {
        val exactFirst = "  我卡在配方法第二步\n"
        val exactSecond = "为什么这里要同时加上 4？  "
        val omittedReply = succeededResponse(
            responseOrdinal = 2,
            studentMessage = exactSecond,
        ).copy(
            status = ModelTaskStatus.CANCELLED,
            output = null,
        )

        assertEquals(
            listOf(exactFirst),
            priorCycleStudentMessages(
                listOf(
                    omittedReply,
                    succeededResponse(responseOrdinal = 1, studentMessage = exactFirst),
                ),
            ),
        )
    }

    @Test
    fun nextCycleMessagesKeepOnlyTheNewestContiguousSuffix() {
        val tasks = listOf(1, 3, 4).map { ordinal ->
            succeededResponse(responseOrdinal = ordinal, studentMessage = "student-$ordinal")
        }

        assertEquals(
            listOf("student-3", "student-4"),
            priorCycleStudentMessages(tasks.reversed()),
        )
    }

    @Test
    fun nextCycleMessageTruncationIsStableByCountAndWholeMessageBudget() {
        val lastOrdinal = TutorPlanInput.MAX_PRIOR_CYCLE_STUDENT_MESSAGES + 2
        val countLimited = (1..lastOrdinal)
            .map { ordinal ->
                succeededResponse(responseOrdinal = ordinal, studentMessage = "student-$ordinal")
            }
        val firstRetainedOrdinal =
            lastOrdinal - TutorPlanInput.MAX_PRIOR_CYCLE_STUDENT_MESSAGES + 1
        assertEquals(
            (firstRetainedOrdinal..lastOrdinal)
                .map { ordinal -> "student-$ordinal" },
            priorCycleStudentMessages(countLimited.shuffled(kotlin.random.Random(7))),
        )

        val fullMessage = "问".repeat(TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS)
        val characterLimited = (1..6).map { ordinal ->
            succeededResponse(responseOrdinal = ordinal, studentMessage = "$ordinal${fullMessage.drop(1)}")
        }
        assertEquals(
            (2..6).map { ordinal -> "$ordinal${fullMessage.drop(1)}" },
            priorCycleStudentMessages(characterLimited.reversed()),
        )
    }

    private fun succeededResponse(
        responseOrdinal: Int,
        requestId: String = "response-$responseOrdinal-attempt-1",
        studentMessage: String = "student-$responseOrdinal",
        assistantMarkdown: String =
            "完整讲解如下：第 $responseOrdinal 次回复包含完整推导和最终答案。",
        explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
        solutionRevealed: Boolean = explanationMode == TutorExplanationMode.DIRECT,
        executionLocation: ModelExecutionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        createdAtEpochMillis: Long = responseOrdinal.toLong(),
        updatedAtEpochMillis: Long = createdAtEpochMillis,
    ): ModelTaskSnapshot {
        val question = currentQuestion().toTutorQuestionContext()
        val provider = provider(executionLocation)
        val request = buildTutorRespondRequest(
            question = question,
            profile = StudyProfileOverview(),
            provider = provider,
            requestId = requestId,
            occurredAtEpochMillis = createdAtEpochMillis,
            approvedAtEpochMillis = createdAtEpochMillis,
            responseOrdinal = responseOrdinal,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = studentMessage,
            visibleTutorContextMarkdown = null,
            priorMessages = emptyList(),
            explanationMode = explanationMode,
        )
        val constrainedOutput =
            checkNotNull(
                TutorRespondOutput(
                    sessionId = question.sessionId,
                    draftRevisionNumber = question.revisionNumber,
                    questionDocumentId = question.questionDocument.document.id,
                    responseOrdinal = responseOrdinal,
                    messageMarkdown =
                        if (explanationMode == TutorExplanationMode.GUIDED) {
                            com.tingyun.smartmistakebook.core.model.GUIDED_INTERACTION_MESSAGE
                        } else {
                            assistantMarkdown
                        },
                    responseIntent =
                        if (explanationMode == TutorExplanationMode.GUIDED) {
                            com.tingyun.smartmistakebook.core.model.TutorResponseIntent.ASK
                        } else {
                            com.tingyun.smartmistakebook.core.model.TutorResponseIntent.EXPLAIN
                        },
                    solutionRevealed = solutionRevealed,
                    interactionDirective =
                        if (explanationMode == TutorExplanationMode.GUIDED) {
                            com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
                                .FreeResponse("请写下你认为关键的关系。")
                        } else {
                            null
                        },
                    intentDecision =
                        TutorIntentDecision(
                            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
                            confidence = 1.0,
                            explicitActionRequest = false,
                            memoryPreference = TutorMemoryPreference.UNCHANGED,
                            requestedLocalCapability = TutorRequestedLocalCapability.NONE,
                        ),
                    modelVersion = "model-v1",
                ).locallyConstrainedFor(request.input as TutorRespondInput),
            )
        return ModelTaskSnapshot(
            taskId = "task-$requestId",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "Tutor response ready",
            attemptCount = 1,
            provider = provider,
            output = constrainedOutput,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun answerExposureKeysFor(
        tasks: List<ModelTaskSnapshot>,
    ) = tasks.mapNotNull(ModelTaskSnapshot::toRespondAnswerExposureKey).toSet()

    private fun completeDirectExplanation(detail: String): String =
        "完整讲解如下：$detail，最终答案已经给出。"

    private fun completeDirectExplanationOfLength(length: Int, fill: Char): String {
        val prefix = "完整讲解如下："
        val suffix = "，最终答案已经给出。"
        require(length >= prefix.length + suffix.length)
        return prefix + fill.toString().repeat(length - prefix.length - suffix.length) + suffix
    }

    private fun provider(
        executionLocation: ModelExecutionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
    ) = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "Compatible model",
        modelId = "model",
        supportedTasks = setOf(ModelTaskKind.TUTOR_RESPOND),
        supportsImageInput = false,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = executionLocation,
        providerConfigurationVersion = "configuration-v1",
    )

    private fun retryableFailure() = ModelTaskFailure(
        code = ModelFailureCode.TIMEOUT,
        message = "暂时没有完成",
        retryable = true,
    )

    private fun currentQuestion() = ConfirmedTutorSession(
        sessionId = "current-question-session",
        draftId = "draft-1",
        draftRevisionNumber = 1,
        subject = "MATH",
        title = "Current question",
        questionDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "current-question-document",
                blocks = listOf(ContentBlock.Paragraph("stem", "Solve the current question")),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "asset-1",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        ),
        sourceImageUri = "file:///private/current-question.jpg",
        createdAtEpochMillis = 1,
        isSaved = false,
        errorBookEntryId = null,
    )
}
