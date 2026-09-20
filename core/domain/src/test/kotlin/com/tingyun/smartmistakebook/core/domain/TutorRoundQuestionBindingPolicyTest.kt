package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorRoundQuestionDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TutorRoundQuestionBindingPolicyTest {

    @Test
    fun `keeps explicit additions ahead of earlier rounds and retrieval`() {
        val menu = TutorRoundQuestionBindingPolicy.assembleCandidates(
            explicitlyAdded = listOf(candidate("p-added", "r-added", "刚加进来的题")),
            previouslyBound = listOf(candidate("p-prev", "r-prev", "上一轮那道题")),
            retrieved = listOf(candidate("p-search", "r-search", "检索命中的题")),
        )

        assertEquals(
            listOf("p-added", "p-prev", "p-search"),
            menu.map(RelatedProblemCandidate::problemId),
        )
    }

    @Test
    fun `deduplicates the same revision and keeps the first occurrence`() {
        val menu = TutorRoundQuestionBindingPolicy.assembleCandidates(
            explicitlyAdded = listOf(candidate("p-1", "r-1", "显式标题")),
            previouslyBound = listOf(candidate("p-1", "r-1", "旧标题")),
            retrieved = listOf(
                candidate("p-1", "r-2", "同一题的另一个版本"),
                candidate("p-1", "r-1", "检索标题"),
            ),
        )

        assertEquals(
            listOf("r-1", "r-2"),
            menu.map(RelatedProblemCandidate::problemRevisionId),
        )
        assertEquals("显式标题", menu.first().title)
    }

    @Test
    fun `caps the menu at eight candidates`() {
        val menu = TutorRoundQuestionBindingPolicy.assembleCandidates(
            explicitlyAdded = emptyList(),
            previouslyBound = emptyList(),
            retrieved = (1..12).map { index -> candidate("p-$index", "r-$index", "题 $index") },
        )

        assertEquals(8, menu.size)
        assertEquals(
            (1..8).map { "p-$it" },
            menu.map(RelatedProblemCandidate::problemId),
        )
    }

    @Test
    fun `a missing declaration is a no-question round`() {
        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                declaration = null,
                studentMessage = "光的折射实验再讲一遍",
            ),
        )
    }

    @Test
    fun `a declaration outside the menu is rejected`() {
        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                declaration = declaration("p-outside", "r-outside", "光的折射"),
                studentMessage = "光的折射实验再讲一遍",
            ),
        )
    }

    @Test
    fun `a declaration naming another revision of a menu problem is rejected`() {
        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                declaration = declaration("p-1", "r-9", "光的折射"),
                studentMessage = "光的折射实验再讲一遍",
            ),
        )
    }

    @Test
    fun `an anchor the student never wrote is rejected`() {
        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                declaration = declaration("p-1", "r-1", "全反射临界角"),
                studentMessage = "光的折射实验再讲一遍",
            ),
        )
    }

    @Test
    fun `an anchor that does not come from the question itself is rejected`() {
        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                declaration = declaration("p-1", "r-1", "再讲一遍"),
                studentMessage = "光的折射实验再讲一遍",
            ),
        )
    }

    @Test
    fun `a declaration without anchor terms is rejected`() {
        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                declaration = declaration("p-1", "r-1"),
                studentMessage = "光的折射实验再讲一遍",
            ),
        )
    }

    @Test
    fun `a one-character anchor cannot carry a binding`() {
        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                declaration = declaration("p-1", "r-1", "光"),
                studentMessage = "光的折射实验再讲一遍",
            ),
        )
    }

    @Test
    fun `an empty menu can never bind`() {
        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = emptyList(),
                declaration = declaration("p-1", "r-1", "光的折射"),
                studentMessage = "光的折射实验再讲一遍",
            ),
        )
    }

    @Test
    fun `a menu candidate anchored verbatim in the student message binds`() {
        val candidates = listOf(
            candidate("p-1", "r-1", "光的折射实验"),
            candidate("p-2", "r-2", "凸透镜成像规律"),
        )

        val bound = TutorRoundQuestionBindingPolicy.resolve(
            candidates = candidates,
            declaration = declaration("p-2", "r-2", "凸透镜成像"),
            studentMessage = "凸透镜成像规律这道题的第二步为什么倒过来",
        )

        assertEquals("p-2", bound?.problemId)
        assertEquals("r-2", bound?.problemRevisionId)
    }

    @Test
    fun `every declared anchor must appear verbatim in the student message`() {
        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(candidate("p-2", "r-2", "凸透镜成像规律")),
                declaration = declaration("p-2", "r-2", "凸透镜成像", "焦距"),
                studentMessage = "凸透镜成像规律这道题的第二步为什么倒过来",
            ),
        )
    }

    @Test
    fun `an anchor found in the question body but not in its title binds`() {
        val candidates = listOf(
            candidate(
                problemId = "p-3",
                problemRevisionId = "r-3",
                title = "第 12 题",
                questionMarkdown = "求斜面上物体所受的摩擦力",
            ),
        )

        val bound = TutorRoundQuestionBindingPolicy.resolve(
            candidates = candidates,
            declaration = declaration("p-3", "r-3", "摩擦力"),
            studentMessage = "第 12 题这道题的摩擦力怎么算",
        )

        assertEquals("p-3", bound?.problemId)
    }

    @Test
    fun `the declaration is rejected when the anchor belongs to no part of the question`() {
        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(
                    candidate(
                        problemId = "p-4",
                        problemRevisionId = "r-4",
                        title = "第 4 题",
                        questionMarkdown = "求斜面上物体所受的摩擦力",
                    ),
                ),
                declaration = declaration("p-4", "r-4", "磁场"),
                studentMessage = "第 4 题里的磁场方向",
            ),
        )
    }

    @Test
    fun `a round whose reply carries no binding is not the previous bound question`() {
        // output.boundQuestion 是**校验过**的绑定；它为 null（无题轮：声明缺失、越界或锚词对不上）
        // 时，这一轮不能成为下一轮的"上一轮绑定的题"——那会把无题轮当成有题轮顺延下去。
        val tasks = listOf(
            respondTask(createdAtEpochMillis = 1, boundQuestion = null),
        )

        assertNull(previousBoundRoundQuestion(tasks))
    }

    @Test
    fun `the most recent bound round supplies the previous bound question`() {
        val tasks = listOf(
            respondTask(
                createdAtEpochMillis = 1,
                boundQuestion = declaration("p-1", "r-1", "光的折射"),
            ),
        )

        assertEquals("p-1", previousBoundRoundQuestion(tasks)?.problemId)
    }

    @Test
    fun `the newest binding wins when several rounds are bound`() {
        // 两道不同的题各绑过一轮：取最近那一轮，而不是任意一轮。
        val tasks = listOf(
            respondTask(
                createdAtEpochMillis = 1,
                boundQuestion = declaration("p-1", "r-1", "光的折射"),
                candidate = candidate("p-1", "r-1", "光的折射实验"),
            ),
            respondTask(
                createdAtEpochMillis = 2,
                boundQuestion = declaration("p-2", "r-2", "凸透镜成像"),
                candidate = candidate("p-2", "r-2", "凸透镜成像规律"),
            ),
        )

        assertEquals("p-2", previousBoundRoundQuestion(tasks)?.problemId)
        // 顺序无关：取的是 createdAt 最近的一轮，不是列表末项——查询结果按什么顺序回来
        // 都必须是同一道题，否则"上一轮绑定的题"会随查询计划漂移。
        assertEquals("p-2", previousBoundRoundQuestion(tasks.reversed())?.problemId)
    }

    @Test
    fun `a failed round does not become the previous bound question`() {
        val tasks = listOf(
            respondTask(
                createdAtEpochMillis = 1,
                boundQuestion = declaration("p-1", "r-1", "光的折射"),
                status = ModelTaskStatus.PERMANENT_FAILURE,
            ),
        )

        assertNull(previousBoundRoundQuestion(tasks))
    }

    private fun respondTask(
        createdAtEpochMillis: Long,
        boundQuestion: TutorRoundQuestionDeclaration?,
        status: ModelTaskStatus = ModelTaskStatus.SUCCEEDED,
        candidate: RelatedProblemCandidate = candidate("p-1", "r-1", "光的折射实验"),
    ): ModelTaskSnapshot {
        val input = TutorRespondInput(
            sessionId = "session-1",
            draftRevisionNumber = 1,
            subject = "物理",
            questionDocument = QuestionDocument(
                id = "question-current",
                blocks = listOf(ContentBlock.Paragraph("stem", "题干")),
            ),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            responseOrdinal = createdAtEpochMillis.toInt(),
            studentMessage = "光的折射实验这道题再讲一遍",
            boundQuestionCandidates = listOf(candidate),
        )
        val request = ModelTaskRequest(
            requestId = "request-$createdAtEpochMillis",
            input = input,
            occurredAtEpochMillis = createdAtEpochMillis,
        )
        return ModelTaskSnapshot(
            taskId = "task-$createdAtEpochMillis",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = status,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "完成",
            attemptCount = 1,
            output = if (status == ModelTaskStatus.PERMANENT_FAILURE) {
                null
            } else {
                respondOutput(
                    boundQuestion = boundQuestion,
                    responseOrdinal = input.responseOrdinal,
                )
            },
            failure = if (status == ModelTaskStatus.PERMANENT_FAILURE) {
                ModelTaskFailure(
                    code = ModelFailureCode.UNKNOWN,
                    message = "失败",
                    retryable = false,
                )
            } else {
                null
            },
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
        )
    }

    private fun respondOutput(
        boundQuestion: TutorRoundQuestionDeclaration?,
        responseOrdinal: Int = 1,
    ) = TutorRespondOutput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        questionDocumentId = "question-current",
        responseOrdinal = responseOrdinal,
        messageMarkdown = "先看这一步。",
        boundQuestion = boundQuestion,
        modelVersion = "test-model",
    )

    private fun declaration(
        problemId: String,
        problemRevisionId: String,
        vararg anchorTerms: String,
    ) = TutorRoundQuestionDeclaration(
        problemId = problemId,
        problemRevisionId = problemRevisionId,
        anchorTerms = anchorTerms.toList(),
    )

    private fun respondInput() = TutorRespondInput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        subject = "物理",
        questionDocument = QuestionDocument(
            id = "question-current",
            blocks = listOf(ContentBlock.Paragraph("stem", "题干")),
        ),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
        responseOrdinal = 1,
        studentMessage = "光的折射实验这道题再讲一遍",
        boundQuestionCandidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
    )

    private fun respondOutput(
        boundQuestion: TutorRoundQuestionDeclaration?,
        messageMarkdown: String,
    ) = TutorRespondOutput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        questionDocumentId = "question-current",
        responseOrdinal = 1,
        messageMarkdown = messageMarkdown,
        boundQuestion = boundQuestion,
        modelVersion = "test-model",
    )

    private fun candidate(
        problemId: String,
        problemRevisionId: String,
        title: String,
        questionMarkdown: String = "题干",
    ): RelatedProblemCandidate = RelatedProblemCandidate(
        problemId = problemId,
        problemRevisionId = problemRevisionId,
        subject = SubjectKind.PHYSICS,
        title = title,
        questionDocument = QuestionDocument(
            id = "question-$problemRevisionId",
            title = title,
            blocks = listOf(
                ContentBlock.Paragraph(id = "block-$problemRevisionId", markdown = questionMarkdown),
            ),
        ),
    )
}
