package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.AttachedRoundQuestion
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学生显式添加的题（加号「从错题库选择」）在 Respond 提示词里的落点。
 *
 * 这是"讲的到底是哪一道"的最后一公里：本地请求已经按附加题对齐
 * （`buildTutorRespondRequest`），若提示词仍把会话题的题面写进 `confirmedQuestion`，
 * 模型就会照着一道"本地已判定不是这一轮要讲"的题作答——学生看到界面上写着
 * 「本题：另一道题」，模型却讲旧的。
 *
 * 会话题身份仍在 `input.questionDocument` 里（时间线过滤、唯一槽位、答案暴露守卫按它
 * 匹配），所以这里同时钉住"它没有被渲染成 confirmedQuestion"。
 */
class TutorAttachedQuestionPromptTest {

    private val sessionStem = "会话题题面：求函数的单调区间"
    private val attachedStem = "附加题题面：求自由落体第 2 秒的位移"

    private fun sessionQuestionDocument() = QuestionDocument(
        id = "question-session",
        blocks = listOf(ContentBlock.Paragraph("stem", sessionStem)),
    )

    private fun attachedQuestion() = AttachedRoundQuestion(
        problemId = "problem-attached",
        problemRevisionId = "revision-attached",
        revisionNumber = 2,
        subject = SubjectKind.PHYSICS,
        title = "自由落体位移",
        questionDocument = QuestionDocument(
            id = "question-attached",
            blocks = listOf(ContentBlock.Paragraph("stem-attached", attachedStem)),
        ),
    )

    private fun respond(
        attachedQuestion: AttachedRoundQuestion? = null,
    ) = TutorRespondInput(
        sessionId = "session-1",
        draftRevisionNumber = 2,
        subject = if (attachedQuestion != null) attachedQuestion.subject.name else "MATH",
        questionDocument = sessionQuestionDocument(),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
        responseOrdinal = 3,
        cycleOrdinal = 1,
        turnOrdinal = 3,
        studentMessage = "这一步怎么来的？",
        boundQuestionCandidates = listOfNotNull(attachedQuestion?.toCandidate()),
        knownRoundQuestion = attachedQuestion?.toCandidate(),
        attachedQuestion = attachedQuestion,
    )

    @Test
    fun theRespondPromptRendersTheAttachedQuestionAsTheConfirmedOne() {
        val prompt = OpenAiModelTaskAdapters.prompt(respond(attachedQuestion()))

        assertTrue(
            "confirmedQuestion 必须是附加的那道题，否则模型照着另一道题讲",
            prompt.contains(attachedStem),
        )
        assertFalse(
            "会话题的题面不该出现在这一轮的提示词里：它会让模型以为讲的是会话那道题",
            prompt.contains(sessionStem),
        )
    }

    @Test
    fun aRoundWithoutAnAttachmentStillRendersTheConversationQuestion() {
        // 对照：没有附加题的那一轮照旧渲染会话题题面。没有这条，上面那条断言
        // 可能只是因为提示词根本没渲染题面而已。
        val prompt = OpenAiModelTaskAdapters.prompt(respond())

        assertTrue(prompt.contains(sessionStem))
        assertFalse(prompt.contains(attachedStem))
    }

    @Test
    fun theRespondPromptTellsTheModelTheStudentAttachedThisQuestion() {
        val attachedPrompt = OpenAiModelTaskAdapters.prompt(respond(attachedQuestion()))
        val plainPrompt = OpenAiModelTaskAdapters.prompt(respond())

        assertTrue(
            "要显式说明题是学生这一轮附加的，并给出从候选菜单原样复制 id 的落点",
            attachedPrompt.contains("本轮学生显式附加了这道题"),
        )
        assertFalse(
            "没有附加题的那一轮不许出现这句声明，否则模型会去声明一个不存在的题锚",
            plainPrompt.contains("本轮学生显式附加了这道题"),
        )
    }

    @Test
    fun theAttachedQuestionIsAlsoAMenuCandidateAndCarriesItsOwnSubject() {
        val attached = attachedQuestion()
        val prompt = OpenAiModelTaskAdapters.prompt(respond(attached))

        // 模型只能声明候选菜单里的题：附加题必须在菜单里（id 与 revision 都要在），
        // 否则"声明指向它"这条本地校验永远核不过，本轮就退化成无题轮。
        assertTrue(prompt.contains(attached.problemId))
        assertTrue(prompt.contains(attached.problemRevisionId))
        // 科目也跟着附加题：拿会话题的科目去调证据/资料是错配。
        assertTrue(prompt.contains("科目：${attached.subject.name}"))
    }
}
