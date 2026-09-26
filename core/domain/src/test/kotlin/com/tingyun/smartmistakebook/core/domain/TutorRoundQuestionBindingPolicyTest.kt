package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.AttachedRoundQuestion
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * 学生显式附加了题的一轮：附加题就是本轮的题，模型指**别的**候选一律无效（不许切走）。
     *
     * 反证：去掉 pinnedQuestion 这条闸门，下面这份声明会通过全部两条本地校验——候选在菜单内，
     * 锚词"另一道题"既能逐字对上 studentMessage、也能在那个候选自己的标题里找到。
     */
    @Test
    fun `a round that pins the attached question refuses a declaration naming another candidate`() {
        val pinned = candidate("p-added", "r-added", "学生所附的题")

        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(pinned, candidate("p-other", "r-other", "另一道题")),
                declaration = declaration("p-other", "r-other", "另一道题"),
                studentMessage = "另一道题也讲讲",
                pinnedQuestion = pinned,
            ),
        )
    }

    @Test
    fun `a round that pins the attached question keeps the declaration naming it`() {
        val pinned = candidate("p-added", "r-added", "学生所附的题")

        assertEquals(
            "p-added",
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = listOf(pinned, candidate("p-other", "r-other", "另一道题")),
                declaration = declaration("p-added", "r-added", "学生所附的题"),
                studentMessage = "学生所附的题再讲讲",
                pinnedQuestion = pinned,
            )?.problemId,
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
    fun `a subject name is not a verifiable anchor`() {
        // `SubjectKind.name` 是枚举名（MATH/PHYSICS…），与题目内容毫无关系。算进"题自身文本"
        // 的话，学生消息里出现该英文串时任何一条同科目候选都能被"核对"上——菜单八条时模型
        // 可随手挑一条同科目候选、拿科目名当锚词通过，这条闸门就等于没有。
        val candidates = listOf(
            candidate(
                problemId = "p-math",
                problemRevisionId = "r-math",
                title = "第 1 题",
                questionMarkdown = "求函数的单调区间",
                subject = SubjectKind.MATH,
            ),
        )

        assertNull(
            TutorRoundQuestionBindingPolicy.resolve(
                candidates = candidates,
                declaration = declaration("p-math", "r-math", "MATH"),
                studentMessage = "MATH 这道题再讲一遍",
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

    @Test
    fun `a call whose declaration passes the two local checks is anchored`() {
        assertTrue(
            TutorRoundQuestionBindingPolicy.callIsAnchoredToRoundQuestion(
                declaration = declaration("p-1", "r-1", "光的折射"),
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                studentMessage = "光的折射实验这道题再讲一遍",
                knownRoundQuestion = null,
            ),
        )
    }

    @Test
    fun `a call without a declaration falls back to the question the request already knows`() {
        // F1：原生 tool_calls 路由的标准形态 content=null，模型复述题锚的唯一落点是每次调用的
        // arguments（schema 的 required 只约束合规 provider）。"没复述"不等于"这一轮没有题"：
        // 请求侧已知的锚就是这一轮的题，写工具的准入事实必须按它成立。
        assertTrue(
            TutorRoundQuestionBindingPolicy.callIsAnchoredToRoundQuestion(
                declaration = null,
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                studentMessage = "这道题还是不懂",
                knownRoundQuestion = candidate("p-1", "r-1", "光的折射实验"),
            ),
        )
    }

    @Test
    fun `a call without a declaration in a round with no known question stays unanchored`() {
        // 负方向：请求侧也没有（真的无题轮）→ 仍然不认，一行证据都不该写。
        assertFalse(
            TutorRoundQuestionBindingPolicy.callIsAnchoredToRoundQuestion(
                declaration = null,
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                studentMessage = "这道题还是不懂",
                knownRoundQuestion = null,
            ),
        )
    }

    @Test
    fun `a declaration that fails the local checks does not fall back to the known question`() {
        // 说错了 ≠ 没说：模型指了一道本轮菜单外的题，不能拿本地已知锚替它兜底——否则声明越界
        // 反而比不声明更宽松，两条本地校验就白设了。
        assertFalse(
            TutorRoundQuestionBindingPolicy.callIsAnchoredToRoundQuestion(
                declaration = declaration("p-outside", "r-outside", "光的折射"),
                candidates = listOf(candidate("p-1", "r-1", "光的折射实验")),
                studentMessage = "光的折射实验这道题再讲一遍",
                knownRoundQuestion = candidate("p-1", "r-1", "光的折射实验"),
            ),
        )
    }

    @Test
    fun `an explicitly attached question carries over even when the model did not restate it`() {
        // 学生显式附加的题就是本轮的题锚：请求契约保证 knownRoundQuestion 与它同题、且它
        // 在本轮候选菜单内。它不依赖模型复述——模型没复述（原生 tool_calls 路由的常见形态）
        // 时，附加题此前会从下一轮的菜单里静默消失，只剩一条日志。
        val attached = candidate("p-attached", "r-attached", "自由落体位移")
        val task = attachedRespondTask(
            createdAtEpochMillis = 3,
            attached = attached,
            boundQuestion = null,
        )

        assertEquals(attached, previousBoundRoundQuestion(listOf(task)))

        // 反方向不变：模型复述了另一道题时，延续的仍是**已校验的绑定**，不是附加题。
        val declaredTask = attachedRespondTask(
            createdAtEpochMillis = 4,
            attached = attached,
            boundQuestion = declaration("p-other", "r-other", "另一道题"),
            candidate = candidate("p-other", "r-other", "另一道题"),
        )

        assertEquals("p-other", previousBoundRoundQuestion(listOf(declaredTask))?.problemId)
    }

    /** 学生显式附加了一道题的一轮（`buildTutorRespondRequest` 的生产口径：known == attached）。 */
    private fun attachedRespondTask(
        createdAtEpochMillis: Long,
        attached: RelatedProblemCandidate,
        boundQuestion: TutorRoundQuestionDeclaration?,
        candidate: RelatedProblemCandidate = attached,
        status: ModelTaskStatus = ModelTaskStatus.SUCCEEDED,
    ): ModelTaskSnapshot {
        val attachedRoundQuestion = AttachedRoundQuestion(
            problemId = attached.problemId,
            problemRevisionId = attached.problemRevisionId,
            revisionNumber = 2,
            subject = SubjectKind.PHYSICS,
            title = attached.title,
            questionDocument = attached.questionDocument,
        )
        val input = TutorRespondInput(
            sessionId = "session-1",
            draftRevisionNumber = 1,
            subject = SubjectKind.PHYSICS.name,
            questionDocument = QuestionDocument(
                id = "question-current",
                blocks = listOf(ContentBlock.Paragraph("stem", "题干")),
            ),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            responseOrdinal = createdAtEpochMillis.toInt(),
            studentMessage = "讲讲这道题",
            // 组合与生产口径一致：附加题是本轮已知锚（必须在菜单内），模型若要声明别的题，
            // 那道题也必须在菜单内。
            boundQuestionCandidates = listOf(attachedRoundQuestion.toCandidate(), candidate)
                .distinctBy { it.problemId to it.problemRevisionId },
            knownRoundQuestion = attachedRoundQuestion.toCandidate(),
            attachedQuestion = attachedRoundQuestion,
        )
        val request = ModelTaskRequest(
            requestId = "request-attached-$createdAtEpochMillis",
            input = input,
            occurredAtEpochMillis = createdAtEpochMillis,
        )
        return ModelTaskSnapshot(
            taskId = "task-attached-$createdAtEpochMillis",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = status,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "完成",
            attemptCount = 1,
            output = respondOutput(
                boundQuestion = boundQuestion,
                responseOrdinal = input.responseOrdinal,
            ),
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
        )
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
        subject: SubjectKind = SubjectKind.PHYSICS,
    ): RelatedProblemCandidate = RelatedProblemCandidate(
        problemId = problemId,
        problemRevisionId = problemRevisionId,
        subject = subject,
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
