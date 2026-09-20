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
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorRoundQuestionDeclaration
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun streamingRespondTaskSurfacesItsLiveStatusText() {
        val streaming = succeededResponse(responseOrdinal = 1, requestId = "response-1-attempt-1")
            .copy(
                status = ModelTaskStatus.STREAMING,
                stateVersion = 2,
                stage = ModelTaskStage.VALIDATING_OUTPUT,
                userMessage = "这道题先看导数变号",
                output = null,
            )

        assertEquals("这道题先看导数变号", streaming.tutorLiveStatusText())
    }

    @Test
    fun generatingLobbyTaskSurfacesItsLiveStatusTextToo() {
        // The lobby streams like the tutor page, and a reasoning model sends its chain-of-thought
        // here first — the student watches it think before the answer lands.
        val lobbyProvider = provider().copy(
            supportedTasks = setOf(ModelTaskKind.TUTOR_LOBBY),
            supportsStreaming = true,
        )
        val lobbyRequest = buildTutorLobbyRequest(
            provider = lobbyProvider,
            conversationId = "tutor-lobby",
            messageOrdinal = 1,
            studentMessage = "帮我看看这道题",
            priorMessages = emptyList(),
            occurredAtEpochMillis = 1,
        )
        val streaming = ModelTaskSnapshot(
            taskId = "task-lobby",
            request = lobbyRequest,
            requestFingerprint = ModelTaskFingerprint.of(lobbyRequest),
            status = ModelTaskStatus.STREAMING,
            stateVersion = 2,
            stage = ModelTaskStage.VALIDATING_OUTPUT,
            userMessage = "先确认题目问的是哪一个量。",
            attemptCount = 1,
            provider = lobbyProvider,
            output = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        assertEquals("先确认题目问的是哪一个量。", streaming.tutorLiveStatusText())
    }

    @Test
    fun onlyAStillGeneratingTaskHasALiveStatusText() {
        // 终态任务不再有"在途"文案：正文已经落库（或失败卡接管），再显示一行状态只会是残留。
        assertNull(succeededResponse(responseOrdinal = 1).tutorLiveStatusText())
        // 生成中的几个状态都有状态行。这两处的状态集此前不一样（大厅认 WAITING/QUEUED/RUNNING/
        // STREAMING，会话只认 STREAMING，还把这一行当回答正文渲染）；合并成一条实时流之后
        // 正文只来自实时通道，快照只提供这一行兜底文案，所以状态集取并集。
        assertNull(
            succeededResponse(responseOrdinal = 2)
                .copy(status = ModelTaskStatus.CANCELLED, output = null, userMessage = "已取消")
                .tutorLiveStatusText(),
        )
        assertEquals(
            "正在准备",
            succeededResponse(responseOrdinal = 2)
                .copy(status = ModelTaskStatus.RUNNING, userMessage = "正在准备")
                .tutorLiveStatusText(),
        )
        assertEquals(
            "排队中",
            succeededResponse(responseOrdinal = 3)
                .copy(status = ModelTaskStatus.QUEUED, userMessage = "排队中")
                .tutorLiveStatusText(),
        )
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
                assistantMarkdown = "assistant-$ordinal",
            )
        }

        val history = tutorChatHistory(tasks, answerExposureKeys = emptySet())

        val firstRetainedOrdinal = taskCount - TutorRespondInput.MAX_PRIOR_MESSAGES + 1
        assertEquals(
            (firstRetainedOrdinal..taskCount).map { ordinal ->
                "student-$ordinal" to "assistant-$ordinal"
            },
            history.map { it.studentMessage to it.assistantMarkdown },
        )
    }

    @Test
    fun historyKeepsMostRecentWholeExchangesWithinTheCharacterLimit() {
        val middleAssistant = "m".repeat(TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - 1)
        val newestAssistant = "n".repeat(TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - 1)
        val tasks = listOf(
            succeededResponse(
                responseOrdinal = 1,
                studentMessage = "older",
                assistantMarkdown = "exchange",
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

        val history = tutorChatHistory(tasks, answerExposureKeys = emptySet())

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
        val almostMaximumAssistant = "n".repeat(TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - 1)
        val tasks = listOf(
            succeededResponse(
                responseOrdinal = 1,
                studentMessage = "old",
                assistantMarkdown = "small exchange",
            ),
            succeededResponse(
                responseOrdinal = 2,
                studentMessage = "mm",
                assistantMarkdown = "m".repeat(TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS - 1),
            ),
            succeededResponse(
                responseOrdinal = 3,
                studentMessage = "n",
                assistantMarkdown = almostMaximumAssistant,
            ),
        )

        val history = tutorChatHistory(tasks, answerExposureKeys = emptySet())

        assertEquals(
            listOf("n" to almostMaximumAssistant),
            history.map { it.studentMessage to it.assistantMarkdown },
        )
    }

    @Test
    fun historyPreservesLeadingAndTrailingWhitespaceAndNewlinesExactly() {
        val studentMessage = " \n  Why does this step work?  \n\n"
        val assistantMarkdown = "\n  Because the sign changes here.  \n "

        val history = tutorChatHistory(
            listOf(
                succeededResponse(
                    responseOrdinal = 1,
                    studentMessage = studentMessage,
                    assistantMarkdown = assistantMarkdown,
                ),
            ),
            answerExposureKeys = emptySet(),
        )

        assertEquals(
            listOf(studentMessage to assistantMarkdown),
            history.map { it.studentMessage to it.assistantMarkdown },
        )
    }

    @Test
    fun historyHidesACompleteAnswerUntilItsBottomWasDurablyExposed() {
        val task = succeededResponse(
            responseOrdinal = 1,
            studentMessage = "请告诉我答案",
            assistantMarkdown = "完整答案是 42",
            solutionRevealed = true,
        )

        val history = tutorChatHistory(listOf(task), answerExposureKeys = emptySet())

        assertEquals("请告诉我答案", history.single().studentMessage)
        assertFalse(history.single().assistantMarkdown.contains("42"))
        assertTrue(history.single().assistantMarkdown.contains("还没有完整看到"))
    }

    @Test
    fun historyNeverLeaksThinkingIntoPriorContext() {
        // T5：thinking 只当轮 UI 呈现，绝不进入 history/priorMessages（否则下一轮模型自见思考）。
        val thinking = "这是内部思考链，不应回传给模型。"
        val task = succeededResponse(
            responseOrdinal = 1,
            studentMessage = "为什么这里要变号？",
            assistantMarkdown = "因为跨过零点后符号改变。",
            thinkingMarkdown = thinking,
        )

        val history = tutorChatHistory(listOf(task), answerExposureKeys = emptySet())

        assertEquals(1, history.size)
        val entry = history.single()
        assertTrue("assistantMarkdown 不得含 thinking", !entry.assistantMarkdown.contains("内部思考链"))
        assertTrue("studentMessage 不得含 thinking", !entry.studentMessage.contains("内部思考链"))
        assertEquals("因为跨过零点后符号改变。", entry.assistantMarkdown)
    }

    @Test
    fun historyKeepsACompleteAnswerAfterItsExactExposureWasRecorded() {
        val task = succeededResponse(
            responseOrdinal = 1,
            studentMessage = "请告诉我答案",
            assistantMarkdown = "完整答案是 42",
            solutionRevealed = true,
        )
        val exposureKey = requireNotNull(task.toRespondAnswerExposureKey())

        val history = tutorChatHistory(listOf(task), answerExposureKeys = setOf(exposureKey))

        assertEquals("完整答案是 42", history.single().assistantMarkdown)
    }

    @Test
    fun exactExposureKeysKeepTwoRevealedRepliesOnTheSameTurnIndependent() {
        val firstReply = succeededResponse(
            responseOrdinal = 1,
            requestId = "same-turn-first-reply",
            studentMessage = "请告诉我答案，先回答第一个追问",
            assistantMarkdown = "第一个完整答案",
            solutionRevealed = true,
        )
        val secondReply = succeededResponse(
            responseOrdinal = 2,
            requestId = "same-turn-second-reply",
            studentMessage = "请告诉我答案，再回答第二个追问",
            assistantMarkdown = "第二个完整答案",
            solutionRevealed = true,
        )
        val firstExposureKey = requireNotNull(firstReply.toRespondAnswerExposureKey())

        val history = tutorChatHistory(
            tasks = listOf(secondReply, firstReply),
            answerExposureKeys = setOf(firstExposureKey),
        )

        assertEquals("第一个完整答案", history[0].assistantMarkdown)
        assertFalse(history[1].assistantMarkdown.contains("第二个完整答案"))
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
            listOf(exactFirst, exactSecond),
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
        assistantMarkdown: String = "assistant-$responseOrdinal",
        solutionRevealed: Boolean = false,
        thinkingMarkdown: String? = null,
        createdAtEpochMillis: Long = responseOrdinal.toLong(),
        updatedAtEpochMillis: Long = createdAtEpochMillis,
        /** 本轮是不是有题轮：这些用例讲的是"当前题"的会话，默认有题；无题轮另有专门用例。 */
        boundQuestion: Boolean = true,
    ): ModelTaskSnapshot {
        val question = currentQuestion().toTutorQuestionContext()
        val provider = provider()
        val bindingCandidate = boundCandidateFor(studentMessage)
        val request = buildTutorRespondRequest(
            question = question,
            profile = StudyProfileOverview(),
            provider = provider,
            requestId = requestId,
            occurredAtEpochMillis = createdAtEpochMillis,
            responseOrdinal = responseOrdinal,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = studentMessage,
            visibleTutorContextMarkdown = null,
            priorMessages = emptyList(),
            boundQuestionCandidates = if (boundQuestion) listOf(bindingCandidate) else emptyList(),
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
            output = TutorRespondOutput(
                sessionId = question.sessionId,
                draftRevisionNumber = question.revisionNumber,
                questionDocumentId = question.questionDocument.document.id,
                responseOrdinal = responseOrdinal,
                messageMarkdown = assistantMarkdown,
                solutionRevealed = solutionRevealed,
                boundQuestion = if (boundQuestion) {
                    TutorRoundQuestionDeclaration(
                        problemId = bindingCandidate.problemId,
                        problemRevisionId = bindingCandidate.problemRevisionId,
                        anchorTerms = listOf(anchorTermFor(studentMessage)),
                    )
                } else {
                    null
                },
                thinkingMarkdown = thinkingMarkdown,
                intentDecision = TutorIntentDecision(
                    intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
                    confidence = 1.0,
                    explicitActionRequest = false,
                    memoryPreference = TutorMemoryPreference.UNCHANGED,
                    requestedLocalCapability = TutorRequestedLocalCapability.NONE,
                ),
                modelVersion = "model-v1",
            ),
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "Compatible model",
        modelId = "model",
        supportedTasks = setOf(ModelTaskKind.TUTOR_RESPOND),
        supportsImageInput = false,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "configuration-v1",
    )

    private fun retryableFailure() = ModelTaskFailure(
        code = ModelFailureCode.TIMEOUT,
        message = "暂时没有完成",
        retryable = true,
    )

    /**
     * 锚词：学生这句话里第一段**连续**的字母/数字/汉字（≥2 字，≤8 字）。
     *
     * 必须是消息里的**连续子串**（逐字锚纪律要求原样出现），且必须是无控制字符的短词
     * （`TutorRoundQuestionDeclaration` 对锚词有长度与字符约束）——所以不能直接拿整句当锚词：
     * 有的用例故意带首尾空白与换行。
     */
    private fun anchorTermFor(studentMessage: String): String =
        ALNUM_RUN.find(studentMessage)?.value?.take(8) ?: "题干"

    /**
     * 本轮菜单里的一条候选：题干里带上锚词，于是"锚词在学生消息里 + 锚词在该题自身文本里"
     * 两条同时成立（与真实派发同构——真实候选来自错题本、题干与学生的话各自独立，但校验规则
     * 一样：同一个词要两边都在）。
     */
    private fun boundCandidateFor(studentMessage: String): RelatedProblemCandidate {
        val anchor = anchorTermFor(studentMessage)
        return RelatedProblemCandidate(
            problemId = "bound-problem-1",
            problemRevisionId = "bound-revision-1",
            subject = SubjectKind.MATH,
            title = "错题本里的一道题",
            questionDocument = QuestionDocument(
                id = "bound-question-1",
                title = "错题本里的一道题",
                blocks = listOf(
                    ContentBlock.Paragraph("bound-stem", "题干：$anchor 的完整表述"),
                ),
            ),
        )
    }

    private companion object {
        // 字母/数字/汉字（Java 正则的 \p{L} 覆盖 CJK），连续 2 字以上。
        val ALNUM_RUN = Regex("[\\p{L}\\p{N}]{2,}")
    }

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
