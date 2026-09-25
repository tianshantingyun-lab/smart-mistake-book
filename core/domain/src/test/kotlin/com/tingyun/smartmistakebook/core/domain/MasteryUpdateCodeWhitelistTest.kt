package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AttachedRoundQuestion
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本轮 MASTERY_UPDATE 的代号白名单。
 *
 * 这条规则钉住的是一个**曾经写反的事实**：附加题轮次的"结构上不可写掌握证据"被当成
 * "清空 `input.knowledgeCodes` 就够了"，而工具环的白名单取自**会话级注册表**——
 * 清空输入字段挡不住它。附加轮里模型若凭上一轮的记忆给出会话题的代号，白名单照放，
 * 掌握证据就记在会话题的节点上。
 */
class MasteryUpdateCodeWhitelistTest {

    private val sessionCodes = setOf("K1", "K2")

    @Test
    fun `an explicitly attached question round keeps no knowledge code`() {
        assertEquals(
            emptySet<String>(),
            masteryUpdateCodeWhitelist(
                input = attachedRespondInput(),
                sessionDisclosedCodes = sessionCodes,
            ),
        )
    }

    @Test
    fun `a round about the conversation question keeps the session codes`() {
        assertEquals(
            sessionCodes,
            masteryUpdateCodeWhitelist(
                input = respondInput(),
                sessionDisclosedCodes = sessionCodes,
            ),
        )
    }

    @Test
    fun `a lobby round keeps whatever the caller passes`() {
        // 大厅没有科目上下文与预披露节点：注册表不建，调用方传空集。规则本身不额外分叉——
        // 唯一的分叉是"这一轮讲的是不是另一道题"。
        assertEquals(
            emptySet<String>(),
            masteryUpdateCodeWhitelist(
                input = TutorLobbyInput(conversationId = "conv-1", messageOrdinal = 1, studentMessage = "你好"),
                sessionDisclosedCodes = emptySet(),
            ),
        )
    }

    private fun respondInput() = TutorRespondInput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        subject = "MATH",
        questionDocument = currentDocument(),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
        responseOrdinal = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        studentMessage = "这一步怎么来的？",
    )

    private fun attachedRespondInput(): TutorRespondInput {
        val attached = AttachedRoundQuestion(
            problemId = "problem-attached",
            problemRevisionId = "revision-attached",
            revisionNumber = 2,
            subject = SubjectKind.PHYSICS,
            title = "自由落体位移",
            questionDocument = QuestionDocument(
                id = "question-attached",
                blocks = listOf(ContentBlock.Paragraph("stem-attached", "求第 2 秒的位移。")),
            ),
        )
        return respondInput().copy(
            subject = attached.subject.name,
            boundQuestionCandidates = listOf(attached.toCandidate()),
            knownRoundQuestion = attached.toCandidate(),
            attachedQuestion = attached,
        )
    }

    private fun currentDocument() = QuestionDocument(
        id = "question-current",
        blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
    )
}
