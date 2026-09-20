package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 轮次号必须**按会话单调**，不能按题派生。
 *
 * 缺陷现场：会话侧序号取"当前这道题的任务"的最大号 +1。可在产品裁定里，一个会话可以跨题
 * （`docs/tutor-surface-unification.md` §5.3：会话不再绑题）。换了题以后 max 从 0 开始，
 * 第二道题的第一轮又分配 1——而
 * - `model_task` 的唯一槽是 `(subject_id, task_kind, tutor_response_ordinal)`，`subject_id`
 *   就是会话 id（`ModelTaskTransactionDao.create` 的槽位校验会抛冲突）；
 * - `tutor_message` 的唯一键是 `(conversation_id, ordinal)`，而消息序号由
 *   `responseOrdinal*2-1` 算出（学生第 n 轮 = 第 2n-1 条）。
 *
 * 所以这里断言的是"会话里第二道题的第一轮不是 1"。
 */
class TutorResponseOrdinalTest {

    @Test
    fun `an empty session starts at the first round`() {
        assertEquals(1, nextTutorResponseOrdinal("session-1", emptyList()))
    }

    @Test
    fun `rounds stay monotonic inside one question`() {
        assertEquals(
            3,
            nextTutorResponseOrdinal(
                "session-1",
                listOf(respondTask("session-1", "question-a", 1), respondTask("session-1", "question-a", 2)),
            ),
        )
    }

    @Test
    fun `a second question in the same session does not restart at one`() {
        // 这就是撞槽的现场：同一会话里已经聊过 question-a 两轮，现在换到 question-b。
        // 按题派生会得到 1；按会话分配必须得到 3。
        val sessionTasks = listOf(
            respondTask("session-1", "question-a", 1),
            respondTask("session-1", "question-a", 2),
        )

        assertEquals(3, nextTutorResponseOrdinal("session-1", sessionTasks))
    }

    @Test
    fun `non-respond tasks and other sessions do not advance the ordinal`() {
        val sessionTasks = listOf(
            respondTask("session-1", "question-a", 1),
            respondTask("session-1", "question-b", 2),
            planTask("session-1"),
            respondTask("session-2", "question-other", 7),
        )

        assertEquals(3, nextTutorResponseOrdinal("session-1", sessionTasks))
    }

    @Test
    fun `another session's rounds start from its own first round`() {
        // 分配按会话隔离：别人的号不算进本会话，本会话也不会把别人的号推高。
        val sessionTasks = listOf(
            respondTask("session-1", "question-a", 1),
            respondTask("session-2", "question-other", 9),
        )

        assertEquals(2, nextTutorResponseOrdinal("session-1", sessionTasks))
        assertEquals(10, nextTutorResponseOrdinal("session-2", sessionTasks))
    }

    private fun respondTask(
        sessionId: String,
        questionId: String,
        responseOrdinal: Int,
    ): ModelTaskSnapshot = snapshot(
        input = TutorRespondInput(
            sessionId = sessionId,
            draftRevisionNumber = 1,
            subject = "数学",
            questionDocument = QuestionDocument(
                id = questionId,
                blocks = listOf(ContentBlock.Paragraph("stem", "求单调区间")),
            ),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            responseOrdinal = responseOrdinal,
            studentMessage = "这一步怎么来的",
        ),
    )

    /** 计划任务不是 RESPOND：它以非终态出现，因为 SUCCEEDED 的模型任务必须带输出。 */
    private fun planTask(sessionId: String): ModelTaskSnapshot = snapshot(
        status = ModelTaskStatus.RUNNING,
        input = com.tingyun.smartmistakebook.core.model.TutorPlanInput(
            sessionId = sessionId,
            draftRevisionNumber = 1,
            subject = "数学",
            questionDocument = QuestionDocument(
                id = "question-a",
                blocks = listOf(ContentBlock.Paragraph("stem", "求单调区间")),
            ),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
        ),
    )

    private fun snapshot(
        status: ModelTaskStatus = ModelTaskStatus.SUCCEEDED,
        input: com.tingyun.smartmistakebook.core.model.ModelTaskInput,
    ): ModelTaskSnapshot {
        val request = ModelTaskRequest(
            requestId = "request:${input.kind}:${input.subjectId}:${(input as? TutorRespondInput)?.responseOrdinal}",
            input = input,
            occurredAtEpochMillis = 100,
        )
        return ModelTaskSnapshot(
            taskId = "task:${request.requestId}",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = status,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "完成",
            attemptCount = 1,
            provider = provider,
            output = if (input is TutorRespondInput) {
                TutorRespondOutput(
                    sessionId = input.sessionId,
                    draftRevisionNumber = input.draftRevisionNumber,
                    questionDocumentId = input.questionDocument.id,
                    responseOrdinal = input.responseOrdinal,
                    messageMarkdown = "先看临界点两侧的符号。",
                    modelVersion = "model-v1",
                )
            } else {
                null
            },
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 100,
        )
    }

    private companion object {
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
